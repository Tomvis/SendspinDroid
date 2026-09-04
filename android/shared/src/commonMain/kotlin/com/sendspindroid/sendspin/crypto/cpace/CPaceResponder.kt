package com.sendspindroid.sendspin.crypto.cpace

import com.sendspindroid.sendspin.crypto.secureRandomBytes

/**
 * CPace responder (role B) with explicit mutual confirmation.
 *
 * Sendspin makes the server the initiator, so this client is always role B.
 * `CI` is empty, `ADa` is "server" and `ADb` is "client", per pairing.md.
 *
 * @param scalar test-only injection point. Production callers omit it and get
 *   a fresh CSPRNG scalar; the vector tests pass the oracle's scalar so the
 *   public share is reproducible.
 */
class CPaceResponder(
    private val prs: ByteArray,
    private val sid: ByteArray,
    scalar: ByteArray? = null,
) {
    private val yb: ByteArray = scalar ?: secureRandomBytes(32)
    private var peerShare: ByteArray? = null
    private var iskValue: ByteArray? = null
    private var macKeyValue: ByteArray? = null

    /** `Yb`, the client's CPace public share, sent as `pake_msg_2`. */
    val publicShare: ByteArray by lazy {
        val g = CPaceX25519.calculateGenerator(prs, EMPTY_CI, sid)
        com.sendspindroid.sendspin.crypto.x25519ScalarMult(yb, g)
    }

    /** The 64-byte intermediate session key. Only valid after [derive]. */
    val isk: ByteArray
        get() = iskValue ?: error("derive() has not been called")

    /**
     * Consume the server's share `Ya`.
     *
     * @return false when the share is unusable -- wrong length, or a low-order
     *   point that [CPaceX25519.scalarMultVfy] rejects. False is a protocol
     *   error at the call site, never a retry.
     */
    fun derive(peerShare: ByteArray): Boolean {
        val k = CPaceX25519.scalarMultVfy(yb, peerShare) ?: return false
        this.peerShare = peerShare.copyOf()
        val computed = CPaceX25519.deriveIsk(
            sid = sid,
            k = k,
            ya = peerShare,
            ada = AD_SERVER,
            yb = publicShare,
            adb = AD_CLIENT,
        )
        iskValue = computed
        macKeyValue = CPaceX25519.macKey(sid, computed)
        return true
    }

    /** Verify the server's MCF tag `Ta` in constant time. */
    fun verify(serverKc: ByteArray): Boolean {
        val key = macKeyValue ?: error("derive() has not been called")
        val ya = peerShare ?: error("derive() has not been called")
        val expected = CPaceX25519.mcfTag(key, ya, AD_SERVER)
        if (expected.size != serverKc.size) return false
        var diff = 0
        for (i in expected.indices) diff = diff or (expected[i].toInt() xor serverKc[i].toInt())
        return diff == 0
    }

    /** The client's MCF tag `Tb`, sent as `client_kc`. */
    fun tag(): ByteArray {
        val key = macKeyValue ?: error("derive() has not been called")
        return CPaceX25519.mcfTag(key, publicShare, AD_CLIENT)
    }

    private companion object {
        val EMPTY_CI = ByteArray(0)
        val AD_SERVER = "server".encodeToByteArray()
        val AD_CLIENT = "client".encodeToByteArray()
    }
}
