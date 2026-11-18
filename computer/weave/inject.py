"""Mouse and keyboard for the laptop, driven by the phone.

Two uinput devices (the kernel's virtual input, /dev/uinput): a mouse and a keyboard.
Keys by position go through the keyboard. Text the phone's keyboard commits goes through the
keyboard too, as the keys the laptop's layout types it with (keymap.py); what the layout can't
type goes through wtype. Keys and text share one queue so a backspace never overtakes the word
before it.
"""

import asyncio
import fcntl
import json
import os
import shutil
import struct
import time

from . import hypr
from .keymap import Stroke, strokes_for

EV_SYN, EV_KEY, EV_REL = 0, 1, 2
REL_X, REL_Y, REL_HWHEEL, REL_WHEEL, REL_WHEEL_HI_RES, REL_HWHEEL_HI_RES = 0, 1, 6, 8, 11, 12
BTN = {"left": 0x110, "right": 0x111, "middle": 0x112, "back": 0x113, "forward": 0x114}

UI_SET_EVBIT, UI_SET_KEYBIT, UI_SET_RELBIT = 0x40045564, 0x40045565, 0x40045566
UI_DEV_SETUP, UI_DEV_CREATE, UI_DEV_DESTROY = 0x405C5503, 0x5501, 0x5502

# Linux key codes by the name the phone sends. Letters and digits sit where QWERTY has them,
# which holds for the Nordic layouts too, so ctrl+c is ctrl+c.
KEYS = {
    "esc": 1, "1": 2, "2": 3, "3": 4, "4": 5, "5": 6, "6": 7, "7": 8, "8": 9, "9": 10, "0": 11,
    "minus": 12, "equal": 13, "backspace": 14, "tab": 15,
    "q": 16, "w": 17, "e": 18, "r": 19, "t": 20, "y": 21, "u": 22, "i": 23, "o": 24, "p": 25,
    "enter": 28, "ctrl": 29,
    "a": 30, "s": 31, "d": 32, "f": 33, "g": 34, "h": 35, "j": 36, "k": 37, "l": 38,
    "shift": 42, "z": 44, "x": 45, "c": 46, "v": 47, "b": 48, "n": 49, "m": 50,
    "comma": 51, "dot": 52, "slash": 53, "alt": 56, "space": 57, "capslock": 58,
    "f1": 59, "f2": 60, "f3": 61, "f4": 62, "f5": 63, "f6": 64, "f7": 65, "f8": 66, "f9": 67, "f10": 68,
    "f11": 87, "f12": 88, "altgr": 100, "print": 99,
    "home": 102, "up": 103, "pageup": 104, "left": 105, "right": 106, "end": 107, "down": 108,
    "pagedown": 109, "insert": 110, "delete": 111,
    "mute": 113, "voldown": 114, "volup": 115, "super": 125, "menu": 127,
    "next": 163, "play": 164, "prev": 165, "brightdown": 224, "brightup": 225,
    # by position, for bindings on them (Nordic å, ø, æ sit on the first three)
    "leftbrace": 26, "rightbrace": 27, "semicolon": 39, "apostrophe": 40, "grave": 41, "backslash": 43,
    "calc": 140, "pause": 201, "kbdlight": 228, "kbddown": 229, "kbdup": 230, "micmute": 248,
}
MODS = ("ctrl", "shift", "alt", "super", "altgr")


# Power, sleep and wake; suspend (logind hibernates on it); battery, Bluetooth, Wi-Fi, UWB, the
# display and brightness cycles, display off, mobile data and the radio kill switch. Pressed from
# the phone, any of these could put the laptop where the phone can't bring it back.
UNSAFE_CODES = {116, 142, 143, 205, *range(236, 248)}


def key_code(name: str) -> int | None:
    """A key by its name, or by its Linux code as `#30` (what a Hyprland `code:` binding presses)."""
    if name.startswith("#"):
        try:
            code = int(name[1:])
        except ValueError:
            return None
        # the keyboard device has codes 1 to 248; never one that could leave the laptop out of reach
        return code if 1 <= code < 249 and code not in UNSAFE_CODES else None
    return KEYS.get(name)


