# Rules for apps that use :player, applied to their release build by R8.
# Moved from the app's proguard-rules.pro, so another app that hosts the
# player gets them too.

# ============================================================================
# OkHttp + OkIO (WebSocket connections)
# ============================================================================
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }

# OkHttp platform adapters
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ============================================================================
# Kotlin Coroutines
# ============================================================================
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ============================================================================
# Kotlin Serialization (if used in future)
# ============================================================================
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# ============================================================================
# Ktor server (the listener for connections a server opens to the app)
# ============================================================================
# kotlin-reflect is left out of the build (see shared/build.gradle.kts). Ktor
# reaches it only when it loads modules by name or auto-reloads, and
# embeddedServer with a lambda does neither.
-dontwarn kotlin.reflect.full.KCallables
-dontwarn kotlin.reflect.full.KClasses
-dontwarn kotlin.reflect.jvm.ReflectJvmMapping

# Ktor asks the JVM's management bean whether a debugger is attached, and
# takes the class being absent, as it is on Android, for "no".
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
