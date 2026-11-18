"""Hyprland over its request socket: monitors, the cursor, the focused window."""

import asyncio
import json
import os
from pathlib import Path


def _socket_path():
    sig = os.environ.get("HYPRLAND_INSTANCE_SIGNATURE")
    runtime = os.environ.get("XDG_RUNTIME_DIR", "/tmp")
    if not sig:
        # started before the session exported it: take the newest instance
        found = sorted(Path(runtime, "hypr").glob("*/.socket.sock"), key=lambda p: p.stat().st_mtime)
        return str(found[-1]) if found else None
    return str(Path(runtime, "hypr", sig, ".socket.sock"))


async def request(command: str, timeout: float = 1.0) -> str:
    path = _socket_path()
    if not path:
        return ""
    try:
        reader, writer = await asyncio.wait_for(asyncio.open_unix_connection(path), timeout)
        writer.write(command.encode())
        await writer.drain()
        data = await asyncio.wait_for(reader.read(-1), timeout)
        writer.close()
        return data.decode(errors="replace")
    except (OSError, asyncio.TimeoutError):
        return ""


async def monitors() -> list[dict]:
    """Enabled monitors with their place in the layout, in logical pixels."""
    try:
        raw = json.loads(await request("j/monitors"))
    except ValueError:
        return []
    out = []
    for m in raw:
        if m.get("disabled"):
            continue
        scale = m.get("scale") or 1
        w, h = m["width"], m["height"]
        if m.get("transform", 0) % 2:
            w, h = h, w
        # what a person calls it: the laptop's own panel, or the monitor's model and how it is plugged in
        name = m["name"]
        if name.startswith(("eDP", "LVDS", "DSI")):
            label = "Laptop screen"
        else:
            plug = "HDMI" if name.startswith("HDMI") else "DisplayPort" if name.startswith("DP") else name
            label = f"{(m.get('model') or '').strip() or name} · {plug}"
        out.append({
            "name": name,
            "label": label,
            "x": m["x"], "y": m["y"],
            "w": round(w / scale), "h": round(h / scale),
            "pw": w, "ph": h,
            "focused": bool(m.get("focused")),
        })
    return out


async def cursor() -> tuple[int, int] | None:
    reply = await request("cursorpos")
    try:
        x, y = reply.split(",")
        return int(x), int(y)
    except ValueError:
        return None


# Hyprland 0.56 reads `dispatch` as Lua (`hl.dsp.cursor.move({ x = 1, y = 2 })`); before it,
# a word and its arguments (`movecursor 1 2`). Which one this Hyprland takes is learnt once.
_lua: bool | None = None


async def dispatch(lua: str, legacy: str) -> str:
    """Runs a dispatcher in whichever syntax this Hyprland speaks; returns its answer."""
    global _lua
    if _lua is not False:
        reply = (await request(f"dispatch {lua}")).strip()
        if not reply:
            return reply   # no answer (a slow or missing socket): learn nothing from it
        if not reply.lower().startswith("error") and "invalid dispatcher" not in reply.lower():
            _lua = True
            return reply
        if _lua:
            return reply
    reply = (await request(f"dispatch {legacy}")).strip()
    if _lua is None and reply == "ok":
        _lua = False
    return reply


async def move_cursor(x: int, y: int):
    await dispatch(f"hl.dsp.cursor.move({{ x = {x}, y = {y} }})", f"movecursor {x} {y}")


async def focus_window(selector: str):
    """Focuses the first window matching a selector such as `class:^(scrcpy)$`."""
    quoted = selector.replace("\\", "\\\\").replace('"', '\\"')
    await dispatch(f'hl.dsp.focus({{ window = "{quoted}" }})', f"focuswindow {selector}")


async def active_window() -> dict:
    try:
        return json.loads(await request("j/activewindow"))
    except ValueError:
        return {}


async def evaluate(lua: str) -> str:
    return await request(f"eval {lua}")


def monitor_at(mons: list[dict], x: int, y: int) -> dict | None:
    for m in mons:
        if m["x"] <= x < m["x"] + m["w"] and m["y"] <= y < m["y"] + m["h"]:
            return m
    return None


async def set_hardware_cursors(mode: int) -> bool:
    """Sets cursor:no_hardware_cursors: 0 draws the pointer on its own plane, over the screen image
    rather than into it, so the recorder's -cursor no really leaves it out of the picture while
    it stays on the laptop's screens. (Hyprland's default, 2, paints it into the image when there
    is an NVIDIA card.) Returns whether Hyprland took it.
    """
    # a Lua config (Hyprland 0.56) refuses `keyword` and takes the option through eval
    reply = (await evaluate(f"hl.config({{ cursor = {{ no_hardware_cursors = {int(mode)} }} }})")).strip()
    if reply != "ok":
        reply = (await request(f"keyword cursor:no_hardware_cursors {int(mode)}")).strip()
    return reply == "ok"


async def hardware_cursors() -> int | None:
    """What cursor:no_hardware_cursors is now; None when Hyprland won't say."""
    try:
        return int(json.loads(await request("j/getoption cursor:no_hardware_cursors"))["int"])
    except (ValueError, KeyError, TypeError):
        return None


async def pointer_gain() -> float | None:
    """Laptop pixels per unit of weave's mouse: 1 + sensitivity on the flat profile, which adds no
    acceleration. None on another profile, where it depends on speed and the phone has to learn it."""
    try:
        profile = json.loads(await request("j/getoption input:accel_profile")).get("str", "")
        sens = float(json.loads(await request("j/getoption input:sensitivity")).get("float", 0.0))
    except (ValueError, TypeError):
        return None
    return round(1.0 + sens, 4) if profile == "flat" else None
