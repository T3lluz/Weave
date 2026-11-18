"""The phone on this computer (its screen in a window, its sound through the speakers) rides on
adb and scrcpy on Linux. Not on Windows yet: this stands in, so the rest of the daemon needn't ask."""

NOT_YET = "not on Windows yet"


class Phone:
    def __init__(self, cfg, notify, changed):
        self.hints: list[str] = []
        self.mirroring = False
        self.audio_wanted = False
        self.audio_on = False
        self.audio_source = "output"
        self.serial = None
        self.model = "Phone"
        self.adb_path = None
        self.scrcpy_path = None

    async def find(self):
        return None

    async def mirror(self, on: bool) -> str:
        return NOT_YET

    async def audio(self, on: bool) -> str:
        return NOT_YET

    async def push(self, paths) -> str:
        return NOT_YET

    async def read_volume(self):
        return None

    async def set_volume(self, *_):
        return NOT_YET

    async def refresh(self, *_):
        return None

    async def restart(self, *_):
        return None

    async def close(self):
        return None
