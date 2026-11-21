import groovy.json.JsonSlurper
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose)
}

// ---------------------------------------------------------------------------------------------
// Native bundle wiring. The bundle repository releases every tool on its own (yt-dlp, cpython, ffmpeg); native/bundle.lock.json pins one
// release per tool. `python3 scripts/fetch_native_bundle.py fetch` downloads them, verifies them (SHA-256) and merges the three into ONE
// tree per ABI (build/native/<abi>/jniLibs/<abi>, assets/ ...). The files are verified again here and again at runtime.
// The NewPipe helper that the bundle also releases is desktop-only and is not used here (NewPipe Extractor: scripts/fetch_newpipe.py).
//   -PnativeAbis=x86_64,arm64-v8a   ABIs whose native runtime is packaged. Empty/absent = none.
//   -PnativeDir=build/native        (script val: bundleDir) where the fetch script extracted the bundle
// Release builds must pass only ABIs that passed runtime tests (scripts/verified_abis.py).
// ---------------------------------------------------------------------------------------------
val supportedAbis = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
val nativeAbis: List<String> = (providers.gradleProperty("nativeAbis").orNull ?: "")
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }
val bundleDir: File = rootProject.file(providers.gradleProperty("nativeDir").orNull ?: "build/native")

@Suppress("UNCHECKED_CAST")
val lock = JsonSlurper().parse(rootProject.file("native/bundle.lock.json")) as Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun componentVersion(name: String): String =
    ((lock["components"] as List<Map<String, Any?>>).firstOrNull { it["name"] == name }
        ?: throw GradleException("native/bundle.lock.json has no component '$name'"))["version"] as String

/** Runtime verification status per ABI (the three tools together). */
@Suppress("UNCHECKED_CAST")
val lockAbis = lock["abis"] as Map<String, Map<String, Any?>>

/** The pinned release of every tool: yt-dlp (no ABI), cpython and ffmpeg (one zip per ABI). Empty while the manifest is UNPINNED. */
@Suppress("UNCHECKED_CAST")
val lockTools = (lock["tools"] as? Map<String, Map<String, Any?>>) ?: emptyMap()
val toolOrder = listOf("yt-dlp", "cpython", "ffmpeg")

/** Shown in About and used as the runtime-extraction stamp: the release tag of each tool, e.g. "yt-dlp-v2026.10.26, cpython-v3.14.10, ffmpeg-v8.1.3". */
val nativeBundleId: String =
    if (lockTools.isEmpty()) "unpinned" else toolOrder.joinToString(", ") { lockTools.getValue(it)["release_tag"] as String }

/** The files the app packages for [abi], merged over the three tools: "relative/path" -> metadata (sha256, size). */
@Suppress("UNCHECKED_CAST")
fun requiredFilesFor(abi: String): Map<String, Map<String, Any?>> {
    if (lockTools.isEmpty()) {
        throw GradleException(
            "native/bundle.lock.json is UNPINNED. Run: python3 scripts/update_native_bundle.py propose, review the diff, " +
                "then cp native/bundle.lock.proposed.json native/bundle.lock.json"
        )
    }
    val merged = linkedMapOf<String, Map<String, Any?>>()
    for (tool in toolOrder) {
        val entry = lockTools.getValue(tool)
        val artifact = (entry["abis"] as? Map<String, Map<String, Any?>>)?.get(abi)
            ?: (entry["artifact"] as? Map<String, Any?>)
            ?: throw GradleException("native/bundle.lock.json has no $tool artifact for ABI $abi")
        for ((rel, meta) in artifact["required_files"] as Map<String, Map<String, Any?>>) {
            val previous = merged.put(rel, meta)
            if (previous != null && previous["sha256"] != meta["sha256"]) {
                throw GradleException("$rel is provided with different content by two tools in native/bundle.lock.json")
            }
        }
    }
    return merged
}

// NewPipeExtractor is fetched at build time (scripts/fetch_newpipe.py, run by settings.gradle.kts), so the version is read back from
// the record it wrote and shown in About: the APK says exactly which release it contains.
@Suppress("UNCHECKED_CAST")
val newpipeExtractorRecord: Map<String, Any?>? = rootProject.file("third_party/newpipe.resolved.json").takeIf { it.isFile }
    ?.let { (JsonSlurper().parse(it) as Map<String, Any?>)["extractor"] as? Map<String, Any?> }
