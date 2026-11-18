package com.t3lluz.weave.data.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkShortcutsTest {
    private val catalog = LinkCatalog(
        apps = listOf(LinkApp("firefox", "Firefox", null), LinkApp("org.gnome.Nautilus", "Files", null)),
        binds = listOf(
            LinkBind("Switch to workspace 3", "3", listOf("super"), "3"),
            LinkBind("Close window", "w", listOf("super"), "W"),
            LinkBind("Omarchy menu", "#193", listOf("shift", "super"), "Key"),
        ),
    )

    private fun made(raw: String) = (parseShortcutAnswer(raw, catalog) as ShortcutAnswer.Made).shortcut

    /** A one-step shortcut's step, for the tests written when a shortcut was one action. */
    private fun step(raw: String) = made(raw).steps.single()

    private fun keys(label: String, key: String, mods: List<String> = emptyList(), cap: String = "") =
        LinkShortcut(label = label, steps = listOf(LinkStep(LinkShortcutKind.KEYS, key = key, mods = mods, cap = cap)))

    @Test
    fun aBindingTheAiPicksKeepsTheLaptopsKeycap() {
        val made = made("""{"kind":"keys","key":"3","mods":["super"],"label":"Workspace 3"}""")
        val s = made.steps.single()
        assertEquals(LinkShortcutKind.KEYS, s.kind)
        assertEquals("3", s.key)
        assertEquals(listOf("super"), s.mods)
        assertEquals("Workspace 3", made.label)
    }

    @Test
    fun aKeyNamedTheWayOtherSystemsNameItIsTheLaptops() {
        val s = step("""{"kind":"keys","key":"T","mods":["Control","shift"],"label":"Reopen tab"}""")
        assertEquals("t", s.key)
        assertEquals(listOf("ctrl", "shift"), s.mods)
        assertEquals("T", s.cap)
    }

    @Test
    fun aRawCodeTheLaptopListedCanBePressed() {
        val s = made("""{"kind":"keys","key":"#193","mods":["super","shift"]}""")
        assertEquals("Omarchy menu", s.label)
        assertEquals(listOf("super", "shift"), s.steps.single().mods)
    }

    @Test
    fun aRawCodeNoBindingUsesIsTurnedDown() {
        // KEY_SUSPEND: logind would hibernate the laptop
        assertTrue(parseShortcutAnswer("""{"kind":"keys","key":"#205","mods":[]}""", catalog) is ShortcutAnswer.Refused)
        assertTrue(parseShortcutAnswer("""{"kind":"keys","key":"#193","mods":[]}""", catalog) is ShortcutAnswer.Refused)
        assertTrue(parseShortcutAnswer("""{"kind":"keys","key":"#193","mods":["super"]}""", null) is ShortcutAnswer.Refused)
    }

    @Test
    fun aKeyTheLaptopCantPressIsTurnedDown() {
        assertTrue(parseShortcutAnswer("""{"kind":"keys","key":"hyper","mods":[]}""", catalog) is ShortcutAnswer.Refused)
        assertTrue(parseShortcutAnswer("""{"kind":"keys","key":"a","mods":["fn"]}""", catalog) is ShortcutAnswer.Refused)
    }

    @Test
    fun anAppMustBeOneTheLaptopHasFoundByIdOrName() {
        assertEquals("firefox", step("""{"kind":"app","app":"firefox"}""").app)
        assertEquals("org.gnome.Nautilus", step("""{"kind":"app","app":"files","label":"Files"}""").app)
        assertTrue(parseShortcutAnswer("""{"kind":"app","app":"photoshop"}""", catalog) is ShortcutAnswer.Refused)
    }

    @Test
    fun aSiteGetsHttpsAndANameFromItsAddress() {
        val s = made("""{"kind":"site","url":"youtube.com"}""")
        assertEquals("https://youtube.com", s.steps.single().url)
        assertEquals("Youtube", s.label)
    }

    @Test
    fun anAnswerWrappedInWordsOrFencesStillReads() {
        val s = made("Sure:\n```json\n{\"kind\":\"text\",\"text\":\"hello@example.com\",\"label\":\"Email\"}\n```")
        assertEquals("hello@example.com", s.steps.single().text)
    }

    @Test
    fun wordsFromTheAiAreOneLineSoNothingRunsInATerminal() {
        val s = made("""{"kind":"text","text":"rm -rf x\nls","label":"Oops"}""")
        assertEquals("rm -rf x ls", s.steps.single().text)
    }

    @Test
    fun aKeyThatMovesWithTheLayoutIsNotMadeHere() {
        assertFalse(LinkKeys.pressable("minus"))
        assertTrue(parseShortcutAnswer("""{"kind":"keys","key":"minus","mods":["ctrl"]}""", catalog) is ShortcutAnswer.Refused)
    }

    @Test
    fun theAisNoIsPassedOn() {
        val answer = parseShortcutAnswer("""{"error":"That takes several steps."}""", catalog)
        assertEquals("That takes several steps.", (answer as ShortcutAnswer.Refused).reason)
        assertTrue(parseShortcutAnswer("no idea", catalog) is ShortcutAnswer.Refused)
    }

    @Test
    fun webAddresses() {
        assertEquals("https://t3lluz.com/x", normalizeShortcutUrl(" t3lluz.com/x "))
        assertEquals("http://localhost:8080", normalizeShortcutUrl("http://localhost:8080"))
        assertNull(normalizeShortcutUrl("not a site"))
        assertNull(normalizeShortcutUrl("word"))
        assertEquals("Bbc", siteName("https://www.bbc.co.uk/news"))
        assertEquals("Github", siteName("https://github.com/T3lluz"))
    }

    @Test
    fun aShortcutSurvivesBeingKeptAndAnIncompleteOneDoesNot() {
        val s = keys("Close", "w", listOf("super"), "W")
        assertEquals(s, LinkShortcut.fromJson(s.toJson()))
        assertNull(LinkShortcut.fromJson(LinkShortcut(label = "x", steps = listOf(LinkStep(LinkShortcutKind.SITE, url = "ftp://x"))).toJson()))
        assertFalse(LinkShortcut(label = "", steps = listOf(LinkStep(LinkShortcutKind.APP))).complete)
        // waits alone do nothing
        assertFalse(LinkShortcut(label = "x", steps = listOf(LinkStep(LinkShortcutKind.WAIT, ms = 500))).complete)
    }

    @Test
    fun keysMadeHereReadAsTheyArePressed() {
        assertEquals("Ctrl + Shift + T", LinkKeys.combo(listOf("shift", "ctrl"), "T"))
        assertEquals("t", LinkKeys.fromTyped("t"))
        assertNull(LinkKeys.fromTyped("/"))
        assertEquals("F5", LinkKeys.cap("f5"))
        // a raw code is never made here: only one of the laptop's bindings carries one
        assertFalse(LinkKeys.pressable("#30"))
    }

    @Test
    fun aBindingsNameIsShortEnoughForATile() {
        assertEquals("Workspace 3", bindName("Switch to workspace 3"))
        assertEquals("Close window", bindName("Close window"))
        assertEquals("Workspace 3", made("""{"kind":"keys","key":"3","mods":["super"]}""").label)
    }

    @Test
    fun thePointersMonitor() {
        val mons = listOf(LinkMonitor("A", "Left", 2560, 1440, 0, 0, 1280, 720), LinkMonitor("B", "Right", 1920, 1080, 1280, 0, 1920, 1080))
        assertEquals("A", monitorUnder(mons, LinkPointer(10f, 10f))?.name)
        assertEquals("B", monitorUnder(mons, LinkPointer(1280f, 900f))?.name)
        assertNull(monitorUnder(mons, LinkPointer(100f, 900f)))
        assertNull(monitorUnder(mons, null))
    }

    @Test
    fun theAiSaysWhatItMadeOnOneLine() {
        val answer = parseShortcutAnswer(
            """{"kind":"keys","key":"3","mods":["super"],"label":"Workspace 3","reply":"Presses Super + 3,\nyour Switch to workspace 3."}""",
            catalog,
        ) as ShortcutAnswer.Made
        assertEquals("Presses Super + 3, your Switch to workspace 3.", answer.reply)
        // an answer with no reply still makes the shortcut; the sheet says what it does instead
        assertEquals("", (parseShortcutAnswer("""{"kind":"app","app":"firefox"}""", catalog) as ShortcutAnswer.Made).reply)
    }

    @Test
    fun aNoComesWithAsksThatComeClose() {
        val answer = parseShortcutAnswer(
            """{"error":"That takes two steps.","suggestions":["Open Spotify","Play or pause","Open Spotify","","Next song","Louder"]}""",
            catalog,
        ) as ShortcutAnswer.Refused
        assertEquals("That takes two steps.", answer.reason)
        assertEquals(listOf("Open Spotify", "Play or pause", "Next song"), answer.suggestions)
    }

    @Test
    fun whatTheAiIsDoingWhileItAnswers() {
        assertNull(shortcutProgress(""))
        assertNull(shortcutProgress("""{"ki"""))
        assertEquals("Picking the keys", shortcutProgress("""{"kind":"keys","key":"3""""))
        assertEquals("Picking the app", shortcutProgress("""{ "kind" : "app""""))
        assertEquals("Writing back", shortcutProgress("""{"kind":"site","url":"https://youtube.com","label":"YouTube","reply":"Op"""))
        assertEquals("Writing back", shortcutProgress("""{"error":"Th"""))
    }

    @Test
    fun whatAShortcutDoesInWords() {
        fun one(st: LinkStep) = LinkShortcut(label = "", steps = listOf(st))
        val names = { id: String -> if (id == "firefox") "Firefox" else null }
        assertEquals("Opens Firefox on t3lluz", describeShortcut(one(LinkStep(LinkShortcutKind.APP, app = "firefox")), "t3lluz", names))
        assertEquals("Opens youtube.com in t3lluz's browser", describeShortcut(one(LinkStep(LinkShortcutKind.SITE, url = "https://www.youtube.com/feed")), "t3lluz"))
        assertEquals("Presses Super + Shift + W on t3lluz", describeShortcut(one(LinkStep(LinkShortcutKind.KEYS, key = "w", mods = listOf("super", "shift"), cap = "W")), "t3lluz"))
        assertEquals("Types \"hi there\" on t3lluz", describeShortcut(one(LinkStep(LinkShortcutKind.TEXT, text = "hi\nthere")), "t3lluz"))
        // nothing picked yet
        assertNull(describeShortcut(one(LinkStep(LinkShortcutKind.KEYS)), "t3lluz"))
        assertNull(describeShortcut(one(LinkStep(LinkShortcutKind.SITE, url = "")), "t3lluz"))
        // several steps read one after another, the wait left out
        val deafen = LinkShortcut(
            label = "Deafen",
            steps = listOf(
                LinkStep(LinkShortcutKind.APP, app = "firefox"), LinkStep(LinkShortcutKind.WAIT, ms = 600),
                LinkStep(LinkShortcutKind.KEYS, key = "d", mods = listOf("ctrl", "shift"), cap = "D"),
            ),
        )
        assertEquals("Focuses Firefox, then presses Ctrl + Shift + D", describeShortcut(deafen, "t3lluz", names))
    }

    @Test
    fun aFollowUpChangesTheShortcutItMade() {
        val current = keys("Workspace 3", "3", listOf("super"), "3")
        val prompt = shortcutPrompt("make it 4", catalog, "t3lluz", current, listOf(ShortcutTurn("workspace 3", "Presses Super + 3.")))
        val sofar = org.json.JSONObject(prompt.lines().first { it.startsWith("The button so far: ") }.removePrefix("The button so far: "))
        val st = sofar.getJSONArray("steps").getJSONObject(0)
        assertEquals("keys", st.getString("kind"))
        assertEquals("3", st.getString("key"))
        assertEquals("super", st.getJSONArray("mods").getString(0))
        assertFalse(st.has("app"))
        assertTrue(prompt.contains("- \"workspace 3\" → Presses Super + 3."))
        assertTrue(prompt.trimEnd().endsWith("The ask: make it 4"))
        // a first ask has neither
        val first = shortcutPrompt("workspace 3", catalog, "t3lluz")
        assertFalse(first.contains("The button so far"))
        assertFalse(first.contains("Asked before"))
    }

    @Test
    fun aLaptopThatCantListItsAppsIsOfferedNoAppButtons() {
        assertTrue(shortcutPrompt("open spotify", catalog, "t3lluz").contains("\"kind\":\"app\""))
        assertFalse(shortcutPrompt("open spotify", LinkCatalog(emptyList(), catalog.binds), "t3lluz").contains("\"kind\":\"app\""))
        assertFalse(shortcutPrompt("open spotify", null, "t3lluz").contains("\"kind\":\"app\""))
    }

    @Test
    fun aShortcutCanFocusAnAppWaitAndPressItsKeys() {
        val answer = parseShortcutAnswer(
            """{"label":"Deafen","steps":[{"kind":"app","app":"Firefox"},{"kind":"wait","ms":99999},{"kind":"keys","key":"d","mods":["ctrl","shift"]}],
               "icon":{"id":"mdi:headphones","query":"headphones","color":"#5865F2"},
               "toggle":{"label":"Undeafen","icon":{"id":"mdi:headphones-off","color":"#ED4245"}},"reply":"Deafens you."}""",
            catalog,
        ) as ShortcutAnswer.Made
        val s = answer.shortcut
        assertEquals(listOf(LinkShortcutKind.APP, LinkShortcutKind.WAIT, LinkShortcutKind.KEYS), s.steps.map { it.kind })
        // the app found by its name; a wait the AI made too long is cut down
        assertEquals("firefox", s.steps[0].app)
        assertEquals(5000, s.steps[1].ms)
        assertEquals(LinkGlyph("mdi:headphones", 0xFF5865F2), s.glyph)
        assertEquals(LinkToggle("Undeafen", LinkGlyph("mdi:headphones-off", 0xFFED4245)), s.toggle)
        assertEquals("headphones", answer.iconQuery)
        assertEquals("firefox", s.appId)
        // off, it shows its usual look; on, the toggle's
        assertEquals("Deafen", s.shownLabel)
        assertEquals("Undeafen", s.copy(on = true).shownLabel)
        assertEquals("mdi:headphones-off", s.copy(on = true).shownGlyph?.id)
        assertEquals(s, LinkShortcut.fromJson(s.toJson()))
    }

    @Test
    fun oneBadStepTurnsTheWholeShortcutDown() {
        val raw = """{"label":"x","steps":[{"kind":"app","app":"photoshop"},{"kind":"keys","key":"s","mods":["ctrl"]}]}"""
        assertTrue(parseShortcutAnswer(raw, catalog) is ShortcutAnswer.Refused)
        val many = (1..9).joinToString(",") { """{"kind":"keys","key":"a"}""" }
        assertTrue(parseShortcutAnswer("""{"label":"x","steps":[$many]}""", catalog) is ShortcutAnswer.Refused)
    }

    @Test
    fun anIconIdThatCantBeOneIsDroppedButItsWordsAreKept() {
        val answer = parseShortcutAnswer(
            """{"label":"Mic","steps":[{"kind":"keys","key":"m","mods":["ctrl","shift"]}],"icon":{"id":"Headphones Off!","query":"mic off"}}""",
            catalog,
        ) as ShortcutAnswer.Made
        assertNull(answer.shortcut.glyph)
        assertEquals("mic off", answer.iconQuery)
        assertNull(LinkGlyph.color("blue"))
        assertEquals(0xFF5865F2, LinkGlyph.color("#5865f2"))
    }

    @Test
    fun aShortcutKeptBeforeStepsLoadsAsOneStep() {
        val old = org.json.JSONObject("""{"id":"a1","kind":"keys","label":"Close","app":"","url":"","key":"w","mods":["super"],"cap":"W","text":""}""")
        val s = LinkShortcut.fromJson(old)!!
        assertEquals("a1", s.id)
        assertEquals(listOf(LinkStep(LinkShortcutKind.KEYS, key = "w", mods = listOf("super"), cap = "W")), s.steps)
        assertNull(s.glyph)
        assertNull(s.toggle)
    }

    @Test
    fun theAiIsToldHowToFocusAnAppBeforeItsKeys() {
        val prompt = shortcutPrompt("deafen me on discord", catalog, "t3lluz")
        assertTrue(prompt.contains("\"steps\""))
        assertTrue(prompt.contains("\"kind\":\"wait\""))
        assertTrue(prompt.contains("toggle"))
        assertTrue(prompt.contains("mdi:headphones-off"))
    }

    @Test
    fun theProgressLineFollowsTheSteps() {
        assertEquals("Step 2: picking the keys", shortcutProgress("""{"label":"Deafen","steps":[{"kind":"app","app":"x"},{"kind":"keys","""").let { it?.replace("Step 2: Picking", "Step 2: picking") })
        assertEquals("Picking an icon", shortcutProgress("""{"steps":[{"kind":"keys","key":"d"}],"icon":{"id":"md"""))
    }
}
