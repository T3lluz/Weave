"""Mouse and keyboard on Windows, driven by the phone: SendInput, the same door a real mouse
and keyboard come through.

The pointer is placed, not nudged: each move is added to where the pointer is and sent as an
absolute position, so Windows' own acceleration never touches it and the phone's drawn pointer
lands exactly (a gain of 1).

Text is typed with the keys the PC's own layout has for each character, as a keyboard would.
What the layout can't type (an emoji, a letter of another alphabet) goes as the character
itself. Either way it is typed at a typist's pace, not in one burst: programs that take their
keys in the background (Windows 11's Notepad is one) read Shift late and blur a burst of
characters together. Keys and text share one queue, so a backspace never overtakes the word
before it.
"""

import asyncio
import ctypes
import os

from . import api

BUTTONS = {  # name: (down flag, up flag, which X button)
    "left": (0x0002, 0x0004, 0), "right": (0x0008, 0x0010, 0), "middle": (0x0020, 0x0040, 0),
    "back": (0x0080, 0x0100, 1), "forward": (0x0080, 0x0100, 2),
}

# Windows' virtual keys by the name the phone sends. True marks an extended key.
VK = {
    "esc": (0x1B, False), "tab": (0x09, False), "enter": (0x0D, False), "backspace": (0x08, False), "space": (0x20, False),
    "ctrl": (0xA2, False), "shift": (0xA0, False), "alt": (0xA4, False), "altgr": (0xA5, True), "super": (0x5B, True),
    "menu": (0x5D, True), "capslock": (0x14, False), "print": (0x2C, True), "pause": (0x13, False),
    "home": (0x24, True), "end": (0x23, True), "pageup": (0x21, True), "pagedown": (0x22, True),
    "insert": (0x2D, True), "delete": (0x2E, True),
    "left": (0x25, True), "up": (0x26, True), "right": (0x27, True), "down": (0x28, True),
    "mute": (0xAD, True), "voldown": (0xAE, True), "volup": (0xAF, True),
    "next": (0xB0, True), "prev": (0xB1, True), "play": (0xB3, True), "calc": (0xB7, True),
    "minus": (0xBD, False), "equal": (0xBB, False), "comma": (0xBC, False), "dot": (0xBE, False), "slash": (0xBF, False),
    "semicolon": (0xBA, False), "apostrophe": (0xDE, False), "grave": (0xC0, False),
    "leftbrace": (0xDB, False), "rightbrace": (0xDD, False), "backslash": (0xDC, False),
    **{chr(c): (ord(chr(c).upper()), False) for c in range(ord("a"), ord("z") + 1)},
    **{str(d): (0x30 + d, False) for d in range(10)},
    **{f"f{n}": (0x6F + n, False) for n in range(1, 13)},
}
MODS = ("ctrl", "shift", "alt", "super", "altgr")
# Between two characters of typed text, in seconds. Programs that take their keys in the
# background read Shift, and characters sent as themselves, a moment after they arrive: typed in
# one burst, a capital loses its Shift and such characters blur into the last one. A fast typist's
# pace is safe for all of them.
TYPING_PACE = 0.012


def key_vk(name: str) -> tuple[int, bool] | None:
    """A key by its name. A raw Linux code (`#30`, what a Hyprland binding presses) means nothing here."""
    return VK.get(name)


def layout_stroke(scan: int) -> tuple[int, bool, bool] | None:
    """What VkKeyScanEx says about a character, as (key, with shift, with AltGr); None when the
    layout has no key for it, or only one that needs some other chord."""
    if scan == -1:
        return None
    vk, state = scan & 0xFF, (scan >> 8) & 0xFF
    if state in (0, 1):
        return vk, bool(state & 1), False
    if state in (6, 7):   # ctrl + alt is AltGr
        return vk, bool(state & 1), True
    return None


