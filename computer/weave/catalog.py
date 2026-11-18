"""What the phone can make a shortcut of: the laptop's apps and its own key bindings.

Apps are the desktop entries the app launcher shows, each with its icon as a small PNG (the
icon theme's, rasterised once and kept in ~/.cache/weave/icons). Bindings are Hyprland's, with
the descriptions Omarchy gives them ("Switch to workspace 3"), turned into keys the injector can
press. Launching goes through uwsm, as Omarchy's own launcher does, so an app opened from the
phone lives in its own scope and outlives weave.
"""

import asyncio
import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
import time
from pathlib import Path

from . import hypr
from .inject import KEYS, key_code

ICON_PX = 96
APPS_TTL = 120          # seconds a scan of the desktop entries is good for
_apps_cache: tuple[float, list[dict]] | None = None
_apps_lock = asyncio.Lock()
_icon_index: dict[str, list[tuple[int, int, str]]] | None = None
CACHE = Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache")) / "weave" / "icons"


# ── apps ──

def _data_dirs() -> list[Path]:
    home = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local/share"))
    # a service may start without XDG_DATA_DIRS; the usual places, and Flatpak's exports, either way
    dirs = os.environ.get("XDG_DATA_DIRS", "") or "/usr/local/share:/usr/share"
    out = [home]
    for d in [*dirs.split(":"), "/usr/local/share", "/usr/share", "/var/lib/flatpak/exports/share",
              str(Path.home() / ".local/share/flatpak/exports/share")]:
        p = Path(d)
        if d and p not in out:
            out.append(p)
    return out


def _entry(path: Path) -> dict[str, str]:
    """The [Desktop Entry] group of a .desktop file, its unlocalised keys only."""
    out: dict[str, str] = {}
    group = None
    try:
        lines = path.read_text(errors="replace").splitlines()
    except OSError:
        return out
    for line in lines:
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("["):
            group = line
            continue
        if group != "[Desktop Entry]" or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        if "[" not in key and key not in out:
            out[key] = value.strip()
    return out


def _shown(e: dict[str, str]) -> bool:
    """What a launcher lists: applications that aren't hidden, meant for this desktop, and installed."""
    if e.get("Type") != "Application" or not e.get("Name") or not e.get("Exec"):
        return False
    if e.get("NoDisplay", "").lower() == "true" or e.get("Hidden", "").lower() == "true":
        return False
    desktops = {"Hyprland", *os.environ.get("XDG_CURRENT_DESKTOP", "Hyprland").split(":")}
    only = [d for d in e.get("OnlyShowIn", "").split(";") if d]
    if only and not desktops.intersection(only):
        return False
    if desktops.intersection(d for d in e.get("NotShowIn", "").split(";") if d):
        return False
    tryexec = e.get("TryExec")
    if tryexec and not (shutil.which(tryexec) or Path(tryexec).exists()):
        return False
    return True


def scan_apps() -> list[dict]:
    """Desktop entries by id, the user's own winning over the system's, as a launcher reads them."""
    found: dict[str, dict] = {}
    for base in _data_dirs():
        root = base / "applications"
        if not root.is_dir():
            continue
        for path in sorted(root.rglob("*.desktop")):
            app_id = str(path.relative_to(root))[:-len(".desktop")].replace("/", "-")
            if app_id in found:
                continue
            e = _entry(path)
            # seen once, shown or not: a hidden user entry hides the system's
            found[app_id] = {"id": app_id, "name": e.get("Name", ""), "icon_name": e.get("Icon", ""),
                             "shown": _shown(e)}
    apps = [a for a in found.values() if a["shown"]]
    apps.sort(key=lambda a: a["name"].casefold())
    return [{"id": a["id"], "name": a["name"], "icon_name": a["icon_name"]} for a in apps]


# ── icons ──

def _theme_chain() -> list[str]:
    theme = ""
    try:
        theme = subprocess.run(["gsettings", "get", "org.gnome.desktop.interface", "icon-theme"],
                               capture_output=True, text=True, timeout=2).stdout.strip().strip("'")
    except (OSError, subprocess.SubprocessError):
        pass
    chain, todo = [], [theme] if theme else []
    while todo:
        name = todo.pop(0)
        if not name or name in chain:
            continue
        chain.append(name)
        for base in _icon_bases():
            index = base / name / "index.theme"
            if index.is_file():
                todo += [t.strip() for t in _ini_value(index, "Inherits").split(",")]
                break
    for fallback in ("hicolor", "Adwaita"):
        if fallback not in chain:
            chain.append(fallback)
    return chain


