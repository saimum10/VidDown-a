package com.viddown.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import com.viddown.core.model.AppSettings
import com.viddown.core.model.QualityPreference
import com.viddown.core.model.SearchEngine
import com.viddown.core.model.SettingsValidator
import com.viddown.core.model.ThemeMode
import com.viddown.data.settings.toSettings
import com.viddown.data.settings.write
import com.viddown.engine.api.EngineMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsPreferencesTest {
    @Test
    fun emptyPreferencesGiveTheDefaults() {
        assertEquals(AppSettings(), mutablePreferencesOf().toSettings())
    }

    @Test
    fun writeThenReadRoundTrips() {
        val s = AppSettings(
            themeMode = ThemeMode.DARK, engineMode = EngineMode.NEWPIPE, askBeforeDownloading = false, wifiOnly = true,
            maxSimultaneousDownloads = 3, defaultQuality = QualityPreference.P720, notificationsEnabled = false,
            downloadTreeUri = "content://tree/abc", homepage = "https://example.org", searchEngine = SearchEngine.BING,
            javascriptEnabled = false,
        )
        val prefs = mutablePreferencesOf()
        prefs.write(s)
        assertEquals(s, prefs.toSettings())
    }

    @Test
    fun theRemovedDirectHttpChoiceMovesToTheDefaultEngine() {
        val prefs = mutablePreferencesOf()
        prefs[stringPreferencesKey("selected_engine")] = "direct_http"
        val s = prefs.toSettings()
        assertEquals(EngineMode.AUTO, s.engineMode)
        assertNull("it is not reported as an unknown engine", s.unrecognizedEngineKey)
    }

    @Test
    fun unknownStoredValuesFallBackToDefaults() {
        val prefs = mutablePreferencesOf()
        prefs[stringPreferencesKey("theme_mode")] = "sepia"
        prefs[stringPreferencesKey("selected_engine")] = "automatic"
        prefs[stringPreferencesKey("default_quality")] = "8k"
        val s = prefs.toSettings()
        assertEquals(ThemeMode.SYSTEM, s.themeMode)
        assertEquals(EngineMode.AUTO, s.engineMode)
        assertEquals(QualityPreference.BEST, s.defaultQuality)
        assertEquals("an unknown engine is reported, not silently accepted", "automatic", s.unrecognizedEngineKey)
    }

    @Test
    fun aMissingEngineKeyIsJustTheDefaultAndAKnownOneIsNotFlagged() {
        assertNull(mutablePreferencesOf().toSettings().unrecognizedEngineKey)
        assertEquals("a missing key means Auto", EngineMode.AUTO, mutablePreferencesOf().toSettings().engineMode)
        val prefs = mutablePreferencesOf()
        prefs[stringPreferencesKey("selected_engine")] = "ytdlp"
        val s = prefs.toSettings()
        assertEquals(EngineMode.YTDLP, s.engineMode)
        assertNull(s.unrecognizedEngineKey)
    }

    @Test
    fun theThreeEngineChoicesAreSavedAndReadBack() {
        for (mode in EngineMode.entries) {
            val prefs = mutablePreferencesOf()
            prefs.write(AppSettings(engineMode = mode))
            assertEquals(mode.storageKey, prefs[stringPreferencesKey("selected_engine")])
            val s = prefs.toSettings()
            assertEquals(mode, s.engineMode)
            assertNull(s.unrecognizedEngineKey)
        }
        val prefs = mutablePreferencesOf()
        prefs[stringPreferencesKey("selected_engine")] = "auto"
        assertEquals(EngineMode.AUTO, prefs.toSettings().engineMode)
    }

    @Test
    fun anEngineChoiceSavedByAnOlderVersionIsKept() {
        for (key in listOf("newpipe", "ytdlp")) {
            val prefs = mutablePreferencesOf()
            prefs[stringPreferencesKey("selected_engine")] = key
            assertEquals(key, prefs.toSettings().engineMode.storageKey)
        }
    }

    @Test
    fun anUnrecognisedEngineKeyIsNotOverwrittenByUnrelatedSettingChanges() {
        val key = stringPreferencesKey("selected_engine")
        val prefs = mutablePreferencesOf()
        prefs[key] = "automatic"
        // what DataStoreSettingsRepository.update does for an unrelated change (the theme): read, transform, write
        prefs.write(prefs.toSettings().copy(themeMode = ThemeMode.DARK))
        assertEquals("automatic", prefs[key])
        assertEquals("automatic", prefs.toSettings().unrecognizedEngineKey)
    }

    @Test
    fun choosingAnEngineReplacesAnUnrecognisedKeyAndClearsTheReport() {
        val key = stringPreferencesKey("selected_engine")
        val prefs = mutablePreferencesOf()
        prefs[key] = "automatic"
        prefs.write(prefs.toSettings().copy(engineMode = EngineMode.NEWPIPE, unrecognizedEngineKey = null))
        assertEquals("newpipe", prefs[key])
        val s = prefs.toSettings()
        assertEquals(EngineMode.NEWPIPE, s.engineMode)
        assertNull(s.unrecognizedEngineKey)
    }

    @Test
    fun aVeryLongUnrecognisedKeyIsShortenedInTheReport() {
        val prefs = mutablePreferencesOf()
        prefs[stringPreferencesKey("selected_engine")] = "x".repeat(500)
        assertEquals(40, prefs.toSettings().unrecognizedEngineKey!!.length)
    }

    @Test
    fun invalidStoredValuesAreSanitised() {
        val prefs = mutablePreferencesOf()
        prefs[intPreferencesKey("max_simultaneous_downloads")] = 99
        prefs[stringPreferencesKey("homepage")] = "javascript:alert(1)"
        val s = prefs.toSettings()
        assertEquals(SettingsValidator.MAX_DOWNLOADS, s.maxSimultaneousDownloads)
        assertEquals(SettingsValidator.DEFAULT_HOMEPAGE, s.homepage)
    }

    @Test
    fun clearingTheDownloadFolderRemovesTheKey() {
        val prefs = mutablePreferencesOf()
        prefs.write(AppSettings(downloadTreeUri = "content://tree/abc"))
        prefs.write(AppSettings(downloadTreeUri = null))
        assertNull(prefs.toSettings().downloadTreeUri)
    }
}
