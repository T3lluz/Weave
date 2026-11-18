"""The screens and the pointer on Windows. It answers what hypr.py answers on Linux, in real
pixels: on Windows the layout the pointer moves in and the pixels on the glass are the same."""

import asyncio
import ctypes
import os
from ctypes import wintypes

from . import api


def monitor_at(mons: list[dict], x: int, y: int) -> dict | None:
    for m in mons:
        if m["x"] <= x < m["x"] + m["w"] and m["y"] <= y < m["y"] + m["h"]:
            return m
    return None


def _monitors() -> list[dict]:
    api.dpi_aware()
    found = []

    def one(handle, _dc, _rect, _data):
        info = api.MONITORINFOEXW()
        info.cbSize = ctypes.sizeof(info)
        if api.user32.GetMonitorInfoW(handle, ctypes.byref(info)):
            r = info.rcMonitor
            found.append({"device": info.szDevice, "primary": bool(info.dwFlags & 1),
                          "x": r.left, "y": r.top, "w": r.right - r.left, "h": r.bottom - r.top})
        return 1

    proc = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HMONITOR, wintypes.HDC, ctypes.POINTER(wintypes.RECT), wintypes.LPARAM)(one)
    api.user32.EnumDisplayMonitors(None, None, proc, 0)
    return describe(found, cursor_now())


def describe(found: list[dict], pointer: tuple[int, int] | None) -> list[dict]:
    """The monitors as the rest of the daemon wants them, left to right: named as Windows names
    them (DISPLAY1), labelled as a person would, the one under the pointer marked."""
    found = sorted(found, key=lambda m: (m["x"], m["y"]))
    out = []
    for i, m in enumerate(found):
        name = m["device"].replace("\\\\.\\", "")
        label = "Main screen" if m["primary"] or len(found) == 1 else f"Screen {i + 1}"
        inside = pointer is not None and m["x"] <= pointer[0] < m["x"] + m["w"] and m["y"] <= pointer[1] < m["y"] + m["h"]
        out.append({"name": name, "label": label, "x": m["x"], "y": m["y"], "w": m["w"], "h": m["h"],
                    "pw": m["w"], "ph": m["h"], "focused": inside})
    return out


async def monitors() -> list[dict]:
    return await asyncio.to_thread(_monitors)


def cursor_now() -> tuple[int, int] | None:
    api.dpi_aware()
    p = api.POINT()
    return (p.x, p.y) if api.user32.GetCursorPos(ctypes.byref(p)) else None


async def cursor() -> tuple[int, int] | None:
    return cursor_now()


async def move_cursor(x: int, y: int):
    api.dpi_aware()
    api.user32.SetCursorPos(int(x), int(y))


async def pointer_gain() -> float | None:
    """The pointer goes exactly as far as it is told (inject.py places it, it doesn't nudge it)."""
    return 1.0


async def hardware_cursors() -> int | None:
    """Windows keeps the pointer out of a recording by itself (ffmpeg's draw_mouse): nothing to set."""
    return 0


async def set_hardware_cursors(mode: int) -> bool:
    return True


def _foreground() -> tuple[int, int]:
    """The window in front: its handle's thread and its process."""
    hwnd = api.user32.GetForegroundWindow()
    pid = wintypes.DWORD()
    thread = api.user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid)) if hwnd else 0
    return thread, pid.value


async def active_window() -> dict:
    """The program in front, by its file name (WindowsTerminal.exe): what "class" is on Linux."""
    _, pid = _foreground()
    if not pid:
        return {}
    handle = api.kernel32.OpenProcess(0x1000, False, pid)   # PROCESS_QUERY_LIMITED_INFORMATION
    if not handle:
        return {}
    try:
        size = wintypes.DWORD(1024)
        path = ctypes.create_unicode_buffer(size.value)
        if not api.kernel32.QueryFullProcessImageNameW(handle, 0, path, ctypes.byref(size)):
            return {}
        return {"class": os.path.basename(path.value)}
    finally:
        api.kernel32.CloseHandle(handle)


def caret_showing() -> bool:
    """Whether the window in front has a text caret up: true in classic text fields (Notepad,
    dialogs, Explorer's address bar). Browsers and other apps that draw their own never say."""
    thread, _ = _foreground()
    if not thread:
        return False
    info = api.GUITHREADINFO()
    info.cbSize = ctypes.sizeof(info)
    if not api.user32.GetGUIThreadInfo(thread, ctypes.byref(info)):
        return False
    return bool(info.hwndCaret) or bool(info.flags & 0x1)   # GUI_CARETBLINKING
