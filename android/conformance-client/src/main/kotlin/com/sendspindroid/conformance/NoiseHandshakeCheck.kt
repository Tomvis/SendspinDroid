package com.sendspindroid.conformance

import com.sendspindroid.sendspin.crypto.Base64Url
import com.sendspindroid.sendspin.crypto.ClientIdentity
import com.sendspindroid.sendspin.crypto.Psk
import com.sendspindroid.sendspin.crypto.PskCandidateSet
import com.sendspindroid.sendspin.crypto.PskCategory
import com.sendspindroid.sendspin.crypto.SentinelPsk
import com.sendspindroid.sendspin.crypto.asNoiseCrypto
import com.sendspindroid.sendspin.crypto.secureRandomBytes
import com.sendspindroid.sendspin.pairing.PairingToken
import com.sendspindroid.sendspin.protocol.ActivationOutcome
import com.sendspindroid.sendspin.protocol.Activity
import com.sendspindroid.sendspin.protocol.NoiseWireCodec
import com.sendspindroid.sendspin.protocol.RehandshakeDriver
import com.sendspindroid.sendspin.protocol.SendSpinHandshakeDriver
import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import com.sendspindroid.sendspin.protocol.ServerActivateRules
import com.sendspindroid.sendspin.protocol.StreamConfig
import com.sendspindroid.sendspin.protocol.message.BinaryMessageParser
import com.sendspindroid.sendspin.protocol.message.InitMessages
import com.sendspindroid.sendspin.protocol.message.MessageBuilder
import com.sendspindroid.sendspin.protocol.message.MessageParser
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * End-to-end check of the encrypted path against a real server.
 *
 * Drives the SAME code the Android app uses - `SendSpinHandshakeDriver`,
 * `NoiseWireCodec`, `ServerActivateRules`, `BinaryMessageParser` and the real
 * `MessageBuilder` - over a real WebSocket against an aiosendspin server
 * running with `allow_unencrypted=False`.
 *
 * The identity is **persisted** rather than generated per run. That matters:
 * an unpaired client only becomes playback-capable once the operator trusts its
 * `client_id`, so a tool that mints a fresh identity every run can never be
 * granted playback and will report empty activities forever - which is exactly
 * what earlier versions of this check did.
 *
 * Usage: `NoiseHandshakeCheck <ws://host:port/sendspin> [identity-file]
 *   [--hold | --hold-seconds=N] [--expect-audio] [--expect-rehandshake]`
 *
 * The tool also holds a pairing PSK, persisted beside the identity, and writes
 * its pairing token to `<identity-file>.token`. Handing that token to the
 * server (`dev_server.py --pair-token-file`) makes it re-handshake this
 * connection to the pairing PSK, which exercises the in-band re-handshake. The
 * pairing itself is not run: the tool answers the pairing activation with
 * `pair/abort` reason `user_cancelled`.
 */
object NoiseHandshakeCheck {

