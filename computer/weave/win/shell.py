"""What the daemon asks of the desktop around it, on Windows: the clipboard, notices, the
pairing question, the lock, whether a text field has the focus, and the network card to wake."""

import asyncio
import ctypes
import ipaddress
import time

from ..proc import run
from . import api, screen

PLATFORM = "windows"
# what this computer can do beyond the first version, for the phone to offer; "sound" is missing:
# the computer's own sound isn't sent from Windows yet
CAPS = ["catalog", "launch", "pointer", "warp", "localcursor", "clipsync", "moveseq"]
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
    flags = 0x4 | 0x20 | 0x10000 | 0x40000   # yes/no, a question mark, in front, on top
    try:
        answer = api.user32.MessageBoxTimeoutW(None, body + "\n\nYes lets it in. No turns it away.", "Weave: " + title, flags, 0, int(timeout * 1000))
    except AttributeError:
        answer = api.user32.MessageBoxW(None, body, "Weave: " + title, flags)
    return True if answer == 6 else False if answer == 7 else None


async def ask(title: str, body: str, timeout: float) -> bool | None:
    """A question with Yes and No on it: the answer, or None when nobody gave one."""
    return await asyncio.to_thread(_ask, title, body, timeout)


async def lock():
    api.user32.LockWorkStation()


async def text_focused() -> bool:
    return screen.caret_showing()


_wake: tuple[float, dict | None] | None = None


async def wake_info() -> dict | None:
    """The network card the PC reaches the world through: its MAC and its network's broadcast
    address, for a magic packet that wakes it. Asked of Windows once in ten minutes."""
    global _wake
    if _wake and time.monotonic() - _wake[0] < 600:
        return _wake[1]
    out = await run(
        "powershell.exe", "-NoProfile", "-Command",
        "$c = Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway } | Select-Object -First 1; "
        "if ($c) { $c.NetAdapter.MacAddress; $c.IPv4Address[0].IPAddress; $c.IPv4Address[0].PrefixLength; $c.NetAdapter.PhysicalMediaType }",
        timeout=12)
    info = None
    lines = [line.strip() for line in out.splitlines() if line.strip()]
    if len(lines) >= 3:
        try:
            net = ipaddress.ip_network(f"{lines[1]}/{int(lines[2])}", strict=False)
            info = {"mac": lines[0].replace("-", ":").lower(), "brd": str(net.broadcast_address),
                    "wifi": "802.11" in (lines[3] if len(lines) > 3 else "")}
        except ValueError:
            info = None
    _wake = (time.monotonic(), info)
    return info
