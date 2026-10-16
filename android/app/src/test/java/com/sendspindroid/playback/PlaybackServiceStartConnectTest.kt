package com.sendspindroid.playback

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import androidx.preference.PreferenceManager
import com.sendspindroid.UnifiedServerRepository
import com.sendspindroid.UserSettings
import com.sendspindroid.coordinator.FailureReason
import com.sendspindroid.coordinator.ReconnectStatus
import com.sendspindroid.coordinator.TransportState
import com.sendspindroid.discovery.NsdDiscoveryManager
import com.sendspindroid.model.LocalConnection
import com.sendspindroid.model.UnifiedServer
import com.sendspindroid.sendspin.SendSpin
import com.sendspindroid.sendspin.SendSpinEndpoint
import io.mockk.every
import io.mockk.just
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import java.time.Duration

/**
 * The connections the service makes on its own, with nobody to retry them:
 * at boot, and when the system restarts the service it killed. Both go
 * through the reconnect loop, so a server that is not up yet is tried again
 * instead of the service giving up and stopping (#158).
 *
 * The client's connection is the test's to drive: connect() only reports
 * Connecting, and the test says how the attempt ends.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceStartConnectTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val defaultServer = UnifiedServer(
        id = "server-1",
        name = "Living Room",
        local = LocalConnection("192.168.1.100:8927"),
        isDefaultServer = true,
    )
    private val clientState = MutableStateFlow<TransportState>(TransportState.Idle)
    private var controller: ServiceController<PlaybackService>? = null

    @Before
    fun setUp() {
        UserSettings.resetForTesting()
        UserSettings.initializeForTesting(prefs, context.getSharedPreferences("sensitive", Context.MODE_PRIVATE))
        mockkObject(UnifiedServerRepository)
        every { UnifiedServerRepository.initialize(any()) } just runs
        every { UnifiedServerRepository.getServer(defaultServer.id) } returns defaultServer
        every { UnifiedServerRepository.getDefaultServer() } returns defaultServer
        every { UnifiedServerRepository.addDiscoveredServer(any(), any(), any()) } just runs
        mockkConstructor(SendSpin::class)
        every { anyConstructed<SendSpin>().connectionState } returns clientState
        every { anyConstructed<SendSpin>().connect(any<SendSpinEndpoint>()) } answers {
            clientState.value = TransportState.Connecting
        }
        ShadowPowerManager.clearWakeLocks()
    }

    @After
    fun tearDown() {
        controller?.destroy()
        unmockkAll()
        UserSettings.resetForTesting()
    }

    private fun searchForServers(search: Boolean) {
        prefs.edit().putBoolean(UserSettings.KEY_SEARCH_FOR_SERVERS, search).commit()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun autoConnectIntent() = Intent(context, PlaybackService::class.java).apply {
        action = PlaybackService.ACTION_AUTO_CONNECT
        putExtra(PlaybackService.EXTRA_SERVER_ID, defaultServer.id)
    }

    /** Start the service the way BootReceiver does in search mode. */
    private fun startForBoot(): PlaybackService {
        val started = Robolectric.buildService(PlaybackService::class.java, autoConnectIntent())
            .create().startCommand(0, 1)
        controller = started
        idle()
        return started.get()
    }

    /** The system restarts a START_STICKY service it killed with a null intent. */
    private fun restartBySystem(): PlaybackService {
        val created = Robolectric.buildService(PlaybackService::class.java).create()
        controller = created
        created.get().onStartCommand(null, 0, 1)
        idle()
        return created.get()
    }

    /** Let the loop's first backoff run out, so it dials. */
    private fun firstAttempt() {
        idleFor(600)
        assertEquals(TransportState.Connecting, clientState.value)
    }

    /** What the loop's mDNS discovery hears servers through. */
    private fun reconnectDiscovery(service: PlaybackService): NsdDiscoveryManager.DiscoveryListener {
        val manager = PlaybackService::class.java.getDeclaredField("reconnectDiscoveryManager")
            .apply { isAccessible = true }.get(service)
        assertNotNull("the loop watches mDNS", manager)
        return NsdDiscoveryManager::class.java.getDeclaredField("listener")
            .apply { isAccessible = true }.get(manager) as NsdDiscoveryManager.DiscoveryListener
    }

    private fun attempting(): ReconnectStatus.Attempting? =
        PlaybackService.reconnectStatus.value as? ReconnectStatus.Attempting

    private fun connectWakeLock(): PowerManager.WakeLock {
        val lock = ShadowPowerManager.getLatestWakeLock()
        assertNotNull("a wake lock is held for the connection", lock)
        assertEquals("SendSpinDroid::Connect", shadowOf(lock).tag)
        return lock
    }

    // ---- at boot ----

    @Test
    fun `boot auto-connect runs the reconnect loop`() {
        searchForServers(true)

        val service = startForBoot()

        assertEquals(defaultServer.id, attempting()?.serverId)
        // Started by startForegroundService, which startForeground must answer.
        val notification = shadowOf(service).lastForegroundNotification
        assertNotNull("the service takes the foreground", notification)
        val text = notification!!.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(text, text.contains(defaultServer.name))
    }

    @Test
    fun `an auto-connect while a connection is being made still takes the foreground`() {
        // The boot notification is tapped while the app's own connect is
        // under way: nothing has taken the foreground yet.
        searchForServers(true)
        val created = Robolectric.buildService(PlaybackService::class.java, autoConnectIntent()).create()
        controller = created
        val service = created.get()
        service.setCurrentServer(defaultServer.id)
        service.connectToServer(defaultServer.local!!.address)
        idle()
        assertEquals(TransportState.Connecting, clientState.value)

        created.startCommand(0, 1)
        idle()

        assertNotNull("the service takes the foreground", shadowOf(service).lastForegroundNotification)
    }

    @Test
    fun `an auto-connect for a server that is gone takes the foreground and lets it go`() {
        // Deleted between boot and the tap on the notification.
        searchForServers(true)
        every { UnifiedServerRepository.getServer(defaultServer.id) } returns null

        val service = startForBoot()

        assertEquals(NotificationHelper.NOTIFICATION_ID, shadowOf(service).lastForegroundNotificationId)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `a refused first attempt at boot is tried again and the service stays up`() {
        searchForServers(true)
        val service = startForBoot()
        firstAttempt()

        clientState.value = TransportState.Failed(FailureReason.TransientNetwork)
        idle()

        assertEquals(2, attempting()?.attempt)
        assertFalse("the service has not stopped itself", shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `a server seen on mDNS under its service name has its saved address refreshed`() {
        // Saved under its service name, not the friendly name in its TXT
        // record: addDiscoveredServer, which refreshes by friendly name,
        // misses it, and each attempt reads the saved address.
        searchForServers(true)
        every { UnifiedServerRepository.refreshSavedServerAddressById(any(), any()) } returns true
        val service = startForBoot()

        reconnectDiscovery(service).onServerDiscovered(
            name = defaultServer.name,
            address = "192.168.1.150:8927",
            path = "/sendspin",
            friendlyName = "Music Assistant",
        )

        verify { UnifiedServerRepository.refreshSavedServerAddressById(defaultServer.id, "192.168.1.150:8927") }
    }

    @Test
    fun `a second boot broadcast leaves the connection being made alone`() {
        // The Android 15+ tap-to-connect notification tapped while the loop
        // is already running.
        searchForServers(true)
        startForBoot()
        firstAttempt()

        controller!!.withIntent(autoConnectIntent()).startCommand(0, 2)
        idle()
        clientState.value = TransportState.Failed(FailureReason.TransientNetwork)
        idle()

        assertEquals("the first loop carries on", 2, attempting()?.attempt)
    }

    // ---- restarted by the system ----

    @Test
    fun `a restart in search mode connects to the default server through the loop`() {
        searchForServers(true)

        restartBySystem()

        assertEquals(defaultServer.id, attempting()?.serverId)
    }

    @Test
    fun `a restart returns to the server it was connected to, not the default`() {
        searchForServers(true)
        val kitchen = UnifiedServer(id = "server-2", name = "Kitchen", local = LocalConnection("192.168.1.101:8927"))
        every { UnifiedServerRepository.getServer(kitchen.id) } returns kitchen
        // The user picked Kitchen, and was connected to it when the system
        // killed the service.
        val first = Robolectric.buildService(PlaybackService::class.java).create()
        first.get().setCurrentServer(kitchen.id)
        first.get().connectToServer(kitchen.local!!.address)
        clientState.value = TransportState.Ready
        idle()
        first.destroy()
        clientState.value = TransportState.Idle

        restartBySystem()

        assertEquals(kitchen.id, attempting()?.serverId)
    }

    @Test
    fun `a restart does not swap a server it cannot look up for the default`() {
        // Connected to a server found on mDNS and never saved: after the
        // restart it is not in the repository.
        searchForServers(true)
        UserSettings.dialledServerId = "discovered-1"
        every { UnifiedServerRepository.getServer("discovered-1") } returns null

        restartBySystem()

        assertEquals(ReconnectStatus.Idle, PlaybackService.reconnectStatus.value)
    }

    @Test
    fun `a restart in search mode with no default server does nothing`() {
        searchForServers(true)
        every { UnifiedServerRepository.getDefaultServer() } returns null

        restartBySystem()

        assertEquals(ReconnectStatus.Idle, PlaybackService.reconnectStatus.value)
    }

    @Test
    fun `a restart while advertising leaves it to the server to connect`() {
        searchForServers(false)

        restartBySystem()

        assertEquals(ReconnectStatus.Idle, PlaybackService.reconnectStatus.value)
        assertEquals(TransportState.Idle, clientState.value)
    }

    @Test
    fun `a start with no action is not taken for a restart`() {
        searchForServers(true)
        val created = Robolectric.buildService(PlaybackService::class.java).create()
        controller = created

        created.get().onStartCommand(Intent(context, PlaybackService::class.java), 0, 1)
        idle()

        assertEquals(ReconnectStatus.Idle, PlaybackService.reconnectStatus.value)
    }

    // ---- the wake lock ----

    @Test
    fun `the wake lock is held while the loop runs and let go when it connects`() {
        searchForServers(true)
        startForBoot()
        val lock = connectWakeLock()
        assertTrue(lock.isHeld)
        firstAttempt()

        clientState.value = TransportState.Ready
        idle()

        assertTrue(PlaybackService.reconnectStatus.value is ReconnectStatus.Succeeded)
        assertFalse(lock.isHeld)
    }

    @Test
    fun `the wake lock is let go when the loop is cancelled`() {
        searchForServers(true)
        val service = restartBySystem()
        val lock = connectWakeLock()
        assertTrue(lock.isHeld)

        // The user picks a server: the loop gives way to that connection.
        service.connectToServer("192.168.1.100:8927")
        idle()

        assertEquals(ReconnectStatus.Idle, PlaybackService.reconnectStatus.value)
        assertFalse(lock.isHeld)
    }
}
