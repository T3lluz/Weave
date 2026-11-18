"""Which real key types each character on this laptop's keyboard layout.

wtype types a character by lending it a key of its own keyboard, and the first one it lends is
the key where Escape sits. A browser (and every Electron app) reads that: the space bar and every
symbol arrive with Escape's key code, and an editor that sees keyCode 27 takes it for Escape and
types nothing. Letters got through only because Chromium names them by the letter.

So text goes through weave's own keyboard where the layout has the character: the space bar
for a space, Shift + 1 for "!", AltGr + 2 for "@" on a Norwegian keyboard. The layout is the one
Hyprland gives that keyboard, read with libxkbcommon. A character the layout can't type is left
to wtype.
"""

import ctypes
import ctypes.util
from dataclasses import dataclass

# keysyms
_LEVEL3 = 0xFE03                     # ISO_Level3_Shift, AltGr on most layouts
_KEYPAD = range(0xFF80, 0xFFBE)      # KP_*: a number pad types the same through Num Lock, or not
# dead keys that make their own character when a space follows them (the usual Compose table)
_DEAD_ALONE = {0xFE50: "`", 0xFE52: "^", 0xFE53: "~"}
_SPACE = 57


@dataclass(frozen=True)
class Stroke:
    """One press of a key (its Linux code), with Shift and AltGr held or not."""
    code: int
    shift: bool = False
    altgr: bool = False


class _Names(ctypes.Structure):
    _fields_ = [(f, ctypes.c_char_p) for f in ("rules", "model", "layout", "variant", "options")]


_lib = None


def _xkb():
    global _lib
    if _lib is None:
        path = ctypes.util.find_library("xkbcommon") or "libxkbcommon.so.0"
        lib = ctypes.CDLL(path)
        p, u32 = ctypes.c_void_p, ctypes.c_uint32
        lib.xkb_context_new.restype = p
        lib.xkb_context_new.argtypes = [ctypes.c_int]
        lib.xkb_context_unref.argtypes = [p]
        lib.xkb_keymap_new_from_names.restype = p
        lib.xkb_keymap_new_from_names.argtypes = [p, ctypes.POINTER(_Names), ctypes.c_int]
        lib.xkb_keymap_unref.argtypes = [p]
        lib.xkb_keymap_num_layouts_for_key.restype = u32
        lib.xkb_keymap_num_layouts_for_key.argtypes = [p, u32]
        lib.xkb_keymap_num_levels_for_key.restype = u32
        lib.xkb_keymap_num_levels_for_key.argtypes = [p, u32, u32]
        lib.xkb_keymap_key_get_syms_by_level.restype = ctypes.c_int
        lib.xkb_keymap_key_get_syms_by_level.argtypes = [p, u32, u32, u32, ctypes.POINTER(ctypes.POINTER(u32))]
        lib.xkb_keymap_key_get_mods_for_level.restype = ctypes.c_size_t
        lib.xkb_keymap_key_get_mods_for_level.argtypes = [p, u32, u32, u32, ctypes.POINTER(u32), ctypes.c_size_t]
        lib.xkb_keymap_mod_get_index.restype = u32
        lib.xkb_keymap_mod_get_index.argtypes = [p, ctypes.c_char_p]
        lib.xkb_keysym_to_utf32.restype = u32
        lib.xkb_keysym_to_utf32.argtypes = [u32]
        _lib = lib
    return _lib


def strokes_for(layout: str, variant: str = "", options: str = "", model: str = "", rules: str = "", group: int = 0) -> dict[str, list[Stroke]]:
    """Every character the layout types, with the key presses that type it (one, or a dead key
    and the space bar). Empty when libxkbcommon is missing or the layout doesn't compile."""
    try:
        lib = _xkb()
    except OSError:
        return {}
    ctx = lib.xkb_context_new(0)
    if not ctx:
        return {}
    names = _Names(*((v or "").encode() or None for v in (rules, model, layout, variant, options)))
    keymap = lib.xkb_keymap_new_from_names(ctx, ctypes.byref(names), 0)
    if not keymap:
        lib.xkb_context_unref(ctx)
        return {}
    try:
        return _read(lib, keymap, group)
    finally:
        lib.xkb_keymap_unref(keymap)
        lib.xkb_context_unref(ctx)


def _read(lib, keymap, group: int) -> dict[str, list[Stroke]]:
    shift = 1 << lib.xkb_keymap_mod_get_index(keymap, b"Shift")
    level3 = 1 << lib.xkb_keymap_mod_get_index(keymap, b"Mod5")
    syms = ctypes.POINTER(ctypes.c_uint32)()
    masks = (ctypes.c_uint32 * 8)()
    found: dict[str, tuple[int, Stroke]] = {}   # the character, by how many modifiers it takes
    dead: dict[str, Stroke] = {}
    has_altgr = False
    # Linux codes 1 to 248 (the keyboard weave made has those); xkb numbers them 8 higher
    for code in range(1, 249):
        kc = code + 8
        layouts = lib.xkb_keymap_num_layouts_for_key(keymap, kc)
        if not layouts:
            continue
        g = group if group < layouts else 0
        for level in range(lib.xkb_keymap_num_levels_for_key(keymap, kc, g)):
            if lib.xkb_keymap_key_get_syms_by_level(keymap, kc, g, level, ctypes.byref(syms)) != 1:
                continue
            sym = syms[0]
            if sym == _LEVEL3 and level == 0:
                has_altgr = has_altgr or code == 100
                continue
            if sym in _KEYPAD:
                continue
            # the fewest modifiers that reach this level, among the ones weave can hold
            best = None
            for i in range(lib.xkb_keymap_key_get_mods_for_level(keymap, kc, g, level, masks, len(masks))):
                m = masks[i]
                if m & ~(shift | level3):
                    continue
                if best is None or bin(m).count("1") < bin(best).count("1"):
                    best = m
            if best is None:
                continue
            stroke = Stroke(code, bool(best & shift), bool(best & level3))
            if sym in _DEAD_ALONE:
                dead.setdefault(_DEAD_ALONE[sym], stroke)
                continue
            cp = lib.xkb_keysym_to_utf32(sym)
            if cp < 0x20 or cp == 0x7F:
                continue
            ch = chr(cp)
            weight = stroke.shift + stroke.altgr
            if ch not in found or weight < found[ch][0]:
                found[ch] = (weight, stroke)
    out = {ch: [s] for ch, (_, s) in found.items() if has_altgr or not s.altgr}
    for ch, s in dead.items():
        if ch not in out and (has_altgr or not s.altgr):
            out[ch] = [s, Stroke(_SPACE)]
    return out