    @JvmStatic
    fun main(args: Array<String>) {
        // Positional args only - a flag landing in the identity-file slot would
        // silently mint a new identity, and the server would see a client it has
        // never been told to trust.
        val positional = args.filterNot { it.startsWith("--") }
        val url = positional.getOrNull(0) ?: "ws://127.0.0.1:8927/sendspin"
        // Music Assistant only offers player setup for a client that is
        // currently online, and a 4-second connection is gone before anyone
        // can click anything. --hold keeps it up until interrupted.
        val hold = args.contains("--hold") || System.getenv("NOISECHECK_HOLD") != null
        // A bounded hold, for scripted runs: long enough for the server to
        // trust the client, re-activate it and stream to it, then report.
        val holdSeconds = args.firstOrNull { it.startsWith("--hold-seconds=") }
            ?.substringAfter('=')?.toLongOrNull()
        // Fail unless audio arrived and survived every check in [AudioCheck].
        val expectAudio = args.contains("--expect-audio")
        // Fail unless the server re-handshook and then activated, with no
        // second hello in between.
        val expectRehandshake = args.contains("--expect-rehandshake")
        val identityFile = File(positional.getOrNull(1) ?: ".dev/noisecheck-identity.key")
        val identity = loadOrCreateIdentity(identityFile)
        val pairingPsk = loadOrCreatePairingPsk(File(identityFile.path + ".pairing-psk"))
        val tokenFile = File(identityFile.path + ".token")
        tokenFile.writeText(
            PairingToken.encode(InitMessages.decodeKey32(identity.clientId)!!, pairingPsk.bytes)
        )
        // The Sentinel and the pairing PSK, as a client with no records holds.
        val candidates = PskCandidateSet(listOf(SentinelPsk.psk, pairingPsk))

        println("client_id : ${identity.clientId}")
        println("identity  : ${identityFile.path} (stable across runs)")
        println("pair token: ${tokenFile.path}")
        println("connecting: $url")
        println()

        val done = CountDownLatch(1)
        var failure: String? = null
        var codec: NoiseWireCodec? = null
        var matchedCategory = PskCategory.SENTINEL
        var serverHellos = 0
        var activationSeen = false
        var ready: SendSpinHandshakeDriver.Event.TransportReady? = null
        var handshakeHash = ByteArray(0)
        var rehandshakes = 0
        var activationsAfterRehandshake = 0
        // "The server MUST NOT start new application messages after sending
        // Noise message 1, nor the client after receiving it" until the
        // server/activate that follows.
        var rehandshakeInProgress = false
        var grantedActivities: Set<Activity> = emptySet()
        var grantedRoles: List<String> = emptyList()
        val audio = AudioCheck()

        val json = Json { ignoreUnknownKeys = true }
        val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()

        lateinit var socket: WebSocket
        lateinit var driver: SendSpinHandshakeDriver

        fun fail(reason: String) {
            if (failure == null) failure = reason
            done.countDown()
        }

        fun sendEncrypted(text: String, label: String) {
            val c = codec ?: return fail("no transport for $label")
            runBlocking { c.encodeJson(text).forEach { socket.send(ByteString.of(*it)) } }
            println("-> enc   $label")
        }

        driver = SendSpinHandshakeDriver(
            identity = identity,
            candidates = candidates,
            onEvent = { event ->
                when (event) {
                    is SendSpinHandshakeDriver.Event.SendCleartext -> {
                        println("-> text  ${event.text.take(90)}")
                        socket.send(event.text)
                    }
                    is SendSpinHandshakeDriver.Event.TransportReady -> {
                        println("HANDSHAKE OK  server=${event.serverInit.serverId} " +
                            "psk=${event.matchedPsk.category}")
                        matchedCategory = event.matchedPsk.category
                        ready = event
                        handshakeHash = event.transport.handshakeHash
                        // Nothing is sent yet: client/hello answers server/hello.
                        codec = NoiseWireCodec(event.transport)
                    }
                    is SendSpinHandshakeDriver.Event.Fail ->
                        fail("${event.reason}: ${event.detail}")
                }
            },
        )

        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = driver.start()

            override fun onMessage(webSocket: WebSocket, text: String) =
                driver.onCleartextFrame(text.toByteArray(Charsets.UTF_8))

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val c = codec ?: return fail("binary frame before transport mode")
                when (val decoded = c.decode(bytes.toByteArray())) {
                    is NoiseWireCodec.Decoded.Json -> {
                        val obj = runCatching {
                            json.parseToJsonElement(decoded.text).jsonObject
                        }.getOrNull() ?: return
                        val type = obj["type"]?.jsonPrimitive?.contentOrNull
                        val payload = obj["payload"] as? kotlinx.serialization.json.JsonObject
                        if (type != SendSpinProtocol.MessageType.SERVER_TIME) {
                            println("<- enc   ${decoded.text.take(140)}")
                        }

                        when (type) {
                            SendSpinProtocol.MessageType.NOISE_HANDSHAKE -> {
                                // An in-band re-handshake, driven by the same
                                // RehandshakeDriver the app uses.
                                rehandshakeInProgress = true
                                val session = ready ?: return fail("re-handshake before a handshake")
                                val outcome = RehandshakeDriver(
                                    identity = identity,
                                    candidates = candidates,
                                    serverId = session.serverInit.serverId,
                                    serverStaticKey = session.serverInit.serverStaticKey,
                                    suite = SendSpinHandshakeDriver.DEFAULT_SUITE,
                                    priorHandshakeHash = handshakeHash,
                                ).handle(payload?.get("data")?.jsonPrimitive?.contentOrNull)
                                when (outcome) {
                                    is RehandshakeDriver.Outcome.Fail ->
                                        return fail("re-handshake failed: ${outcome.reason}")
                                    is RehandshakeDriver.Outcome.Reply -> {
                                        // Message 2 under the old keys, then the swap.
                                        runBlocking {
                                            c.encodeAndSwap(
                                                SendSpinProtocol.BinaryType.JSON,
                                                outcome.replyJson.encodeToByteArray(),
                                                outcome.transport.asNoiseCrypto(),
                                            )
                                        }.forEach { socket.send(ByteString.of(*it)) }
                                        handshakeHash = outcome.transport.handshakeHash
                                        matchedCategory = outcome.matched.category
                                        rehandshakes++
                                        println("RE-HANDSHAKE OK  psk=${outcome.matched.category} " +
                                            "(no client/hello sent; awaiting server/activate)")
                                    }
                                }
                            }

                            SendSpinProtocol.MessageType.SERVER_HELLO -> {
                                serverHellos++
                                // The real builder, so this exercises what the
                                // app sends, and at the point the app sends it.
                                sendEncrypted(
                                    MessageBuilder.buildClientHello(
                                        deviceName = "NoiseHandshakeCheck",
                                        bufferCapacity = 1_680_000,
                                        manufacturer = "conformance",
                                        supportedFormats = listOf(
                                            MessageBuilder.FormatEntry("pcm", 48000, 2, 16)
                                        ),
                                        softwareVersion = "check",
                                        unpairedAccessEnabled = true,
                                    ),
                                    "client/hello",
                                )
                            }

                            SendSpinProtocol.MessageType.SERVER_ACTIVATE -> {
                                rehandshakeInProgress = false
                                if (rehandshakes > 0) activationsAfterRehandshake++
                                val activate = ServerActivateRules.parse(payload)
                                    ?: return fail("malformed server/activate")
                                // Same rules the app applies.
                                val outcome = ServerActivateRules.evaluate(
                                    activate = activate,
                                    category = matchedCategory,
                                    unpairedAccessEnabled = true,
                                    previousRoles = grantedRoles,
                                    isFirstActivation = !activationSeen,
                                    offeredPairMethods = setOf("pairing_psk"),
                                )
                                when (outcome) {
                                    is ActivationOutcome.Accept -> {
                                        activationSeen = true
                                        grantedActivities = activate.activities
                                        grantedRoles = outcome.activeRoles
                                        println("         activation accepted: " +
                                            "activities=${activate.activities} roles=${outcome.activeRoles}")
                                        // Only now may we speak, and every role
                                        // this activation made active is owed
                                        // its state object.
                                        sendEncrypted(
                                            MessageBuilder.buildPlayerState(
                                                volume = 100, muted = false, available = true,
                                                playerRoleActive = outcome.activeRoles
                                                    .contains(SendSpinProtocol.Roles.PLAYER),
                                                artworkRoleActive = outcome.activeRoles
                                                    .contains(SendSpinProtocol.Roles.ARTWORK),
                                            ),
                                            "client/state available=true",
                                        )
                                        sendEncrypted(
                                            MessageBuilder.buildClientTime(
                                                System.nanoTime() / 1000
                                            ),
                                            "client/time",
                                        )
                                        if (Activity.PAIRING in activate.activities) {
                                            // This tool does not pair; decline
                                            // so the server leaves pairing.
                                            sendEncrypted(
                                                MessageBuilder.buildPairAbort("user_cancelled"),
                                                "pair/abort user_cancelled",
                                            )
                                        }
                                        if (hold) {
                                            println("         holding connection open - " +
                                                "configure this player in Music Assistant now")
                                        } else if (holdSeconds != null) {
                                            // The hold loop below ends the run.
                                        } else if (Activity.PLAYBACK in activate.activities) {
                                            Thread { Thread.sleep(4000); done.countDown() }.start()
                                        } else {
                                            done.countDown()
                                        }
                                    }
                                    is ActivationOutcome.Close ->
                                        fail("activation rejected: ${outcome.goodbyeReason}")
                                    is ActivationOutcome.AbortPairing ->
                                        fail("pairing aborted: ${outcome.reason}")
                                }
                            }

                            SendSpinProtocol.MessageType.STREAM_START ->
                                MessageParser.parseStreamStart(payload)?.let { audio.onStreamStart(it) }
                        }
                    }
                    is NoiseWireCodec.Decoded.Typed -> {
                        // The real parser: a wrong header size shows up here as
                        // a payload that is not a whole number of PCM frames.
                        val message = BinaryMessageParser.parse(decoded.type, decoded.body)
                        if (message is BinaryMessageParser.BinaryMessage.Audio) {
                            audio.onChunk(message)
                            // --hold never reaches the RESULT block, so without a
                            // line here a streaming session and a silent one look
                            // exactly the same: no output at all.
                            if (audio.chunks == 1 || audio.chunks % 100 == 0) {
                                println("<- enc   audio chunk #${audio.chunks} " +
                                    "ts=${message.timestampMicros} " +
                                    "send_ahead=${message.sendAheadMicros} " +
                                    "${message.payload.size}B")
                            }
                        } else println("<- enc   binary type=${decoded.type} ${decoded.body.size}B (ignored)")
                    }
                    is NoiseWireCodec.Decoded.Buffered ->
                        println("<- enc   fragment buffered")
                    is NoiseWireCodec.Decoded.ProtocolError ->
                        fail("decode failed: ${decoded.reason}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                fail("socket failure: ${t.message}")

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                println("socket closed: $code $reason")
                done.countDown()
            }
        })

