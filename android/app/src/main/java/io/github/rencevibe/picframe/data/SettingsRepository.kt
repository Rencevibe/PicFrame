package io.github.rencevibe.picframe.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.rencevibe.picframe.domain.Config
import io.github.rencevibe.picframe.domain.MiniJson
import io.github.rencevibe.picframe.domain.toRaw
import io.github.rencevibe.picframe.domain.validateConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/** Persisted folder grants in [held] that nothing in [inUse] points at; safe to release. */
fun unusedGrants(held: List<String>, inUse: Set<String>): List<String> = held.filterNot { it in inUse }

/** What [SettingsRepository.load] found. */
data class LoadedSettings(val config: Config, val warnings: List<String>, val banner: String?, val setupDone: Boolean)

/** Writes [text] to [file] atomically: temp file, fsync, rename. */
fun atomicWrite(file: File, text: String) {
    val tmp = File(file.parentFile, file.name + ".tmp")
    try {
        FileOutputStream(tmp).use {
            it.write(text.toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        if (!tmp.renameTo(file)) throw IOException("rename failed")
    } finally {
        tmp.delete()
    }
}

/**
 * Settings storage (replaces config.toml, plan §3). The config is one JSON string of the raw values
 * ([toRaw]); reading validates it field by field, as config.py did. A copy of the previous good
 * settings is kept as a backup file, used when the DataStore file is unreadable (the `.bak`
 * behavior of the Pi). DataStore itself writes atomically, so a failed save leaves the old file.
 */
class SettingsRepository internal constructor(
    private val store: DataStore<Preferences>,
    private val backup: File,
    private val recovered: AtomicReference<String?>,
    private val writeBackup: (File, String) -> Unit,
) {
    /** Loads and validates. Never throws; falls back to the backup, then to defaults. */
    suspend fun load(): LoadedSettings {
        var readFailed = false
        val prefs = try {
            store.data.first()
        } catch (e: IOException) { // exists but can't be read (permissions, I/O error): not "no settings"
            readFailed = true
            emptyPreferences()
        }
        val warnings = mutableListOf<String>()
        var source = recovered.getAndSet(null) // "last saved" | "defaults" when the file was corrupt
        var raw = parse(prefs[CONFIG])
        if (readFailed || (prefs[CONFIG] != null && raw == null)) { // same recovery as a corrupt file
            raw = parse(readBackup(backup))
            source = if (raw != null) "last saved" else "defaults"
        }
        when (source) {
            "last saved" -> warnings += "settings unreadable — using last saved settings"
            "defaults" -> warnings += "settings unreadable — using defaults"
        }
        val result = validateConfig(raw ?: emptyMap())
        warnings += result.warnings
        val banner = when {
            source == "last saved" -> "Settings were unreadable — using last saved settings"
            source == "defaults" -> "Settings were unreadable — using defaults"
            result.critical -> "Some settings were invalid — using defaults for them"
            else -> null
        }
        // A read failure says nothing about first run: don't send the user back to setup
        return LoadedSettings(result.config, warnings, banner, readFailed || prefs[SETUP_DONE] == true)
    }

    /** Saves [cfg]. Returns null on success, else a short reason; on failure the stored settings
     *  are unchanged. The previous good settings become the backup first. */
    suspend fun save(cfg: Config): String? = try {
        val previous = store.data.first()[CONFIG]
        if (previous != null && parse(previous) != null) writeBackup(backup, previous)
        val json = MiniJson.write(cfg.toRaw())
        store.edit { it[CONFIG] = json }
        val written = parse(store.data.first()[CONFIG])
        if (written == null || validateConfig(written).config != cfg) "written settings did not match" else null
    } catch (e: IOException) {
        e.message ?: e.toString()
    }

    /** Folder URIs that the stored settings or the backup name, i.e. every folder a later load can
     *  return to (an unreadable store falls back to the backup). Null if the store can't be read. */
    suspend fun foldersInUse(): Set<String>? = try {
        listOf(store.data.first()[CONFIG], readBackup(backup))
            .mapNotNull { parse(it)?.get("photo_dir") as? String }.toSet()
    } catch (e: IOException) {
        null
    }

    suspend fun setSetupDone() {
        try {
            store.edit { it[SETUP_DONE] = true }
        } catch (e: IOException) {
            // asked again next launch; harmless
        }
    }

    companion object {
        internal val CONFIG = stringPreferencesKey("config_json")
        internal val SETUP_DONE = booleanPreferencesKey("setup_done")

        @Suppress("UNCHECKED_CAST")
        internal fun parse(json: String?): Map<String, Any?>? = try {
            json?.let { MiniJson.parse(it) as? Map<String, Any?> }
        } catch (e: IllegalArgumentException) {
            null
        }

        internal fun readBackup(file: File): String? = try {
            file.takeIf { it.exists() }?.readText(Charsets.UTF_8)
        } catch (e: IOException) {
            null
        }

        fun create(
            file: File,
            backup: File,
            scope: CoroutineScope,
            writeBackup: (File, String) -> Unit = ::atomicWrite,
        ): SettingsRepository {
            val recovered = AtomicReference<String?>(null)
            val store = PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler {
                    val json = readBackup(backup)?.takeIf { parse(it) != null }
                    recovered.set(if (json != null) "last saved" else "defaults")
                    if (json != null) mutablePreferencesOf(CONFIG to json) else emptyPreferences()
                },
                scope = scope,
                produceFile = { file },
            )
            return SettingsRepository(store, backup, recovered, writeBackup)
        }
    }
}