class _Device:
    def __init__(self, name: str, keys, rels=()):
        self.fd = os.open("/dev/uinput", os.O_WRONLY | os.O_NONBLOCK)
        fcntl.ioctl(self.fd, UI_SET_EVBIT, EV_KEY)
        for code in keys:
            fcntl.ioctl(self.fd, UI_SET_KEYBIT, code)
        if rels:
            fcntl.ioctl(self.fd, UI_SET_EVBIT, EV_REL)
            for code in rels:
                fcntl.ioctl(self.fd, UI_SET_RELBIT, code)
        # struct uinput_setup: input_id (bus, vendor, product, version), name[80], ff_effects_max
        setup = struct.pack("HHHH80sI", 0x03, 0x7A6D, 0x0001, 1, name.encode(), 0)
        fcntl.ioctl(self.fd, UI_DEV_SETUP, setup)
        fcntl.ioctl(self.fd, UI_DEV_CREATE)

    def emit(self, *events):
        """events are (type, code, value); a sync report closes the batch."""
        now = time.time()
        sec, usec = int(now), int((now % 1) * 1_000_000)
        data = b"".join(struct.pack("llHHi", sec, usec, t, c, v) for t, c, v in events)
        data += struct.pack("llHHi", sec, usec, EV_SYN, 0, 0)
        try:
            os.write(self.fd, data)
        except OSError:
            pass

    def close(self):
        try:
            fcntl.ioctl(self.fd, UI_DEV_DESTROY)
            os.close(self.fd)
        except OSError:
            pass