        if (hold || holdSeconds != null) {
            println()
            if (hold) {
                println("HOLDING. Configure the player in Music Assistant, then watch for a new")
                println("server/activate below. Ctrl-C to stop.")
            } else {
                println("HOLDING for $holdSeconds seconds.")
            }
            println()
            val deadline = holdSeconds?.let { System.nanoTime() + it * 1_000_000_000L }
            // A real client keeps clock sync running; without it MA may treat
            // the session as idle.
            while (deadline == null || System.nanoTime() < deadline) {
                Thread.sleep(2000)
                if (done.count == 0L) break
                if (activationSeen && !rehandshakeInProgress && failure == null) {
                    sendEncrypted(
                        MessageBuilder.buildClientTime(System.nanoTime() / 1000),
                        "client/time",
                    )
                }
            }
            done.countDown()
        }

        val finished = done.await(40, TimeUnit.SECONDS)
        socket.close(1000, "done")
        client.dispatcher.executorService.shutdown()

        println()
        println("RESULT")
        println("  handshake      : ${if (codec != null) "OK" else "FAILED"}")
        println("  server/hello   : ${if (serverHellos > 0) "received ($serverHellos)" else "MISSING"}")
        println("  re-handshakes  : $rehandshakes " +
            "(server/activate after: $activationsAfterRehandshake, psk now $matchedCategory)")
        println("  activities     : ${grantedActivities.map { it.wireName }}")
        println("  active_roles   : $grantedRoles")
        audio.report().forEach { println("  $it") }
        println()

