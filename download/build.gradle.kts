plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin/JVM on purpose: the queue, state handling, recovery and cleanup rules are tested on the JVM with fake engines,
// a fake store and a fake output. Android pieces (MediaStore, service, notifications) live in :app behind the interfaces here.
// Only API-24-safe JDK classes are used (no java.nio.file, no java.time).
dependencies {
    api(project(":engine-api"))

    testImplementation(libs.junit)
}
