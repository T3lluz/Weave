"""The desktop the daemon runs on. Everything that differs between Linux (Hyprland) and Windows
is picked here, once: the screens and pointer (`hypr`), the mouse and keyboard (`Injector`), the
picture and sound (`Capture`, `SoundCapture`), the apps (`catalog`), the phone on this computer
(`Phone`) and the odds and ends (`shell`). The rest of the daemon is the same on both."""

import sys

if sys.platform == "win32":
    from .win import catalog, screen as hypr, shell
    from .win.capture import Capture, SoundCapture
    from .win.inject import Injector
    from .win.phone import Phone
else:
    from . import catalog, hypr, shell
    from .capture import Capture, SoundCapture
    from .inject import Injector
    from .phone import Phone

__all__ = ["Capture", "Injector", "Phone", "SoundCapture", "catalog", "hypr", "shell"]
