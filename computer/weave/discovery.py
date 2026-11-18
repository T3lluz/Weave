"""Being found on the local network. A phone shouts "weave?" to everyone on its Wi-Fi (a UDP
broadcast); every computer running weave answers with its name and port. Nothing is let in by
this: the phone still has to pair."""

import asyncio
import json

ASK = b"weave?"


class Discovery(asyncio.DatagramProtocol):
    def __init__(self, describe, allowed):
        self.describe = describe    # () -> dict: what to answer
        self.allowed = allowed      # (address) -> bool: the same rule as for connecting
        self.transport = None

    def connection_made(self, transport):
        self.transport = transport

    def datagram_received(self, data, addr):
        if data.strip() != ASK or not self.allowed(addr[0]):
            return
        try:
            self.transport.sendto(json.dumps({"weave": 1, **self.describe()}).encode(), addr)
        except OSError:
            pass


async def listen(port: int, describe, allowed):
    """Answers on UDP [port]; returns the transport to close, or None when the port can't be had."""
    loop = asyncio.get_running_loop()
    try:
        transport, _ = await loop.create_datagram_endpoint(
            lambda: Discovery(describe, allowed), local_addr=("0.0.0.0", port), allow_broadcast=True)
        return transport
    except OSError as err:
        print(f"weave: can't be found on the local network: {err}")
        return None
