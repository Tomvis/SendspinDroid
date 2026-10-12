package com.sendspindroid.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The spec's "Calculating current track position": the reported progress plus
 * the elapsed time scaled by playback_speed, clamped to the track.
 */
class TrackPositionTest {

    @Test
    fun normalSpeed_addsTheElapsedTime() {
        assertEquals(70_000L, trackPositionMs(60_000, elapsedMs = 10_000, playbackSpeed = 1000, durationMs = 240_000))
    }

    @Test
    fun fasterSpeed_scalesTheElapsedTime() {
        assertEquals(75_000L, trackPositionMs(60_000, elapsedMs = 10_000, playbackSpeed = 1500, durationMs = 240_000))
    }

    @Test
    fun slowerSpeed_scalesTheElapsedTime() {
        assertEquals(65_000L, trackPositionMs(60_000, elapsedMs = 10_000, playbackSpeed = 500, durationMs = 240_000))
    }

    @Test
    fun speedZero_isPaused() {
        assertEquals(60_000L, trackPositionMs(60_000, elapsedMs = 10_000, playbackSpeed = 0, durationMs = 240_000))
    }

    @Test
    fun knownDuration_capsThePosition() {
        assertEquals(240_000L, trackPositionMs(235_000, elapsedMs = 10_000, playbackSpeed = 1000, durationMs = 240_000))
    }

    @Test
    fun unknownDuration_doesNotCap() {
        assertEquals(245_000L, trackPositionMs(235_000, elapsedMs = 10_000, playbackSpeed = 1000, durationMs = 0))
    }

    @Test
    fun neverNegative() {
        assertEquals(0L, trackPositionMs(1_000, elapsedMs = -5_000, playbackSpeed = 1000, durationMs = 240_000))
    }
}
