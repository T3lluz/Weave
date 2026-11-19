"""Sound that follows the earbuds (a lab, off until a phone turns it on).

Bluetooth earbuds reach some ten metres from the computer. A phone in the pocket reaches the
computer from anywhere. So when the earbuds' link to this computer gets weak, the phone takes
them over and plays this computer's sound, sent to it the way the remote's sound is. Back in
reach, the computer takes them back.

What does what:
  Judge      reads the link's strength and says when it is about to be out of reach
  Sink       a silent output this computer plays into while the phone has the earbuds
  Hand       a helper on the phone, run over adb, that connects the earbuds there
  Nearness   hears the phone's beacon, to know when it is back beside the computer
  Roam       puts them together

The phone's part is the roam package of the app: a service that plays what this sends, and
the helper `Hand` runs. Linux only (BlueZ, PipeWire).
"""

import asyncio
import collections
import json
import math
import os
import re
import secrets
import shutil
import struct
import time

from . import btlink
from .capture import CONFIG, SoundCapture
from .config import Store
from .proc import run

AUDIO = 3                      # the link's kind byte for sound (server.py)
TICK = 0.25                    # how often the link is read
# How weak is too weak, and for how long, by how early the switch is wanted. The first number is
# for a controller that says how far under its good range the earbuds are heard (most do: 0 is
# fine, negative is too weak), the second for one that says dBm.
SENS = {"early": (-4, -66, 0.8), "balanced": (-8, -72, 1.2), "late": (-13, -78, 2.0)}
ABSOLUTE_BELOW = -25           # a controller whose best reading is under this speaks dBm
SMOOTH = 0.7                   # seconds the strength is averaged over
DEEP = 8                       # this far under the limit twice running: no waiting out the hold
FLOWING = 20                   # packets a second that count as sound being sent
STALL_WINDOW = 0.75            # the sound counts as stuck when, over this long,
STALLED = 0.3                  # less than this share of the usual got out,
STALL_BELOW = -2               # with the link already this weak
BEHIND = 48_000                # unsent sound on a phone's line (some 2.5 s): past it, frames are skipped
SINK = "weave_roam"
TAKE_WAIT = 9.0                # how long the phone gets to take the earbuds
GRACE = 3.0                    # earbuds that left by themselves: how long a phone gets to say it has them
LOST_WAIT = 4.0                # earbuds the phone lost: how long they get to turn up here
LINE_WAIT = 45.0               # a phone gone quiet mid-bridge: how long before the bridge is given up
SETTLE = 15.0                  # after coming back, no handing over again for this long
STAYED = 12.0                  # a phone heard this near, this soon after taking them, never left the desk
STAYED_OFF = 300.0             # and then nothing is handed over by itself for this long
NEAR = -70                     # the phone's beacon this strong (dBm) is beside the computer again,
AWAY_BELOW = 8                 # having been this much weaker, or not heard at all
RETURN_RETRY = (60.0, 600.0)   # a failed try at taking them back waits this long, doubling
HAND_PING = 40.0               # how often the helper on the phone is checked to still be there
BLUEZ = re.compile(r"bluez_(?:output|sink)\.((?:[0-9A-Fa-f]{2}_){5}[0-9A-Fa-f]{2})")
BEACON = b"WV"                 # what the phone's beacon starts with, before the number it was given
BEACON_ID = 0xFFFF             # the manufacturer id set aside for tests, which nothing else on the air uses


def sink_address(name: str) -> str | None:
    """The earbuds an output belongs to, from its name; None for one that isn't Bluetooth."""
    m = BLUEZ.search(name or "")
    return m.group(1).replace("_", ":").upper() if m else None


def limit_for(sens: str, absolute: bool, learned: float | None, fixed: float | None = None) -> float:
    """The strength at which the earbuds count as too far, for this controller's way of counting."""
    if fixed is not None:
        return float(fixed)
    relative, dbm, _ = SENS.get(sens, SENS["balanced"])
    base = float(dbm if absolute else relative)
    # what was learnt (the sound broke up before the limit was reached) only ever moves it earlier
    return max(base, learned) if learned is not None else base


class Judge:
    """Says when the earbuds are about to be out of reach, from readings of their link."""

    def __init__(self, limit: float, hold: float):
        self.limit, self.hold = limit, hold
        self.reset()

    def reset(self):
        self.level: float | None = None    # the strength, smoothed
        self.best: int | None = None       # the strongest single reading
        self.at = 0.0
        self.weak_since: float | None = None
        self.deep = 0
        self.rate: float | None = None     # packets a second just now
        self.usual = 0.0                   # and while the sound flows as it should
        self.window: collections.deque = collections.deque()

    def feed(self, now: float, rssi: int | None, sent: int | None = None) -> str | None:
        """One reading. "weak": under the limit for long enough. "stalled": the sound has
        stopped getting out while the link is weak. None: all is well."""
        verdict = None
        if rssi is not None:
            if self.level is None:
                self.level = float(rssi)
            else:
                self.level += (1 - math.exp(-max(now - self.at, 0.0) / SMOOTH)) * (rssi - self.level)
            self.at = now
            self.best = rssi if self.best is None else max(self.best, rssi)
            self.deep = self.deep + 1 if rssi <= self.limit - DEEP else 0
            if self.level <= self.limit:
                if self.weak_since is None:
                    self.weak_since = now
                if now - self.weak_since >= self.hold:
                    verdict = "weak"
            else:
                self.weak_since = None
            if self.deep >= 2:
                verdict = "weak"
        if sent is not None:
            self.window.append((now, sent))
            while len(self.window) > 1 and now - self.window[1][0] >= STALL_WINDOW:
                self.window.popleft()
            span = now - self.window[0][0]
            if span >= STALL_WINDOW:
                self.rate = (sent - self.window[0][1]) / span
                if self.rate >= FLOWING:
                    self.usual = self.rate if not self.usual else self.usual + (self.rate - self.usual) * 0.1
                elif (self.usual >= FLOWING and self.rate < self.usual * STALLED
                      and self.level is not None and self.level <= STALL_BELOW):
                    verdict = verdict or "stalled"
        return verdict

    def quiet(self):
        """Nothing is playing: what was usual is forgotten, so silence is never taken for a stall."""
        self.usual = 0.0
        self.window.clear()


