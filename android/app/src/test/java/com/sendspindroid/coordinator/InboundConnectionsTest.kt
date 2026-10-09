package com.sendspindroid.coordinator

import com.sendspindroid.e2e.E2ETestBase
import com.sendspindroid.e2e.FakeSendSpinServer
import com.sendspindroid.e2e.FakeTransport
import com.sendspindroid.sendspin.SendSpin
import com.sendspindroid.sendspin.crypto.Psk
import com.sendspindroid.sendspin.crypto.PskCategory
import com.sendspindroid.sendspin.protocol.ConnectionAdmission
import com.sendspindroid.sendspin.protocol.PlaintextCrypto
import com.sendspindroid.sendspin.protocol.SendSpinHandshakeDriver
import android.util.Log
import com.sendspindroid.UserSettings
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Several servers connecting to the client at once (`connection.md`,
 * "Multiple servers (server-initiated)"), with real clients over fake
 * sockets: which one is played from, what the others are told, and that a
 * connection that is not played from reports nothing to the service.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InboundConnectionsTest : E2ETestBase() {

    /** One server's connection: its socket, the client made for it, and what that client reported. */
    private inner class Server(val id: String) {
        val transport = FakeTransport()
        val wire = FakeSendSpinServer(transport)
        lateinit var client: SendSpin
        lateinit var callback: SendSpin.Callback

        /** Connect as far as the hello exchange: a provisional connection. */
        fun connect(matched: PskCategory? = null, key: Byte = 3): Server {
            transport.afterConnected = {
                client.installEncryptedChannel(PlaintextCrypto)
                setField(client, "sessionFacts", sessionFacts(id))
                matched?.let { setField(client, "matchedPsk", Psk(ByteArray(32) { key }, it)) }
            }
            inbound.onConnection(transport, "$id:51000")
            // Refused at the door.
            if (transport.closed) return this
            client = clients.last()
            callback = callbacks.last()
            transport.simulateConnected()
            wire.sendServerHello()
            assertTrue(transport.hasSentMessageContaining("client/hello"))
            scope.runCurrent()
            return this
        }

        fun activate(vararg activities: String): Server {
            val roles = if ("playback" in activities) listOf("player@v1") else emptyList()
            wire.sendServerActivate(activities.toList(), activeRoles = roles)
            scope.runCurrent()
            return this
        }

        fun goodbyes() = transport.sentTextMessages.filter { "client/goodbye" in it }
            .map { it.substringAfter("\"reason\":\"").substringBefore('"') }

        fun toldGoodbye(reason: String): Boolean =
            transport.hasSentMessageContaining("client/goodbye") && goodbyes() == listOf(reason)
    }

    private val scope = TestScope()
    private val clients = mutableListOf<SendSpin>()
    private val callbacks = mutableListOf<SendSpin.Callback>()
    private val admitted = mutableListOf<SendSpin>()
    private var lastPlayback: String? = null
    private val stored = mutableListOf<String>()

    private val inbound = InboundConnections(
        scope = scope,
        newClient = {
            val callback = mockk<SendSpin.Callback>(relaxed = true)
            callbacks += callback
            SendSpin("Tablet", callback).also { clients += it }
        },
        onAdmitted = { admitted += it },
        lastPlaybackServerId = { lastPlayback },
        storeLastPlaybackServerId = { stored += it },
        nowMs = { scope.testScheduler.currentTime },
    )

    private fun sessionFacts(serverId: String): Any {
        val type = SendSpin::class.java.declaredClasses.first { it.simpleName == "SessionFacts" }
        val constructor = type.declaredConstructors.first().apply { isAccessible = true }
        return constructor.newInstance(
            serverId, ByteArray(32), SendSpinHandshakeDriver.DEFAULT_SUITE, ByteArray(32)
        )
    }

    override fun configureUserSettings() {
        every { UserSettings.setOutputDelayMs(any()) } returns true
    }

    @After
    fun closeConnections() {
        inbound.close()
        clients.forEach { it.destroy() }
    }

    // ---- a single server ----

    @Test
    fun `a connection is provisional until its first activation and reports nothing`() {
        inbound.open()

        val a = Server("a").connect()

        assertFalse(a.client.reporting)
        assertEquals(emptyList<SendSpin>(), admitted)
        verify { a.callback wasNot Called }
    }

    @Test
    fun `the first server to activate is admitted and played from`() {
        inbound.open()

        val a = Server("a").connect().activate()

        assertEquals(listOf(a.client), admitted)
        assertTrue(a.client.reporting)
        verify { a.callback.onAdmissionStateChanged(any()) }
        assertTrue(a.transport.hasSentMessageContaining("client/state"))
    }

    @Test
    fun `later activations on the admitted connection hand nothing over again`() {
        inbound.open()
        val a = Server("a").connect().activate()

        a.activate("playback")
        a.activate()

        assertEquals(listOf(a.client), admitted)
        assertFalse(a.transport.closed)
    }

    // ---- two servers ----

    @Test
    fun `a playing server displaces an idle one, which is told another_server`() {
        inbound.open()
        val a = Server("a").connect().activate()

        val b = Server("b").connect().activate("playback")

        assertEquals(listOf(a.client, b.client), admitted)
        assertTrue(a.toldGoodbye("another_server"))
        assertTrue(a.transport.closed)
        assertFalse(b.transport.closed)
        // The service is not told the displaced connection ended: it has
        // already moved to the other one.
        assertFalse(a.client.reporting)
        verify(exactly = 0) { a.callback.onDisconnected(any()) }
    }

    @Test
    fun `a lower-ranked server is refused with concurrent_attempt and leaves no trace`() {
        inbound.open()
        val a = Server("a").connect().activate("playback")

        val b = Server("b").connect().activate()

        assertTrue(b.toldGoodbye("concurrent_attempt"))
        assertTrue(b.transport.closed)
        assertEquals(listOf(a.client), admitted)
        assertFalse(a.transport.closed)
        assertEquals(emptyList<String>(), a.goodbyes())
        verify { b.callback wasNot Called }
        assertFalse(b.transport.hasSentMessageContaining("client/state"))
    }

    @Test
    fun `an equal-ranked server displaces the holder`() {
        inbound.open()
        val a = Server("a").connect().activate("playback")

        val b = Server("b").connect().activate("playback")

        assertSame(b.client, admitted.last())
        assertTrue(a.toldGoodbye("another_server"))
    }

    @Test
    fun `between two idle servers the last-playback server wins`() {
        lastPlayback = "b"
        inbound.open()
        val a = Server("a").connect().activate()

        val b = Server("b").connect().activate()

        assertSame(b.client, admitted.last())
        assertTrue(a.toldGoodbye("another_server"))
    }

    @Test
    fun `between two idle servers the holder is kept when the incoming is not the last-playback server`() {
        lastPlayback = "a"
        inbound.open()
        val a = Server("a").connect().activate()

        val b = Server("b").connect().activate()

        assertEquals(listOf(a.client), admitted)
        assertTrue(b.toldGoodbye("concurrent_attempt"))
    }

    @Test
    fun `the last-playback server is stored when the admitted connection declares playback`() {
        inbound.open()
        val a = Server("a").connect().activate()
        assertEquals(emptyList<String>(), stored)

        a.activate("playback")
        Server("b").connect().activate("playback")

        assertEquals(listOf("a", "b"), stored)
    }

    @Test
    fun `a holder with a pairing attempt in progress is not displaced by a playing server`() {
        inbound.open()
        val a = Server("a").connect(matched = PskCategory.PAIRING)
        a.transport.simulateTextMessage(
            """{"type":"server/activate","payload":{"activities":["pairing"],""" +
                """"active_roles":[],"pairing":{"method":"pairing_psk"}}}"""
        )
        assertTrue(a.transport.hasSentMessageContaining("client/pair-init"))
        assertTrue(a.client.pairingAttemptInProgress)

        val b = Server("b").connect().activate("playback")

        assertTrue(b.toldGoodbye("concurrent_attempt"))
        assertEquals(listOf(a.client), admitted)
        assertFalse(a.transport.closed)
    }

    @Test
    fun `a holder whose connection ended by itself is replaced without a goodbye`() {
        inbound.open()
        val a = Server("a").connect().activate("playback")
        a.transport.simulateClosed(1001, "going away")
        scope.runCurrent()

        val b = Server("b").connect().activate()

        assertSame(b.client, admitted.last())
        assertEquals(emptyList<String>(), a.goodbyes())
    }

    @Test
    fun `two servers activating together are handed over in the order they were admitted`() {
        inbound.open()
        val a = Server("a").connect()
        val b = Server("b").connect()
        // Stop a's activation where it has been admitted and before anyone
        // has been told, the one point at which b's can overtake it.
        val admittingA = CountDownLatch(1)
        val carryOn = CountDownLatch(1)
        every { Log.i("InboundConnections", match { it.startsWith("Admitting a:") }) } answers {
            admittingA.countDown()
            carryOn.await(30, TimeUnit.SECONDS)
            0
        }

        val first = thread { a.wire.sendServerActivate(emptyList(), activeRoles = emptyList()) }
        assertTrue(admittingA.await(30, TimeUnit.SECONDS))
        val second = thread { b.wire.sendServerActivate(listOf("playback"), activeRoles = listOf("player@v1")) }
        // b's activation either waits for a's to finish, or has run past it.
        while (second.isAlive && second.state != Thread.State.BLOCKED) Thread.yield()
        carryOn.countDown()
        first.join()
        second.join()

        assertEquals(listOf(a.client, b.client), admitted)
        assertTrue(b.client.reporting)
        assertFalse(b.transport.closed)
        assertFalse("the displaced connection must not report again", a.client.reporting)
        assertTrue(a.toldGoodbye("another_server"))
    }

    @Test
    fun `a connection that is not the one played from cannot change a setting`() {
        val setOutputDelay =
            """{"type":"server/command","payload":{"player":{"command":"set_output_delay","output_delay_ms":3000}}}"""
        inbound.open()
        val a = Server("a").connect().activate("playback")

        a.client.reporting = false
        a.transport.simulateTextMessage(setOutputDelay)
        verify(exactly = 0) { UserSettings.setOutputDelayMs(any()) }

        a.client.reporting = true
        a.transport.simulateTextMessage(setOutputDelay)
        verify(exactly = 1) { UserSettings.setOutputDelayMs(3000) }
    }

    // ---- provisional connections ----

    @Test
    fun `a server that does not activate within 30 seconds is dropped without a word`() {
        inbound.open()
        val a = Server("a").connect()

        scope.advanceTimeBy(ConnectionAdmission.PROVISIONAL_TIMEOUT_MS - 1_000)
        scope.runCurrent()
        assertFalse(a.transport.closed)

        scope.advanceTimeBy(2_000)
        scope.runCurrent()
        assertTrue(a.transport.closed)
        assertEquals(emptyList<String>(), a.goodbyes())
        verify { a.callback wasNot Called }
        assertEquals(emptyList<SendSpin>(), admitted)
    }

    @Test
    fun `the timeout does not touch a connection that was admitted`() {
        inbound.open()
        val a = Server("a").connect().activate()

        scope.advanceTimeBy(ConnectionAdmission.PROVISIONAL_TIMEOUT_MS * 2)
        scope.runCurrent()

        assertFalse(a.transport.closed)
    }

    @Test
    fun `connections beyond the provisional cap are refused at the door`() {
        inbound.open()
        val waiting = (1..ConnectionAdmission.MAX_PROVISIONAL).map { Server("s$it").connect() }

        val extra = Server("extra").connect()

        assertTrue(extra.transport.closed)
        assertEquals(1013, extra.transport.closeCode)
        assertEquals("no client is made for it", ConnectionAdmission.MAX_PROVISIONAL, clients.size)
        assertEquals(emptyList<String>(), extra.transport.sentTextMessages.toList())
        assertTrue(waiting.none { it.transport.closed })
    }

    // ---- forgetting a pairing ----

    private fun pskIdOf(key: Byte) = Psk(ByteArray(32) { key }, PskCategory.LONG_TERM).pskId

    @Test
    fun `forgetting the record that admitted the held connection ends it as unauthorized`() {
        inbound.open()
        val a = Server("a").connect(PskCategory.LONG_TERM, key = 4).activate("playback")
        val b = Server("b").connect(PskCategory.LONG_TERM, key = 5)

        inbound.leaveIfAdmittedBy(pskIdOf(4))
        scope.runCurrent()

        assertTrue(a.toldGoodbye("unauthorized"))
        assertTrue(a.transport.closed)
        verify(exactly = 1) { a.callback.onDisconnected(false) }
        verify(exactly = 0) { a.callback.onDisconnected(true) }
        assertFalse("another server's connection is left alone", b.transport.closed)
    }

    @Test
    fun `a provisional connection on the forgotten key is dropped before it can be admitted`() {
        inbound.open()
        val a = Server("a").connect(PskCategory.LONG_TERM, key = 4)

        inbound.leaveIfAdmittedBy(pskIdOf(4))
        scope.runCurrent()

        assertTrue(a.transport.closed)
        assertEquals(emptyList<SendSpin>(), admitted)
    }

    @Test
    fun `the forgotten server connecting again is met as an unpaired one`() {
        inbound.open()
        Server("a").connect(PskCategory.LONG_TERM, key = 4).activate("playback")
        inbound.leaveIfAdmittedBy(pskIdOf(4))
        scope.runCurrent()

        // Its key is no longer held, so the handshake falls back to the
        // Sentinel, and the ordinary admission rules take it from there.
        val again = Server("a").connect(PskCategory.SENTINEL).activate()

        assertSame(again.client, admitted.last())
        assertFalse(again.transport.closed)
    }

    // ---- stopping ----

    @Test
    fun `closing ends every connection and refuses new ones`() {
        inbound.open()
        val a = Server("a").connect().activate("playback")
        val b = Server("b").connect()

        inbound.close()

        assertTrue(a.toldGoodbye("another_server"))
        assertTrue(a.transport.closed)
        assertFalse(a.client.reporting)
        assertTrue(b.transport.closed)
        assertEquals(emptyList<String>(), b.goodbyes())

        val c = Server("c").connect()
        assertTrue(c.transport.closed)
    }

    @Test
    fun `nothing is accepted before it is opened`() {
        val a = Server("a").connect()

        assertTrue(a.transport.closed)
        assertEquals(emptyList<SendSpin>(), admitted)
    }

    @Test
    fun `opening again starts with no holder`() {
        inbound.open()
        Server("a").connect().activate("playback")
        inbound.close()
        inbound.open()

        val b = Server("b").connect().activate()

        assertSame(b.client, admitted.last())
    }
}
