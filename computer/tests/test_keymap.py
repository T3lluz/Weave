import time
import unittest

from weave.inject import EV_KEY, Injector
from weave.keymap import Stroke, strokes_for

SHIFT, ALTGR, SPACE = 42, 100, 57


class Layout(unittest.TestCase):
    def setUp(self):
        self.no = strokes_for("no")
        if not self.no:
            self.skipTest("libxkbcommon is not here")

    def test_space_and_symbols_are_real_keys(self):
        self.assertEqual(self.no[" "], [Stroke(SPACE)])
        self.assertEqual(self.no["!"], [Stroke(2, shift=True)])
        self.assertEqual(self.no["?"], [Stroke(12, shift=True)])
        self.assertEqual(self.no["@"], [Stroke(3, altgr=True)])
        self.assertEqual(self.no["{"], [Stroke(8, altgr=True)])
        self.assertEqual(self.no["æ"], [Stroke(40)])

    def test_a_dead_key_types_its_mark_with_a_space(self):
        self.assertEqual(self.no["~"], [Stroke(27, altgr=True), Stroke(SPACE)])
        self.assertEqual(self.no["`"], [Stroke(13, shift=True), Stroke(SPACE)])

    def test_what_the_layout_lacks_is_not_there(self):
        self.assertNotIn("é", self.no)
        self.assertNotIn("😀", self.no)

    def test_another_layout_puts_them_elsewhere(self):
        us = strokes_for("us")
        self.assertEqual(us["@"], [Stroke(3, shift=True)])
        self.assertEqual(us["~"], [Stroke(41, shift=True)])

    def test_a_layout_that_does_not_exist_gives_nothing(self):
        self.assertEqual(strokes_for("no-such-layout"), {})


class Keys:
    def __init__(self):
        self.events = []

    def emit(self, *events):
        self.events += [(c, v) for t, c, v in events if t == EV_KEY]


class Typing(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        strokes = strokes_for("no")
        if not strokes:
            self.skipTest("libxkbcommon is not here")
        inj = Injector.__new__(Injector)
        inj.keyboard = Keys()
        inj._held = set()
        inj._strokes = strokes
        inj._caps = False
        inj._layout_read = time.monotonic()   # no Hyprland to ask
        inj.wtyped = []

        async def wtype(part):
            if part:
                inj.wtyped.append(("wtype", part, len(inj.keyboard.events)))
        inj._wtype = wtype
        self.inj = inj

    async def test_a_space_and_a_symbol_go_through_the_keyboard(self):
        await self.inj._type("a !")
        self.assertEqual(self.inj.keyboard.events, [
            (30, 1), (30, 0),
            (SPACE, 1), (SPACE, 0),
            (SHIFT, 1), (2, 1), (2, 0), (SHIFT, 0),
        ])
        self.assertEqual(self.inj.wtyped, [])

    async def test_caps_lock_is_turned_back_for_letters_only(self):
        self.inj._caps = True
        await self.inj._type("a1")
        self.assertEqual(self.inj.keyboard.events, [(SHIFT, 1), (30, 1), (30, 0), (SHIFT, 0), (2, 1), (2, 0)])

    async def test_what_the_layout_lacks_goes_to_wtype_in_its_place(self):
        await self.inj._type("ké!")
        # k, then é through wtype, then Shift + 1
        self.assertEqual(self.inj.wtyped, [("wtype", "é", 2)])
        self.assertEqual(self.inj.keyboard.events[2:], [(SHIFT, 1), (2, 1), (2, 0), (SHIFT, 0)])


if __name__ == "__main__":
    unittest.main()
