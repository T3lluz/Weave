"""The laptop driving the phone: finding it over adb, the scrcpy window, and its sound.

Two scrcpy runs, never sharing audio: the window (video and control) and a windowless one
that only plays the phone's sound on the laptop's output. So the sound can stay on with
the window closed, and opening or closing the window never cuts it.
"""

import asyncio
import json
import os
import re
import shutil
from pathlib import Path

from . import hypr
from .config import DATA_DIR, Store


def _die_with_parent():
    """Runs in scrcpy just before it starts: if the daemon goes, however it goes, scrcpy goes too.

    Otherwise a killed daemon left the phone's sound playing here with nothing to turn it off.
    """
    import ctypes
    import signal
    ctypes.CDLL("libc.so.6", use_errno=True).prctl(1, signal.SIGTERM)   # PR_SET_PDEATHSIG


def _tool(name: str) -> str | None:
    found = shutil.which(name)
    if found:
        return found
    vendored = DATA_DIR / "scrcpy" / name
    return str(vendored) if vendored.exists() else None


class Phone:
    def __init__(self, cfg: dict, notify, changed):
        self.cfg = cfg
        self.notify = notify          # notify(title, body)
        self.changed = changed        # called when the window or the sound starts or stops
        self.store = Store("phone.json", {"endpoints": [], "uhid_hint_shown": False})
        self.adb_path = _tool("adb")
        self.scrcpy_path = _tool("scrcpy")
        self.hints: list[str] = []    # host:port the phone's app told us about
        self.serial: str | None = None
        self.model = "Phone"
        self.mirror_proc = None
        self.audio_proc = None
        self.audio_wanted = False
        # the phone's sound stays on the phone for now, though it is wanted here (see hold_audio)
        self.audio_held = False
        self.audio_source = "output"
        self._audio_task = None
        self._looked = -1e9           # when the phone was last searched for over the network
        self.volume: int | None = None    # the "Phone" stream on the laptop, in percent
        self.sink = ""                    # what it plays through
        self._audio_restart = False   # the source changed: start again without counting a failure
        self._call_failed = False     # this call's audio could not be captured: stop trying until it ends
        self._lock = asyncio.Lock()

    # ── adb ──

    async def adb(self, *args, timeout: float = 8) -> tuple[int, str]:
        if not self.adb_path:
            return 127, "adb is not installed"
        try:
            proc = await asyncio.create_subprocess_exec(
                self.adb_path, *args, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT)
            out, _ = await asyncio.wait_for(proc.communicate(), timeout)
            return proc.returncode or 0, out.decode(errors="replace").strip()
        except asyncio.TimeoutError:
            proc.kill()
            return 124, "timed out"
        except OSError as err:
            return 126, str(err)

    async def _connected(self) -> list[str]:
        _, out = await self.adb("devices")
        return [line.split("\t")[0] for line in out.splitlines()[1:] if line.endswith("\tdevice")]

    async def _mdns(self) -> list[str]:
        _, out = await self.adb("mdns", "services", timeout=5)
        found = []
        for line in out.splitlines():
            parts = line.split()
            if len(parts) >= 3 and parts[1] in ("_adb-tls-connect._tcp", "_adb._tcp") and ":" in parts[2]:
                found.append(parts[2])
        return found

    async def find(self) -> str | None:
        """The phone's adb serial, connecting to it over the network if it is not already."""
        async with self._lock:
            devices = await self._connected()
            if self.serial in devices:
                return self.serial
            if not devices:
                tried = set()
                for endpoint in [*self.hints, *self.store.data["endpoints"], *await self._mdns()]:
                    if endpoint in tried:
                        continue
                    tried.add(endpoint)
                    _, out = await self.adb("connect", endpoint, timeout=5)
                    if "connected to" in out:
                        devices = await self._connected()
                        if devices:
                            break
            if not devices:
                self.serial = None
                return None
            self.serial = devices[0]
            if ":" in self.serial:
                known = self.store.data["endpoints"]
                if self.serial in known:
                    known.remove(self.serial)
                known.insert(0, self.serial)
                del known[4:]
                self.store.save()
            code, model = await self.adb("-s", self.serial, "shell", "getprop", "ro.product.model")
            if code == 0 and model:
                self.model = model.splitlines()[0]
            return self.serial

    async def refresh(self):
        """Keeps `serial` honest for the dashboard: cheap while the phone is there, a real look now and then."""
        if self.serial and self.serial in await self._connected():
            return
        was, self.serial = self.serial, None
        now = asyncio.get_running_loop().time()
        if now - self._looked > 120:
            self._looked = now
            await self.find()
        if self.serial != was:
            self.changed()

    async def screen_size(self) -> tuple[int, int]:
        _, out = await self.adb("-s", self.serial or "", "shell", "wm", "size")
        try:
            w, h = out.splitlines()[-1].split(":")[1].strip().split("x")
            return int(w), int(h)
        except (IndexError, ValueError):
            return 1080, 2400

    def _env(self) -> dict:
        env = dict(os.environ)
        if self.adb_path:
            env["ADB"] = self.adb_path
        # what the stream is called in the volume mixer, so Sonus can route it by name
        env["PULSE_PROP"] = "application.name=Phone media.role=music"
        env["PIPEWIRE_PROPS"] = '{ application.name = "Phone" media.role = "Music" }'
        return env

    # ── the window ──

    @property
    def mirroring(self) -> bool:
        return self.mirror_proc is not None and self.mirror_proc.returncode is None

    @property
    def audio_on(self) -> bool:
        return self.audio_proc is not None and self.audio_proc.returncode is None

    async def mirror(self, on: bool) -> str:
        if not on:
            if self.mirroring:
                self.mirror_proc.terminate()
            return "closed"
        if self.mirroring:
            await self._focus_window()
            return "already open"
        if not self.scrcpy_path:
            return "scrcpy is not installed"
        serial = await self.find()
        if not serial:
            return "no phone: turn on Wireless debugging, or open the remote in DailyDash"
        m = self.cfg["mirror"]
        pw, ph = await self.screen_size()
        mons = await hypr.monitors()
        pos = await hypr.cursor()
        mon = (hypr.monitor_at(mons, *pos) if pos else None) or next((x for x in mons if x["focused"]), None)
        height = int((mon["h"] if mon else 900) * m["height"])
        # float it before it maps; the rule lives until Hyprland reloads its config
        await hypr.evaluate('hl.window_rule({ name = "weave-phone", match = { class = "^(scrcpy)$" }, float = true })')
        args = [
            self.scrcpy_path, "-s", serial, "--no-audio", f"--window-title={self.model}",
            "--shortcut-mod=rctrl", f"--keyboard={m['keyboard']}", f"--video-codec={m['codec']}",
            f"--video-bit-rate={m['bit_rate']}", f"--max-fps={m['max_fps']}",
            f"--window-height={height}", "--window-borderless", *m["extra_args"],
        ]
        if m["stay_awake"]:
            args.append("--stay-awake")
        if m["screen_off"]:
            args.append("--turn-screen-off")
        self.mirror_proc = await asyncio.create_subprocess_exec(
            *args, env=self._env(), stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT,
            preexec_fn=_die_with_parent)
        asyncio.create_task(self._watch_mirror(self.mirror_proc))
        if m["with_audio"] and not self.audio_wanted:
            await self.audio(True)
        if m["keyboard"] == "uhid" and not self.store.data.get("uhid_hint_shown"):
            self.store.data["uhid_hint_shown"] = True
            self.store.save()
            self.notify("Phone keyboard layout", "Typing the wrong letters? Press Right Ctrl + K in the phone window and pick your layout for the scrcpy keyboard. Android remembers it.")
        self.changed()
        return "open"

    async def _watch_mirror(self, proc):
        tail = []
        async for line in proc.stdout:
            tail.append(line.decode(errors="replace").strip())
            del tail[:-6]
        code = await proc.wait()
        if code not in (0, -15):
            errors = [t for t in tail if "ERROR" in t]
            self.notify("Phone window closed", (errors or tail or ["scrcpy stopped"])[-1][:200])
        self.changed()

    async def _focus_window(self):
        await hypr.focus_window("class:^(scrcpy)$")

    # ── the phone's sound on the laptop ──

    async def audio(self, on: bool, source: str | None = None) -> str:
        if source and source != self.audio_source:
            self.audio_source = source
            if self.audio_on:
                self._audio_restart = True
                self.audio_proc.terminate()   # the keeper below starts it again with the new source
        self.audio_wanted = on
        if not on:
            if self.audio_on:
                self.audio_proc.terminate()
            self.changed()
            return "off"
        if not self.scrcpy_path:
            self.audio_wanted = False
            return "scrcpy is not installed"
        if not self._audio_task or self._audio_task.done():
            if not await self.find():
                self.audio_wanted = False
                return "no phone: turn on Wireless debugging, or open the remote in DailyDash"
            self._audio_task = asyncio.create_task(self._keep_audio())
            if self.cfg["phone_audio"].get("calls", True):
                asyncio.create_task(self._watch_calls())
        return "on"

    async def hold_audio(self, held: bool):
        """Keeps the phone's sound on the phone for a while, without forgetting that it is wanted
        here. While the phone has the earbuds (roam.py) they are the phone's own output: its
        sound belongs in them directly, not on a trip to this computer and back. Capturing it
        would also take it away from them, since Android stops playing what it sends here."""
        if held == self.audio_held:
            return
        self.audio_held = held
        if held and self.audio_on:
            self._audio_restart = True   # ended on purpose: not a failure to count
            self.audio_proc.terminate()
        self.changed()

    async def _stream(self) -> dict | None:
        """scrcpy's playback stream in the laptop's mixer."""
        from .server import run
        try:
            for s in json.loads(await run("pactl", "-f", "json", "list", "sink-inputs") or "[]"):
                if s.get("properties", {}).get("node.name") == "scrcpy":
                    return s
        except ValueError:
            pass
        return None

    async def read_volume(self):
        """How loud the phone plays here and through what, for the dashboard."""
        from .server import run
        s = await self._stream() if self.audio_on else None
        if not s:
            self.volume, self.sink = None, ""
            return
        try:
            channel = next(iter(s["volume"].values()))
            self.volume = int(str(channel["value_percent"]).rstrip("%"))
            sinks = json.loads(await run("pactl", "-f", "json", "list", "sinks") or "[]")
            self.sink = next((k.get("description") or k.get("name") or "" for k in sinks if k.get("index") == s.get("sink")), "")
        except (KeyError, ValueError, StopIteration):
            pass

    async def set_volume(self, percent: float) -> str:
        from .server import run
        s = await self._stream()
        if not s:
            return "The phone's sound is not playing here yet."
        self.volume = int(min(max(percent, 0), 150))
        await run("pactl", "set-sink-input-volume", str(s["index"]), f"{self.volume}%")
        return ""

    async def restart(self, audio: bool, mirror: bool):
        """A setting changed: what is running starts again with it."""
        if audio and self.audio_on:
            self._audio_restart = True
            self.audio_proc.terminate()
        if mirror and self.mirroring:
            await self.mirror(False)
            for _ in range(20):
                if not self.mirroring:
                    break
                await asyncio.sleep(0.1)
            await self.mirror(True)

    async def _unmute(self, serial: str):
        """A phone kept on silent would send silence. Raises the volume of what is sent, not the speaker's.

        Android keeps a media volume per output; while scrcpy captures, the output is
        "remote_submix", so setting the volume now leaves the phone's own speaker level alone.
        """
        want = self.cfg["phone_audio"].get("volume")
        if not want:
            return
        for _ in range(4):
            await asyncio.sleep(1.5)
            if not self.audio_on:
                return
            _, out = await self.adb("-s", serial, "shell", "dumpsys audio | grep -A8 -m1 '^- STREAM_MUSIC'", timeout=5)
            if "Devices: remote_submix" not in out:
                continue   # not routed to the capture yet
            level = re.search(r"remote_submix\): (\d+)", out)
            top = re.search(r"Max: (\d+)", out)
            if not top:
                return
            target = round(int(top.group(1)) * float(want))
            if "Muted: true" in out or not level or int(level.group(1)) < target // 2:
                await self.adb("-s", serial, "shell", "cmd", "media_session", "volume", "--stream", "3", "--set", str(target), timeout=5)
            return

    async def _watch_calls(self):
        """A phone call is not part of the normal output: while one is up, capture the caller instead."""
        while self.audio_wanted:
            await asyncio.sleep(3)
            if not self.serial or not self.audio_wanted:
                continue
            code, out = await self.adb("-s", self.serial, "shell", "dumpsys telephony.registry | grep -m1 mCallState", timeout=4)
            if code != 0 or "mCallState=" not in out:
                continue
            in_call = out.split("mCallState=")[1][:1] == "2"
            if not in_call:
                self._call_failed = False
            want = "voice-call-downlink" if in_call and not self._call_failed else "output"
            if want != self.audio_source and self.audio_wanted:
                await self.audio(True, want)

    async def _keep_audio(self):
        """Runs the sound for as long as it is wanted, through drops, for a few minutes of trying."""
        failures = 0
        while self.audio_wanted:
            if self.audio_held:
                await asyncio.sleep(0.5)
                continue
            serial = await self.find()
            if serial:
                a = self.cfg["phone_audio"]
                args = [
                    self.scrcpy_path, "-s", serial, "--no-video", "--no-window", "--no-control",
                    f"--audio-source={self.audio_source}", f"--audio-buffer={a['buffer_ms']}", *a["extra_args"],
                ]
                started = asyncio.get_running_loop().time()
                self.audio_proc = await asyncio.create_subprocess_exec(
                    *args, env=self._env(), stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT,
            preexec_fn=_die_with_parent)
                self.changed()
                asyncio.create_task(self._unmute(serial))
                tail = []
                async for line in self.audio_proc.stdout:
                    tail.append(line.decode(errors="replace").strip())
                    del tail[:-6]
                await self.audio_proc.wait()
                self.changed()
                if not self.audio_wanted:
                    break
                if self._audio_restart:
                    self._audio_restart = False
                    continue
                ran = asyncio.get_running_loop().time() - started
                failures = 0 if ran > 20 else failures + 1
                if ran < 5 and self.audio_source != "output":
                    # the call could not be captured: back to everything else, and leave this call alone
                    self.audio_source = "output"
                    self._call_failed = True
                    continue
                last = next((t for t in reversed(tail) if "ERROR" in t), "")
                if failures >= 3 and last:
                    self.notify("Phone audio stopped", last[:200])
                    break
            else:
                failures += 1
            if failures >= 20:
                self.notify("Phone audio stopped", "The phone has been out of reach for a while.")
                break
            await asyncio.sleep(2 if failures < 3 else 10)
        self.audio_wanted = False
        self.changed()

    async def push(self, paths: list[str]) -> str:
        serial = await self.find()
        if not serial:
            return "no phone"
        sent = 0
        for path in paths:
            if Path(path).is_file():
                code, out = await self.adb("-s", serial, "push", path, "/sdcard/Download/", timeout=300)
                if code == 0:
                    sent += 1
                    await self.adb("-s", serial, "shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
                                   "-d", f"file:///sdcard/Download/{Path(path).name}")
        return f"sent {sent} of {len(paths)} to the phone's Downloads"

    async def close(self):
        self.audio_wanted = False
        for proc in (self.mirror_proc, self.audio_proc):
            if proc and proc.returncode is None:
                proc.terminate()
