"""weave: the command. It talks to the daemon over a line only this computer has (local.py)."""

import argparse
import json
import os
import time
import subprocess
import sys

from . import __version__
from . import pairing
from .local import ask

USAGE = """\
weave pair                        show a code for a phone to scan, and let that phone in
weave status                      what is connected and running
weave allow [NAME] | deny         answer a phone that is waiting to pair
weave forget [NAME]               unpair a phone, or all of them
weave clip                        send this clipboard to the phone (the remote must be open there)
weave audio [on|off|toggle]       the phone's sound on this computer's output (Linux)
weave phone [open|close|toggle]   the phone's screen in a window (Linux)
weave send FILE...                put files in the phone's Downloads (Linux)
weave roam [on|off|auto|manual|now|back|watch|early|balanced|late]
                                  sound that follows the earbuds to the phone (a lab, Linux):
                                  now hands them to the phone, back takes them back
weave daemon                      run the daemon in the foreground (the service does this)
"""


def pair() -> int:
    """Shows the code and waits until a phone has used it, or its time is up."""
    r = ask({"cmd": "pair"})
    if not r.get("ok"):
        print(r.get("msg"))
        return 1
    if sys.platform == "win32":
        os.system("")   # turns on the console's colour codes, which the code is drawn with
    print(pairing.qr_text(r["link"]))
    print()
    print("Scan this with Weave on your phone (Scan a code), or with its camera.")
    where = "your network or your tailnet" if r.get("lan") else "your tailnet"
    print(f"The phone has to be on {where}. The code works once, for {int(r['ttl'] // 60)} minutes.")
    print()
    print(f"No camera? In Weave, tap By address and type:  {r['addrs'][0]}")
    print("Then answer the question that shows up here, or run: weave allow")
    print()
    try:
        while True:
            time.sleep(1)
            st = ask({"cmd": "pair_status", "code": r["code"]}, timeout=5)
            if st.get("by") is not None:
                print(f"Paired with {st['by'] or 'a phone'}.")
                return 0
            if not st.get("ok") or st.get("left", 0) <= 0:
                print("The code ran out. Run weave pair again for a new one.")
                return 1
    except KeyboardInterrupt:
        print()
        return 130


def say(title: str, reply: dict, popup: bool):
    text = reply.get("msg", "")
    print(text)
    if popup:
        subprocess.run(["notify-send", "-a", "Weave", "-i", "phone", "-t", "3000",
                        "-h", "string:x-canonical-private-synchronous:weave", title, text], check=False)
    return 0 if reply.get("ok") else 1


def status() -> int:
    r = ask({"cmd": "status", "probe": True})
    if not r.get("ok"):
        print(r.get("msg"))
        return 1
    p = r["phone"]
    print(f"weave {r['version']} on {r['host']}, port {r['port']}")
    if sys.platform != "win32":   # the phone on this computer (its window, its sound) is Linux's for now
        print(f"phone      {p['model'] + ' (' + p['serial'] + ')' if p['serial'] else 'not reachable over adb'}")
        print(f"  window   {'open' if p['mirror'] else 'closed'}")
        audio = "off"
        if p["audio"]:
            audio = f"on ({p['audio_source']})" if p["audio_running"] else "on, waiting for the phone"
        print(f"  audio    {audio}")
    if r["sessions"]:
        for s in r["sessions"]:
            print(f"remote     {s['name']} from {s['peer']}" + (f", watching {s['monitor']}" if s["video"] else ""))
    else:
        print("remote     not open on a phone")
    print(f"paired     {', '.join(r['paired']) or 'none'}")
    dash = r.get("dashboard") or {}
    if dash.get("url"):
        print(f"dashboard  {dash['url']} ({'announced' if dash.get('online') else 'not answering'})")
    if r["pending"]:
        print(f"waiting    {', '.join(r['pending'])}  (weave allow)")
    for name in r.get("missing", []):
        print(f"missing    {name}")
    return 0


STATES = {
    "off": "off", "idle": "no earbuds are this computer's output", "armed": "the earbuds are here",
    "handing": "handing them to the phone", "waiting": "they left; waiting to hear who has them",
    "bridged": "the phone has them and plays this computer's sound", "returning": "taking them back",
}


def roam_line(r: dict) -> str:
    """What `weave roam watch` prints each time: the link as the daemon reads it."""
    unit = " dBm" if r.get("dbm") else ""
    parts = [r["state"]]
    if r["state"] == "armed":
        parts.append(f"signal {r.get('raw')}{unit} (smoothed {r.get('signal')}, hands over at {r.get('limit'):g} held {r.get('hold'):g} s)")
        parts.append(f"sending {r.get('rate')}/s (usual {r.get('usual')})")
    if r.get("near") is not None:
        parts.append(f"phone heard at {r['near']} dBm")
    m = r.get("meter")
    if m:
        parts.append(f"phone plays at {m.get('peak')} dB, its volume {m.get('vol')}/{m.get('max')}" + (f" ({m.get('db')} dB)" if m.get("db") is not None else ""))
    return "  ".join(parts)


