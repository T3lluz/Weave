package com.t3lluz.weave.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicModelsTest {
    @Test
    fun `4-x models fold under older, 5-x ones stay`() {
        assertTrue(AnthropicModels.isOld("claude-opus-4-8"))
        assertTrue(AnthropicModels.isOld("claude-sonnet-4-5-20250929"))
        assertFalse(AnthropicModels.isOld("claude-opus-5-5"))
        assertFalse(AnthropicModels.isOld("claude-fable-5-2"))
    }

    @Test
    fun `an id the list never named still reads as a name`() {
        assertEquals("Claude Opus 4.5", AnthropicModels.prettyName("claude-opus-4-5-20251101"))
        assertEquals("Claude Fable 5.2", AnthropicModels.prettyName("claude-fable-5-2"))
    }

    @Test
    fun `a saved model from the account's list is kept, blank goes to the default`() {
        assertEquals("claude-opus-4-8", AnthropicModels.resolveId("claude-opus-4-8"))
        assertEquals(AnthropicModels.DEFAULT_ID, AnthropicModels.resolveId(""))
        assertEquals(AnthropicModels.DEFAULT_ID, AnthropicModels.resolveId(null))
    }

    @Test
    fun `models that take no effort offer no depths`() {
        assertEquals(AnthropicModels.EFFORTS, AnthropicModels.effortsFor("claude-opus-5-5"))
        assertTrue(AnthropicModels.effortsFor("claude-haiku-4-5-20251001").isEmpty())
    }
}
