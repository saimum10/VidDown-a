package com.viddown.app

import android.content.Context
import android.os.Build
import java.io.File
import java.io.IOException

object Abi {
    /** The packaged ABI this device can run, or null when this build has no native runtime for it. */
    fun select(): String? {
        val packaged = BuildConfig.NATIVE_ABIS.split(",").filter { it.isNotBlank() }
        return Build.SUPPORTED_ABIS.firstOrNull { it in packaged }
    }
}

/**
 * Prepares the bundled CPython + yt-dlp + FFmpeg for use as child processes (the way the bundle's launcher was built):
 * executables stay in nativeLibraryDir, the Python stdlib and yt-dlp packages are extracted to filesDir.
 */
class RuntimeInstaller(private val context: Context) {
    val abi: String = Abi.select() ?: throw IllegalStateException(
        "This build has no native runtime for this device (packaged: '${BuildConfig.NATIVE_ABIS}', " +
            "device: ${Build.SUPPORTED_ABIS.joinToString()})"
    )

    val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
    val pythonHome = File(context.filesDir, "python")
    val sitePackages = File(context.filesDir, "site")
    val homeDir = File(context.filesDir, "home")
    val tmpDir = File(context.cacheDir, "runtime-tmp")
    val workDir = File(context.filesDir, "work")

    private val minor = BuildConfig.CPYTHON_VERSION.substringBeforeLast('.')
    private val marker = File(context.filesDir, "runtime-marker.txt")

    fun expectedHashes(): Map<String, String> = NativeManifest.load(context).hashesFor(abi)

    /** Compares every packaged native file with its pinned SHA-256. Returns human-readable problems (empty = all good). */
    fun verify(): List<String> {
        val problems = mutableListOf<String>()
        for ((rel, expected) in expectedHashes()) {
            val actual: String? = when {
                rel.startsWith("assets/") -> try {
                    context.assets.open(rel.removePrefix("assets/")).use { Sha256.hex(it) }
                } catch (e: IOException) {
                    null
                }
                rel.startsWith("jniLibs/") -> {
                    val file = File(nativeLibDir, File(rel).name)
                    if (file.isFile) Sha256.hex(file) else null
                }
                else -> expected
            }
            if (actual == null) {
                problems += "$rel: missing"
            } else if (actual != expected) {
                problems += "$rel: SHA-256 mismatch"
            }
        }
        return problems
    }

    /** Extracts the runtime once (idempotent). Throws if verification fails: never runs unverified files. */
    fun install() {
        val problems = verify()
        if (problems.isNotEmpty()) {
            throw IOException("Native runtime verification failed: " + problems.joinToString("; "))
        }
        val libZip = "python-lib-$minor-$abi.zip"
        // Every tool has its own release now: re-extract when the stdlib zip (cpython) OR the yt-dlp site-packages zip changes.
        val hashes = expectedHashes()
        val stamp = "$abi|${BuildConfig.NATIVE_BUNDLE_TAG}|${hashes["assets/$libZip"]}|${hashes["assets/ytdlp-site-packages.zip"]}"
        val stdlib = File(pythonHome, "lib/python$minor")
        if (marker.isFile && marker.readText() == stamp && stdlib.isDirectory && File(sitePackages, "yt_dlp").isDirectory) {
            ensureDirs()
            return
        }
        pythonHome.deleteRecursively()
        sitePackages.deleteRecursively()
        // The stdlib zip holds the CONTENTS of python3.14/, so it must be extracted into lib/python3.14/.
        context.assets.open(libZip).use { Unzip.extract(it, stdlib) }
        context.assets.open("ytdlp-site-packages.zip").use { Unzip.extract(it, sitePackages) }
        ensureDirs()
        marker.writeText(stamp)
    }

    private fun ensureDirs() {
        homeDir.mkdirs()
        tmpDir.mkdirs()
        workDir.mkdirs()
    }

    fun environment(): Map<String, String> = mapOf(
        "PYTHONHOME" to pythonHome.absolutePath,
        "PYTHONPATH" to sitePackages.absolutePath,
        "LD_LIBRARY_PATH" to nativeLibDir.absolutePath,
        "HOME" to homeDir.absolutePath,
        "TMPDIR" to tmpDir.absolutePath,
        "PYTHONDONTWRITEBYTECODE" to "1",
        "SSL_CERT_FILE" to File(sitePackages, "certifi/cacert.pem").absolutePath,
    )

    fun nativeFile(name: String): File = File(nativeLibDir, name)
}

/** Runs the bundled tools as child processes. Create it only when the yt-dlp engine is actually used. */
class NativeRuntime(context: Context) {
    val installer = RuntimeInstaller(context)

    init {
        installer.install()
    }

    private fun run(command: List<String>, timeoutMs: Long): ProcResult =
        ProcessRunner.run(command, installer.environment(), installer.workDir, timeoutMs)

    fun python(vararg args: String, timeoutMs: Long = 120_000): ProcResult =
        run(listOf(installer.nativeFile("libviddown_python.so").path) + args, timeoutMs)

    fun ytDlp(vararg args: String, timeoutMs: Long = 180_000): ProcResult =
        python("-m", "yt_dlp", "--ignore-config", "--no-cache-dir", "--no-warnings", *args, timeoutMs = timeoutMs)

    fun ffmpeg(vararg args: String, timeoutMs: Long = 120_000): ProcResult =
        run(listOf(installer.nativeFile("libffmpeg.so").path) + args, timeoutMs)

    fun ffprobe(vararg args: String, timeoutMs: Long = 60_000): ProcResult =
        run(listOf(installer.nativeFile("libffprobe.so").path) + args, timeoutMs)

    /** For cancellation tests and, later, real downloads: start a Python process and keep the handle. */
    fun startPython(vararg args: String): Process =
        ProcessRunner.start(listOf(installer.nativeFile("libviddown_python.so").path) + args, installer.environment(), installer.workDir)
}
