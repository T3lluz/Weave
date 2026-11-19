"""The session's screen switching, with recorders and Hyprland faked.

    python3 -m unittest discover tests
"""

import asyncio
import json
import struct
import unittest
from types import SimpleNamespace
from unittest import mock

from weave import server
from weave.capture import CONFIG, KEY
from weave.config import DEFAULTS

MONS = [
    {"name": "A", "label": "Left", "x": 0, "y": 0, "w": 100, "h": 50, "pw": 200, "ph": 100, "focused": True},
    {"name": "B", "label": "Middle", "x": 100, "y": 0, "w": 100, "h": 50, "pw": 200, "ph": 100, "focused": False},
    {"name": "C", "label": "Right", "x": 200, "y": 0, "w": 100, "h": 50, "pw": 200, "ph": 100, "focused": False},
]


class FakeCapture:
    """A recorder that sends frames only when the test says so."""

    def __init__(self, mon, preset, limit, quality, fps, kbps, on_video, on_audio, on_exit, cursor=True):
        self.mon, self.monitor, self.preset = mon, mon["name"], preset
        self.cursor = cursor
        self.on_video, self.on_audio, self.on_exit = on_video, on_audio, on_exit
        self.video_config = self.audio_config = None
        self.gop = []
        self.proc = SimpleNamespace(returncode=None)
        self.stopped = False
        self.flowing = asyncio.Event()
        self.got_frame = False
        self.errors = []
        Fake.made.append(self)

    async def start(self):
        if Fake.slow_start:
            await Fake.slow_start.wait()

    async def stop(self):
        # as the real one: a stopped recorder's exit is never reported
        self.stopped = True
        self.proc.returncode = 0
        self.on_video = self.on_audio = self.on_exit = lambda *_: None

    def exit(self, code=1):
        """Ends as gpu-screen-recorder does: the reader's finally sets flowing, then on_exit runs."""
        self.flowing.set()
        self.proc.returncode = code
        self.on_exit(self, code)

    def setup_then_die(self):
        """Writes its header and decoder setup, as a muxer does at open, then dies before any picture."""
        self.video_config = b"setup-" + self.monitor.encode()
        self.on_video(self, CONFIG, 0, self.video_config)
        self.exit(1)

    def frame(self, key=True, size=10):
        if self.stopped:
            return
        if key and not self.video_config:
            self.video_config = b"setup-" + self.monitor.encode()
            self.on_video(self, CONFIG, 0, self.video_config)
        self.flowing.set()
        self.got_frame = True
        data = (b"K" if key else b"p") * size
        if key:
            self.gop = [(KEY, 0, data)]
        elif self.gop:
            self.gop.append((0, 0, data))
        self.on_video(self, KEY if key else 0, 0, data)


class Fake:
    made: list = []
    sounds: list = []
    cursor = (10, 10)
    slow_start = None


class FakeSound:
    """ffmpeg sending the laptop's sound alone."""

    def __init__(self, kbps, on_audio, on_exit):
        self.on_audio, self.on_exit = on_audio, on_exit
        self.audio_config = None
        self.got_sound = False
        self.errors = []
        self.stopped = False
        Fake.sounds.append(self)

    async def start(self):
        pass

    async def stop(self):
        self.stopped = True
        self.on_audio = self.on_exit = lambda *_: None

    def exit(self, code=1):
        self.stopped = True   # gone, whoever ended it
        self.on_exit(self, code)


class Writer:
    """Collects what the session sends; the backlog is whatever the test sets."""

    def __init__(self):
        self.out = []
        self.backlog = 0
        self.transport = SimpleNamespace(get_write_buffer_size=lambda: self.backlog)

    def get_extra_info(self, _):
        return ("100.64.0.9", 1)

    def write(self, b):
        kind, _ = struct.unpack(">BI", b[:5])
        self.out.append((kind, b[5:]))

    def close(self):
        pass

    def messages(self, t="video"):
        return [json.loads(p) for k, p in self.out if k == server.JSON and json.loads(p).get("t") == t]

    def frames(self):
        return [p[9:] for k, p in self.out if k == server.VIDEO]

    def flags(self):
        return [p[0] for k, p in self.out if k == server.VIDEO]


def daemon():
    cfg = json.loads(json.dumps(DEFAULTS))
    return SimpleNamespace(cfg=cfg, dashboard=SimpleNamespace(poke=lambda: None), log=lambda *_: None,
                           sessions=set(), injector=None)


async def settle():
    for _ in range(10):
        await asyncio.sleep(0)


async def cursor():
    return Fake.cursor


async def monitors():
    return [dict(m) for m in MONS]


def live(cap):
    return [c for c in Fake.made if c.monitor == cap and not c.stopped]


