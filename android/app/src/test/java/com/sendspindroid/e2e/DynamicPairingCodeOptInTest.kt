package com.sendspindroid.e2e

import android.util.Log
import com.sendspindroid.UserSettings
import com.sendspindroid.sendspin.SendSpin
import com.sendspindroid.sendspin.protocol.message.MessageBuilder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FIX 1 regression guard: `dynamic_pairing_code` is opt-in and defaults off.
 *
 * Advertising it unconditionally broke the handshake entirely against
 * aiosendspin 9.1.1 (Music Assistant's shipped version): its `PairMethod`
 * enum predates `dynamic_pairing_code`, so `client/hello` fails to
 * deserialize with `InvalidFieldValue` - not a degraded feature, a dead
 * player. With the setting off, `client/hello` must be byte-identical to
 * before this branch: no `dynamic_pairing_code` anywhere, and
 * `supported_pair_methods` names only `pairing_psk`.
 *
 * Exercised directly against [SendSpin]'s protected pair-method accessors
 * (via reflection) rather than through the full E2E transport/handshake fake:
 * the fakes here never drive a real Noise handshake to completion, so
 * `client/hello` never actually reaches the wire through that path. Testing
 * the accessors that feed `client/hello` - and re-running the real
 * [MessageBuilder.buildClientHello] over their output - covers the same
 * property without that gap.
 */
class DynamicPairingCodeOptInTest {

    private lateinit var client: SendSpin

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockkObject(UserSettings)
        every { UserSettings.getUnpairedAccessEnabled() } returns true
        every { UserSettings.getDynamicPairingCodeEnabled() } returns false

        client = SendSpin("TestDevice", mockk(relaxed = true))
    }

    @After
    fun tearDown() {
        client.destroy()
        unmockkAll()
    }

    @Suppress("UNCHECKED_CAST")
    private fun offeredPairMethods(): Set<String> {
        val m = SendSpin::class.java.getDeclaredMethod("offeredPairMethods")
        m.isAccessible = true
        return m.invoke(client) as Set<String>
    }

    @Suppress("UNCHECKED_CAST")
    private fun supportedPairMethods(): List<MessageBuilder.PairMethodDescriptor> {
        val m = SendSpin::class.java.getDeclaredMethod("getSupportedPairMethods")
        m.isAccessible = true
        return m.invoke(client) as List<MessageBuilder.PairMethodDescriptor>
    }

    private fun hello(methods: List<MessageBuilder.PairMethodDescriptor>) = MessageBuilder.buildClientHello(
        deviceName = "TestDevice",
        bufferCapacity = 1,
        manufacturer = "Test",
        supportedFormats = emptyList(),
        supportedPairMethods = methods,
    )

    @Test
    fun `offeredPairMethods contains only pairing_psk when dynamic pairing is off`() {
        assertEquals(setOf("pairing_psk"), offeredPairMethods())
    }

    @Test
    fun `getSupportedPairMethods omits the dynamic descriptor when off`() {
        val methods = supportedPairMethods()
        assertEquals(listOf(MessageBuilder.PairMethodDescriptor.PAIRING_PSK), methods)
    }

    @Test
    fun `dynamic_pairing_code does not appear anywhere in client-hello when off`() {
        val json = hello(supportedPairMethods())
        assertFalse(
            "the dynamic descriptor must not appear anywhere in client/hello " +
                "when dynamicPairingCodeEnabled is false",
            json.contains("dynamic_pairing_code")
        )
        assertTrue(json.contains("\"pairing_psk\""))
    }

    @Test
    fun `dynamic_pairing_code is offered once explicitly enabled`() {
        every { UserSettings.getDynamicPairingCodeEnabled() } returns true

        assertEquals(setOf("pairing_psk", "dynamic_pairing_code"), offeredPairMethods())
        assertTrue(supportedPairMethods().contains(MessageBuilder.PairMethodDescriptor.DYNAMIC_PAIRING_CODE))
        assertTrue(hello(supportedPairMethods()).contains("dynamic_pairing_code"))
    }
}
