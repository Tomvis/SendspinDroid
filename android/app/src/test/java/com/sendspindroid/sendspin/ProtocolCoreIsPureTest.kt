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

    private fun protocolSources(): List<File> {
        val dir = File("src/main/java/com/sendspindroid/sendspin")
        require(dir.isDirectory) { "protocol core not found at " + dir.absolutePath }
        return dir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
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
