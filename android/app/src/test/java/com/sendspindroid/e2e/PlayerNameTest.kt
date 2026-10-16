package com.sendspindroid.e2e

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The server learns the player's name from `client/hello` only ("the
 * `client/hello` value is authoritative", connection.md): a rename reaches
 * the next hello.
 */
class PlayerNameTest : E2ETestBase() {

    private fun helloNames(): List<String> {
        // The client sends on its own timer thread: the hello may not have
        // landed when the handshake call returns.
        fakeTransport.hasSentMessageContaining("\"type\":\"client/hello\"")
        return fakeTransport.sentTextMessages
            .filter { it.contains("\"type\":\"client/hello\"") }
            .map { Regex("\"name\":\"([^\"]*)\"").find(it)!!.groupValues[1] }
    }

    @Test
    fun `the hello carries the name the client was built with`() {
        connectAndHandshake()

        assertEquals(listOf("E2ETestDevice"), helloNames())
    }

    @Test
    fun `a renamed client's next hello carries the new name`() {
        client.setDeviceName("Kitchen")

        connectAndHandshake()

        assertEquals(listOf("Kitchen"), helloNames())
    }
}
