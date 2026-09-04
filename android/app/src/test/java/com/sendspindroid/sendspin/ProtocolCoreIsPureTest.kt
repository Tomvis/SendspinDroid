package com.sendspindroid.sendspin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SendSpin protocol core must know nothing about Music Assistant. Its only
 * MA-aware surface was the ma-api DataChannel, which existed to tunnel MA's
 * control API over WebRTC remote access. With remote access removed there is
 * nothing left to tunnel.
 */
class ProtocolCoreIsPureTest {

    /**
     * Both source roots that make up the SendSpin protocol core: the Android-side
     * code in this module and the multiplatform code shared with other targets.
     * Each root is required to exist and be a directory -- if either is moved or
     * renamed, this fails loudly instead of silently scanning zero files from it
     * and reporting a vacuous "no MA references found".
     */
    private fun protocolSources(): List<File> {
        val roots = listOf(
            File("src/main/java/com/sendspindroid/sendspin"),
            File("../shared/src/commonMain/kotlin/com/sendspindroid/sendspin")
        )
        return roots.flatMap { dir ->
            require(dir.isDirectory) { "protocol core not found at " + dir.absolutePath }
            dir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
        }
    }

    @Test
    fun protocolCoreHasNoMusicAssistantReferences() {
        val offending = protocolSources().flatMap { file ->
            file.readLines()
                .map { it.trim() }
                .filter { it.contains("musicassistant") || it.contains("MaApi") }
                .map { file.name + ": " + it }
        }
        assertEquals("the protocol core must not reference Music Assistant", emptyList<String>(), offending)
    }

    @Test
    fun protocolCoreStillExists() {
        val names = protocolSources().map { it.name }
        assertTrue("SendSpin.kt must survive", names.contains("SendSpin.kt"))
        assertTrue("the protocol package must not be emptied", names.size > 5)
    }
}
