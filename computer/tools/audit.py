#!/usr/bin/env python3
"""A phone on the command line, for checking a computer's side of Weave end to end.

    audit.py HOST[:PORT] [--code CODE] [--token TOKEN] [--out DIR]

It connects as a phone would, says hello (with a pairing code when given), and then tries each
thing the phone can ask for, saying for each whether it worked and what it saw. The picture it
receives is written to DIR/picture.h264, for a look with ffmpeg. It moves the pointer, types
into whatever has the focus and copies to the clipboard, so run it against a test machine.
"""

import argparse
import asyncio
import json
import os
import struct
import sys
import time

JSON, VIDEO, AUDIO = 1, 2, 3
KEY, CONFIG = 1, 2


class Phone:
    def __init__(self, reader, writer):
        self.r, self.w = reader, writer
        self.inbox: list[dict] = []
        self.video: list[tuple[int, int, bytes]] = []
        self.audio = 0
        self.task = asyncio.create_task(self._read())

    async def _read(self):
        try:
            while True:
                kind, size = struct.unpack(">BI", await self.r.readexactly(5))
                body = await self.r.readexactly(size)
                if kind == JSON:
                    self.inbox.append(json.loads(body))
                elif kind == VIDEO:
                    self.video.append((body[0], struct.unpack(">Q", body[1:9])[0], body[9:]))
                elif kind == AUDIO:
                    self.audio += 1
        except (asyncio.IncompleteReadError, ConnectionError, OSError):
            self.inbox.append({"t": "_closed"})

    def send(self, **msg):
        body = json.dumps(msg).encode()
        self.w.write(struct.pack(">BI", JSON, len(body)) + body)

    async def wait(self, kind: str, timeout: float = 5, since: int = 0, where=None) -> dict | None:
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            for m in self.inbox[since:]:
                if m.get("t") == kind and (where is None or where(m)):
                    return m
            if any(m.get("t") == "_closed" for m in self.inbox):
                return None
            await asyncio.sleep(0.02)
        return None


RESULTS: list[tuple[str, str, str]] = []


def say(name: str, ok, note: str = ""):
    word = "ok" if ok is True else "FAIL" if ok is False else str(ok)
    RESULTS.append((name, word, note))
    print(f"{word:5} {name}" + (f"  ({note})" if note else ""), flush=True)


