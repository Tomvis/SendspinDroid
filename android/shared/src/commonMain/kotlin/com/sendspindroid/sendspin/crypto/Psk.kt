package com.sendspindroid.sendspin.crypto

/**
 * Which kind of secret a PSK is.
 *
 * The category is not cosmetic: it decides what the server is allowed to do
 * once the handshake matches it (`messaging.md#server--client-serveractivate`),
 * and the server declares which one it is using in Noise message 1 as
 * `psk_category`, so a match binds both sides to the same category.
 *
 * @param wire the `psk_category` code. "The codes share one length, so the
 *   encrypted payload's length is independent of the category."
 */
enum class PskCategory(val wire: String) {
    /** A per-(client, server) secret established by pairing. */
    LONG_TERM("lt"),

    /** The client's own long-lived pairing secret, distributed as a pairing token. */
    PAIRING("pr"),

    /** The published constant used before any pairing record exists. */
    SENTINEL("sn");

    companion object {
        fun fromWire(value: String): PskCategory? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * A 32-byte pre-shared key, tagged with its category and its derived `psk_id`.
 *
 * @param serverId the stored-pubkey binding of a [PskCategory.LONG_TERM]
 *   record, where the client "verifies that the matched PSK's stored
 *   `server_id` equals the one in `server/init`"
 *   (`connection.md#pre-shared-key`). Null means no binding: the Sentinel and
 *   the Pairing PSK.
 */
class Psk(
    bytes: ByteArray,
    val category: PskCategory,
    val serverId: String? = null,
) {
    init {
        require(bytes.size == PSK_SIZE) {
            "a Sendspin PSK is $PSK_SIZE bytes, got ${bytes.size}"
        }
        require(serverId == null || category == PskCategory.LONG_TERM) {
            "only a long-term record binds a server_id, not $category"
        }
    }

    private val secret = bytes.copyOf()

    /** A copy; the internal array is never handed out. */
    val bytes: ByteArray get() = secret.copyOf()

    /** `base64url(SHA-256("sendspin-psk-id-v1" || psk))`, 43 characters. */
    val pskId: String by lazy { PskId.derive(secret) }

    /** Category and `psk_id` only. The bytes must never reach a log. */
    override fun toString(): String = "Psk($category, pskId=$pskId)"

    companion object {
        const val PSK_SIZE = 32
    }
}
