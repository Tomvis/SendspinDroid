package com.sendspindroid

import com.sendspindroid.sendspin.SendSpinHost
import com.sendspindroid.sendspin.crypto.AndroidPairingConfigStore
import com.sendspindroid.sendspin.crypto.ClientIdentity
import com.sendspindroid.sendspin.crypto.PairingConfigStore
import com.sendspindroid.sendspin.crypto.TrustStore
import com.sendspindroid.sendspin.pairing.PairedServers
import com.sendspindroid.sendspin.pairing.PairingOutcome

/** The player's settings and pairing state, kept in [UserSettings] and [PairedServers]. */
object AppSendSpinHost : SendSpinHost {
    override val softwareVersion: String get() = BuildConfig.VERSION_NAME
    override val lowMemoryMode: Boolean get() = UserSettings.lowMemoryMode
    override val highPowerMode: Boolean get() = UserSettings.highPowerMode

    override fun preferredCodec(): String = UserSettings.getPreferredCodec()
    override fun outputDelayMs(): Int = UserSettings.getOutputDelayMs()
    override fun setOutputDelayMs(delayMs: Int) {
        UserSettings.setOutputDelayMs(delayMs)
    }

    override fun clientIdentity(): ClientIdentity = UserSettings.getOrCreateClientIdentity()
    override fun trustStore(): TrustStore = UserSettings.getOrCreateTrustStore()
    override fun pairingConfigStore(): PairingConfigStore = AndroidPairingConfigStore()

    override fun pairingCodeFailures(): Int = UserSettings.getPairingCodeFailures()
    override fun setPairingCodeFailures(value: Int) {
        UserSettings.setPairingCodeFailures(value)
    }

    override fun rememberServerName(serverId: String, name: String) =
        PairedServers.rememberName(serverId, name)

    override fun forgetServerName(serverId: String) = UserSettings.setPairedServerName(serverId, null)

    override fun onPairingOutcome(outcome: PairingOutcome) = PairedServers.report(outcome)
}
