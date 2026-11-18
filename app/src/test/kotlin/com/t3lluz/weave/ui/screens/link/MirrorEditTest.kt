package com.t3lluz.weave.ui.screens.link

import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorEditTest {
    @Test
    fun typingOnOnlyAddsTheNewLetters() {
        assertEquals(0 to "e", mirrorEdit("th", "the"))
        assertEquals(0 to "hello ", mirrorEdit("", "hello "))
    }

    @Test
    fun aCorrectionGoesBackToWhereTheWordsPartWays() {
        assertEquals(2 to "he ", mirrorEdit("teh", "the "))
        // a swiped word replaced by the keyboard's second guess
        assertEquals(5 to "there", mirrorEdit("hello where", "hello there"))
    }

    @Test
    fun aDeletionIsBackspacesOnly() {
        assertEquals(1 to "", mirrorEdit("cat", "ca"))
        assertEquals(3 to "", mirrorEdit("cat", ""))
    }
}
