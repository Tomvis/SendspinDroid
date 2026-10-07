package com.sendspindroid.playback

import android.media.AudioManager
import com.sendspindroid.model.PlaybackStateType
import com.sendspindroid.playback.AudioInterruption.BECOMING_NOISY
import com.sendspindroid.playback.AudioInterruption.FOCUS_GAIN
import com.sendspindroid.playback.AudioInterruption.FOCUS_LOSS
import com.sendspindroid.playback.AudioInterruption.FOCUS_LOSS_TRANSIENT
import com.sendspindroid.playback.AudioInterruption.FOCUS_LOSS_TRANSIENT_CAN_DUCK
import com.sendspindroid.playback.InterruptionAction.BECOME_UNAVAILABLE
import com.sendspindroid.playback.InterruptionAction.MUTE_LOCALLY
import com.sendspindroid.playback.InterruptionAction.NONE
import com.sendspindroid.playback.InterruptionAction.PAUSE_ON_SERVER
import com.sendspindroid.playback.InterruptionAction.RESUME_ON_SERVER
import com.sendspindroid.playback.InterruptionAction.UNMUTE_LOCALLY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the player does when another app takes the audio output. These drive
 * the real [AudioInterruptionPolicy] that PlaybackService's focus and
 * becoming-noisy handlers call.
 */
class AudioInterruptionPolicyTest {

    private val callModes = listOf(
        AudioManager.MODE_IN_CALL,
        AudioManager.MODE_IN_COMMUNICATION,
        AudioManager.MODE_RINGTONE,
        AudioManager.MODE_CALL_SCREENING,
    )
    private val transientLosses = listOf(FOCUS_LOSS_TRANSIENT, FOCUS_LOSS_TRANSIENT_CAN_DUCK)

    private fun decide(
        event: AudioInterruption,
        audioMode: Int = AudioManager.MODE_NORMAL,
        serverState: PlaybackStateType = PlaybackStateType.PLAYING,
        canSendPause: Boolean = true,
        pausedForCall: Boolean = false,
    ) = AudioInterruptionPolicy.decide(event, audioMode, serverState, canSendPause, pausedForCall)

    // ---- A phone or VoIP call ----

    @Test
    fun `call while playing pauses the group on the server`() {
        for (mode in callModes) for (loss in transientLosses) {
            assertEquals("mode=$mode $loss", PAUSE_ON_SERVER, decide(loss, audioMode = mode))
        }
    }

    @Test
    fun `call ending resumes the group if our pause still stands`() {
        assertEquals(
            RESUME_ON_SERVER,
            decide(FOCUS_GAIN, serverState = PlaybackStateType.PAUSED, pausedForCall = true),
        )
        // The call was so short that the server has not reported our pause yet.
        assertEquals(
            RESUME_ON_SERVER,
            decide(FOCUS_GAIN, serverState = PlaybackStateType.PLAYING, pausedForCall = true),
        )
    }

    @Test
    fun `call while already paused or stopped mutes and does not pause`() {
        for (state in PlaybackStateType.values().filter { it != PlaybackStateType.PLAYING }) {
            assertEquals(
                "$state",
                MUTE_LOCALLY,
                decide(FOCUS_LOSS_TRANSIENT, audioMode = AudioManager.MODE_IN_CALL, serverState = state),
            )
        }
    }

    @Test
    fun `call ending does not resume when we did not pause for it`() {
        for (state in PlaybackStateType.values()) {
            assertEquals("$state", UNMUTE_LOCALLY, decide(FOCUS_GAIN, serverState = state))
        }
    }

    @Test
    fun `call when pause cannot be sent mutes for the duration`() {
        for (mode in callModes) {
            assertEquals(
                "mode=$mode",
                MUTE_LOCALLY,
                decide(FOCUS_LOSS_TRANSIENT, audioMode = mode, canSendPause = false),
            )
        }
        assertEquals(UNMUTE_LOCALLY, decide(FOCUS_GAIN, canSendPause = false))
    }

    // ---- Our pause superseded by someone else during the call ----

    @Test
    fun `resume from elsewhere during the call supersedes our pause`() {
        assertTrue(
            AudioInterruptionPolicy.callPauseSuperseded(PlaybackStateType.PAUSED, PlaybackStateType.PLAYING)
        )
    }

    @Test
    fun `stop from elsewhere supersedes our pause even before it was confirmed`() {
        assertTrue(
            AudioInterruptionPolicy.callPauseSuperseded(PlaybackStateType.PAUSED, PlaybackStateType.STOPPED)
        )
        assertTrue(
            AudioInterruptionPolicy.callPauseSuperseded(PlaybackStateType.PLAYING, PlaybackStateType.STOPPED)
        )
    }

    @Test
    fun `server confirming or repeating our pause does not supersede it`() {
        // Our pause landing.
        assertFalse(
            AudioInterruptionPolicy.callPauseSuperseded(PlaybackStateType.PLAYING, PlaybackStateType.PAUSED)
        )
        // A repeated report of the same state.
        assertFalse(
            AudioInterruptionPolicy.callPauseSuperseded(PlaybackStateType.PAUSED, PlaybackStateType.PAUSED)
        )
        // A 'playing' report already in flight when we sent the pause.
        assertFalse(
            AudioInterruptionPolicy.callPauseSuperseded(PlaybackStateType.PLAYING, PlaybackStateType.PLAYING)
        )
    }

