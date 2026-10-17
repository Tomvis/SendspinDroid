package com.sendspindroid.ui.theme

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.WeakHashMap

/**
 * Fork-only (HW-125): the person's home theme on the XML side too -- dialogs, snackbars, the stats sheet --
 * which otherwise keep the slate res/values{,-night}/colors.xml (or the wallpaper colors DynamicColors puts
 * on top). Each activity gets ThemeOverlay.Home.<id> (res/values{,-night}/home_theme_overlays.xml, vendored
 * from homelab-stacks theme/build.py) as it is created, after DynamicColors' overlay, and is recreated when
 * it comes back to the front under another theme, as a light/dark change does.
 */
object HomeXmlTheme : Application.ActivityLifecycleCallbacks {
    private val applied = WeakHashMap<Activity, String>()

    fun register(application: Application) = application.registerActivityLifecycleCallbacks(this)

    /** The overlay for [themeId], or 0 when none was vendored for it. */
    @SuppressLint("DiscouragedApi") // the ids come from the generated catalog, kept by res/raw/home_theme_keep.xml
    fun overlayFor(activity: Activity, themeId: String): Int =
        activity.resources.getIdentifier("ThemeOverlay.Home.$themeId", "style", activity.packageName)

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        val theme = HomeThemeController.effective.value.theme
        overlayFor(activity, theme).takeIf { it != 0 }?.let { activity.theme.applyStyle(it, true) }
        applied[activity] = theme
    }

    override fun onActivityResumed(activity: Activity) {
        val theme = applied[activity] ?: return
        if (theme != HomeThemeController.effective.value.theme) activity.recreate()
    }

    override fun onActivityDestroyed(activity: Activity) {
        applied.remove(activity)
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