def normalized(x: float, y: float, desk: tuple[int, int, int, int]) -> tuple[int, int]:
    """A point of the desktop as SendInput wants it: 0 to 65535 across all the monitors together."""
    left, top, width, height = desk
    nx = round((x - left) * 65535 / max(width - 1, 1))
    ny = round((y - top) * 65535 / max(height - 1, 1))
    return min(max(nx, 0), 65535), min(max(ny, 0), 65535)


class Injector:
    def __init__(self):
        if not api.WINDOWS:
            raise OSError("not Windows")
        api.dpi_aware()
        self._at: tuple[float, float] | None = None   # where the pointer was put last, to the fraction
        self._wy = self._wx = 0.0                     # wheel, in 120ths of a notch
        self._held: set[tuple[str, object]] = set()
        self._presses: dict[str, int] = {}
        self._queue: asyncio.Queue = asyncio.Queue()
        self.pace = float(os.environ.get("WEAVE_TYPING_PACE", TYPING_PACE))
        self._typist = asyncio.get_running_loop().create_task(self._type())

    # ── out ──

    def _send(self, *inputs):
        n = len(inputs)
        array = (api.INPUT * n)(*inputs)
        api.user32.SendInput(n, array, ctypes.sizeof(api.INPUT))

    @staticmethod
    def _mouse(flags: int, dx: int = 0, dy: int = 0, data: int = 0):
        i = api.INPUT(type=api.INPUT_MOUSE)
        i.mi = api.MOUSEINPUT(dx, dy, data & 0xFFFFFFFF, flags, 0, 0)
        return i

    @staticmethod
    def _key(vk: int, up: bool, extended: bool = False, scan: int = 0, unicode: bool = False):
        flags = (api.KEYEVENTF_KEYUP if up else 0) | (api.KEYEVENTF_EXTENDEDKEY if extended else 0) | (api.KEYEVENTF_UNICODE if unicode else 0)
        i = api.INPUT(type=api.INPUT_KEYBOARD)
        i.ki = api.KEYBDINPUT(vk, scan, flags, 0, 0)
        return i

    # ── the mouse ──

    def _desk(self) -> tuple[int, int, int, int]:
        m = api.user32.GetSystemMetrics
        return m(api.SM_XVIRTUALSCREEN), m(api.SM_YVIRTUALSCREEN), m(api.SM_CXVIRTUALSCREEN), m(api.SM_CYVIRTUALSCREEN)

    def move(self, dx: float, dy: float):
        p = api.POINT()
        api.user32.GetCursorPos(ctypes.byref(p))
        # the fraction left over from last time counts, unless something else moved the pointer since
        at = self._at if self._at and (round(self._at[0]), round(self._at[1])) == (p.x, p.y) else (float(p.x), float(p.y))
        left, top, width, height = desk = self._desk()
        x = min(max(at[0] + dx, left), left + width - 1)
        y = min(max(at[1] + dy, top), top + height - 1)
        self._at = (x, y)
        if (round(x), round(y)) == (p.x, p.y):
            return
        nx, ny = normalized(round(x), round(y), desk)
        self._send(self._mouse(api.MOUSEEVENTF_MOVE | api.MOUSEEVENTF_ABSOLUTE | api.MOUSEEVENTF_VIRTUALDESK, nx, ny))

    def button(self, name: str, down: bool):
        """Presses are counted: the phone can hold one button two ways at once (its Left key and a
        held finger on the pad), and the button comes up only when the last of them lets go."""
        spec = BUTTONS.get(name)
        if spec is None:
            return
        before = self._presses.get(name, 0)
        after = before + 1 if down else max(before - 1, 0)
        self._presses[name] = after
        if (before == 0) == (after == 0):
            return
        (self._held.add if down else self._held.discard)(("button", name))
        self._send(self._mouse(spec[0] if down else spec[1], data=spec[2]))

    def click(self, name: str = "left", count: int = 1):
        spec = BUTTONS.get(name)
        if spec is None or self._presses.get(name):
            return   # a tap while that button is held would let go of it mid-drag
        for _ in range(max(1, min(count, 3))):
            self._send(self._mouse(spec[0], data=spec[2]), self._mouse(spec[1], data=spec[2]))

    def scroll(self, dx: float, dy: float):
        """dx, dy in wheel notches; positive dy scrolls up, positive dx right."""
        self._wy += dy * 120
        self._wx += dx * 120
        hy, hx = int(self._wy), int(self._wx)
        if hy:
            self._wy -= hy
            self._send(self._mouse(api.MOUSEEVENTF_WHEEL, data=hy))
        if hx:
            self._wx -= hx
            self._send(self._mouse(api.MOUSEEVENTF_HWHEEL, data=hx))

    # ── keys and text: one queue, in the order they were typed ──

    def key(self, name: str, mods=(), down: bool | None = None):
        self._queue.put_nowait(("key", name, tuple(mods), down))

    def text(self, value: str):
        if value:
            self._queue.put_nowait(("text", value))

    async def _type(self):
        while True:
            job = await self._queue.get()
            try:
                if job[0] == "key":
                    self._key_now(*job[1:])
                else:
                    await self._text_now(job[1])
            except Exception as err:   # one bad key must not end the typing
                print(f"weave: typing: {err!r}")

    def _key_now(self, name: str, mods, down):
        spec = key_vk(name)
        if spec is None:
            return
        vk, extended = spec
        if down is not None:
            (self._held.add if down else self._held.discard)(("key", name))
            self._send(self._key(vk, not down, extended))
            return
        held = [VK[m] for m in mods if m in MODS and ("key", m) not in self._held]
        self._send(*[self._key(v, False, e) for v, e in held], self._key(vk, False, extended), self._key(vk, True, extended),
                   *[self._key(v, True, e) for v, e in reversed(held)])

    def _layout(self):
        """The keyboard layout of whatever has the focus: that is the one its keys are read by."""
        thread = api.user32.GetWindowThreadProcessId(api.user32.GetForegroundWindow(), None)
        return api.user32.GetKeyboardLayout(thread)

    async def _text_now(self, value: str):
        layout = self._layout()
        for ch in value:
            if ch == "\n":
                self._send(self._key(0x0D, False), self._key(0x0D, True))
                await asyncio.sleep(self.pace)
                continue
            stroke = layout_stroke(api.user32.VkKeyScanExW(ch, layout)) if len(ch.encode("utf-16-le")) == 2 else None
            if stroke:
                vk, shift, altgr = stroke
                mods = [v for v, on in ((0xA0, shift), (0xA2, altgr), (0xA4, altgr)) if on]
                if mods:
                    # held a moment before the key and a moment after, as a hand holds it: a
                    # program that looks at Shift late must still find it down
                    self._send(*[self._key(m, False) for m in mods])
                    await asyncio.sleep(self.pace / 2)
                self._send(self._key(vk, False), self._key(vk, True))
                if mods:
                    await asyncio.sleep(self.pace / 2)
                    self._send(*[self._key(m, True) for m in reversed(mods)])
            else:
                # no key for it: the character itself (both halves of one past the first 65536 together)
                data = ch.encode("utf-16-le")
                units = [int.from_bytes(data[i:i + 2], "little") for i in range(0, len(data), 2)]
                self._send(*[self._key(0, False, scan=u, unicode=True) for u in units], *[self._key(0, True, scan=u, unicode=True) for u in units])
            await asyncio.sleep(self.pace)

    def release_all(self):
        """Lets go of whatever a dropped connection left held."""
        for kind, name in list(self._held):
            if kind == "button":
                self._send(self._mouse(BUTTONS[name][1], data=BUTTONS[name][2]))
            else:
                vk, extended = VK[name]
                self._send(self._key(vk, True, extended))
        self._held.clear()
        self._presses = {}

    def close(self):
        self._typist.cancel()
        self.release_all()
