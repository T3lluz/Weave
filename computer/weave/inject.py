"""Mouse, keyboard and touchpad for the laptop, driven by the phone.

Three uinput devices (the kernel's virtual input, /dev/uinput): a mouse, a keyboard and a
touchpad. One finger on the phone's pad is the mouse. Two fingers and more are laid on the
touchpad where they are on the phone, so the desktop reads them as it reads the laptop's own
pad: scrolling that rolls on, a sideways swipe that goes back in a browser, pinching, and
whatever gestures Hyprland is set to act on.
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

EV_SYN, EV_KEY, EV_REL, EV_ABS = 0, 1, 2, 3
ABS_X, ABS_Y, ABS_MT_SLOT, ABS_MT_POSITION_X, ABS_MT_POSITION_Y, ABS_MT_TRACKING_ID = 0x00, 0x01, 0x2F, 0x35, 0x36, 0x39
BTN_TOUCH = 0x14A
# which of these is down says how many fingers are on the pad: one, two, three, four, five
BTN_TOOL = (0x145, 0x14D, 0x14E, 0x14F, 0x148)
REL_X, REL_Y, REL_HWHEEL, REL_WHEEL, REL_WHEEL_HI_RES, REL_HWHEEL_HI_RES = 0, 1, 6, 8, 11, 12
BTN = {"left": 0x110, "right": 0x111, "middle": 0x112, "back": 0x113, "forward": 0x114}

UI_SET_EVBIT, UI_SET_KEYBIT, UI_SET_RELBIT = 0x40045564, 0x40045565, 0x40045566
UI_DEV_SETUP, UI_DEV_CREATE, UI_DEV_DESTROY = 0x405C5503, 0x5501, 0x5502
UI_SET_ABSBIT, UI_SET_PROPBIT, UI_ABS_SETUP = 0x40045567, 0x4004556E, 0x401C5504

# The touchpad: far larger than any phone, so the fingers (laid round its middle) never reach
# the rims, where libinput takes a touch for a palm. Sizes in mm; the kernel gets units.
PAD_MM, PAD_UNITS_PER_MM, PAD_FINGERS = 240, 20, 5

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
    def __init__(self, name: str, keys, rels=(), axes=(), product: int = 0x0001):
        """[axes] are (code, lowest, highest, units per mm): an absolute device, a pointer one (a touchpad)."""
        self.fd = os.open("/dev/uinput", os.O_WRONLY | os.O_NONBLOCK)
        try:
            fcntl.ioctl(self.fd, UI_SET_EVBIT, EV_KEY)
            for code in keys:
                fcntl.ioctl(self.fd, UI_SET_KEYBIT, code)
            if rels:
                fcntl.ioctl(self.fd, UI_SET_EVBIT, EV_REL)
                for code in rels:
                    fcntl.ioctl(self.fd, UI_SET_RELBIT, code)
            if axes:
                fcntl.ioctl(self.fd, UI_SET_EVBIT, EV_ABS)
                for code, *_ in axes:
                    fcntl.ioctl(self.fd, UI_SET_ABSBIT, code)
                fcntl.ioctl(self.fd, UI_SET_PROPBIT, 0)   # INPUT_PROP_POINTER: moves a pointer, isn't a screen
            # struct uinput_setup: input_id (bus, vendor, product, version), name[80], ff_effects_max
            setup = struct.pack("HHHH80sI", 0x03, 0x7A6D, product, 1, name.encode(), 0)
            fcntl.ioctl(self.fd, UI_DEV_SETUP, setup)
            for code, low, high, per_mm in axes:
                # struct uinput_abs_setup: code, then input_absinfo (value, min, max, fuzz, flat, resolution)
                fcntl.ioctl(self.fd, UI_ABS_SETUP, struct.pack("H2x6i", code, 0, low, high, 0, 0, per_mm))
            fcntl.ioctl(self.fd, UI_DEV_CREATE)
        except OSError:
            os.close(self.fd)
            raise

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


class Touchpad:
    """The fingers on the phone's pad, laid on a touchpad of the laptop's own.

    Each frame says where every finger is, in mm on the phone. The first frame of a touch puts
    the fingers round the pad's middle; they keep their places to each other from there. The
    kernel is told only what changed (protocol B: a slot per finger)."""

    def __init__(self, device=None):
        top = PAD_MM * PAD_UNITS_PER_MM
        self.device = device or _Device(
            "Weave Touchpad", (BTN["left"], BTN["right"], BTN_TOUCH, *BTN_TOOL), product=0x0003,
            axes=((ABS_X, 0, top, PAD_UNITS_PER_MM), (ABS_Y, 0, top, PAD_UNITS_PER_MM),
                  (ABS_MT_SLOT, 0, PAD_FINGERS - 1, 0), (ABS_MT_TRACKING_ID, 0, 65535, 0),
                  (ABS_MT_POSITION_X, 0, top, PAD_UNITS_PER_MM), (ABS_MT_POSITION_Y, 0, top, PAD_UNITS_PER_MM)))
        self._slots: dict[int, int] = {}                  # the phone's number for a finger: its slot
        self._at: dict[int, tuple[int, int]] = {}         # slot: where it was last put
        self._origin = (0.0, 0.0)
        self._serial = 0
        self._turned = False
        self._first = None

    @property
    def touched(self) -> bool:
        return bool(self._slots)

    def frame(self, fingers, turned: bool = False):
        """[fingers] are (number, x mm, y mm), all that are on the pad now; none lifts them all.
        [turned] lays them the other way round (both axes), which is what turns the scrolling
        round for a phone that wants it the other way than this desktop's touchpads have it."""
        fingers = {int(n): (float(x), float(y)) for n, x, y in list(fingers)[:PAD_FINGERS]}
        if not self._slots:
            if not fingers:
                return
            # a new touch: its middle goes on the pad's middle
            self._origin = (sum(x for x, _ in fingers.values()) / len(fingers), sum(y for _, y in fingers.values()) / len(fingers))
            self._turned = turned
        events = []
        before = len(self._slots)
        for number in [n for n in self._slots if n not in fingers]:
            slot = self._slots.pop(number)
            self._at.pop(slot, None)
            events += [(EV_ABS, ABS_MT_SLOT, slot), (EV_ABS, ABS_MT_TRACKING_ID, -1)]
        sign = -1 if self._turned else 1
        low, high = 4 * PAD_UNITS_PER_MM, (PAD_MM - 4) * PAD_UNITS_PER_MM
        for number, (x, y) in fingers.items():
            px = int(min(max((PAD_MM / 2 + sign * (x - self._origin[0])) * PAD_UNITS_PER_MM, low), high))
            py = int(min(max((PAD_MM / 2 + sign * (y - self._origin[1])) * PAD_UNITS_PER_MM, low), high))
            slot = self._slots.get(number)
            if slot is None:
                free = [s for s in range(PAD_FINGERS) if s not in self._slots.values()]
                if not free:
                    continue
                slot = self._slots[number] = free[0]
                self._serial = (self._serial + 1) % 65536
                events += [(EV_ABS, ABS_MT_SLOT, slot), (EV_ABS, ABS_MT_TRACKING_ID, self._serial),
                           (EV_ABS, ABS_MT_POSITION_X, px), (EV_ABS, ABS_MT_POSITION_Y, py)]
            elif self._at.get(slot) != (px, py):
                events += [(EV_ABS, ABS_MT_SLOT, slot), (EV_ABS, ABS_MT_POSITION_X, px), (EV_ABS, ABS_MT_POSITION_Y, py)]
            self._at[slot] = (px, py)
        after = len(self._slots)
        if before != after:
            if (before == 0) != (after == 0):
                events.append((EV_KEY, BTN_TOUCH, 1 if after else 0))
            if before:
                events.append((EV_KEY, BTN_TOOL[before - 1], 0))
            if after:
                events.append((EV_KEY, BTN_TOOL[after - 1], 1))
        # the single-touch axes follow the first finger, as a real pad's do
        first = self._at[min(self._at)] if self._at else None
        if first and first != self._first:
            events += [(EV_ABS, ABS_X, first[0]), (EV_ABS, ABS_Y, first[1])]
        self._first = first
        if events:
            self.device.emit(*events)

    def lift(self):
        self.frame(())

    def close(self):
        self.lift()
        self.device.close()


