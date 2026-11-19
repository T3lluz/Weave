"""Sound that follows the earbuds: when the link counts as weak, what the phone's beacon lines
say, what the controller's answers mean, and how the phone's two kinds of line are let in.

    python3 -m unittest discover tests
"""

import asyncio
import json
import struct
import unittest
from types import SimpleNamespace
from unittest import mock

from weave import btlink, roam, server


class WhenTheLinkIsWeak(unittest.TestCase):
    def test_a_strong_link_is_left_alone(self):
        judge = roam.Judge(-8, 1.2)
        for i in range(40):
            self.assertIsNone(judge.feed(i * 0.25, 0))

    def test_weak_for_long_enough_hands_over_and_not_before(self):
        judge = roam.Judge(-8, 1.2)
        said = [judge.feed(i * 0.25, -14) for i in range(12)]
        first = said.index("weak")
        self.assertGreaterEqual(first * 0.25, 1.2, "the hold is waited out")
        self.assertLess(first * 0.25, 2.0, "and not much more")

    def test_a_dip_that_recovers_starts_the_wait_over(self):
        judge = roam.Judge(-8, 1.2)
        now = 0.0
        for rssi in [-12, -12, -12] + [0] * 12 + [-12, -12, -12]:
            self.assertIsNone(judge.feed(now, rssi))
            now += 0.25

    def test_a_link_that_falls_off_a_cliff_is_not_waited_on(self):
        judge = roam.Judge(-8, 1.2)
        judge.feed(0.0, 0)
        self.assertIsNone(judge.feed(0.25, -20))
        self.assertEqual(judge.feed(0.5, -20), "weak", "twice far under the limit: at once")

    def test_sound_that_stops_getting_out_on_a_weak_link_is_a_stall(self):
        judge = roam.Judge(-8, 1.2)
        sent, now = 0, 0.0
        for _ in range(40):                     # ten seconds of sound flowing, the link a little weak
            sent += 12
            self.assertIsNone(judge.feed(now, -4, sent))
            now += 0.25
        said = None
        for _ in range(8):                      # and then almost nothing gets out
            sent += 1
            said = said or judge.feed(now, -4, sent)
            now += 0.25
        self.assertEqual(said, "stalled")

    def test_sound_that_stops_on_a_strong_link_was_paused_not_stalled(self):
        judge = roam.Judge(-8, 1.2)
        sent, now = 0, 0.0
        for _ in range(40):
            sent += 12
            judge.feed(now, 0, sent)
            now += 0.25
        for _ in range(12):
            self.assertIsNone(judge.feed(now, 0, sent))
            now += 0.25

    def test_told_nothing_is_playing_it_forgets_what_was_usual(self):
        judge = roam.Judge(-8, 1.2)
        sent, now = 0, 0.0
        for _ in range(40):
            sent += 12
            judge.feed(now, -4, sent)
            now += 0.25
        judge.quiet()
        for _ in range(12):
            self.assertIsNone(judge.feed(now, -4, sent))
            now += 0.25


class WhereTheLimitIs(unittest.TestCase):
    def test_each_pick_has_its_limit_for_each_way_of_counting(self):
        self.assertEqual(roam.limit_for("early", False, None), -4)
        self.assertEqual(roam.limit_for("balanced", False, None), -8)
        self.assertEqual(roam.limit_for("late", True, None), -78)
        self.assertEqual(roam.limit_for("nonsense", False, None), -8, "an unknown pick is Balanced")

    def test_what_was_learnt_only_ever_moves_it_earlier(self):
        self.assertEqual(roam.limit_for("balanced", False, -5.0), -5.0)
        self.assertEqual(roam.limit_for("balanced", False, -12.0), -8, "later than the pick is not taken")

    def test_a_limit_set_in_the_config_is_the_limit(self):
        self.assertEqual(roam.limit_for("early", False, -3.0, fixed=-11), -11)

    def test_an_outputs_name_says_whose_it_is(self):
        self.assertEqual(roam.sink_address("bluez_output.AA_BB_CC_DD_EE_01.1"), "AA:BB:CC:DD:EE:01")
        self.assertEqual(roam.sink_address("bluez_sink.aa_bb_cc_dd_ee_01.a2dp_sink"), "AA:BB:CC:DD:EE:01")
        self.assertIsNone(roam.sink_address("alsa_output.pci-0000_75_00.6.analog-stereo"))
        self.assertIsNone(roam.sink_address(""))