def _die_with_parent():
    """Runs in a helper just before it starts: if the daemon goes, however it goes, the helper goes
    too. The silent output must never outlive the daemon, or the computer would stay mute."""
    import ctypes
    import signal
    ctypes.CDLL("libc.so.6", use_errno=True).prctl(1, signal.SIGTERM)   # PR_SET_PDEATHSIG


async def _end(proc, wait: float = 1.0):
    if proc is None or proc.returncode is not None:
        return
    try:
        proc.terminate()
        await asyncio.wait_for(proc.wait(), wait)
    except asyncio.TimeoutError:
        proc.kill()
    except ProcessLookupError:
        pass


class Sink:
    """A silent output: what plays here goes nowhere on this computer and is sent to the phone
    instead. It lives exactly as long as the pw-cli that made it, which dies with the daemon."""

    def __init__(self):
        self.proc = None
        self.module: str | None = None   # where there is no pw-cli: a Pulse module, unloaded by hand

    @property
    def up(self) -> bool:
        return (self.proc is not None and self.proc.returncode is None) or self.module is not None

    async def start(self, label: str) -> bool:
        if self.up:
            return True
        label = re.sub(r"[^\w .,()+-]", "", label)[:60] or "Earbuds through the phone"
        if shutil.which("pw-cli"):
            # (the priority puts it above every real output, so that it is what takes over the
            # moment the earbuds' own output goes: nothing is heard from the speakers in between)
            node = ("create-node adapter { factory.name=support.null-audio-sink "
                    f'node.name={SINK} node.description="{label}" media.class=Audio/Sink '
                    "audio.position=[FL,FR] priority.session=30000 monitor.channel-volumes=true "
                    "object.linger=false }\n")
            try:
                self.proc = await asyncio.create_subprocess_exec(
                    "pw-cli", stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.DEVNULL,
                    stderr=asyncio.subprocess.DEVNULL, preexec_fn=_die_with_parent)
                self.proc.stdin.write(node.encode())
                await self.proc.stdin.drain()
            except OSError as err:
                print(f"weave: the silent output couldn't be made: {err}")
                self.proc = None
                return False
        else:
            made = (await run("pactl", "load-module", "module-null-sink", f"sink_name={SINK}",
                              f"sink_properties=device.description='{label}'")).strip()
            if not made.isdigit():
                return False
            self.module = made
        for _ in range(30):
            if f"\t{SINK}\t" in await run("pactl", "list", "short", "sinks", timeout=2):
                return True
            await asyncio.sleep(0.05)
        await self.stop()
        return False

    async def stop(self):
        proc, self.proc = self.proc, None
        await _end(proc)
        if self.module:
            await run("pactl", "unload-module", self.module)
            self.module = None

    @staticmethod
    async def sweep():
        """One left behind by a daemon that died with no pw-cli to take it along."""
        for line in (await run("pactl", "list", "short", "modules", timeout=3)).splitlines():
            if f"sink_name={SINK}" in line:
                await run("pactl", "unload-module", line.split("\t")[0])


class Hand:
    """The helper on the phone that connects the earbuds there. Android lets no ordinary app do
    that, but the phone's adb shell may, so the app's own helper class is run as that shell and
    kept running: one line written to it and the phone reaches for the earbuds at once."""

    PACKAGE = "com.t3lluz.weave"
    MAIN = "com.t3lluz.weave.roam.RoamShell"

    def __init__(self, phone):
        self.phone = phone
        self.proc = None
        self.serial: str | None = None
        self.bonded: set[str] = set()
        self.why = "not looked for yet"
        self.lock = asyncio.Lock()
        self.pinged = 0.0
        self.tried = -1e9
        self.asked = 0

    @property
    def ready(self) -> bool:
        return self.proc is not None and self.proc.returncode is None

    async def ensure(self, addr: str | None = None) -> bool:
        """Starts the helper on the phone that is paired with [addr], unless it runs already."""
        if self.ready:
            return True
        async with self.lock:
            if self.ready:
                return True
            self.tried = time.monotonic()
            if not self.phone.adb_path:
                self.why = "adb isn't installed on this computer"
                return False
            await self.phone.find()
            devices = await self.phone._connected()
            if not devices:
                self.why = "the phone isn't reachable over adb (Wireless debugging)"
                return False
            self.why = "Weave on the phone is older than this, or isn't installed"
            for serial in devices:
                code, out = await self.phone.adb("-s", serial, "shell", "pm", "path", self.PACKAGE, timeout=6)
                apk = next((line[8:].strip() for line in out.splitlines() if line.startswith("package:")), "")
                if code != 0 or not apk:
                    continue
                proc = await asyncio.create_subprocess_exec(
                    self.phone.adb_path, "-s", serial, "shell",
                    f"CLASSPATH={apk} exec app_process / {self.MAIN} serve",
                    stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.DEVNULL, preexec_fn=_die_with_parent)
                try:
                    hello = (await asyncio.wait_for(proc.stdout.readline(), 8)).decode(errors="replace").split()
                except asyncio.TimeoutError:
                    hello = []
                bonded = {a.upper() for a in hello[2:]}
                if hello[:1] != ["ready"] or (addr and addr not in bonded):
                    if hello[:1] == ["ready"]:
                        self.why = "the phone isn't paired with these earbuds"
                    await _end(proc)
                    continue
                self.proc, self.serial, self.bonded = proc, serial, bonded
                self.pinged = time.monotonic()
                self.why = ""
                return True
            return False

    async def ask(self, line: str, timeout: float = 3.0) -> str:
        """One line to the helper and its answer; nothing when it is gone or says nothing in time."""
        if not self.ready:
            return ""
        async with self.lock:
            proc = self.proc
            if proc is None or proc.returncode is not None:
                return ""
            self.asked += 1
            mark = f"{self.asked} "
            try:
                proc.stdin.write((mark + line).encode() + b"\n")
                await proc.stdin.drain()
                until = time.monotonic() + timeout
                while True:
                    # each answer starts with its question's number; anything else is not for us
                    said = (await asyncio.wait_for(proc.stdout.readline(), max(until - time.monotonic(), 0.05))).decode(errors="replace")
                    if not said:
                        raise OSError("the helper ended")
                    if said.startswith(mark):
                        return said[len(mark):].strip()
            except (asyncio.TimeoutError, OSError):
                self.proc = None
                self.why = "the phone stopped answering over adb"
                await _end(proc)
                return ""

    async def take(self, addr: str) -> bool:
        """The phone wakes its player and connects the earbuds to itself."""
        return (await self.ask(f"take {addr}")).startswith("ok")

    async def keep(self):
        """Checked now and then, so a helper lost to a new network is noticed before it is needed."""
        if self.ready and time.monotonic() - self.pinged > HAND_PING:
            self.pinged = time.monotonic()
            await self.ask("ping")

    async def close(self):
        proc, self.proc = self.proc, None
        await _end(proc)