async def audit(host: str, port: int, code: str, token: str, out: str) -> int:
    try:
        reader, writer = await asyncio.wait_for(asyncio.open_connection(host, port), 6)
    except (OSError, asyncio.TimeoutError) as err:
        say("connect", False, str(err))
        return 1
    say("connect", True, f"{host}:{port}")
    p = Phone(reader, writer)
    p.send(t="hello", token=token, ticket=code, name="Audit phone", ver=3, net="wifi", cursor="local")
    first = None
    for _ in range(300):
        first = next((m for m in p.inbox if m.get("t") in ("welcome", "pending", "denied", "_closed")), None)
        if first:
            break
        await asyncio.sleep(0.02)
    if not first or first["t"] != "welcome":
        say("let in", False, f"the computer said {first['t'] if first else 'nothing'}" + ("" if code else " (no pairing code given)"))
        return 1
    w = first
    say("let in", True, f"{w.get('host')}, weave {w.get('ver')}, caps {','.join(w.get('caps', []))}")
    mons = w.get("monitors") or []
    say("monitors", bool(mons), ", ".join(f"{m['name']} {m['w']}x{m['h']} at {m['x']},{m['y']}" for m in mons))
    say("pointer gain", w.get("gain") is not None, str(w.get("gain")))
    say("wake info", "ok" if w.get("wake") else "none", json.dumps(w.get("wake")))
    caps = set(w.get("caps", []))

    # ── round trip ──
    t = time.monotonic()
    p.send(t="ping", id=1)
    pong = await p.wait("pong", 3)
    say("ping", bool(pong), f"{(time.monotonic() - t) * 1000:.0f} ms")

    # ── the picture ──
    mark = len(p.inbox)
    p.send(t="video", on=True, monitor="auto", quality="mid")
    started = await p.wait("video", 25, mark, lambda m: m.get("on") is not None)
    end = time.monotonic() + 20
    while time.monotonic() < end and sum(1 for f in p.video if not f[0] & CONFIG) < 30:
        await asyncio.sleep(0.1)
    frames = [f for f in p.video if not f[0] & CONFIG]
    configs = [f for f in p.video if f[0] & CONFIG]
    keys = [f for f in frames if f[0] & KEY]
    err = next((m.get("error") for m in p.inbox[mark:] if m.get("t") == "video" and m.get("error")), None)
    say("picture starts", bool(started and started.get("on") and frames), err or (f"monitor {started.get('monitor')}" if started else "no answer"))
    if frames:
        span = max((frames[-1][1] - frames[0][1]) / 1000, 0.001)
        say("picture flows", len(frames) >= 30, f"{len(frames)} frames, {len(keys)} keyframes, {len(frames) / span:.0f} fps, {sum(len(f[2]) for f in frames) * 8 / span / 1000:.0f} kbit/s")
        say("decoder setup sent", bool(configs), f"{len(configs[0][2]) if configs else 0} bytes of SPS/PPS")
        os.makedirs(out, exist_ok=True)
        with open(os.path.join(out, "picture.h264"), "wb") as f:
            for flags, _, data in p.video:
                f.write(data)

    # ── the pointer: moves land, and the reports say which ──
    # from a third of the way in: left in a corner by whoever used it last, it has nowhere to go
    p.send(t="abs", x=0.3, y=0.3)
    await asyncio.sleep(0.4)
    mark = len(p.inbox)
    for i in range(1, 11):
        p.send(t="m", dx=12, dy=7, s=i)
        await asyncio.sleep(0.02)
    await asyncio.sleep(0.5)
    curs = [m for m in p.inbox[mark:] if m.get("t") == "cur"]
    if len(curs) >= 2 and mons:
        m0 = next((m for m in mons if m["name"] == (started or {}).get("monitor")), mons[0])
        dx = (curs[-1]["x"] - curs[0]["x"]) * m0.get("lw", m0["w"])
        dy = (curs[-1]["y"] - curs[0]["y"]) * m0.get("lh", m0["h"])
        say("pointer moves", abs(dx) > 20 and abs(dy) > 10, f"went {dx:.0f},{dy:.0f} px in {len(curs)} reports for 120,70 asked (x gain {w.get('gain')})")
        say("moves are numbered", any("seq" in c for c in curs), f"last report shows move {curs[-1].get('seq')}")
    else:
        say("pointer moves", False, f"{len(curs)} pointer reports")
    # back near where it was
    for i in range(11, 21):
        p.send(t="m", dx=-12, dy=-7, s=i)
    await asyncio.sleep(0.3)

    # ── a tap on the picture, scrolling, buttons ──
    p.send(t="abs", x=0.5, y=0.5)
    await asyncio.sleep(0.25)
    mark = len(p.inbox)
    p.send(t="m", dx=1, dy=0, s=30)
    cur = await p.wait("cur", 2, mark)
    say("tap on the picture puts the pointer there", bool(cur) and abs(cur["x"] - 0.5) < 0.02 and abs(cur["y"] - 0.5) < 0.02, f"at {cur['x']:.3f},{cur['y']:.3f}" if cur else "no report")
    p.send(t="scroll", dx=0, dy=-1)
    p.send(t="b", btn="left", down=True)
    p.send(t="b", btn="left", down=False)
    p.send(t="click", btn="right", n=1)
    await asyncio.sleep(0.3)
    p.send(t="key", key="esc", mods=[])
    say("scroll, buttons, click", "sent", "no way to see them land from here: check the screen")

    # ── the clipboard, both ways ──
    stamp = f"weave audit {int(time.time())} æøå ✓"
    mark = len(p.inbox)
    p.send(t="clip_set", text=stamp)
    toast = await p.wait("toast", 4, mark)
    mark = len(p.inbox)
    p.send(t="clip_get")
    clip = await p.wait("clip", 5, mark)
    say("clipboard: phone to computer and back", bool(clip) and clip.get("text") == stamp, (clip or {}).get("text", "no answer")[:60] + (f"; said: {toast.get('msg')}" if toast else ""))
    if "clipsync" in caps:
        mark = len(p.inbox)
        p.send(t="clip_sync", on=True)
        await asyncio.sleep(1.5)
        p.send(t="clip_set", text=stamp + " 2", quiet=True)
        await asyncio.sleep(2.5)
        echoed = [m for m in p.inbox[mark:] if m.get("t") == "clip"]
        say("clipboard sync doesn't echo the phone's own copy", not echoed, f"{len(echoed)} sent back")
        p.send(t="clip_sync", on=False)

    # ── apps and bindings ──
    if "catalog" in caps:
        mark = len(p.inbox)
        p.send(t="catalog")
        cat = await p.wait("catalog", 20, mark)
        apps = (cat or {}).get("apps", [])
        say("apps listed", bool(apps), f"{len(apps)} apps, {len((cat or {}).get('binds', []))} bindings" + (f": {(cat or {}).get('error')}" if (cat or {}).get("error") else ""))
        icons, done = 0, None
        end = time.monotonic() + 60
        while time.monotonic() < end and not done:
            for m in p.inbox[mark:]:
                if m.get("t") == "icons":
                    done = done or m.get("done")
            await asyncio.sleep(0.2)
        got = {}
        for m in p.inbox[mark:]:
            if m.get("t") == "icons":
                got.update(m.get("icons") or {})
        say("app icons", bool(got), f"{len(got)} icons for {len(apps)} apps, {sum(len(v) for v in got.values()) * 3 // 4 // 1024} KB")
        if got:
            import base64
            os.makedirs(out, exist_ok=True)
            name, data = next(iter(got.items()))
            with open(os.path.join(out, "icon.png"), "wb") as f:
                f.write(base64.b64decode(data))

    # ── text focus, typing, keys ──
    p.send(t="text", s="weave audit: typed text æøå ✓")
    p.send(t="key", key="enter", mods=[])
    p.send(t="key", key="a", mods=["ctrl"])
    await asyncio.sleep(0.4)
    say("typing and keys", "sent", "check what has the focus on the computer")
    p.send(t="media", cmd="mute")
    await asyncio.sleep(0.2)
    p.send(t="media", cmd="mute")
    say("media keys", "sent", "mute twice")

    # ── sound ──
    if "sound" in caps:
        mark_a = p.audio
        p.send(t="audio", on=True)
        await asyncio.sleep(4)
        say("sound", p.audio > mark_a, f"{p.audio - mark_a} pieces in 4 s")
        p.send(t="audio", on=False)
    else:
        say("sound", "none", "this computer doesn't offer it")

    p.send(t="video", on=False)
    await asyncio.sleep(0.3)
    writer.close()
    failed = [r for r in RESULTS if r[1] == "FAIL"]
    print(f"\n{len(RESULTS) - len(failed)} of {len(RESULTS)} checks passed or were sent; {len(failed)} failed.")
    return 1 if failed else 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("host")
    ap.add_argument("--code", default="")
    ap.add_argument("--token", default="audit-phone-token-0123456789abcdef")
    ap.add_argument("--out", default="audit-out")
    a = ap.parse_args()
    host, _, port = a.host.partition(":")
    sys.exit(asyncio.run(audit(host, int(port or 47600), a.code, a.token, a.out)))


if __name__ == "__main__":
    main()
