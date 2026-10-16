package com.sendspindroid.playback

import android.content.Context
import android.os.Bundle
import android.os.Process
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.preference.PreferenceManager
import com.sendspindroid.UnifiedServerRepository
import com.sendspindroid.UserSettings
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * "Allow pairing" lifts the round limit's hold-back and resets the round
 * count: pairing.md "Rounds" asks for "a deliberate, manufacturer-defined
 * operator action". The service is exported, so any app could connect a
 * controller and press it, and with it guess codes without limit. Only the
 * app's own UI may send it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AllowPairingCommandTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private var created: ServiceController<PlaybackService>? = null
    private val allowPairing = SessionCommand(PlaybackService.COMMAND_ALLOW_PAIRING, Bundle.EMPTY)

    @Before
    fun setUp() {
        UserSettings.resetForTesting()
        UserSettings.initializeForTesting(
            PreferenceManager.getDefaultSharedPreferences(context),
            context.getSharedPreferences("sensitive", Context.MODE_PRIVATE),
        )
        mockkObject(UnifiedServerRepository)
        every { UnifiedServerRepository.initialize(any()) } just runs
        every { UnifiedServerRepository.getDefaultServer() } returns null
    }

    @After
    fun tearDown() {
        created?.destroy()
        unmockkAll()
        UserSettings.resetForTesting()
    }

    private fun service(): PlaybackService =
        Robolectric.buildService(PlaybackService::class.java).create().also { created = it }.get()

    private fun controller(uid: Int): MediaSession.ControllerInfo =
        MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            /* packageName = */ "com.example.controller",
            /* pid = */ 0,
            /* uid = */ uid,
            /* libraryVersion = */ 0,
            /* interfaceVersion = */ 0,
            /* trusted = */ false,
            /* connectionHints = */ Bundle.EMPTY,
            /* isPackageNameVerified = */ true,
        )

    private val otherApp = Process.myUid() + 1

    /** The "Allow pairing" prompt, which the command clears as it lets the attempt go ahead. */
    private val gestureRequested = PlaybackService::class.java.getDeclaredField("pairingGestureRequested")
        .apply { isAccessible = true }

    /** The service, showing the "Allow pairing" prompt for an attempt held back at the round limit. */
    private fun promptingService(): PlaybackService = service().also { gestureRequested.setBoolean(it, true) }

    @Test
    fun `another app's controller is not offered Allow pairing`() {
        val service = service()
        val other = controller(otherApp)

        val result = service.LibraryCallback().onConnect(service.onGetSession(other)!!, other)

        assertFalse(result.availableSessionCommands.contains(allowPairing))
        assertTrue(
            "the other commands are still offered",
            result.availableSessionCommands.contains(SessionCommand(PlaybackService.COMMAND_CONNECT, Bundle.EMPTY)),
        )
    }

    @Test
    fun `the app's own controller is offered Allow pairing`() {
        val service = service()
        val own = controller(Process.myUid())

        val result = service.LibraryCallback().onConnect(service.onGetSession(own)!!, own)

        assertTrue(result.availableSessionCommands.contains(allowPairing))
    }

    @Test
    fun `Allow pairing from another app is refused and changes nothing`() {
        val service = promptingService()
        val other = controller(otherApp)

        val result = service.LibraryCallback()
            .onCustomCommand(service.onGetSession(other)!!, other, allowPairing, Bundle.EMPTY).get()

        assertEquals(SessionError.ERROR_PERMISSION_DENIED, result.resultCode)
        assertTrue("the attempt is still held back", gestureRequested.getBoolean(service))
    }

    @Test
    fun `Allow pairing from the app's own UI is accepted`() {
        val service = promptingService()
        val own = controller(Process.myUid())

        val result = service.LibraryCallback()
            .onCustomCommand(service.onGetSession(own)!!, own, allowPairing, Bundle.EMPTY).get()

        assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
        assertFalse("the prompt goes once it is tapped", gestureRequested.getBoolean(service))
    }
}
