"""The laptop's line to the dashboard's bridge (laptop_link.py on the server).

It is only the meeting place. The laptop announces itself there, so DailyDash can list it and
connect with a tap, and the dashboard's phone page can show what it is doing and ask it for
things (open the phone's window, play its sound, lock, answer a phone waiting to pair).
Screen, sound and touches never go through it.
"""

import asyncio
import json
import secrets
import socket
import threading
import urllib.error
import urllib.request

from . import __version__
from .desk import catalog, hypr, shell
from .config import Store


async def run(*args, **kw):
    from .server import run as _run   # server imports this module, so the helper is fetched late
    return await _run(*args, **kw)


def _http(url: str, body: dict | None = None, headers: dict | None = None, timeout: float = 10) -> dict:
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, headers={
        "Content-Type": "application/json", "User-Agent": f"weave/{__version__}", **(headers or {})})
    with urllib.request.urlopen(req, timeout=timeout) as reply:
        return json.loads(reply.read() or b"{}")


async def _in_thread(fn, *args):
    """Runs a blocking call off the loop, in a thread that never holds up the daemon's exit.

    asyncio's own worker threads are joined on shutdown, and the request held open on the
    bridge can take 25 seconds to come back: a restart would wait for it.
    """
    loop = asyncio.get_running_loop()
    done = loop.create_future()

    def settle(result, error):
        if not done.done():
            done.set_exception(error) if error else done.set_result(result)

    def work():
        try:
            result, error = fn(*args), None
        except Exception as err:
            result, error = None, err
        try:
            loop.call_soon_threadsafe(settle, result, error)
        except RuntimeError:
            pass   # the loop is gone: the daemon is on its way out

    threading.Thread(target=work, daemon=True).start()
    return await done


class Dashboard:
    def __init__(self, daemon):
        self.daemon = daemon
        self.url = str(daemon.cfg.get("dashboard") or "").rstrip("/")
        self.store = Store("link.json", {})
        if not self.store.data.get("id"):
            # made once: the id names this laptop on the dashboard, the secret is the ticket
            # DailyDash shows to be let in without being asked about
            self.store.data = {"id": secrets.token_hex(12), "secret": secrets.token_hex(24)}
            self.store.save()
        self.id, self.secret = self.store.data["id"], self.store.data["secret"]
        self.note = ""                 # why the last thing the page asked for did not happen
        self.online = False
        self._changed = asyncio.Event()
        self._addrs: list[str] = []

    def poke(self):
        """Something the dashboard shows changed: tell it soon."""
        self._changed.set()

    async def _addresses(self) -> list[str]:
        out = []
        try:
            proc = await asyncio.create_subprocess_exec(
                "tailscale", "ip", "-4", stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
            got, _ = await asyncio.wait_for(proc.communicate(), 4)
            out = [line.strip() for line in got.decode().splitlines() if line.strip()]
        except (OSError, asyncio.TimeoutError):
            pass
        return [*out, socket.gethostname()]

    async def run(self):
        if not self.url:
            return
        await asyncio.gather(self._announce(), self._listen())

    async def _announce(self):
        failures, beat = 0, 0
        while True:
            if beat % 20 == 0:
                self._addrs = await self._addresses()
            beat += 1
            d = self.daemon
            await d.phone.refresh()
            await d.phone.read_volume()
            d.mons = await hypr.monitors() or d.mons
            body = {
                "id": self.id, "secret": self.secret, "name": d.host, "addrs": self._addrs,
                "port": d.cfg["port"], "ver": __version__, "state": {**d.link_state(), "note": self.note},
            }
            try:
                reply = await _in_thread(_http, f"{self.url}/_api/link/announce", body)
                if not self.online:
                    print(f"weave: announced to {self.url}")
                self.online, failures = True, 0
                await self._run(reply.get("cmds") or [])
            except (OSError, ValueError, urllib.error.URLError) as err:
                if self.online or failures == 0:
                    print(f"weave: the dashboard did not answer: {err}")
                self.online, failures = False, failures + 1
            try:
                await asyncio.wait_for(self._changed.wait(), 30 if failures < 3 else 120)
            except asyncio.TimeoutError:
                pass
            self._changed.clear()
            await asyncio.sleep(0.25)   # a burst of changes goes out as one announce

    async def _listen(self):
        """Holds a request open on the bridge; it answers the moment the page asks for something."""
        while True:
            if not self.online:
                await asyncio.sleep(3)
                continue
            try:
                reply = await _in_thread(
                    _http, f"{self.url}/_api/link/wait?id={self.id}", None, {"X-Link-Secret": self.secret}, 40)
                await self._run(reply.get("cmds") or [])
            except (OSError, ValueError, urllib.error.URLError):
                await asyncio.sleep(8)

    async def _run(self, cmds: list):
        d = self.daemon
        for c in cmds:
            kind, args = c.get("kind"), c.get("args") or {}
            result = ""
            try:
                if kind == "mirror":
                    result = await d.phone.mirror(bool(args.get("on")))
                elif kind == "audio":
                    result = await d.phone.audio(bool(args.get("on")))
                elif kind == "lock":
                    await d.lock()
                elif kind in ("allow", "deny"):
                    d.decide(kind == "allow")
                elif kind == "forget":
                    await d.forget(args.get("name"))
                elif kind == "kick":
                    for s in [s for s in d.sessions if args.get("name") in (None, s.name)]:
                        await s.close()
                elif kind == "volume":
                    result = await d.phone.set_volume(float(args.get("value") or 0))
                elif kind == "laptop-sound":
                    result = d.set_laptop_sound(bool(args.get("on")))
                elif kind == "config":
                    result = await d.configure(args.get("patch") if isinstance(args.get("patch"), dict) else {})
                elif kind == "media":
                    inj = d.ensure_injector()
                    if inj and args.get("cmd") in ("prev", "play", "next", "mute", "voldown", "volup"):
                        inj.key(args["cmd"])
                elif kind == "clip":
                    await shell.clip_set(str(args.get("text") or ""))
                    d.log("The dashboard put text on the clipboard")
                elif kind == "open":
                    url = str(args.get("url") or "")
                    if url.startswith(("http://", "https://")):
                        await catalog.open_url(url)
                        d.log(f"Opened {url[:80]}")
            except Exception as err:   # one bad command must not end the line to the dashboard
                result = str(err)
            # the phone module answers with a word when it worked and a sentence when it did not
            self.note = result if " " in result and result != "already open" else ""
            self.poke()
