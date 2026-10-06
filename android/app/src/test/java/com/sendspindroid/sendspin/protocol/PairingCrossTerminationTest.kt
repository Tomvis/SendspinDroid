package com.sendspindroid.sendspin.protocol

import com.sendspindroid.sendspin.SendspinTimeFilter
import com.sendspindroid.sendspin.crypto.NoiseCipherSuite
import com.sendspindroid.sendspin.crypto.Psk
import com.sendspindroid.sendspin.crypto.PskCategory
import com.sendspindroid.sendspin.crypto.PskId
import com.sendspindroid.sendspin.crypto.PskRecord
import com.sendspindroid.sendspin.crypto.TrustStore
import com.sendspindroid.sendspin.pairing.PairingCounterStore
import com.sendspindroid.sendspin.protocol.message.MessageBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FIX 2 regression guard: a pairing activation must reach BOTH flows.
 *
 * `handleServerActivate` routes an accepted pairing activation to only ONE of
 * [com.sendspindroid.sendspin.pairing.PairingPskFlow] and
 * [com.sendspindroid.sendspin.pairing.DynamicPairingCodeFlow] - the one the
 * activation names. If a server switches methods across activations, the
 * flow that was NOT selected must be told its attempt is over too, or a
 * stale attempt in the other flow survives: a stale PSK attempt can later
 * persist a record the server never stored for it, and a stale dynamic
 * attempt's timer can fire `pair/abort(attempt_timeout)` into the middle of
 * a new attempt on the other method.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairingCrossTerminationTest {

    private fun activateJson(method: String) = pairingActivateJson(method)

    @Test
    fun `a dynamic activation terminates a live Pairing PSK attempt`() {
        val handler = CrossTerminationTestHandler()
        handler.matchedCategory = PskCategory.PAIRING

        // Start a Pairing PSK attempt - the client mints a secret and sends it.
        handler.handleTextMessageForTest(activateJson("pairing_psk"))
        handler.scope.runCurrent()
        assertTrue(
            "PSK attempt should have started",
            handler.events.contains("send:client/pair-finalize")
        )
        handler.clearEvents()

        // The server switches to the dynamic method on the next activation
        // (e.g. after an in-band re-handshake promotes the connection off the
        // Pairing PSK).
        handler.matchedCategory = PskCategory.SENTINEL
        handler.handleTextMessageForTest(activateJson("dynamic_pairing_code"))
        handler.scope.runCurrent()

        // A late server/pair-finalize for the stale PSK attempt must not
        // persist a record the server never actually stored for THIS attempt.
        handler.handleTextMessageForTest("""{"type":"server/pair-finalize"}""")
        handler.scope.runCurrent()

        assertEquals(
            "no record should have been persisted for the superseded attempt",
            0,
            handler.store.listRecords().size,
        )
    }

    @Test
    fun `a Pairing PSK activation terminates a live dynamic attempt`() {
        val handler = CrossTerminationTestHandler()
        handler.matchedCategory = PskCategory.SENTINEL

        // Start a dynamic pairing attempt; its attempt timer begins now (t=0).
        handler.handleTextMessageForTest(activateJson("dynamic_pairing_code"))
        handler.scope.runCurrent()
        handler.scope.advanceTimeBy(10_000)

        // The server switches to Pairing PSK on the next activation, at t=10s.
        handler.matchedCategory = PskCategory.PAIRING
        handler.handleTextMessageForTest(activateJson("pairing_psk"))
        handler.scope.runCurrent()
        handler.clearEvents()

        // Advance just past the ORIGINAL dynamic attempt's deadline (t=120s)
        // but well before the new PSK attempt's own deadline (t=130s). If the
        // dynamic flow's timer were still alive, it would fire
        // pair/abort(attempt_timeout) right here, into the middle of the new
        // PSK attempt.
        handler.scope.advanceTimeBy(SendSpinProtocol.PAIR_ATTEMPT_TIMEOUT_MS - 10_000 + 1)
        handler.scope.runCurrent()

        assertEquals(
            "the superseded dynamic attempt's timer must not fire",
            emptyList<String>(),
            handler.events,
        )
    }
}

/** A pairing `server/activate`; `format` is required for (only) the dynamic method. */
fun pairingActivateJson(
    method: String,
    format: String? = if (method == "dynamic_pairing_code") "digits" else null,
): String {
    val formatField = if (format == null) "" else ""","format":"$format""""
    return """{"type":"server/activate","payload":{"activities":["pairing"],""" +
        """"pairing":{"method":"$method"$formatField}}}"""
}

