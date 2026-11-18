"""The parts of the Windows side that are plain arithmetic and text: they run anywhere."""

import sys
import unittest

from weave.win import capture, catalog, helpers, inject, screen


class Picture(unittest.TestCase):
    def test_a_monitor_inside_the_limit_is_sent_as_it_is(self):
        self.assertEqual(capture.target_size(1920, 1080, "1920x1200"), (1920, 1080))

    def test_a_bigger_one_shrinks_to_fit_and_never_grows(self):
        self.assertEqual(capture.target_size(3840, 2160, "1920x1200"), (1920, 1080))
        self.assertEqual(capture.target_size(1280, 800, "2560x1600"), (1280, 800))

    def test_sizes_are_even_both_ways(self):
        w, h = capture.target_size(1366, 767, "1280x800")
        self.assertEqual((w % 2, h % 2), (0, 0))

    def test_the_command_grabs_exactly_the_monitor_and_leaves_the_pointer_out_when_asked(self):
        mon = {"x": -1920, "y": 0, "pw": 1920, "ph": 1080}
        args = capture.video_args("ffmpeg", mon, "1280x800", "high", 60, False, capture.ENCODERS[-1])
        joined = " ".join(args)
        self.assertIn("-f gdigrab -framerate 60 -draw_mouse 0 -offset_x -1920 -offset_y 0 -video_size 1920x1080 -i desktop", joined)
        self.assertIn("scale=1280:720", joined)
        self.assertIn("-c:v libx264 -preset ultrafast -tune zerolatency", joined)
        self.assertTrue(joined.endswith("-f flv -flvflags no_duration_filesize -flush_packets 1 pipe:1"))
        self.assertIn("-draw_mouse 1", " ".join(capture.video_args("ffmpeg", mon, "1280x800", "high", 60, True, capture.ENCODERS[-1])))

    def test_with_sound_the_command_reads_it_from_its_input_and_sends_it_as_aac(self):
        mon = {"x": 0, "y": 0, "pw": 1280, "ph": 800}
        quiet = capture.video_args("ffmpeg", mon, "1920x1200", "high", 60, False, capture.ENCODERS[-1])
        loud = capture.video_args("ffmpeg", mon, "1920x1200", "high", 60, False, capture.ENCODERS[-1], audio_kbps=160)
        self.assertIn("-nostdin", quiet)
        self.assertNotIn("-nostdin", loud, "its input is the sound")
        self.assertIn("-f s16le -ar 48000 -ac 2 -i pipe:0 -c:a aac -b:a 160k", " ".join(loud))
        self.assertLess(loud.index("desktop"), loud.index("pipe:0"), "the picture is the first input")
        alone = " ".join(capture.sound_args("ffmpeg", 160))
        self.assertIn("-f s16le -ar 48000 -ac 2 -i pipe:0 -c:a aac -b:a 160k -f flv", alone)

    def test_bitrate_follows_size_pace_and_quality_within_bounds(self):
        self.assertGreater(capture.bitrate(1920, 1080, 60, "very_high"), capture.bitrate(1920, 1080, 60, "medium"))
        self.assertEqual(capture.bitrate(320, 200, 30, "medium"), 1500)
        self.assertEqual(capture.bitrate(7680, 4320, 60, "very_high"), 40_000)


class Screens(unittest.TestCase):
    def test_monitors_stand_left_to_right_named_as_windows_names_them(self):
        found = [{"device": "\\\\.\\DISPLAY2", "primary": False, "x": 1920, "y": 0, "w": 2560, "h": 1440},
                 {"device": "\\\\.\\DISPLAY1", "primary": True, "x": 0, "y": 0, "w": 1920, "h": 1080}]
        mons = screen.describe(found, (2000, 10))
        self.assertEqual([m["name"] for m in mons], ["DISPLAY1", "DISPLAY2"])
        self.assertEqual([m["label"] for m in mons], ["Main screen", "Screen 2"])
        self.assertEqual([m["focused"] for m in mons], [False, True])
        self.assertEqual((mons[1]["pw"], mons[1]["ph"]), (2560, 1440))
        self.assertEqual(screen.monitor_at(mons, 100, 100)["name"], "DISPLAY1")
        self.assertIsNone(screen.monitor_at(mons, 9000, 0))


