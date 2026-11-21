package com.viddown.app

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Phase 1 proof-of-concept tests. They run the REAL packaged CPython, yt-dlp and FFmpeg on the device/emulator.
 * Build with -PnativeAbis=<abi> so the native runtime is packaged; without it these tests fail on purpose
 * (a skipped test would look like a pass).
 */
class RuntimeInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var runtime: NativeRuntime
    private lateinit var scratch: File

    @Before
    fun setUp() {
        assertTrue(
            "No native runtime packaged. Build with -PnativeAbis=<abi> after fetching the bundle.",
            BuildConfig.NATIVE_ABIS.isNotBlank(),
        )
        assertNotNull("None of the packaged ABIs (${BuildConfig.NATIVE_ABIS}) matches this device ${Build.SUPPORTED_ABIS.toList()}", Abi.select())
        runtime = NativeRuntime(context)
        scratch = File(context.cacheDir, "poc-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        if (::scratch.isInitialized) scratch.deleteQuietly()
    }

    private fun describe(r: ProcResult) = "exit=${r.exitCode} timedOut=${r.timedOut}\nSTDOUT:\n${r.stdout.take(2000)}\nSTDERR:\n${r.stderr.take(2000)}"

    // ---- ABI and integrity -------------------------------------------------------------------------------------

    @Test
    fun abiIsPackagedAndElfMatches() {
        val abi = Abi.select()!!
        val want = Elf.machineFor(abi)
        for (name in listOf("libviddown_python.so", "libpython3.14.so", "libffmpeg.so", "libffprobe.so", "libssl_python.so", "libcrypto_python.so")) {
            assertEquals("$name ELF machine", want, Elf.machine(runtime.installer.nativeFile(name)))
        }
    }

    @Test
    fun appRunsAsAProcessOfTheExpectedBitnessForThePackagedAbi() {
        // x86 and armeabi-v7a libraries can only run in a 32-bit process, x86_64 and arm64-v8a only in a 64-bit one.
        // This proves the x86 matrix job really exercised the 32-bit x86 build and not the x86_64 one.
        val abi = Abi.select()!!
        val expect64 = abi == "x86_64" || abi == "arm64-v8a"
        assertEquals("abi=$abi, device ABIs=${Build.SUPPORTED_ABIS.toList()}", expect64, android.os.Process.is64Bit())
    }

    @Test
    fun packagedFilesMatchPinnedSha256() {
        assertEquals(emptyList<String>(), runtime.installer.verify())
    }

    @Test
    fun corruptedBytesAreRejected() {
        val expected = runtime.installer.expectedHashes().getValue("assets/python-version.txt")
        val original = context.assets.open("python-version.txt").use { it.readBytes() }
        assertEquals(expected, Sha256.hex(original))
        val corrupted = original.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertFalse("corrupted data must not match the pinned hash", Sha256.hex(corrupted) == expected)
    }

    @Test
    fun sharedLibrariesLoad() {
        // Dependencies first (libssl needs libcrypto). These are real dlopen calls in this process.
        for (name in listOf("libcrypto_python.so", "libssl_python.so", "libpython3.14.so")) {
            System.load(runtime.installer.nativeFile(name).absolutePath)
        }
    }

    // ---- CPython and yt-dlp --------------------------------------------------------------------------------------

    @Test
    fun cpythonStartsWithPinnedOpenSsl() {
        val r = runtime.python("-c", "import sys, ssl, zlib, hashlib, json; print(sys.version.split()[0]); print(ssl.OPENSSL_VERSION)")
        assertTrue(describe(r), r.ok)
        val lines = r.stdout.trim().lines()
        assertEquals(describe(r), BuildConfig.CPYTHON_VERSION, lines[0])
        assertTrue(describe(r), lines[1].contains(BuildConfig.OPENSSL_VERSION))
    }

    @Test
    fun ytDlpReportsPinnedVersion() {
        val r = runtime.ytDlp("--version")
        assertTrue(describe(r), r.ok)
        assertEquals(describe(r), BuildConfig.YTDLP_VERSION, r.stdout.trim())
    }

    @Test
    fun ytDlpInspectsLocalDirectFileAndHls() {
        // Owned test media: a WAV for the direct case, two AAC (ADTS) segments made by the bundled FFmpeg for HLS.
        val wav = TestMedia.wav(2.0)
        val segments = (0..1).map { i ->
            val inFile = File(scratch, "seg$i.wav").apply { writeBytes(TestMedia.wav(1.0, hz = 440.0 + 110 * i)) }
            val outFile = File(scratch, "seg$i.aac")
            val enc = runtime.ffmpeg("-hide_banner", "-loglevel", "error", "-y", "-i", inFile.path, "-c:a", "aac", "-f", "adts", outFile.path)
            assertTrue(describe(enc), enc.ok && outFile.length() > 0)
            outFile.readBytes()
        }
        val playlist = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n" +
            "#EXTINF:1.000,\nseg0.aac\n#EXTINF:1.000,\nseg1.aac\n#EXT-X-ENDLIST\n"
        TestServer(
            mapOf(
                "/test.wav" to ("audio/wav" to wav),
                "/index.m3u8" to ("application/vnd.apple.mpegurl" to playlist.toByteArray()),
                "/seg0.aac" to ("audio/aac" to segments[0]),
                "/seg1.aac" to ("audio/aac" to segments[1]),
            ),
        ).use { server ->
            for (path in listOf("/test.wav", "/index.m3u8")) {
                val r = runtime.ytDlp("-J", "--no-playlist", "http://127.0.0.1:${server.port}$path")
                assertTrue("$path\n${describe(r)}", r.ok)
                val json = JSONObject(r.stdout)
                val formats = json.getJSONArray("formats")
                assertTrue("$path returned no formats\n${r.stdout.take(1500)}", formats.length() > 0)
                assertTrue("$path format has no url", formats.getJSONObject(0).optString("url").isNotEmpty())
            }
        }
    }

    @Test
    fun ytDlpDownloadsLocalFile() {
        val wav = TestMedia.wav(2.0)
        TestServer(mapOf("/test.wav" to ("audio/wav" to wav))).use { server ->
            val target = File(scratch, "downloaded.wav")
            val r = runtime.ytDlp("--no-playlist", "-o", target.path, "http://127.0.0.1:${server.port}/test.wav")
            assertTrue(describe(r), r.ok)
            assertTrue("output file missing: ${scratch.list()?.toList()}\n${describe(r)}", target.isFile)
            assertEquals(wav.size.toLong(), target.length())
            assertEquals(Sha256.hex(wav), Sha256.hex(target))
        }
    }

    // ---- FFmpeg --------------------------------------------------------------------------------------------------

    @Test
    fun ffmpegReportsPinnedVersionAndIsNotGpl() {
        val r = runtime.ffmpeg("-version")
        assertTrue(describe(r), r.ok)
        assertTrue(describe(r), r.stdout.lines().first().contains(BuildConfig.FFMPEG_VERSION))
        assertFalse("FFmpeg must not be configured with --enable-gpl", r.stdout.contains("--enable-gpl"))
        val p = runtime.ffprobe("-version")
        assertTrue(describe(p), p.ok && p.stdout.lines().first().contains(BuildConfig.FFMPEG_VERSION))
    }

    @Test
    fun ffmpegProcessesOwnedFileAndOutputIsValidated() {
        val input = File(scratch, "in.wav").apply { writeBytes(TestMedia.wav(2.0)) }
        val output = File(scratch, "out.mp4")
        val enc = runtime.ffmpeg("-hide_banner", "-loglevel", "error", "-y", "-i", input.path, "-c:a", "aac", "-f", "mp4", output.path)
        assertTrue(describe(enc), enc.ok && output.length() > 0)
        val codec = runtime.ffprobe("-v", "error", "-select_streams", "a:0", "-show_entries", "stream=codec_name", "-of", "csv=p=0", output.path)
        assertTrue(describe(codec), codec.ok)
        assertEquals("aac", codec.stdout.trim())
        val duration = runtime.ffprobe("-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", output.path)
        assertTrue(describe(duration), duration.ok)
        val seconds = duration.stdout.trim().toDouble()
        assertTrue("duration $seconds not close to 2.0", seconds in 1.8..2.4)
    }

    // ---- cancellation --------------------------------------------------------------------------------------------

    @Test
    fun cancellationKillsTheChildProcess() {
        val process = runtime.startPython("-c", "import time; time.sleep(300)")
        Thread.sleep(1500)
        assertTrue("process should still be running before cancel", ProcessRunner.isAlive(process))
        assertTrue("process did not exit after kill", ProcessRunner.kill(process))
        assertFalse(ProcessRunner.isAlive(process))
    }

    @Test
    fun timeoutKillsAHungProcess() {
        val r = ProcessRunner.run(
            listOf(runtime.installer.nativeFile("libviddown_python.so").path, "-c", "import time; time.sleep(300)"),
            runtime.installer.environment(),
            runtime.installer.workDir,
            timeoutMs = 3_000,
        )
        assertTrue(describe(r), r.timedOut)
    }
}
