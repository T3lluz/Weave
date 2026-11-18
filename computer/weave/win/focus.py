"""Whether what has the focus on Windows is something you type into, so the phone can raise
its keyboard when a click lands in a text field and leave it down when it doesn't.

Windows' accessibility layer (UI Automation) knows: it is what a screen reader asks. A helper
that stays running answers one question per line, in a few milliseconds; starting PowerShell
for each click would take a second. Classic programs, the Store's apps and browsers all answer
it, which the older way (is a caret blinking?) did not.
"""

import asyncio

from . import helpers

NO_WINDOW = 0x08000000

SOURCE = r'''
using System;
using System.Windows.Automation;

static class WeaveFocus {
    // "1" when the focused thing takes typing, "0" when it doesn't, with why after a space.
    static string Look() {
        AutomationElement el;
        try { el = AutomationElement.FocusedElement; } catch (Exception) { return "0 nothing"; }
        if (el == null) return "0 nothing";
        try {
            ControlType kind = el.Current.ControlType;
            string name = kind.ProgrammaticName.Replace("ControlType.", "");
            if (!el.Current.IsEnabled) return "0 " + name + " disabled";
            object p;
            bool hasValue = el.TryGetCurrentPattern(ValuePattern.Pattern, out p);
            bool readOnly = hasValue && ((ValuePattern)p).Current.IsReadOnly;
            object t;
            bool hasText = el.TryGetCurrentPattern(TextPattern.Pattern, out t);
            string detail = name + (hasValue ? (readOnly ? " value:readonly" : " value") : "") + (hasText ? " text" : "")
                + " class:" + el.Current.ClassName + " fw:" + el.Current.FrameworkId;
            if (kind == ControlType.Edit) return (readOnly ? "0 " : "1 ") + detail;
            if (kind == ControlType.ComboBox) return (hasValue && !readOnly ? "1 " : "0 ") + detail;
            // A document is typed into when it can be changed. A web page is a document too, and
            // says it is read-only; an editor's page says it isn't, or has text and no value at all.
            if (kind == ControlType.Document) return ((hasValue ? !readOnly : hasText) ? "1 " : "0 ") + detail;
            return "0 " + detail;
        } catch (Exception) {
            return "0 gone";   // it closed while being asked
        }
    }

    [STAThread]
    static int Main(string[] args) {
        string line;
        while ((line = Console.ReadLine()) != null) {
            Console.WriteLine(Look());
            Console.Out.Flush();
        }
        return 0;
    }
}
'''

_proc: asyncio.subprocess.Process | None = None
_lock = asyncio.Lock()


async def _helper():
    global _proc
    if _proc and _proc.returncode is None:
        return _proc
    exe = await helpers.build("weave-focus", SOURCE, ("UIAutomationClient", "UIAutomationTypes", "WindowsBase"))
    if not exe:
        return None
    _proc = await asyncio.create_subprocess_exec(
        exe, stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL, creationflags=NO_WINDOW)
    return _proc


async def describe() -> str | None:
    """The helper's whole answer ("1 Edit value class:... fw:..."); None when it gave none in time."""
    async with _lock:
        try:
            proc = await _helper()
            if not proc:
                return None
            proc.stdin.write(b"\n")
            await proc.stdin.drain()
            return (await asyncio.wait_for(proc.stdout.readline(), 1.5)).decode(errors="replace").strip() or None
        except (OSError, asyncio.TimeoutError, ConnectionError):
            if _proc and _proc.returncode is None:
                _proc.kill()   # stuck on a program that doesn't answer: a fresh one next time
            return None


async def typable() -> bool | None:
    """Whether the focus is on something that takes typing; None when Windows wouldn't say."""
    answer = await describe()
    return None if answer is None else answer.startswith("1")