class Injector:
    def __init__(self):
        self.mouse = _Device("Weave Mouse", BTN.values(), (REL_X, REL_Y, REL_WHEEL, REL_HWHEEL, REL_WHEEL_HI_RES, REL_HWHEEL_HI_RES))
        self.keyboard = _Device("Weave Keyboard", range(1, 249))
        self._rx = self._ry = 0.0        # sub-pixel motion carried to the next event
        self._wy = self._wx = 0.0        # wheel, in 120ths of a notch
        self._notch_y = self._notch_x = 0.0
        self._held: set[int] = set()
        self._queue: asyncio.Queue = asyncio.Queue()
        self._worker = asyncio.create_task(self._drain())
        self.has_wtype = shutil.which("wtype") is not None
        # the characters this keyboard's layout types, read from Hyprland and kept a little while
        self._layout_key = None
        self._strokes: dict[str, list[Stroke]] = {}
        self._caps = False
        self._layout_read = 0.0
        # WEAVE_INPUT=log prints every key and text as it is typed; =dry prints without typing.
        # For finding out which half of the path lost a key. It writes what you type to the journal.
        self.debug = os.environ.get("WEAVE_INPUT", "")

    # ── mouse: straight to the device, order against keys does not matter ──

    def move(self, dx: float, dy: float):
        self._rx += dx
        self._ry += dy
        ix, iy = int(self._rx), int(self._ry)
        if not ix and not iy:
            return
        self._rx -= ix
        self._ry -= iy
        self.mouse.emit((EV_REL, REL_X, ix), (EV_REL, REL_Y, iy))

    def button(self, name: str, down: bool):
        """Presses are counted: the phone can hold one button two ways at once (its Left key and a
        held finger on the pad), and the laptop's button comes up only when the last of them lets go."""
        code = BTN.get(name)
        if code is None:
            return
        presses = getattr(self, "_presses", None)
        if presses is None:
            presses = self._presses = {}
        before = presses.get(code, 0)
        after = before + 1 if down else max(before - 1, 0)
        presses[code] = after
        if (before == 0) == (after == 0):
            return   # still held, or already up: nothing changes on the laptop
        (self._held.add if down else self._held.discard)(code)
        self.mouse.emit((EV_KEY, code, 1 if down else 0))

    def click(self, name: str = "left", count: int = 1):
        code = BTN.get(name)
        if code is None:
            return
        if getattr(self, "_presses", {}).get(code):
            return   # a tap while that button is held would let go of it mid-drag
        for _ in range(max(1, min(count, 3))):
            self.mouse.emit((EV_KEY, code, 1))
            self.mouse.emit((EV_KEY, code, 0))

    def scroll(self, dx: float, dy: float):
        """dx, dy in wheel notches; positive dy scrolls up, positive dx right."""
        events = []
        self._wy += dy * 120
        self._wx += dx * 120
        hy, hx = int(self._wy), int(self._wx)
        if hy:
            self._wy -= hy
            events.append((EV_REL, REL_WHEEL_HI_RES, hy))
            self._notch_y += hy / 120
            whole = int(self._notch_y)
            if whole:
                self._notch_y -= whole
                events.append((EV_REL, REL_WHEEL, whole))
        if hx:
            self._wx -= hx
            events.append((EV_REL, REL_HWHEEL_HI_RES, hx))
            self._notch_x += hx / 120
            whole = int(self._notch_x)
            if whole:
                self._notch_x -= whole
                events.append((EV_REL, REL_HWHEEL, whole))
        if events:
            self.mouse.emit(*events)

    # ── keys and text: one queue, in the order they were typed ──

    def key(self, name: str, mods=(), down: bool | None = None):
        self._queue.put_nowait(("key", name, tuple(mods), down))

    def text(self, value: str):
        if value:
            self._queue.put_nowait(("text", value))

    def release_all(self):
        """Lets go of whatever a dropped connection left held."""
        for code in list(self._held):
            device = self.mouse if code >= 0x100 else self.keyboard
            device.emit((EV_KEY, code, 0))
        self._held.clear()
        self._presses = {}

    def _key_now(self, name: str, mods, down):
        code = key_code(name)
        if code is None:
            return
        if down is not None:
            (self._held.add if down else self._held.discard)(code)
            self.keyboard.emit((EV_KEY, code, 1 if down else 0))
            return
        mod_codes = [KEYS[m] for m in mods if m in MODS and KEYS[m] not in self._held]
        for m in mod_codes:
            self.keyboard.emit((EV_KEY, m, 1))
        self.keyboard.emit((EV_KEY, code, 1))
        self.keyboard.emit((EV_KEY, code, 0))
        for m in reversed(mod_codes):
            self.keyboard.emit((EV_KEY, m, 0))

    async def _layout(self):
        """The layout Hyprland gives weave's keyboard (and Caps Lock), read again after 10 s."""
        if time.monotonic() - self._layout_read < 10:
            return
        self._layout_read = time.monotonic()
        try:
            keyboards = json.loads(await hypr.request("j/devices") or "{}").get("keyboards", [])
        except ValueError:
            return
        mine = next((k for k in keyboards if k.get("name") == "weave-keyboard"), None)
        if not mine:
            return
        self._caps = bool(mine.get("capsLock"))
        key = tuple(str(mine.get(f) or "") for f in ("layout", "variant", "options", "model", "rules")) + (int(mine.get("active_layout_index") or 0),)
        if key != self._layout_key:
            self._layout_key = key
            self._strokes = await asyncio.to_thread(strokes_for, *key[:5], group=key[5])

    def _stroke(self, s: Stroke, shift: bool):
        mods = [m for m, on in ((KEYS["shift"], shift), (KEYS["altgr"], s.altgr)) if on and m not in self._held]
        for m in mods:
            self.keyboard.emit((EV_KEY, m, 1))
        self.keyboard.emit((EV_KEY, s.code, 1))
        self.keyboard.emit((EV_KEY, s.code, 0))
        for m in reversed(mods):
            self.keyboard.emit((EV_KEY, m, 0))

    async def _wtype(self, part: str):
        if not part or not self.has_wtype:
            return
        try:
            proc = await asyncio.create_subprocess_exec("wtype", "--", part, stderr=asyncio.subprocess.DEVNULL)
            await asyncio.wait_for(proc.wait(), 5)
        except (OSError, asyncio.TimeoutError):
            pass
        # the compositor swaps keymaps between wtype's keyboard and ours; give it a beat
        await asyncio.sleep(0.004)

    async def _type(self, value: str):
        # Enter and Tab as real keys: programs treat a typed newline and the Enter key differently.
        await self._layout()
        rest = ""   # what the layout can't type, for wtype
        for ch in value:
            strokes = self._strokes.get(ch)
            if ch in "\n\r\t" or strokes:
                await self._wtype(rest)
                rest = ""
            if ch in ("\n", "\r"):
                self._key_now("enter", (), None)
            elif ch == "\t":
                self._key_now("tab", (), None)
            elif strokes:
                # Caps Lock turns a letter's case; Shift turns it back
                flip = self._caps and ch.lower() != ch.upper()
                for s in strokes:
                    self._stroke(s, s.shift != flip if len(strokes) == 1 else s.shift)
            else:
                rest += ch
        await self._wtype(rest)

    async def _drain(self):
        while True:
            item = await self._queue.get()
            if self.debug:
                print(f"weave: input {item!r}")
                if self.debug == "dry":
                    continue
            try:
                if item[0] == "text":
                    value = item[1]
                    # letters typed in a burst go out as one string
                    while not self._queue.empty() and self._queue._queue[0][0] == "text":
                        value += self._queue.get_nowait()[1]
                    await self._type(value)
                else:
                    self._key_now(item[1], item[2], item[3])
                    await asyncio.sleep(0.002)
            except Exception as err:  # one bad event must not stop typing for good
                print(f"weave: input: {err}")

    def close(self):
        self._worker.cancel()
        self.release_all()
        self.mouse.close()
        self.keyboard.close()
