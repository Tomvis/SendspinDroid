plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.serialization")
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

    // Plain JVM target so the protocol layer (MessageBuilder/MessageParser/
    // BinaryMessageParser/SendspinTimeFilter) can be driven by the Sendspin
    // conformance harness adapter (:conformance-client) on a desktop JVM.
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
            implementation("io.ktor:ktor-client-core:3.1.1")
            implementation("io.ktor:ktor-client-websockets:3.1.1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
        androidMain.dependencies {
            implementation("io.ktor:ktor-client-okhttp:3.1.1")
        }
        jvmMain.dependencies {
            implementation("io.ktor:ktor-client-okhttp:3.1.1")
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

// "test" lifecycle alias for the KMP host-test suite.
//
// The Android KMP target names its host (JVM) test task "testAndroidHostTest"
// and, unlike the java plugin, never creates a plain "test" task. Gradle's
// multi-project "./gradlew test" matches tasks by name across every project,
// so without this alias the documented "cd android && ./gradlew test" resolved
// to :app:test only and silently skipped this module's entire suite - a
// regression in, say, the transport error classifier or PlaybackState metadata
// merging would ship green.
//
// dependsOn takes the task *name* (not a TaskProvider) on purpose: the Android
// plugin registers testAndroidHostTest during afterEvaluate, so resolving it
// eagerly here would fail with UnknownTaskException.
tasks.register("test") {
    group = "verification"
    description = "Runs the host (JVM) unit tests, i.e. testAndroidHostTest."
    dependsOn("testAndroidHostTest")
}