    @Test
    fun `superseded pause is not resumed when the call ends`() {
        // The service clears pausedForCall when callPauseSuperseded is true, so
        // the gain is decided with pausedForCall = false whatever the state.
        var pausedForCall = true
        if (AudioInterruptionPolicy.callPauseSuperseded(PlaybackStateType.PAUSED, PlaybackStateType.PLAYING)) {
            pausedForCall = false
        }
        // Resumed elsewhere, then paused again elsewhere: still not ours to resume.
        if (AudioInterruptionPolicy.callPauseSuperseded(PlaybackStateType.PLAYING, PlaybackStateType.PAUSED)) {
            pausedForCall = false
        }
        assertEquals(
            UNMUTE_LOCALLY,
            decide(FOCUS_GAIN, serverState = PlaybackStateType.PAUSED, pausedForCall = pausedForCall),
        )
    }

    // ---- Other transient audio: a video, a notification, navigation ----

    @Test
    fun `transient loss in normal mode mutes locally and tells the server nothing`() {
        for (loss in transientLosses) for (state in PlaybackStateType.values()) {
            assertEquals("$loss $state", MUTE_LOCALLY, decide(loss, serverState = state))
        }
    }

    @Test
    fun `focus returning after other audio un-mutes`() {
        assertEquals(UNMUTE_LOCALLY, decide(FOCUS_GAIN))
    }

    @Test
    fun `mode turning into a call after the loss upgrades the mute to a pause`() {
        // Focus loss arrives first, in normal mode ...
        assertEquals(MUTE_LOCALLY, decide(FOCUS_LOSS_TRANSIENT, audioMode = AudioManager.MODE_NORMAL))
        // ... and the same loss is decided again once the mode is a call mode.
        assertEquals(PAUSE_ON_SERVER, decide(FOCUS_LOSS_TRANSIENT, audioMode = AudioManager.MODE_RINGTONE))
    }

    // ---- Permanent loss ----

    @Test
    fun `permanent loss reports unavailable whatever else is going on`() {
        for (mode in callModes + AudioManager.MODE_NORMAL) {
            for (state in PlaybackStateType.values()) for (canSend in listOf(true, false)) {
                assertEquals(
                    BECOME_UNAVAILABLE,
                    decide(FOCUS_LOSS, audioMode = mode, serverState = state, canSendPause = canSend, pausedForCall = true),
                )
            }
        }
    }

    // ---- Headphones unplugged ----

    @Test
    fun `becoming noisy while playing pauses the group`() {
        assertEquals(PAUSE_ON_SERVER, decide(BECOMING_NOISY))
        // The audio mode is irrelevant to an output disconnect.
        assertEquals(PAUSE_ON_SERVER, decide(BECOMING_NOISY, audioMode = AudioManager.MODE_IN_CALL))
    }

    @Test
    fun `becoming noisy when pause cannot be sent mutes instead`() {
        assertEquals(MUTE_LOCALLY, decide(BECOMING_NOISY, canSendPause = false))
    }

    @Test
    fun `becoming noisy when nothing is playing does nothing`() {
        for (state in PlaybackStateType.values().filter { it != PlaybackStateType.PLAYING }) {
            for (canSend in listOf(true, false)) {
                assertEquals("$state", NONE, decide(BECOMING_NOISY, serverState = state, canSendPause = canSend))
            }
        }
    }

    @Test
    fun `no interruption resumes after becoming noisy`() {
        // The service sets pausedForCall only for a transient focus loss, so a
        // later focus gain is decided without it and only un-mutes.
        assertFalse(BECOMING_NOISY.isTransientLoss)
        assertEquals(UNMUTE_LOCALLY, decide(FOCUS_GAIN, serverState = PlaybackStateType.PAUSED))
    }

    // ---- Mapping from the platform ----

    @Test
    fun `focus changes map to interruptions`() {
        assertEquals(FOCUS_GAIN, AudioInterruption.fromFocusChange(AudioManager.AUDIOFOCUS_GAIN))
        assertEquals(FOCUS_LOSS, AudioInterruption.fromFocusChange(AudioManager.AUDIOFOCUS_LOSS))
        assertEquals(
            FOCUS_LOSS_TRANSIENT,
            AudioInterruption.fromFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT),
        )
        assertEquals(
            FOCUS_LOSS_TRANSIENT_CAN_DUCK,
            AudioInterruption.fromFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK),
        )
        assertNull(AudioInterruption.fromFocusChange(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT))
    }

    @Test
    fun `only call modes count as a call`() {
        for (mode in callModes) assertTrue("mode=$mode", AudioInterruptionPolicy.isCallMode(mode))
        assertFalse(AudioInterruptionPolicy.isCallMode(AudioManager.MODE_NORMAL))
    }
}
