// Conformance-client: a JVM-only fat-jar CLI that drives the protocol layer
// against the Sendspin/conformance Python harness. Not shipped with the
// Android app — built on demand via `./gradlew :conformance-client:jar`.

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":shared"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("io.ktor:ktor-client-core:3.1.1")
    implementation("io.ktor:ktor-client-okhttp:3.1.1")
    implementation("io.ktor:ktor-client-websockets:3.1.1")
    implementation("com.squareup.moshi:moshi:1.15.1")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Quiet Ktor's SLF4J "no provider" warning; the harness captures stderr.
    implementation("org.slf4j:slf4j-simple:2.0.13")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

application {
    mainClass.set("com.sendspindroid.conformance.MainKt")
}

// Fat-jar packaging without an extra plugin. The harness invokes
// `java -jar conformance-client-all.jar ...`; everything must be on the
// classpath bundled inside.
tasks.jar {
    archiveBaseName.set("conformance-client")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Main-Class" to "com.sendspindroid.conformance.MainKt",
        )
    }
    from(
        configurations.runtimeClasspath.get().map { artifact ->
            if (artifact.isDirectory) artifact else zipTree(artifact)
        }
    )
    // Strip signature files from bundled jars so the JVM does not reject the
    // shaded archive ("Invalid signature file digest for Manifest main attributes").
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.kotlin_module")
}
