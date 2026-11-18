import asyncio
import json
import socket
import unittest
from unittest import mock
from urllib.parse import parse_qs, urlsplit

from types import SimpleNamespace

from weave import discovery, pairing, server


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


class Line:
    """What a phone's connection looks like to a session: where it comes from, and what it was sent."""

    def __init__(self, peer="192.168.1.50"):
        self.peer, self.sent = peer, []
        self.transport = SimpleNamespace(get_write_buffer_size=lambda: 0)

    def get_extra_info(self, _):
        return (self.peer, 1)

    def write(self, b):
        self.sent.append(json.loads(b[5:]))

    def close(self):
        pass

    def kinds(self):
        return [m["t"] for m in self.sent]


class LettingIn(unittest.IsolatedAsyncioTestCase):
    """The daemon's own admit(), with a computer around it that is all stand-ins."""

    def setUp(self):
        async def nothing(*_a, **_k):
            return None

        async def no_monitors():
            return []

        self.asked = []

        async def ask(key, session, answer, until):
            self.asked.append(session.name)

        self.d = SimpleNamespace(
            devices=SimpleNamespace(data={}, save=lambda: None), dashboard=SimpleNamespace(secret="dash-secret", poke=lambda: None),
            codes=pairing.Codes(), pending={}, sessions=set(), host="box", cfg={"remote": {"sound": False, "notify": False}, "port": 47600},
            log=lambda *_: None, replace_sessions=lambda key: None, ensure_injector=lambda: object(), state=lambda: {},
            phone=SimpleNamespace(hints=[]), _ask=ask)
        for target, name, fake in ((server.hypr, "monitors", no_monitors), (server.hypr, "pointer_gain", nothing), (server.shell, "wake_info", nothing)):
            patch = mock.patch.object(target, name, fake)
            patch.start()
            self.addCleanup(patch.stop)

    def phone(self, peer="192.168.1.50"):
        line = Line(peer)
        return server.Session(self.d, None, line), line

    async def hello(self, session, token, ticket="", name="Pixel", **more):
        return await server.Daemon.admit(self.d, session, {"t": "hello", "token": token, "ticket": ticket, "name": name, **more})

    async def test_a_code_lets_one_phone_in_once_and_the_next_is_asked_about(self):
        code = self.d.codes.new()
        s, line = self.phone()
        self.assertTrue(await self.hello(s, "a" * 32, code))
        self.assertEqual(line.kinds(), ["welcome"])
        self.assertEqual(self.d.codes.used[code], "Pixel")
        self.assertEqual(self.asked, [], "nobody was asked: the code was the yes")
        # the same code from another phone: spent, so the owner is asked
        s2, line2 = self.phone("192.168.1.51")
        waiting = asyncio.create_task(self.hello(s2, "b" * 32, code, name="Other"))
        await asyncio.sleep(0.01)
        self.assertEqual(line2.kinds(), ["pending"])
        self.assertEqual(self.asked, ["Other"])
        server.Daemon.decide(self.d, False)
        self.assertFalse(await waiting)
        self.assertEqual(line2.kinds(), ["pending", "denied"])

    async def test_a_paired_phone_comes_back_without_a_code_and_a_wrong_one_does_no_harm(self):
        s, _ = self.phone()
        await self.hello(s, "a" * 32, self.d.codes.new())
        again, line = self.phone()
        self.assertTrue(await self.hello(again, "a" * 32, "not-a-code"))
        self.assertEqual(line.kinds(), ["welcome"])

    async def test_a_ticket_that_is_not_plain_text_is_just_a_wrong_ticket(self):
        self.d.codes.new()
        s, line = self.phone()
        waiting = asyncio.create_task(self.hello(s, "a" * 32, "kød\u2713\U0001f600"))
        await asyncio.sleep(0.01)
        self.assertEqual(line.kinds(), ["pending"], "asked about, not crashed on")
        server.Daemon.decide(self.d, False)
        await waiting

    async def test_one_phone_waits_per_address_and_only_a_few_in_all(self):
        waiting, lines = [], []
        for i in range(server.PENDING_MOST):
            s, line = self.phone(f"192.168.1.{60 + i}")
            waiting.append(asyncio.create_task(self.hello(s, chr(97 + i) * 32, name=f"Phone {i}")))
            lines.append(line)
        await asyncio.sleep(0.01)
        self.assertTrue(all(line.kinds() == ["pending"] for line in lines))
        # a second from an address that already has one waiting, and one more than the limit
        same, same_line = self.phone("192.168.1.60")
        self.assertFalse(await self.hello(same, "x" * 32))
        more, more_line = self.phone("192.168.1.99")
        self.assertFalse(await self.hello(more, "y" * 32))
        self.assertEqual(same_line.kinds(), ["denied"])
        self.assertEqual(more_line.kinds(), ["denied"])
        self.assertEqual(len(self.asked), server.PENDING_MOST, "no question was raised for the ones turned away")
        server.Daemon.decide(self.d, False)
        await asyncio.gather(*waiting)

    async def test_the_same_phone_trying_again_takes_over_its_own_wait(self):
        first, first_line = self.phone("192.168.1.50")
        a = asyncio.create_task(self.hello(first, "a" * 32, name="My Pixel"))
        await asyncio.sleep(0.01)
        # it tried again from the same address before anyone answered: not turned away, not asked about twice
        again, again_line = self.phone("192.168.1.50")
        b = asyncio.create_task(self.hello(again, "a" * 32, name="My Pixel"))
        await asyncio.sleep(0.01)
        self.assertEqual(again_line.kinds(), ["pending"])
        self.assertEqual(self.asked, ["My Pixel"], "one question")
        self.assertEqual(server.Daemon.decide(self.d, True), "let in My Pixel from 192.168.1.50")
        self.assertTrue(await b, "the newer line is the one let in")
        self.assertFalse(await a, "the older one just ends")
        self.assertEqual(first_line.kinds(), ["pending"], "and is told nothing more")
        self.assertEqual(again_line.kinds(), ["pending", "welcome"])
        self.assertEqual(self.d.pending, {})

    async def test_a_shortcut_from_a_phone_not_yet_let_in_raises_no_question(self):
        s, line = self.phone()
        self.assertFalse(await self.hello(s, "q" * 32, quiet=True))
        self.assertEqual(line.kinds(), ["pending"], "told to pair in the app first")
        self.assertEqual(self.asked, [])
        self.assertEqual(self.d.pending, {}, "and it holds no place in the queue: the app can ask right after")

    async def test_a_quiet_line_that_goes_on_past_a_shortcut_is_closed(self):
        s, line = self.phone()
        await self.hello(s, "a" * 32, self.d.codes.new(), quiet=True)
        pressed = []
        self.d.injector = SimpleNamespace(key=lambda *a: pressed.append(a))
        closed = []

        async def close():
            closed.append(True)
            s.closed = True

        s.close = close
        with mock.patch.object(server.catalog, "bound_code", mock.AsyncMock(return_value=True)):
            for _ in range(server.QUIET_STEPS + 5):
                await s.handle({"t": "key", "key": "a", "mods": []})
            await asyncio.sleep(0)
        self.assertEqual(len(pressed), server.QUIET_STEPS, "a shortcut's worth of keys and no more")
        self.assertTrue(closed)

    async def test_yes_with_two_waiting_needs_a_name(self):
        mine, mine_line = self.phone("192.168.1.50")
        theirs, theirs_line = self.phone("192.168.1.66")
        a = asyncio.create_task(self.hello(mine, "a" * 32, name="My Pixel"))
        b = asyncio.create_task(self.hello(theirs, "b" * 32, name="Stranger"))
        await asyncio.sleep(0.01)
        said = server.Daemon.decide(self.d, True)
        self.assertIn("more than one is waiting", said)
        self.assertIn("My Pixel (192.168.1.50)", said)
        self.assertEqual(mine_line.kinds(), ["pending"], "nobody was let in")
        self.assertEqual(server.Daemon.decide(self.d, True, "My Pixel"), "let in My Pixel from 192.168.1.50")
        self.assertTrue(await a)
        self.assertEqual(theirs_line.kinds(), ["pending"], "the other one still waits")
        self.assertEqual(server.Daemon.decide(self.d, False), "turned away Stranger from 192.168.1.66")
        self.assertFalse(await b)
        self.assertEqual(server.Daemon.decide(self.d, True), "nothing is waiting")

    async def test_a_quiet_line_runs_a_shortcuts_steps_and_nothing_else(self):
        s, line = self.phone()
        await self.hello(s, "a" * 32, self.d.codes.new(), quiet=True)
        self.assertTrue(s.quiet)
        pressed, closed = [], []
        self.d.injector = SimpleNamespace(key=lambda *a: pressed.append(a), move=lambda *a: pressed.append(("moved", a)))

        async def close():
            closed.append(True)
            s.closed = True

        s.close = close
        with mock.patch.object(server.catalog, "bound_code", mock.AsyncMock(return_value=True)), \
                mock.patch.object(server.shell, "clip_get", mock.AsyncMock(return_value="the owner's clipboard")):
            await s.handle({"t": "key", "key": "a", "mods": []})
            self.assertFalse(closed, "a shortcut's step is what a quiet line is for")
            # anything else (here: reading the clipboard) is not a shortcut: the line is closed
            await s.handle({"t": "clip_get"})
            await asyncio.sleep(0)
            await s.handle({"t": "m", "dx": 5, "dy": 5})
            await s.handle({"t": "video", "on": True})
        self.assertEqual(pressed, [("a", (), None)], "the key went through; nothing after it did")
        self.assertTrue(closed)
        self.assertNotIn("clip", line.kinds())
        self.assertFalse(s.video_wanted)


if __name__ == "__main__":
    unittest.main()
