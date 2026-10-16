package com.sendspindroid.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import androidx.media3.session.MediaSession
import androidx.preference.PreferenceManager
import com.google.common.collect.ImmutableList
import com.sendspindroid.UnifiedServerRepository
import com.sendspindroid.UserSettings
import com.sendspindroid.coordinator.TransportState
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowAudioManager

/**
 * The volume on an output whose STREAM_MUSIC index cannot hold one (#355):
 * HDMI on an Android TV box, fixed-volume or full-volume. There the whole
 * volume is track gain, which Android does not keep as it keeps an index, so
 * the service keeps it, and a restart or media coming back to that output
 * plays and reports it again. roles/player/v1.md, "Volume and mute":
 * "Persisting `volume` and `muted` across reboots is RECOMMENDED for players."
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ShadowTvAudioManager::class])
class PlaybackServiceTvVolumeTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val maxIndex = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    private val server = UnifiedServer(
        id = "server-1",
        name = "Living Room",
        local = LocalConnection("192.168.1.100:8927"),
    )
    private val clientState = MutableStateFlow<TransportState>(TransportState.Idle)
    private var controller: ServiceController<PlaybackService>? = null

    // As the service's AudioTrack and its routing query use them
    private val mediaAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    @Before
    fun setUp() {
        UserSettings.resetForTesting()
        UserSettings.initializeForTesting(prefs, context.getSharedPreferences("sensitive", Context.MODE_PRIVATE))
        // Search mode: nothing listens on a port of this machine.
        prefs.edit().putBoolean(UserSettings.KEY_SEARCH_FOR_SERVERS, true).commit()
        mockkObject(UnifiedServerRepository)
        every { UnifiedServerRepository.initialize(any()) } just runs
        every { UnifiedServerRepository.getServer(server.id) } returns server
        every { UnifiedServerRepository.getDefaultServer() } returns null
        mockkConstructor(SendSpin::class)
        every { anyConstructed<SendSpin>().connectionState } returns clientState
        every { anyConstructed<SendSpin>().connect(any<SendSpinEndpoint>()) } answers {
            clientState.value = TransportState.Connecting
        }
        routeMediaTo(AudioDeviceInfo.TYPE_HDMI)
    }

    @After
    fun tearDown() {
        controller?.destroy()
        unmockkAll()
        UserSettings.resetForTesting()
        ShadowTvAudioManager.fullVolume = false
        ShadowTvAudioManager.volumeFixed = false
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun start(): PlaybackService {
        controller?.destroy()
        val created = Robolectric.buildService(PlaybackService::class.java).create()
        controller = created
        idle()
        return created.get()
    }

    private fun routeMediaTo(type: Int) {
        val device = AudioDeviceInfoBuilder.newBuilder().setType(type).build()
        shadowOf(audioManager).setAudioDevicesForAttributes(mediaAttributes, ImmutableList.of(device))
    }

    /** HDMI with CEC: the platform holds the index at max whatever is set. */
    private fun fullVolumeHdmi() {
        ShadowTvAudioManager.fullVolume = true
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxIndex, 0)
    }

    private fun trackGain(service: PlaybackService): Float {
        val engine = PlaybackService::class.java.getDeclaredField("engine")
            .apply { isAccessible = true }.get(service)
        return SendSpinEngine::class.java.getDeclaredField("trackGain")
            .apply { isAccessible = true }.getFloat(engine)
    }

    /** What the service does once routing settles after an output came or went. */
    private fun syncOutputVolume(service: PlaybackService) {
        val sync = PlaybackService::class.java.getDeclaredField("outputVolumeSync")
            .apply { isAccessible = true }.get(service) as Runnable
        sync.run()
        idle()
    }

    /** The volume the service last sent the activity, in its session extras. */
    private fun publishedVolume(service: PlaybackService): Int {
        val session = PlaybackService::class.java.getDeclaredField("mediaSession")
            .apply { isAccessible = true }.get(service) as MediaSession
        return session.sessionExtras.getInt(PlaybackService.EXTRA_VOLUME)
    }

    /** This device's gain in dB at STREAM_MUSIC [index] on HDMI. */
    private fun hdmiDb(index: Int): Double =
        audioManager.getStreamVolumeDb(AudioManager.STREAM_MUSIC, index, AudioDeviceInfo.TYPE_HDMI).toDouble()

    /** The volume [index] stands for on its own, on this device's curve. */
    private fun deviceVolume(index: Int): Int = VolumeLaw.volumeFor(index, ::hdmiDb)

    private fun connect(service: PlaybackService) {
        service.setCurrentServer(server.id)
        service.connectToServer(server.local!!.address)
        idle()
    }

    @Test
    fun `a volume set on a full-volume output comes back after a restart`() {
        fullVolumeHdmi()
        start().setVolume(0.3f)

        val service = start()

        assertEquals(30, service.playbackState.value.volume)
        assertEquals(VolumeLaw.amplitude(30), trackGain(service), 0f)
        connect(service)
        verify { anyConstructed<SendSpin>().setInitialVolume(30, false) }
    }

    @Test
    fun `a volume near the top on a full-volume output comes back after a restart`() {
        fullVolumeHdmi()
        start().setVolume(0.3f)
        // Volume 90 asks for the max index, which the output reads back as
        // asked: only the type found full-volume before, in the run before,
        // tells that it plays as track gain
        assertEquals(maxIndex, VolumeLaw.indexFor(90, maxIndex, ::hdmiDb))
        start().setVolume(0.9f)

        val service = start()

        assertEquals(90, service.playbackState.value.volume)
        assertEquals(VolumeLaw.amplitude(90), trackGain(service), 0f)
    }

    @Test
    fun `a volume set on a fixed-volume output comes back after a restart`() {
        // The platform holds a fixed-volume device's index at max
        ShadowTvAudioManager.volumeFixed = true
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxIndex, 0)
        start().setVolume(0.3f)

        val service = start()

        assertEquals(30, service.playbackState.value.volume)
        assertEquals(VolumeLaw.amplitude(30), trackGain(service), 0f)
        connect(service)
        verify { anyConstructed<SendSpin>().setInitialVolume(30, false) }
    }

    @Test
    fun `media back on a full-volume output plays the volume set there`() {
        fullVolumeHdmi()
        val service = start()
        service.setVolume(0.3f)

        // Bluetooth headphones: a variable output, with an index of its own
        ShadowTvAudioManager.fullVolume = false
        routeMediaTo(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 10, 0)
        syncOutputVolume(service)
        assertEquals(1f, trackGain(service), 0f)

        // Unplugged: media back on HDMI, its index at max
        routeMediaTo(AudioDeviceInfo.TYPE_HDMI)
        fullVolumeHdmi()
        syncOutputVolume(service)

        assertEquals(30, service.playbackState.value.volume)
        assertEquals(VolumeLaw.amplitude(30), trackGain(service), 0f)
        verify { anyConstructed<SendSpin>().setVolume(0.3) }
        // The slider too, which showed the headphones' volume
        assertEquals(30, publishedVolume(service))
    }

    @Test
    fun `an output that holds its index again starts from that index`() {
        fullVolumeHdmi()
        start().setVolume(0.3f)
        // CEC turned off: HDMI takes the index it is given
        ShadowTvAudioManager.fullVolume = false
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 10, 0)

        val service = start()

        assertEquals(deviceVolume(10), service.playbackState.value.volume)
        assertEquals(1f, trackGain(service), 0f)
    }
}

/**
 * AudioManager on an Android TV box over HDMI. [fullVolume]: the platform
 * keeps the STREAM_MUSIC index at max whatever is set (HDMI with CEC), and
 * isVolumeFixed does not say so. [volumeFixed]: a fixed-volume device.
 */
@Implements(AudioManager::class)
class ShadowTvAudioManager : ShadowAudioManager() {

    @Implementation
    override fun setStreamVolume(streamType: Int, index: Int, flags: Int) {
        super.setStreamVolume(streamType, if (fullVolume) getStreamMaxVolume(streamType) else index, flags)
    }

    @Implementation
    fun isVolumeFixed(): Boolean = volumeFixed

    companion object {
        var fullVolume = false
        var volumeFixed = false
    }
}
