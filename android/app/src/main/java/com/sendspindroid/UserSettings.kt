package com.sendspindroid

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.util.UUID
import com.sendspindroid.sendspin.crypto.ClientIdentity
import com.sendspindroid.sendspin.crypto.AndroidPairingConfigStore
import com.sendspindroid.sendspin.crypto.EncryptedPrefsTrustStore
import com.sendspindroid.sendspin.crypto.TrustStore

/**
 * Centralized access to user settings stored in SharedPreferences.
 *
 * Two separate SharedPreferences instances are used:
 * - [prefs]: Default SharedPreferences for non-sensitive UI settings (codec, layout, etc.).
 *   Uses the default file for compatibility with PreferenceFragmentCompat.
 * - [sensitivePrefs]: EncryptedSharedPreferences for auth tokens, credentials, and
 *   connection secrets (proxy servers, remote servers). Encrypted at rest using
 *   Android Keystore (L-15).
 *
 * If EncryptedSharedPreferences fails to initialize (broken Keystore on some OEMs),
 * [sensitivePrefs] falls back to a plain SharedPreferences with a warning log.
 * The app must not crash due to Keystore issues.
 *
 * Thread-safety: Uses @Volatile + double-checked locking for initialization,
 * matching the pattern in UnifiedServerRepository.
 */
object UserSettings {

    private const val TAG = "UserSettings"

    /** File name for the encrypted SharedPreferences store. */
    private const val ENCRYPTED_PREFS_FILE = "sendspin_secure_prefs"

    // Preference keys - must match keys in preferences.xml
    const val KEY_PLAYER_ID = "player_id"
    const val KEY_PLAYER_NAME = "player_name"
    const val KEY_SYNC_OFFSET_MS = "sync_offset_ms"
    const val KEY_LOW_MEMORY_MODE = "low_memory_mode"
    const val KEY_PREFERRED_CODEC = "preferred_codec"
    const val KEY_FULL_SCREEN_MODE = "full_screen_mode"
    const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    const val KEY_HIGH_POWER_MODE = "high_power_mode"
    const val KEY_MINI_PLAYER_POSITION = "mini_player_position"
    const val KEY_ALBUM_ARTISTS_ONLY = "album_artists_only"
    const val KEY_LAYOUT_MODE = "layout_mode"
    const val KEY_AUTO_START_ON_BOOT = "auto_start_on_boot"

    // Long-term PSK records from pairing (stored in encrypted prefs).
    // Record semantics live in the trust store; this is only the blob.
    const val KEY_PSK_RECORDS = "sendspin_psk_records"

    // Pairing configuration. The PSK is sensitive; the flags are policy.
    // All of it is read and written only through the pairing config store -
    // these are plain accessors with no policy of their own.
    const val KEY_PAIRING_PSK = "sendspin_pairing_psk"
    const val KEY_UNPAIRED_ACCESS = "sendspin_unpaired_access"
    const val KEY_DYNAMIC_PAIRING_CODE_ENABLED = "sendspin_dynamic_pairing_code_enabled"
    const val KEY_PAIRING_CODE_FAILURES = "pairing_code_failures"
    const val KEY_OUTPUT_DELAY_MS = "output_delay_ms"

    const val KEY_LAST_REMOTE_ID = "last_remote_id"
    const val KEY_LAST_PROXY_URL = "last_proxy_url"

    // Sync offset range limits (milliseconds)
    const val SYNC_OFFSET_MIN = -5000
    const val SYNC_OFFSET_MAX = 5000
    const val SYNC_OFFSET_DEFAULT = 0

    /** Non-sensitive UI/app preferences (default SharedPreferences). */
    @Volatile
    private var prefs: SharedPreferences? = null

    /**
     * Encrypted preferences for sensitive data (auth tokens, credentials).
     * Falls back to plain SharedPreferences if Keystore is broken.
     */
    @Volatile
    private var sensitivePrefs: SharedPreferences? = null

    /** True if [sensitivePrefs] is actually encrypted; false if using plain fallback. */
    @Volatile
    internal var isEncrypted: Boolean = false
        private set

    /** Application context for system service lookups (e.g., ActivityManager). */
    @Volatile
    private var appContext: Context? = null

    // In-memory fallback for player ID generated before prefs is available.
    // Ensures getPlayerId() always returns the same value even if called
    // before initialize(), preventing silent UUID loss (C-16).
    @Volatile
    private var cachedPlayerId: String? = null

