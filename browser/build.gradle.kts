plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin/JVM on purpose (no Android classes): URL rules, media classification, manifest inspection, de-duplication,
// tab bookkeeping and the probe are all tested on the JVM, the probe against a real local HTTP server. The WebView itself and
// everything that touches it live in :app. Only API-24-safe JDK classes are used (no java.nio.file, no java.time).
dependencies {
    api(project(":engine-api"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
