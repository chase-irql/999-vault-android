package com.vault999.android.preferences

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.vaultDataStore by preferencesDataStore("vault_settings")

enum class NetworkPolicy { ANY, WIFI_ONLY, ASK_ON_METERED }
enum class Appearance { SYSTEM, DARK, HIGH_CONTRAST }

data class VaultSettings(
    val schemaVersion: Int = 1,
    val safTreeUri: String? = null,
    val networkPolicy: NetworkPolicy = NetworkPolicy.ASK_ON_METERED,
    val downloadConcurrency: Int = 3,
    val appearance: Appearance = Appearance.SYSTEM,
    val reducedMotion: Boolean = false,
    val equalizerEnabled: Boolean = false,
)

class VaultPreferences(private val context: Context) {
    private object Keys {
        val schema = intPreferencesKey("schema_version")
        val tree = stringPreferencesKey("saf_tree_uri")
        val network = stringPreferencesKey("network_policy")
        val concurrency = intPreferencesKey("download_concurrency")
        val appearance = stringPreferencesKey("appearance")
        val reducedMotion = booleanPreferencesKey("reduced_motion")
        val equalizer = booleanPreferencesKey("equalizer_enabled")
    }

    val settings: Flow<VaultSettings> = context.vaultDataStore.data.map(::decode)

    suspend fun update(transform: (VaultSettings) -> VaultSettings) {
        context.vaultDataStore.edit { prefs ->
            val transformed = transform(decode(prefs))
            val next = transformed.copy(schemaVersion = 1, downloadConcurrency = transformed.downloadConcurrency.coerceIn(1, 4))
            prefs[Keys.schema] = next.schemaVersion
            next.safTreeUri?.let { prefs[Keys.tree] = it } ?: prefs.remove(Keys.tree)
            prefs[Keys.network] = next.networkPolicy.name
            prefs[Keys.concurrency] = next.downloadConcurrency
            prefs[Keys.appearance] = next.appearance.name
            prefs[Keys.reducedMotion] = next.reducedMotion
            prefs[Keys.equalizer] = next.equalizerEnabled
        }
    }

    private fun decode(prefs: Preferences): VaultSettings = VaultSettings(
        schemaVersion = prefs[Keys.schema] ?: 1,
        safTreeUri = prefs[Keys.tree],
        networkPolicy = prefs[Keys.network]?.let { runCatching { NetworkPolicy.valueOf(it) }.getOrNull() } ?: NetworkPolicy.ASK_ON_METERED,
        downloadConcurrency = (prefs[Keys.concurrency] ?: 3).coerceIn(1, 4),
        appearance = prefs[Keys.appearance]?.let { runCatching { Appearance.valueOf(it) }.getOrNull() } ?: Appearance.SYSTEM,
        reducedMotion = prefs[Keys.reducedMotion] ?: false,
        equalizerEnabled = prefs[Keys.equalizer] ?: false,
    )
}