def roam(args: list[str], popup: bool) -> int:
    do = (args or ["status"])[0]
    if do == "watch":
        try:
            while True:
                r = ask({"cmd": "roam", "do": "status"}, timeout=5)
                if not r.get("ok"):
                    print(r.get("msg"))
                    return 1
                print(time.strftime("%H:%M:%S"), roam_line(r["roam"]), flush=True)
                time.sleep(0.5)
        except KeyboardInterrupt:
            print()
            return 130
    if do not in ("status", "on", "off", "auto", "manual", "now", "back", "early", "balanced", "late"):
        print("weave roam [on|off|auto|manual|now|back|watch|early|balanced|late]")
        return 2
    reply = ask({"cmd": "roam", "do": do, "probe": do == "status"}, timeout=40)
    r = reply.get("roam")
    if not r:
        return say("Earbuds", reply, popup)
    if not reply.get("ok"):
        return say("Earbuds", reply, popup)
    if do in ("now", "back"):
        return say("Earbuds", {"ok": True, "msg": "Handing the earbuds to the phone" if do == "now" else "Taking the earbuds back"}, popup)
    device = r.get("device") or {}
    how = f"by itself, {r['sens']}" if r.get("mode") != "manual" else "only when asked: weave roam now, weave roam back"
    print(f"follows    {'on' if r['on'] else 'off'} ({how})")
    print(f"earbuds    {device.get('name') or 'none'}: {STATES.get(r['state'], r['state'])}" + (f" ({r['why']})" if r.get("why") and r["state"] != "off" else ""))
    if r["state"] == "armed":
        unit = " dBm" if r.get("dbm") else ""
        print(f"signal     {r.get('signal')}{unit}, handed over at {r.get('limit'):g}{unit} held {r.get('hold'):g} s")
    m = r.get("meter")
    if m:
        print(f"playing    at {m.get('peak')} dB on the phone, its volume at {m.get('vol')} of {m.get('max')}" + (f" ({m.get('db')} dB)" if m.get("db") is not None else "")
              + (f", brought up by {m.get('lift')} dB" if m.get("lift") is not None else ""))
    print(f"the phone  {'ready to take them' if r['auto'] else 'cannot be told to take them: ' + (r.get('autoWhy') or 'the earbuds are not here')}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(prog="weave", usage=USAGE, add_help=False)
    parser.add_argument("command", nargs="?", default="help")
    parser.add_argument("args", nargs="*")
    parser.add_argument("--notify", action="store_true", help="also show the answer as a notification")
    opts = parser.parse_args()
    cmd, args = opts.command, opts.args

    if cmd == "daemon":
        if sys.stdout is None:
            # started without a console (Windows, at logon): what it says goes to a file instead
            from .config import STATE_DIR
            STATE_DIR.mkdir(parents=True, exist_ok=True)
            sys.stdout = sys.stderr = open(STATE_DIR / "daemon.log", "a", buffering=1, encoding="utf-8")
        from .server import main as daemon
        daemon()
        return 0
    if cmd == "pair":
        return pair()
    if cmd in ("phone", "mirror"):
        word = (args or ["open"])[0]
        on = {"open": True, "on": True, "close": False, "off": False}.get(word, "toggle")
        return say("Phone", ask({"cmd": "mirror", "on": on}), opts.notify)
    if cmd == "audio":
        word = (args or ["toggle"])[0]
        on = {"on": True, "off": False}.get(word, "toggle")
        reply = ask({"cmd": "audio", "on": on})
        if reply.get("msg") in ("on", "off"):
            reply["msg"] = "Phone audio plays here now" if reply["msg"] == "on" else "Phone audio is back on the phone"
        return say("Phone audio", reply, opts.notify)
    if cmd == "send":
        if not args:
            print("weave send FILE...")
            return 2
        return say("Phone", ask({"cmd": "send", "paths": [os.path.abspath(a) for a in args]}, timeout=600), opts.notify)
    if cmd == "clip":
        return say("Phone", ask({"cmd": "clip"}), opts.notify)
    if cmd == "roam":
        return roam(args, opts.notify)
    if cmd in ("allow", "deny"):
        return say("Weave", ask({"cmd": cmd, "name": " ".join(args) or None}), opts.notify)
    if cmd == "forget":
        return say("Weave", ask({"cmd": "forget", "name": args[0] if args else None}), opts.notify)
    if cmd == "status":
        return status()
    if cmd in ("version", "--version", "-v"):
        print(__version__)
        return 0
    print(USAGE, end="")
    return 0 if cmd in ("help", "--help", "-h") else 2


if __name__ == "__main__":
    sys.exit(main())