class Keys(unittest.TestCase):
    @unittest.skipIf(sys.platform == "win32", "the Linux side's key table loads only on Linux")
    def test_every_named_key_the_linux_side_knows_has_a_windows_key_or_is_laptop_only(self):
        from weave.inject import KEYS
        laptop_only = {"brightdown", "brightup", "kbdlight", "kbddown", "kbdup", "micmute"}
        self.assertEqual(set(KEYS) - set(inject.VK), laptop_only)

    def test_a_raw_linux_code_presses_nothing(self):
        self.assertIsNone(inject.key_vk("#30"))
        self.assertEqual(inject.key_vk("a"), (0x41, False))
        self.assertEqual(inject.key_vk("left"), (0x25, True))

    def test_a_character_is_typed_with_the_layouts_own_key_when_it_has_one(self):
        # what VkKeyScanEx answers: the key in the low byte, shift (1) and ctrl+alt (6) in the high
        self.assertEqual(inject.layout_stroke(0x0041), (0x41, False, False))      # a
        self.assertEqual(inject.layout_stroke(0x0141), (0x41, True, False))       # A
        self.assertEqual(inject.layout_stroke(0x0632), (0x32, False, True))       # @ on a Nordic layout: AltGr + 2
        self.assertIsNone(inject.layout_stroke(-1), "no key for it: sent as itself")
        self.assertIsNone(inject.layout_stroke(0x0241), "ctrl + a is a command, not a character")
        # ^ on a Norwegian layout is a dead key: pressing it types nothing until the next letter
        self.assertIsNone(inject.layout_stroke(0x01DD, dead=True))
        self.assertEqual(inject.layout_stroke(0x01DD), (0xDD, True, False))

    def test_a_point_maps_onto_the_whole_desktop(self):
        desk = (-1920, 0, 3840, 1080)   # a monitor to the left of the main one
        self.assertEqual(inject.normalized(-1920, 0, desk), (0, 0))
        self.assertEqual(inject.normalized(1919, 1079, desk), (65535, 65535))
        x, _ = inject.normalized(0, 0, desk)
        self.assertAlmostEqual(x, 32776, delta=2)


class PowerShellText(unittest.TestCase):
    def test_no_kind_of_quote_ends_the_string_early(self):
        self.assertEqual(helpers.ps_quote("plain"), "'plain'")
        self.assertEqual(helpers.ps_quote("O'Brien"), "'O''Brien'")
        # PowerShell also closes a string at the typographic quotes a phone's name can carry
        self.assertEqual(helpers.ps_quote("Fredde\u2019s Pixel"), "'Fredde\u2019\u2019s Pixel'")
        for quote in "'\u2018\u2019\u201a\u201b":
            inner = helpers.ps_quote(f"a{quote}; calc.exe; {quote}b")[1:-1]
            self.assertEqual(inner.count(quote) % 2, 0, f"every {quote!r} doubled")


class StartMenu(unittest.TestCase):
    def test_programs_and_store_apps_are_listed_once_and_uninstallers_left_out(self):
        text = '''[{"Name":"Notepad","AppID":"Microsoft.WindowsNotepad_8wekyb3d8bbwe!App"},
                   {"Name":"Spotify","AppID":"Spotify.exe"},
                   {"Name":"Uninstall Spotify","AppID":"{6D809377}\\\\Spotify\\\\uninstall.exe"},
                   {"Name":"Notepad","AppID":"another"},
                   {"Name":"Vendor website","AppID":"https://example.com"},
                   {"Name":"","AppID":"x"}]'''
        apps = catalog.parse_start_apps(text)
        self.assertEqual([a["name"] for a in apps], ["Notepad", "Spotify"])
        self.assertEqual(apps[0]["id"], "Microsoft.WindowsNotepad_8wekyb3d8bbwe!App")
        self.assertEqual(apps[0]["path"], "shell:AppsFolder\\Microsoft.WindowsNotepad_8wekyb3d8bbwe!App")

    def test_a_lone_app_and_no_answer_are_both_fine(self):
        self.assertEqual(len(catalog.parse_start_apps('{"Name":"Paint","AppID":"p"}')), 1)
        self.assertEqual(catalog.parse_start_apps(""), [])
        self.assertEqual(catalog.parse_start_apps("not json"), [])


if __name__ == "__main__":
    unittest.main()
