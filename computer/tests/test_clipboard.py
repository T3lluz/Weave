"""Keeping the two clipboards the same, with the computer's clipboard faked.

    python3 -m unittest discover tests
"""

import asyncio
import json
import unittest
from types import SimpleNamespace
from unittest import mock

from weave import server


class Line:
    def __init__(self):
        self.sent = []
        self.transport = SimpleNamespace(get_write_buffer_size=lambda: 0)

    def get_extra_info(self, _):
        return ("192.168.1.50", 1)

    def write(self, b):
        self.sent.append(json.loads(b[5:]))

    def close(self):
        pass

    def clips(self):
        return [m["text"] for m in self.sent if m["t"] == "clip" and m.get("sync")]


class InStep(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.board = "old"

        async def clip_get(timeout=5):
            return self.board

        async def clip_set(text):
            self.board = text

        for name, fake in (("clip_get", clip_get), ("clip_set", clip_set)):
            patch = mock.patch.object(server.shell, name, fake)
            patch.start()
            self.addCleanup(patch.stop)
        patch = mock.patch.object(server, "CLIP_EVERY", 0.01)
        patch.start()
        self.addCleanup(patch.stop)
        self.d = SimpleNamespace(clip_seen={}, sessions=set(), injector=None)

    def phone(self, key="pixel"):
        line = Line()
        s = server.Session(self.d, None, line)
        s.key, s.authorized = key, True
        return s, line

    async def step(self):
        await asyncio.sleep(0.05)

    async def leave(self, s):
        s.clip_task.cancel()
        s.closed = True

    async def test_only_what_is_copied_after_a_new_phone_starts_goes_over(self):
        s, line = self.phone()
        await s.handle({"t": "clip_sync", "on": True})
        await self.step()
        self.assertEqual(line.clips(), [], "what was there stays put")
        self.board = "copied on the computer"
        await self.step()
        self.assertEqual(line.clips(), ["copied on the computer"])
        await self.step()
        self.assertEqual(line.clips(), ["copied on the computer"], "once")
        await self.leave(s)

    async def test_what_the_phone_copied_is_set_and_not_sent_back(self):
        s, line = self.phone()
        await s.handle({"t": "clip_sync", "on": True})
        await s.handle({"t": "clip_set", "text": "from the phone", "quiet": True})
        await self.step()
        self.assertEqual(self.board, "from the phone")
        self.assertEqual(line.sent, [], "no clip back, and no word about it")
        await self.leave(s)

    async def test_a_line_break_lost_on_the_way_back_is_not_a_new_copy(self):
        s, line = self.phone()
        await s.handle({"t": "clip_sync", "on": True})
        await s.handle({"t": "clip_set", "text": "a line\n", "quiet": True})
        self.board = "a line"   # as wl-paste -n reads it back
        await self.step()
        self.assertEqual(line.clips(), [])
        await self.leave(s)

    async def test_a_phone_that_comes_back_gets_what_was_copied_while_it_was_away(self):
        s, _ = self.phone()
        await s.handle({"t": "clip_sync", "on": True})
        await self.step()
        await self.leave(s)
        self.board = "copied while the phone was away"
        again, line = self.phone()
        await again.handle({"t": "clip_sync", "on": True})
        await self.step()
        self.assertEqual(line.clips(), ["copied while the phone was away"])
        await self.leave(again)

    async def test_what_the_phone_copied_while_away_wins_over_what_the_computer_did(self):
        s, _ = self.phone()
        await s.handle({"t": "clip_sync", "on": True})
        await self.step()
        await self.leave(s)
        self.board = "copied on the computer meanwhile"
        again, line = self.phone()
        await again.handle({"t": "clip_sync", "on": True})
        await again.handle({"t": "clip_set", "text": "copied on the phone meanwhile", "quiet": True})
        await self.step()
        self.assertEqual(self.board, "copied on the phone meanwhile")
        self.assertEqual(line.clips(), [])
        await self.leave(again)

    async def test_another_phone_is_not_sent_what_the_first_one_missed(self):
        s, _ = self.phone("pixel")
        await s.handle({"t": "clip_sync", "on": True})
        await self.step()
        await self.leave(s)
        self.board = "copied since"
        other, line = self.phone("tablet")
        await other.handle({"t": "clip_sync", "on": True})
        await self.step()
        self.assertEqual(line.clips(), [], "new here: what is there stays put")
        await self.leave(other)

    async def test_only_so_many_phones_are_remembered(self):
        for n in range(server.CLIP_PHONES + 3):
            s, _ = self.phone(f"phone-{n}")
            s._clip_is("text")
        self.assertEqual(len(self.d.clip_seen), server.CLIP_PHONES)
        self.assertNotIn("phone-0", self.d.clip_seen)

    async def test_turning_it_off_stops_it(self):
        s, line = self.phone()
        await s.handle({"t": "clip_sync", "on": True})
        await self.step()
        await s.handle({"t": "clip_sync", "on": False})
        self.board = "copied after it was turned off"
        await self.step()
        self.assertEqual(line.clips(), [])


if __name__ == "__main__":
    unittest.main()
