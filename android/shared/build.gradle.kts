plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

kotlin {
    androidLibrary {
        namespace = "com.sendspindroid.shared"
        compileSdk = 36
        minSdk = 26

        withHostTest {
            isIncludeAndroidResources = false
        }
    }

    // Second target so the conformance-client (JVM-only fat-jar that wraps the
    // Sendspin/conformance harness) can reuse the same protocol code without
    // pulling in Android dependencies.
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
            implementation("io.ktor:ktor-client-core:3.1.1")
            implementation("io.ktor:ktor-client-websockets:3.1.1")
            // Moshi: protocol JSON parsing/building. KSP generates per-class
            // adapters from @JsonClass annotations at compile time (no runtime
            // reflection on the hot path); moshi-kotlin's KotlinJsonAdapterFactory
            // provides the reflection-based fallback for anything not annotated.
            implementation("com.squareup.moshi:moshi:1.15.1")
            implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
        androidMain.dependencies {
            implementation("io.ktor:ktor-client-okhttp:3.1.1")
        }
        getByName("jvmMain").dependencies {
            implementation("io.ktor:ktor-client-okhttp:3.1.1")
            implementation("com.squareup.okhttp3:okhttp:4.12.0")
        }
        getByName("androidHostTest") {
            dependencies {
                implementation("junit:junit:4.13.2")
                implementation("io.mockk:mockk:1.13.16")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvmToolchain(21)
}

// KSP configuration for Moshi codegen.
// The androidLibrary target under com.android.kotlin.multiplatform.library
// exposes its KSP processor classpath as kspAndroidMainProcessorClasspath,
// but that name isn't usable until the Android KMP plugin finishes wiring
// targets. Defer adding the processor until afterEvaluate, when the
// configuration exists.
afterEvaluate {
    dependencies {
        add("kspAndroidMainProcessorClasspath", "com.squareup.moshi:moshi-kotlin-codegen:1.15.1")
        // The jvm() target (reused by :conformance-client) needs the same Moshi
        // codegen, otherwise the conformance harness validates reflective
        // (KotlinJsonAdapterFactory) adapters instead of the production
        // KSP-generated ones. The processor must be added to the RESOLVABLE
        // processor classpath kspKotlinJvmProcessorClasspath; the friendly
        // declarable bucket kspJvm does not feed it (no extendsFrom wiring).
        add("kspKotlinJvmProcessorClasspath", "com.squareup.moshi:moshi-kotlin-codegen:1.15.1")
    }
}
