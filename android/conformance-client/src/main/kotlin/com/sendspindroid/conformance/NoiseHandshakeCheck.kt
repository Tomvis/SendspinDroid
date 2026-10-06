package com.sendspindroid.conformance

import com.sendspindroid.sendspin.crypto.Base64Url
import com.sendspindroid.sendspin.crypto.ClientIdentity
import com.sendspindroid.sendspin.crypto.InMemoryTrustStore
import com.sendspindroid.sendspin.crypto.PairingConfig
import com.sendspindroid.sendspin.crypto.Psk
import com.sendspindroid.sendspin.crypto.PskCandidateSet
import com.sendspindroid.sendspin.crypto.PskCandidates
import com.sendspindroid.sendspin.crypto.PskCategory
import com.sendspindroid.sendspin.crypto.PskRecordCodec
import com.sendspindroid.sendspin.crypto.TrustStore
import com.sendspindroid.sendspin.crypto.asNoiseCrypto
import com.sendspindroid.sendspin.crypto.secureRandomBytes
import com.sendspindroid.sendspin.pairing.DynamicPairingAction
import com.sendspindroid.sendspin.pairing.DynamicPairingCodeFlow
import com.sendspindroid.sendspin.pairing.DynamicPairingEvent
import com.sendspindroid.sendspin.pairing.PairAbortReason
import com.sendspindroid.sendspin.pairing.PairMethod
import com.sendspindroid.sendspin.pairing.PairingAction
import com.sendspindroid.sendspin.pairing.PairingCounterStore
import com.sendspindroid.sendspin.pairing.PairingEvent
import com.sendspindroid.sendspin.pairing.PairingFailureCounter
import com.sendspindroid.sendspin.pairing.PairingPskFlow
import com.sendspindroid.sendspin.pairing.PairingToken
import com.sendspindroid.sendspin.protocol.ActivationOutcome
import com.sendspindroid.sendspin.protocol.Activity
import com.sendspindroid.sendspin.protocol.ControllerState
import com.sendspindroid.sendspin.protocol.GoodbyeReason
import com.sendspindroid.sendspin.protocol.NoiseWireCodec
import com.sendspindroid.sendspin.protocol.RehandshakeDriver
import com.sendspindroid.sendspin.protocol.SendSpinHandshakeDriver
import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import com.sendspindroid.sendspin.protocol.ServerActivateRules
import com.sendspindroid.sendspin.protocol.StreamConfig
import com.sendspindroid.sendspin.protocol.message.ArtworkReceiver
import com.sendspindroid.sendspin.protocol.message.BinaryMessageParser
import com.sendspindroid.sendspin.protocol.message.InitMessages
import com.sendspindroid.sendspin.protocol.message.MessageBuilder
import com.sendspindroid.sendspin.protocol.message.MessageParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * End-to-end check of the encrypted path against a real server.
 *
 * Drives the SAME code the Android app uses - `SendSpinHandshakeDriver`,
 * `NoiseWireCodec`, `ServerActivateRules`, `BinaryMessageParser`,
 * `ArtworkReceiver` and the real `MessageBuilder` - over a real WebSocket against an aiosendspin server
 * running with `allow_unencrypted=False`.
 *
 * The identity is **persisted** rather than generated per run. That matters:
 * an unpaired client only becomes playback-capable once the operator trusts its
 * `client_id`, so a tool that mints a fresh identity every run can never be
 * granted playback and will report empty activities forever - which is exactly
 * what earlier versions of this check did.
 *
 * Usage: `NoiseHandshakeCheck <ws://host:port/sendspin> [identity-file]
 *   [--hold | --hold-seconds=N] [--expect-audio] [--expect-artwork]
 *   [--expect-rehandshake] [--pair] [--expect-mismatches=N] [--expect-paired]
 *   [--seek]`
 *
 * With `--seek` it sends one `seek` and one `seek_relative`, built by the real
 * `MessageBuilder.buildCommand`, once the server's controller state offers
 * both (`dev_server.py --offer-seek`), and fails if that never happens. The
 * server's log shows whether it accepted them.
 *
 * The tool also holds a pairing PSK, persisted beside the identity, and writes
 * its pairing token to `<identity-file>.token`. Handing that token to the
 * server (`dev_server.py --pair-token-file`) makes it re-handshake this
 * connection to the pairing PSK, which exercises the in-band re-handshake.
 * Without `--pair` the pairing itself is not run: the tool answers the pairing
 * activation with `pair/abort` reason `user_cancelled`.
 *
 * With `--pair` it offers both pairing methods and runs whichever the server
 * picks through the app's own `PairingPskFlow` / `DynamicPairingCodeFlow`. The
 * record lands in `<identity-file>.records` and is a handshake candidate on
 * the next run; a dynamic pairing code is written to `<identity-file>.code`
 * while it is being shown (`dev_server.py --pair-dynamic-code-file`). The run
 * passes once the record is persisted, the server has re-handshaken to it and
 * a `server/activate` has followed. `--expect-paired` instead fails unless the
 * initial handshake itself matched a stored record.
 *
 * What `--pair` does NOT drive is `SendSpinProtocolHandler`, which lives in
 * the Android module: counting `pairing_index`, routing a message to the flow
 * its activation selected and the attempt timer are re-implemented here in a
 * few lines, and are covered by the handler's unit tests instead.
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
        // Fail unless artwork arrived as [ArtworkCheck] requires.
        val expectArtwork = args.contains("--expect-artwork")
        // Fail unless the server re-handshook and then activated, with no
        // second hello in between.
        val expectRehandshake = args.contains("--expect-rehandshake")
        // Run a pairing to completion instead of declining it.
        val pair = args.contains("--pair")
        // With --pair: the number of attempts that must end in our own
        // pair/abort pairing_code_mismatch before one succeeds.
        val expectMismatches = args.firstOrNull { it.startsWith("--expect-mismatches=") }
            ?.substringAfter('=')?.toIntOrNull()
        // Fail unless the initial handshake matched a stored pairing record.
        val expectPaired = args.contains("--expect-paired")
        // Send a seek and a seek_relative once the server offers both.
        val seek = args.contains("--seek")
        val identityFile = File(positional.getOrNull(1) ?: ".dev/noisecheck-identity.key")
        val identity = loadOrCreateIdentity(identityFile)
        val pairingPsk = loadOrCreatePairingPsk(File(identityFile.path + ".pairing-psk"))
        val tokenFile = File(identityFile.path + ".token")
        tokenFile.writeText(
            PairingToken.encode(InitMessages.decodeKey32(identity.clientId)!!, pairingPsk.bytes)
        )
        val recordsFile = File(identityFile.path + ".records")
        val codeFile = File(identityFile.path + ".code")
        val store = object : InMemoryTrustStore(
            initial = PskRecordCodec.decode(if (recordsFile.exists()) recordsFile.readText() else ""),
        ) {
            override fun onChanged() = recordsFile.writeText(PskRecordCodec.encode(listRecords()))
        }
        val pairingConfig = PairingConfig(
            pairingPsk.bytes, unpairedAccessEnabled = true, dynamicPairingCodeEnabled = pair,
        )
        // The app's candidate set: the records, the Sentinel and the pairing
        // PSK. Rebuilt per handshake, so a record persisted by this run is a
        // candidate for the re-handshake that follows it.
        fun candidates() = PskCandidateSet(PskCandidates.build(store.listRecords(), pairingConfig))
        val pairMethods = if (pair) {
            listOf(
                MessageBuilder.PairMethodDescriptor.PAIRING_PSK,
                MessageBuilder.PairMethodDescriptor.DYNAMIC_PAIRING_CODE,
            )
        } else {
            listOf(MessageBuilder.PairMethodDescriptor.PAIRING_PSK)
        }

        println("client_id : ${identity.clientId}")
        println("identity  : ${identityFile.path} (stable across runs)")
        println("pair token: ${tokenFile.path}")
        println("records   : ${recordsFile.path} (${store.listRecords().size} stored)")
        println("connecting: $url")
        println()

        val done = CountDownLatch(1)
        var failure: String? = null
        var matchedCategory = PskCategory.SENTINEL
        var initialCategory: PskCategory? = null
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
        val artwork = ArtworkCheck()
        var controllerState: ControllerState? = null
        var seeksSent = false

        // Pairing (--pair). pairing_index counts every pairing server/activate
        // since the last Noise handshake.
        var pairingIndex = 0
        var activeMethod: String? = null
        val pskFlow = PairingPskFlow()
        var dynamicFlow: DynamicPairingCodeFlow? = null
        var recordsPersisted = 0
        var mismatches = 0
        val abortsSent = mutableListOf<String>()
        val counterStore = object : PairingCounterStore {
            private var value = 0
            override fun load() = value
            override fun save(value: Int) { this.value = value }
        }

        val json = Json { ignoreUnknownKeys = true }
        val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()

        lateinit var socket: EncryptedSocket

        fun fail(reason: String) {
            if (failure == null) failure = reason
            done.countDown()
        }

        fun sendEncrypted(text: String, label: String) {
            if (!socket.send(text)) return fail("no transport for $label")
            println("-> enc   $label")
        }

        fun sendPairAbort(reason: String) {
            abortsSent += reason
            if (reason == PairAbortReason.PAIRING_CODE_MISMATCH) mismatches++
            sendEncrypted(MessageBuilder.buildPairAbort(reason), "pair/abort $reason")
        }

        fun persistRecord(psk: ByteArray) {
            val serverId = ready?.serverInit?.serverId ?: return fail("paired before a handshake")
            when (val result = store.addRecord(psk, serverId)) {
                is TrustStore.AddRecordResult.Ok -> {
                    recordsPersisted++
                    println("PAIRED  record persisted: psk_id=${result.record.pskId} server=$serverId " +
                        "(${store.listRecords().size} stored)")
                }
                else -> fail("could not persist the pairing record: $result")
            }
        }

        fun runPskActions(event: PairingEvent) {
            for (action in pskFlow.onEvent(event)) {
                when (action) {
                    is PairingAction.SendPairInit -> sendEncrypted(
                        MessageBuilder.buildClientPairInit(action.pairingIndex),
                        "client/pair-init pairing_index=${action.pairingIndex}",
                    )
                    is PairingAction.SendPairFinalize -> sendEncrypted(
                        MessageBuilder.buildClientPairFinalize(action.longTermPsk),
                        "client/pair-finalize (long_term_psk)",
                    )
                    is PairingAction.SendPairAbort -> sendPairAbort(action.reason)
                    is PairingAction.PersistRecord -> persistRecord(action.psk)
                    // The run is bounded by its own timeout.
                    PairingAction.StartAttemptTimeout, PairingAction.ClearAttemptTimeout -> Unit
                }
            }
        }

        fun runDynamicActions(event: DynamicPairingEvent) {
            val flow = dynamicFlow ?: return
            for (action in flow.onEvent(event)) {
                when (action) {
                    is DynamicPairingAction.SendPairInit -> sendEncrypted(
                        MessageBuilder.buildClientPairInit(action.pairingIndex, action.commitB),
                        "client/pair-init pairing_index=${action.pairingIndex} (commit_B)",
                    )
                    is DynamicPairingAction.SendPairPending -> sendEncrypted(
                        MessageBuilder.buildClientPairPending(action.pairingIndex),
                        "client/pair-pending pairing_index=${action.pairingIndex}",
                    )
                    is DynamicPairingAction.EmitPairingCode -> {
                        println("PAIRING CODE  ${action.code}  (written to ${codeFile.path})")
                        codeFile.writeText(action.code)
                    }
                    is DynamicPairingAction.SendPairAuth -> sendEncrypted(
                        MessageBuilder.buildClientPairAuth(action.yb), "client/pair-auth",
                    )
                    is DynamicPairingAction.SendPairConfirm -> sendEncrypted(
                        MessageBuilder.buildClientPairConfirm(action.tb, action.wrappedNonceB),
                        "client/pair-confirm",
                    )
                    is DynamicPairingAction.SendPairFinalize -> sendEncrypted(
                        MessageBuilder.buildClientPairFinalizeWrapped(action.wrappedPsk),
                        "client/pair-finalize (wrapped_psk)",
                    )
                    is DynamicPairingAction.SendPairAbort -> sendPairAbort(action.reason)
                    is DynamicPairingAction.PersistRecord -> persistRecord(action.psk)
                    DynamicPairingAction.StopEmittingCode -> if (codeFile.delete()) {
                        println("         pairing code cleared")
                    }
                    DynamicPairingAction.ProtocolError -> fail("dynamic pairing protocol error")
                    // No gesture UI and no timer: the run is bounded by its own timeout.
                    DynamicPairingAction.RequestGesture, DynamicPairingAction.StartAttemptTimeout -> Unit
                }
            }
        }

        socket = EncryptedSocket(
            client = client,
            url = url,
            identity = identity,
            candidates = candidates(),
            onCleartextSent = { println("-> text  ${it.take(90)}") },
            onReady = { event ->
                println("HANDSHAKE OK  server=${event.serverInit.serverId} " +
                    "psk=${event.matchedPsk.category}")
                event.lookupMiss?.let { println("SENTINEL FALLBACK  $it") }
                matchedCategory = event.matchedPsk.category
                initialCategory = event.matchedPsk.category
                ready = event
                handshakeHash = event.transport.handshakeHash
            },
            onFrame = fun(decoded: NoiseWireCodec.Decoded) {
                when (decoded) {
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
                                    candidates = candidates(),
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
                                        socket.sendAndSwap(
                                            SendSpinProtocol.BinaryType.JSON,
                                            outcome.replyJson.encodeToByteArray(),
                                            outcome.transport.asNoiseCrypto(),
                                        )
                                        handshakeHash = outcome.transport.handshakeHash
                                        matchedCategory = outcome.matched.category
                                        rehandshakes++
                                        // The count restarts with every Noise
                                        // handshake, and a dynamic attempt is
                                        // bound to the old handshake hash.
                                        pairingIndex = 0
                                        dynamicFlow = null
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
                                        supportedPairMethods = pairMethods,
                                    ),
                                    "client/hello",
                                )
                            }

                            SendSpinProtocol.MessageType.SERVER_ACTIVATE -> {
                                rehandshakeInProgress = false
                                if (rehandshakes > 0) activationsAfterRehandshake++
                                val activate = ServerActivateRules.parse(payload)
                                    ?: return fail("malformed server/activate")
                                val pairing = Activity.PAIRING in activate.activities
                                if (pairing) pairingIndex++
                                // Same rules the app applies.
                                val outcome = ServerActivateRules.evaluate(
                                    activate = activate,
                                    category = matchedCategory,
                                    unpairedAccessEnabled = true,
                                    previousRoles = grantedRoles,
                                    isFirstActivation = !activationSeen,
                                    offeredPairMethods = pairMethods.map { it.wireName }.toSet(),
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
                                        activeMethod = if (pairing) activate.pairingMethod else null
                                        if (!pair) {
                                            if (pairing) {
                                                // Not asked to pair; decline
                                                // so the server leaves pairing.
                                                sendEncrypted(
                                                    MessageBuilder.buildPairAbort("user_cancelled"),
                                                    "pair/abort user_cancelled",
                                                )
                                            }
                                        } else when (activeMethod) {
                                            PairMethod.PAIRING_PSK -> {
                                                runDynamicActions(DynamicPairingEvent.NonPairingActivation)
                                                runPskActions(
                                                    PairingEvent.PairingActivation(pairingIndex, matchedCategory)
                                                )
                                            }
                                            PairMethod.DYNAMIC_PAIRING_CODE -> {
                                                runPskActions(PairingEvent.NonPairingActivation)
                                                if (dynamicFlow == null) {
                                                    dynamicFlow = DynamicPairingCodeFlow(
                                                        handshakeHash,
                                                        PairingFailureCounter(counterStore),
                                                        SendSpinHandshakeDriver.DEFAULT_SUITE,
                                                    )
                                                }
                                                runDynamicActions(
                                                    DynamicPairingEvent.PairingActivation(pairingIndex)
                                                )
                                            }
                                            else -> {
                                                runPskActions(PairingEvent.NonPairingActivation)
                                                runDynamicActions(DynamicPairingEvent.NonPairingActivation)
                                            }
                                        }
                                        val pairingDone = recordsPersisted > 0 &&
                                            matchedCategory == PskCategory.LONG_TERM
                                        if (hold) {
                                            println("         holding connection open - " +
                                                "configure this player in Music Assistant now")
                                        } else if (holdSeconds != null) {
                                            // The hold loop below ends the run.
                                        } else if (pair && !pairingDone) {
                                            // Still waiting for the pairing, the
                                            // re-handshake to its record and the
                                            // activation after that.
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

                            SendSpinProtocol.MessageType.SERVER_STATE -> {
                                // Full state: a controller object replaces what we held.
                                MessageParser.parseServerState(payload).controller
                                    ?.let { controllerState = it }
                                val supported = controllerState?.supportedCommands.orEmpty()
                                val seekMaxMs = controllerState?.seekMaxMs
                                // The app's rules: the role is active, both
                                // commands are in the latest supported_commands
                                // and the position is within 0..seek_max_ms.
                                if (seek && !seeksSent && !rehandshakeInProgress &&
                                    SendSpinProtocol.Roles.CONTROLLER in grantedRoles &&
                                    "seek" in supported && "seek_relative" in supported &&
                                    seekMaxMs != null
                                ) {
                                    seeksSent = true
                                    sendEncrypted(
                                        MessageBuilder.buildCommand("seek", positionMs = seekMaxMs / 2),
                                        "client/command seek position_ms=${seekMaxMs / 2}",
                                    )
                                    sendEncrypted(
                                        MessageBuilder.buildCommand("seek_relative", offsetMs = -10_000),
                                        "client/command seek_relative offset_ms=-10000",
                                    )
                                }
                            }

                            SendSpinProtocol.MessageType.PAIR_ABORT -> {
                                val reason = payload?.get("reason")?.jsonPrimitive?.contentOrNull
                                    ?: "unspecified"
                                runPskActions(PairingEvent.PairAbortReceived(reason))
                                runDynamicActions(DynamicPairingEvent.PairAbortReceived(reason))
                            }

                            SendSpinProtocol.MessageType.SERVER_PAIR_INIT ->
                                MessageParser.parseServerPairInit(payload)
                                    ?.let { runDynamicActions(DynamicPairingEvent.ServerPairInit(it)) }
                                    ?: fail("malformed server/pair-init")

                            SendSpinProtocol.MessageType.SERVER_PAIR_AUTH ->
                                MessageParser.parseServerPairAuth(payload)
                                    ?.let { runDynamicActions(DynamicPairingEvent.ServerPairAuth(it)) }
                                    ?: fail("malformed server/pair-auth")

                            SendSpinProtocol.MessageType.SERVER_PAIR_CONFIRM ->
                                MessageParser.parseServerPairConfirm(payload)
                                    ?.let { runDynamicActions(DynamicPairingEvent.ServerPairConfirm(it)) }
                                    ?: fail("malformed server/pair-confirm")

                            SendSpinProtocol.MessageType.SERVER_PAIR_FINALIZE ->
                                if (activeMethod == PairMethod.DYNAMIC_PAIRING_CODE) {
                                    runDynamicActions(DynamicPairingEvent.ServerPairFinalize)
                                } else {
                                    runPskActions(PairingEvent.ServerPairFinalize)
                                }

                            SendSpinProtocol.MessageType.STREAM_START -> {
                                MessageParser.parseStreamStart(payload)?.let { audio.onStreamStart(it) }
                                if (payload?.containsKey("artwork") == true) artwork.onStreamStart()
                            }

                            SendSpinProtocol.MessageType.STREAM_END -> {
                                val roles = payload?.get("roles")?.jsonArray
                                    ?.map { it.jsonPrimitive.content }
                                if (roles == null || roles.any {
                                        SendSpinProtocol.isStreamRole(it, SendSpinProtocol.StreamRoles.ARTWORK)
                                    }
                                ) artwork.onStreamEnd()
                            }
                        }
                    }
                    is NoiseWireCodec.Decoded.Typed -> if (
                        decoded.type - SendSpinProtocol.BinaryType.ARTWORK_BASE in 0..3
                    ) {
                        // The real receiver: reassembly and every protocol
                        // error the spec lists.
                        artwork.onMessage(decoded.type, decoded.body)?.let { fail("artwork: $it") }
                    } else {
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
            },
            onFail = { fail(it) },
            onClosed = { code, reason ->
                println("socket closed: $code $reason")
                done.countDown()
            },
        )

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
        // The process is going away, so a session that got as far as the
        // hellos says so before it closes. OkHttp writes queued frames ahead
        // of the close frame.
        if (failure == null && serverHellos > 0 && !rehandshakeInProgress) {
            sendEncrypted(
                MessageBuilder.buildGoodbye(GoodbyeReason.SHUTDOWN),
                "client/goodbye shutdown",
            )
        }
        socket.close()
        client.dispatcher.executorService.shutdown()

        println()
        println("RESULT")
        println("  handshake      : ${if (socket.codec != null) "OK" else "FAILED"}")
        println("  server/hello   : ${if (serverHellos > 0) "received ($serverHellos)" else "MISSING"}")
        println("  re-handshakes  : $rehandshakes " +
            "(server/activate after: $activationsAfterRehandshake, psk now $matchedCategory)")
        println("  activities     : ${grantedActivities.map { it.wireName }}")
        println("  active_roles   : $grantedRoles")
        println("  initial psk    : $initialCategory")
        if (pair) {
            println("  pairing        : $recordsPersisted record(s) persisted, " +
                "${store.listRecords().size} stored; pair/abort sent: $abortsSent")
        }
        audio.report().forEach { println("  $it") }
        artwork.report().forEach { println("  $it") }
        println()

        val err = failure
        val audioProblem = if (expectAudio) audio.problem() else null
        val artworkProblem = if (expectArtwork) artwork.problem() else null
        when {
            err != null -> exitFail(err)
            !finished -> exitFail("timed out")
            serverHellos == 0 -> exitFail("no encrypted server/hello")
            audioProblem != null -> exitFail("audio: $audioProblem")
            artworkProblem != null -> exitFail("artwork: $artworkProblem")
            expectPaired && initialCategory != PskCategory.LONG_TERM ->
                exitFail("the initial handshake matched $initialCategory, not a pairing record")
            pair && recordsPersisted == 0 -> exitFail("no pairing record was persisted")
            pair && matchedCategory != PskCategory.LONG_TERM ->
                exitFail("the server never re-handshook to the new long-term PSK (psk is $matchedCategory)")
            pair && activationsAfterRehandshake == 0 ->
                exitFail("no server/activate followed the re-handshake")
            pair && serverHellos != 1 ->
                exitFail("server/hello was sent $serverHellos times; a re-handshake re-sends neither hello")
            pair && store.listRecords().size != 1 ->
                exitFail("${store.listRecords().size} records stored for one server; a new record replaces the old")
            expectMismatches != null && mismatches != expectMismatches ->
                exitFail("sent $mismatches pairing_code_mismatch aborts, expected $expectMismatches")
            pair -> println(
                "PASS: paired, re-handshook to the long-term PSK and activated" +
                    (expectMismatches?.let { " after $it pairing_code_mismatch" } ?: "")
            )
            expectPaired -> println("PASS: authenticated with the stored pairing record")
            expectRehandshake && rehandshakes == 0 -> exitFail("the server never re-handshook")
            expectRehandshake && activationsAfterRehandshake == 0 ->
                exitFail("no server/activate followed the re-handshake")
            expectRehandshake && serverHellos != 1 ->
                exitFail("server/hello was sent $serverHellos times; a re-handshake re-sends neither hello")
            seek && !seeksSent -> exitFail("the server never offered both seek and seek_relative")
            expectAudio || expectArtwork || expectRehandshake || seek -> println(
                "PASS: " + listOfNotNull(
                    "audio received and verified".takeIf { expectAudio },
                    "artwork reassembled and cleared".takeIf { expectArtwork },
                    "re-handshake completed and activated".takeIf { expectRehandshake },
                    "seek and seek_relative sent".takeIf { seek },
                ).joinToString("; ")
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

    /**
     * Checks the artwork the server transferred, as reassembled by
     * [ArtworkReceiver].
     *
     * Each image is reported with its SHA-256, to compare with the one
     * `dev_server.py --send-test-artwork` logs for the bytes it sent.
     */
    private class ArtworkCheck {
        private val receiver = ArtworkReceiver()
        private var streamActive = false
        private var streamStarts = 0
        private var partsInFlight = 0
        private var multiPartImages = 0

        /** "image" or "clear" for every completed transfer, in order. */
        private val completed = mutableListOf<String>()
        private val lines = mutableListOf<String>()

        @Synchronized
        fun onStreamStart() {
            streamActive = true
            streamStarts++
        }

        @Synchronized
        fun onStreamEnd() {
            streamActive = false
            receiver.reset()
        }

        /** @return why the connection must close, or null. */
        @Synchronized
        fun onMessage(type: Int, body: ByteArray): String? {
            // "Servers MUST NOT send artwork messages outside an active artwork stream."
            if (!streamActive) return "message outside an active artwork stream"
            val channel = type - SendSpinProtocol.BinaryType.ARTWORK_BASE
            if (body.isNotEmpty() && body[0].toInt() == 0) partsInFlight++
            when (val result = receiver.accept(type, body)) {
                is ArtworkReceiver.Result.ProtocolError -> return result.reason
                is ArtworkReceiver.Result.None -> {}
                is ArtworkReceiver.Result.Discard -> {
                    partsInFlight = 0
                    if (body[0].toInt() == ArtworkReceiver.FLAG_CANCEL) note("channel $channel cancel")
                }
                is ArtworkReceiver.Result.Image -> if (result.data.isEmpty()) {
                    completed += "clear"
                    note("channel $channel clear ts=${result.timestampMicros}")
                } else {
                    completed += "image"
                    if (partsInFlight > 1) multiPartImages++
                    val sha = MessageDigest.getInstance("SHA-256").digest(result.data)
                        .joinToString("") { "%02x".format(it) }
                    val jpeg = result.data.size > 2 &&
                        result.data[0] == 0xFF.toByte() && result.data[1] == 0xD8.toByte()
                    note("channel $channel image ${result.data.size} bytes in $partsInFlight part(s) " +
                        "ts=${result.timestampMicros} jpeg=$jpeg sha256=$sha")
                }
            }
            return null
        }

        private fun note(line: String) {
            lines += line
            println("<- enc   artwork $line")
        }

        @Synchronized
        fun report(): List<String> = buildList {
            add("artwork        : $streamStarts stream/start, " +
                "${completed.count { it == "image" }} images ($multiPartImages in several parts), " +
                "${completed.count { it == "clear" }} clears")
            lines.forEach { add("  $it") }
        }

        /** Why the artwork is not acceptable, or null if it is. */
        @Synchronized
        fun problem(): String? = when {
            streamStarts == 0 -> "no stream/start carried an artwork object"
            "image" !in completed -> "no image received"
            multiPartImages == 0 -> "no image arrived in more than one part"
            completed.last() != "clear" -> "the last image was not cleared"
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
