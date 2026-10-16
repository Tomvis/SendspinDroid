package com.sendspindroid.sendspin

import com.sendspindroid.sendspin.crypto.ClientIdentity
import com.sendspindroid.sendspin.crypto.PairingConfigStore
import com.sendspindroid.sendspin.crypto.TrustStore
import com.sendspindroid.sendspin.pairing.PairingOutcome

/**
 * What a [SendSpin] client needs from the app it runs in: the settings it
 * reads and writes, the device's keys, and where pairing news goes.
 *
 * Every member is read when it is needed, not once, so a setting changed
 * while connected is seen by the next message that uses it.
 */
interface SendSpinHost {
    /** Reported to servers as `device_info.software_version`. */
    val softwareVersion: String

    /** Cap the PCM bit depth at 16 and keep buffers small. */
    val lowMemoryMode: Boolean

    /** Poll the connection more often when idle (15 s rather than 30 s). */
    val highPowerMode: Boolean

    /** List `color@v1` in `client/hello`, for the server's colors for the current audio. */
    val serverColors: Boolean

    /** The codec to list first in `client/hello`. */
    fun preferredCodec(): String

    /** Delay beyond the audio port, as last set by a server. */
    fun outputDelayMs(): Int

    /** Persist the delay a server set, so it survives a restart. */
    fun setOutputDelayMs(delayMs: Int)

    /** The device's long-term key pair; the same one on every call. */
    fun clientIdentity(): ClientIdentity

    /** The paired servers' PSK records; the same store on every call. */
    fun trustStore(): TrustStore

    /** The pairing PSK and the pairing options. Read once per client. */
    fun pairingConfigStore(): PairingConfigStore

    /** Dynamic Pairing Code rounds since the last verified `server_kc`, for the round limit. */
    fun pairingCodeFailures(): Int

    fun setPairingCodeFailures(value: Int)

    /** A paired server's name, for listing it. */
    fun rememberServerName(serverId: String, name: String)

    fun forgetServerName(serverId: String)

    /**
     * How a pairing ended. Called for every client, including one that is
     * not the connection being played from.
     */
    fun onPairingOutcome(outcome: PairingOutcome)
}
