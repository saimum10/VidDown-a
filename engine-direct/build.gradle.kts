plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin/JVM on purpose (no Android classes): the download logic is tested against a real local HTTP server on the JVM.
// Only API-24-safe JDK classes are used (no java.nio.file, no java.time), because this code runs on Android.
dependencies {
    api(project(":engine-api"))
    implementation(libs.okhttp)

    testImplementation(libs.junit)
}