ADDED = ("/: org.freedesktop.DBus.ObjectManager.InterfacesAdded (objectpath '/org/bluez/hci0/dev_5A_11_22_33_44_55', "
         "{'org.bluez.Device1': {'Address': <'5A:11:22:33:44:55'>, 'RSSI': <int16 -61>, "
         "'ManufacturerData': <{uint16 65535: <[byte 0x57, 0x56, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x09]>}>}})")
STRONGER = ("/org/bluez/hci0/dev_5A_11_22_33_44_55: org.freedesktop.DBus.Properties.PropertiesChanged "
            "('org.bluez.Device1', {'RSSI': <int16 -52>}, @as [])")
SOMEONE_ELSE = ("/org/bluez/hci0/dev_7E_9A_E0_92_AA_70: org.freedesktop.DBus.Properties.PropertiesChanged "
                "('org.bluez.Device1', {'RSSI': <int16 -40>, 'ManufacturerData': <{uint16 76: <[byte 0x16, 0x08, 0x00]>}>}, @as [])")
REMOVED = "/: org.freedesktop.DBus.ObjectManager.InterfacesRemoved (objectpath '/org/bluez/hci0/dev_5A_11_22_33_44_55', ['org.bluez.Device1'])"
WANT = b"WV" + bytes([1, 2, 3, 4, 5, 6, 7, 9])


class ThePhonesBeacon(unittest.TestCase):
    def test_its_first_line_names_it_and_says_how_loud(self):
        self.assertEqual(roam.parse_beacon(ADDED, WANT), ("/org/bluez/hci0/dev_5A_11_22_33_44_55", True, -61))

    def test_another_number_is_not_ours(self):
        self.assertEqual(roam.parse_beacon(ADDED, b"WV" + bytes(8))[1], False)

    def test_a_line_about_strength_alone_says_nothing_of_whose_it_is(self):
        self.assertEqual(roam.parse_beacon(STRONGER, WANT), ("/org/bluez/hci0/dev_5A_11_22_33_44_55", None, -52))

    def test_someone_elses_beacon_is_not_ours_however_loud(self):
        self.assertEqual(roam.parse_beacon(SOMEONE_ELSE, WANT)[1], False)

    def test_lines_about_nothing_near_are_passed_over(self):
        self.assertIsNone(roam.parse_beacon("/org/bluez/hci0: org.freedesktop.DBus.Properties.PropertiesChanged ('org.bluez.Adapter1', {'Discovering': <true>}, @as [])", WANT))

    def test_the_phone_is_heard_for_as_long_as_bluez_lists_it(self):
        near = roam.Nearness()
        near.want = WANT
        self.assertIsNone(near.near(0.0))
        self.assertFalse(near.lost, "never heard is not lost")
        near.line(SOMEONE_ELSE, 0.5)
        self.assertIsNone(near.near(0.5))
        near.line(ADDED, 1.0)
        self.assertEqual(near.near(1.0), -61)
        near.line(STRONGER, 2.0)
        self.assertGreater(near.near(2.0), -61)
        # BlueZ says nothing while the strength stays the same: silence is not the phone going away
        self.assertIsNotNone(near.near(60.0))
        self.assertFalse(near.lost)
        near.line(REMOVED, 61.0)
        self.assertIsNone(near.near(61.0))
        self.assertTrue(near.lost, "heard before, and off BlueZ's list now")


