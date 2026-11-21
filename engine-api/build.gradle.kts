plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin on purpose: no Android classes here, so the engine contract can be tested on the JVM.
dependencies {
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