def parse_beacon(line: str, want: bytes) -> tuple[str, bool | None, int | None] | None:
    """One line of `gdbus monitor` about a Bluetooth device near by: its path, whether it carries
    the beacon [want] (None when the line says nothing of that), and how strongly it was heard.
    A device BlueZ has stopped hearing and taken off its list carries no beacon any more."""
    m = re.search(r"(/org/bluez/hci\d+/dev_[0-9A-F_]{17})", line)
    if not m:
        return None
    if "InterfacesRemoved" in line:
        return m.group(1), False, None
    ours = None
    data = re.search(rf"uint16 {BEACON_ID}: <\[byte ([^\]]*)\]>", line)
    if data:
        try:
            ours = bytes(int(b, 16) for b in data.group(1).split(", ")) == want
        except ValueError:
            ours = False
    elif "ManufacturerData" in line:
        ours = False
    heard = re.search(r"'RSSI': <int16 (-?\d+)>", line)
    return m.group(1), ours, int(heard.group(1)) if heard else None


class Nearness:
    """How near the phone is, while it has the earbuds: it sends a small Bluetooth beacon with a
    number this computer gave it for the occasion, and this listens for it. Where the phone is,
    the earbuds are, and a beacon heard loud means they are back in reach."""

    def __init__(self):
        self.scan = self.watch = self.task = None
        self.want = b""
        self.ours: set[str] = set()
        self.level: float | None = None
        self.heard = 0.0

    @staticmethod
    def possible() -> bool:
        return bool(shutil.which("gdbus") and shutil.which("bluetoothctl"))

    async def start(self, nonce: bytes):
        await self.stop()
        if not self.possible():
            return
        self.want = BEACON + nonce
        self.ours, self.level, self.heard = set(), None, 0.0
        try:
            self.watch = await asyncio.create_subprocess_exec(
                "gdbus", "monitor", "--system", "--dest", "org.bluez", stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.DEVNULL, limit=1 << 20, preexec_fn=_die_with_parent)
            # bluetoothctl keeps the scan going for as long as it runs: BlueZ ends a scan when the
            # program that asked for it goes
            self.scan = await asyncio.create_subprocess_exec(
                "bluetoothctl", stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.DEVNULL,
                stderr=asyncio.subprocess.DEVNULL, preexec_fn=_die_with_parent)
            self.scan.stdin.write(b"menu scan\ntransport le\nduplicate-data on\nback\nscan on\n")
            await self.scan.stdin.drain()
        except OSError as err:
            print(f"weave: listening for the phone's beacon: {err}")
            await self.stop()
            return
        self.task = asyncio.create_task(self._read(self.watch))

    async def _read(self, proc):
        try:
            while True:
                line = await proc.stdout.readline()
                if not line:
                    return
                self.line(line.decode(errors="replace"), time.monotonic())
        except (asyncio.CancelledError, ValueError):
            pass

    def line(self, text: str, now: float):
        found = parse_beacon(text, self.want)
        if not found:
            return
        path, ours, heard = found
        if ours:
            self.ours.add(path)
        elif ours is False:
            self.ours.discard(path)
        if heard is not None and path in self.ours:
            self.level = float(heard) if self.level is None else self.level + 0.4 * (heard - self.level)
            self.heard = now
        if not self.ours:
            self.level = None

    def near(self, now: float) -> float | None:
        """How strongly the phone is heard (dBm), or None while it isn't heard at all. BlueZ only
        says something when the strength changes, so a phone lying still is heard in silence:
        what tells that it has gone is BlueZ taking it off its list, half a minute after its
        last beacon."""
        return self.level if self.ours and self.level is not None else None

    @property
    def lost(self) -> bool:
        """It was heard, and is not any more."""
        return bool(self.heard) and not self.ours

    async def stop(self):
        task, self.task = self.task, None
        if task:
            task.cancel()
        scan, self.scan = self.scan, None
        if scan and scan.returncode is None:
            try:
                scan.stdin.write(b"scan off\nquit\n")
                await scan.stdin.drain()
            except OSError:
                pass
        watch, self.watch = self.watch, None
        await asyncio.gather(_end(scan, 0.5), _end(watch, 0.5))


