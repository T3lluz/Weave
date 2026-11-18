"""What the phone can make a shortcut of: the laptop's apps and its key bindings.

    python3 -m unittest discover tests
"""

import tempfile
import unittest
from pathlib import Path
from unittest import mock

from weave import catalog
from weave.inject import key_code


def bind(desc, key="", mask=64, **kw):
    return {"description": desc, "key": key, "modmask": mask, "keycode": 0, "submap": "", "mouse": False, **kw}


class Binds(unittest.TestCase):
    def test_a_letter_binding_is_its_letter_with_its_modifiers(self):
        out = catalog.bind_keys([bind("Close window", "W"), bind("Browser", "RETURN", 65)], {}, None)
        self.assertEqual(out[0], {"label": "Close window", "key": "w", "mods": ["super"], "cap": "W"})
        self.assertEqual(out[1]["key"], "enter")
        self.assertEqual(out[1]["mods"], ["shift", "super"])

    def test_numbered_bindings_made_in_a_loop_get_the_number_row(self):
        # Hyprland 0.56 reports `code:NN` bindings with no key; the loop in the config adds 9
        out = catalog.bind_keys([bind("Switch to workspace 3"), bind("Switch to workspace 10")], {}, 9)
        self.assertEqual([b["key"] for b in out], ["3", "0"])

    def test_a_code_binding_named_in_the_config_gets_that_key(self):
        out = catalog.bind_keys([bind("Expand window left")], {"Expand window left": 20}, 9)
        self.assertEqual(out[0]["key"], "minus")

    def test_a_key_named_by_place_reads_as_the_laptops_layout_prints_it(self):
        out = catalog.bind_keys([bind("Expand window left")], {"Expand window left": 20}, 9, {"minus": "+"})
        self.assertEqual(out[0]["cap"], "+")

    def test_the_norwegian_layout_as_xkb_has_it(self):
        if not catalog.shutil.which("xkbcli"):
            self.skipTest("no xkbcli here")
        caps = catalog._read_layout_caps("no", "")
        self.assertEqual((caps.get("minus"), caps.get("equal"), caps.get("leftbrace")), ("+", "\\", "Å"))

    def test_a_key_with_no_name_is_pressed_by_its_code(self):
        out = catalog.bind_keys([bind("Omarchy menu", mask=65)], {"Omarchy menu": 201}, None)
        self.assertEqual(out[0]["key"], "#193")
        self.assertEqual(key_code("#193"), 193)

    def test_a_description_bound_twice_keeps_the_named_key(self):
        out = catalog.bind_keys([bind("Omarchy menu", mask=65), bind("Omarchy menu", "SPACE", 72)], {"Omarchy menu": 201}, None)
        self.assertEqual(len(out), 1)
        self.assertEqual((out[0]["key"], out[0]["mods"]), ("space", ["alt", "super"]))

    def test_what_cant_be_pressed_from_here_is_left_out(self):
        raw = [
            bind("Drag", "mouse:272", mouse=True),
            bind("Resize submap", "R", submap="resize"),
            bind("Scaling up", "SLASH"),            # moves with the layout
            bind("Caps thing", "A", mask=2),         # caps lock
            bind("Power", "XF86PowerOff", mask=0),
            bind("", "Q"),
        ]
        self.assertEqual(catalog.bind_keys(raw, {}, None), [])

    def test_nothing_that_leaves_the_laptop_out_of_reach_is_pressed_by_code(self):
        # power, sleep, suspend (logind hibernates), Wi-Fi, Bluetooth, display off, radio kill
        for code in (116, 142, 205, 237, 238, 245, 247, 300):
            self.assertIsNone(key_code(f"#{code}"), code)
        self.assertIsNone(key_code("#x"))
        self.assertEqual(key_code("#30"), 30)

    def test_a_push_to_talk_binding_is_left_out(self):
        # pressed and let go in one tap, it would start and stop at once
        out = catalog.bind_keys([bind("Start dictation (push-to-talk)", "F9", 0, release=False),
                                 bind("Stop dictation (push-to-talk)", "F9", 0, release=True)], {}, None)
        self.assertEqual([b["label"] for b in out], ["Start dictation (push-to-talk)"])


class Bound(unittest.IsolatedAsyncioTestCase):
    async def test_a_raw_code_is_pressed_only_when_a_binding_uses_it(self):
        catalog._binds_cache = (catalog.time.monotonic(), [{"label": "Omarchy menu", "key": "#193", "mods": ["super"], "cap": "Key"}])
        self.assertTrue(await catalog.bound_code("#193"))
        self.assertFalse(await catalog.bound_code("#30"))
        self.assertTrue(await catalog.bound_code("w"))


class Apps(unittest.TestCase):
    def write(self, root: Path, name: str, body: str):
        path = root / "applications" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("[Desktop Entry]\n" + body)

    def test_a_launcher_lists_shown_apps_and_the_users_own_entry_wins(self):
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as system:
            home, system = Path(home), Path(system)
            self.write(system, "firefox.desktop", "Type=Application\nName=Firefox\nExec=firefox\nIcon=firefox\n")
            self.write(system, "hidden.desktop", "Type=Application\nName=Hidden\nExec=x\nNoDisplay=true\n")
            self.write(system, "gnome-only.desktop", "Type=Application\nName=Gnome\nExec=x\nOnlyShowIn=GNOME;\n")
            self.write(system, "kde/dolphin.desktop", "Type=Application\nName=Dolphin\nExec=dolphin\n")
            self.write(system, "steam.desktop", "Type=Application\nName=Steam\nExec=steam\n")
            # the user's own copy hides the system's
            self.write(home, "steam.desktop", "Type=Application\nName=Steam\nExec=steam\nHidden=true\n")
            self.write(home, "web.desktop", "Type=Application\nName=YouTube\nName[nb]=YouTube NB\nExec=omarchy-launch-webapp x\n")
            with mock.patch.object(catalog, "_data_dirs", lambda: [home, system]):
                apps = catalog.scan_apps()
        self.assertEqual([(a["id"], a["name"]) for a in apps], [("kde-dolphin", "Dolphin"), ("firefox", "Firefox"), ("web", "YouTube")])


if __name__ == "__main__":
    unittest.main()
