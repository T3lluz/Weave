"""The daemon: the phone's link, and a local socket the `weave` command talks to.

Wire format on the link (TCP): one byte of kind, four bytes of length, then the payload.
  1  JSON, both ways
  2  video for the phone: flags, 8 bytes of pts (ms), H.264 Annex B
  3  audio for the phone: flags, 8 bytes of pts (ms), AAC
Flag 1 is a keyframe, flag 2 is decoder setup (SPS/PPS, or AAC's AudioSpecificConfig).
"""

import asyncio
import collections
import hashlib
import ipaddress
import json
import os
import secrets
import signal
import socket
import struct
import time

from . import __version__, discovery, local, pairing
from .capture import CONFIG, KEY
from .config import Store, apply_patch, load, settings_of
from .dashboard import Dashboard
from .desk import Capture, Injector, Phone, SoundCapture, catalog, hypr, shell
from .proc import run

JSON, VIDEO, AUDIO = 1, 2, 3
TAILNET = (ipaddress.ip_network("100.64.0.0/10"), ipaddress.ip_network("fd7a:115c:a1e0::/48"))
# Behind this much unsent video the phone is on a slow link: skip to the next keyframe. The
# limit follows the stream's keyframes (40 KB on Light, 350 KB on Sharp): a few frames' worth.
DROP_MIN, DROP_MAX, RESUME_BELOW = 256_000, 1_500_000, 64_000
ICON_BATCH = 8          # app icons per message: about 80 KB, so the picture never waits long behind one
# What the kernel may hold unsent: past this, a write waits, so a backlog builds where the drop
# logic above sees it instead of as seconds of video in the kernel. The send buffer itself is left
# to autotune, so a long path (a Tailscale relay) still gets its full window in flight.
NOT_SENT_LOW = 64 * 1024
# A picture stepped down for a slow link goes back up one step after this many calm seconds;
# each time it has to come down again soon after, the wait doubles, up to the last.
CALM_START, CALM_MOST = 60, 600
QUIET_AFTER_CHANGE = 3.0   # seconds after a change of quality whose drops are not counted
# A recorder that dies on start is not tried again on that monitor for this long, doubling each time.
RETRY_START, RETRY_MOST = 5.0, 60.0
RETRY_SHOWN = 1.0
# Video frame flag: sent only to catch the decoder up (the frames of a buffered switch before the
# last); the phone decodes it without showing it.
CATCHUP = 4
LIGHTER = {"high": "mid", "mid": "low"}
SHARPER = {"low": "mid", "mid": "high"}


notify = shell.notify


QUALITY_WORD = {"low": "light", "mid": "balanced", "high": "sharp"}


def short_label(mon: dict) -> str:
    """A monitor as a person names it: "LC27G5xT · HDMI" is the LC27G5xT."""
    return (mon.get("label") or mon.get("name") or "the monitor").split(" · ")[0]


def monitor_list(mons: list[dict]) -> list[dict]:
    """The monitors as the phone gets them: size in pixels, and where each sits in the layout."""
    return [{"name": m["name"], "label": m["label"], "w": m["pw"], "h": m["ph"],
             "x": m["x"], "y": m["y"], "lw": m["w"], "lh": m["h"]} for m in mons]


# how often the laptop's clipboard is looked at while the phone keeps the two in step
CLIP_EVERY = 1.0

# how long Hyprland may take to apply a move from weave's mouse before the pointer counts it
MOVE_SETTLE = 0.003
# how often the pointer is checked to still be out of the picture (a Hyprland reload puts it back)
CURSOR_CHECK = 2.0

