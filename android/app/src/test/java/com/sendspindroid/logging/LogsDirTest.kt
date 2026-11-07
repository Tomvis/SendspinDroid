package com.sendspindroid.logging

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogsDirTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `resolves to the external files dir`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals(ctx.getExternalFilesDir("logs"), LogsDir.resolve(ctx))
    }

    /**
     * FileProvider caches its path roots in a static map that outlives the
     * per-test Robolectric Application, so a root from an earlier test's temp
     * dir would reject this test's files and the writer falls back to file://.
     */
    @Before
    fun clearFileProviderCache() {
        val cache = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (cache.get(null) as MutableMap<*, *>).clear()
    }

    @Test
    fun `export from the external dir goes through the FileProvider`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val writer = LogFileWriter(LogsDir.resolve(ctx))
        writer.init()
        writer.appendLine("hello")

        @Suppress("DEPRECATION")
        val uri = writer.shareIntent(ctx)!!.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", uri.scheme)
    }

    @Test
    fun `moves old cache logs into the legacy subdir`() {
        val from = tmp.newFolder("cache-logs")
        val to = tmp.newFolder("ext-logs")
        File(from, "sendspin-log-0.txt").writeText("newest")
        File(from, "sendspin-log-1.txt").writeText("older")
        File(from, "sendspin-log-combined.txt").writeText("share copy")
        File(from, "other.txt").writeText("keep")

        LogsDir.migrate(from, to)

        val legacy = File(to, LogsDir.LEGACY_SUBDIR)
        assertEquals("newest", File(legacy, "sendspin-log-0.txt").readText())
        assertEquals("older", File(legacy, "sendspin-log-1.txt").readText())
        assertFalse(File(from, "sendspin-log-0.txt").exists())
        assertTrue(File(from, "other.txt").exists())
        // The active log in the new dir is untouched by the move.
        assertFalse(File(to, "sendspin-log-0.txt").exists())
    }

    @Test
    fun `a second migration never overwrites the first`() {
        val from = tmp.newFolder("cache-logs")
        val to = tmp.newFolder("ext-logs")
        File(from, "sendspin-log-0.txt").writeText("first")
        LogsDir.migrate(from, to)
        File(from, "sendspin-log-0.txt").writeText("second")
        LogsDir.migrate(from, to)

        assertEquals("first", File(to, "${LogsDir.LEGACY_SUBDIR}/sendspin-log-0.txt").readText())
        assertFalse(File(from, "sendspin-log-0.txt").exists())
    }

    @Test
    fun `no old dir is a no-op`() {
        val to = tmp.newFolder("ext-logs")
        LogsDir.migrate(File(tmp.root, "missing"), to)
        assertFalse(File(to, LogsDir.LEGACY_SUBDIR).exists())
    }
}