class Harness(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        Fake.made = []
        Fake.sounds = []
        Fake.cursor = (10, 10)
        Fake.slow_start = None
        self.patches = [mock.patch.object(server, "Capture", FakeCapture),
                        mock.patch.object(server, "SoundCapture", FakeSound),
                        mock.patch.object(server.hypr, "cursor", cursor),
                        mock.patch.object(server.hypr, "monitors", monitors)]
        for p in self.patches:
            p.start()
        self.w = Writer()
        self.s = server.Session(daemon(), None, self.w)

    def tearDown(self):
        for p in self.patches:
            p.stop()

    async def start(self):
        await self.s.set_video(True, "auto", "mid")
        live("A")[0].frame()
        await settle()
        for name in "BC":
            live(name)[0].frame()


class Switching(Harness):
    async def test_the_other_monitors_wait_warm_and_a_hop_needs_no_new_recorder(self):
        await self.start()
        self.assertEqual(self.s.capture.monitor, "A")
        self.assertEqual(sorted(self.s.warm), ["B", "C"])
        made = len(Fake.made)
        Fake.cursor = (150, 10)
        await self.s._follow_once()
        Fake.cursor = (250, 10)
        await self.s._follow_once()
        await settle()
        self.assertEqual(self.s.capture.monitor, "C")
        self.assertEqual(sorted(self.s.warm), ["A", "B"])
        self.assertEqual(len(Fake.made), made, "a hop between warm monitors starts nothing")
        switches = [m.get("switch") for m in self.w.messages()]
        self.assertEqual(switches[-2:], ["A", "B"])
        # the new monitor's buffered frames went out right after its setup
        self.assertEqual(self.w.frames()[-1], b"K" * 10)

    async def test_a_phone_that_draws_its_own_pointer_gets_pictures_without_one(self):
        await self.start()
        self.assertTrue(all(c.cursor for c in Fake.made), "the pointer is in the picture by default")
        self.s = server.Session(daemon(), None, Writer())
        self.s.local_cursor = True
        Fake.made = []
        await self.start()
        self.assertTrue(Fake.made)
        self.assertFalse(any(c.cursor for c in Fake.made), "the shown and the warm recorders all leave it out")

    async def test_back_before_the_other_was_ready_keeps_it_warm(self):
        await self.s.set_video(True, "auto", "mid")
        live("A")[0].frame()
        await settle()
        Fake.cursor = (150, 10)          # B has not sent a frame yet: it becomes the one awaited
        await self.s._follow_once()
        self.assertEqual(self.s.pending.monitor, "B")
        Fake.cursor = (10, 10)
        await self.s._follow_once()
        self.assertIsNone(self.s.pending)
        self.assertIn("B", self.s.warm)
        self.assertEqual(self.s.capture.monitor, "A")

    async def test_picture_off_stops_everything_and_nothing_warms_up_after(self):
        await self.start()
        await self.s.set_video(False)
        await self.s._rewarm()
        await settle()
        self.assertEqual([c.monitor for c in Fake.made if not c.stopped], [])
        self.assertIsNone(self.s.capture)
        self.assertEqual(self.s.warm, {})

    async def test_the_warm_setting_with_no_picture_starts_nothing(self):
        self.s.mons = await monitors()
        await self.s._rewarm()
        self.assertEqual(Fake.made, [])

    async def test_the_keyframe_that_takes_over_goes_out_over_a_full_buffer(self):
        await self.start()
        self.s.follow = False
        await self.s.set_video(True, "B", "low")          # a new quality: a recorder that must start
        self.w.backlog = 10_000_000
        pending = self.s.pending
        pending.frame(size=20)
        self.assertIs(self.s.capture, pending)
        self.assertEqual(self.w.frames()[-1], b"K" * 20)
        self.assertEqual(self.w.messages()[-1]["switch"], "A")

    async def test_a_slow_link_steps_down_once_then_waits(self):
        await self.start()
        cap = self.s.capture
        self.w.backlog = 10_000_000
        for _ in range(3):
            self.s.dropping = False
            cap.frame(key=False)
        await settle()
        self.assertEqual(self.s.quality, "low")
        # the backlog the old stream left does not count against the lighter one straight away
        for _ in range(3):
            self.s.dropping = False
            self.s._fell_behind()
        self.assertEqual(self.s.drops.maxlen and len(self.s.drops), 0)


    async def test_a_buffered_switch_shows_only_its_newest_frame(self):
        await self.start()
        b = live("B")[0]
        b.frame(key=False)
        b.frame(key=False)
        before = len(self.w.out)
        Fake.cursor = (150, 10)
        await self.s._follow_once()
        sent = [p[0] for k, p in self.w.out[before:] if k == server.VIDEO]
        # setup, then the keyframe and one P-frame to catch up on, then the newest shown
        self.assertEqual(sent, [CONFIG, KEY | server.CATCHUP, server.CATCHUP, 0])

    async def test_a_busy_monitor_waits_for_its_next_keyframe_instead_of_a_long_replay(self):
        await self.start()
        c = live("C")[0]
        for _ in range(40):        # most of a second of heavy frames since its keyframe
            c.frame(key=False, size=30)
        Fake.cursor = (250, 10)
        await self.s._follow_once()
        self.assertIs(self.s.pending, c)
        self.assertEqual(self.s.capture.monitor, "A")
        c.frame()
        self.assertIs(self.s.capture, c)

    async def test_a_recorder_that_dies_on_start_is_not_tried_again_at_once(self):
        await self.s.set_video(True, "auto", "mid")
        live("A")[0].frame()
        await settle()
        dead = live("B")[0]
        del self.s.warm["B"]
        dead.exit(1)                 # never sent a frame, as a real recorder ends
        made = len(Fake.made)
        Fake.cursor = (150, 10)
        for _ in range(5):
            await self.s._follow_once()
        self.assertEqual(len(Fake.made), made)
        self.assertEqual(self.s.capture.monitor, "A")

    async def test_closing_while_recorders_start_leaves_none_running(self):
        Fake.slow_start = asyncio.Event()
        task = asyncio.create_task(self.s.set_video(True, "auto", "mid"))
        await settle()
        self.s.closed = True
        await self.s.set_video(False)
        Fake.slow_start.set()
        await task
        await settle()
        self.assertTrue(all(c.stopped for c in Fake.made))
        self.assertEqual(self.s.warm, {})


    async def test_a_monitor_dropped_mid_hop_stays_warm(self):
        await self.start()
        b = live("B")[0]
        for _ in range(40):          # B is busy: going there waits for its next keyframe
            b.frame(key=False, size=30)
        Fake.cursor = (150, 10)
        await self.s._follow_once()
        self.assertIs(self.s.pending, b)
        Fake.cursor = (250, 10)      # on to C, which shows at once
        await self.s._follow_once()
        self.assertEqual(self.s.capture.monitor, "C")
        self.assertIs(self.s.warm.get("B"), b)
        self.assertFalse(b.stopped)


    async def test_a_dead_picture_comes_back_after_its_wait(self):
        await self.start()
        shown = self.s.capture
        shown.exit(1)                # the recorder on screen dies
        self.assertIsNone(self.s.capture)
        self.assertIn("A", self.s.failed)
        made = len(Fake.made)
        await self.s._follow_once()
        self.assertEqual(len(Fake.made), made, "not tried again inside its wait")
        self.s.failed["A"] = (0.0, self.s.failed["A"][1])   # the wait is over
        await self.s._follow_once()
        retry = self.s.pending
        self.assertEqual(retry.monitor, "A")
        retry.frame()
        self.assertIs(self.s.capture, retry)
        self.assertNotIn("A", self.s.failed, "recording fine again clears the wait")

    async def test_a_replaced_session_closes_without_a_line_in_lately(self):
        logged, released = [], []
        d = self.s.daemon
        d.log = logged.append
        d.injector = SimpleNamespace(release_all=lambda: released.append(1))
        self.s.authorized = True
        self.s.replaced = True
        d.sessions.add(self.s)
        await self.s.close()
        self.assertEqual(logged, [])
        self.assertEqual(released, [], "replace_sessions let go already; the new session's keys stay")


    async def test_a_shortcut_line_that_closes_lets_its_text_finish(self):
        calls = []
        d = self.s.daemon
        d.log = lambda *_: None
        d.injector = SimpleNamespace(release_all=lambda **k: calls.append(k))
        self.s.authorized = self.s.quiet = True
        d.sessions.add(self.s)
        await self.s.close()
        self.assertEqual(calls, [{"finish": True}], "150 ms after its last step is too soon to stop typing")

    async def test_a_shortcut_line_may_only_type_so_much(self):
        calls, typed = [], []
        d = self.s.daemon
        d.log = lambda *_: None
        d.injector = SimpleNamespace(release_all=lambda **k: calls.append(k), text=typed.append)
        self.s.authorized = self.s.quiet = True
        d.sessions.add(self.s)
        await self.s.handle({"t": "text", "s": "x" * 500})
        self.assertEqual(len(typed), 1)
        await self.s.handle({"t": "text", "s": "x" * server.QUIET_TEXT})
        await settle()
        self.assertEqual(len(typed), 1, "past the limit nothing more is typed")
        self.assertTrue(self.s.closed)
        self.assertEqual(calls, [{}], "and what was on its way is cut off")

    async def test_opening_the_remote_while_a_shortcut_types_lets_it_finish(self):
        calls = []

        class Old:
            def __init__(self, quiet):
                self.key, self.replaced, self.quiet, self.cut = "k", False, quiet, False

            async def close(self):
                pass

        d = SimpleNamespace(sessions={Old(True)}, injector=SimpleNamespace(release_all=lambda **k: calls.append(k)))
        server.Daemon.replace_sessions(d, "k")
        d.sessions = {Old(True), Old(False)}
        server.Daemon.replace_sessions(d, "k")
        await settle()
        self.assertEqual(calls, [{"finish": True}, {}], "a real session among them is cut off as before")

    async def test_a_shortcut_line_cut_off_for_asking_too_much_stops_typing(self):
        calls = []
        d = self.s.daemon
        d.log = lambda *_: None
        d.injector = SimpleNamespace(release_all=lambda **k: calls.append(k))
        self.s.authorized = self.s.quiet = True
        d.sessions.add(self.s)
        await self.s.handle({"t": "video", "on": True})
        await settle()
        self.assertTrue(self.s.closed)
        self.assertEqual(calls, [{}])

    async def test_fingers_from_the_phone_go_on_the_touchpad_turned_when_the_desktop_scrolls_the_other_way(self):
        frames = []
        pad = SimpleNamespace(touched=False, frame=lambda f, turned: frames.append((f, turned)), lift=lambda: frames.append("lift"))
        d = self.s.daemon
        d.injector = SimpleNamespace(pad=pad)

        async def natural():
            return False

        d.touchpad_natural = natural
        await self.s.handle({"t": "touch", "nat": True, "f": [[1, 20.5, 40], [2, 40, 44], "junk", [3, 1]]})
        self.assertEqual(frames, [([(1, 20.5, 40.0), (2, 40.0, 44.0)], True)])
        await self.s.handle({"t": "touch", "nat": False, "f": [[1, 1e9, "x"]]})
        self.assertEqual(frames[-1], "lift", "a frame that makes no sense lifts the fingers")
        await self.s.handle({"t": "touch", "f": []})
        self.assertEqual(frames[-1], ([], False))

    async def test_fingers_sent_to_a_computer_with_no_touchpad_do_nothing(self):
        self.s.daemon.injector = SimpleNamespace()
        await self.s.handle({"t": "touch", "f": [[1, 2, 3], [2, 3, 4]]})

    async def test_a_recorder_that_only_wrote_its_setup_still_backs_off(self):
        await self.s.set_video(True, "auto", "mid")
        live("A")[0].frame()
        await settle()
        dead = live("B")[0]          # warm, but it never sent a picture
        del self.s.warm["B"]
        dead.setup_then_die()
        self.assertIn("B", self.s.failed, "a header is not a picture")

    async def test_an_explicit_request_clears_the_wait(self):
        await self.start()
        self.s.failed["B"] = (10**12, 40.0)
        await self.s.handle({"t": "video", "on": True, "monitor": "B"})
        self.assertEqual(self.s.failed, {})

    async def test_a_phone_back_on_a_new_connection_lets_go_of_what_the_old_one_held(self):
        released = []

        class Old:
            def __init__(self, key):
                self.key, self.replaced = key, False

            async def close(self):
                pass

        old, other = Old("k"), Old("x")
        d = SimpleNamespace(sessions={old, other}, injector=SimpleNamespace(release_all=lambda: released.append(1)))
        server.Daemon.replace_sessions(d, "k")
        await settle()
        self.assertEqual(released, [1])
        self.assertTrue(old.replaced)
        self.assertEqual(d.sessions, {other})


    async def test_the_phone_hears_once_that_a_monitor_cant_be_recorded(self):
        await self.s.set_video(True, "auto", "mid")
        live("A")[0].frame()
        await settle()
        for _ in range(3):
            self.s.failed.clear()
            Fake.cursor = (150, 10)
            self.s.warm.pop("B", None)
            await self.s._follow_once()
            self.s.pending.exit(1)
            Fake.cursor = (10, 10)
            await self.s._follow_once()
        self.assertEqual(len(self.w.messages("toast")), 1)


    async def test_a_connection_given_up_on_presses_nothing_more(self):
        pressed = []
        self.s.daemon.injector = SimpleNamespace(button=lambda b, down: pressed.append((b, down)))
        self.s.replaced = True
        await self.s.handle({"t": "b", "btn": "left", "down": True})
        self.assertEqual(pressed, [])

    async def test_a_picture_that_was_showing_is_tried_again_soon(self):
        await self.start()
        self.s.capture.exit(1)
        until, wait = self.s.failed["A"]
        self.assertLessEqual(wait, 1.0)


    async def test_a_monitor_unplugged_while_shown_hands_over_to_one_still_there(self):
        await self.start()
        self.s.capture.exit(1)       # A is unplugged: its recorder dies and it leaves the layout
        self.s.failed.clear()
        gone = [m for m in MONS if m["name"] != "A"]

        async def without_a():
            return [dict(m) for m in gone]

        Fake.cursor = (150, 10)
        with mock.patch.object(server.hypr, "monitors", without_a):
            await self.s._follow_once()
        self.assertEqual(self.s.monitor["name"], "B")
        self.assertEqual([m["name"] for m in self.s.mons], ["B", "C"])


    async def test_a_recorder_that_cannot_even_launch_is_not_tried_on_every_turn(self):
        await self.start()
        self.s.capture.exit(1)
        self.s.failed.clear()

        async def broken(_self):
            raise OSError("gpu-screen-recorder: not found")

        with mock.patch.object(FakeCapture, "start", broken):
            await self.s._follow_once()
            made = len(Fake.made)
            for _ in range(5):
                await self.s._follow_once()
        self.assertEqual(len(Fake.made), made)
        self.assertIn("A", self.s.failed)


class SoundAlone(Harness):
    """With the picture off, the laptop's sound still reaches the phone, from ffmpeg."""

    def running(self):
        return [c for c in Fake.sounds if not c.stopped]

    async def test_sound_without_the_picture_runs_alone_and_stops_when_the_picture_returns(self):
        await self.s.handle({"t": "audio", "on": True})
        self.assertEqual(len(self.running()), 1)
        await self.s.set_video(True, "auto", "mid")
        await settle()
        self.assertEqual(self.running(), [], "the picture's recorder carries the sound")
        await self.s.set_video(False)
        self.assertEqual(len(self.running()), 1)
        await self.s.handle({"t": "audio", "on": False})
        await settle()
        self.assertEqual(self.running(), [])

    async def test_the_sound_alone_never_starts_while_a_picture_is_shown(self):
        await self.start()
        await self.s.handle({"t": "audio", "on": True})
        self.assertEqual(Fake.sounds, [])

    async def test_sound_that_wont_start_waits_longer_each_time_and_says_so_once(self):
        await self.s.handle({"t": "audio", "on": True})
        self.running()[0].exit(1)
        self.assertEqual(self.running(), [])
        self.s._sync_sound()
        self.assertEqual(self.running(), [], "not tried again before the wait is over")
        self.s.sound_retry_at = 0
        self.s._sync_sound()
        self.running()[0].exit(1)
        self.assertEqual(len(self.w.messages("toast")), 1)
        self.assertGreater(self.s.sound_wait, server.RETRY_START * 2 - 0.01)

    async def test_closing_stops_the_sound(self):
        await self.s.handle({"t": "audio", "on": True})
        await self.s.close()
        await settle()
        self.assertEqual(self.running(), [])
        self.s._sync_sound()
        self.assertEqual(self.running(), [])

    async def test_a_picture_that_wont_start_never_brings_the_sound_alone_in_beside_it(self):
        # the recorder can't even launch: the picture is still wanted, so ffmpeg stays off, or
        # the two would play at once when the recorder comes back
        Fake.slow_start = None
        await self.s.handle({"t": "audio", "on": True})
        self.assertEqual(len(self.running()), 1)
        await self.s.set_video(True, "auto", "mid")
        await settle()
        live("A")[0].exit(1)
        await settle()
        self.s._sync_sound()
        self.assertEqual(self.running(), [])

    async def test_a_quality_step_never_brings_back_a_picture_the_phone_hid(self):
        await self.start()
        await self.s.set_video(False)
        await self.s.set_video(True, "auto", "low", by_phone=False)
        await settle()
        self.assertIsNone(self.s.capture)
        self.assertIsNone(self.s.pending)

    async def test_only_the_shown_recorder_or_the_sound_alone_reach_the_phone(self):
        await self.s.handle({"t": "audio", "on": True})
        sound = self.running()[0]
        stray = FakeSound(160, self.s._on_audio, self.s._on_sound_exit)
        self.s._on_audio(stray, 0, 0, b"stray")
        self.s._on_audio(sound, 0, 0, b"ours")
        sent = [p[9:] for k, p in self.w.out if k == server.AUDIO]
        self.assertEqual(sent, [b"ours"])


class Pointer(Harness):
    async def test_a_warp_lands_inside_the_monitor_nearest_the_point(self):
        moved = []

        async def move(x, y):
            moved.append((x, y))
        with mock.patch.object(server.hypr, "move_cursor", move):
            await self.s.handle({"t": "warp", "x": 150, "y": 25})
            await self.s.handle({"t": "warp", "x": 400, "y": -30})
        self.assertEqual(moved, [(150, 25), (299, 0)])

    async def test_the_pointer_is_reported_only_when_it_moves(self):
        task = asyncio.create_task(self.s._report_pointer())
        await asyncio.sleep(0.12)
        Fake.cursor = (150, 20)
        await asyncio.sleep(0.12)
        task.cancel()
        self.assertEqual([(m["x"], m["y"]) for m in self.w.messages("ptr")], [(10, 10), (150, 20)])
        self.assertEqual(len(self.w.messages("monitors")), 1)


class Catalog(Harness):
    async def test_the_list_goes_at_once_and_the_icons_follow_in_batches(self):
        apps = [{"id": f"a{i}", "name": f"App {i}", "icon_name": "", **({"icon": "x" * 10} if i != 3 else {})} for i in range(19)]

        async def fake_apps(with_icons=True):
            if not with_icons:
                return [{"id": a["id"], "name": a["name"], "has_icon": "icon" in a} for a in apps]
            return [dict(a) for a in apps]

        async def fake_binds():
            return [{"label": "Close window", "key": "w", "mods": ["super"], "cap": "W"}]
        with mock.patch.object(server.catalog, "apps", fake_apps), mock.patch.object(server.catalog, "binds", fake_binds):
            await self.s._send_catalog()
        first = self.w.messages("catalog")[0]
        self.assertIs(first["apps"][0]["icon"], True, "the list says a picture is coming, and carries none")
        self.assertIs(first["apps"][3]["icon"], False)
        self.assertEqual(len(first["binds"]), 1)
        batches = self.w.messages("icons")
        self.assertTrue(all(len(b["icons"]) <= server.ICON_BATCH for b in batches))
        self.assertEqual(sum(len(b["icons"]) for b in batches), 18)
        self.assertTrue(batches[-1].get("done"), "the phone hears when the last has gone")

    async def test_a_raw_code_no_binding_uses_is_not_pressed(self):
        pressed = []
        self.s.daemon.injector = SimpleNamespace(key=lambda name, mods, down: pressed.append(name))

        async def bound(name):
            return name == "#193"
        self.s.daemon.host = "t3lluz"
        with mock.patch.object(server.catalog, "bound_code", bound):
            await self.s.handle({"t": "key", "key": "#205"})
            await self.s.handle({"t": "key", "key": "#193", "mods": ["super"]})
        self.assertEqual(pressed, ["#193"])
        self.assertEqual(len(self.w.messages("toast")), 1, "the phone hears the key isn't bound")


class Buttons(unittest.TestCase):
    """The phone can hold one button two ways at once: its Left key and a held finger on the pad."""

    def injector(self):
        from weave.inject import Injector
        inj = Injector.__new__(Injector)
        inj.events = []
        inj.mouse = SimpleNamespace(emit=lambda *e: inj.events.extend(e))
        inj.keyboard = SimpleNamespace(emit=lambda *e: inj.events.extend(e))
        inj._held = set()
        return inj

    def test_the_button_comes_up_only_when_the_last_hold_lets_go(self):
        inj = self.injector()
        inj.button("left", True)      # the Left key
        inj.button("left", True)      # a finger held on the pad
        inj.button("left", False)     # the finger lifts
        self.assertEqual([e[2] for e in inj.events], [1], "still held by the Left key")
        inj.button("left", False)
        self.assertEqual([e[2] for e in inj.events], [1, 0])

    def test_a_tap_while_the_button_is_held_does_not_let_go(self):
        inj = self.injector()
        inj.button("left", True)
        inj.click("left")
        self.assertEqual([e[2] for e in inj.events], [1])

    def test_letting_go_of_everything_resets_the_count(self):
        inj = self.injector()
        inj.button("left", True)
        inj.button("left", True)
        inj.release_all()
        inj.button("left", True)
        self.assertEqual([e[2] for e in inj.events], [1, 0, 1])


if __name__ == "__main__":
    unittest.main()


class FingersOnThePad(unittest.TestCase):
    """The touchpad the phone's fingers are laid on, with the kernel's device written down instead."""

    def pad(self):
        from weave import inject
        self.sent = []
        device = SimpleNamespace(emit=lambda *e: self.sent.append(list(e)), close=lambda: None)
        return inject, inject.Touchpad(device)

    def last(self, code):
        return [v for frame in self.sent for t, c, v in frame if c == code][-1]

    def test_two_fingers_land_round_the_middle_and_keep_their_places_to_each_other(self):
        inject, pad = self.pad()
        pad.frame([(7, 20.0, 40.0), (9, 40.0, 44.0)])
        frame = self.sent[0]
        xs = [v for t, c, v in frame if c == inject.ABS_MT_POSITION_X]
        ys = [v for t, c, v in frame if c == inject.ABS_MT_POSITION_Y]
        middle = inject.PAD_MM / 2 * inject.PAD_UNITS_PER_MM
        self.assertEqual(sum(xs) / 2, middle)
        self.assertEqual(sum(ys) / 2, middle)
        self.assertEqual(xs[1] - xs[0], 20 * inject.PAD_UNITS_PER_MM, "20 mm apart on the phone, 20 mm apart here")
        self.assertIn((inject.EV_KEY, inject.BTN_TOUCH, 1), frame)
        self.assertIn((inject.EV_KEY, inject.BTN_TOOL[1], 1), frame, "two fingers")
        self.assertEqual(len({v for t, c, v in frame if c == inject.ABS_MT_TRACKING_ID}), 2)

    def test_moving_says_only_what_moved_and_lifting_lets_go(self):
        inject, pad = self.pad()
        pad.frame([(1, 20.0, 40.0), (2, 40.0, 40.0)])
        pad.frame([(1, 25.0, 40.0), (2, 40.0, 40.0)])
        moved = self.sent[1]
        self.assertEqual([v for t, c, v in moved if c == inject.ABS_MT_SLOT], [0], "the still finger says nothing")
        self.assertNotIn(inject.ABS_MT_TRACKING_ID, [c for t, c, v in moved])
        pad.frame([(1, 25.0, 40.0), (2, 40.0, 40.0)])
        self.assertEqual(len(self.sent), 2, "nothing moved, nothing sent")
        pad.lift()
        gone = self.sent[-1]
        self.assertEqual([v for t, c, v in gone if c == inject.ABS_MT_TRACKING_ID], [-1, -1])
        self.assertIn((inject.EV_KEY, inject.BTN_TOUCH, 0), gone)
        self.assertIn((inject.EV_KEY, inject.BTN_TOOL[1], 0), gone)
        self.assertFalse(pad.touched)

    def test_a_third_finger_joins_and_the_count_follows(self):
        inject, pad = self.pad()
        pad.frame([(1, 20.0, 40.0), (2, 40.0, 40.0)])
        pad.frame([(1, 20.0, 40.0), (2, 40.0, 40.0), (3, 60.0, 40.0)])
        joined = self.sent[-1]
        self.assertIn((inject.EV_KEY, inject.BTN_TOOL[1], 0), joined)
        self.assertIn((inject.EV_KEY, inject.BTN_TOOL[2], 1), joined)
        self.assertNotIn((inject.EV_KEY, inject.BTN_TOUCH, 1), joined, "the pad was touched already")

    def test_turned_round_the_same_stroke_goes_the_other_way(self):
        inject, pad = self.pad()
        pad.frame([(1, 20.0, 40.0), (2, 40.0, 40.0)], turned=True)
        before = self.last(inject.ABS_X)
        pad.frame([(1, 30.0, 40.0), (2, 50.0, 40.0)], turned=True)
        self.assertLess(self.last(inject.ABS_X), before, "to the right on the phone, to the left here")

    def test_only_two_fingers_are_ever_turned_and_a_third_starts_the_touch_over(self):
        inject, pad = self.pad()
        pad.frame([(1, 20.0, 40.0), (2, 40.0, 40.0), (3, 60.0, 40.0)], turned=True)
        before = self.last(inject.ABS_X)
        pad.frame([(1, 30.0, 40.0), (2, 50.0, 40.0), (3, 70.0, 40.0)], turned=True)
        self.assertGreater(self.last(inject.ABS_X), before, "three fingers go the way they go on the phone")
        pad.lift()
        self.sent.clear()
        pad.frame([(1, 20.0, 40.0), (2, 40.0, 40.0)], turned=True)
        pad._began -= 1   # well past the moment a lift would read as a tap
        pad.frame([(1, 20.0, 40.0), (2, 40.0, 40.0), (3, 60.0, 40.0)], turned=True)
        ids = [v for frame in self.sent for t, c, v in frame if c == inject.ABS_MT_TRACKING_ID]
        self.assertEqual(ids.count(-1), 2, "the two turned fingers are lifted")
        self.assertEqual(len([i for i in ids if i != -1]), 5, "and all three are laid again")
        before = self.last(inject.ABS_X)
        pad.frame([(1, 30.0, 40.0), (2, 50.0, 40.0), (3, 70.0, 40.0)], turned=True)
        self.assertGreater(self.last(inject.ABS_X), before)

    def test_a_stroke_off_the_pad_stops_at_its_rim_and_more_fingers_than_it_has_are_left_out(self):
        inject, pad = self.pad()
        pad.frame([(n, 10.0 * n, 0.0) for n in range(8)])
        self.assertEqual(len({v for t, c, v in self.sent[0] if c == inject.ABS_MT_TRACKING_ID}), inject.PAD_FINGERS)
        pad.frame([(0, 9000.0, -9000.0)])
        top = inject.PAD_MM * inject.PAD_UNITS_PER_MM
        self.assertTrue(0 < self.last(inject.ABS_MT_POSITION_X) < top)
        self.assertTrue(0 < self.last(inject.ABS_MT_POSITION_Y) < top)

    def test_a_finger_that_is_nowhere_is_left_out_and_none_is_left_stuck(self):
        inject, pad = self.pad()
        pad.frame([(1, 20.0, 40.0), (2, 40.0, 40.0), (3, 60.0, 40.0)])
        pad.frame([(1, float("nan"), 40.0), (2, 40.0, 40.0)])
        self.assertEqual(len(pad._slots), 1, "the one that made no sense and the one that left are both up")
        pad.lift()
        ups = [v for frame in self.sent for t, c, v in frame if c == inject.ABS_MT_TRACKING_ID and v == -1]
        self.assertEqual(len(ups), 3)
        self.assertIn((inject.EV_KEY, inject.BTN_TOUCH, 0), self.sent[-1])

    def test_hyprland_says_on_and_off_two_ways(self):
        from weave import hypr
        self.assertTrue(hypr.option_on({"option": "input:touchpad:natural_scroll", "bool": True, "set": True}))
        self.assertFalse(hypr.option_on({"option": "input:touchpad:natural_scroll", "bool": False, "set": True}))
        self.assertTrue(hypr.option_on({"option": "input:touchpad:natural_scroll", "int": 1, "set": True}))
        self.assertFalse(hypr.option_on({}))

    def test_letting_go_of_everything_lifts_the_fingers_too(self):
        from weave.inject import Injector
        inject, pad = self.pad()
        inj = Injector.__new__(Injector)
        inj.mouse = inj.keyboard = SimpleNamespace(emit=lambda *e: None)
        inj._held, inj.pad = set(), pad
        pad.frame([(1, 20.0, 40.0), (2, 40.0, 40.0)])
        inj.release_all()
        self.assertFalse(pad.touched)


class FingersInBunches(unittest.IsolatedAsyncioTestCase):
    """Frames that arrive together are not written together: libinput takes that for a jump."""

    def pad(self):
        from weave import inject
        self.sent = []
        device = SimpleNamespace(emit=lambda *e: self.sent.append((asyncio.get_running_loop().time(), list(e))), close=lambda: None)
        return inject, inject.Touchpad(device)

    async def test_the_same_fingers_again_wait_their_turn_and_only_the_newest_is_written(self):
        inject, pad = self.pad()
        pad.frame([(1, 20.0, 40.0), (2, 40.0, 40.0)])
        for step in (1, 2, 3):
            pad.frame([(1, 20.0 + step, 40.0), (2, 40.0 + step, 40.0)])
        self.assertEqual(len(self.sent), 1, "the landing went at once, the rest wait")
        await asyncio.sleep(inject.PAD_PACE * 3)
        self.assertEqual(len(self.sent), 2)
        self.assertGreaterEqual(self.sent[1][0] - self.sent[0][0], inject.PAD_PACE * 0.9)
        xs = [v for t, c, v in self.sent[1][1] if c == inject.ABS_MT_POSITION_X]
        landed = [v for t, c, v in self.sent[0][1] if c == inject.ABS_MT_POSITION_X]
        self.assertEqual(xs[0] - landed[0], 3 * inject.PAD_UNITS_PER_MM, "where the fingers are now, not a step on the way")

    async def test_the_end_of_a_stroke_is_written_before_the_fingers_lift(self):
        inject, pad = self.pad()
        pad.frame([(1, 20.0, 40.0), (2, 40.0, 40.0)])
        pad.frame([(1, 25.0, 40.0), (2, 45.0, 40.0)])
        pad.frame(())      # as the phone says it: the fingers are up
        self.assertEqual(len(self.sent), 1, "both wait their turn")
        await asyncio.sleep(inject.PAD_PACE * 3)
        self.assertEqual(len(self.sent), 3, "where the fingers got to, then up")
        moved = [v for t, c, v in self.sent[1][1] if c == inject.ABS_MT_POSITION_X]
        landed = [v for t, c, v in self.sent[0][1] if c == inject.ABS_MT_POSITION_X]
        self.assertEqual(moved[0] - landed[0], 5 * inject.PAD_UNITS_PER_MM)
        self.assertEqual([v for t, c, v in self.sent[2][1] if c == inject.ABS_MT_TRACKING_ID], [-1, -1])
        self.assertFalse(pad.touched)

    async def test_a_new_touch_right_behind_a_lift_does_not_run_into_the_old_one(self):
        inject, pad = self.pad()
        pad.frame([(1, 20.0, 40.0), (2, 40.0, 40.0)])
        pad.frame([(1, 25.0, 40.0), (2, 45.0, 40.0)])
        pad.frame(())
        pad.frame([(7, 60.0, 80.0), (8, 80.0, 80.0)])     # new fingers, in the same bunch
        await asyncio.sleep(inject.PAD_PACE * 3)
        keys = [(c, v) for _, frame in self.sent for t, c, v in frame if t == inject.EV_KEY and c == inject.BTN_TOUCH]
        self.assertEqual(keys, [(inject.BTN_TOUCH, 1), (inject.BTN_TOUCH, 0), (inject.BTN_TOUCH, 1)], "down, up, down again")
        middle = inject.PAD_MM / 2 * inject.PAD_UNITS_PER_MM
        xs = [v for t, c, v in self.sent[-1][1] if c == inject.ABS_MT_POSITION_X]
        self.assertEqual(sum(xs) / 2, middle, "and the new touch is laid round the middle, as any is")
        pad.lift()

    async def test_a_lift_when_the_held_frame_is_already_due_still_writes_it_first(self):
        inject, pad = self.pad()
        pad.frame([(1, 20.0, 40.0), (2, 40.0, 40.0)])
        pad.frame([(1, 25.0, 40.0), (2, 45.0, 40.0)])
        pad._wrote -= 1          # its time has come, but the loop has not got to its timer yet
        pad.frame(())
        self.assertEqual(len(self.sent), 3, "where the fingers got to, then up, at once")
        self.assertEqual([v for t, c, v in self.sent[2][1] if c == inject.ABS_MT_TRACKING_ID], [-1, -1])
        await asyncio.sleep(inject.PAD_PACE * 3)
        self.assertEqual(len(self.sent), 3)

    async def test_a_phone_that_is_gone_lifts_the_fingers_at_once(self):
        inject, pad = self.pad()
        pad.frame([(1, 20.0, 40.0), (2, 40.0, 40.0)])
        pad.frame([(1, 25.0, 40.0), (2, 45.0, 40.0)])
        pad.lift()
        self.assertEqual(len(self.sent), 2, "landing, then lifting, at once")
        self.assertFalse(pad.touched)
        await asyncio.sleep(inject.PAD_PACE * 3)
        self.assertEqual(len(self.sent), 2, "nothing is written after the fingers are up")

    async def test_a_turned_touch_is_not_started_over_in_its_first_moment(self):
        inject, pad = self.pad()
        with mock.patch.object(inject, "PAD_TAP", 0.05):
            pad.frame([(1, 20.0, 40.0), (2, 40.0, 40.0)], turned=True)
            await asyncio.sleep(inject.PAD_PACE * 2)
            pad.frame([(1, 20.0, 40.0), (2, 40.0, 40.0), (3, 60.0, 40.0)], turned=True)
            await asyncio.sleep(inject.PAD_PACE * 2)
            ids = [v for _, frame in self.sent for t, c, v in frame if c == inject.ABS_MT_TRACKING_ID]
            self.assertNotIn(-1, ids, "two fingers down and up this fast would be a tap: the third waits")
            self.assertEqual(len(pad._slots), 2)
            await asyncio.sleep(0.06)
            pad.frame([(1, 21.0, 40.0), (2, 41.0, 40.0), (3, 61.0, 40.0)], turned=True)
            await asyncio.sleep(inject.PAD_PACE * 2)
            self.assertEqual(len(pad._slots), 3, "after that the touch starts over with all three")
        pad.lift()


class Typing(unittest.IsolatedAsyncioTestCase):
    injector = Buttons.injector

    def typist(self, typed):
        """An injector whose typing is slow and written down instead of done."""
        from weave.inject import Injector
        inj = self.injector()
        inj._queue, inj._typing, inj._closing, inj.debug = asyncio.Queue(), False, False, False

        async def slow(value):
            for ch in value:
                await asyncio.sleep(0.01)
                typed.append(ch)

        inj._type = slow
        inj._worker = asyncio.create_task(Injector._drain(inj))
        return inj

    async def test_a_dropped_phone_stops_typing_where_it_is(self):
        typed = []
        inj = self.typist(typed)
        inj.text("a long sentence")
        await asyncio.sleep(0.035)
        inj.text("and one waiting behind it")
        inj.release_all()
        await asyncio.sleep(0.1)
        self.assertLess(len(typed), 6, "cut off, and what waited is dropped")
        inj.text("ok")
        await asyncio.sleep(0.05)
        self.assertEqual("".join(typed)[-2:], "ok", "and it types again for the next phone")
        inj._worker.cancel()

    async def test_a_shortcut_from_the_home_screen_is_typed_to_the_end(self):
        typed = []
        inj = self.typist(typed)
        inj.text("mail@example.com")
        await asyncio.sleep(0.015)
        inj.release_all(finish=True)
        await asyncio.sleep(0.3)
        self.assertEqual("".join(typed), "mail@example.com")
        inj._worker.cancel()


class Clipboard(Harness):
    async def test_copies_go_both_ways_and_never_bounce_back(self):
        board = {"text": "before"}

        async def paste(timeout=5):
            return board["text"]

        async def copy(text):
            board["text"] = text

        async def sendable(text):
            return True

        with mock.patch.object(server.shell, "clip_get", paste), mock.patch.object(server.shell, "clip_set", copy), \
                mock.patch.object(server.shell, "clip_sendable", sendable), mock.patch.object(server, "CLIP_EVERY", 0.01):
            await self.s.handle({"t": "clip_sync", "on": True})
            await asyncio.sleep(0.05)
            # what was there already stays put
            self.assertFalse(self.w.messages("clip"))
            board["text"] = "copied on the laptop"
            await asyncio.sleep(0.05)
            clips = self.w.messages("clip")
            self.assertEqual([c["text"] for c in clips], ["copied on the laptop"])
            self.assertTrue(clips[0]["sync"])
            # a copy from the phone, kept in step: no toast, and not sent back
            await self.s.handle({"t": "clip_set", "text": "copied on the phone", "quiet": True})
            await asyncio.sleep(0.05)
            self.assertEqual(board["text"], "copied on the phone")
            self.assertEqual(len(self.w.messages("clip")), 1)
            self.assertFalse(self.w.messages("toast"))
            await self.s.handle({"t": "clip_sync", "on": False})


class PointerOutOfThePicture(unittest.IsolatedAsyncioTestCase):
    """Hyprland as far as the pointer goes: the option, and what was asked of it."""

    def setUp(self):
        self.mode, self.set = 2, []

        async def get():
            return self.mode

        async def put(mode):
            self.set.append(mode)
            self.mode = mode
            return True

        for name, fake in (("hardware_cursors", get), ("set_hardware_cursors", put)):
            patch = mock.patch.object(server.hypr, name, fake)
            patch.start()
            self.addCleanup(patch.stop)

    async def test_hardware_pointers_only_while_a_drawing_phone_shows_the_picture(self):
        phone = SimpleNamespace(local_cursor=True, video_wanted=True, closed=False)
        plain = SimpleNamespace(local_cursor=False, video_wanted=True, closed=False)
        d = SimpleNamespace(sessions=[plain], cursor_apart=False, cursor_was=None, cursor_lock=asyncio.Lock())
        sync = server.Daemon.sync_cursor
        await sync(d)
        self.assertEqual(self.set, [], "a phone that shows the laptop's pointer changes nothing")
        d.sessions.append(phone)
        await sync(d)
        await sync(d)
        self.assertEqual(self.set, [0])
        self.mode = 2   # a Hyprland reload put it back: the check takes it out again
        await sync(d, check=True)
        await sync(d, check=True)
        self.assertEqual(self.set, [0, 0])
        phone.video_wanted = False   # the picture folded away: Hyprland draws it its own way again
        await sync(d)
        self.assertEqual(self.set, [0, 0, 2])

    async def test_a_laptop_already_on_hardware_pointers_is_left_alone(self):
        self.mode = 0
        phone = SimpleNamespace(local_cursor=True, video_wanted=True, closed=False)
        d = SimpleNamespace(sessions=[phone], cursor_apart=False, cursor_was=None, cursor_lock=asyncio.Lock())
        await server.Daemon.sync_cursor(d)
        phone.video_wanted = False
        await server.Daemon.sync_cursor(d)
        self.assertEqual(self.set, [])


class SettingThePointerMode(unittest.IsolatedAsyncioTestCase):
    async def test_a_lua_config_takes_it_through_eval_and_a_classic_one_through_keyword(self):
        asked = []

        def hyprland(lua):
            async def request(cmd):
                asked.append(cmd)
                if cmd.startswith("eval "):
                    return "ok" if lua else "unknown request"
                return "keyword can't work with non-legacy parsers. Use eval." if lua else "ok"
            return request

        with mock.patch.object(server.hypr, "request", hyprland(lua=True)):
            self.assertTrue(await server.hypr.set_hardware_cursors(0))
        self.assertEqual(asked, ["eval hl.config({ cursor = { no_hardware_cursors = 0 } })"])
        asked.clear()
        with mock.patch.object(server.hypr, "request", hyprland(lua=False)):
            self.assertTrue(await server.hypr.set_hardware_cursors(2))
        self.assertEqual(asked[-1], "keyword cursor:no_hardware_cursors 2")


class MoveNumbers(Harness):
    async def test_a_pointer_report_names_the_last_move_it_shows(self):
        await self.start()
        self.s.daemon.injector = SimpleNamespace(move=lambda *_: None)
        await self.s.handle({"t": "m", "dx": 5, "dy": 0, "s": 7})
        await asyncio.sleep(0.01)
        Fake.cursor = (20, 10)
        await self.s.handle({"t": "m", "dx": 5, "dy": 0, "s": 8})
        # read at once: move 8 had no time to land, move 7 did
        await self.s._follow_once()
        self.assertEqual(self.w.messages("cur")[-1]["seq"], 7)
        await asyncio.sleep(0.01)
        # the pointer hasn't moved since, but there is a newer move to own up to
        await self.s._follow_once()
        self.assertEqual(self.w.messages("cur")[-1]["seq"], 8)
