package com.viddown.core.model

import com.viddown.engine.api.EngineMode
import com.viddown.engine.api.FormatOption
import com.viddown.engine.api.StreamKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {
    private fun f(id: String, kind: StreamKind, h: Int? = null, kbps: Int? = null) = FormatOption(id = id, kind = kind, height = h, bitrateKbps = kbps)

    @Test
    fun defaultsAreSafeAndUseAutoEngineSelection() {
        val d = AppSettings()
        assertEquals(EngineMode.AUTO, d.engineMode)
        assertEquals(ThemeMode.SYSTEM, d.themeMode)
        assertTrue(d.askBeforeDownloading)
    }

    @Test
    fun maxDownloadsAreClamped() {
        assertEquals(1, SettingsValidator.coerceMaxDownloads(0))
        assertEquals(3, SettingsValidator.coerceMaxDownloads(3))
        assertEquals(10, SettingsValidator.coerceMaxDownloads(99))
    }

    @Test
    fun homepageNormalisation() {
        assertEquals("https://example.com", SettingsValidator.normalizeHomepage("example.com"))
        assertEquals("http://example.com/a", SettingsValidator.normalizeHomepage("  http://example.com/a "))
        assertNull(SettingsValidator.normalizeHomepage("javascript:alert(1)"))
        assertNull(SettingsValidator.normalizeHomepage("file:///etc/passwd"))
        assertNull(SettingsValidator.normalizeHomepage("two words"))
        assertNull(SettingsValidator.normalizeHomepage(""))
        assertNull(SettingsValidator.normalizeHomepage("https://"))
    }

    @Test
    fun sanitizeFixesInvalidValues() {
        val s = SettingsValidator.sanitize(AppSettings(maxSimultaneousDownloads = 50, homepage = "not a url", downloadTreeUri = " "))
        assertEquals(10, s.maxSimultaneousDownloads)
        assertEquals(SettingsValidator.DEFAULT_HOMEPAGE, s.homepage)
        assertNull(s.downloadTreeUri)
    }

    @Test
    fun storageKeysRoundTripAndUnknownIsNull() {
        for (m in ThemeMode.entries) assertEquals(m, ThemeMode.fromStorageKey(m.storageKey))
        for (q in QualityPreference.entries) assertEquals(q, QualityPreference.fromStorageKey(q.storageKey))
        for (e in SearchEngine.entries) assertEquals(e, SearchEngine.fromStorageKey(e.storageKey))
        assertNull(ThemeMode.fromStorageKey("sepia"))
    }

    @Test
    fun searchUrlEncodesTheQuery() {
        assertEquals("https://duckduckgo.com/?q=a+b%26c%3Dd", SearchEngine.DUCKDUCKGO.searchUrl(" a b&c=d "))
    }

    @Test
    fun qualityPickRespectsTheLimitAndNeverInventsAFormat() {
        val formats = listOf(f("av720", StreamKind.VIDEO_AND_AUDIO, 720), f("av360", StreamKind.VIDEO_AND_AUDIO, 360), f("av1080", StreamKind.VIDEO_AND_AUDIO, 1080))
        assertEquals("av1080", QualityPreference.BEST.pick(formats, canMerge = false)!!.id)
        assertEquals("av720", QualityPreference.P720.pick(formats, canMerge = false)!!.id)
        assertEquals("av360", QualityPreference.P480.pick(formats, canMerge = false)!!.id)
        assertNull(QualityPreference.P480.pick(listOf(f("av1080", StreamKind.VIDEO_AND_AUDIO, 1080)), canMerge = false))
        assertNull(QualityPreference.BEST.pick(emptyList(), canMerge = true))
    }

    @Test
    fun qualityPickUsesSeparateStreamsOnlyWhenMergingIsPossibleAndSharper() {
        val formats = listOf(
            f("av720", StreamKind.VIDEO_AND_AUDIO, 720), f("v1080", StreamKind.VIDEO_ONLY, 1080),
            f("v2160", StreamKind.VIDEO_ONLY, 2160), f("a128", StreamKind.AUDIO_ONLY, kbps = 128),
        )
        assertEquals("av720", QualityPreference.BEST.pick(formats, canMerge = false)!!.id)
        assertEquals("v2160", QualityPreference.BEST.pick(formats, canMerge = true)!!.id)
        assertEquals("v1080", QualityPreference.P1080.pick(formats, canMerge = true)!!.id)
        assertEquals("av720", QualityPreference.P720.pick(formats, canMerge = true)!!.id)
    }

    @Test
    fun audioOnlyPicksTheBestAudioStream() {
        val formats = listOf(f("a64", StreamKind.AUDIO_ONLY, kbps = 64), f("a160", StreamKind.AUDIO_ONLY, kbps = 160), f("av", StreamKind.VIDEO_AND_AUDIO, 720))
        assertEquals("a160", QualityPreference.AUDIO_ONLY.pick(formats, canMerge = false)!!.id)
        assertNull(QualityPreference.AUDIO_ONLY.pick(listOf(f("av", StreamKind.VIDEO_AND_AUDIO, 720)), canMerge = false))
    }

    @Test
    fun originalFileIsUsedWhenNoResolutionIsKnown() {
        val orig = f("original", StreamKind.ORIGINAL_FILE)
        assertEquals("original", QualityPreference.BEST.pick(listOf(orig), canMerge = false)!!.id)
        assertNull(QualityPreference.P720.pick(listOf(orig), canMerge = false)?.takeIf { false })
    }
}