class Session:
    """One phone, connected."""

    def __init__(self, daemon: "Daemon", reader, writer):
        self.daemon, self.reader, self.writer = daemon, reader, writer
        self.transport = writer.transport
        self.peer = writer.get_extra_info("peername")[0].split("%")[0]
        self.name = "Phone"
        self.key = ""
        self.authorized = False
        # the phone draws the pointer itself, ahead of the picture: the recorders leave it out
        self.local_cursor = False
        # the phone numbers its moves; each pointer report says the last one it already shows,
        # so the phone draws exactly the moves after it on top (recent ones, with when each landed)
        self.moves: collections.deque[tuple[int, float]] = collections.deque(maxlen=64)
        # The recorder whose picture the phone sees, and one starting up beside it for another
        # monitor or quality. The phone keeps seeing the first until the second's first frame
        # is ready, then the two swap at once: no black gap between monitors.
        self.capture: Capture | None = None
        self.pending: Capture | None = None
        # the other monitors, recorded unseen while following the pointer, ready to show at once
        self.warm: dict[str, Capture] = {}
        self._warming = asyncio.Lock()
        # after a buffered switch the link may hold up to a second of video for a moment
        self.grace_until = 0.0
        self.grace_bytes = 0
        self.monitor: dict | None = None
        self.mons: list[dict] = []
        self.follow = True
        self.quality = "mid"
        # what the phone picked; the link may hold the picture lighter for a while (see _fell_behind)
        self.wanted = "mid"
        self.last_drop = 0.0
        self.stepped_up_at = 0.0
        self.quiet_until = 0.0
        self.calm_needed = CALM_START
        self.failed: dict[str, tuple[float, float]] = {}   # monitor: (not before, next wait)
        self.told: set[str] = set()   # monitors the phone was told can't be recorded
        self.want_audio = False
        self.video_wanted = False   # the phone asked for the picture (it may still be starting, or retrying)
        # the laptop's sound on its own, while the picture is off (see _sync_sound)
        self.sound: SoundCapture | None = None
        self.sound_wait = RETRY_START
        self.sound_retry_at = 0.0
        self.sound_told = False
        self.pointer_task = None
        self.catalog_task = None
        # keeping the two clipboards the same: the laptop's looked at once a second, the last text
        # either side had kept so a copy that came from the phone isn't sent straight back
        self.clip_task = None
        self.clip_last: str | None = None
        self.dropping = False
        self.drop_above = DROP_MIN
        self.drops: collections.deque = collections.deque(maxlen=6)   # when the link last fell behind
        self.cursor_task = None
        self.last_cursor = None
        self.last_seq = None
        self.rtt: int | None = None
        self.net = ""
        self.closed = False
        self.replaced = False   # the same phone came back on a new connection: this one leaves quietly

    # ── out ──

    def send(self, kind: int, payload: bytes):
        if not self.closed:
            self.writer.write(struct.pack(">BI", kind, len(payload)) + payload)

    def send_json(self, obj: dict):
        self.send(JSON, json.dumps(obj, separators=(",", ":")).encode())

    def _on_video(self, cap: Capture, flags: int, pts: int, data: bytes):
        if self.failed and not (flags & CONFIG) and cap.monitor in self.failed:
            del self.failed[cap.monitor]   # it records fine now: a later failure starts the wait afresh
            self.told.discard(cap.monitor)
        if cap is self.pending:
            # held back until it has a whole picture to show, then it takes over
            if not (flags & KEY) or not cap.video_config:
                return
            self._promote(cap)
            # the keyframe the phone is waiting for goes out whatever the old stream left queued
            self.drop_above = min(max(int(len(data) * 2.5), DROP_MIN), DROP_MAX)
            self.send(VIDEO, struct.pack(">BQ", flags, pts) + data)
            return
        elif cap is not self.capture:
            return   # an unseen monitor keeps its frames (cap.gop) and nothing else
        waiting = self.transport.get_write_buffer_size()
        limit = self.drop_above + (self.grace_bytes if time.monotonic() < self.grace_until else 0)
        if flags & KEY:
            self.drop_above = min(max(int(len(data) * 2.5), DROP_MIN), DROP_MAX)
        if flags & CONFIG:
            pass
        elif self.dropping:
            if not (flags & KEY) or waiting > RESUME_BELOW:
                return
            self.dropping = False
        elif waiting > limit:
            self.dropping = True
            self._fell_behind()
            return
        self.send(VIDEO, struct.pack(">BQ", flags, pts) + data)

    def _on_audio(self, cap: Capture, flags: int, pts: int, data: bytes):
        if cap is not self.capture and cap is not self.sound:
            return
        if self.want_audio and (flags & CONFIG or self.transport.get_write_buffer_size() < self.drop_above):
            self.send(AUDIO, struct.pack(">BQ", flags, pts) + data)

    def _on_capture_exit(self, cap: Capture, code: int):
        # the phone gets plain words; the recorder's own line goes to the journal
        raw = cap.errors[-1] if cap.errors else f"exit {code}"
        if not cap.stopped:
            print(f"weave: the recorder of {cap.monitor} stopped: {raw}")
        label = short_label(cap.mon)
        # a retry of the picture that died says the same as the death did, not something that sounds final
        retrying = self.capture is None and self.monitor is not None and self.monitor.get("name") == cap.monitor and cap.monitor in self.failed
        reason = (f"The picture from {label} stopped. It comes back by itself in a moment." if cap.got_frame or retrying
                  else f"{label} couldn't be recorded. It tries again by itself.")
        if (not cap.got_frame or cap is self.capture) and not cap.stopped:
            # it never got going, or the picture on screen died: leave that monitor alone a while
            # before trying again (the follow loop tries it again then)
            # a picture that was showing usually died for a passing reason (a mode change, a
            # reload): it is tried again in about a second; one that never started waits longer
            _, wait = self.failed.get(cap.monitor, (0.0, (RETRY_SHOWN if cap.got_frame else RETRY_START) / 2))
            wait = min(wait * 2, RETRY_MOST)
            self.failed[cap.monitor] = (time.monotonic() + wait, wait)
            # said once per monitor (until it records again), and only about one the phone sees or
            # is going to, never an unseen warm one
            # (a picture that was showing and died has the cover say so instead)
            # and only while an older picture is still on screen: with nothing showing, the cover says it
            if not cap.got_frame and cap.monitor not in self.told and not self.closed and cap is self.pending and self.capture is not None:
                self.told.add(cap.monitor)
                self.send_json({"t": "toast", "msg": f"{short_label(cap.mon)} can't be recorded right now."})
        if self.warm.get(cap.monitor) is cap:
            del self.warm[cap.monitor]
            return
        if cap is self.pending:
            self.pending = None
            if not self.capture:
                self.send_json({"t": "video", "on": False, "error": reason, "monitor": cap.monitor})
        elif cap is self.capture:
            self.capture = None
            if self.pending:
                return   # its replacement is on the way
            self.send_json({"t": "video", "on": False, "error": reason, "monitor": cap.monitor})

    def _fell_behind(self):
        """The link keeps falling behind: show a lighter picture rather than a stuttering one."""
        now = time.monotonic()
        if now < self.quiet_until or now < self.grace_until:
            return   # a backlog left by a change of quality or a buffered switch: not the link's fault
        self.last_drop = now
        self.drops.append(now)
        recent = [t for t in self.drops if now - t < 15]
        lighter = LIGHTER.get(self.quality)
        if len(recent) >= 3 and lighter and not self.pending:
            self.drops.clear()
            self.quiet_until = now + QUIET_AFTER_CHANGE
            if now - self.stepped_up_at < self.calm_needed:
                # it went back up too soon: wait longer before the next try
                self.calm_needed = min(self.calm_needed * 2, CALM_MOST)
            self.send_json({"t": "toast", "msg": "The connection is slow, so the picture is lighter for now."})
            self.daemon.log(f"{self.name}'s link was slow: the picture went from {QUALITY_WORD[self.quality]} to {QUALITY_WORD[lighter]}")
            asyncio.create_task(self.set_video(True, "auto" if self.follow else self.monitor["name"], lighter, by_phone=False))

    def _maybe_sharper(self):
        """A picture held lighter goes back a step toward what the phone picked once the link has been calm."""
        if self.quality == self.wanted or self.pending or not self.capture:
            return
        now = time.monotonic()
        if now - max(self.last_drop, self.stepped_up_at) < self.calm_needed:
            return
        sharper = SHARPER.get(self.quality)
        if not sharper:
            return
        self.stepped_up_at = now
        self.quiet_until = now + QUIET_AFTER_CHANGE
        self.send_json({"t": "toast", "msg": "The connection is better: the picture is sharper again."})
        asyncio.create_task(self.set_video(True, "auto" if self.follow else self.monitor["name"], sharper, by_phone=False))

    def _monitor_list(self) -> list[dict]:
        return monitor_list(self.mons)

    def _video_message(self, **extra) -> dict:
        m = self.monitor or {}
        return {"t": "video", "on": True, "monitor": m.get("name"), "label": m.get("label"),
                "w": m.get("pw"), "h": m.get("ph"), "auto": self.follow, "quality": self.quality,
                "monitors": self._monitor_list(), **extra}

    def _promote(self, cap: Capture):
        """The new recorder's first keyframe is here: the phone switches to it now."""
        old = self.capture
        self.capture, self.pending, self.monitor = cap, None, cap.mon
        self.quality = cap.preset
        self.dropping = False
        self.last_cursor = None
        self.last_seq = None
        # "switch" names the monitor the phone saw until now, so it can slide from one to the other
        self.send_json(self._video_message(**({"switch": old.monitor} if old else {})))
        self.send(VIDEO, struct.pack(">BQ", CONFIG, 0) + cap.video_config)
        if self.want_audio and cap.audio_config:
            self.send(AUDIO, struct.pack(">BQ", CONFIG, 0) + cap.audio_config)
        self._retire(old)
        if not self.cursor_task or self.cursor_task.done():
            self.cursor_task = asyncio.create_task(self._track_cursor())
        asyncio.create_task(self._rewarm())
        self.daemon.dashboard.poke()

    def _replayable(self, cap: Capture) -> bool:
        """Whether a warm monitor's frames since its keyframe are worth sending at once: half a
        second of them at most, or barely more than the keyframe itself (a still desktop). A
        busier picture waits for its next keyframe instead, which is then at most half a second off."""
        fps = self.daemon.cfg["video"]["presets"][self.quality][2]
        key = len(cap.gop[0][2])
        size = sum(len(d) for _, _, d in cap.gop)
        return len(cap.gop) <= fps // 2 or size <= key * 1.6

    def _show_warm(self, cap: Capture):
        """Shows a monitor that was being recorded unseen: its frames since the last keyframe go
        out at once, and the phone has the picture as fast as the link carries them."""
        old = self.capture
        self.warm.pop(cap.monitor, None)
        self.capture, self.monitor = cap, cap.mon
        self.dropping = False
        self.last_cursor = None
        self.last_seq = None
        self.send_json(self._video_message(**({"switch": old.monitor} if old else {})))
        self.send(VIDEO, struct.pack(">BQ", CONFIG, 0) + cap.video_config)
        if self.want_audio and cap.audio_config:
            self.send(AUDIO, struct.pack(">BQ", CONFIG, 0) + cap.audio_config)
        size = 0
        gop = list(cap.gop)
        for i, (flags, pts, data) in enumerate(gop):
            # all but the last only catch the decoder up: the phone shows the newest picture, not a fast-forward
            self.send(VIDEO, struct.pack(">BQ", flags | (CATCHUP if i < len(gop) - 1 else 0), pts) + data)
            size += len(data)
        self.grace_bytes, self.grace_until = size, time.monotonic() + 0.8
        if gop:
            self.drop_above = min(max(int(len(gop[0][2]) * 2.5), DROP_MIN), DROP_MAX)
        self._retire(old)
        asyncio.create_task(self._rewarm())
        self.daemon.dashboard.poke()

    def _shelve_pending(self):
        """The recorder that was about to take over is no longer wanted there: keep it recording
        unseen when following the pointer (the pointer may well come back), else stop it."""
        back, self.pending = self.pending, None
        if not back:
            return
        if self._warm_wanted() and back.preset == self.quality and back.monitor not in self.warm and back.proc and back.proc.returncode is None:
            self.warm[back.monitor] = back
        else:
            asyncio.create_task(back.stop())

    def _retire(self, old: Capture | None):
        """The monitor shown until now: kept recording unseen when the stream follows the pointer
        (going back is then as quick), stopped otherwise."""
        if not old:
            return
        if self._warm_wanted() and old.preset == self.quality and old.proc and old.proc.returncode is None and old.monitor not in self.warm:
            self.warm[old.monitor] = old
        else:
            asyncio.create_task(old.stop())

    def _warm_wanted(self) -> bool:
        on = self.capture is not None or self.pending is not None
        return on and self.follow and bool(self.daemon.cfg["video"].get("warm", True)) and len(self.mons) > 1 and not self.closed

    async def _rewarm(self):
        """Records every other monitor unseen while following the pointer; nothing when not."""
        async with self._warming:
            shown = self.capture.monitor if self.capture else None
            pend = self.pending.monitor if self.pending else None
            want = {m["name"]: m for m in self.mons if m["name"] not in (shown, pend)} if self._warm_wanted() else {}
            for name, cap in list(self.warm.items()):
                if name not in want or cap.preset != self.quality:
                    del self.warm[name]
                    asyncio.create_task(cap.stop())
            for name, mon in want.items():
                # what is shown can change while a recorder starts: look again each time
                shown = self.capture.monitor if self.capture else None
                pend = self.pending.monitor if self.pending else None
                if name in self.warm or name in (shown, pend) or not self._warm_wanted() or self._held_off(name):
                    continue
                limit, q, fps = self.daemon.cfg["video"]["presets"][self.quality]
                cap = Capture(mon, self.quality, limit, q, fps, self.daemon.cfg["video"]["audio_kbps"],
                              self._on_video, self._on_audio, self._on_capture_exit, cursor=not self.local_cursor)
                self.warm[name] = cap
                try:
                    await cap.start()
                except OSError:
                    self.warm.pop(name, None)

    async def _go(self, mon: dict):
        """Moves the picture to this monitor as fast as it can: at once when it is being
        recorded unseen and its frames since the last keyframe are few enough to send in one go,
        else as soon as a recorder for it has its next keyframe."""
        if self.closed:
            return
        cap = self.warm.get(mon["name"])
        if cap and cap.preset == self.quality and cap.gop and cap.video_config and self._replayable(cap):
            self._shelve_pending()
            self._show_warm(cap)
            return
        if cap and cap.preset == self.quality:
            # still starting up, or a long way past its keyframe: it takes over at its next one
            self.warm.pop(mon["name"], None)
            if self.pending is not cap:
                self._shelve_pending()
            self.pending = cap
            return
        await self._start(mon, self.quality)

    # ── the screen ──

    def _held_off(self, name: str) -> bool:
        """A monitor whose recorder just died on start, not to be tried again yet."""
        until, _ = self.failed.get(name, (0.0, 0.0))
        return time.monotonic() < until

    async def _start(self, mon: dict, preset: str):
        """Starts a recorder for this monitor beside the one shown; it takes over at its first frame."""
        if self.closed or self._held_off(mon["name"]):
            return
        if self.pending and self.pending.monitor == mon["name"] and self.pending.preset == preset:
            return
        if self.pending:
            asyncio.create_task(self.pending.stop())
            self.pending = None
        limit, q, fps = self.daemon.cfg["video"]["presets"][preset]
        cap = Capture(mon, preset, limit, q, fps, self.daemon.cfg["video"]["audio_kbps"],
                      self._on_video, self._on_audio, self._on_capture_exit, cursor=not self.local_cursor)
        self.pending = cap
        try:
            await cap.start()
        except OSError as err:
            if self.pending is cap:
                self.pending = None
            # couldn't even launch it (the binary mid-upgrade, out of files): wait before the next
            # try, or the follow loop would try again on every turn
            self.failed[mon["name"]] = (time.monotonic() + RETRY_START, RETRY_START)
            if not self.capture:
                print(f"weave: gpu-screen-recorder would not start: {err}")
                self.send_json({"t": "video", "on": False, "error": "The screen recorder wouldn't start on the laptop."})

    async def set_video(self, on: bool, monitor: str = "auto", quality: str | None = None, by_phone: bool = True):
        if on and self.closed:
            return
        if not by_phone and not self.video_wanted:
            return   # a quality step decided before the phone hid the picture: it stays hidden
        self.video_wanted = on
        self._sync_sound()
        # the laptop's pointer stays out of the picture (not off the laptop) while this phone draws its own
        sync = getattr(self.daemon, "sync_cursor", None)
        if sync:
            asyncio.create_task(sync())
        if not on:
            # let go of every recorder first, so none can take over or warm up again meanwhile,
            # then stop them together rather than one after another
            caps = [c for c in (self.capture, self.pending, *self.warm.values()) if c]
            self.capture = self.pending = None
            self.warm = {}
            if self.cursor_task:
                self.cursor_task.cancel()
                self.cursor_task = None
            await asyncio.gather(*(c.stop() for c in caps), return_exceptions=True)
            return
        if quality in self.daemon.cfg["video"]["presets"]:
            if by_phone:
                # a pick of the phone's own: what the picture goes back to after a slow spell
                if quality != self.wanted:
                    self.calm_needed = CALM_START
                self.wanted = quality
            self.quality = quality
        self.mons = await hypr.monitors()
        if not self.video_wanted:
            return   # the phone hid the picture while the monitors were being read
        if not self.mons:
            self.send_json({"t": "video", "on": False, "error": "No monitor to show. Is Hyprland running?"})
            return
        self.follow = monitor == "auto"
        chosen = next((m for m in self.mons if m["name"] == monitor), None)
        if not chosen:
            pos = await hypr.cursor()
            chosen = (hypr.monitor_at(self.mons, *pos) if pos else None) or next((m for m in self.mons if m["focused"]), self.mons[0])
        cap = self.capture
        if cap and cap.monitor == chosen["name"] and cap.preset == self.quality and cap.proc and cap.proc.returncode is None:
            # what is on screen already is what was asked for: only "follow the pointer" changed
            if self.pending:
                asyncio.create_task(self.pending.stop())
                self.pending = None
            self.monitor = chosen
            self.send_json(self._video_message(same=True))
            await self._rewarm()
            return
        if not self.video_wanted:
            return
        await self._go(chosen)
        await self._rewarm()

    # ── sound without the picture ──

    def _sync_sound(self):
        """The laptop's sound rides with the picture. With the picture turned off and the sound
        wanted, ffmpeg sends it alone; once the picture is asked for again, that stops (a picture
        that is starting or retrying brings its own sound, and two would play at once)."""
        want = self.want_audio and not self.closed and not self.video_wanted
        if want and not self.sound:
            if time.monotonic() < self.sound_retry_at:
                return
            cap = SoundCapture(self.daemon.cfg["video"]["audio_kbps"], self._on_audio, self._on_sound_exit)
            cap.started_at = time.monotonic()
            self.sound = cap
            asyncio.create_task(self._start_sound(cap))
        elif not want and self.sound:
            cap, self.sound = self.sound, None
            asyncio.create_task(cap.stop())

    async def _start_sound(self, cap: SoundCapture):
        try:
            await cap.start()
        except OSError as err:
            print(f"weave: the sound alone wouldn't start: {err}")
            if self.sound is cap:
                self.sound = None
                self._sound_failed()

    def _on_sound_exit(self, cap: SoundCapture, code: int):
        if cap is not self.sound:
            return
        self.sound = None
        raw = cap.errors[-1] if cap.errors else f"exit {code}"
        print(f"weave: the sound alone stopped: {raw}")
        if cap.got_sound and time.monotonic() - cap.started_at > 10:
            # it ran a good while: a hiccup (the output changed), so straight back
            self.sound_wait = RETRY_START
            asyncio.get_running_loop().call_later(RETRY_SHOWN, self._sync_sound)
        else:
            self._sound_failed()

    def _sound_failed(self):
        """Tried again later, each wait longer; the phone hears about it once."""
        self.sound_retry_at = time.monotonic() + self.sound_wait
        asyncio.get_running_loop().call_later(self.sound_wait + 0.05, self._sync_sound)
        self.sound_wait = min(self.sound_wait * 2, RETRY_MOST)
        if not self.sound_told:
            self.sound_told = True
            self.send_json({"t": "toast", "msg": "The laptop's sound can't be sent right now. Trying again."})

    # ── the pointer, while the picture is off ──

    async def _report_pointer(self):
        """Where the pointer is on the desk, in layout pixels, for the phone's map of the monitors."""
        mons = await hypr.monitors() or self.mons
        self.mons = mons
        self.send_json({"t": "monitors", "monitors": monitor_list(mons)})
        last, ticks, failing = None, 0, False
        while not self.closed:
            await asyncio.sleep(0.033)
            try:
                ticks += 1
                if ticks % 60 == 0:
                    # a monitor plugged in or out since: the phone redraws its map
                    now = await hypr.monitors()
                    if now and monitor_list(now) != monitor_list(mons):
                        mons = self.mons = now
                        self.send_json({"t": "monitors", "monitors": monitor_list(mons)})
                pos = await hypr.cursor()
                if pos and pos != last:
                    last = pos
                    self.send_json({"t": "ptr", "x": pos[0], "y": pos[1]})
                failing = False
            except asyncio.CancelledError:
                raise
            except Exception as err:
                # once per stretch of failures (Hyprland restarting), not 30 times a second
                if not failing:
                    print(f"weave: telling the phone where the pointer is: {err!r}")
                failing = True

    async def _sync_clipboard(self):
        """Sends the laptop's clipboard to the phone each time it changes, while the phone keeps them in step."""
        # what is there now stays: only what is copied from here on goes over
        if self.clip_last is None:
            self.clip_last = await shell.clip_get(timeout=2)
        while not self.closed:
            await asyncio.sleep(CLIP_EVERY)
            try:
                text = await shell.clip_get(timeout=2)
            except asyncio.CancelledError:
                raise
            except Exception:
                continue
            # an image, or nothing at all, isn't text to send
            if text and text != self.clip_last:
                self.clip_last = text
                self.send_json({"t": "clip", "text": text[:200_000], "sync": True})

    async def _warp(self, x: float, y: float):
        """Puts the pointer at a point of the desk, kept inside the monitor nearest to it."""
        mons = self.mons or await hypr.monitors()
        if not mons:
            return
        m = hypr.monitor_at(mons, int(x), int(y)) or min(
            mons, key=lambda m: (m["x"] + m["w"] / 2 - x) ** 2 + (m["y"] + m["h"] / 2 - y) ** 2)
        x = min(max(x, m["x"]), m["x"] + m["w"] - 1)
        y = min(max(y, m["y"]), m["y"] + m["h"] - 1)
        await hypr.move_cursor(int(x), int(y))

    async def _send_catalog(self):
        """The apps and bindings at once (small), then the apps' icons a few at a time, each batch
        only once the link has sent what was queued: 900 KB in one go would hold the picture up."""
        try:
            listed, binds = await asyncio.gather(catalog.apps(with_icons=False), catalog.binds())
        except Exception as err:
            # an answer either way, so the phone isn't left waiting on its list
            print(f"weave: reading the apps and bindings: {err!r}")
            self.send_json({"t": "catalog", "apps": [], "binds": [], "error": "couldn't read them"})
            return
        # "icon": true says one is on its way, so the phone waits for it rather than noting there is none
        self.send_json({"t": "catalog", "apps": [{"id": a["id"], "name": a["name"], "icon": a["has_icon"]} for a in listed], "binds": binds})
        try:
            apps = await catalog.apps()
        except Exception as err:
            # no "done" after this: the phone would take every icon still on its way as none
            print(f"weave: drawing the apps' icons: {err!r}")
            return
        batch: dict[str, str] = {}
        for a in apps:
            if "icon" in a:
                batch[a["id"]] = a["icon"]
            if len(batch) >= ICON_BATCH or (batch and a is apps[-1]):
                # room on the link first, but a busy one gets the icons within a second anyway
                waited = 0.0
                while not self.closed and self.transport.get_write_buffer_size() > RESUME_BELOW and waited < 1.0:
                    await asyncio.sleep(0.03)
                    waited += 0.03
                if self.closed:
                    return
                self.send_json({"t": "icons", "icons": batch})
                batch = {}
        # every icon there is has gone: an app still without one has none
        if not self.closed:
            self.send_json({"t": "icons", "icons": {}, "done": True})

    async def _launch(self, app_id: str):
        result = await catalog.launch(app_id)
        name = catalog.app_name(app_id) or "That app"
        if result == "gone":
            self.send_json({"t": "toast", "msg": f"{name} isn't on {self.daemon.host} any more."})
        elif result == "failed":
            self.send_json({"t": "toast", "msg": f"{name} wouldn't open on {self.daemon.host}."})

    async def _track_cursor(self):
        """Tells the phone where the pointer is, and moves the stream to the monitor it went to."""
        ticks = 0
        while True:
            await asyncio.sleep(0.033)
            try:
                ticks += 1
                if ticks % 30 == 0:
                    self._maybe_sharper()
                await self._follow_once()
            except asyncio.CancelledError:
                raise
            except Exception as err:   # one bad turn must not end following the pointer
                print(f"weave: following the pointer: {err!r}")

    async def _follow_once(self):
        """One look at the pointer: tell the phone where it is, and follow it to another monitor."""
        m = self.monitor
        if m and not self.capture and not self.pending and not self._held_off(m["name"]):
            # The picture on screen died (a mode change, a Hyprland reload, a monitor unplugged):
            # look at the monitors again, and try it again if it is still there, else go to the
            # monitor under the pointer.
            self.mons = await hypr.monitors() or self.mons
            still = next((x for x in self.mons if x["name"] == m["name"]), None)
            if still:
                self.monitor = still
                await self._start(still, self.quality)
                return
            pos = await hypr.cursor()
            target = (hypr.monitor_at(self.mons, *pos) if pos else None) or (self.mons[0] if self.mons else None)
            if target:
                self.monitor = target
                await self._go(target)
                asyncio.create_task(self._rewarm())
            return
        # the moves Hyprland has had a moment to apply before the pointer is read are in what it says
        looked = time.monotonic() - MOVE_SETTLE
        pos = await hypr.cursor()
        if not pos or not m:
            return
        inside = m["x"] <= pos[0] < m["x"] + m["w"] and m["y"] <= pos[1] < m["y"] + m["h"]
        if inside:
            if self.pending and self.follow and self.pending.monitor != m["name"]:
                # back before the other monitor was ready: stay here, and keep that one warm
                self._shelve_pending()
            seq = next((s for s, at in reversed(self.moves) if at <= looked), None)
            if pos != self.last_cursor or seq != self.last_seq:
                self.last_cursor = pos
                self.last_seq = seq
                report = {"t": "cur", "x": round((pos[0] - m["x"]) / m["w"], 4), "y": round((pos[1] - m["y"]) / m["h"], 4)}
                if seq is not None:
                    report["seq"] = seq
                self.send_json(report)
        elif self.follow:
            target = hypr.monitor_at(self.mons, *pos)
            if not target:
                # a monitor plugged in since: look again
                self.mons = await hypr.monitors() or self.mons
                target = hypr.monitor_at(self.mons, *pos)
                asyncio.create_task(self._rewarm())
            if target and target["name"] != m["name"] and not (self.pending and self.pending.monitor == target["name"]):
                await self._go(target)

    # ── in ──

    async def handle(self, msg: dict):
        if self.closed or self.replaced:
            # a connection given up on: input it still delivers late (a path that came back after
            # the phone reconnected) would press buttons nobody lets go of
            return
        d, inj, t = self.daemon, self.daemon.injector, msg.get("t")
        if t == "m":
            inj.move(float(msg.get("dx", 0)), float(msg.get("dy", 0)))
            if "s" in msg:
                self.moves.append((int(msg["s"]), time.monotonic()))
        elif t == "scroll":
            inj.scroll(float(msg.get("dx", 0)), float(msg.get("dy", 0)))
        elif t == "b":
            inj.button(msg.get("btn", "left"), bool(msg.get("down")))
            if not msg.get("down"):
                d.check_focus(self)
        elif t == "click":
            inj.click(msg.get("btn", "left"), int(msg.get("n", 1)))
            d.check_focus(self)
        elif t == "abs" and self.monitor:
            m = self.monitor
            x = m["x"] + min(max(float(msg.get("x", 0)), 0), 0.999) * m["w"]
            y = m["y"] + min(max(float(msg.get("y", 0)), 0), 0.999) * m["h"]
            await hypr.move_cursor(int(x), int(y))
            if msg.get("click"):
                await asyncio.sleep(0.03)
                inj.click("left")
                d.check_focus(self)
        elif t == "key":
            name = str(msg.get("key", ""))
            if await catalog.bound_code(name):
                inj.key(name, msg.get("mods") or (), msg.get("down"))
            else:
                self.send_json({"t": "toast", "msg": f"That key isn't bound on {d.host} any more."})
        elif t == "text":
            inj.text(str(msg.get("s", "")))
        elif t == "media":
            inj.key(str(msg.get("cmd", "")))
        elif t == "video":
            self.failed.clear()   # asked for outright (Try again, a pick): no waiting out a back-off
            await self.set_video(bool(msg.get("on")), msg.get("monitor") or "auto", msg.get("quality"))
        elif t == "audio":
            self.want_audio = bool(msg.get("on"))
            if self.want_audio:
                self.sound_told, self.sound_wait, self.sound_retry_at = False, RETRY_START, 0.0
            d.dashboard.poke()
            source = self.capture or self.sound
            if self.want_audio and source and source.audio_config:
                self.send(AUDIO, struct.pack(">BQ", CONFIG, 0) + source.audio_config)
            self._sync_sound()
        elif t == "ping":
            self.send_json({"t": "pong", "id": msg.get("id")})
            # the phone says how the link is doing, for the dashboard
            if isinstance(msg.get("rtt"), (int, float)):
                self.rtt = int(msg["rtt"])
            if msg.get("net"):
                self.net = str(msg["net"])[:12]
        elif t == "clip_set":
            text = str(msg.get("text", ""))
            self.clip_last = text
            await shell.clip_set(text)
            # a copy kept in step by the phone says nothing; one sent on purpose says it landed
            if not msg.get("quiet"):
                self.send_json({"t": "toast", "msg": "Copied to the laptop"})
        elif t == "clip_sync":
            if self.clip_task:
                self.clip_task.cancel()
                self.clip_task = None
            if msg.get("on"):
                self.clip_task = asyncio.create_task(self._sync_clipboard())
        elif t == "clip_get":
            text = await shell.clip_get()
            self.send_json({"t": "clip", "text": text[:200_000]})
        elif t == "open":
            url = str(msg.get("url", ""))
            if url.startswith(("http://", "https://")):
                await catalog.open_url(url)
        elif t == "catalog":
            if not self.catalog_task or self.catalog_task.done():
                self.catalog_task = asyncio.create_task(self._send_catalog())
        elif t == "launch":
            asyncio.create_task(self._launch(str(msg.get("app", ""))))
        elif t == "pointer":
            if msg.get("on"):
                if not self.pointer_task or self.pointer_task.done():
                    self.pointer_task = asyncio.create_task(self._report_pointer())
            elif self.pointer_task:
                self.pointer_task.cancel()
                self.pointer_task = None
        elif t == "warp":
            await self._warp(float(msg.get("x", 0)), float(msg.get("y", 0)))
        elif t == "phone_audio":
            result = await d.phone.audio(bool(msg.get("on")))
            if result not in ("on", "off"):
                self.send_json({"t": "toast", "msg": result})
        elif t == "mirror":
            result = await d.phone.mirror(bool(msg.get("on")))
            if result not in ("open", "closed", "already open"):
                self.send_json({"t": "toast", "msg": result})
        elif t == "sys":
            if msg.get("cmd") == "lock":
                await d.lock()
        elif t == "adb":
            # the app says where its adb listens: its own addresses and the port
            ports = [int(p) for p in msg.get("ports", []) if str(p).isdigit()]
            hosts = [self.peer, *[str(h) for h in msg.get("ips", [])]]
            d.phone.hints = [f"{h}:{p}" for p in [*ports, 5555] for h in hosts]

    async def serve(self):
        d = self.daemon
        try:
            while True:
                head = await self.reader.readexactly(5)
                kind, size = struct.unpack(">BI", head)
                if size > 8_000_000:
                    break
                body = await self.reader.readexactly(size)
                if kind != JSON:
                    continue
                try:
                    msg = json.loads(body)
                except ValueError:
                    continue
                if not self.authorized:
                    if msg.get("t") != "hello" or not await d.admit(self, msg):
                        break
                    continue
                try:
                    await self.handle(msg)
                except Exception as err:   # one message the daemon cannot follow must not end the link
                    print(f"weave: bad message {msg.get('t')}: {err!r}")
        except (asyncio.IncompleteReadError, ConnectionError, OSError):
            pass
        finally:
            await self.close()

    async def close(self):
        if self.closed:
            return
        self.closed = True
        if self.pointer_task:
            self.pointer_task.cancel()
        if self.clip_task:
            self.clip_task.cancel()
        await self.set_video(False)
        if self.authorized and not self.replaced:
            self.daemon.sessions.discard(self)
            sync = getattr(self.daemon, "sync_cursor", None)
            if sync:
                await sync()
            if self.daemon.injector:
                self.daemon.injector.release_all()
            self.daemon.log(f"{self.name} disconnected")
            print(f"weave: {self.name} left")
        try:
            self.writer.close()
        except OSError:
            pass


