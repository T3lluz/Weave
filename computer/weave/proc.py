"""Running another program and reading what it prints."""

import asyncio
import sys

# A console program started by a daemon that has no console of its own (Windows, at logon) gets a
# new one, in a window, on the user's screen. This keeps it without.
_QUIET = {"creationflags": 0x08000000} if sys.platform == "win32" else {}


async def run(*args, stdin: bytes | None = None, timeout: float = 5) -> str:
    """What the program printed, or nothing when it isn't there, failed to start or took too long."""
    try:
        proc = await asyncio.create_subprocess_exec(
            *args, stdin=asyncio.subprocess.PIPE if stdin is not None else asyncio.subprocess.DEVNULL,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL, **_QUIET)
        out, _ = await asyncio.wait_for(proc.communicate(stdin), timeout)
        return out.decode(errors="replace")
    except (OSError, asyncio.TimeoutError):
        return ""