class WhatTheControllerSays(unittest.TestCase):
    def test_the_links_it_has_with_their_handles(self):
        buf = struct.pack("<HH", 0, 2)
        buf += struct.pack("<H6sBBHI", 50, bytes.fromhex("AABBCCDDEE01")[::-1], btlink.ACL, 1, 1, 0)
        buf += struct.pack("<H6sBBHI", 51, bytes.fromhex("112233445566")[::-1], 0x80, 1, 1, 0)   # not a link sound goes over
        self.assertEqual(btlink.parse_connections(buf), {"AA:BB:CC:DD:EE:01": 50})
        self.assertEqual(btlink.parse_connections(b""), {})

    def test_how_much_it_has_sent(self):
        buf = bytes(52) + struct.pack("<10I", 0, 0, 0, 0, 4711, 0, 0, 0, 0, 180_000)
        self.assertEqual(btlink.parse_sent(buf), (4711, 180_000))
        self.assertIsNone(btlink.parse_sent(bytes(10)))

    def test_an_answer_and_a_link_ending(self):
        answer = bytes([4, 0x0E, 7, 1]) + struct.pack("<H", btlink.READ_RSSI) + bytes([0]) + struct.pack("<Hb", 50, -9)
        kind, opcode, data = btlink.parse_event(answer)
        self.assertEqual((kind, opcode), ("answer", btlink.READ_RSSI))
        self.assertEqual(btlink.parse_rssi(data), -9)
        gone = bytes([4, 0x05, 4, 0]) + struct.pack("<H", 50) + bytes([btlink.TIMEOUT])
        self.assertEqual(btlink.parse_event(gone), ("gone", 50, btlink.TIMEOUT))
        self.assertIsNone(btlink.parse_event(bytes([4, 0x13, 1, 0])))

    def test_an_answer_that_failed_gives_no_strength(self):
        self.assertIsNone(btlink.parse_rssi(bytes([2, 50, 0, 0])))
        self.assertIsNone(btlink.parse_rssi(None))


class Line:
    def __init__(self, peer="100.64.0.9"):
        self.peer, self.sent = peer, []
        self.transport = SimpleNamespace(get_write_buffer_size=lambda: 0)

    def get_extra_info(self, _):
        return (self.peer, 1)

    def write(self, b):
        if b[0] == server.JSON:
            self.sent.append(json.loads(b[5:]))

    def close(self):
        pass

    def kinds(self):
        return [m["t"] for m in self.sent]


class FakeRoam:
    """The earbuds' side of the daemon, as far as letting a line in goes."""

    def __init__(self):
        self.lines = set()
        self.handled = []

    def attach(self, s):
        self.lines.add(s)

    def detach(self, s):
        self.lines.discard(s)

    def status(self):
        return {"on": True, "state": "armed"}

    async def handle(self, s, msg):
        self.handled.append(msg)