    /**
     * Initialize UserSettings with application context.
     * Must be called before accessing settings, typically in Application.onCreate() or MainActivity.onCreate().
     * Thread-safe: uses double-checked locking so concurrent callers don't race.
     */
    fun initialize(context: Context) {
        if (prefs == null) {
            synchronized(this) {
                if (prefs == null) {
                    val appContext = context.applicationContext
                    this.appContext = appContext
                    val p = PreferenceManager.getDefaultSharedPreferences(appContext)
                    // If a player ID was generated before prefs was available,
                    // persist it now so it survives app restarts.
                    cachedPlayerId?.let { id ->
                        if (p.getString(KEY_PLAYER_ID, null).isNullOrBlank()) {
                            p.edit().putString(KEY_PLAYER_ID, id).apply()
                        }
                    }

                    // Initialize encrypted prefs for sensitive data (L-15).
                    sensitivePrefs = createEncryptedPrefs(appContext)

                    prefs = p
                }
            }
        }
    }

    /**
     * Creates an EncryptedSharedPreferences instance backed by Android Keystore.
     * If the Keystore is broken (known issue on some OEM devices), falls back to
     * a plain SharedPreferences and logs a warning. The app must not crash.
     */
    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        return try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            val encrypted = EncryptedSharedPreferences.create(
                ENCRYPTED_PREFS_FILE,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            isEncrypted = true
            Log.i(TAG, "Encrypted SharedPreferences initialized for sensitive data")
            encrypted
        } catch (e: Throwable) {
            // Catch Throwable (not just Exception) because some MediaTek and other
            // OEM devices throw Error subclasses from the Android Keystore JNI layer
            // during MasterKeys.getOrCreate() -- especially on first-run key generation.
            // Known to fail on Samsung, Xiaomi, Huawei, and some MediaTek devices.
            // Fall back to plain SharedPreferences so the app remains functional.
            Log.w(TAG, "EncryptedSharedPreferences failed, falling back to plain prefs. " +
                    "Auth tokens will NOT be encrypted on this device.", e)
            isEncrypted = false
            context.getSharedPreferences(ENCRYPTED_PREFS_FILE, Context.MODE_PRIVATE)
        }
    }

    /**
     * Gets the user-configured player name, or the device model as default.
     * This name is sent to the SendSpin server to identify this player.
     */
    fun getPlayerName(): String {
        val savedName = prefs?.getString(KEY_PLAYER_NAME, null)
        return if (savedName.isNullOrBlank()) {
            Build.MODEL
        } else {
            savedName
        }
    }

    /**
     * Sets the player name.
     */
    fun setPlayerName(name: String) {
        prefs?.edit()?.putString(KEY_PLAYER_NAME, name)?.apply()
    }

    /**
     * Gets the persistent player ID, generating one if it doesn't exist.
     * This ID is stable across app launches and name changes, allowing the server
     * to consistently identify this player.
     *
     * Thread-safe: if called before initialize(), generates a UUID and caches it
     * in memory. The cached ID is persisted when initialize() runs.
     * Double-checked locking ensures only one UUID is ever generated.
     */
    // ========== Sendspin identity (Curve25519) ==========

    private const val KEY_NOISE_IDENTITY = "sendspin_noise_identity"

    @Volatile
    private var cachedIdentity: ClientIdentity? = null

    /**
     * The client's persistent Sendspin identity.
     *
     * Its public half is the `client_id` on the wire, and it is a pre-message
     * input to every Noise handshake, so losing it makes this device a stranger
     * to every server it has paired with - and the failure mode the spec
     * prescribes is a silent socket close.
     *
     * Stored in [sensitivePrefs] with `commit()` rather than `apply()`: an
     * async write that loses a race with process death would mint a different
     * identity on next launch, breaking pairings with nothing to point at.
     *
     * A stored value that will not decode is NOT silently replaced. That state
     * means something went wrong, and quietly generating a new identity would
     * convert a recoverable problem into permanent, invisible unpairing.
     */
    fun getOrCreateClientIdentity(): ClientIdentity {
        cachedIdentity?.let { return it }
        synchronized(this) {
            cachedIdentity?.let { return it }

            val stored = sensitivePrefs?.getString(KEY_NOISE_IDENTITY, null)
            if (!stored.isNullOrBlank()) {
                val restored = ClientIdentity.fromStoredKey(stored)
                if (restored != null) {
                    cachedIdentity = restored
                    return restored
                }
                Log.e(
                    TAG,
                    "Stored Sendspin identity is unreadable. Refusing to overwrite it: " +
                        "minting a new one would silently unpair this device from every " +
                        "server. Clear app data deliberately if that is what you want."
                )
                error("stored Sendspin identity is corrupt")
            }

            val fresh = ClientIdentity.generate()
            val ok = sensitivePrefs?.edit()
                ?.putString(KEY_NOISE_IDENTITY, ClientIdentity.encodeForStorage(fresh))
                ?.commit() ?: false
            if (!ok) {
                Log.w(TAG, "Could not persist the Sendspin identity; it will not survive restart")
            }
            cachedIdentity = fresh
            Log.i(TAG, "Generated Sendspin identity ${fresh.clientId}")
            return fresh
        }
    }

    // ========== Long-term PSK records (trust store backing) ==========
    //
    // Only the opaque blob lives here. Record semantics - the psk_id namespace,
    // `used`, and the add/remove rules - belong to the trust store, so that
    // there is exactly one place they can be got wrong.

    @Volatile
    private var cachedTrustStore: TrustStore? = null

    /**
     * The process-wide trust store.
     *
     * One instance, for the same reason the identity is cached: two stores over
     * the same preferences would each hold their own record list, so a write
     * through one would be invisible to the other and the `psk_id` namespace
     * check would run against a stale view.
     */
    fun getOrCreateTrustStore(): TrustStore {
        cachedTrustStore?.let { return it }
        synchronized(this) {
            cachedTrustStore?.let { return it }
            val store = EncryptedPrefsTrustStore()
            cachedTrustStore = store
            return store
        }
    }

    /** The serialised record store, or "" when nothing has been paired. */
    fun getPskRecordsBlob(): String =
        sensitivePrefs?.getString(KEY_PSK_RECORDS, null) ?: ""

    /**
     * Persist the serialised record store.
     *
     * `commit()` rather than `apply()`, for the same reason the identity uses
     * it: an asynchronous write that loses a race with process death drops a
     * record the server has already accepted, and the next connect fails as
     * `unauthorized` with nothing to point at.
     *
     * @return false if there is no storage to write to.
     */
    fun setPskRecordsBlob(blob: String): Boolean =
        sensitivePrefs?.edit()?.putString(KEY_PSK_RECORDS, blob)?.commit() ?: false

    /** The stored Pairing PSK as base64url, or null when none has been minted. */
    fun getPairingPskBlob(): String? =
        sensitivePrefs?.getString(KEY_PAIRING_PSK, null)

    /** `commit()`: a lost write would mint a different PSK on next launch. */
    fun setPairingPskBlob(blob: String): Boolean =
        sensitivePrefs?.edit()?.putString(KEY_PAIRING_PSK, blob)?.commit() ?: false

    fun getUnpairedAccessEnabled(): Boolean =
        sensitivePrefs?.getBoolean(KEY_UNPAIRED_ACCESS, true) ?: true

    fun setUnpairedAccessEnabled(enabled: Boolean): Boolean =
        sensitivePrefs?.edit()?.putBoolean(KEY_UNPAIRED_ACCESS, enabled)?.commit() ?: false

    /**
     * Whether the `dynamic_pairing_code` method is offered. Default false: most
     * servers do not yet support this method, and advertising it unconditionally
     * can prevent connecting at all.
     */
    fun getDynamicPairingCodeEnabled(): Boolean =
        sensitivePrefs?.getBoolean(KEY_DYNAMIC_PAIRING_CODE_ENABLED, false) ?: false

    fun setDynamicPairingCodeEnabled(enabled: Boolean): Boolean =
        sensitivePrefs?.edit()?.putBoolean(KEY_DYNAMIC_PAIRING_CODE_ENABLED, enabled)?.commit() ?: false

    /**
     * The spec's `output_delay_ms`: delay beyond the audio port, 0-5000 ms.
     *
     * roles/player/v1.md requires this be persisted "locally across reboots
     * and server reconnections", so it lives here rather than only in the time
     * filter. Stored in the ordinary preferences, not the encrypted ones - it
     * is a speaker-placement setting, not a secret.
     *
     * Deliberately NOT the same quantity as the auto-measured hardware
     * latency, which the client compensates itself and must not report.
     */
    fun getOutputDelayMs(): Int =
        prefs?.getInt(KEY_OUTPUT_DELAY_MS, 0)?.coerceIn(0, 5000) ?: 0

    fun setOutputDelayMs(value: Int): Boolean =
        prefs?.edit()?.putInt(KEY_OUTPUT_DELAY_MS, value.coerceIn(0, 5000))?.commit() ?: false

    fun getPairingCodeFailures(): Int =
        sensitivePrefs?.getInt(KEY_PAIRING_CODE_FAILURES, 0) ?: 0

    fun setPairingCodeFailures(value: Int): Boolean =
        sensitivePrefs?.edit()?.putInt(KEY_PAIRING_CODE_FAILURES, value)?.commit() ?: false

    fun getPlayerId(): String {
        // Fast path: prefs available and ID already stored
        val p = prefs
        if (p != null) {
            val saved = p.getString(KEY_PLAYER_ID, null)
            if (!saved.isNullOrBlank()) return saved
        }

        // Check in-memory cache (covers pre-init calls)
        cachedPlayerId?.let { return it }

        // Slow path: generate under lock to prevent duplicate UUIDs
        synchronized(this) {
            // Re-check after acquiring lock
            cachedPlayerId?.let { return it }

            // Also re-check prefs (initialize() may have run while we waited)
            val p2 = prefs
            if (p2 != null) {
                val saved = p2.getString(KEY_PLAYER_ID, null)
                if (!saved.isNullOrBlank()) {
                    cachedPlayerId = saved
                    return saved
                }
            }

            val newId = UUID.randomUUID().toString()
            cachedPlayerId = newId

            // Persist immediately if prefs is available
            p2?.edit()?.putString(KEY_PLAYER_ID, newId)?.apply()

            return newId
        }
    }

    /**
     * Sets the player ID (typically only called internally on first launch).
     */
    fun setPlayerId(id: String) {
        cachedPlayerId = id
        prefs?.edit()?.putString(KEY_PLAYER_ID, id)?.apply()
    }

    /**
     * Gets the default player name (device model).
     * Used as placeholder/hint in settings UI.
     */
    fun getDefaultPlayerName(): String = Build.MODEL

    /**
     * Gets the manual sync offset in milliseconds.
     * Positive = delay playback (plays later), Negative = advance (plays earlier).
     */
    fun getSyncOffsetMs(): Int {
        return prefs?.getInt(KEY_SYNC_OFFSET_MS, SYNC_OFFSET_DEFAULT) ?: SYNC_OFFSET_DEFAULT
    }

    /**
     * Sets the manual sync offset in milliseconds.
     */
    fun setSyncOffsetMs(offsetMs: Int) {
        val clamped = offsetMs.coerceIn(SYNC_OFFSET_MIN, SYNC_OFFSET_MAX)
        prefs?.edit()?.putInt(KEY_SYNC_OFFSET_MS, clamped)?.apply()
    }

    /**
     * Whether Low Memory Mode is enabled.
     * When enabled:
     * - Album artwork is not fetched (uses placeholder)
     * - Audio buffer is reduced from 32MB to 8MB
     * - Coil ImageLoader is not initialized
     * Use when controlling playback from the server and UI isn't needed.
     */
    val lowMemoryMode: Boolean
        get() {
            val p = prefs ?: return false
            if (!p.contains(KEY_LOW_MEMORY_MODE)) {
                // First launch: auto-detect from device capabilities
                val am = appContext?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    ?: return false
                // OEM-flagged low RAM devices, or devices with 2GB or less total RAM
                if (am.isLowRamDevice) return true
                val memInfo = ActivityManager.MemoryInfo()
                am.getMemoryInfo(memInfo)
                return memInfo.totalMem <= 2L * 1024 * 1024 * 1024
            }
            return p.getBoolean(KEY_LOW_MEMORY_MODE, false)
        }

    /**
     * Whether Full Screen Mode is enabled.
     * When enabled, the status bar and navigation bar are hidden.
     * Users can reveal them by swiping from screen edges.
     */
    val fullScreenMode: Boolean
        get() = prefs?.getBoolean(KEY_FULL_SCREEN_MODE, false) ?: false

    /**
     * Whether Keep Screen On is enabled.
     * When enabled and audio is playing, the screen won't dim or lock.
     */
    val keepScreenOn: Boolean
        get() = prefs?.getBoolean(KEY_KEEP_SCREEN_ON, false) ?: false

    /**
     * Whether High Power Mode is enabled.
     * When enabled (for always-on/plugged-in devices):
     * - WiFi and CPU locks held for entire connection (not just streaming)
     * - WiFi lock uses low-latency mode (API 29+)
     * - Screen kept on while connected
     * - Infinite reconnection attempts (30s steady-state interval)
     * - Faster WebSocket ping (15s vs 30s) for quicker drop detection
     * - Prompts user to exempt app from battery optimization
     */
    val highPowerMode: Boolean
        get() = prefs?.getBoolean(KEY_HIGH_POWER_MODE, false) ?: false

    /**
     * Whether Auto-Start on Boot is enabled.
     * When enabled, the app starts PlaybackService on device boot and connects
     * to the default server without launching the UI.
     * Requires a default server to be set in the server list.
     */
    val autoStartOnBoot: Boolean
        get() = prefs?.getBoolean(KEY_AUTO_START_ON_BOOT, false) ?: false

    var albumArtistsOnly: Boolean
        get() = prefs?.getBoolean(KEY_ALBUM_ARTISTS_ONLY, false) ?: false
        set(value) { prefs?.edit()?.putBoolean(KEY_ALBUM_ARTISTS_ONLY, value)?.apply() }

    /**
     * Layout mode override for adaptive UI.
     * AUTO uses automatic detection; HEADUNIT forces head unit layout.
     */
    enum class LayoutMode {
        AUTO,
        HEADUNIT
    }

    var layoutMode: LayoutMode
        get() {
            val value = prefs?.getString(KEY_LAYOUT_MODE, "AUTO")
            return try {
                LayoutMode.valueOf(value ?: "AUTO")
            } catch (e: Exception) {
                LayoutMode.AUTO
            }
        }
        set(value) { prefs?.edit()?.putString(KEY_LAYOUT_MODE, value.name)?.apply() }

    /**
     * Position of the mini player in the navigation content area.
     */
    enum class MiniPlayerPosition {
        TOP, BOTTOM
    }

    /**
     * Gets the mini player position.
     * Defaults to TOP (current behavior).
     */
    val miniPlayerPosition: MiniPlayerPosition
        get() {
            val value = prefs?.getString(KEY_MINI_PLAYER_POSITION, "TOP")
            return try {
                MiniPlayerPosition.valueOf(value ?: "TOP")
            } catch (e: Exception) {
                MiniPlayerPosition.TOP
            }
        }

    /**
     * Sets the mini player position.
     */
    fun setMiniPlayerPosition(position: MiniPlayerPosition) {
        prefs?.edit()?.putString(KEY_MINI_PLAYER_POSITION, position.name)?.apply()
    }

    /**
     * Gets the preferred audio codec for streaming.
     * The server will be asked for this codec first; PCM is always used as fallback.
     * Values: "opus" (default), "flac"
     */
    fun getPreferredCodec(): String {
        return prefs?.getString(KEY_PREFERRED_CODEC, "opus") ?: "opus"
    }

    /**
     * Sets the preferred audio codec for streaming.
     */
    fun setPreferredCodec(codec: String) {
        prefs?.edit()?.putString(KEY_PREFERRED_CODEC, codec)?.apply()
    }

    // ========== Remote Access Settings ==========

    /**
     * Gets the last used Remote ID for quick reconnection.
     * Stored in encrypted prefs (contains connection credential).
     */
    fun getLastRemoteId(): String? {
        return sensitivePrefs?.getString(KEY_LAST_REMOTE_ID, null)
    }

    // ========== Proxy Access Settings ==========

    /**
     * Gets the last used proxy URL for quick reconnection.
     * Stored in encrypted prefs (proxy URL can reveal server identity).
     */
    fun getLastProxyUrl(): String? {
        return sensitivePrefs?.getString(KEY_LAST_PROXY_URL, null)
    }

    // ========== Testing Support ==========

    /**
     * Reset all internal state. For unit tests only -- do not call in production.
     */
    @Suppress("unused") // Called via reflection or directly from tests
    internal fun resetForTesting() {
        synchronized(this) {
            prefs = null
            sensitivePrefs = null
            isEncrypted = false
            cachedPlayerId = null
            appContext = null
            // Or a store built over one test's mock prefs would answer queries
            // in the next test, against records that test never wrote.
            cachedTrustStore = null
            AndroidPairingConfigStore.resetForTesting()
        }
    }

    /**
     * Initialize with pre-created SharedPreferences instances.
     * For unit tests only -- allows injecting mock prefs without Android context.
     */
    internal fun initializeForTesting(
        plainPrefs: SharedPreferences,
        encryptedPrefs: SharedPreferences,
        encrypted: Boolean = false
    ) {
        synchronized(this) {
            prefs = plainPrefs
            sensitivePrefs = encryptedPrefs
            isEncrypted = encrypted
        }
    }
}