def _ini_value(path: Path, key: str) -> str:
    try:
        for line in path.read_text(errors="replace").splitlines():
            if line.startswith(key + "="):
                return line.split("=", 1)[1]
    except OSError:
        pass
    return ""


def _icon_bases() -> list[Path]:
    return [Path.home() / ".local/share/icons", Path.home() / ".icons", *(d / "icons" for d in _data_dirs()[1:])]


def _size_of(rel: str) -> int:
    """How big the icon in a theme folder is: `scalable` beats any bitmap."""
    if "scalable" in rel or "symbolic" in rel:
        return 0 if "symbolic" in rel else 1000
    m = re.search(r"(\d+)x\d+(@(\d)x)?", rel)
    if not m:
        return 48
    return int(m.group(1)) * int(m.group(3) or 1)


def _build_index() -> dict[str, list[tuple[int, int, str]]]:
    """Every icon the themes hold, by name: (theme rank, size, path)."""
    index: dict[str, list[tuple[int, int, str]]] = {}
    for rank, theme in enumerate(_theme_chain()):
        for base in _icon_bases():
            root = base / theme
            if not root.is_dir():
                continue
            for dirpath, _, files in os.walk(root):
                rel = dirpath[len(str(root)):]
                size = _size_of(rel)
                if size == 0:
                    continue
                for f in files:
                    stem, ext = os.path.splitext(f)
                    if ext in (".png", ".svg"):
                        index.setdefault(stem, []).append((rank, size, os.path.join(dirpath, f)))
    for base in _data_dirs():
        pixmaps = base / "pixmaps"
        if pixmaps.is_dir():
            for f in pixmaps.iterdir():
                if f.suffix in (".png", ".svg", ".xpm"):
                    index.setdefault(f.stem, []).append((99, 64, str(f)))
    return index


def _ensure_index():
    global _icon_index
    if _icon_index is None:
        _icon_index = _build_index()


def icon_file(name: str) -> str | None:
    """The best file for an icon name: the first theme that has it, then the size nearest a sharp 96 px."""
    if not name:
        return None
    if name.startswith("/"):
        return name if Path(name).is_file() else None
    _ensure_index()
    found = _icon_index.get(name)
    if not found:
        return None
    rank = min(r for r, _, _ in found)

    def score(item):
        _, size, path = item
        # a vector, or the smallest bitmap at least as big as asked, else the biggest there is
        return (0, 0) if size >= 1000 else ((1, size) if size >= ICON_PX else (2, -size))
    return min((i for i in found if i[0] == rank), key=score)[2]


