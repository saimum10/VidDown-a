package com.viddown.app

import android.content.Context
import android.os.Build

data class Check(val name: String, val ok: Boolean, val detail: String)

/** The runtime self-test shown on screen. Every line is a real command result; nothing is simulated. */
object SelfTest {
    fun run(context: Context): List<Check> {
        val checks = mutableListOf<Check>()
        val device = "device ABIs: ${Build.SUPPORTED_ABIS.joinToString()}; packaged: '${BuildConfig.NATIVE_ABIS}'"
        val abi = Abi.select()
        if (abi == null) {
            checks += Check(
                "Native runtime",
                false,
                if (BuildConfig.NATIVE_ABIS.isBlank()) {
                    "This build contains no native runtime (no ABI has passed runtime verification yet). $device"
                } else {
                    "No packaged ABI matches this device. $device"
                },
            )
            return checks
        }
        checks += Check("ABI selected", true, "$abi ($device)")

        val runtime = try {
            val started = System.currentTimeMillis()
            NativeRuntime(context).also {
                checks += Check("SHA-256 of packaged files and extraction", true, "verified and extracted in ${System.currentTimeMillis() - started} ms")
            }
        } catch (e: Exception) {
            checks += Check("SHA-256 of packaged files and extraction", false, e.message ?: e.toString())
            return checks
        }

        val installer = runtime.installer
        val wantMachine = Elf.machineFor(abi)
        val badElf = listOf("libviddown_python.so", "libpython3.14.so", "libffmpeg.so", "libffprobe.so")
            .filter { runCatching { Elf.machine(installer.nativeFile(it)) }.getOrDefault(-1) != wantMachine }
        checks += Check("ELF architecture matches ABI", badElf.isEmpty(), if (badElf.isEmpty()) "e_machine=$wantMachine" else "wrong or unreadable: $badElf")

        val py = runtime.python("-c", "import sys, ssl, zlib; print(sys.version.split()[0]); print(ssl.OPENSSL_VERSION)")
        val pyLines = py.stdout.trim().lines()
        checks += Check(
            "CPython ${BuildConfig.CPYTHON_VERSION} + OpenSSL ${BuildConfig.OPENSSL_VERSION}",
            py.ok && pyLines.getOrNull(0) == BuildConfig.CPYTHON_VERSION && (pyLines.getOrNull(1) ?: "").contains(BuildConfig.OPENSSL_VERSION),
            if (py.ok) pyLines.joinToString(" | ") else "exit ${py.exitCode}: ${py.stderr.trim().take(400)}",
        )

        val yt = runtime.ytDlp("--version")
        checks += Check(
            "yt-dlp ${BuildConfig.YTDLP_VERSION}",
            yt.ok && yt.stdout.trim() == BuildConfig.YTDLP_VERSION,
            if (yt.ok) yt.stdout.trim() else "exit ${yt.exitCode}: ${yt.stderr.trim().take(400)}",
        )

        val ff = runtime.ffmpeg("-version")
        val ffFirst = ff.stdout.lines().firstOrNull().orEmpty()
        checks += Check(
            "FFmpeg ${BuildConfig.FFMPEG_VERSION}",
            ff.ok && ffFirst.contains(BuildConfig.FFMPEG_VERSION) && !ff.stdout.contains("--enable-gpl"),
            if (ff.ok) ffFirst else "exit ${ff.exitCode}: ${ff.stderr.trim().take(400)}",
        )

        val fp = runtime.ffprobe("-version")
        checks += Check("FFprobe", fp.ok && fp.stdout.contains(BuildConfig.FFMPEG_VERSION), fp.stdout.lines().firstOrNull().orEmpty())
        return checks
    }

    fun format(checks: List<Check>): String = buildString {
        appendLine("Native tools: ${BuildConfig.NATIVE_BUNDLE_TAG}")
        appendLine()
        for (c in checks) {
            appendLine("[${if (c.ok) "PASS" else "FAIL"}] ${c.name}")
            appendLine("       ${c.detail}")
        }
        appendLine()
        appendLine(if (checks.all { it.ok }) "All checks passed." else "Some checks failed.")
    }
}
