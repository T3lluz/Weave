"""What the daemon asks of the desktop around it, on Windows: the clipboard, notices, the
pairing question, the lock, whether a text field has the focus, and the network card to wake."""

import asyncio
import ctypes
import ipaddress
import os
import threading
import time
from ctypes import wintypes

from ..proc import run
from . import api, helpers, screen

PLATFORM = "windows"
# what this computer can do beyond the first version, for the phone to offer
CAPS = ["catalog", "launch", "pointer", "warp", "sound", "localcursor", "clipsync", "moveseq"]
NEEDS = ["ffmpeg"]

CF_UNICODETEXT, GMEM_MOVEABLE = 13, 0x0002


def _open_clipboard() -> bool:
    # another program may hold it for a moment
    for _ in range(8):
        if api.user32.OpenClipboard(None):
            return True
        time.sleep(0.02)
    return False


def _clip_get() -> str:
    if not _open_clipboard():
        return ""
    try:
        handle = api.user32.GetClipboardData(CF_UNICODETEXT)
        if not handle:
            return ""
        at = api.kernel32.GlobalLock(handle)
        if not at:
            return ""
        try:
            return ctypes.wstring_at(at)
        finally:
            api.kernel32.GlobalUnlock(handle)
    finally:
        api.user32.CloseClipboard()


def _clip_set(text: str):
    data = (text.replace("\r\n", "\n").replace("\n", "\r\n") + "\0").encode("utf-16-le")
    if not _open_clipboard():
        return
    try:
        api.user32.EmptyClipboard()
        handle = api.kernel32.GlobalAlloc(GMEM_MOVEABLE, len(data))
        if not handle:
            return
        at = api.kernel32.GlobalLock(handle)
        ctypes.memmove(at, data, len(data))
        api.kernel32.GlobalUnlock(handle)
        if not api.user32.SetClipboardData(CF_UNICODETEXT, handle):
            api.kernel32.GlobalFree(handle)   # the clipboard owns it only when it took it
    finally:
        api.user32.CloseClipboard()


async def clip_get(timeout: float = 5) -> str:
    # Windows ends lines with \r\n; the phone and Linux with \n
    return (await asyncio.to_thread(_clip_get)).replace("\r\n", "\n")


async def clip_set(text: str):
    await asyncio.to_thread(_clip_set, text)


_ps_quote = helpers.ps_quote


def notify(title: str, body: str = "", urgency: str = "normal", ms: int = 5000):
    """A notice from the tray. PowerShell draws it: nothing to install for it."""
    script = (
        "Add-Type -AssemblyName System.Windows.Forms; Add-Type -AssemblyName System.Drawing; "
        "$n = New-Object System.Windows.Forms.NotifyIcon; $n.Icon = [System.Drawing.SystemIcons]::Information; $n.Visible = $true; "
        f"$n.ShowBalloonTip({int(ms)}, {_ps_quote(title)}, {_ps_quote(body or ' ')}, 'Info'); Start-Sleep -Milliseconds {int(ms) + 1500}; $n.Dispose()")
    asyncio.create_task(run("powershell.exe", "-NoProfile", "-WindowStyle", "Hidden", "-Command", script, timeout=ms / 1000 + 10))


BOX_TITLE = "Weave"


def _ask(title: str, body: str, timeout: float) -> bool | None:
    # Yes/no with No as the button Enter presses, a question mark, and over every other window
    # (system modal keeps a box from a background program on top: without it the question opens
    # behind whatever is in front). It does NOT ask for the keyboard: the owner may be typing, and
    # a "y" meant for their document must not be a Yes here. The box waits to be clicked.
    flags = 0x4 | 0x100 | 0x20 | 0x1000 | 0x40000
    try:
        # the sentence goes in the box, not its title: Windows cuts a long title off
        text = f"{title}.\n\n{body}\n\nYes lets it in. No turns it away."
        answer = api.user32.MessageBoxTimeoutW(None, text, BOX_TITLE, flags, 0, int(timeout * 1000))
    except AttributeError:
        answer = api.user32.MessageBoxW(None, f"{title}.\n\n{body}", BOX_TITLE, flags)
    return True if answer == 6 else False if answer == 7 else None


