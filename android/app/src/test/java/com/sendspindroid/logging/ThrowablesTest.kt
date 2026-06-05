package com.sendspindroid.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [throwableSummary], the OOM-safe one-line Throwable formatter used on
 * reconnect/audio hot paths instead of logging full stack traces.
 */
class ThrowablesTest {

    @Test
    fun typeAndMessage() {
        val summary = throwableSummary(IllegalStateException("boom"))
        assertEquals("IllegalStateException: boom", summary)
    }

    @Test
    fun blankMessageYieldsTypeOnly() {
        assertEquals("RuntimeException", throwableSummary(RuntimeException()))
        assertEquals("IllegalArgumentException", throwableSummary(IllegalArgumentException("")))
    }

    @Test
    fun keepsOnlyFirstLineOfMultilineMessage() {
        val summary = throwableSummary(RuntimeException("first line\nsecond line\nthird"))
        assertEquals("RuntimeException: first line", summary)
    }

    @Test
    fun truncatesLongMessageToMaxChars() {
        val long = "x".repeat(500)
        val summary = throwableSummary(RuntimeException(long), maxChars = 10)
        assertEquals("RuntimeException: " + "x".repeat(10), summary)
    }

    @Test
    fun doesNotIncludeStackTrace() {
        val summary = throwableSummary(IllegalStateException("dead object"))
        assertTrue("summary should be a single line", !summary.contains('\n'))
        assertTrue("summary should not contain stack frames", !summary.contains("\tat "))
    }
}
