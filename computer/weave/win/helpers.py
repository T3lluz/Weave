"""Small C# programs the Windows side needs, compiled on the PC by the compiler every Windows
has (PowerShell's Add-Type). Nothing is downloaded, and each program's source sits beside the
Python that uses it. A program is built once, and again only when its source changes."""

import asyncio
import hashlib

from ..config import DATA_DIR
from ..proc import run

def ps_quote(text: str) -> str:
    """[text] as a PowerShell string that is only ever text. PowerShell ends a single-quoted
    string at the typographic quotes too, so all of them are doubled, not just the plain one."""
    for quote in "'\u2018\u2019\u201a\u201b":
        text = text.replace(quote, quote + quote)
    return "'" + text + "'"


_built: dict[str, str | None] = {}
_lock = asyncio.Lock()


async def build(name: str, source: str, references: tuple[str, ...] = ()) -> str | None:
    """The path of [name] built from [source]; None when this PC wouldn't build it."""
    async with _lock:
        if name in _built:
            return _built[name]
        folder = DATA_DIR / "helpers"
        exe = folder / f"{name}-{hashlib.sha1(source.encode()).hexdigest()[:10]}.exe"
        if not exe.is_file():
            folder.mkdir(parents=True, exist_ok=True)
            code = folder / f"{name}.cs"
            code.write_text(source, encoding="utf-8")
            refs = f" -ReferencedAssemblies {','.join(references)}" if references else ""
            await run(
                "powershell.exe", "-NoProfile", "-Command",
                f"Add-Type -TypeDefinition (Get-Content -Raw -Encoding UTF8 -LiteralPath {ps_quote(str(code))}){refs} "
                f"-OutputAssembly {ps_quote(str(exe))} -OutputType ConsoleApplication",
                timeout=90)
            # an older build of the same helper only takes space
            for old in folder.glob(f"{name}-*.exe"):
                if old != exe:
                    try:
                        old.unlink()
                    except OSError:
                        pass
        _built[name] = str(exe) if exe.is_file() else None
        if not _built[name]:
            print(f"weave: the {name} helper wouldn't build on this PC")
        return _built[name]
