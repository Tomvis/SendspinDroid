package com.sendspindroid.e2e

import io.mockk.verify
import org.junit.Test

/**
 * `stream/end` and `stream/clear` carry UNVERSIONED role names.
 *
 * `messaging.md` defines `stream/end`'s field as "roles to end streams for
 * ('player', 'artwork', 'visualizer')" and `stream/clear`'s as "which roles to
 * clear: 'player', 'visualizer', or both. If omitted, clears both roles".
 * `supported_roles` and `active_roles` are versioned (`player@v1`); these two
 * fields deliberately are not.
 *
 * Two defects followed from missing that asymmetry:
 *
 *  - `stream/end` compared the array against `"player@v1"`, which never
 *    matched, so a server sending `roles: ["player"]` had its stream/end
 *    silently dropped and the stream never ended.
 *  - `stream/clear` was dispatched with no payload at all, so every clear was
 *    global: a visualizer-only clear reset the decoder and discarded the whole
 *    audio queue mid-track.
 */
class StreamRoleNamesTest : E2ETestBase() {

    private fun send(json: String) {
        fakeTransport.simulateTextMessage(json)
    }

    @Test
    fun `stream-end with the unversioned player role ends the stream`() {
        connectAndHandshake()
        send("""{"type":"stream/end","payload":{"roles":["player"]}}""")
        verify(exactly = 1) { mockCallback.onStreamEnd() }
    }

    /** A server that sends the versioned form is still understood. */
    @Test
    fun `stream-end with the versioned player role also ends the stream`() {
        connectAndHandshake()
        send("""{"type":"stream/end","payload":{"roles":["player@v1"]}}""")
        verify(exactly = 1) { mockCallback.onStreamEnd() }
    }

    @Test
    fun `stream-end with no roles ends all streams`() {
        connectAndHandshake()
        send("""{"type":"stream/end","payload":{}}""")
        verify(exactly = 1) { mockCallback.onStreamEnd() }
    }

    /** An artwork-only end must not stop audio. */
    @Test
    fun `stream-end for other roles leaves the player alone`() {
        connectAndHandshake()
        send("""{"type":"stream/end","payload":{"roles":["artwork","visualizer"]}}""")
        verify(exactly = 0) { mockCallback.onStreamEnd() }
    }

    @Test
    fun `stream-clear with the unversioned player role flushes audio`() {
        connectAndHandshake()
        send("""{"type":"stream/clear","payload":{"roles":["player"]}}""")
        verify(exactly = 1) { mockCallback.onStreamClear() }
    }

    @Test
    fun `stream-clear with no roles flushes audio`() {
        connectAndHandshake()
        send("""{"type":"stream/clear","payload":{}}""")
        verify(exactly = 1) { mockCallback.onStreamClear() }
    }

    /**
     * The defect that mattered most: a visualizer-only clear used to reset the
     * decoder and discard the entire chunk queue mid-track, because the
     * dispatcher never passed the payload through.
     */
    @Test
    fun `stream-clear for the visualizer alone does not touch audio`() {
        connectAndHandshake()
        send("""{"type":"stream/clear","payload":{"roles":["visualizer"]}}""")
        verify(exactly = 0) { mockCallback.onStreamClear() }
    }
}
