package com.sendspindroid.conformance

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/**
 * Sendspin conformance-client entry point.
 *
 * Invoked by the Python `Sendspin/conformance` harness via the conformance
 * adapter. Accepts harness coordination flags plus the scenario parameters
 * and writes a JSON summary file the harness validates against its expected
 * fixture.
 *
 * Argument contract (mirrors the official sendspin-jvm conformance client):
 *
 *   --initiator-role  server|client   "client" only on this fork (Phase 3 scope)
 *   --scenario-id     <id>            Identifier echoed back in the summary
 *   --preferred-codec flac|opus|pcm   Codec to advertise in client/hello
 *   --port            <port>          Server TCP port
 *   --host            <host>          Server host (default 127.0.0.1)
 *   --path            <path>          WebSocket path (default /sendspin)
 *   --summary         <path>          JSON summary output (required)
 *   --ready           <path>          File touched once the client is ready
 *   --registry        <path>          Harness registry config (currently ignored)
 *   --timeout-seconds <s>             Hard stop after N seconds (default 30)
 *
 * Exit codes:
 *   0   scenario completed; summary written
 *   1   bad arguments
 *   2   handshake never completed inside the timeout
 *   3   internal error (uncaught exception)
 */
fun main(rawArgs: Array<String>) {
    val args = try {
        parseArgs(rawArgs)
    } catch (e: IllegalArgumentException) {
        System.err.println("conformance-client: ${e.message}")
        printUsage()
        exitProcess(1)
    }

    if (args.initiatorRole != "client") {
        System.err.println("conformance-client: only --initiator-role client is implemented on this fork")
        exitProcess(1)
    }

    try {
        runBlocking {
            val summary = ConformanceClient(args).run()
            writeSummary(args.summaryPath, summary)
            exitProcess(if (summary.handshakeComplete) 0 else 2)
        }
    } catch (e: Throwable) {
        System.err.println("conformance-client: fatal: ${e.message}")
        e.printStackTrace(System.err)
        exitProcess(3)
    }
}

internal data class CliArgs(
    val initiatorRole: String,
    val scenarioId: String,
    val preferredCodec: String,
    val host: String,
    val port: Int,
    val path: String,
    val summaryPath: Path,
    val readyPath: Path?,
    val registryPath: Path?,
    val timeoutSeconds: Int,
)

private fun parseArgs(rawArgs: Array<String>): CliArgs {
    val map = mutableMapOf<String, String>()
    var i = 0
    while (i < rawArgs.size) {
        val flag = rawArgs[i]
        if (!flag.startsWith("--")) {
            throw IllegalArgumentException("expected flag, got '$flag'")
        }
        val value = rawArgs.getOrNull(i + 1)
            ?: throw IllegalArgumentException("missing value for $flag")
        map[flag.removePrefix("--")] = value
        i += 2
    }

    val initiatorRole = map["initiator-role"]
        ?: throw IllegalArgumentException("--initiator-role required")
    val scenarioId = map["scenario-id"]
        ?: throw IllegalArgumentException("--scenario-id required")
    val preferredCodec = map["preferred-codec"] ?: "pcm"
    if (preferredCodec !in setOf("pcm", "flac", "opus")) {
        throw IllegalArgumentException("--preferred-codec must be pcm|flac|opus")
    }
    val port = map["port"]?.toIntOrNull()
        ?: throw IllegalArgumentException("--port required and must be int")
    val summaryPath = map["summary"]?.let(::Path)
        ?: throw IllegalArgumentException("--summary required")
    val host = map["host"] ?: "127.0.0.1"
    val path = map["path"] ?: "/sendspin"
    val readyPath = map["ready"]?.let(::Path)
    val registryPath = map["registry"]?.let(::Path)
    val timeoutSeconds = map["timeout-seconds"]?.toIntOrNull() ?: 30

    return CliArgs(
        initiatorRole = initiatorRole,
        scenarioId = scenarioId,
        preferredCodec = preferredCodec,
        host = host,
        port = port,
        path = path,
        summaryPath = summaryPath,
        readyPath = readyPath,
        registryPath = registryPath,
        timeoutSeconds = timeoutSeconds,
    )
}

private fun printUsage() {
    System.err.println(
        """
        |usage: conformance-client \
        |    --initiator-role client \
        |    --scenario-id <id> \
        |    [--preferred-codec flac|opus|pcm] \
        |    [--host 127.0.0.1] --port <port> [--path /sendspin] \
        |    --summary <path> \
        |    [--ready <path>] [--registry <path>] \
        |    [--timeout-seconds 30]
        """.trimMargin()
    )
}

private fun writeSummary(path: Path, summary: Summary) {
    path.createParentDirectories()
    path.writeText(summary.toJson())
}