class Daemon:
    def __init__(self):
        self.cfg = load()
        self.devices = Store("devices.json", {})
        self.sessions: set[Session] = set()
        # whether weave took the laptop's pointer out of the screen image (hardware pointers) for a
        # phone that draws its own, and what cursor:no_hardware_cursors was before
        self.cursor_apart = False
        self.codes = pairing.Codes()
        self.cursor_was: int | None = None
        self.cursor_lock = asyncio.Lock()
        self.connections: set[Session] = set()   # every phone on the line, let in or not
        self.pending: dict[str, tuple[Session, asyncio.Future]] = {}
        self.injector: Injector | None = None
        self.phone = Phone(self.cfg, notify, self.broadcast_state)
        self.host = socket.gethostname()
        self._focus_task = None
        self.dashboard = Dashboard(self)
        self.events: collections.deque = collections.deque(maxlen=30)   # what happened lately, for the dashboard
        self.started = int(time.time())
        self._was = (False, False)
        self.mons: list[dict] = []     # the monitors as last read, for the dashboard's picture of the desk

    # ── who gets in ──

    def _allowed_peer(self, peer: str) -> bool:
        try:
            ip = ipaddress.ip_address(peer)
        except ValueError:
            return False
        if ip.version == 6 and ip.ipv4_mapped:
            ip = ip.ipv4_mapped
        if ip.is_loopback or any(ip in net for net in TAILNET):
            return True
        return bool(self.cfg["allow_lan"]) and ip.is_private

    async def admit(self, session: Session, hello: dict) -> bool:
        token = str(hello.get("token", ""))
        if len(token) < 16:
            return False
        key = hashlib.sha256(token.encode()).hexdigest()
        session.key = key
        session.name = str(hello.get("name") or "Phone")[:60]
        session.net = str(hello.get("net") or "")[:12]
        session.local_cursor = hello.get("cursor") == "local"
        ticket = str(hello.get("ticket") or "")
        if key not in self.devices.data and ticket and secrets.compare_digest(ticket, self.dashboard.secret):
            # the dashboard only hands this laptop's ticket to the phone paired with it
            self.devices.data[key] = {"name": session.name, "added": int(time.time()), "via": "dashboard"}
            self.devices.save()
        if key not in self.devices.data and ticket and self.codes.take(ticket, session.name):
            # the code `weave pair` showed: whoever could read this computer's screen may come in
            self.devices.data[key] = {"name": session.name, "added": int(time.time()), "via": "code"}
            self.devices.save()
            self.log(f"{session.name} paired with a code")
        if key not in self.devices.data:
            session.send_json({"t": "pending", "host": self.host})
            self.log(f"{session.name} asked to pair")
            answer = asyncio.get_running_loop().create_future()
            self.pending[key] = (session, answer)
            self.dashboard.poke()
            asyncio.create_task(self._ask(key, session, answer))
            try:
                allowed = await asyncio.wait_for(answer, 120)
            except asyncio.TimeoutError:
                allowed = False
            self.pending.pop(key, None)
            self.dashboard.poke()
            if not allowed:
                session.send_json({"t": "denied"})
                await asyncio.sleep(0.1)
                return False
            self.devices.data[key] = {"name": session.name, "added": int(time.time())}
            self.devices.save()
        self.replace_sessions(key)
        session.authorized = True
        self.sessions.add(session)
        if not self.ensure_injector():
            session.send_json({"t": "toast", "msg": "No access to /dev/uinput on the laptop."})
            return False
        mons = await hypr.monitors()
        session.send_json({
            "t": "welcome", "host": self.host, "ver": __version__, "laptopSound": bool(self.cfg["remote"]["sound"]),
            "monitors": monitor_list(mons),
            # what this weave can do beyond the first version, for the phone to offer
            "caps": shell.CAPS,
            # how far the pointer goes per unit of the phone's moves, so the pointer it draws lands exactly
            "gain": await hypr.pointer_gain(),
            # what the phone needs to wake this laptop from sleep on the same network
            "wake": await shell.wake_info(),
            **self.state(),
        })
        self.dashboard.poke()
        print(f"weave: {session.name} connected from {session.peer}")
        self.log(f"{session.name} connected")
        # a shortcut run from the phone's home screen connects, does its steps and leaves: no notice for that
        if self.cfg["remote"]["notify"] and not hello.get("quiet"):
            notify(f"{session.name} connected", "It can see and control this laptop.", "low", 3000)
        # the phone that just connected is the one to mirror: its address is the best adb guess
        self.phone.hints = [f"{session.peer}:5555", *self.phone.hints][:6]
        return True

    def replace_sessions(self, key: str):
        """The same phone again: its old connection is one it gave up on (a new network, a screen
        closed and opened). It goes quietly in the background, and whatever it left held (a drag
        cut off by the drop) is let go now, before the new one has pressed anything."""
        old = [s for s in self.sessions if s.key == key]
        if old and self.injector:
            # all of the injector's held buttons: there is one phone in this house; with two, one
            # coming back would also let go of the other's drag
            self.injector.release_all()
        for s in old:
            self.sessions.discard(s)
            s.replaced = True
            asyncio.create_task(s.close())

    async def _ask(self, key: str, session: Session, answer: asyncio.Future):
        said = await shell.ask(
            f"{session.name} wants to control this computer",
            f"From {session.peer}. Allow it here, or run: weave allow", 115)
        if not answer.done() and said is not None:
            answer.set_result(said)

    def decide(self, allow: bool) -> str:
        if not self.pending:
            return "nothing is waiting"
        for _, (session, answer) in list(self.pending.items()):
            if not answer.done():
                answer.set_result(allow)
        return "allowed" if allow else "denied"

    # ── shared state ──

    def state(self) -> dict:
        return {"phoneAudio": self.phone.audio_wanted, "mirror": self.phone.mirroring}

    def log(self, text: str):
        """One line of the dashboard's activity list."""
        self.events.append({"at": int(time.time()), "text": text})
        self.dashboard.poke()

    def broadcast_state(self):
        for s in self.sessions:
            s.send_json({"t": "state", **self.state()})
        now = (self.phone.mirroring, self.phone.audio_wanted)
        if now[0] != self._was[0]:
            self.log(f"{self.phone.model}'s screen opened in a window" if now[0] else "The phone's window closed")
        if now[1] != self._was[1]:
            self.log(f"{self.phone.model}'s sound plays here" if now[1] else "The phone's sound went back to the phone")
        self._was = now
        self.dashboard.poke()

    def ensure_injector(self) -> Injector | None:
        if not self.injector:
            try:
                self.injector = Injector()
            except OSError as err:
                print(f"weave: the mouse and keyboard can't be driven: {err}")
        return self.injector

    async def configure(self, patch: dict) -> str:
        """Settings from the dashboard: saved, and what is running starts again with them."""
        changed = apply_patch(self.cfg, patch)
        if not changed:
            return ""
        groups = {g for g, _ in changed}
        if ("video", "warm") in changed:
            for s in self.sessions:
                asyncio.create_task(s._rewarm())
        self.log("Settings changed: " + ", ".join(f"{g.replace('_', ' ')} {k.replace('_', ' ')}" for g, k in changed))
        await self.phone.restart(audio="phone_audio" in groups, mirror=any(g == "mirror" and k != "with_audio" for g, k in changed))
        return ""

    def set_laptop_sound(self, on: bool) -> str:
        """The laptop's sound on the phone, asked for from the dashboard. The phone's app plays it."""
        if not self.sessions:
            return "Open the remote on the phone first: the laptop's sound rides along with it."
        for s in self.sessions:
            s.send_json({"t": "set", "laptopSound": on})
        return ""

    def link_state(self) -> dict:
        """What the dashboard's phone page shows about this laptop."""
        p = self.phone
        return {
            "phone": {"serial": p.serial, "model": p.model if p.serial else None},
            "mirror": p.mirroring, "audio": p.audio_wanted, "audioRunning": p.audio_on, "audioSource": p.audio_source,
            "sessions": [{"name": s.name, "peer": s.peer, "video": s.capture is not None,
                          "monitor": s.monitor["name"] if s.monitor and s.capture else None,
                          "label": s.monitor["label"] if s.monitor and s.capture else None,
                          "follow": s.follow, "quality": s.quality, "sound": s.want_audio,
                          "rtt": s.rtt, "net": s.net} for s in self.sessions],
            "pending": [{"name": s.name, "peer": s.peer} for s, _ in self.pending.values()],
            "paired": sorted({v["name"] for v in self.devices.data.values()}),
            "laptopSound": any(s.want_audio for s in self.sessions),
            "monitors": monitor_list(self.mons),
            "volume": p.volume, "sink": p.sink,
            "settings": settings_of(self.cfg),
            "events": list(self.events)[::-1],
            "started": self.started,
            "missing": [name for name, path in (("scrcpy", p.scrcpy_path), ("adb", p.adb_path)) if not path],
        }

    async def lock(self):
        self.log("Locked")
        await shell.lock()

    async def forget(self, name: str | None) -> int:
        gone = [k for k, v in self.devices.data.items() if name in (None, v["name"])]
        for k in gone:
            del self.devices.data[k]
        self.devices.save()
        for s in list(self.sessions):
            if name in (None, s.name):
                await s.close()
        self.dashboard.poke()
        return len(gone)

    def check_focus(self, session: Session):
        """After a click: did it land in something you type into? Then the phone raises its keyboard."""
        if self._focus_task and not self._focus_task.done():
            self._focus_task.cancel()
        self._focus_task = asyncio.create_task(self._check_focus(session))

    async def _check_focus(self, session: Session):
        last = None
        for delay in (0.18, 0.4):
            await asyncio.sleep(delay)
            window = await hypr.active_window()
            # the phone turns its keyboard's autocorrect off in a terminal: a corrected command is a wrong one
            terminal = window.get("class") in self.cfg["terminals"]
            text = terminal or await shell.text_focused()
            if (text, terminal) != last:
                last = (text, terminal)
                session.send_json({"t": "focus", "text": text, "terminal": terminal})

    # ── the local socket ──

    async def command(self, req: dict) -> dict:
        cmd = req.get("cmd")
        want = req.get("on")
        if cmd == "status":
            serial = await self.phone.find() if req.get("probe") else self.phone.serial
            return {
                "ok": True, "host": self.host, "port": self.cfg["port"], "version": __version__,
                "sessions": [{"name": s.name, "peer": s.peer, "video": s.capture is not None,
                              "monitor": s.monitor["name"] if s.monitor else None} for s in self.sessions],
                "phone": {"serial": serial, "model": self.phone.model if serial else None,
                          "mirror": self.phone.mirroring, "audio": self.phone.audio_wanted,
                          "audio_running": self.phone.audio_on, "audio_source": self.phone.audio_source},
                "paired": [v["name"] for v in self.devices.data.values()],
                "pending": [s.name for s, _ in self.pending.values()],
                "tools": {"scrcpy": self.phone.scrcpy_path, "adb": self.phone.adb_path},
                "dashboard": {"url": self.dashboard.url, "online": self.dashboard.online},
            }
        if cmd == "pair":
            code = self.codes.new()
            addrs = pairing.addresses(await self.dashboard._addresses() if hasattr(self.dashboard, "_addresses") else None)
            # the tailnet lookup ends with the name, which the list has already
            addrs = list(dict.fromkeys(addrs))
            return {"ok": True, "code": code, "name": self.host, "port": self.cfg["port"], "addrs": addrs,
                    "link": pairing.invite(self.host, addrs, self.cfg["port"], code), "ttl": self.codes.ttl,
                    "lan": bool(self.cfg["allow_lan"])}
        if cmd == "pair_status":
            code = str(req.get("code", ""))
            return {"ok": True, "by": self.codes.used.get(code), "left": self.codes.left(code)}
        if cmd == "mirror":
            on = (not self.phone.mirroring) if want == "toggle" else bool(want)
            return {"ok": True, "msg": await self.phone.mirror(on)}
        if cmd == "audio":
            on = (not self.phone.audio_wanted) if want == "toggle" else bool(want)
            return {"ok": True, "msg": await self.phone.audio(on)}
        if cmd == "send":
            return {"ok": True, "msg": await self.phone.push([str(p) for p in req.get("paths", [])])}
        if cmd == "clip":
            if not self.sessions:
                return {"ok": False, "msg": "the remote is not open on the phone"}
            text = await shell.clip_get()
            for s in self.sessions:
                s.send_json({"t": "clip", "text": text[:200_000]})
            return {"ok": True, "msg": "sent the clipboard to the phone"}
        if cmd in ("allow", "deny"):
            return {"ok": True, "msg": self.decide(cmd == "allow")}
        if cmd == "forget":
            return {"ok": True, "msg": f"forgot {await self.forget(req.get('name'))} device(s)"}
        return {"ok": False, "msg": f"unknown command: {cmd}"}

    async def _link(self, reader, writer):
        peer = writer.get_extra_info("peername")[0].split("%")[0]
        if not self._allowed_peer(peer):
            print(f"weave: turned away {peer} (not on this network or the tailnet)")
            writer.close()
            return
        sock = writer.get_extra_info("socket")
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
        try:
            sock.setsockopt(socket.IPPROTO_TCP, getattr(socket, "TCP_NOTSENT_LOWAT", 25), NOT_SENT_LOW)
        except OSError:
            pass
        session = Session(self, reader, writer)
        self.connections.add(session)
        try:
            await session.serve()
        finally:
            self.connections.discard(session)

    async def _prewarm_catalog(self):
        """The apps' icons are rasterised once and cached, so the phone's first look is quick."""
        try:
            await catalog.apps()
        except Exception as err:
            print(f"weave: reading the apps: {err!r}")

    async def sync_cursor(self, check: bool = False):
        """The laptop's pointer kept out of the picture, and on the laptop's screens, while some phone
        draws its own over the picture; Hyprland's own way of drawing it otherwise."""
        async with self.cursor_lock:   # one at a time, so a change and its undoing can't land out of order
            want = any(s.local_cursor and s.video_wanted and not s.closed for s in self.sessions)
            if want and not self.cursor_apart:
                self.cursor_was = await hypr.hardware_cursors()
            elif want == self.cursor_apart and not (check and want and await hypr.hardware_cursors() != 0):
                return
            self.cursor_apart = want
            mode = 0 if want else self.cursor_was
            if mode is not None and await hypr.hardware_cursors() != mode and not await hypr.set_hardware_cursors(mode):
                print(f"weave: Hyprland wouldn't {'take the pointer out of the picture' if want else 'put the pointer back as it was'}")

    async def _keep_cursor_apart(self):
        """A Hyprland reload puts every option back: take the pointer out of the picture again."""
        while True:
            await asyncio.sleep(CURSOR_CHECK)
            if self.cursor_apart:
                try:
                    await self.sync_cursor(check=True)
                except Exception as err:
                    print(f"weave: keeping the pointer out of the picture: {err!r}")

    async def main(self):
        local_line = await local.serve(self.command)
        link = await asyncio.start_server(self._link, port=self.cfg["port"], reuse_address=True)
        print(f"weave {__version__}: listening on port {self.cfg['port']} as {self.host}")
        # systemd stops the daemon with SIGTERM: leave by the door, so the phone's window and sound end with it
        stop = asyncio.Event()
        loop = asyncio.get_running_loop()
        for sig in (signal.SIGTERM, signal.SIGINT):
            try:
                loop.add_signal_handler(sig, stop.set)
            except NotImplementedError:   # Windows: no signal handlers on its event loop
                signal.signal(sig, lambda *_: loop.call_soon_threadsafe(stop.set))
        found = None
        if self.cfg["allow_lan"]:
            found = await discovery.listen(
                self.cfg["port"], lambda: {"name": self.host, "port": self.cfg["port"], "busy": bool(self.sessions)}, self._allowed_peer)
        asyncio.create_task(self.dashboard.run())
        asyncio.create_task(self._prewarm_catalog())
        asyncio.create_task(self._keep_cursor_apart())
        try:
            async with local_line, link:
                await stop.wait()
                # The phones first: a server waits for its connections to end before it closes,
                # so leaving them open held a restart up until systemd lost patience.
                for s in list(self.connections):
                    await s.close()
        finally:
            if found:
                found.close()
            if self.cursor_apart and self.cursor_was is not None:
                await hypr.set_hardware_cursors(self.cursor_was)
            await self.phone.close()
            if self.injector:
                self.injector.close()


def main():
    try:
        asyncio.run(Daemon().main())
    except KeyboardInterrupt:
        pass
