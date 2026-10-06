package com.sendspindroid.sendspin

import com.sendspindroid.sendspin.protocol.message.MessageBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * pairing.md: the dynamic descriptor carries out_channels and formats, and a
 * client MUST NOT advertise both static and dynamic pairing codes.
 */
class DynamicPairMethodDescriptorTest {

    private fun hello(methods: List<MessageBuilder.PairMethodDescriptor>) = MessageBuilder.buildClientHello(
        deviceName = "Test",
        bufferCapacity = 1,
        manufacturer = "Test",
        supportedFormats = emptyList(),
        supportedPairMethods = methods,
    )

    @Test
    fun `dynamic descriptor advertises display and digits`() {
        val json = hello(
            listOf(MessageBuilder.PairMethodDescriptor.PAIRING_PSK, MessageBuilder.PairMethodDescriptor.DYNAMIC_PAIRING_CODE)
        )
        assertTrue(json.contains("dynamic_pairing_code"))
        assertTrue(json.contains("\"out_channels\":[\"display\"]"))
        assertTrue(json.contains("\"formats\":[\"digits\"]"))
    }

    /** pairing.md: `locations` belongs to the secret-based methods only. */
    @Test
    fun `dynamic descriptor carries no locations`() {
        val json = hello(listOf(MessageBuilder.PairMethodDescriptor.DYNAMIC_PAIRING_CODE))
        assertFalse(json.contains("locations"))
        assertTrue(json.contains("\"dynamic_pairing_code\":{\"out_channels\""))
    }

    /** Never speaker: that would oblige us to accept a digit audio pack. */
    @Test
    fun `never advertises the speaker channel`() {
        val json = hello(listOf(MessageBuilder.PairMethodDescriptor.DYNAMIC_PAIRING_CODE))
        assertFalse(json.contains("speaker"))
        assertFalse(json.contains("digit_audio"))
    }

    /** Offering dynamic forecloses static; both together is non-conformant. */
    @Test
    fun `never advertises the static pairing code`() {
        val json = hello(listOf(MessageBuilder.PairMethodDescriptor.DYNAMIC_PAIRING_CODE))
        assertFalse(json.contains("static_pairing_code"))
    }

    @Test
    fun `pairing psk remains advertised alongside it`() {
        val json = hello(
            listOf(MessageBuilder.PairMethodDescriptor.PAIRING_PSK, MessageBuilder.PairMethodDescriptor.DYNAMIC_PAIRING_CODE)
        )
        assertTrue(json.contains("pairing_psk"))
    }
}
