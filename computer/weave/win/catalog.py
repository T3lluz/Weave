"""What the PC can do for a shortcut, on Windows: the programs in the Start menu, with their
icons, and opening one or a web page. There are no bindings to list: Windows has no file of them."""

import asyncio
import base64
import hashlib
import json
import os
import time
from pathlib import Path

from ..config import DATA_DIR
from ..proc import run

APPS_TTL = 120
ICON_PX = 96
# Start menu entries that aren't programs anyone would make a shortcut of
SKIP = ("uninstall", "readme", "release notes", "license", "documentation", "help", "website", "manual")

_apps_cache: tuple[float, list[dict]] | None = None
_apps_lock = asyncio.Lock()


def start_menus() -> list[Path]:
    roots = [os.environ.get("ProgramData"), os.environ.get("APPDATA")]
    return [Path(r) / "Microsoft" / "Windows" / "Start Menu" / "Programs" for r in roots if r]


def app_id(name: str) -> str:
    """A name the phone can keep: the entry's own, lower case, with nothing a JSON key minds."""
    return "".join(c if c.isalnum() else "-" for c in name.lower()).strip("-")


def scan_apps(menus: list[Path] | None = None) -> list[dict]:
    """Every program shortcut of the Start menu, once each by name, the user's own winning."""
    seen: dict[str, dict] = {}
    for menu in (menus if menus is not None else start_menus()):
        if not menu.is_dir():
            continue
        for link in sorted(menu.rglob("*.lnk")):
            name = link.stem
            if any(word in name.lower() for word in SKIP):
                continue
            seen[app_id(name)] = {"id": app_id(name), "name": name, "path": str(link)}
    return sorted(seen.values(), key=lambda a: a["name"].lower())


# One PowerShell run draws every missing icon: the shell's own picture of each shortcut at
# ICON_PX, alpha kept (Image.FromHbitmap drops it, so the same bits are read again as ARGB).
ICON_SCRIPT = r'''
param($list)
Add-Type -AssemblyName System.Drawing
Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @"
using System; using System.Drawing; using System.Drawing.Imaging; using System.Runtime.InteropServices;
public static class WeaveIcon {
  [StructLayout(LayoutKind.Sequential)] struct SIZE { public int cx, cy; }
  [ComImport, Guid("bcc18b79-ba16-442f-80c4-8a59c30c463b"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IShellItemImageFactory { void GetImage(SIZE size, int flags, out IntPtr phbm); }
  [DllImport("shell32.dll", CharSet = CharSet.Unicode, PreserveSig = false)]
  static extern void SHCreateItemFromParsingName(string path, IntPtr pbc, ref Guid riid, [MarshalAs(UnmanagedType.Interface)] out IShellItemImageFactory ppv);
  [DllImport("gdi32.dll")] static extern bool DeleteObject(IntPtr o);
  public static void Save(string path, int px, string png) {
    Guid iid = new Guid("bcc18b79-ba16-442f-80c4-8a59c30c463b");
    IShellItemImageFactory f; SHCreateItemFromParsingName(path, IntPtr.Zero, ref iid, out f);
    IntPtr hbm; f.GetImage(new SIZE { cx = px, cy = px }, 4, out hbm);
    try {
      using (Bitmap raw = Image.FromHbitmap(hbm)) {
        BitmapData d = raw.LockBits(new Rectangle(0, 0, raw.Width, raw.Height), ImageLockMode.ReadOnly, raw.PixelFormat);
        try { using (Bitmap argb = new Bitmap(d.Width, d.Height, d.Stride, PixelFormat.Format32bppArgb, d.Scan0)) { argb.Save(png, ImageFormat.Png); } }
        finally { raw.UnlockBits(d); }
      }
    } finally { DeleteObject(hbm); }
  }
}
"@
foreach ($job in (Get-Content -Raw -Encoding UTF8 $list | ConvertFrom-Json)) {
  try { [WeaveIcon]::Save($job.path, $job.px, $job.png) }
  catch { try { [System.Drawing.Icon]::ExtractAssociatedIcon($job.path).ToBitmap().Save($job.png, [System.Drawing.Imaging.ImageFormat]::Png) } catch {} }
}
'''


def icon_file(app: dict) -> Path:
    """Where an app's icon is kept: named after the shortcut and when it last changed."""
    try:
        stamp = int(os.path.getmtime(app["path"]))
    except OSError:
        stamp = 0
    return DATA_DIR / "icons" / (hashlib.sha1(f"{app['path']}:{stamp}:{ICON_PX}".encode()).hexdigest()[:20] + ".png")


async def draw_icons(apps: list[dict]):
    """Draws the icons not on disk yet, all in one go."""
    missing = [a for a in apps if not icon_file(a).is_file()]
    if not missing:
        return
    icons = DATA_DIR / "icons"
    icons.mkdir(parents=True, exist_ok=True)
    script, jobs = icons / "draw.ps1", icons / "jobs.json"
    script.write_text(ICON_SCRIPT, encoding="utf-8")
    jobs.write_text(json.dumps([{"path": a["path"], "px": ICON_PX, "png": str(icon_file(a))} for a in missing]), encoding="utf-8")
    await run("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(script), str(jobs), timeout=90)


async def apps(with_icons: bool = True) -> list[dict]:
    """The Start menu's programs, each with its icon as base64 PNG (or none)."""
    global _apps_cache
    async with _apps_lock:
        if _apps_cache and time.monotonic() - _apps_cache[0] < APPS_TTL:
            found = _apps_cache[1]
        else:
            found = await asyncio.to_thread(scan_apps)
            _apps_cache = (time.monotonic(), found)
        if with_icons:
            await draw_icons(found)
    out = [{"id": a["id"], "name": a["name"]} for a in found]
    for a, o in zip(found, out):
        if not with_icons:
            o["has_icon"] = True   # every shortcut has a picture: the shell draws one for anything
            continue
        try:
            o["icon"] = base64.b64encode(icon_file(a).read_bytes()).decode()
        except OSError:
            pass
    return out


def app_name(wanted: str) -> str | None:
    if _apps_cache:
        return next((a["name"] for a in _apps_cache[1] if a["id"] == wanted), None)
    return None


async def launch(wanted: str) -> str:
    """Opens a program by its Start menu entry, as a click there would: "ok", "gone" or "failed".
    One already running comes to the front when it is the kind that keeps to one window."""
    await apps(with_icons=False)
    app = next((a for a in (_apps_cache[1] if _apps_cache else []) if a["id"] == wanted), None)
    if not app:
        return "gone"
    try:
        os.startfile(app["path"])
        return "ok"
    except OSError:
        return "failed"


async def open_url(url: str):
    """A web page in the PC's own browser."""
    try:
        os.startfile(url)
    except OSError:
        pass


async def binds() -> list[dict]:
    return []


async def bound_code(name: str) -> bool:
    """A raw Linux key code (`#30`) presses nothing on Windows."""
    return not name.startswith("#")
