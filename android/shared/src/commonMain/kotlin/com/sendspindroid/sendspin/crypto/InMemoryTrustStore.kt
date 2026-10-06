package com.sendspindroid.sendspin.crypto

/**
 * The record semantics, with no persistence.
 *
 * All the logic lives here so it can be tested without Android; the encrypted
 * preferences implementation wraps this and adds loading and flushing.
 *
 * @param initial records restored from storage
 * @param pairingPskId the client's own Pairing PSK id. Not a record, but a
 *   record may not reuse it: a long-term PSK is a fresh secret, so a collision
 *   means the pairing went wrong.
 * @param storageIsEncrypted reported through [TrustStore]; always true for a
 *   store that never touches disk.
 */
open class InMemoryTrustStore(
    initial: List<PskRecord> = emptyList(),
    private val pairingPskId: String? = null,
    override val storageIsEncrypted: Boolean = true,
) : TrustStore {

    private val records = initial.toMutableList()

    /**
     * Called after any mutation so a subclass can flush.
     *
     * The persistence layer subclasses rather than wraps: a wrapper would have
     * to redeclare all seven members just to add a write, and the one that got
     * forgotten would lose records silently.
     *
     * @return false if the change could not be persisted.
     */
    protected open fun onChanged(): Boolean = true

    /**
     * [onChanged], with a write that throws counted as a write that failed.
     * An encrypted store can throw from the write itself; letting that escape
     * would leave this list changed and the stored one not.
     */
    private fun persist(): Boolean = runCatching { onChanged() }.getOrDefault(false)

    override fun listRecords(): List<PskRecord> = records.toList()

    override fun findByPskId(pskId: String): PskRecord? =
        records.firstOrNull { it.pskId == pskId }

    override fun addRecord(psk: ByteArray, serverId: String?): TrustStore.AddRecordResult {
        if (psk.size != Psk.PSK_SIZE) return TrustStore.AddRecordResult.Invalid

        val pskId = PskId.derive(psk)
        if (isClaimed(pskId)) return TrustStore.AddRecordResult.AlreadyExists

        val record = PskRecord(pskId, psk, serverId, used = false)
        // "The client MUST persist the new record, replacing any record it
        // already holds for the server."
        val before = records.toList()
        if (serverId != null) records.removeAll { it.serverId == serverId }
        records += record
        if (!persist()) {
            // Not stored, so not paired: put back what was held, or this
            // process would authenticate with a record the next one has lost.
            records.clear()
            records += before
            return TrustStore.AddRecordResult.StorageFailed
        }
        return TrustStore.AddRecordResult.Ok(record)
    }

    override fun removeRecord(pskId: String): Boolean {
        val removed = records.removeAll { it.pskId == pskId }
        if (removed) persist()
        return removed
    }

    override fun markUsed(pskId: String) {
        val index = records.indexOfFirst { it.pskId == pskId }
        if (index < 0) return
        if (records[index].used) return  // idempotent; no needless write
        records[index] = records[index].withUsed(true)
        persist()
    }

    override fun candidates(): List<Psk> =
        records.map { it.toPsk() } + SentinelPsk.psk

    /** A record may not reuse a `psk_id` this client already holds. */
    private fun isClaimed(pskId: String): Boolean =
        pskId == SentinelPsk.EXPECTED_PSK_ID ||
            pskId == pairingPskId ||
            records.any { it.pskId == pskId }
}
