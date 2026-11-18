"""The picture on Windows: ffmpeg grabs one monitor's part of the desktop (gdigrab), encodes
H.264 with the graphics card when it can and the processor when it can't, and writes FLV to a
pipe. The reading of that pipe is capture.py's, the same as on Linux."""

import asyncio
import shutil

from ..capture import Capture as _Recorder
from ..config import DATA_DIR

NO_WINDOW = 0x08000000   # ffmpeg is a console program: without this each run flashes a black box

# Encoders in the order tried, each with what makes it answer at once instead of looking ahead.
ENCODERS = [
    ("h264_nvenc", ["-preset", "p1", "-tune", "ull", "-rc", "cbr", "-zerolatency", "1"]),
    ("h264_qsv", ["-preset", "veryfast", "-look_ahead", "0", "-async_depth", "1"]),
    ("h264_amf", ["-usage", "ultralowlatency", "-quality", "speed"]),
    ("libx264", ["-preset", "ultrafast", "-tune", "zerolatency", "-sc_threshold", "0"]),
]
# bits per pixel per frame for each of the presets' quality words
BITS = {"medium": 0.045, "high": 0.075, "very_high": 0.11}

_ffmpeg: str | None = None
_encoder: tuple[str, list[str]] | None = None
_probing = asyncio.Lock()


def ffmpeg_path() -> str | None:
    """ffmpeg from the PATH, or the copy the installer put beside weave."""
    global _ffmpeg
    if _ffmpeg is None:
        own = DATA_DIR / "ffmpeg" / "bin" / "ffmpeg.exe"
        _ffmpeg = shutil.which("ffmpeg") or (str(own) if own.is_file() else None)
    return _ffmpeg


def target_size(pw: int, ph: int, limit: str) -> tuple[int, int]:
    """The picture's size: the monitor's own, shrunk to fit inside [limit] ("1920x1200") if it
    is bigger, never grown, and even both ways (H.264 halves the colour planes)."""
    try:
        lw, lh = (int(v) for v in limit.lower().split("x"))
    except ValueError:
        lw, lh = pw, ph
    k = min(1.0, lw / pw, lh / ph)
    return max(2, int(pw * k) // 2 * 2), max(2, int(ph * k) // 2 * 2)


def bitrate(w: int, h: int, fps: int, quality: str) -> int:
    """Kilobits a second for a picture of this size and pace, within what a phone on Wi-Fi takes."""
    return int(min(max(w * h * fps * BITS.get(quality, BITS["high"]) / 1000, 1500), 40_000))


def video_args(ffmpeg: str, mon: dict, limit: str, quality: str, fps: int, cursor: bool, encoder: tuple[str, list[str]]) -> list[str]:
    """The whole ffmpeg command for one monitor."""
    w, h = target_size(mon["pw"], mon["ph"], limit)
    kbps = bitrate(w, h, fps, quality)
    name, tuning = encoder
    scale = [] if (w, h) == (mon["pw"], mon["ph"]) else ["-vf", f"scale={w}:{h}:flags=fast_bilinear"]
    return [
        ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin",
        "-fflags", "nobuffer", "-flags", "low_delay", "-probesize", "32", "-analyzeduration", "0",
        "-f", "gdigrab", "-framerate", str(fps), "-draw_mouse", "1" if cursor else "0",
        "-offset_x", str(mon["x"]), "-offset_y", str(mon["y"]), "-video_size", f"{mon['pw']}x{mon['ph']}", "-i", "desktop",
        *scale, "-c:v", name, *tuning, "-pix_fmt", "yuv420p", "-bf", "0", "-g", str(fps),
        "-b:v", f"{kbps}k", "-maxrate", f"{kbps}k", "-bufsize", f"{max(kbps // 2, 500)}k",
        "-f", "flv", "-flvflags", "no_duration_filesize", "-flush_packets", "1", "pipe:1",
    ]


async def _works(ffmpeg: str, encoder: tuple[str, list[str]]) -> bool:
    """Whether this encoder really encodes here: half a second of a test picture, thrown away."""
    name, tuning = encoder
    try:
        proc = await asyncio.create_subprocess_exec(
            ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30",
            "-frames:v", "15", "-c:v", name, *tuning, "-pix_fmt", "yuv420p", "-bf", "0", "-g", "30", "-b:v", "2000k", "-f", "null", "-",
            stdin=asyncio.subprocess.DEVNULL, stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL, creationflags=NO_WINDOW)
        return await asyncio.wait_for(proc.wait(), 15) == 0
    except (OSError, asyncio.TimeoutError):
        return False


async def encoder(ffmpeg: str) -> tuple[str, list[str]] | None:
    """The first encoder that works on this PC, found once."""
    global _encoder
    async with _probing:
        if _encoder is None:
            for candidate in ENCODERS:
                if await _works(ffmpeg, candidate):
                    _encoder = candidate
                    print(f"weave: encoding with {candidate[0]}")
                    break
    return _encoder


class Capture(_Recorder):
    """One ffmpeg run of one monitor."""

    spawn = {"creationflags": NO_WINDOW}

    def __init__(self, monitor: dict, preset: str, limit: str, quality: str, fps: int, audio_kbps: int, on_video, on_audio, on_exit,
                 cursor: bool = True):
        super().__init__(monitor, preset, limit, quality, fps, audio_kbps, on_video, on_audio, on_exit, cursor)
        self._want = (limit, quality, fps, cursor)
        self.args = []

    async def start(self):
        ffmpeg = ffmpeg_path()
        enc = await encoder(ffmpeg) if ffmpeg else None
        if not enc:
            self.errors.append("ffmpeg is missing" if not ffmpeg else "ffmpeg has no H.264 encoder that works here")
            self.flowing.set()
            self.on_exit(self, 1)
            return
        limit, quality, fps, cursor = self._want
        self.args = video_args(ffmpeg, self.mon, limit, quality, fps, cursor, enc)
        await super().start()

    async def stop(self):
        self.stopped = True
        proc, self.on_exit = self.proc, lambda *_: None
        self.on_video = self.on_audio = lambda *_: None
        if not proc or proc.returncode is not None:
            return
        try:
            proc.terminate()
            await asyncio.wait_for(proc.wait(), 1.5)
        except asyncio.TimeoutError:
            proc.kill()
        except ProcessLookupError:
            pass


class SoundCapture(Capture):
    """The computer's sound alone. Not on Windows yet: ffmpeg can't hear what Windows plays."""

    def __init__(self, audio_kbps: int, on_audio, on_exit):
        _Recorder.__init__(self, {"name": "sound", "label": "Sound"}, "sound", "", "", 1, audio_kbps, lambda *_: None, on_audio, on_exit)

    async def start(self):
        self.errors.append("the computer's sound isn't sent from Windows yet")
        self.flowing.set()
        self.on_exit(self, 1)
