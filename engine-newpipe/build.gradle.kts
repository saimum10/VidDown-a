plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin/JVM on purpose (no Android classes): the adapter logic is tested on the JVM with offline fixtures.
// NewPipeExtractor is NOT a precompiled artifact: it is built from source as a Gradle composite build (see settings.gradle.kts and
// docs/newpipe.md). Only the package com.viddown.engine.newpipe.adapter may import org.schabi.newpipe.* classes.
dependencies {
    api(project(":engine-api"))

    // Reused HTTP primitives only (HttpTransport, OkHttpTransport, RetryPolicy, SpeedMeter, ResumeMeta). DirectHttpEngine itself is
    // never created by this module, so selecting NewPipe never loads or starts the Direct HTTP engine.
    implementation(project(":engine-direct"))
    implementation(libs.okhttp)

    // Resolved by the composite build (settings.gradle.kts), never from a binary repository.
    implementation(libs.newpipe.extractor)

    testImplementation(libs.junit)
}
