"""The parts that can be checked without a phone, a compositor or a GPU.

    python3 -m unittest discover tests
"""

import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from weave import config, hypr
from weave.capture import _annexb, _parameter_sets


class Video(unittest.TestCase):
    def test_length_prefixed_units_become_start_codes(self):
        avcc = (3).to_bytes(4, "big") + b"\x65\x01\x02" + (2).to_bytes(4, "big") + b"\x41\x09"
        self.assertEqual(_annexb(avcc), b"\x00\x00\x00\x01\x65\x01\x02" + b"\x00\x00\x00\x01\x41\x09")

    def test_a_truncated_unit_is_dropped_not_read_past_the_end(self):
        avcc = (3).to_bytes(4, "big") + b"\x65\x01\x02" + (9).to_bytes(4, "big") + b"\x41"
        self.assertEqual(_annexb(avcc), b"\x00\x00\x00\x01\x65\x01\x02")

    def test_parameter_sets_come_out_of_the_decoder_record(self):
        sps, pps = b"\x67\x64\x00\x2a", b"\x68\xee\x3c"
        record = bytes([1, 0x64, 0, 0x2A, 0xFF, 0xE1]) + len(sps).to_bytes(2, "big") + sps + bytes([1]) + len(pps).to_bytes(2, "big") + pps
        sets, length_size = _parameter_sets(record)
        self.assertEqual(sets, b"\x00\x00\x00\x01" + sps + b"\x00\x00\x00\x01" + pps)
        self.assertEqual(length_size, 4)


class Settings(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.patch = mock.patch.object(config, "CONFIG_DIR", Path(self.dir.name))
        self.patch.start()

    def tearDown(self):
        self.patch.stop()
        self.dir.cleanup()

    def cfg(self):
        return json.loads(json.dumps(config.DEFAULTS))

    def test_a_patch_changes_what_it_may_and_saves_only_that(self):
        cfg = self.cfg()
        changed = config.apply_patch(cfg, {"mirror": {"bit_rate": "8M", "screen_off": 1}, "phone_audio": {"buffer_ms": 120}})
        self.assertEqual(sorted(changed), [("mirror", "bit_rate"), ("mirror", "screen_off"), ("phone_audio", "buffer_ms")])
        self.assertEqual((cfg["mirror"]["bit_rate"], cfg["mirror"]["screen_off"], cfg["phone_audio"]["buffer_ms"]), ("8M", True, 120))
        saved = json.loads((Path(self.dir.name) / "config.json").read_text())
        self.assertEqual(saved, {"mirror": {"bit_rate": "8M", "screen_off": True}, "phone_audio": {"buffer_ms": 120}})

    def test_what_is_not_a_setting_or_not_a_choice_is_dropped(self):
        cfg = self.cfg()
        changed = config.apply_patch(cfg, {"mirror": {"bit_rate": "999M", "extra_args": ["--evil"]}, "port": 1, "nope": {"x": 1}, "terminals": ["x"]})
        self.assertEqual(changed, [])
        self.assertEqual(cfg["mirror"]["extra_args"], [])
        self.assertFalse((Path(self.dir.name) / "config.json").exists())

    def test_a_range_is_clamped(self):
        cfg = self.cfg()
        config.apply_patch(cfg, {"mirror": {"height": 4}, "phone_audio": {"volume": -1}})
        self.assertEqual((cfg["mirror"]["height"], cfg["phone_audio"]["volume"]), (0.95, 0.0))

    def test_a_patch_keeps_what_the_file_already_had(self):
        (Path(self.dir.name) / "config.json").write_text(json.dumps({"dashboard": "https://example.com", "mirror": {"keyboard": "sdk"}}))
        config.apply_patch(self.cfg(), {"mirror": {"max_fps": 60}})
        saved = json.loads((Path(self.dir.name) / "config.json").read_text())
        self.assertEqual(saved, {"dashboard": "https://example.com", "mirror": {"keyboard": "sdk", "max_fps": 60}})

    def test_every_setting_has_a_default(self):
        for group, key in config.SETTINGS:
            self.assertIn(key, config.DEFAULTS[group], f"{group}.{key}")
        self.assertEqual(set(config.settings_of(config.DEFAULTS)), {g for g, _ in config.SETTINGS})

    def test_the_users_file_is_laid_over_the_defaults(self):
        merged = config._merge(config.DEFAULTS, {"mirror": {"keyboard": "sdk"}, "port": 5})
        self.assertEqual((merged["mirror"]["keyboard"], merged["mirror"]["codec"], merged["port"]), ("sdk", "h265", 5))


class Monitors(unittest.TestCase):
    MONS = [{"name": "A", "x": 0, "y": 0, "w": 100, "h": 50}, {"name": "B", "x": 100, "y": 0, "w": 200, "h": 100}]

    def test_the_monitor_under_a_point(self):
        self.assertEqual(hypr.monitor_at(self.MONS, 10, 10)["name"], "A")
        self.assertEqual(hypr.monitor_at(self.MONS, 100, 99)["name"], "B")
        self.assertIsNone(hypr.monitor_at(self.MONS, 50, 60))



class Dispatch(unittest.IsolatedAsyncioTestCase):
    """Hyprland 0.56 takes Lua dispatchers; older ones the word-and-arguments form."""

    async def run_with(self, replies):
        sent = []

        async def request(cmd, timeout=1.0):
            sent.append(cmd)
            return replies(cmd)

        with mock.patch.object(hypr, "request", request), mock.patch.object(hypr, "_lua", None):
            await hypr.move_cursor(10, 20)
            await hypr.move_cursor(30, 40)
            return sent, hypr._lua

    async def test_a_lua_hyprland_gets_lua(self):
        sent, lua = await self.run_with(lambda cmd: "ok")
        self.assertEqual(sent, ["dispatch hl.dsp.cursor.move({ x = 10, y = 20 })", "dispatch hl.dsp.cursor.move({ x = 30, y = 40 })"])
        self.assertTrue(lua)

    async def test_an_older_hyprland_gets_the_old_form_and_it_is_remembered(self):
        sent, lua = await self.run_with(lambda cmd: "Invalid dispatcher" if "hl." in cmd else "ok")
        self.assertEqual(sent, ["dispatch hl.dsp.cursor.move({ x = 10, y = 20 })", "dispatch movecursor 10 20", "dispatch movecursor 30 40"])
        self.assertFalse(lua)


if __name__ == "__main__":
    unittest.main()
