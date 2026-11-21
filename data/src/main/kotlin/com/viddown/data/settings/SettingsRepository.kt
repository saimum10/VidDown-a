package com.viddown.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.viddown.core.model.AppSettings
import com.viddown.core.model.QualityPreference
import com.viddown.core.model.SearchEngine
import com.viddown.core.model.SettingsValidator
import com.viddown.core.model.ThemeMode
import com.viddown.engine.api.EngineMode
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

interface SettingsRepository {
    val settings: Flow<AppSettings>

    /** Applies [transform] to the stored settings and persists the sanitised result. */
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

private object Keys {
    val theme = stringPreferencesKey("theme_mode")
    val engine = stringPreferencesKey("selected_engine")
    val askBefore = booleanPreferencesKey("ask_before_downloading")
    val wifiOnly = booleanPreferencesKey("wifi_only")
    val maxDownloads = intPreferencesKey("max_simultaneous_downloads")
    val quality = stringPreferencesKey("default_quality")
    val notifications = booleanPreferencesKey("notifications_enabled")
    val treeUri = stringPreferencesKey("download_tree_uri")
    val homepage = stringPreferencesKey("homepage")
    val searchEngine = stringPreferencesKey("search_engine")
    val javascript = booleanPreferencesKey("javascript_enabled")
    val lastQuality = stringPreferencesKey("last_quality")
    val preferredCodec = stringPreferencesKey("preferred_codec")
    val preferredContainer = stringPreferencesKey("preferred_container")
}

/**
 * Unknown or missing values fall back to the defaults: an old or damaged file never crashes the app. The one exception is the engine:
 * a present but unknown engine key is reported through [AppSettings.unrecognizedEngineKey] instead of being replaced by the default.
 */
internal fun Preferences.toSettings(): AppSettings {
    val d = AppSettings()
    // "direct_http" was removed from the app: an older install that had it selected moves to the default engine.
    val storedEngineKey = this[Keys.engine]?.takeIf { it != "direct_http" }
    val storedEngine = EngineMode.fromStorageKey(storedEngineKey)
    return SettingsValidator.sanitize(
        AppSettings(
            themeMode = ThemeMode.fromStorageKey(this[Keys.theme]) ?: d.themeMode,
            engineMode = storedEngine ?: d.engineMode,
            // A key that is present but unknown is NOT turned into the default silently: it is reported and no engine is active.
            unrecognizedEngineKey = if (storedEngineKey != null && storedEngine == null) storedEngineKey.take(40) else null,
            askBeforeDownloading = this[Keys.askBefore] ?: d.askBeforeDownloading,
            wifiOnly = this[Keys.wifiOnly] ?: d.wifiOnly,
            maxSimultaneousDownloads = this[Keys.maxDownloads] ?: d.maxSimultaneousDownloads,
            defaultQuality = QualityPreference.fromStorageKey(this[Keys.quality]) ?: d.defaultQuality,
            notificationsEnabled = this[Keys.notifications] ?: d.notificationsEnabled,
            downloadTreeUri = this[Keys.treeUri],
            homepage = this[Keys.homepage] ?: d.homepage,
            searchEngine = SearchEngine.fromStorageKey(this[Keys.searchEngine]) ?: d.searchEngine,
            javascriptEnabled = this[Keys.javascript] ?: d.javascriptEnabled,
            lastQuality = this[Keys.lastQuality],
            preferredCodec = this[Keys.preferredCodec],
            preferredContainer = this[Keys.preferredContainer],
        ),
    )
}

internal fun MutablePreferences.write(s: AppSettings) {
    this[Keys.theme] = s.themeMode.storageKey
    // While the saved value is unrecognised it is left untouched: changing an unrelated setting must not overwrite it with the default.
    if (s.unrecognizedEngineKey == null) this[Keys.engine] = s.engineMode.storageKey
    this[Keys.askBefore] = s.askBeforeDownloading
    this[Keys.wifiOnly] = s.wifiOnly
    this[Keys.maxDownloads] = s.maxSimultaneousDownloads
    this[Keys.quality] = s.defaultQuality.storageKey
    this[Keys.notifications] = s.notificationsEnabled
    val tree = s.downloadTreeUri
    if (tree == null) remove(Keys.treeUri) else this[Keys.treeUri] = tree
    this[Keys.homepage] = s.homepage
    this[Keys.searchEngine] = s.searchEngine.storageKey
    this[Keys.javascript] = s.javascriptEnabled
    writeOptional(Keys.lastQuality, s.lastQuality)
    writeOptional(Keys.preferredCodec, s.preferredCodec)
    writeOptional(Keys.preferredContainer, s.preferredContainer)
}

private fun MutablePreferences.writeOptional(key: Preferences.Key<String>, value: String?) {
    if (value == null) remove(key) else this[key] = value
}

class DataStoreSettingsRepository(private val store: DataStore<Preferences>) : SettingsRepository {
    override val settings: Flow<AppSettings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }
        .distinctUntilChanged()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            prefs.write(SettingsValidator.sanitize(transform(prefs.toSettings())))
        }
    }
}

// One DataStore per file name per process: this delegate must exist exactly once.
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

fun createSettingsRepository(context: Context): SettingsRepository =
    DataStoreSettingsRepository(context.applicationContext.settingsDataStore)
