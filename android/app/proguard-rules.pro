# ProGuard/R8 rules for SendSpinDroid
# These rules prevent R8 from removing classes accessed via reflection

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
# Moshi (SendSpin protocol JSON parsing / building)
#
# Moshi resolves codegen-generated adapters reflectively as
#   <ClassName>JsonAdapter via Class.forName, so R8 can't see them as
# referenced and strips them. Without the adapter, Moshi falls back to
# KotlinJsonAdapterFactory and fails on Kotlin data classes whose
# constructor-parameter metadata has been minified out, throwing
#   "No property for required constructor parameter #N <name>" on (de)serialize.
# This first manifested as a hello-message send failure against the MA-backed
# SendSpin server after the Moshi+KSP migration.
# ============================================================================
-keep,allowobfuscation,allowshrinking @com.squareup.moshi.JsonClass class *
-if @com.squareup.moshi.JsonClass class *
-keep class <1>JsonAdapter {
    <init>(...);
    <fields>;
}
# Keep every constructor on @JsonClass-annotated data classes. Codegen-generated
# adapters look up the synthetic default constructor via
#   getDeclaredConstructor(<field-types>..., Int, DefaultConstructorMarker)
# for data classes with default-valued params. R8 doesn't trace those
# reflection lookups, so without this rule it strips the synthetic ctor and
# parsing fails with a NoSuchMethodException-style error mentioning the
# obfuscated parameter types (e.g. `WireMetadata.<init> [class ka.f, ...]`).
-if @com.squareup.moshi.JsonClass class *
-keepclassmembers class <1> {
    <init>(...);
}
# Reflection fallback (KotlinJsonAdapterFactory) reads kotlin.Metadata; without
# it, constructor-parameter names are unrecoverable and adapter creation throws.
-keep class kotlin.Metadata { *; }
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault

# ============================================================================
# AndroidX Media3 (MediaSession, MediaController)
# ============================================================================
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Media3 session callbacks must be preserved
-keepclassmembers class * extends androidx.media3.session.MediaLibraryService {
    <methods>;
}
-keepclassmembers class * extends androidx.media3.session.MediaSession$Callback {
    <methods>;
}

# ============================================================================
# Coil (Image loading with reflection-based decoders)
# ============================================================================
-keep class coil.** { *; }
-keep interface coil.** { *; }
-dontwarn coil.**

# ============================================================================
# AndroidX Lifecycle (ViewModel, LiveData callbacks)
# ============================================================================
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keep class * extends androidx.lifecycle.AndroidViewModel { <init>(...); }
-keepclassmembers class * implements androidx.lifecycle.LifecycleObserver {
    <methods>;
}

# ============================================================================
# AndroidX Preference (settings reflection)
# ============================================================================
-keep class * extends androidx.preference.Preference { *; }
-keep class * extends androidx.preference.PreferenceFragmentCompat { *; }

# ============================================================================
# Keep app's model/data classes (if JSON parsing is added)
# ============================================================================
# -keep class com.sendspindroid.model.** { *; }

# ============================================================================
# Android components (Activities, Services, Receivers)
# ============================================================================
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver

# ============================================================================
# Keep native method names (for JNI)
# ============================================================================
-keepclasseswithmembernames class * {
    native <methods>;
}

# ============================================================================
# Debugging: Keep source file names and line numbers for crash reports
# ============================================================================
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

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
