package com.viddown.app.engines

import android.os.Build
import com.viddown.app.Abi
import com.viddown.app.BuildConfig
import com.viddown.engine.api.EngineAvailability

/**
 * Whether the yt-dlp engine can be offered in this build on this device. Reading it never starts Python or any process:
 * it only compares the ABIs packaged in this APK with the device's. Whether the runtime really works is found out the first
 * time the engine is used, and a failure there is reported as "could not start its native components", never hidden.
 */
object YtDlpSupport {
    /**
     * Set when the native runtime failed its SHA-256 check or could not be extracted or started (AndroidNativeTools.prepare).
     * While it is set the engine is "not available" and says why; it is a per-process fact (gone after a restart, which retries)
     * and is cleared by the next successful preparation.
     */
    @Volatile
    private var runtimeProblem: String? = null

    fun reportRuntimeProblem(reason: String) {
        runtimeProblem = reason.take(300)
    }

    fun reportRuntimeOk() {
        runtimeProblem = null
    }

    private fun list(csv: String): List<String> = csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    fun availability(): EngineAvailability {
        if (list(BuildConfig.NATIVE_ABIS).isEmpty()) return EngineAvailability.NotAvailable("Not available in this build")
        if (Abi.select() == null) {
            return EngineAvailability.NotAvailable(
                "This build has no yt-dlp runtime for this device's processor (${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}).",
            )
        }
        runtimeProblem?.let { return EngineAvailability.NotAvailable("Its native components failed their check on this device: $it") }
        return EngineAvailability.Available
    }

    /** True when the packaged runtime for this device's ABI has passed the on-device tests recorded in native/bundle.lock.json. */
    fun isVerifiedOnThisDevice(): Boolean {
        val abi = Abi.select() ?: return false
        return abi in list(BuildConfig.VERIFIED_NATIVE_ABIS)
    }

    /** The text under the engine's name in Settings; says plainly when the runtime has not been verified on this device's ABI. */
    fun description(): String {
        val base = "Reads a wide range of sites with yt-dlp, using its bundled Python runtime. Merges separate video and audio streams on this device."
        val abi = Abi.select()
        return if (abi != null && !isVerifiedOnThisDevice()) {
            "$base Experimental: the runtime for $abi has not passed on-device tests yet."
        } else {
            base
        }
    }
}
