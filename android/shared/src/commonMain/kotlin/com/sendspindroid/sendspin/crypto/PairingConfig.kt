package com.sendspindroid.sendspin.crypto

/**
 * The client's pairing configuration.
 *
 * @param pairingPsk the per-device pairing PSK. "MUST be drawn from a CSPRNG
 *   per device and MUST NOT be a fixed default shared across devices", and
 *   long-lived: "a successful pairing does not consume it". Nothing may rewrite
 *   it except a deliberate operator rotation.
 * @param pairingPskEnabled whether the method is offered. A disabled method's
 *   PSK leaves the candidate set, so a handshake naming it fails as a lookup
 *   miss, and the descriptor leaves `client/hello`.
 * @param unpairedAccessEnabled whether this client admits a server with no
 *   pairing record. Advertised as `unpaired_access.enabled`.
 * @param dynamicPairingCodeEnabled whether the `dynamic_pairing_code` method is
 *   offered. Default false: `server/hello` carries no signal for whether the
 *   server understands this method, and advertising it unconditionally broke
 *   the handshake against aiosendspin 9.1.1 (its `PairMethod` enum predates
 *   `dynamic_pairing_code`, so `client/hello` fails to deserialize). Until a
 *   capability signal exists, this is an explicit opt-in the user makes
 *   knowing older servers may reject the connection.
 */
class PairingConfig(
    pairingPsk: ByteArray,
    val pairingPskEnabled: Boolean,
    val unpairedAccessEnabled: Boolean,
    val dynamicPairingCodeEnabled: Boolean,
) {
    init {
        require(pairingPsk.size == Psk.PSK_SIZE) {
            "a Sendspin PSK is ${Psk.PSK_SIZE} bytes, got ${pairingPsk.size}"
        }
    }

    private val secret = pairingPsk.copyOf()

    /** A copy; the internal array is never handed out. */
    val pairingPsk: ByteArray get() = secret.copyOf()

    /** `psk_id` of the Pairing PSK, whether or not the method is enabled. */
    val pairingPskId: String by lazy { PskId.derive(secret) }

    fun withEnabled(enabled: Boolean): PairingConfig =
        PairingConfig(secret, enabled, unpairedAccessEnabled, dynamicPairingCodeEnabled)

    fun withUnpairedAccess(enabled: Boolean): PairingConfig =
        PairingConfig(secret, pairingPskEnabled, enabled, dynamicPairingCodeEnabled)

    fun withDynamicPairingCodeEnabled(enabled: Boolean): PairingConfig =
        PairingConfig(secret, pairingPskEnabled, unpairedAccessEnabled, enabled)

    fun withPairingPsk(psk: ByteArray): PairingConfig =
        PairingConfig(psk, pairingPskEnabled, unpairedAccessEnabled, dynamicPairingCodeEnabled)

    // Hand-written: a data class holding a ByteArray compares by reference.
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is PairingConfig &&
                pairingPskEnabled == other.pairingPskEnabled &&
                unpairedAccessEnabled == other.unpairedAccessEnabled &&
                dynamicPairingCodeEnabled == other.dynamicPairingCodeEnabled &&
                secret.contentEquals(other.secret)
            )

    override fun hashCode(): Int {
        var result = secret.contentHashCode()
        result = 31 * result + pairingPskEnabled.hashCode()
        result = 31 * result + unpairedAccessEnabled.hashCode()
        result = 31 * result + dynamicPairingCodeEnabled.hashCode()
        return result
    }

    /** Never the bytes. */
    override fun toString(): String =
        "PairingConfig(pairingPskId=$pairingPskId, enabled=$pairingPskEnabled, " +
            "unpairedAccess=$unpairedAccessEnabled, dynamicPairingCode=$dynamicPairingCodeEnabled)"
}