class Injector:
    def __init__(self):
        self.mouse = _Device("Weave Mouse", BTN.values(), (REL_X, REL_Y, REL_WHEEL, REL_HWHEEL, REL_WHEEL_HI_RES, REL_HWHEEL_HI_RES))
        self.keyboard = _Device("Weave Keyboard", range(1, 249))
        # the touchpad is an extra: without it two fingers scroll as a mouse's wheel does
        try:
            self.pad: Touchpad | None = Touchpad()
        except OSError as err:
            print(f"weave: no touchpad for the phone's fingers, they scroll as a wheel instead: {err}")
            self.pad = None
        self._rx = self._ry = 0.0        # sub-pixel motion carried to the next event
        self._wy = self._wx = 0.0        # wheel, in 120ths of a notch
        self._notch_y = self._notch_x = 0.0
        self._held: set[int] = set()
        self._queue: asyncio.Queue = asyncio.Queue()
        self._typing = False          # a text is on its way out right now
        self._closing = False
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

    def release_all(self, finish: bool = False):
        """Lets go of whatever a dropped connection left held, and stops its typing where it is:
        what waited to be typed is dropped and the text on its way out is cut off, so the rest
        of a sentence never comes out under the next phone's fingers. With [finish], what was
        asked for is still typed to the end: a shortcut run from the phone's home screen closes
        its line the moment its last step is sent."""
        if not finish and getattr(self, "_worker", None):
            while not self._queue.empty():
                self._queue.get_nowait()
            if self._typing and not self._closing:
                self._worker.cancel()
                self._typing = False
                self._worker = asyncio.create_task(self._drain())
        for code in list(self._held):
            device = self.mouse if code >= 0x100 else self.keyboard
            device.emit((EV_KEY, code, 0))
        self._held.clear()
        self._presses = {}
        if getattr(self, "pad", None):
            self.pad.lift()   # fingers a dropped phone left on the touchpad

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
        proc = None
        try:
            proc = await asyncio.create_subprocess_exec("wtype", "--", part, stderr=asyncio.subprocess.DEVNULL)
            await asyncio.wait_for(proc.wait(), 5)
        except (OSError, asyncio.TimeoutError):
            pass
        except asyncio.CancelledError:
            # cut off (the phone dropped): the rest of this stretch isn't typed either
            if proc and proc.returncode is None:
                proc.kill()
            raise
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
                    self._typing = True
                    try:
                        await self._type(value)
                    finally:
                        self._typing = False
                else:
                    self._key_now(item[1], item[2], item[3])
                    await asyncio.sleep(0.002)
            except Exception as err:  # one bad event must not stop typing for good
                print(f"weave: input: {err}")

    def close(self):
        self._closing = True
        self._worker.cancel()
        self.release_all()
        if self.pad:
            self.pad.close()
        self.mouse.close()
        self.keyboard.close()