        val err = failure
        val audioProblem = if (expectAudio) audio.problem() else null
        when {
            err != null -> exitFail(err)
            !finished -> exitFail("timed out")
            serverHellos == 0 -> exitFail("no encrypted server/hello")
            audioProblem != null -> exitFail("audio: $audioProblem")
            expectRehandshake && rehandshakes == 0 -> exitFail("the server never re-handshook")
            expectRehandshake && activationsAfterRehandshake == 0 ->
                exitFail("no server/activate followed the re-handshake")
            expectRehandshake && serverHellos != 1 ->
                exitFail("server/hello was sent $serverHellos times; a re-handshake re-sends neither hello")
            expectAudio || expectRehandshake -> println(
                "PASS:" + (if (expectAudio) " audio received and verified" else "") +
                    (if (expectAudio && expectRehandshake) ";" else "") +
                    (if (expectRehandshake) " re-handshake completed and activated" else "")
            )
            Activity.PLAYBACK !in grantedActivities -> {
                // Never a client bug, but two very different situations share
                // this exit. A server declares 'playback' only while playback is
                // actually running, so an idle server reporting no playback
                // activity is entirely normal. Separate that from "the server is
                // offering this client nothing" so nobody looks in the wrong place.
                println("INCOMPLETE: handshake and encrypted traffic verified, but the")
                println("  server declared no playback activity.")
                println()
                if (grantedRoles.isEmpty()) {
                    println("  It granted no roles either, so this client is not eligible to")
                    println("  play at all. Either the operator has not trusted this client_id,")
                    println("  or the player has not been set up on the server. For the dev")
                    println("  server, start it with --trust-all-unpaired. Trust is remembered")
                    println("  per client_id, which is why this tool persists its identity.")
                } else {
                    println("  It did grant roles, so this client IS eligible and playback is")
                    println("  simply not running right now. A server declares")
                    println("  activities=['playback'] when playback starts, not at connect")
                    println("  time. Start playback on this player and re-run with --hold to")
                    println("  watch the second server/activate and the audio frames arrive.")
                }
                kotlin.system.exitProcess(2)
            }
            else -> println("PASS: playback granted (activities=${grantedActivities.map { it.wireName }})")
        }
    }

    /**
     * Checks the audio the server streamed, as parsed by [BinaryMessageParser].
     *
     * A header read at the wrong size cannot pass these: the payload stops
     * being a whole number of PCM frames, the timestamps stop following one
     * another, and the frame counter `dev_server.py --play-test-audio` writes
     * into the samples stops being contiguous.
     */
    private class AudioCheck {
        var chunks = 0
            private set
        private var config: StreamConfig? = null
        private var frames = 0L
        private var bytes = 0L
        private var partialFrameChunks = 0
        private var timestampGaps = 0
        private var nextTimestamp: Long? = null
        private var minSendAhead = Long.MAX_VALUE
        private var maxSendAhead = Long.MIN_VALUE
        private var firstCounter: Long? = null
        private var nextCounter: Long? = null
        private var counterBreaks = 0
        private val digest = MessageDigest.getInstance("SHA-256")

        @Synchronized
        fun onStreamStart(config: StreamConfig) {
            this.config = config
        }

        @Synchronized
        fun onChunk(chunk: BinaryMessageParser.BinaryMessage.Audio) {
            chunks++
            bytes += chunk.payload.size
            digest.update(chunk.payload)
            minSendAhead = minOf(minSendAhead, chunk.sendAheadMicros)
            maxSendAhead = maxOf(maxSendAhead, chunk.sendAheadMicros)

            val format = config ?: return
            if (format.codec != "pcm") return
            val frameSize = format.channels * (format.bitDepth / 8)
            if (chunk.payload.size % frameSize != 0) partialFrameChunks++
            val chunkFrames = chunk.payload.size / frameSize
            frames += chunkFrames

            // Each chunk starts where the previous one ended, to the microsecond
            // the server's own integer rounding allows.
            val expected = nextTimestamp
            if (expected != null && kotlin.math.abs(chunk.timestampMicros - expected) > 1) {
                timestampGaps++
            }
            nextTimestamp = chunk.timestampMicros + chunkFrames * 1_000_000L / format.sampleRate

            // dev_server.py --play-test-audio: 16-bit stereo, where frame n
            // carries the low 16 bits of n on the left and the next 16 on the
            // right, little-endian.
            if (format.channels == 2 && format.bitDepth == 16) {
                for (i in 0 until chunkFrames) {
                    val p = chunk.payload
                    val o = i * 4
                    val counter = (p[o].toLong() and 0xFF) or
                        ((p[o + 1].toLong() and 0xFF) shl 8) or
                        ((p[o + 2].toLong() and 0xFF) shl 16) or
                        ((p[o + 3].toLong() and 0xFF) shl 24)
                    val want = nextCounter
                    if (want == null) firstCounter = counter else if (counter != want) counterBreaks++
                    nextCounter = counter + 1
                }
            }
        }

        @Synchronized
        fun report(): List<String> = buildList {
            add("audio chunks   : $chunks ($bytes payload bytes, 13-byte header parsed)")
            if (chunks == 0) return@buildList
            val format = config
            add("stream format  : " + (format?.let {
                "${it.codec} ${it.sampleRate}Hz ${it.channels}ch ${it.bitDepth}bit"
            } ?: "no stream/start seen"))
            add("pcm frames     : $frames (chunks with a partial frame: $partialFrameChunks)")
            add("timestamps     : $timestampGaps discontinuities between consecutive chunks")
            add("send_ahead     : min=${minSendAhead}us max=${maxSendAhead}us")
            add("frame counter  : first=$firstCounter, breaks=$counterBreaks " +
                "(0 breaks = byte-exact against dev_server.py --play-test-audio)")
            add("payload sha256 : " + digest.digest().joinToString("") { "%02x".format(it) })
        }

        /** Why the audio is not acceptable, or null if it is. */
        @Synchronized
        fun problem(): String? = when {
            chunks == 0 -> "no audio chunks received"
            config == null -> "audio arrived with no stream/start"
            partialFrameChunks > 0 -> "$partialFrameChunks chunks were not whole PCM frames"
            timestampGaps > 0 -> "$timestampGaps timestamp discontinuities"
            counterBreaks > 0 -> "$counterBreaks breaks in the frame counter"
            else -> null
        }
    }

    private fun exitFail(reason: String): Nothing {
        println("FAIL: $reason")
        kotlin.system.exitProcess(1)
    }

    private fun loadOrCreatePairingPsk(file: File): Psk {
        if (file.exists()) {
            Base64Url.decodeOrNull(file.readText().trim())
                ?.takeIf { it.size == Psk.PSK_SIZE }
                ?.let { return Psk(it, PskCategory.PAIRING) }
        }
        val fresh = secureRandomBytes(Psk.PSK_SIZE)
        file.parentFile?.mkdirs()
        file.writeText(Base64Url.encode(fresh))
        return Psk(fresh, PskCategory.PAIRING)
    }

    private fun loadOrCreateIdentity(file: File): ClientIdentity {
        if (file.exists()) {
            val restored = ClientIdentity.fromStoredKey(file.readText().trim())
            if (restored != null) return restored
            println("WARNING: ${file.path} is unreadable; generating a new identity. " +
                "The server will not recognise it as the same client.")
        }
        val fresh = ClientIdentity.generate()
        file.parentFile?.mkdirs()
        file.writeText(ClientIdentity.encodeForStorage(fresh))
        println("generated a new identity at ${file.path}")
        return fresh
    }
}