val newpipeTag: String = newpipeExtractorRecord?.get("tag") as? String ?: "unknown"
val newpipeCommit: String = (newpipeExtractorRecord?.get("commit") as? String)?.take(10) ?: "unknown"

val unknownAbis = nativeAbis.filter { it !in supportedAbis }
if (unknownAbis.isNotEmpty()) {
    throw GradleException("Unsupported ABI in -PnativeAbis: $unknownAbis (supported: $supportedAbis)")
}
for (abi in nativeAbis) {
    if (!File(bundleDir, "$abi/jniLibs/$abi").isDirectory) {
        throw GradleException(
            "Native bundle for $abi not found in $bundleDir. " +
                "Run: python3 scripts/fetch_native_bundle.py fetch --abi $abi --out ${bundleDir.path}"
        )
    }
    requiredFilesFor(abi) // fails with an explanation when the manifest is UNPINNED or lacks this ABI
    @Suppress("UNCHECKED_CAST")
    val status = ((lockAbis.getValue(abi)["verification"] as Map<String, Any?>)["runtime_on_device_or_emulator"] as String)
    if (!status.startsWith("PASS")) {
        logger.lifecycle("NOTE: ABI $abi has no PASS runtime verification in native/bundle.lock.json: test/PoC build only, do not release.")
    }
}

/** Packaged ABIs whose native runtime passed the on-device tests recorded in native/bundle.lock.json. */
val verifiedNativeAbis: List<String> = nativeAbis.filter { abi ->
    @Suppress("UNCHECKED_CAST")
    val status = ((lockAbis.getValue(abi)["verification"] as Map<String, Any?>)["runtime_on_device_or_emulator"] as String)
    status.startsWith("PASS")
}

/** Copies the ABI-independent assets and the per-ABI Python stdlib zip into the APK assets and writes native-manifest.json. */
abstract class PrepareNativeAssets : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val nativeDir: DirectoryProperty

    /** key "abi/relative/path" -> expected SHA-256, taken from the three tool pins in native/bundle.lock.json */
    @get:Input
    abstract val expected: MapProperty<String, String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @TaskAction
    fun prepare() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val manifest = linkedMapOf<String, MutableMap<String, String>>()
        val chosen = mutableMapOf<String, String>()
        for ((key, sha) in expected.get().toSortedMap()) {
            val abi = key.substringBefore('/')
            val rel = key.substringAfter('/')
            val src = File(nativeDir.get().asFile, "$abi/$rel")
            if (rel.startsWith("assets/") || rel.startsWith("jniLibs/")) {
                if (!src.isFile) throw GradleException("Missing native file: $src")
                val actual = sha256(src)
                if (actual != sha) throw GradleException("SHA-256 mismatch for $src (expected $sha, got $actual)")
            }
            manifest.getOrPut(abi) { linkedMapOf() }[rel] = sha
            if (rel.startsWith("assets/")) {
                val name = rel.removePrefix("assets/")
                val previous = chosen.put(name, sha)
                if (previous != null && previous != sha) {
                    throw GradleException("Asset $name differs between ABIs; it cannot be shared in one APK")
                }
                val dst = File(out, name)
                if (!dst.exists()) src.copyTo(dst)
            }
        }
        File(out, "native-manifest.json").writeText(groovy.json.JsonOutput.toJson(manifest))
    }
}

val prepareNativeAssets = if (nativeAbis.isEmpty()) null else tasks.register<PrepareNativeAssets>("prepareNativeAssets") {
    nativeDir.set(layout.dir(provider { bundleDir }))
    val map = linkedMapOf<String, String>()
    for (abi in nativeAbis) {
        @Suppress("UNCHECKED_CAST")
        for ((rel, meta) in requiredFilesFor(abi)) map["$abi/$rel"] = meta["sha256"] as String
    }
    expected.set(map)
    outputDir.set(layout.buildDirectory.dir("generated/native-assets"))
}

