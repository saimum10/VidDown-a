plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin/JVM on purpose (no Android classes): the yt-dlp adapter logic (argument building, JSON mapping, error mapping,
// progress parsing, merge planning, output validation and the download flow) is tested on the JVM with a scripted fake runtime.
// The real CPython + yt-dlp + FFmpeg executables are reached only through the NativeTools interface, which :app implements on top
// of the verified bundle (RuntimeInstaller, ProcessBuilder). Nothing here loads Python: creating the engine starts nothing.
// Only API-24-safe JDK classes are used (no java.nio.file, no java.time).
dependencies {
    api(project(":engine-api"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