class ThePhonesOwnLines(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        async def nothing(*_a, **_k):
            return None

        async def no_monitors():
            return []

        self.replaced = []
        self.roam = FakeRoam()
        self.d = SimpleNamespace(
            devices=SimpleNamespace(data={}, save=lambda: None), dashboard=SimpleNamespace(secret="dash", poke=lambda: None, id="box-id"),
            codes=server.pairing.Codes(), pending={}, sessions=set(), host="box", cfg={"remote": {"sound": False, "notify": False}, "port": 47600},
            log=lambda *_: None, replace_sessions=self.replaced.append, ensure_injector=lambda: object(), state=lambda: {},
            phone=SimpleNamespace(hints=[]), roam=self.roam, injector=None)
        for target, name, fake in ((server.hypr, "monitors", no_monitors), (server.hypr, "pointer_gain", nothing), (server.shell, "wake_info", nothing)):
            patch = mock.patch.object(target, name, fake)
            patch.start()
            self.addCleanup(patch.stop)

    def phone(self):
        line = Line()
        return server.Session(self.d, None, line), line

    async def hello(self, session, token="a" * 32, **more):
        return await server.Daemon.admit(self.d, session, {"t": "hello", "token": token, "name": "Pixel", **more})

    async def pair(self):
        s, _ = self.phone()
        await self.hello(s, ticket=self.d.codes.new())
        self.replaced.clear()
        return s

    async def test_the_player_comes_in_beside_the_remote_and_takes_no_ones_place(self):
        remote = await self.pair()
        player, line = self.phone()
        self.assertTrue(await self.hello(player, roam="play", quiet=True))
        self.assertEqual(self.replaced, [], "the remote's line is left alone")
        self.assertIn(player, self.roam.lines)
        self.assertNotIn(player, self.d.sessions, "and it is no session of the remote's")
        self.assertIn(remote, self.d.sessions)
        welcome = line.sent[-1]
        self.assertEqual((welcome["t"], welcome["caps"]), ("welcome", ["roam"]))
        self.assertEqual(welcome["roam"]["state"], "armed")

    async def test_a_new_player_takes_the_old_ones_place_and_the_screens_line_does_not(self):
        await self.pair()
        first, _ = self.phone()
        await self.hello(first, roam="play")
        watch, _ = self.phone()
        await self.hello(watch, roam="watch")
        self.assertFalse(first.replaced, "the Labs screen's line ends no player")
        second, _ = self.phone()
        await self.hello(second, roam="play")
        self.assertTrue(first.replaced)
        self.assertFalse(watch.replaced)

    async def test_what_a_player_sends_goes_to_the_earbuds_side_and_nowhere_else(self):
        await self.pair()
        player, _ = self.phone()
        await self.hello(player, roam="play")
        pressed = []
        self.d.injector = SimpleNamespace(move=lambda *a: pressed.append(a), key=lambda *a: pressed.append(a))
        await player.handle({"t": "m", "dx": 5, "dy": 5})
        await player.handle({"t": "roam", "have": "AA:BB:CC:DD:EE:01"})
        self.assertEqual(pressed, [], "a player's line moves no mouse")
        self.assertEqual([m["t"] for m in self.roam.handled], ["m", "roam"])

    async def test_a_phone_never_let_in_gets_no_player_and_raises_no_question(self):
        player, line = self.phone()
        self.assertFalse(await self.hello(player, token="z" * 32, roam="play"))
        self.assertEqual(line.kinds(), ["pending"])
        self.assertEqual(self.d.pending, {})
        self.assertEqual(self.roam.lines, set())

    async def test_a_player_closing_lets_go_of_nothing_the_remote_holds(self):
        await self.pair()
        player, _ = self.phone()
        await self.hello(player, roam="play")
        released = []
        self.d.injector = SimpleNamespace(release_all=lambda **k: released.append(k))
        await player.close()
        self.assertEqual(released, [])
        self.assertEqual(self.roam.lines, set())

    async def test_a_computer_that_cant_follow_earbuds_treats_the_line_as_any_other(self):
        self.d.roam = None
        s, line = self.phone()
        await self.hello(s, ticket=self.d.codes.new(), roam="play")
        self.assertFalse(s.roam)
        self.assertNotIn("roam", line.sent[-1]["caps"])


class PhoneLine:
    """A line as the earbuds' side sees it: its kind, and what it was sent."""

    def __init__(self, kind):
        self.key, self.peer, self.roam_kind = "k", "100.64.0.9", kind
        self.roam_watch = self.want_audio = False
        self.sent = []
        self.transport = SimpleNamespace(get_write_buffer_size=lambda: 0)

    def send_json(self, msg):
        self.sent.append(msg)

    def send(self, *frame):
        self.sent.append(frame)


class OneLineToTheEarbudsSide(unittest.IsolatedAsyncioTestCase):
    """Roam's own answers to a line, with no Bluetooth, sound server or phone behind it."""

    def setUp(self):
        self.store = {}
        patch = mock.patch.object(roam, "Store", lambda name, default: SimpleNamespace(data=dict(default), save=lambda: None))
        patch.start()
        self.addCleanup(patch.stop)
        self.pressed = []
        d = SimpleNamespace(cfg={"roam": {}, "video": {"audio_kbps": 160}}, phone=SimpleNamespace(serial=None, model="Phone", adb_path=None, hints=[]),
                            host="box", log=lambda *_: None, ensure_injector=lambda: SimpleNamespace(key=self.pressed.append))
        self.r = roam.Roam(d)

    def line(self, kind="play"):
        s = PhoneLine(kind)
        self.r.attach(s)
        return s

    async def test_turning_it_on_from_the_phone_is_kept_and_answered(self):
        s = self.line("watch")
        await self.r.handle(s, {"t": "roam", "set": {"on": True, "sens": "late", "mode": "manual"}})
        self.assertTrue(self.r.on)
        self.assertFalse(self.r.by_itself)
        last = s.sent[-1]
        self.assertEqual((last["on"], last["sens"], last["mode"], last["state"]), (True, "late", "manual", "idle"))

    async def test_off_says_off_whatever_state_it_was_in(self):
        self.assertEqual(self.r.status()["state"], "off")

    async def test_earbuds_this_computer_never_played_through_start_nothing(self):
        self.r.set(on=True)
        s = self.line()
        await self.r.handle(s, {"t": "roam", "have": "11:22:33:44:55:66", "force": True})
        self.assertEqual(self.r.state, "idle")

    async def test_earbuds_that_connect_to_the_phone_some_other_day_are_the_phones_business(self):
        self.r.set(on=True)
        self.r.store.data["seen"]["AA:BB:CC:DD:EE:01"] = "AirPods Pro"
        s = self.line()
        with mock.patch.object(self.r, "_silent", mock.AsyncMock(return_value=True)) as silent:
            await self.r.handle(s, {"t": "roam", "have": "AA:BB:CC:DD:EE:01"})
        self.assertEqual(self.r.state, "idle", "they didn't just leave this computer, and nobody asked")
        silent.assert_not_called()

    async def test_asked_for_by_hand_on_the_phone_the_bridge_starts(self):
        self.r.set(on=True)
        self.r.store.data["seen"]["AA:BB:CC:DD:EE:01"] = "AirPods Pro"
        self.r.d.phone.hold_audio = mock.AsyncMock()
        s = self.line()
        with mock.patch.object(self.r, "_silent", mock.AsyncMock(return_value=True)), \
                mock.patch.object(self.r, "_mute_here", mock.AsyncMock()), \
                mock.patch.object(self.r.near, "start", mock.AsyncMock()) as listening:
            await self.r.handle(s, {"t": "roam", "have": "AA:BB:CC:DD:EE:01", "force": True})
        self.assertEqual(self.r.state, "bridged")
        self.r.d.phone.hold_audio.assert_awaited_with(True)
        listening.assert_awaited()
        self.assertFalse(self.r.been_away, "moved by hand: the phone has to go away before it can be back")
        self.assertEqual(len(self.r.status()["beacon"]), 16)

    async def test_media_keys_from_the_notification_are_pressed_and_nothing_else_is(self):
        s = self.line()
        await self.r.handle(s, {"t": "media", "cmd": "play"})
        await self.r.handle(s, {"t": "media", "cmd": "volup"})
        await self.r.handle(s, {"t": "key", "key": "a"})
        self.assertEqual(self.pressed, ["play"])

    async def test_the_sound_goes_to_players_that_asked_and_skips_one_that_fell_behind(self):
        keen, behind, deaf = self.line(), self.line(), self.line("watch")
        keen.want_audio = behind.want_audio = True
        behind.transport = SimpleNamespace(get_write_buffer_size=lambda: roam.BEHIND + 1)
        self.r.sound = cap = SimpleNamespace(audio_config=b"cfg")
        self.r._on_audio(cap, 0, 40, b"frame")
        self.assertEqual(len(keen.sent), 1)
        self.assertEqual((behind.sent, deaf.sent), ([], []))
        self.r._on_audio(cap, roam.CONFIG, 0, b"cfg")
        self.assertEqual(len(behind.sent), 1, "the decoder's setup goes out whatever is queued")


if __name__ == "__main__":
    unittest.main()
