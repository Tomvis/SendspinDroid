package com.sendspindroid.sendspin.crypto

/**
 * Storage for the pairing configuration.
 *
 * An interface so tests and `:conformance-client` can substitute an in-memory
 * implementation for the Android one.
 */
interface PairingConfigStore {

    sealed interface RotateResult {
        object Ok : RotateResult

        /** The new `psk_id` is already claimed by a record or the Sentinel. */
        object AlreadyExists : RotateResult

        /** Not a 32-byte PSK. */
        object Invalid : RotateResult

        /** The bytes were fine; persisting them was not. */
        object StorageFailed : RotateResult
    }

    /**
     * The current configuration, generating and persisting a Pairing PSK on
     * first use.
     */
    fun load(): PairingConfig

    /**
     * Offer or withdraw the `pairing_psk` method. Never discards the secret.
     *
     * @return false if the change could not be persisted.
     */
    fun setEnabled(enabled: Boolean): Boolean

    fun setUnpairedAccess(enabled: Boolean): Boolean

    /**
     * Replace the Pairing PSK.
     *
     * Only ever a deliberate local operator action. There is no timer, no
     * counter, and no automatic rotation anywhere - pairing success in
     * particular does not consume it.
     *
     * @param claimedPskIds every `psk_id` already spoken for - the trust store's
     *   records and the Sentinel. Passed in rather than read from a store so
     *   this stays testable.
     */
    fun rotatePairingPsk(newPsk: ByteArray, claimedPskIds: Set<String>): RotateResult
}
