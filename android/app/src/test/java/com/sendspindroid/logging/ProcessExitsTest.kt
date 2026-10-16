package com.sendspindroid.logging

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class ProcessExitsTest {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // 2026-08-19 13:32:45 UTC
    private val killedAt = 1_787_146_365_000L

    @Test
    fun `format lists each exit with its time, reason, importance, memory and description`() {
        val exits = listOf(
            ProcessExits.Exit(killedAt, 3, 400, 81_920, 163_840, "Watermark Pressure"),
            ProcessExits.Exit(killedAt - 60_000, 10, 100, 0, 0, "remove task"),
        )

        assertEquals(
            "Recent exits (newest first):\n" +
                "  2026-08-19 13:32:45 LOW_MEMORY importance=400 pss=81920kB rss=163840kB: Watermark Pressure\n" +
                "  2026-08-19 13:31:45 USER_REQUESTED importance=100: remove task",
            ProcessExits.format(exits, dateFormat),
        )
    }

    @Test
    fun `format leaves out a description the system did not give`() {
        val exits = listOf(ProcessExits.Exit(killedAt, 2, 125, 0, 0, null))

        assertEquals(
            "Recent exits (newest first):\n" +
                "  2026-08-19 13:32:45 SIGNALED importance=125",
            ProcessExits.format(exits, dateFormat),
        )
    }

    @Test
    fun `format says so when there are no exits`() {
        assertEquals("Recent exits: none recorded", ProcessExits.format(emptyList(), dateFormat))
    }

    @Test
    fun `reasonName names the reasons of every release`() {
        assertEquals("UNKNOWN", ProcessExits.reasonName(0))
        assertEquals("CRASH", ProcessExits.reasonName(4))
        assertEquals("ANR", ProcessExits.reasonName(6))
        assertEquals("EXCESSIVE_RESOURCE_USAGE", ProcessExits.reasonName(9))
        assertEquals("OTHER", ProcessExits.reasonName(13))
        // Added in API 33 and 34: still named on an older device's build.
        assertEquals("FREEZER", ProcessExits.reasonName(14))
        assertEquals("PACKAGE_UPDATED", ProcessExits.reasonName(16))
    }

    @Test
    fun `reasonName falls back to the number for a reason it does not know`() {
        assertEquals("17", ProcessExits.reasonName(17))
    }
}
