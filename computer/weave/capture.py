"""The laptop's screen and sound as a stream the phone can decode.

gpu-screen-recorder captures a monitor straight from the GPU (no portal prompt), encodes
H.264 on it and writes FLV to a pipe. FLV says where each frame ends, so every frame goes
out the moment it is encoded. Video is re-packed from length-prefixed NAL units to Annex B,
which is what Android's decoder takes; audio is AAC as it comes.
"""

import asyncio
import signal

KEY, CONFIG = 1, 2
GOP_MAX = 240   # a keyframe a second at up to 120 frames: never reached unless the encoder misbehaves


def _annexb(avcc: bytes, length_size: int = 4) -> bytes:
    out = bytearray()
    pos, end = 0, len(avcc)
    while pos + length_size <= end:
        size = int.from_bytes(avcc[pos:pos + length_size], "big")
        pos += length_size
        if size <= 0 or pos + size > end:
            break
        out += b"\x00\x00\x00\x01"
        out += avcc[pos:pos + size]
        pos += size
    return bytes(out)


def _parameter_sets(record: bytes) -> tuple[bytes, int]:
    """SPS and PPS out of an AVCDecoderConfigurationRecord, as Annex B, and the NAL length size."""
    length_size = (record[4] & 3) + 1
    out = bytearray()
    pos = 5
    count = record[pos] & 0x1F
    pos += 1
    for _ in range(count):
        size = int.from_bytes(record[pos:pos + 2], "big")
        out += b"\x00\x00\x00\x01" + record[pos + 2:pos + 2 + size]
        pos += 2 + size
    count = record[pos]
    pos += 1
    for _ in range(count):
        size = int.from_bytes(record[pos:pos + 2], "big")
        out += b"\x00\x00\x00\x01" + record[pos + 2:pos + 2 + size]
        pos += 2 + size
    return bytes(out), length_size


class Capture:
    """One gpu-screen-recorder run of one monitor.

    Calls on_video(capture, flags, pts_ms, data), on_audio(capture, ...) and on_exit(capture, code),
    so a session can tell the run it shows from one still starting up beside it. It keeps the
    latest decoder setup of each kind, for whoever starts listening late.
    """

    # extra arguments for starting the recorder (Windows hides its console window)
    spawn: dict = {}

    def __init__(self, monitor: dict, preset: str, limit: str, quality: str, fps: int, audio_kbps: int, on_video, on_audio, on_exit,
                 cursor: bool = True):
        self.mon = monitor
        self.monitor = monitor["name"]
        self.preset = preset
        self.video_config: bytes | None = None
        self.audio_config: bytes | None = None
        # every frame since the last keyframe, keyframe first: enough to show this monitor at once
        self.gop: list[tuple[int, int, bytes]] = []
        self.args = [
            "gpu-screen-recorder", "-w", self.monitor, "-c", "flv", "-k", "h264", "-f", str(fps),
            # the pointer left out when the phone draws its own (it moves there the moment the finger does)
            "-bm", "vbr", "-q", quality, "-keyint", "1", "-cursor", "yes" if cursor else "no", "-s", limit,
            "-a", "default_output", "-ac", "aac", "-ab", str(audio_kbps),
        ]
        self.on_video, self.on_audio, self.on_exit = on_video, on_audio, on_exit
        self.proc = None
        self.task = None
        self.errors: list[str] = []
        self.flowing = asyncio.Event()   # the recorder is past its start-up (or gone): stop() may go ahead
        self.got_frame = False           # it wrote at least one frame: it really started
        self.got_sound = False           # and at least one piece of sound
        self.stopped = False

    async def start(self):
        self.proc = await asyncio.create_subprocess_exec(
            *self.args, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
            limit=1 << 22, **self.spawn,
        )
        if self.stopped:
            # told to stop while it was being started: it never gets to run unseen
            self.proc.kill()
        self.task = asyncio.create_task(self._read())
        self._error_task = asyncio.create_task(self._errors())   # kept, so it isn't collected mid-read

    async def _errors(self):
        try:
            async for line in self.proc.stderr:
                text = line.decode(errors="replace").strip()
                if "error" in text.lower():
                    self.errors.append(text)
                    del self.errors[:-5]
        except (OSError, ValueError):
            pass

    async def _read(self):
        out = self.proc.stdout
        length_size = 4
        try:
            await out.readexactly(13)  # "FLV", version, flags, header size, first back-pointer
            while True:
                head = await out.readexactly(11)
                kind = head[0]
                size = int.from_bytes(head[1:4], "big")
                pts = int.from_bytes(head[4:7], "big") | (head[7] << 24)
                body = await out.readexactly(size + 4)  # the tag, then a back-pointer to skip
                if not self.flowing.is_set():
                    self.flowing.set()
                if kind == 9 and size > 5 and body[0] & 0x0F == 7:
                    if body[1] == 0:
                        sets, length_size = _parameter_sets(body[5:size])
                        self.video_config = sets
                        self.on_video(self, CONFIG, pts, sets)
                    elif body[1] == 1:
                        # a real picture: the muxer writes its header and setup before the encoder
                        # has made anything, so only this says the recorder truly started
                        self.got_frame = True
                        flags = KEY if body[0] >> 4 == 1 else 0
                        frame = _annexb(body[5:size], length_size)
                        if flags & KEY:
                            self.gop = [(flags, pts, frame)]
                        elif self.gop and len(self.gop) < GOP_MAX:
                            self.gop.append((flags, pts, frame))
                        self.on_video(self, flags, pts, frame)
                elif kind == 8 and size > 2 and body[0] >> 4 == 10:
                    if body[1] == 0:
                        self.audio_config = body[2:size]
                    else:
                        self.got_sound = True
                    self.on_audio(self, CONFIG if body[1] == 0 else 0, pts, body[2:size])
        except (asyncio.IncompleteReadError, OSError, IndexError):
            pass
        finally:
            self.flowing.set()
            code = await self.proc.wait()
            self.on_exit(self, code)

    async def stop(self):
        self.stopped = True
        proc, self.on_exit = self.proc, lambda *_: None
        self.on_video = self.on_audio = lambda *_: None
        if not proc or proc.returncode is not None:
            return
        # Interrupted while it sets up its encoder and audio, gpu-screen-recorder can crash
        # (it dumped core in swr_alloc). A run given up on before it showed anything, because
        # the pointer went straight back, is let finish starting first.
        try:
            await asyncio.wait_for(self.flowing.wait(), 1.5)
        except asyncio.TimeoutError:
            pass
        try:
            proc.send_signal(signal.SIGINT)
            await asyncio.wait_for(proc.wait(), 1.5)
        except asyncio.TimeoutError:
            proc.kill()
        except ProcessLookupError:
            pass


class SoundCapture(Capture):
    """The laptop's sound alone, for while the phone has the picture off: ffmpeg reads what the
    default output plays (PipeWire's Pulse side), or what [source] does, and writes AAC in FLV, so
    the same reader takes it."""

    def __init__(self, audio_kbps: int, on_audio, on_exit, source: str = "@DEFAULT_MONITOR@"):
        super().__init__({"name": "sound", "label": "Sound"}, "sound", "", "", 1, audio_kbps,
                         lambda *_: None, on_audio, on_exit)
        self.args = [
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-fflags", "nobuffer",
            # 10 ms reads, so the sound isn't held back waiting for a big block
            "-f", "pulse", "-fragment_size", "3840", "-i", source,
            "-c:a", "aac", "-b:a", f"{audio_kbps}k", "-ar", "48000", "-ac", "2",
            "-f", "flv", "-flvflags", "no_duration_filesize", "pipe:1",
        ]