async def icon_png(name: str) -> bytes | None:
    """An app's icon as a PNG of about [ICON_PX], from the cache when it was made before."""
    path = await asyncio.to_thread(icon_file, name)
    if not path:
        return None
    try:
        stat = os.stat(path)
    except OSError:
        return None
    key = hashlib.sha1(f"{path}:{stat.st_mtime_ns}:{ICON_PX}".encode()).hexdigest()
    cached = CACHE / f"{key}.png"
    if cached.is_file():
        try:
            return cached.read_bytes()
        except OSError:
            pass
    if path.endswith(".svg"):
        args = ["rsvg-convert", "-w", str(ICON_PX), "-h", str(ICON_PX), "-a", path]
    elif path.endswith(".png") and stat.st_size < 24_000:
        args = None   # small enough as it is
    else:
        args = ["magick", path, "-resize", f"{ICON_PX}x{ICON_PX}", "png:-"]
    if args is None:
        try:
            data = Path(path).read_bytes()
        except OSError:
            return None
    else:
        proc = None
        try:
            proc = await asyncio.create_subprocess_exec(*args, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
            data, _ = await asyncio.wait_for(proc.communicate(), 5)
        except (OSError, asyncio.TimeoutError):
            if proc and proc.returncode is None:
                proc.kill()   # a converter stuck on one icon isn't left behind
            return None
        if not data.startswith(b"\x89PNG"):
            return None
    try:
        CACHE.mkdir(parents=True, exist_ok=True)
        cached.write_bytes(data)
    except OSError:
        pass
    return data


async def apps(with_icons: bool = True) -> list[dict]:
    """The apps a launcher shows, each with its icon as base64 PNG (or none)."""
    global _apps_cache
    async with _apps_lock:
        if _apps_cache and time.monotonic() - _apps_cache[0] < APPS_TTL:
            found = _apps_cache[1]
        else:
            found = await asyncio.to_thread(scan_apps)
            _apps_cache = (time.monotonic(), found)
    out = [{"id": a["id"], "name": a["name"]} for a in found]
    if not with_icons:
        # whether each has an icon file at all: a quick look in the index, nothing drawn
        await asyncio.to_thread(_ensure_index)
        for a, o in zip(found, out):
            o["has_icon"] = icon_file(a["icon_name"]) is not None
    if with_icons:
        await asyncio.to_thread(_ensure_index)   # once, before the icons are looked up side by side
        limit = asyncio.Semaphore(6)

        async def one(app, item):
            async with limit:
                png = await icon_png(app["icon_name"])
            if png:
                item["icon"] = base64.b64encode(png).decode()
        await asyncio.gather(*(one(a, o) for a, o in zip(found, out)))
    return out


def app_name(app_id: str) -> str | None:
    if _apps_cache:
        return next((a["name"] for a in _apps_cache[1] if a["id"] == app_id), None)
    return None


async def launch(app_id: str) -> str:
    """Opens an app by its desktop id, through uwsm as Omarchy's launcher does: "ok", "gone"
    (no longer installed) or "failed"."""
    known = await apps(with_icons=False)
    if not any(a["id"] == app_id for a in known):
        return "gone"
    if shutil.which("uwsm-app"):
        args = ["uwsm-app", "--", f"{app_id}.desktop"]
    elif shutil.which("gtk-launch"):
        args = ["gtk-launch", app_id]
    else:
        return "failed"
    try:
        proc = await asyncio.create_subprocess_exec(*args, stdin=asyncio.subprocess.DEVNULL,
                                                    stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL)
        # uwsm hands the app on and returns at once; never wait on the app itself
        await asyncio.wait_for(proc.wait(), 5)
        return "ok" if proc.returncode == 0 else "failed"
    except OSError:
        return "failed"
    except asyncio.TimeoutError:
        return "ok"


async def open_url(url: str):
    """A web page in the laptop's browser, in its own scope when uwsm is there."""
    args = ["uwsm-app", "--", "xdg-open", url] if shutil.which("uwsm-app") else ["xdg-open", url]
    try:
        proc = await asyncio.create_subprocess_exec(*args, stdin=asyncio.subprocess.DEVNULL,
                                                    stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL)
        await asyncio.wait_for(proc.wait(), 5)
    except (OSError, asyncio.TimeoutError):
        pass


# ── key bindings ──

MODMASK = {1: "shift", 4: "ctrl", 8: "alt", 64: "super"}
# keysyms whose key sits in the same place on every layout this laptop is likely to have
_KEYSYMS = {
    "return": "enter", "space": "space", "tab": "tab", "escape": "esc", "backspace": "backspace",
    "delete": "delete", "insert": "insert", "home": "home", "end": "end", "prior": "pageup", "page_up": "pageup",
    "next": "pagedown", "page_down": "pagedown", "left": "left", "right": "right", "up": "up", "down": "down",
    "print": "print", "comma": "comma", "period": "dot", "aring": "leftbrace", "oslash": "semicolon", "ae": "apostrophe",
    "xf86audioraisevolume": "volup", "xf86audiolowervolume": "voldown", "xf86audiomute": "mute",
    "xf86audiomicmute": "micmute", "xf86audioplay": "play", "xf86audiopause": "pause", "xf86audionext": "next",
    "xf86audioprev": "prev", "xf86monbrightnessup": "brightup", "xf86monbrightnessdown": "brightdown",
    "xf86kbdbrightnessup": "kbdup", "xf86kbdbrightnessdown": "kbddown", "xf86kbdlightonoff": "kbdlight",
    "xf86calculator": "calc",
}
# what a key reads as on the keycap, for the phone
_CAPS = {
    "enter": "Enter", "space": "Space", "tab": "Tab", "esc": "Esc", "backspace": "Backspace", "delete": "Del",
    "insert": "Ins", "home": "Home", "end": "End", "pageup": "PgUp", "pagedown": "PgDn", "left": "←", "right": "→",
    "up": "↑", "down": "↓", "print": "Print", "comma": ",", "dot": ".", "leftbrace": "Å", "semicolon": "Ø",
    "apostrophe": "Æ", "minus": "−", "equal": "=", "volup": "Vol +", "voldown": "Vol −", "mute": "Mute",
    "micmute": "Mic", "play": "Play", "pause": "Pause", "next": "Next", "prev": "Prev", "brightup": "Bright +",
    "brightdown": "Bright −", "kbdup": "Kbd +", "kbddown": "Kbd −", "kbdlight": "Kbd", "calc": "Calc",
    "rightbrace": "¨", "grave": "|", "backslash": "'",
}
_BY_CODE = {code: name for name, code in KEYS.items()}
# `"SUPER + SHIFT + code:20", "Shrink window up"` in a Lua config, and the numbered ones made in a loop
_LUA_CODE = re.compile(r'"([A-Za-z_ +]*?)\+\s*code:(\d+)"\s*,\s*"([^"]+)"')
_LUA_LOOP = re.compile(r'code:"\s*\.\.\s*tostring\(\s*(\w+)\s*\+\s*(\d+)\s*\)')


def _code_keys() -> tuple[dict[str, int], int | None]:
    """Hyprland 0.56 reports a binding on `code:NN` with no key at all. The Lua configs say which
    code each description is on; numbered ones ("workspace 3") come from a loop over the number row."""
    by_desc: dict[str, int] = {}
    loop_offset = None
    roots = [Path.home() / ".config/hypr", Path("/usr/share/omarchy/default/hypr")]
    for root in roots:
        if not root.is_dir():
            continue
        for f in root.rglob("*.lua"):
            try:
                text = f.read_text(errors="replace")
            except OSError:
                continue
            for _, code, desc in _LUA_CODE.findall(text):
                by_desc.setdefault(desc, int(code))
            m = _LUA_LOOP.search(text)
            if m and loop_offset is None:
                loop_offset = int(m.group(2))
    return by_desc, loop_offset


# keys named by where they sit; what they print depends on the layout (on a Norwegian one, the
# key after 0 is +), so their caps are read from the laptop's own keymap
_POSITIONAL = {
    "minus": "AE11", "equal": "AE12", "leftbrace": "AD11", "rightbrace": "AD12", "semicolon": "AC10",
    "apostrophe": "AC11", "grave": "TLDE", "backslash": "BKSL", "comma": "AB08", "dot": "AB09", "slash": "AB10",
}
_SYM_CHAR = {
    "plus": "+", "minus": "-", "equal": "=", "backslash": "\\", "slash": "/", "bar": "|", "apostrophe": "'",
    "comma": ",", "period": ".", "semicolon": ";", "colon": ":", "grave": "`", "bracketleft": "[",
    "bracketright": "]", "numbersign": "#", "less": "<", "greater": ">", "underscore": "_", "asterisk": "*",
    "dead_diaeresis": "¨", "dead_acute": "´", "dead_grave": "`", "dead_circumflex": "^", "dead_tilde": "~",
    "aring": "Å", "oslash": "Ø", "ae": "Æ", "adiaeresis": "Ä", "odiaeresis": "Ö", "udiaeresis": "Ü", "ssharp": "ß",
}
_layout_caps: dict[tuple[str, str], dict[str, str]] = {}


def _read_layout_caps(layout: str, variant: str) -> dict[str, str]:
    """What the positional keys print on [layout], from xkbcli's keymap; empty when it can't say."""
    args = ["xkbcli", "compile-keymap", "--layout", layout] + (["--variant", variant] if variant else [])
    try:
        text = subprocess.run(args, capture_output=True, text=True, timeout=5).stdout
    except (OSError, subprocess.SubprocessError):
        return {}
    out = {}
    for name, pos in _POSITIONAL.items():
        m = re.search(rf"key <{pos}>\s*{{\s*\[\s*([A-Za-z_0-9]+)", text)
        if m:
            sym = m.group(1)
            char = _SYM_CHAR.get(sym) or (sym.upper() if len(sym) == 1 else None)
            if char:
                out[name] = char
    return out


async def layout_caps() -> dict[str, str]:
    """The positional keys' caps for the layout Hyprland uses now, read once per layout."""
    layout = variant = ""
    try:
        layout = str(json.loads(await hypr.request("j/getoption input:kb_layout")).get("str", "")).split(",")[0].strip()
        variant = str(json.loads(await hypr.request("j/getoption input:kb_variant")).get("str", "")).split(",")[0].strip()
    except (ValueError, AttributeError):
        pass
    key = (layout or "us", variant)
    if key not in _layout_caps:
        if not shutil.which("xkbcli"):
            return {}
        caps = await asyncio.to_thread(_read_layout_caps, *key)
        if not caps:
            return {}   # not kept: the next look tries again
        _layout_caps[key] = caps
    return _layout_caps[key]


def _cap(name: str, layout: dict[str, str] | None = None) -> str:
    if name.startswith("#"):
        return name
    if layout and name in layout:
        return layout[name]
    return _CAPS.get(name, name.upper() if len(name) == 1 else name.title())


def bind_keys(raw: list[dict], by_desc: dict[str, int], loop_offset: int | None, layout: dict[str, str] | None = None) -> list[dict]:
    """Hyprland's bindings as the phone can press them: a description, the key, its modifiers and
    what the keycap reads, in Hyprland's order. Mouse, switch and submap bindings, and keys that
    can't be pressed reliably from here (a `/` that moves with the layout, power), are left out.
    A description bound twice keeps its binding on a named key."""
    best: dict[str, dict] = {}
    for b in raw:
        desc = (b.get("description") or "").strip()
        # a binding on release (push to talk) would start and stop at once from one press
        if not desc or b.get("mouse") or b.get("submap") or b.get("catch_all") or b.get("longPress") or b.get("release"):
            continue
        mask = int(b.get("modmask") or 0)
        if mask & ~sum(MODMASK):
            continue   # caps lock, num lock and the like
        mods = [name for bit, name in MODMASK.items() if mask & bit]
        sym = (b.get("key") or "").strip()
        key = None
        if sym:
            low = sym.lower()
            if len(low) == 1 and low.isascii() and low.isalnum():
                key = low
            elif re.fullmatch(r"f([1-9]|1[0-2])", low):
                key = low
            else:
                key = _KEYSYMS.get(low)
        else:
            code = by_desc.get(desc) or int(b.get("keycode") or 0)
            if not code and loop_offset is not None:
                # "Switch to workspace 3": made in a loop over the number row, code = n + offset
                m = re.search(r"\b(10|[1-9])$", desc)
                if m:
                    code = int(m.group(1)) + loop_offset
            if code:
                evdev = code - 8   # an XKB keycode is the Linux one plus 8
                key = _BY_CODE.get(evdev) or (f"#{evdev}" if 0 < evdev < 249 else None)
        if not key or key_code(key) is None:
            continue
        item = {"label": desc, "key": key, "mods": mods, "cap": _cap(key, layout) if not key.startswith("#") else (sym or "Key")}
        held = best.get(desc)
        if held is None or (held["key"].startswith("#") and not key.startswith("#")):
            best[desc] = item
    return list(best.values())


_binds_cache: tuple[float, list[dict]] | None = None


async def binds() -> list[dict]:
    global _binds_cache
    try:
        raw = json.loads(await hypr.request("j/binds", timeout=2))
    except ValueError:
        # Hyprland not answering (restarting): kept, so a press doesn't ask again straight away
        _binds_cache = (time.monotonic(), _binds_cache[1] if _binds_cache else [])
        return _binds_cache[1]
    by_desc, offset = await asyncio.to_thread(_code_keys)
    out = bind_keys(raw if isinstance(raw, list) else [], by_desc, offset, await layout_caps())
    _binds_cache = (time.monotonic(), out)
    return out


async def bound_code(name: str) -> bool:
    """Whether a raw code (`#193`) is one a binding of this laptop uses: the phone presses no
    other, so a stray or made-up code never reaches the keyboard."""
    if not name.startswith("#"):
        return True
    age = time.monotonic() - _binds_cache[0] if _binds_cache else None
    # a good list for two minutes; an empty one (Hyprland was away) for a few seconds
    if age is None or age > APPS_TTL or (not _binds_cache[1] and age > 5):
        await binds()
    return any(b["key"] == name for b in (_binds_cache[1] if _binds_cache else []))
