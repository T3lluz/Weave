"""What the daemon asks of the desktop around it, on Linux: the clipboard, notices, the pairing
question, the lock, whether a text field has the focus, and the network card to wake.
weave/win/shell.py answers the same on Windows."""

import asyncio
import json
import os

from .proc import run

PLATFORM = "linux"
# what this computer can do beyond the first version, for the phone to offer
CAPS = ["catalog", "launch", "pointer", "warp", "sound", "localcursor", "clipsync", "moveseq"]
# programs the daemon leans on, for `weave status` to name the missing ones
NEEDS = ["gpu-screen-recorder", "wl-copy", "wl-paste", "notify-send"]


async def clip_get(timeout: float = 5) -> str:
    return await run("wl-paste", "-n", "-t", "text", timeout=timeout)


async def clip_set(text: str):
    await run("wl-copy", stdin=text.encode())


def notify(title: str, body: str = "", urgency: str = "normal", ms: int = 5000):
    asyncio.create_task(run(
        "notify-send", "-a", "Weave", "-i", "phone", "-u", urgency, "-t", str(ms),
        "-h", "string:x-canonical-private-synchronous:weave", title, body))


async def ask(title: str, body: str, timeout: float) -> bool | None:
    """A question with Allow and Deny on it: the answer, or None when nobody gave one."""
    out = await run(
        "notify-send", "-a", "Weave", "-i", "phone", "-t", str(int(timeout * 1000)), "-w",
        "-A", "default=Allow", "-A", "deny=Deny", title, body, timeout=timeout)
    return (out.strip() == "default") if out.strip() else None


async def lock():
    await run("loginctl", "lock-session")


async def text_focused() -> bool:
    """Whether what has the focus is something you type into: fcitx5 hears from every app when a
    text field takes it."""
    info = await run("busctl", "--user", "call", "org.fcitx.Fcitx5", "/controller",
                     "org.fcitx.Fcitx.Controller1", "DebugInfo", timeout=1)
    return " focus:1" in info


async def wake_info() -> dict | None:
    """The network card the computer reaches the world through: its MAC and its network's broadcast
    address, for a magic packet that wakes it. Only from the same network: asleep, it is off the tailnet."""
    try:
        iface = None
        with open("/proc/net/route") as f:
            for line in f.readlines()[1:]:
                parts = line.split()
                if len(parts) > 2 and parts[1] == "00000000":
                    iface = parts[0]
                    break
        if not iface:
            return None
        mac = open(f"/sys/class/net/{iface}/address").read().strip()
        addrs = json.loads(await run("ip", "-j", "-4", "addr", "show", "dev", iface) or "[]")
        brd = next((a.get("broadcast") for x in addrs for a in x.get("addr_info", []) if a.get("broadcast")), None)
        wifi = os.path.isdir(f"/sys/class/net/{iface}/wireless")
        return {"mac": mac, "brd": brd, "wifi": wifi}
    except (OSError, ValueError):
        return None
