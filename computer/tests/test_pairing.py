import asyncio
import json
import socket
import unittest
from unittest import mock
from urllib.parse import parse_qs, urlsplit

from weave import discovery, pairing


class Codes(unittest.TestCase):
    def test_a_code_works_once(self):
        codes = pairing.Codes()
        code = codes.new()
        self.assertFalse(codes.take("not-" + code))
        self.assertTrue(codes.take(code, "Pixel"))
        self.assertFalse(codes.take(code), "spent")
        self.assertEqual(codes.used[code], "Pixel")

    def test_a_code_runs_out(self):
        codes = pairing.Codes(ttl=60)
        with mock.patch.object(pairing.time, "monotonic", return_value=1000.0):
            code = codes.new()
            self.assertEqual(codes.left(code), 60)
        with mock.patch.object(pairing.time, "monotonic", return_value=1061.0):
            self.assertEqual(codes.left(code), 0)
            self.assertFalse(codes.take(code))

    def test_two_codes_out_at_once_both_work(self):
        codes = pairing.Codes()
        a, b = codes.new(), codes.new()
        self.assertNotEqual(a, b)
        self.assertTrue(codes.take(b))
        self.assertTrue(codes.take(a))


class Invitation(unittest.TestCase):
    def test_the_link_carries_everything_in_its_fragment(self):
        link = pairing.invite("my box", ["192.168.1.5", "100.64.0.7", "box"], 47600, "s3cr-et_")
        parts = urlsplit(link)
        self.assertEqual(parts.scheme + "://" + parts.netloc + parts.path, pairing.PAIR_URL)
        self.assertEqual(parts.query, "", "nothing the server would be sent")
        got = parse_qs(parts.fragment)
        self.assertEqual(got, {"n": ["my box"], "a": ["192.168.1.5,100.64.0.7,box"], "p": ["47600"], "c": ["s3cr-et_"]})

    def test_addresses_are_the_local_ones_then_the_tailnet_then_the_name(self):
        with mock.patch.object(pairing.socket, "getaddrinfo", return_value=[(0, 0, 0, "", ("192.168.1.5", 0)), (0, 0, 0, "", ("127.0.0.1", 0))]), \
                mock.patch.object(pairing.socket, "gethostname", return_value="box"), \
                mock.patch.object(pairing.socket, "socket", side_effect=OSError):
            self.assertEqual(pairing.addresses(["100.64.0.7", "box"]), ["192.168.1.5", "100.64.0.7", "box"])

    def test_only_real_cards_count_when_the_system_names_them(self):
        # a bridge for containers has a private address too, and no phone can reach it
        with mock.patch.object(pairing.socket, "getaddrinfo", return_value=[(0, 0, 0, "", ("172.17.0.1", 0))]), \
                mock.patch.object(pairing.socket, "gethostname", return_value="box"), \
                mock.patch.object(pairing.socket, "socket", side_effect=OSError):
            self.assertEqual(pairing.addresses(["100.64.0.7"], cards=["10.0.0.4"]), ["10.0.0.4", "100.64.0.7", "box"])

    def test_the_code_on_screen_has_a_quiet_border_and_square_modules(self):
        text = pairing.qr_text(pairing.invite("box", ["192.168.1.5"], 47600, "abcDEF123456"))
        lines = [line.replace("\x1b[30;107m", "").replace("\x1b[0m", "") for line in text.splitlines()]
        self.assertEqual(len({len(line) for line in lines}), 1, "every row as wide as the others")
        # two rows of the code per line of text
        self.assertAlmostEqual(len(lines) * 2, len(lines[0]), delta=1)
        self.assertEqual(lines[0].strip(), "", "the top of the quiet border")


class Found(unittest.IsolatedAsyncioTestCase):
    async def test_a_computer_answers_a_phone_that_asks_and_nobody_else(self):
        allowed = {"127.0.0.1"}
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
        transport = await discovery.listen(port, lambda: {"name": "box", "port": 47600, "busy": False}, lambda a: a in allowed)
        self.addAsyncCleanup(asyncio.sleep, 0)
        self.addCleanup(transport.close)
        loop = asyncio.get_running_loop()
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as phone:
            phone.setblocking(False)
            await loop.sock_sendto(phone, b"hello?", ("127.0.0.1", port))
            await loop.sock_sendto(phone, discovery.ASK, ("127.0.0.1", port))
            data = await asyncio.wait_for(loop.sock_recv(phone, 2048), 2)
            self.assertEqual(json.loads(data), {"weave": 1, "name": "box", "port": 47600, "busy": False})
            allowed.clear()
            await loop.sock_sendto(phone, discovery.ASK, ("127.0.0.1", port))
            with self.assertRaises(asyncio.TimeoutError):
                await asyncio.wait_for(loop.sock_recv(phone, 2048), 0.2)


if __name__ == "__main__":
    unittest.main()
