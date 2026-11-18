"""What the daemon asks of the desktop around it, on Windows: the clipboard, notices, the
pairing question, the lock, whether a text field has the focus, and the network card to wake."""

import asyncio
import ctypes
import ipaddress
import time

from ..proc import run
from . import api, screen

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


def _ps_quote(text: str) -> str:
    return "'" + text.replace("'", "''") + "'"


def notify(title: str, body: str = "", urgency: str = "normal", ms: int = 5000):
    """A notice from the tray. PowerShell draws it: nothing to install for it."""
    script = (
        "Add-Type -AssemblyName System.Windows.Forms; Add-Type -AssemblyName System.Drawing; "
        "$n = New-Object System.Windows.Forms.NotifyIcon; $n.Icon = [System.Drawing.SystemIcons]::Information; $n.Visible = $true; "
        f"$n.ShowBalloonTip({int(ms)}, {_ps_quote(title)}, {_ps_quote(body or ' ')}, 'Info'); Start-Sleep -Milliseconds {int(ms) + 1500}; $n.Dispose()")
    asyncio.create_task(run("powershell.exe", "-NoProfile", "-WindowStyle", "Hidden", "-Command", script, timeout=ms / 1000 + 10))


def _ask(title: str, body: str, timeout: float) -> bool | None:
    # yes/no, a question mark, and over every other window (system modal is what keeps a box from
    # a background program on top: without it the question opens behind whatever is in front)
    flags = 0x4 | 0x20 | 0x1000 | 0x10000 | 0x40000
    try:
        # the sentence goes in the box, not its title: Windows cuts a long title off
        text = f"{title}.\n\n{body}\n\nYes lets it in. No turns it away."
        answer = api.user32.MessageBoxTimeoutW(None, text, "Weave", flags, 0, int(timeout * 1000))
    except AttributeError:
        answer = api.user32.MessageBoxW(None, f"{title}.\n\n{body}", "Weave", flags)
    return True if answer == 6 else False if answer == 7 else None


async def ask(title: str, body: str, timeout: float) -> bool | None:
    """A question with Yes and No on it: the answer, or None when nobody gave one."""
    return await asyncio.to_thread(_ask, title, body, timeout)


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