def _close_box():
    """Takes this program's question off the screen: answered some other way, it is in the way."""
    found = []

    def one(hwnd, _):
        pid = wintypes.DWORD()
        api.user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        title = ctypes.create_unicode_buffer(64)
        api.user32.GetWindowTextW(hwnd, title, 64)
        kind = ctypes.create_unicode_buffer(32)
        api.user32.GetClassNameW(hwnd, kind, 32)
        if pid.value == os.getpid() and title.value == BOX_TITLE and kind.value == "#32770":
            found.append(hwnd)
        return 1

    proc = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)(one)
    api.user32.EnumWindows(proc, 0)
    for hwnd in found:
        api.user32.PostMessageW(hwnd, 0x0111, 7, 0)   # WM_COMMAND, No: the box closes as if No was clicked


_asking = asyncio.Lock()


async def ask(title: str, body: str, until: float, still=lambda: True) -> bool | None:
    """A question with Yes and No on it, up until [until] (the daemon's own clock): the answer, or
    None when nobody gave one. One box at a time, each on a thread of its own (a box waits up to
    two minutes, and the shared pool of threads is what the clipboard runs on). [still] says
    whether anyone is waiting on the answer: a question nobody waits on any more is never shown,
    and one already up is taken down."""
    async with _asking:
        left = until - time.monotonic()
        if left <= 1 or not still():
            return None
        loop = asyncio.get_running_loop()
        done = loop.create_future()

        def work():
            try:
                result = _ask(title, body, left)
            except Exception:
                result = None
            loop.call_soon_threadsafe(lambda: done.done() or done.set_result(result))

        threading.Thread(target=work, daemon=True).start()
        while not done.done():
            await asyncio.wait([done], timeout=0.3)
            if not done.done() and not still():
                await asyncio.to_thread(_close_box)
                await asyncio.wait([done], timeout=2)
                return None
        return done.result()


async def lock():
    api.user32.LockWorkStation()


async def text_focused() -> bool:
    """Whether what has the focus takes typing: Windows' accessibility layer says (focus.py);
    when it won't, a blinking caret is the next best sign."""
    from . import focus
    said = await focus.typable()
    return screen.caret_showing() if said is None else said


_wake: tuple[float, dict | None] | None = None
_wake_task: asyncio.Task | None = None


async def _read_wake() -> dict | None:
    out = await run(
        "powershell.exe", "-NoProfile", "-Command",
        "$c = Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway } | Select-Object -First 1; "
        "if ($c) { $c.NetAdapter.MacAddress; $c.IPv4Address[0].IPAddress; $c.IPv4Address[0].PrefixLength; $c.NetAdapter.PhysicalMediaType }",
        timeout=30)
    lines = [line.strip() for line in out.splitlines() if line.strip()]
    if len(lines) < 3:
        return None
    try:
        net = ipaddress.ip_network(f"{lines[1]}/{int(lines[2])}", strict=False)
    except ValueError:
        return None
    return {"mac": lines[0].replace("-", ":").lower(), "brd": str(net.broadcast_address),
            "wifi": "802.11" in (lines[3] if len(lines) > 3 else "")}


async def _refresh_wake():
    global _wake
    _wake = (time.monotonic(), await _read_wake())


async def wake_info() -> dict | None:
    """The network card the PC reaches the world through: its MAC and its network's broadcast
    address, for a magic packet that wakes it. Never waited for: asking Windows takes PowerShell
    seconds, and a phone saying hello shouldn't sit through them. What was read last is given,
    and it is read again in the background once it is ten minutes old."""
    global _wake_task
    stale = _wake is None or time.monotonic() - _wake[0] > 600
    if stale and (_wake_task is None or _wake_task.done()):
        _wake_task = asyncio.create_task(_refresh_wake())
    return _wake[1] if _wake else None


async def prewarm():
    """What takes seconds the first time, done once at the start instead of on a phone's first
    hello: the network card, which encoder works, and the sound helper's build."""
    from . import capture, focus, sound
    await wake_info()
    ffmpeg = capture.ffmpeg_path()
    if ffmpeg:
        await capture.encoder(ffmpeg)
    await sound.helper()
    await focus.describe()


def local_addresses() -> list[str] | None:
    """Windows names no easy way to tell a real card from a virtual one: pairing.py's own guess stands."""
    return None
