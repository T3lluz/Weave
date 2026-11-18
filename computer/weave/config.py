"""Settings and what the daemon remembers between runs. Plain JSON, no schema."""

import json
import os
import sys
from pathlib import Path

if sys.platform == "win32":
    # %APPDATA%\\Weave for settings, %LOCALAPPDATA%\\Weave for what the daemon remembers
    _roaming = Path(os.environ.get("APPDATA", Path.home() / "AppData/Roaming")) / "Weave"
    _local = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData/Local")) / "Weave"
    CONFIG_DIR, STATE_DIR, DATA_DIR, RUNTIME_DIR = _roaming, _local / "state", _local / "data", _local
else:
    CONFIG_DIR = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "weave"
    STATE_DIR = Path(os.environ.get("XDG_STATE_HOME", Path.home() / ".local/state")) / "weave"
    DATA_DIR = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local/share")) / "weave"
    RUNTIME_DIR = Path(os.environ.get("XDG_RUNTIME_DIR", "/tmp"))
SOCKET = RUNTIME_DIR / "weave.sock"

DEFAULTS = {
    # The dashboard whose bridge is the meeting place (laptop_link.py there): the laptop announces
    # itself to it, DailyDash lists it and connects with a tap, and the phone page can run it.
    # "https://example.com", or empty to pair by hand.
    "dashboard": "",
    # The phone connects here, from this computer's own network or a tailnet. Set allow_lan to
    # false to take tailnet phones only: on a network you don't trust, what the phone and the
    # computer say to each other can be read by others on it.
    "port": 47600,
    "allow_lan": True,
    # Phone controls the laptop.
    "video": {
        # name: [longest side limit, gpu-screen-recorder quality, fps]
        "presets": {
            "low": ["1280x800", "medium", 30],
            "mid": ["1920x1200", "high", 60],
            "high": ["2560x1600", "very_high", 60],
        },
        "audio_kbps": 160,
        # While the phone follows the pointer, the other monitors are recorded too (unseen), so
        # the picture moves to the next monitor the moment the pointer does instead of after a
        # recorder starts (a third to two thirds of a second). Costs CPU and memory while the
        # remote is open with more than one monitor.
        "warm": True,
    },
    # The phone driving the laptop.
    "remote": {
        # the laptop's sound starts playing on the phone as soon as the remote opens
        "sound": False,
        # a notification here each time a phone connects
        "notify": True,
    },
    # Window classes where a click always means "I want to type".
    "terminals": ["Alacritty", "com.mitchellh.ghostty", "kitty", "foot", "org.wezfurlong.wezterm", "com.omarchy.terminal",
                  "WindowsTerminal.exe", "cmd.exe", "powershell.exe", "pwsh.exe", "conhost.exe"],
    # Laptop controls the phone (scrcpy).
    "mirror": {
        "keyboard": "uhid",
        "bit_rate": "12M",
        "max_fps": 120,
        "codec": "h265",
        "stay_awake": True,
        "screen_off": False,
        # the window is this share of the monitor's height
        "height": 0.82,
        "with_audio": True,
        "extra_args": [],
    },
    # calls: switch to the caller's voice while a phone call is up (the normal output leaves calls out)
    # volume: a phone on silent would send silence, so the sent volume is raised to this share of full
    # when it is muted or very low (the phone's speaker keeps its own level). null leaves it alone.
    "phone_audio": {"buffer_ms": 60, "calls": True, "volume": 0.8, "extra_args": []},
    # Where files the phone sends land.
    "downloads": str(Path.home() / "Downloads"),
}


def _merge(base, over):
    out = dict(base)
    for key, value in over.items():
        out[key] = _merge(base[key], value) if isinstance(value, dict) and isinstance(base.get(key), dict) else value
    return out


def load() -> dict:
    path = CONFIG_DIR / "config.json"
    try:
        return _merge(DEFAULTS, json.loads(path.read_text()))
    except FileNotFoundError:
        return dict(DEFAULTS)
    except (OSError, ValueError) as err:
        print(f"weave: ignoring {path}: {err}")
        return dict(DEFAULTS)


# What the dashboard's laptop page may change, and what each may be: a tuple of choices,
# a (low, high) range for a float, or bool. Anything else in a patch is dropped.
SETTINGS = {
    ("mirror", "keyboard"): ("uhid", "sdk"),
    ("mirror", "bit_rate"): ("4M", "8M", "12M", "20M"),
    ("mirror", "max_fps"): (60, 120),
    ("mirror", "screen_off"): bool,
    ("mirror", "stay_awake"): bool,
    ("mirror", "with_audio"): bool,
    ("mirror", "height"): [0.5, 0.95],
    ("phone_audio", "calls"): bool,
    ("phone_audio", "buffer_ms"): (30, 60, 120),
    ("phone_audio", "volume"): [0.0, 1.0],     # 0 leaves a silent phone silent
    ("video", "audio_kbps"): (96, 160, 256),
    ("video", "warm"): bool,
    ("remote", "sound"): bool,
    ("remote", "notify"): bool,
}


def settings_of(cfg: dict) -> dict:
    """The changeable settings as they stand, grouped as in the file."""
    out: dict = {}
    for group, key in SETTINGS:
        out.setdefault(group, {})[key] = cfg.get(group, {}).get(key)
    return out


def apply_patch(cfg: dict, patch: dict) -> list[tuple[str, str]]:
    """Takes the valid parts of a patch into cfg and the user's file. Returns what changed."""
    changed = []
    for (group, key), rule in SETTINGS.items():
        if not isinstance(patch.get(group), dict) or key not in patch[group]:
            continue
        value = patch[group][key]
        if rule is bool:
            value = bool(value)
        elif isinstance(rule, list):
            try:
                value = min(max(float(value or 0), rule[0]), rule[1])
            except (TypeError, ValueError):
                continue
            value = round(value, 2)
        elif value not in rule:
            continue
        if cfg.setdefault(group, {}).get(key) != value:
            cfg[group][key] = value
            changed.append((group, key))
    if changed:
        path = CONFIG_DIR / "config.json"
        try:
            mine = json.loads(path.read_text())
        except (OSError, ValueError):
            mine = {}
        for group, key in changed:
            mine.setdefault(group, {})[key] = cfg[group][key]
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(".tmp")
        tmp.write_text(json.dumps(mine, indent=2) + "\n")
        tmp.replace(path)
    return changed


class Store:
    """A JSON file under the state dir, read once and written on every change."""

    def __init__(self, name: str, default):
        self.path = STATE_DIR / name
        try:
            self.data = json.loads(self.path.read_text())
        except (OSError, ValueError):
            self.data = default

    def save(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.data, indent=2))
        os.chmod(tmp, 0o600)
        tmp.replace(self.path)