// ---------------------------------------------------------------------------------------------
// App version. The one place to change it is version.properties in the repository root
// (VERSION_NAME and VERSION_CODE). A build can override it with -PversionName=... and -PversionCode=...
// ---------------------------------------------------------------------------------------------
val versionFile: File = rootProject.file("version.properties")
if (!versionFile.isFile) {
    throw GradleException("version.properties is missing in ${rootProject.projectDir}. It must contain VERSION_NAME=... and VERSION_CODE=...")
}
val versionProps = Properties().apply { versionFile.inputStream().use { load(it) } }
val appVersionName: String = (providers.gradleProperty("versionName").orNull ?: versionProps.getProperty("VERSION_NAME"))?.trim().orEmpty()
val appVersionCodeText: String = (providers.gradleProperty("versionCode").orNull ?: versionProps.getProperty("VERSION_CODE"))?.trim().orEmpty()
val appVersionCode: Int = appVersionCodeText.toIntOrNull()?.takeIf { it > 0 }
    ?: throw GradleException("VERSION_CODE in version.properties must be a whole number above 0, but it is \"$appVersionCodeText\".")
if (appVersionName.isEmpty()) throw GradleException("VERSION_NAME in version.properties is empty.")

// Optional release signing: only when all four variables are present (set by the release workflow from secrets).
val keystorePath: String? = System.getenv("VIDDOWN_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }

android {
    namespace = "com.viddown.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.viddown.app"
        minSdk = 24 // the native bundle is built for API 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Link shown in About. CI passes -PsourceRepoUrl=<this repository>; without it About says that no link is set.
        buildConfigField("String", "SOURCE_REPO_URL", "\"${providers.gradleProperty("sourceRepoUrl").orNull ?: ""}\"")
        buildConfigField("String", "NATIVE_ABIS", "\"${nativeAbis.joinToString(",")}\"")
        buildConfigField("String", "VERIFIED_NATIVE_ABIS", "\"${verifiedNativeAbis.joinToString(",")}\"")
        buildConfigField("String", "NATIVE_BUNDLE_TAG", "\"$nativeBundleId\"")
        buildConfigField("String", "CPYTHON_VERSION", "\"${componentVersion("cpython")}\"")
        buildConfigField("String", "YTDLP_VERSION", "\"${componentVersion("yt-dlp")}\"")
        buildConfigField("String", "FFMPEG_VERSION", "\"${componentVersion("ffmpeg")}\"")
        buildConfigField("String", "OPENSSL_VERSION", "\"${componentVersion("openssl")}\"")
        buildConfigField("String", "NEWPIPE_EXTRACTOR_TAG", "\"$newpipeTag\"")
        buildConfigField("String", "NEWPIPE_EXTRACTOR_COMMIT", "\"$newpipeCommit\"")

        if (nativeAbis.isNotEmpty()) {
            ndk {
                abiFilters.addAll(nativeAbis)
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor needs core library desugaring below API 33 and names the desugar_jdk_libs_nio artifact in its README (minSdk is 24).
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        jniLibs {
            // libviddown_python.so, libffmpeg.so and libffprobe.so are EXECUTABLES that must exist as real files in
            // nativeLibraryDir (Android 10+ forbids executing files from writable app storage).
            useLegacyPackaging = true
            // Keep the files byte-identical to the verified bundle (no stripping), so runtime SHA-256 checks hold.
            keepDebugSymbols.add("**/*.so")
        }
    }

    if (keystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("VIDDOWN_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("VIDDOWN_KEY_ALIAS")
                keyPassword = System.getenv("VIDDOWN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

androidComponents {
    onVariants { variant ->
        if (prepareNativeAssets != null) {
            variant.sources.assets?.addGeneratedSourceDirectory(prepareNativeAssets, PrepareNativeAssets::outputDir)
        }
        for (abi in nativeAbis) {
            variant.sources.jniLibs?.addStaticSourceDirectory(File(bundleDir, "$abi/jniLibs").absolutePath)
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(project(":browser"))
    implementation(project(":download"))
    implementation(project(":engine-api"))
    implementation(project(":engine-newpipe"))
    implementation(project(":engine-ytdlp"))
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
}
