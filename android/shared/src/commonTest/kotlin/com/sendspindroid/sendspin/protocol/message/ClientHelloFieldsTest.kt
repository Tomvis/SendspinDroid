package com.sendspindroid.sendspin.protocol.message

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The shape of `client/hello` (`messaging.md#client--server-clienthello`).
 *
 * aiosendspin 10.0.0 classifies a client as speaking the pre-1.0.0-rc1 wire
 * from this one message: a `trust_level`, or `supported_commands` inside
 * `player@v1_support`, switches it to the 9-byte audio header and the old
 * fragment framing, and a strict server rejects `artwork@v1_support` and a
 * list-shaped `supported_pair_methods` outright. So the fields that must NOT be
 * here matter as much as the ones that must.
 *
 * `unpaired_access` is the load-bearing positive one. The spec permits
 * `'playback'` on an unpaired session "only when the client has unpaired access
 * enabled"; a client that never advertises the field leaves an unpaired server
 * no choice but to send empty activities.
 */
class ClientHelloFieldsTest {

    private val formats = listOf(MessageBuilder.FormatEntry("pcm", 48000, 2, 16))

    private fun helloText(
        unpairedAccess: Boolean = true,
        lowMemoryMode: Boolean = false,
        pairMethods: List<MessageBuilder.PairMethodDescriptor> =
            listOf(MessageBuilder.PairMethodDescriptor.PAIRING_PSK),
    ) = MessageBuilder.buildClientHello(
        deviceName = "Tablet",
        bufferCapacity = 1_680_000,
        manufacturer = "test",
        supportedFormats = formats,
        lowMemoryMode = lowMemoryMode,
        softwareVersion = "2.0.0",
        unpairedAccessEnabled = unpairedAccess,
        supportedPairMethods = pairMethods,
    )

    private fun hello(
        unpairedAccess: Boolean = true,
        lowMemoryMode: Boolean = false,
        pairMethods: List<MessageBuilder.PairMethodDescriptor> =
            listOf(MessageBuilder.PairMethodDescriptor.PAIRING_PSK),
    ): JsonObject = Json.parseToJsonElement(helloText(unpairedAccess, lowMemoryMode, pairMethods))
        .jsonObject["payload"]!!.jsonObject

    @Test
    fun theWholeMessageIsExactlyTheRc1Shape() {
        // Byte for byte, so a field added or renamed by accident fails here.
        // aiosendspin 10.0.0 parses this text with no legacy or noncompliance
        // marker set (trust_level_used, legacy_pair_methods_list_used,
        // artwork_support, player supported_commands all None).
        assertEquals(
            """{"type":"client/hello","payload":{"name":"Tablet",""" +
                """"supported_roles":["player@v1","controller@v1","metadata@v1","artwork@v1"],""" +
                """"device_info":{"product_name":"SendSpinDroid","manufacturer":"test",""" +
                """"software_version":"2.0.0"},""" +
                """"player@v1_support":{"supported_formats":[{"codec":"pcm","sample_rate":48000,""" +
                """"channels":2,"bit_depth":16}],"buffer_capacity":1680000},""" +
                """"supported_pair_methods":{"pairing_psk":{"locations":["device"]},""" +
                """"dynamic_pairing_code":{"out_channels":["display"],"formats":["digits"]}},""" +
                """"unpaired_access":{"enabled":true}}}""",
            helloText(
                pairMethods = listOf(
                    MessageBuilder.PairMethodDescriptor.PAIRING_PSK,
                    MessageBuilder.PairMethodDescriptor.DYNAMIC_PAIRING_CODE,
                ),
            ),
        )
    }

    @Test
    fun carriesOnlyTheFieldsTheSpecDefines() {
        assertEquals(
            setOf(
                "name", "supported_roles", "device_info", "player@v1_support",
                "supported_pair_methods", "unpaired_access",
            ),
            hello().keys,
        )
    }

    @Test
    fun advertisesUnpairedAccessSoAnUnpairedServerCanGrantPlayback() {
        assertEquals(
            true,
            hello(unpairedAccess = true)["unpaired_access"]!!
                .jsonObject["enabled"]!!.jsonPrimitive.booleanOrNull,
        )
        assertEquals(
            false,
            hello(unpairedAccess = false)["unpaired_access"]!!
                .jsonObject["enabled"]!!.jsonPrimitive.booleanOrNull,
        )
    }

    @Test
    fun hasNoTrustLevel() {
        // Removed from the spec; its presence alone marks a client as legacy.
        assertFalse(hello().containsKey("trust_level"))
    }

    @Test
    fun hasNoClientIdOrVersion() {
        // Both live in client/init, and messaging.md forbids sending fields the
        // spec does not define for a message.
        assertFalse(hello().containsKey("client_id"))
        assertFalse(hello().containsKey("version"))
    }

    @Test
    fun playerSupportCarriesOnlyFormatsAndBufferCapacity() {
        // supported_commands moved to the client/state player object.
        val support = hello()["player@v1_support"]!!.jsonObject
        assertEquals(setOf("supported_formats", "buffer_capacity"), support.keys)
        assertEquals(
            setOf("codec", "sample_rate", "channels", "bit_depth"),
            support["supported_formats"]!!.jsonArray[0].jsonObject.keys,
        )
    }

    @Test
    fun hasNoArtworkSupportObjectEvenWhenTheRoleIsListed() {
        // The artwork role defines no support object: its channels are
        // configured by the client/state artwork object.
        val payload = hello(lowMemoryMode = false)
        assertTrue(payload["supported_roles"]!!.jsonArray.any { it.jsonPrimitive.content == "artwork@v1" })
        assertFalse(payload.containsKey("artwork@v1_support"))
    }

    @Test
    fun lowMemoryModeDoesNotListTheArtworkRole() {
        val roles = hello(lowMemoryMode = true)["supported_roles"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals(listOf("player@v1", "controller@v1", "metadata@v1"), roles)
    }

    @Test
    fun pairMethodsAreAnObjectKeyedByMethodIdentifier() {
        val methods = hello()["supported_pair_methods"]!!.jsonObject
        assertEquals(setOf("pairing_psk"), methods.keys)
        val descriptor = methods["pairing_psk"]!!.jsonObject
        // Informational hint. This client generates its own Pairing PSK and
        // shows the resulting token on screen, which is what "device" describes.
        assertEquals(setOf("locations"), descriptor.keys)
        assertEquals("device", descriptor["locations"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun theDynamicPairingCodeDescriptorHasChannelsAndFormatsAndNoLocations() {
        // pairing.md "pair-method descriptor": dynamic_pairing_code has
        // out_channels and formats, and nothing else.
        val methods = hello(
            pairMethods = listOf(
                MessageBuilder.PairMethodDescriptor.PAIRING_PSK,
                MessageBuilder.PairMethodDescriptor.DYNAMIC_PAIRING_CODE,
            ),
        )["supported_pair_methods"]!!.jsonObject
        assertEquals(setOf("pairing_psk", "dynamic_pairing_code"), methods.keys)

        val dynamic = methods["dynamic_pairing_code"]!!.jsonObject
        assertEquals(setOf("out_channels", "formats"), dynamic.keys)
        assertEquals(listOf("display"), dynamic["out_channels"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("digits"), dynamic["formats"]!!.jsonArray.map { it.jsonPrimitive.content })
        // The method identifier is the key, never a "method" field.
        assertFalse(dynamic.containsKey("method"))
        assertFalse(methods["pairing_psk"]!!.jsonObject.containsKey("method"))
    }
}
