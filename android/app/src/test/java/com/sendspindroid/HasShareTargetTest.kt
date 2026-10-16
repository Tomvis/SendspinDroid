package com.sendspindroid

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Report a problem and Export Debug Logs need an app to share to. On the
 * SHIELD the only one that answers is a stub that does nothing with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HasShareTargetTest {

    private val packageManager =
        ApplicationProvider.getApplicationContext<Context>().packageManager

    private fun addShareTarget(packageName: String) {
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                name = "$packageName.Share"
            }
        }
        shadowOf(packageManager)
            .addResolveInfoForIntent(Intent(Intent.ACTION_SEND).setType("text/plain"), info)
    }

    @Test
    fun `with no app to share to there is no target`() {
        assertFalse(hasShareTarget(packageManager))
    }

    @Test
    fun `the TV stub alone is not a target`() {
        addShareTarget("com.google.android.tv.frameworkpackagestubs")

        assertFalse(hasShareTarget(packageManager))
    }

    @Test
    fun `a real app is a target, stub or not`() {
        addShareTarget("com.google.android.tv.frameworkpackagestubs")
        addShareTarget("com.example.mail")

        assertTrue(hasShareTarget(packageManager))
    }
}
