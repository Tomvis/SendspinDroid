package com.sendspindroid.sendspin.crypto

/**
 * The set of PSKs a handshake may match, and the lookup the Noise layer runs
 * against the `psk_id` and `psk_category` in Noise message 1.
 *
 * The lookup is category-bound (`connection.md#pre-shared-key`): the client
 * "compares the included `psk_id` to the hash of each candidate PSK of the
 * declared `psk_category`", so "a match binds both sides to the same category".
 * A `psk_id` the client holds only under a different category is a lookup miss.
 *
 * The Pairing PSK must be a member **at all times**, not merely while a pairing
 * screen is open, because the server re-handshakes to it unprompted.
 */
class PskCandidateSet(candidates: List<Psk>) {

    private val candidates = candidates.toList()

    /** Every candidate, in lookup order. */
    val all: List<Psk> get() = candidates

    /**
     * Find the PSK a server named by `psk_id`, among the candidates of
     * [category] only.
     *
     * A miss is not an error at this layer - it is the caller that maps it to
     * `NoiseHandshakeException.Cause.PskLookupMiss` and closes the socket with
     * no application-level message.
     */
    fun resolve(pskId: String, category: PskCategory): Psk? =
        candidates.firstOrNull { it.category == category && it.pskId == pskId }

    /** The outcome of choosing a PSK for a handshake. */
    sealed interface Selection {
        data class Matched(val candidate: Psk) : Selection

        /** No candidate of the declared category claims this `psk_id`. */
        object NoMatch : Selection

        /**
         * The `psk_id` matched a record bound to a different server.
         *
         * Kept apart from [NoMatch] because both close the socket with no
         * application-level message, so a log line is the only place they can
         * ever be distinguished - and they mean very different things. A miss
         * is "I have never been told about this secret"; a mismatch is "I hold
         * this secret, but for someone else", which is what a spoofed or
         * misconfigured server looks like.
         */
        data class ServerIdMismatch(val expected: String, val actual: String) : Selection
    }

    /**
     * Choose the PSK for a handshake: [resolve] then the stored-pubkey check,
     * in one call so the two cannot drift apart or be applied in the wrong
     * order.
     *
     * "After a `psk_id` match, the client verifies that the matched PSK's
     * stored `server_id` equals the one in `server/init`; mismatch fails the
     * handshake." A candidate with no binding (the Sentinel, the Pairing PSK)
     * passes unconditionally.
     */
    fun select(pskId: String, category: PskCategory, serverIdFromServerInit: String): Selection {
        val matched = resolve(pskId, category) ?: return Selection.NoMatch
        val bound = matched.serverId
        if (bound != null && bound != serverIdFromServerInit) {
            return Selection.ServerIdMismatch(expected = bound, actual = serverIdFromServerInit)
        }
        return Selection.Matched(matched)
    }

    companion object {
        /** A client with no records and no Pairing PSK: the Sentinel alone. */
        fun sentinelOnly(): PskCandidateSet =
            PskCandidateSet(listOf(SentinelPsk.psk))
    }
}
