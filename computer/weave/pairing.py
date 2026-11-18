"""Letting a phone in by a code this computer shows: a QR code to scan, or the link behind it.

`weave pair` makes a code that works once, for ten minutes. The link carries it with the
computer's name, port and every address it can be reached at; the phone tries them in order.
The code is the only secret in it, and it is gone the moment a phone uses it.
"""

import ipaddress
import secrets
import socket
import time
from urllib.parse import urlencode

from . import qrcodegen

# the page the link opens when a camera scans it: it hands the invitation to the app
PAIR_URL = "https://t3lluz.com/weave/pair"
CODE_TTL = 600


class Codes:
    """Pairing codes that are out: each works once, until its time is up."""

    def __init__(self, ttl: float = CODE_TTL):
        self.ttl = ttl
        self.out: dict[str, float] = {}
        # who used which, for `weave pair` to say so
        self.used: dict[str, str] = {}

    def new(self) -> str:
        self._prune()
        code = secrets.token_urlsafe(12)
        self.out[code] = time.monotonic() + self.ttl
        return code

    def take(self, code: str, by: str = "") -> bool:
        """Whether [code] is one that is out; it is spent either way."""
        self._prune()
        hit = next((c for c in self.out if secrets.compare_digest(c, code)), None)
        if hit is None:
            return False
        del self.out[hit]
        self.used[hit] = by
        return True

    def left(self, code: str) -> float:
        return max(0.0, self.out.get(code, 0.0) - time.monotonic())

    def _prune(self):
        now = time.monotonic()
        for code in [c for c, until in self.out.items() if until <= now]:
            del self.out[code]


def addresses(tailnet: list[str] | None = None) -> list[str]:
    """Where a phone can reach this computer, best first: its addresses on the local network,
    then its tailnet ones, then its name (which a tailnet resolves)."""
    found: list[str] = []
    try:
        infos = socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)
        found += [i[4][0] for i in infos]
    except OSError:
        pass
    # the address the default route leaves from, which the lookup above can miss
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("192.0.2.1", 9))   # nothing is sent: it only picks the route
            found.insert(0, s.getsockname()[0])
    except OSError:
        pass
    local = []
    for a in found:
        try:
            ip = ipaddress.ip_address(a)
        except ValueError:
            continue
        if ip.is_private and not ip.is_loopback and not ip.is_link_local and a not in local:
            local.append(a)
    out = local + [a for a in (tailnet or []) if a not in local]
    name = socket.gethostname()
    return out + ([name] if name and name not in out else [])


def invite(name: str, addrs: list[str], port: int, code: str) -> str:
    """The link a phone opens to pair. Everything rides in the fragment, which a browser never
    sends to the server it loads the page from."""
    return PAIR_URL + "#" + urlencode({"n": name, "a": ",".join(addrs), "p": port, "c": code})


def qr_text(data: str) -> str:
    """[data] as a QR code a terminal can show: two rows of the code per line of text, dark on
    light whatever the terminal's own colours, with the quiet border a camera needs."""
    qr = qrcodegen.QrCode.encode_text(data, qrcodegen.QrCode.Ecc.LOW)
    n, edge = qr.get_size(), 2
    dark = lambda x, y: 0 <= x < n and 0 <= y < n and qr.get_module(x, y)   # noqa: E731
    lines = []
    for y in range(-edge, n + edge, 2):
        row = ""
        for x in range(-edge, n + edge):
            top, bottom = dark(x, y), dark(x, y + 1)
            row += "█" if top and bottom else "▀" if top else "▄" if bottom else " "
        lines.append("\x1b[30;107m" + row + "\x1b[0m")
    return "\n".join(lines)
