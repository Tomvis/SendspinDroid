package com.sendspindroid.playback

import android.media.AudioManager
import com.sendspindroid.model.PlaybackStateType

/** Something else wants the audio output. */
enum class AudioInterruption {
    FOCUS_GAIN,
    FOCUS_LOSS,
    FOCUS_LOSS_TRANSIENT,
    FOCUS_LOSS_TRANSIENT_CAN_DUCK,
    BECOMING_NOISY;

    val isTransientLoss: Boolean
        get() = this == FOCUS_LOSS_TRANSIENT || this == FOCUS_LOSS_TRANSIENT_CAN_DUCK

    companion object {
        /** Maps an `AudioManager.AUDIOFOCUS_*` change, or null if it is not one we act on. */
        fun fromFocusChange(focusChange: Int): AudioInterruption? = when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> FOCUS_GAIN
            AudioManager.AUDIOFOCUS_LOSS -> FOCUS_LOSS
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> FOCUS_LOSS_TRANSIENT
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> FOCUS_LOSS_TRANSIENT_CAN_DUCK
            else -> null
        }
    }
}

enum class InterruptionAction {
    /** Mute this device now and send the controller `pause` (pauses the whole group). */
    PAUSE_ON_SERVER,
    /** Un-mute and send the controller `play`: the call we paused for is over. */
    RESUME_ON_SERVER,
    /** Silence this device only. Audio keeps draining in sync; the server is not told. */
    MUTE_LOCALLY,
    UNMUTE_LOCALLY,
    /** Mute and report `external_source`: another app owns the output for good. */
    BECOME_UNAVAILABLE,
    NONE,
}

/**
 * Decides what to do when another app takes the audio output.
 *
 * - A call (transient focus loss while the audio mode is a call mode) pauses the
 *   group on the server, and resumes it afterwards only if that pause still stands.
 * - Any other transient loss (a video, a notification, navigation) mutes this
 *   device only, so un-muting is instant and exactly in time.
 * - Permanent loss reports the player as unavailable.
 * - An output disconnect (headphones unplugged) pauses the group and never resumes.
 *
 * Whenever the `pause` command cannot be sent, the device is muted instead.
 */
object AudioInterruptionPolicy {

    fun isCallMode(audioMode: Int): Boolean =
        audioMode == AudioManager.MODE_IN_CALL ||
            audioMode == AudioManager.MODE_IN_COMMUNICATION ||
            audioMode == AudioManager.MODE_RINGTONE ||
            audioMode == AudioManager.MODE_CALL_SCREENING

    /**
     * @param serverState the group's playback state as last reported by the server
     * @param canSendPause connected, controller role active and `pause` supported
     * @param pausedForCall we sent `pause` for a call and nobody has changed
     *   playback since (see [callPauseSuperseded])
     */
    fun decide(
        event: AudioInterruption,
        audioMode: Int,
        serverState: PlaybackStateType,
        canSendPause: Boolean,
        pausedForCall: Boolean,
    ): InterruptionAction {
        val canPause = canSendPause && serverState == PlaybackStateType.PLAYING
        return when (event) {
            AudioInterruption.FOCUS_GAIN ->
                if (pausedForCall) InterruptionAction.RESUME_ON_SERVER else InterruptionAction.UNMUTE_LOCALLY
            AudioInterruption.FOCUS_LOSS -> InterruptionAction.BECOME_UNAVAILABLE
            AudioInterruption.FOCUS_LOSS_TRANSIENT,
            AudioInterruption.FOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                if (isCallMode(audioMode) && canPause) InterruptionAction.PAUSE_ON_SERVER
                else InterruptionAction.MUTE_LOCALLY
            AudioInterruption.BECOMING_NOISY -> when {
                canPause -> InterruptionAction.PAUSE_ON_SERVER
                serverState == PlaybackStateType.PLAYING -> InterruptionAction.MUTE_LOCALLY
                else -> InterruptionAction.NONE
            }
        }
    }

    /**
     * True when a server playback-state change shows that our pause-for-a-call no
     * longer stands, so we must not resume when the call ends: someone stopped
     * playback, or playback left the paused state (it was resumed, even if it has
     * been paused again since).
     */
    fun callPauseSuperseded(previous: PlaybackStateType, new: PlaybackStateType): Boolean =
        new == PlaybackStateType.STOPPED ||
            (previous == PlaybackStateType.PAUSED && new != PlaybackStateType.PAUSED)

    fun modeName(audioMode: Int): String = when (audioMode) {
        AudioManager.MODE_NORMAL -> "NORMAL"
        AudioManager.MODE_RINGTONE -> "RINGTONE"
        AudioManager.MODE_IN_CALL -> "IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
        AudioManager.MODE_CALL_SCREENING -> "CALL_SCREENING"
        else -> audioMode.toString()
    }
}