/**
 * A handler whose matched PSK category can be changed mid-test, to simulate a
 * server switching pairing methods across activations (e.g. around an
 * in-band re-handshake). Offers both pairing methods so either can be
 * Accepted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrossTerminationTestHandler : SendSpinProtocolHandler("CrossTerminationTest") {

    var matchedCategory: PskCategory = PskCategory.SENTINEL

    val scope = TestScope()
    val sent = mutableListOf<String>()
    val events = mutableListOf<String>()
    val store = RecordingTrustStore()

    /** The code on screen, or null when none is shown. */
    var shownCode: String? = null
    val protocolFailures = mutableListOf<String>()

    private val timeFilter = SendspinTimeFilter()
    private val counterStore = InMemoryPairingCounterStore()
    private val handshakeHash = ByteArray(32) { 7 }

    init {
        handshakeComplete = true
    }

    fun handleTextMessageForTest(text: String) = handleTextMessage(text)

    fun clearEvents() {
        events.clear()
        sent.clear()
    }

    override fun onDynamicPairingCodeEmitted(code: String) {
        shownCode = code
    }

    override fun onDynamicPairingCodeCleared() {
        shownCode = null
    }

    override fun onProtocolFailure(reason: String) {
        protocolFailures.add(reason)
    }

    override fun offeredPairMethods(): Set<String> = setOf("pairing_psk", "dynamic_pairing_code")

    override fun matchedPskCategory(): PskCategory = matchedCategory

    override fun matchedPsk(): Psk? = Psk(ByteArray(32) { 3 }, matchedCategory)

    override fun trustStore(): TrustStore = store

    override fun currentServerId(): String = "srv1"

    override fun currentHandshakeHash(): ByteArray = handshakeHash

    override fun pairingCounterStore(): PairingCounterStore = counterStore

    override fun negotiatedCipherSuite(): NoiseCipherSuite = NoiseCipherSuite.CHACHA_POLY

    override fun closeConnectionAfterFlush() {
        events.add("close")
    }

    init {
        installEncryptedChannel(PlaintextCrypto)
    }

    override fun sendBinaryFrame(bytes: ByteArray) {
        val text = bytes.jsonFrameText() ?: return
        sent.add(text)
        events.add("send:" + Json.parseToJsonElement(text).jsonObject["type"]?.jsonPrimitive?.content)
    }

    override fun getCoroutineScope(): CoroutineScope = scope

    override fun getTimeFilter(): SendspinTimeFilter = timeFilter

    override fun isLowMemoryMode(): Boolean = false
    override fun getClientId(): String = "test-client"
    override fun getDeviceName(): String = "Test"
    override fun getManufacturer(): String = "Test"
    override fun getSoftwareVersion(): String = "0.0.0"
    override fun getSupportedFormats(): List<MessageBuilder.FormatEntry> = emptyList()

    override fun onHandshakeComplete(serverName: String, serverId: String) = Unit
    override fun onMetadataUpdate(metadata: TrackMetadata) = Unit
    override fun onPlaybackStateChanged(state: String) = Unit
    override fun onVolumeCommand(volume: Int) = Unit
    override fun onMuteCommand(muted: Boolean) = Unit
    override fun onGroupUpdate(info: GroupInfo) = Unit
    override fun onStreamStart(config: StreamConfig) = Unit
    override fun onStreamClear() = Unit
    override fun onStreamEnd() = Unit
    override fun onAudioChunk(timestampMicros: Long, audioData: ByteArray) = Unit
    override fun onArtwork(channel: Int, payload: ByteArray) = Unit
    override fun onSyncOffsetApplied(offsetMs: Double, source: String) = Unit
    override fun onSyncMuteChanged(muted: Boolean) = Unit
}

/** In-memory [PairingCounterStore]. */
class InMemoryPairingCounterStore : PairingCounterStore {
    private var value = 0
    override fun load(): Int = value
    override fun save(value: Int) {
        this.value = value
    }
}

/** In-memory [TrustStore] that actually supports [addRecord], unlike [FakeTrustStore]. */
class RecordingTrustStore : TrustStore {
    private val records = mutableMapOf<String, PskRecord>()

    override fun listRecords(): List<PskRecord> = records.values.toList()

    override fun findByPskId(pskId: String): PskRecord? = records[pskId]

    override fun addRecord(psk: ByteArray, serverId: String?): TrustStore.AddRecordResult {
        val pskId = PskId.derive(psk)
        if (pskId in records) return TrustStore.AddRecordResult.AlreadyExists
        val record = PskRecord(pskId, psk, serverId)
        records[pskId] = record
        return TrustStore.AddRecordResult.Ok(record)
    }

    override fun removeRecord(pskId: String): Boolean = records.remove(pskId) != null

    override fun markUsed(pskId: String) {
        records[pskId]?.let { records[pskId] = it.withUsed(true) }
    }

    override fun candidates(): List<Psk> = records.values.map { it.toPsk() }

    override val storageIsEncrypted: Boolean = true
}
