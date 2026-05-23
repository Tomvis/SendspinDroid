// Top-level build file where you can add configuration options common to all sub-projects/modules.
// This file uses Kotlin DSL (.kts) instead of Groovy - modern Android best practice since 2023

plugins {
    // Android Gradle Plugin (AGP) - manages Android build process
    id("com.android.application") version "9.0.1" apply false
    id("com.android.kotlin.multiplatform.library") version "9.0.1" apply false

    // Kotlin plugins
    id("org.jetbrains.kotlin.android") version "2.3.0" apply false
    id("org.jetbrains.kotlin.jvm") version "2.3.0" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.3.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.0" apply false
    // KSP: powers Moshi @JsonClass(generateAdapter = true) code generation.
    // KSP versioning post-2.3.0 dropped the `-X.Y.Z` API-suffix; the plugin
    // version simply tracks the Kotlin compiler series.
    id("com.google.devtools.ksp") version "2.3.8" apply false
}
