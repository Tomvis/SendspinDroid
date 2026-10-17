package com.sendspindroid.e2e

import com.sendspindroid.UserSettings
import io.mockk.every
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Album colors switch decides whether `client/hello` lists the role
 * messaging.md defines as "`color@v1` - receives colors derived from the
 * current audio": a server activates only roles the client lists, so with
 * the switch off no colors are sent at all.
 */
class ServerColorsHelloTest : E2ETestBase() {

    private fun helloRoles(): List<String> {
        // The client sends on its own timer thread: the hello may not have
        // landed when the handshake call returns.
        fakeTransport.hasSentMessageContaining("\"type\":\"client/hello\"")
        val hello = fakeTransport.sentTextMessages.single { it.contains("\"type\":\"client/hello\"") }
        val roles = Regex("\"supported_roles\":\\[([^\\]]*)]").find(hello)!!.groupValues[1]
        return roles.split(",").map { it.trim('"') }
    }

    @Test
    fun `with album colors on the hello lists color@v1`() {
        every { UserSettings.serverColors } returns true // fork: off by default (HW-125)
        connectAndHandshake()

        assertEquals(listOf("player@v1", "controller@v1", "metadata@v1", "artwork@v1", "color@v1"), helloRoles())
    }

    @Test
    fun `with album colors off the hello leaves color@v1 out`() {
        every { UserSettings.serverColors } returns false

        connectAndHandshake()

        assertEquals(listOf("player@v1", "controller@v1", "metadata@v1", "artwork@v1"), helloRoles())
    }
}