class Roam:
    """The earbuds' way between this computer and the phone.

    idle       no earbuds are this computer's output
    armed      they are, and their link is being read
    handing    the phone was told to take them
    waiting    they left by themselves; a phone may say it has them
    bridged    the phone has them and plays what this computer sends
    returning  this computer is taking them back
    """

    def __init__(self, daemon):
        self.d = daemon
        self.cfg = daemon.cfg.get("roam", {})
        self.store = Store("roam.json", {})
        for key, value in {"on": False, "mode": "auto", "sens": "balanced", "phone": "", "seen": {}, "learned": {}}.items():
            self.store.data.setdefault(key, value)
        self.state = "idle"
        self.why = ""
        self.addr: str | None = None
        self.name = ""
        self.output = ""               # the earbuds' own output here, by name
        self.conn: int | None = None
        self.ctl: btlink.Controller | None = None
        self.controllers: list[btlink.Controller] = []
        self.judge = Judge(*self._limits())
        self.absolute = False
        self.lines: set = set()
        self.sink = Sink()
        self.sound: SoundCapture | None = None
        self.hand = Hand(daemon.phone)
        self.near = Nearness()
        self.nonce = b""
        self.left: tuple[int, float] | None = None   # why the earbuds' link ended, and when
        self.since = time.monotonic()                # when the state was entered
        self.left_at: float | None = None
        self.hold_until = 0.0          # nothing is handed over by itself before this
        self.back_after = 0.0          # and nothing taken back before this
        self.back_wait = RETURN_RETRY[0]
        self.near_since: float | None = None
        self.auto = False              # this bridge began with the computer handing over
        self.lost_at: float | None = None
        self.meter: dict = {}
        self.metered = 0.0
        self.misses = 0                # looks in a row that didn't find the earbuds' link
        self.final = False             # the return under way ends the bridge whether or not it works
        self.been_away = False         # the phone's beacon went weak or unheard since it took the earbuds
        self.alone_since: float | None = None
        self.task = None
        self.work: asyncio.Task | None = None
        self.looked = 0.0
        self.default = ""
        self.ticks = 0
        self.rssi: int | None = None

    # ── what it is set to ──

    @staticmethod
    def possible() -> bool:
        return bool(shutil.which("pactl")) and hasattr(__import__("socket"), "AF_BLUETOOTH")

    @property
    def on(self) -> bool:
        return bool(self.store.data.get("on"))

    @property
    def by_itself(self) -> bool:
        """Automatic: the link's strength decides when the earbuds change hands. Manual: only a
        person does, from the phone or with `weave roam now` and `weave roam back`."""
        return self.store.data.get("mode", "auto") != "manual"

    def _limits(self) -> tuple[float, float]:
        sens = self.store.data.get("sens", "balanced")
        learned = self.store.data.get("learned", {}).get(self.addr or "")
        hold = self.cfg.get("hold")
        return (limit_for(sens, getattr(self, "absolute", False), learned, self.cfg.get("away")),
                float(hold) if hold is not None else SENS.get(sens, SENS["balanced"])[2])

    def _rejudge(self):
        self.judge.limit, self.judge.hold = self._limits()

    def set(self, on: bool | None = None, sens: str | None = None, phone: str | None = None, mode: str | None = None):
        data = self.store.data
        if on is not None:
            data["on"] = bool(on)
        if mode in ("auto", "manual") and mode != data.get("mode"):
            data["mode"] = mode
            if self.state == "bridged":
                # the phone's beacon is only listened for while this computer may take them back by itself
                asyncio.create_task(self.near.start(self.nonce) if mode == "auto" else self.near.stop())
        if sens in SENS and sens != data.get("sens"):
            data["sens"] = sens
            data["learned"] = {}     # a pick of one's own starts from what it says
        if phone:
            data["phone"] = phone
        self.store.save()
        self._rejudge()
        self.tell()

    def status(self) -> dict:
        now = time.monotonic()
        near = self.near.near(now)
        return {
            "on": self.on, "mode": "auto" if self.by_itself else "manual",
            "sens": self.store.data.get("sens", "balanced"), "state": self.state if self.on else "off",
            "device": {"addr": self.addr, "name": self.name} if self.addr else None,
            "devices": [{"addr": a, "name": n} for a, n in self.store.data.get("seen", {}).items()],
            "signal": round(self.judge.level, 1) if self.judge.level is not None and self.state == "armed" else None,
            "limit": self.judge.limit, "dbm": self.absolute,
            "rate": round(self.judge.rate) if self.judge.rate is not None and self.state == "armed" else None,
            "auto": self.hand.ready, "autoWhy": "" if self.hand.ready else self.hand.why,
            "near": round(near) if near is not None else None,
            "beacon": self.nonce.hex() if self.state == "bridged" and self.nonce and self.by_itself else None,
            "why": self.why,
            # what the phone last said it is playing: its loudest sample (dB under full), and its own volume
            "meter": self.meter if self.state == "bridged" and time.monotonic() - self.metered < 6 else None,
        }

    def tell(self, extra: dict | None = None):
        msg = {"t": "roam", **self.status(), **(extra or {})}
        for s in list(self.lines):
            s.send_json(msg)

    def _go(self, state: str, why: str = ""):
        if state != self.state:
            print(f"weave: earbuds {self.state} -> {state}" + (f" ({why})" if why else ""))
            if state == "bridged":
                self.d.log(f"{self.name or 'The earbuds'} went over to the phone")
            elif state == "armed" and self.state in ("bridged", "returning"):
                self.d.log(f"{self.name or 'The earbuds'} came back to this computer")
        self.state, self.why, self.since = state, why or self.why, time.monotonic()
        self.tell()

    # ── the phone's lines ──

    def _players(self) -> list:
        return [s for s in self.lines if getattr(s, "roam_kind", "play") == "play"]

    def attach(self, session):
        self.lines.add(session)
        if self._players():
            self.alone_since = None

    def detach(self, session):
        self.lines.discard(session)
        if not self._players() and self.alone_since is None:
            self.alone_since = time.monotonic()

    async def handle(self, session, msg: dict):
        t = msg.get("t")
        if t == "ping":
            session.send_json({"t": "pong", "id": msg.get("id")})
        elif t == "audio":
            session.want_audio = bool(msg.get("on"))
            if session.want_audio and self.sound and self.sound.audio_config:
                session.send(AUDIO, struct.pack(">BQ", CONFIG, 0) + self.sound.audio_config)
        elif t == "media":
            inj = self.d.ensure_injector()
            if inj and msg.get("cmd") in ("play", "pause", "next", "prev"):
                inj.key(str(msg["cmd"]))
        elif t == "adb":
            ports = [int(p) for p in msg.get("ports", []) if str(p).isdigit()]
            hosts = [session.peer, *[str(h) for h in msg.get("ips", [])]]
            self.d.phone.hints = [f"{h}:{p}" for p in [*ports, 5555] for h in hosts]
        elif t == "roam":
            if "watch" in msg:
                session.roam_watch = bool(msg["watch"])
            if isinstance(msg.get("meter"), dict):
                # what the phone's player says is coming out of it; no answer needed
                self.meter = {k: msg["meter"].get(k) for k in ("peak", "vol", "max", "db", "lift")}
                self.metered = time.monotonic()
                return
            if isinstance(msg.get("set"), dict):
                wanted = msg["set"]
                self.set(on=wanted.get("on"), sens=wanted.get("sens"), phone=session.key, mode=wanted.get("mode"))
            if msg.get("have"):
                await self._phone_has(str(msg["have"]).upper(), bool(msg.get("force")))
            if msg.get("lost") and self.state == "bridged" and str(msg["lost"]).upper() == self.addr:
                self.lost_at = time.monotonic()
            if msg.get("stop") and self.state in ("bridged", "handing", "waiting"):
                # done on the phone: the earbuds come back here by themselves when they can
                self._return("stopped on the phone", final=True)
            if msg.get("back") and self.state == "bridged":
                self._return("asked for")
            if msg.get("now"):
                if self.state != "armed":
                    session.send_json({"t": "toast", "msg": f"The earbuds aren't on {self.d.host} right now."})
                elif await self.hand.ensure(self.addr):
                    await self._hand_over("asked for", asked=True)
                else:
                    session.send_json({"t": "toast", "msg": f"{self.d.host} can't make this phone take them: {self.hand.why}."})
            session.send_json({"t": "roam", **self.status()})

    # ── running ──

    async def start(self):
        await Sink.sweep()
        self.task = asyncio.create_task(self._run())

    async def _run(self):
        while True:
            await asyncio.sleep(TICK)
            try:
                await self._tick()
            except asyncio.CancelledError:
                raise
            except Exception as err:
                print(f"weave: following the earbuds: {err!r}")

    def _gone(self, controller, handle: int, reason: int):
        if controller is self.ctl and handle == self.conn:
            self.left = (reason, time.monotonic())

    def _open(self):
        self.controllers = [c for c in self.controllers if not c.closed]
        if not self.controllers:
            self.controllers = btlink.open_all(self._gone)

    def _linked(self, addr: str | None) -> tuple[btlink.Controller, int] | None:
        """The controller the earbuds are connected to, and their handle there."""
        if not addr:
            return None
        for c in self.controllers:
            handle = c.connections().get(addr)
            if handle is not None:
                return c, handle
        return None

    async def _default(self, now: float, fresh: bool = False) -> str:
        if fresh or now - self.looked > 1.0:
            self.looked = now
            self.default = (await run("pactl", "get-default-sink", timeout=2)).strip()
        return self.default

    async def _tick(self):
        now = time.monotonic()
        self.ticks += 1
        if not self.on:
            if self.state != "idle":
                await self._drop("turned off")
            for c in self.controllers:
                c.close()
            self.controllers = []
            return
        self._open()
        step = getattr(self, f"_while_{self.state}")
        await step(now)
        if self.ticks % 2 == 0:
            for s in list(self.lines):
                if getattr(s, "roam_watch", False):
                    s.send_json({"t": "roam", **self.status()})

    async def _while_idle(self, now: float):
        # with no Bluetooth link of any kind there is nothing to ask the sound server about
        if not any(c.connections() for c in self.controllers):
            return
        addr = sink_address(await self._default(now))
        link = self._linked(addr)
        if not addr or not link:
            return
        self.addr, (self.ctl, self.conn) = addr, link
        self.output = self.default
        self.name = await self._name_of(self.output) or self.store.data["seen"].get(addr, "") or "Earbuds"
        if self.store.data["seen"].get(addr) != self.name:
            self.store.data["seen"][addr] = self.name
            self.store.save()
        self.left, self.misses = None, 0
        self.absolute = False
        self.judge = Judge(*self._limits())
        # The silent output is made now and waits behind the earbuds, unused, for as long as they
        # are here. However they leave (handed over, taken by the phone by hand, out of reach),
        # what plays goes silent in the same instant, with no time for the speakers to cut in.
        await self._line_up()
        await self._ready_sink()
        self._go("armed", "the earbuds are this computer's output")
        self._bring_hand()

    async def _line_up(self):
        """Puts the silent output next in line behind the earbuds. The sound server keeps a list of
        the outputs that were picked, newest first, and when the one in use goes it moves
        everything to the next that exists. With the silent output second, the moment the
        earbuds leave, what plays here goes silent in the same breath: never a note from the
        speakers in between. It is named before it exists, so nothing that plays is touched."""
        if not shutil.which("pw-metadata") or not self.output or self.sink.up:
            return   # (naming it while it exists would move what plays onto it for a moment)
        for name in (SINK, self.output):
            await run("pw-metadata", "-n", "default", "0", "default.configured.audio.sink",
                      json.dumps({"name": name}), "Spa:String:JSON", timeout=2)

    async def _mute_here(self):
        """With the earbuds gone, this computer plays into the silent output and nowhere else.
        That happens by itself when the outputs were lined up; this is for when they weren't."""
        if self.sink.up and (await self._default(time.monotonic(), fresh=True)) != SINK:
            await run("pactl", "set-default-sink", SINK, timeout=2)
            self.looked = 0.0

    def _bring_hand(self):
        if not self.hand.ready and time.monotonic() - self.hand.tried > 30:
            asyncio.create_task(self._ensure_hand())

    async def _ensure_hand(self):
        was = self.hand.ready
        try:
            await self.hand.ensure(self.addr)
        except Exception as err:
            self.hand.why = f"the helper on the phone wouldn't start: {err!r}"
        if self.hand.ready != was:
            self.tell()

    async def _name_of(self, sink: str) -> str:
        try:
            for s in json.loads(await run("pactl", "-f", "json", "list", "sinks", timeout=3) or "[]"):
                if s.get("name") == sink:
                    return str(s.get("description") or "")
        except ValueError:
            pass
        return ""

    async def _while_armed(self, now: float):
        link = self._linked(self.addr)
        if not link or link[1] != self.conn:
            # the controller said the link ended, or it has been missing for three looks running:
            # one look that finds nothing (a controller being asked again) is not the earbuds leaving
            self.misses += 1
            if link:
                # the same earbuds on a new link: they dropped and reconnected between two looks
                (self.ctl, self.conn), self.left, self.misses = link, None, 0
            elif self.left or self.misses >= 3:
                await self._left_by_itself(now)
            return
        self.misses = 0
        if sink_address(await self._default(now)) != self.addr:
            await self._quiet_down()
            self._go("idle", "another output was picked")
            return
        self.rssi = await self.ctl.rssi(self.conn)
        sent = self.ctl.sent()
        verdict = self.judge.feed(now, self.rssi, sent[0] if sent else None)
        if self._scale(now):
            return
        if self.ticks % 8 == 0:
            self._bring_hand()
            asyncio.create_task(self.hand.keep())
        if not verdict or now < self.hold_until or not self.by_itself:
            return
        if verdict == "stalled":
            if not await self._playing():
                self.judge.quiet()
                return
            self._learn()
        if not self.hand.ready:
            return   # nobody to hand them to: they stay until they drop
        await self._hand_over("the link got weak" if verdict == "weak" else "the sound stopped getting through")

    def _scale(self, now: float) -> bool:
        """Works out which way this controller counts. One that has ever read near 0 says how far
        outside its good range the earbuds are, for good. One that never has, ten seconds into a
        link, says dBm. True when that was just found out, and the limit changed with it."""
        best = self.judge.best
        if best is None:
            return False
        if best > ABSOLUTE_BELOW:
            if self.store.data.get("scale") != "relative":
                self.store.data["scale"] = "relative"
                self.store.save()
            return False
        if self.absolute or self.store.data.get("scale") == "relative" or now - self.since < 10:
            return False
        self.absolute = True
        self._rejudge()
        self.judge.weak_since, self.judge.deep = None, 0
        return True

    async def _playing(self) -> bool:
        for line in (await run("pactl", "list", "short", "sinks", timeout=2)).splitlines():
            parts = line.split("\t")
            if len(parts) > 1 and parts[1] == self.output:
                return parts[-1] == "RUNNING"
        return False

    def _learn(self):
        """The sound broke up before the link counted as weak: for these earbuds the limit moves
        to where that was, so next time the phone has them before it happens."""
        if self.judge.level is None or not self.addr or self.cfg.get("away") is not None:
            return
        floor = -72.0 if self.absolute else -3.0
        learnt = min(self.judge.level + 2, floor)
        if learnt > self.judge.limit:
            self.store.data["learned"][self.addr] = round(learnt, 1)
            self.store.save()
            self._rejudge()
            print(f"weave: these earbuds are handed over at {learnt:.0f} from now on")

    async def _hand_over(self, why: str, asked: bool = False):
        self.auto, self.left_at, self.left = not asked, None, None
        self._go("handing", why)
        # (again, in case another output was picked since the earbuds came)
        await self._line_up()
        if not await self._silent():
            self._go("armed", "the silent output couldn't be made")
            self.hold_until = time.monotonic() + 30
            return
        if not await self.hand.take(self.addr):
            await self._quiet_down(sink=False)
            self.hold_until = time.monotonic() + 30
            self._go("armed", "the phone didn't answer")

    async def _ready_sink(self) -> bool:
        """The silent output, there to be played into."""
        phone = self.d.phone.model if self.d.phone.serial else "the phone"
        return await self.sink.start(f"{self.name or 'Earbuds'} through {phone}")

    async def _silent(self) -> bool:
        """The silent output, and its sound on its way to whichever phone asks."""
        if not await self._ready_sink():
            return False
        if not self.sound:
            cap = SoundCapture(self.d.cfg["video"]["audio_kbps"], self._on_audio, self._on_sound_exit, source=f"{SINK}.monitor")
            self.sound = cap
            try:
                await cap.start()
            except OSError as err:
                print(f"weave: the sound for the phone wouldn't start: {err}")
                self.sound = None
                return False
        return True

    def _on_audio(self, cap, flags: int, pts: int, data: bytes):
        if cap is not self.sound:
            return
        for s in list(self.lines):
            if s.want_audio and (flags & CONFIG or s.transport.get_write_buffer_size() < BEHIND):
                s.send(AUDIO, struct.pack(">BQ", flags, pts) + data)

    def _on_sound_exit(self, cap, code: int):
        if cap is not self.sound:
            return
        self.sound = None
        if self.sink.up and self.state in ("handing", "waiting", "bridged", "returning"):
            print(f"weave: the sound for the phone stopped ({cap.errors[-1] if cap.errors else code}); starting it again")
            asyncio.get_running_loop().call_later(0.5, lambda: asyncio.create_task(self._silent()) if self.sink.up else None)

    async def _quiet_down(self, sink: bool = True):
        """Nothing is sent to a phone any more, and with [sink] the silent output goes too: this
        computer plays through its own outputs again. It stays while the earbuds are here."""
        cap, self.sound = self.sound, None
        if cap:
            await cap.stop()
        if sink:
            await self.sink.stop()
        await self.near.stop()
        self.near_since = None
        # and the phone's sound plays here again, if it did before the phone had the earbuds
        await self.d.phone.hold_audio(False)

    async def _while_handing(self, now: float):
        gone = not self._linked(self.addr)
        if gone and self.left_at is None:
            self.left_at = now
            await self._mute_here()
        if not gone and now - self.since > TAKE_WAIT:
            # the phone never got them (it is out of their reach too, or it was told nothing)
            await self._quiet_down(sink=False)
            self.hold_until = now + 60
            self.left = None
            self._go("armed", "the phone couldn't take them")
        elif gone and now - self.left_at > TAKE_WAIT:
            # they went to the phone and it never played: back here with them, if they can be reached
            self._return("the phone never played", final=True)

    async def _left_by_itself(self, now: float):
        """The earbuds' link here ended and this computer didn't end it."""
        reason = self.left[0] if self.left else 0
        timed_out = reason == btlink.TIMEOUT
        if timed_out:
            self._learn()
        self.auto, self.left_at = False, now
        if not await self._silent():
            self._go("idle", "the earbuds left")
            return
        await self._mute_here()
        if timed_out and self.hand.ready and self.by_itself:
            self.auto = True
            self._go("handing", "the earbuds went out of reach")
            asyncio.create_task(self.hand.take(self.addr))
        else:
            # put in their case, or taken by a phone by hand: a phone that has them says so shortly
            self._go("waiting", "the earbuds went out of reach" if timed_out else "the earbuds left")

    async def _while_waiting(self, now: float):
        if self._linked(self.addr):
            await self._came_back("they are back")
        elif now - self.since > (TAKE_WAIT if self.left and self.left[0] == btlink.TIMEOUT else GRACE):
            # nobody has them: out of reach, what plays is paused; put away, it goes on from the speakers
            await self._drop("no phone has the earbuds", pause=bool(self.left and self.left[0] == btlink.TIMEOUT))

    async def _phone_has(self, addr: str, asked: bool = False):
        """A phone says the earbuds are connected to it and it is ready to play. That starts a
        bridge when they just left this computer, or when it was [asked] for by hand on the
        phone. Earbuds that connect to the phone some other day are the phone's own business:
        this computer goes on playing wherever it was."""
        if self.state in ("bridged", "returning") and addr == self.addr:
            self.lost_at = None
            return
        if self.state == "idle" or addr != self.addr:
            if not asked or self.state not in ("idle", "armed") or addr not in self.store.data["seen"]:
                return
            self.addr, self.name = addr, self.store.data["seen"][addr]
        if self.state in ("idle", "armed"):
            self.auto = False   # taken on the phone by hand
        if not await self._silent():
            return
        if not self._linked(self.addr):
            await self._mute_here()
        self.nonce = secrets.token_bytes(7) + bytes([secrets.randbelow(255) + 1])
        self.lost_at, self.near_since = None, None
        # Handed over because the link got weak, the earbuds were at the edge of reach: wherever
        # the phone is heard next, it is on its way back. Moved by hand, it may be lying right
        # here, and has to go away first.
        self.been_away = self.auto
        self.back_after = time.monotonic() + 2
        self._go("bridged", "the phone has the earbuds")
        # The phone's own sound, if it was playing here, plays on the phone now: the earbuds are
        # its output, so everything is still heard in them, this computer's sound and the phone's.
        await self.d.phone.hold_audio(True)
        if self.by_itself:
            await self.near.start(self.nonce)

    async def _while_bridged(self, now: float):
        if now - self.since > 1.5 and self._linked(self.addr):
            await self._came_back("they connected here")
            return
        if self.lost_at is not None and now - self.lost_at > LOST_WAIT:
            self._return("the earbuds left the phone", final=True)
            return
        if not self._players() and now - (self.alone_since or self.since) > LINE_WAIT:
            self._return("the phone went quiet", final=True)
            return
        near = self.near.near(now) if self.by_itself else None
        limit = float(self.cfg.get("near", NEAR))
        # The phone has to have been away before it can be back: earbuds moved to it by hand while
        # it lies beside the computer stay on it, however loud its beacon.
        if self.near.lost or (near is not None and near < limit - AWAY_BELOW):
            self.been_away = True
        if near is None or near < limit:
            self.near_since = None
            return
        if self.near_since is None:
            self.near_since = now
        heard_for = now - self.near_since
        if self.auto and now - self.since < STAYED and heard_for >= 2:
            # handed over a moment ago and the phone is right here: it was left on the desk
            self.hold_until = now + STAYED_OFF
            self._return("the phone stayed by the computer")
        elif self.been_away and heard_for >= float(self.cfg.get("near_hold", 4.0)) and now >= self.back_after:
            self._return("back in reach")

    def _return(self, why: str, final: bool = False):
        """Takes the earbuds back. [final]: the bridge is over whatever comes of it (stopped on the
        phone, or the phone gone quiet), so earbuds that can't be reached from here are let go,
        where otherwise the phone would simply keep them."""
        if self.state not in ("bridged", "handing", "waiting") or (self.work and not self.work.done()):
            return
        self.final = final
        self._go("returning", why)
        self.work = asyncio.create_task(self._take_back())

    async def _take_back(self):
        """Connects the earbuds here again. The phone plays on until they leave it, so the only
        gap is the moment they change hands."""
        await self.near.stop()
        self.tell({"release": True})
        ok = False
        try:
            addr = self.addr or ""
            if shutil.which("busctl"):
                path = f"/org/bluez/hci{self.ctl.dev if self.ctl else 0}/dev_{addr.replace(':', '_')}"
                await run("busctl", "--system", "--timeout=14", "call", "org.bluez", path, "org.bluez.Device1", "Connect", timeout=15)
            else:
                await run("bluetoothctl", "--timeout", "14", "connect", addr, timeout=15)
            for _ in range(20):
                if self._linked(self.addr):
                    ok = True
                    break
                await asyncio.sleep(0.1)
        except Exception as err:
            print(f"weave: taking the earbuds back: {err!r}")
        if self.state != "returning":
            return
        if ok:
            self.back_wait = RETURN_RETRY[0]
            await self._came_back(self.why)
        elif self.final:
            await self._drop("the earbuds are out of reach, and the phone is done with them", pause=True)
        else:
            self.back_after = time.monotonic() + self.back_wait
            self.back_wait = min(self.back_wait * 2, RETURN_RETRY[1])
            self.near_since = None
            self._go("bridged", "they couldn't be taken back yet")
            if self.by_itself:
                await self.near.start(self.nonce)

    async def _came_back(self, why: str):
        """The earbuds are connected here again: their own output takes the sound, then the silent one goes."""
        link = self._linked(self.addr)
        name = ""
        for _ in range(40):
            name = next((line.split("\t")[1] for line in (await run("pactl", "list", "short", "sinks", timeout=2)).splitlines()
                         if sink_address(line) == self.addr), "")
            if name:
                break
            await asyncio.sleep(0.1)
        if name:
            await run("pactl", "set-default-sink", name, timeout=2)
            self.output = name
        link = self._linked(self.addr) or link
        await self._quiet_down(sink=not (link and name))
        self.hold_until = max(self.hold_until, time.monotonic() + SETTLE)
        self.left = None
        if link and name:
            self.ctl, self.conn = link
            self.misses = 0
            self.judge = Judge(*self._limits())
            self._go("armed", why)
        else:
            self._go("idle", why)

    async def _while_returning(self, now: float):
        if now - self.since > 30 and (not self.work or self.work.done()):
            if self.final:
                await self._drop("taking them back took too long", pause=True)
            else:
                self._go("bridged", "taking them back took too long")

    async def _drop(self, why: str, pause: bool = False):
        """The bridge is over and the earbuds aren't here either."""
        if pause and self.cfg.get("pause", True) and self.sink.up:
            await self._pause()
        if self.work and not self.work.done() and self.work is not asyncio.current_task():
            self.work.cancel()
        await self._quiet_down()
        self.left = None
        self._go("idle", why)

    async def _pause(self):
        """What plays here is paused: with nobody listening it would run on unheard, or come out of
        the speakers of an empty room."""
        if shutil.which("playerctl"):
            await run("playerctl", "-a", "pause", timeout=2)
        else:
            inj = self.d.ensure_injector()
            if inj:
                inj.key("pause")
        await asyncio.sleep(0.25)

    # ── the `weave roam` command ──

    async def command(self, req: dict) -> dict:
        do = req.get("do") or "status"
        if do in ("on", "off"):
            self.set(on=do == "on")
        elif do in SENS:
            self.set(sens=do)
        elif do in ("auto", "manual"):
            self.set(mode=do)
        elif do == "now":
            if self.state != "armed":
                return {"ok": False, "msg": "the earbuds aren't this computer's output right now", "roam": self.status()}
            if not await self.hand.ensure(self.addr):
                return {"ok": False, "msg": self.hand.why, "roam": self.status()}
            await self._hand_over("asked for", asked=True)
        elif do == "back":
            if self.state != "bridged":
                return {"ok": False, "msg": "the phone doesn't have the earbuds", "roam": self.status()}
            self._return("asked for")
        elif do == "status" and req.get("probe") and self.on:
            await self.hand.ensure(self.addr)
        return {"ok": True, "msg": "", "roam": {**self.status(), "raw": self.rssi, "best": self.judge.best,
                                                 "usual": round(self.judge.usual), "hold": self.judge.hold}}

    async def close(self):
        if self.task:
            self.task.cancel()
        # (a bridge cut off by the daemon stopping: paused, so the speakers don't take over mid-song)
        if self.state in ("bridged", "handing", "returning") and self.cfg.get("pause", True) and self.sink.up:
            try:
                await self._pause()
            except Exception:
                pass
        await self._quiet_down()
        await self.hand.close()
        for c in self.controllers:
            c.close()
