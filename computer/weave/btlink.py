"""The Bluetooth link to a pair of earbuds, read straight from the controller: how strong it is,
how much is being sent over it, and why it ended.

A raw HCI socket. The kernel lets any user send the few read-only commands used here (signal
strength, the list of connections) and hear the events that answer them, so none of this needs
root. Linux only.
"""

import array
import asyncio
import fcntl
import socket
import struct

HCIGETDEVINFO = 0x800448D3
HCIGETCONNLIST = 0x800448D4
READ_RSSI = 0x1405
EVENT_PACKET = 4
GONE, ANSWER = 0x05, 0x0E      # Disconnection Complete, Command Complete
ACL = 1
# why a link ended: its timer ran out with nothing heard from the other end, which is what
# walking out of range looks like. Anything else was somebody's decision.
TIMEOUT = 0x08
CONTROLLERS = 4   # hci0 to hci3 are looked at


def parse_connections(buf: bytes) -> dict[str, int]:
    """The addresses a controller has an audio-capable (ACL) link to, each with its handle, out of
    what HCIGETCONNLIST wrote."""
    out = {}
    if len(buf) < 4:
        return out
    _, count = struct.unpack_from("<HH", buf, 0)
    for i in range(count):
        at = 4 + 16 * i
        if at + 16 > len(buf):
            break
        handle, addr, kind = struct.unpack_from("<H6sB", buf, at)
        if kind == ACL:
            out[":".join(f"{b:02X}" for b in addr[::-1])] = handle
    return out


def parse_sent(buf: bytes) -> tuple[int, int] | None:
    """Packets and bytes a controller has been handed to send since it came up, out of what
    HCIGETDEVINFO wrote."""
    if len(buf) < 92:
        return None
    stats = struct.unpack_from("<10I", buf, 52)
    return stats[4], stats[9]


def parse_event(pkt: bytes) -> tuple | None:
    """("answer", opcode, what it returned) or ("gone", handle, reason), or None for anything else."""
    if len(pkt) < 3 or pkt[0] != EVENT_PACKET:
        return None
    if pkt[1] == ANSWER and len(pkt) >= 6:
        return "answer", struct.unpack_from("<H", pkt, 4)[0], pkt[6:]
    if pkt[1] == GONE and len(pkt) >= 7 and pkt[3] == 0:
        return "gone", struct.unpack_from("<H", pkt, 4)[0], pkt[6]
    return None


def parse_rssi(answer: bytes | None) -> int | None:
    """The strength out of Read RSSI's answer: status, handle, then the value as a signed byte."""
    if not answer or len(answer) < 4 or answer[0] != 0:
        return None
    return struct.unpack_from("<b", answer, 3)[0]


class Controller:
    """One Bluetooth controller (hci0, usually). [on_gone] hears each link that ends: the controller
    it was on, its handle, and why."""

    def __init__(self, dev: int = 0, on_gone=None):
        self.dev = dev
        self.on_gone = on_gone or (lambda controller, handle, reason: None)
        self.sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_RAW, socket.BTPROTO_HCI)
        try:
            self.sock.bind((dev,))
            # events only, and of those the two this reads: a command's answer, and a link ending
            self.sock.setsockopt(0, 2, struct.pack("<IIIHH", 1 << EVENT_PACKET, (1 << GONE) | (1 << ANSWER), 0, 0, 0))
            self.sock.setblocking(False)
        except OSError:
            self.sock.close()
            raise
        self.waiting: dict[int, asyncio.Future] = {}
        self.lock = asyncio.Lock()
        self.closed = False
        self.loop = asyncio.get_running_loop()
        self.loop.add_reader(self.sock.fileno(), self._readable)

    def _readable(self):
        try:
            pkt = self.sock.recv(260)
        except BlockingIOError:
            return
        except OSError:
            # the controller went away (Bluetooth turned off, the adapter reset)
            self.close()
            return
        event = parse_event(pkt)
        if not event:
            return
        if event[0] == "answer":
            waiting = self.waiting.pop(event[1], None)
            if waiting and not waiting.done():
                waiting.set_result(event[2])
        else:
            try:
                self.on_gone(self, event[1], event[2])
            except Exception as err:
                print(f"weave: a Bluetooth link ended and that couldn't be followed: {err!r}")

    async def _ask(self, opcode: int, params: bytes, timeout: float = 0.4) -> bytes | None:
        if self.closed:
            return None
        async with self.lock:
            answer = self.loop.create_future()
            self.waiting[opcode] = answer
            try:
                self.sock.send(struct.pack("<BHB", 1, opcode, len(params)) + params)
                return await asyncio.wait_for(answer, timeout)
            except asyncio.TimeoutError:
                return None
            except OSError:
                self.close()
                return None
            finally:
                self.waiting.pop(opcode, None)

    def connections(self) -> dict[str, int]:
        if self.closed:
            return {}
        buf = array.array("B", struct.pack("<HH", self.dev, 16) + bytes(16 * 16))
        try:
            fcntl.ioctl(self.sock.fileno(), HCIGETCONNLIST, buf, True)
        except OSError:
            self.close()
            return {}
        return parse_connections(buf.tobytes())

    def sent(self) -> tuple[int, int] | None:
        if self.closed:
            return None
        buf = array.array("B", struct.pack("<H", self.dev) + bytes(90))
        try:
            fcntl.ioctl(self.sock.fileno(), HCIGETDEVINFO, buf, True)
        except OSError:
            self.close()
            return None
        return parse_sent(buf.tobytes())

    async def rssi(self, handle: int) -> int | None:
        """How strong the other end is heard. On most controllers this is how far outside the range
        the two radios aim for (0 inside it, negative when too weak); a few say dBm."""
        return parse_rssi(await self._ask(READ_RSSI, struct.pack("<H", handle)))

    def close(self):
        if self.closed:
            return
        self.closed = True
        try:
            self.loop.remove_reader(self.sock.fileno())
        except (OSError, ValueError):
            pass
        self.sock.close()


def open_all(on_gone=None) -> list[Controller]:
    """Every controller this computer has that can be opened."""
    found = []
    for dev in range(CONTROLLERS):
        try:
            found.append(Controller(dev, on_gone))
        except OSError:
            continue
    return found
