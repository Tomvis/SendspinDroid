package com.sendspindroid.e2e

import com.sendspindroid.UserSettings
import com.sendspindroid.coordinator.TransportState
import com.sendspindroid.sendspin.SendSpin
import com.sendspindroid.sendspin.crypto.InMemoryTrustStore
import com.sendspindroid.sendspin.crypto.Psk
import com.sendspindroid.sendspin.crypto.PskCategory
import com.sendspindroid.sendspin.crypto.PskRecord
import com.sendspindroid.sendspin.crypto.TrustStore
import com.sendspindroid.sendspin.pairing.PairedServer
import com.sendspindroid.sendspin.pairing.PairedServers
import com.sendspindroid.sendspin.pairing.PairingOutcome
import com.sendspindroid.sendspin.protocol.SendSpinHandshakeDriver
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the pairing UI is told, and what Forget does to a live connection
 * (#225), on a real client over a fake socket.
 *
 * The store is a real one, so "the record is gone" is the store's answer and
 * not a mock's.
 */
class PairingStatusTest : E2ETestBase() {

    private val serverId = "server-id"
    private val livePsk = ByteArray(Psk.PSK_SIZE) { 7 }
    private val otherPsk = ByteArray(Psk.PSK_SIZE) { 9 }
    private lateinit var store: TrustStore
    private lateinit var liveRecord: PskRecord
    private lateinit var otherRecord: PskRecord

    /** Stands in for the service, which ends connections on [PairedServers.forgotten]. */
    private val service = CoroutineScope(Dispatchers.Unconfined)
    private var recordHeldWhenToldToLeave: Boolean? = null

    override fun configureUserSettings() {
        store = InMemoryTrustStore()
        liveRecord = (store.addRecord(livePsk, serverId) as TrustStore.AddRecordResult.Ok).record
        otherRecord = (store.addRecord(otherPsk, "other-server") as TrustStore.AddRecordResult.Ok).record
        every { UserSettings.getOrCreateTrustStore() } returns store
        every { UserSettings.getPairedServerName(any()) } returns null
        PairedServers.refresh()
    }

    @After
    fun stopService() = service.cancel()

    /** A session the live record admitted, with the service listening for Forget. */
    private fun connectPaired() {
        connectAndHandshake()
        setField(client, "matchedPsk", Psk(livePsk, PskCategory.LONG_TERM, serverId))
        service.launch {
            PairedServers.forgotten.collect { pskId ->
                recordHeldWhenToldToLeave = store.findByPskId(pskId) != null
                client.leaveIfAdmittedBy(pskId)
            }
        }
    }

    private fun goodbyes() = fakeTransport.sentTextMessages.filter { "client/goodbye" in it }

    private fun awaitClosed() {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!fakeTransport.closed && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("transport should have been closed", fakeTransport.closed)
    }

    // ========== Forget ==========

    @Test
    fun `forgetting the record that admitted the connection ends it as unauthorized`() {
        connectPaired()

        PairedServers.forget(liveRecord.pskId)

        assertEquals(
            listOf("""{"type":"client/goodbye","payload":{"reason":"unauthorized"}}"""),
            goodbyes(),
        )
        assertTrue("transport should be closed", fakeTransport.closed)
        assertTrue("the close must let the goodbye out first", fakeTransport.flushedBeforeClose)
        assertEquals(TransportState.Idle, client.connectionState.value)
    }

    @Test
    fun `a forgotten live connection is not reconnected and its key is gone first`() {
        connectPaired()

        PairedServers.forget(liveRecord.pskId)

        // The owner starts its reconnect loop only on true.
        verify(exactly = 1) { mockCallback.onDisconnected(false) }
        verify(exactly = 0) { mockCallback.onDisconnected(true) }
        // Whoever connects next is matched against these, and the forgotten
        // key is not among them: the server lands on the Sentinel PSK.
        assertEquals(false, recordHeldWhenToldToLeave)
        assertFalse(store.candidates().any { it.pskId == liveRecord.pskId })
    }

    @Test
    fun `forget removes exactly the one record`() {
        connectPaired()

        PairedServers.forget(liveRecord.pskId)

        assertEquals(listOf(otherRecord), store.listRecords())
        assertEquals(
            listOf(PairedServer(otherRecord.pskId, "other-server", null)),
            PairedServers.servers.value,
        )
    }

    @Test
    fun `forgetting clears the outcome of the pairing it undid`() {
        connectPaired()
        PairedServers.report(PairingOutcome.Paired(fakeServer.serverName))

        PairedServers.forget(liveRecord.pskId)

        assertEquals(null, PairedServers.lastOutcome.value)
    }

    @Test
    fun `forgetting another server's record leaves the connection alone`() {
        connectPaired()

        PairedServers.forget(otherRecord.pskId)

        assertEquals(listOf(liveRecord), store.listRecords())
        assertEquals(emptyList<String>(), goodbyes())
        assertFalse(fakeTransport.closed)
        assertEquals(TransportState.Ready, client.connectionState.value)
    }

    // ========== Outcomes ==========

    @Test
    fun `server unpair removes the row and says who unpaired`() {
        connectPaired()
        assertEquals(2, PairedServers.servers.value.size)

        fakeTransport.simulateTextMessage("""{"type":"server/unpair"}""")
        awaitClosed()

        assertEquals(listOf(otherRecord.pskId), PairedServers.servers.value.map { it.pskId })
        val outcome = PairedServers.lastOutcome.value
        assertTrue("$outcome", outcome is PairingOutcome.Unpaired)
        assertEquals(fakeServer.serverName, outcome?.server)
    }

    @Test
    fun `an abort from the server is reported with its reason`() {
        connectPaired()
        setField(client, "matchedPsk", Psk(ByteArray(Psk.PSK_SIZE) { 1 }, PskCategory.PAIRING))
        fakeTransport.simulateTextMessage(
            """{"type":"server/activate","payload":{"activities":["pairing"],"pairing":{"method":"pairing_psk"}}}"""
        )

        fakeTransport.simulateTextMessage(
            """{"type":"pair/abort","payload":{"reason":"user_cancelled"}}"""
        )

        val outcome = PairedServers.lastOutcome.value as PairingOutcome.Aborted
        assertEquals("user_cancelled", outcome.reason)
        assertFalse(outcome.sentByUs)
        assertEquals(fakeServer.serverName, outcome.server)
    }

    @Test
    fun `an abort this client sends is reported as its own`() {
        // Token pairing asked for on a session the pairing PSK did not key.
        connectAndHandshake()
        fakeTransport.simulateTextMessage(
            """{"type":"server/activate","payload":{"activities":["pairing"],"pairing":{"method":"pairing_psk"}}}"""
        )

        val outcome = PairedServers.lastOutcome.value as PairingOutcome.Aborted
        assertEquals("method_not_supported", outcome.reason)
        assertTrue(outcome.sentByUs)
    }

    @Test
    fun `the server's abort does not replace the reason this client already gave`() {
        connectAndHandshake()
        fakeTransport.simulateTextMessage(
            """{"type":"server/activate","payload":{"activities":["pairing"],"pairing":{"method":"pairing_psk"}}}"""
        )

        fakeTransport.simulateTextMessage(
            """{"type":"pair/abort","payload":{"reason":"user_cancelled"}}"""
        )

        assertEquals(
            "method_not_supported",
            (PairedServers.lastOutcome.value as PairingOutcome.Aborted).reason,
        )
    }

    @Test
    fun `a completed pairing adds the row under the server's name`() {
        val names = mutableMapOf<String, String?>()
        every { UserSettings.getPairedServerName(any()) } answers { names[firstArg()] }
        every { UserSettings.setPairedServerName(any(), any()) } answers { names[firstArg()] = secondArg() }
        store.removeRecord(liveRecord.pskId)
        store.removeRecord(otherRecord.pskId)
        PairedServers.refresh()
        assertEquals(emptyList<PairedServer>(), PairedServers.servers.value)

        connectAndHandshake()
        setField(client, "matchedPsk", Psk(ByteArray(Psk.PSK_SIZE) { 1 }, PskCategory.PAIRING))
        setField(client, "sessionFacts", sessionFacts(serverId))
        fakeTransport.simulateTextMessage(
            """{"type":"server/activate","payload":{"activities":["pairing"],"pairing":{"method":"pairing_psk"}}}"""
        )
        fakeTransport.simulateTextMessage("""{"type":"server/pair-finalize"}""")

        val row = PairedServers.servers.value.single()
        assertEquals(serverId, row.serverId)
        assertEquals(fakeServer.serverName, row.name)
        assertEquals(PairingOutcome.Paired::class, PairedServers.lastOutcome.value!!::class)
    }

    private fun sessionFacts(serverId: String): Any {
        val type = SendSpin::class.java.declaredClasses.first { it.simpleName == "SessionFacts" }
        val constructor = type.declaredConstructors.first().apply { isAccessible = true }
        return constructor.newInstance(
            serverId, ByteArray(32), SendSpinHandshakeDriver.DEFAULT_SUITE, ByteArray(32)
        )
    }
}
