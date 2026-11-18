"""The line between the `weave` command and the daemon, on this computer only.

On Linux it is a socket file only this user can open. Windows has no such thing in asyncio, so
there it is a port on the loopback address, and each request carries a token kept in a file in
the user's own folder: another user of the same PC can reach the port, but not read the token.
"""

import asyncio
import json
import os
import secrets
import socket
import sys

from .config import SOCKET, STATE_DIR

WINDOWS = sys.platform == "win32"
PORT = 47601
TOKEN_FILE = STATE_DIR / "local.token"


async def serve(command):
    """Starts answering; [command] takes a request and returns the reply. Returns the server."""
    token = None
    if WINDOWS:
        STATE_DIR.mkdir(parents=True, exist_ok=True)
        token = secrets.token_hex(24)
        TOKEN_FILE.write_text(token)

    async def one(reader, writer):
        try:
            req = json.loads(await asyncio.wait_for(reader.readline(), 5))
            if token is not None and not secrets.compare_digest(str(req.get("token", "")), token):
                reply = {"ok": False, "msg": "not from this user"}
            else:
                reply = await command(req)
        except Exception as err:
            reply = {"ok": False, "msg": str(err)}
        try:
            writer.write(json.dumps(reply).encode() + b"\n")
            await writer.drain()
            writer.close()
        except OSError:
            pass

    if WINDOWS:
        try:
            return await asyncio.start_server(one, "127.0.0.1", PORT)
        except OSError as err:
            raise SystemExit(f"weave: port {PORT} on this PC is taken (another weave, or another program): {err}")
    SOCKET.parent.mkdir(parents=True, exist_ok=True)
    try:
        SOCKET.unlink()
    except FileNotFoundError:
        pass
    server = await asyncio.start_unix_server(one, path=str(SOCKET))
    os.chmod(SOCKET, 0o600)
    return server


def ask(request: dict, timeout: float = 30) -> dict:
    """Sends the daemon a request from the command line and waits for its answer."""
    try:
        if WINDOWS:
            s = socket.create_connection(("127.0.0.1", PORT), timeout=timeout)
            request = {**request, "token": TOKEN_FILE.read_text().strip()}
        else:
            s = socket.socket(socket.AF_UNIX)
            s.settimeout(timeout)
            s.connect(str(SOCKET))
    except OSError:
        return {"ok": False, "msg": "the daemon is not running" + ("" if WINDOWS else ": systemctl --user start weave")}
    with s:
        s.settimeout(timeout)
        s.sendall(json.dumps(request).encode() + b"\n")
        data = b""
        try:
            while not data.endswith(b"\n"):
                chunk = s.recv(65536)
                if not chunk:
                    break
                data += chunk
        except OSError:
            pass
    try:
        return json.loads(data)
    except ValueError:
        return {"ok": False, "msg": "the daemon gave no answer"}
