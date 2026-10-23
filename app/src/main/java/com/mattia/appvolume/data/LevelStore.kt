package com.mattia.appvolume.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Impostazione salvata per un package. Il mute non cancella il livello. */
data class AppSetting(val level: Int = 100, val muted: Boolean = false)

private val Context.levelsDataStore by preferencesDataStore(name = "livelli_app")

/** Persistenza dei livelli per package (non per UID: l'UID può cambiare reinstallando). */
class LevelStore(private val context: Context) {

    val settings: Flow<Map<String, AppSetting>> = context.levelsDataStore.data.map { prefs ->
        val result = HashMap<String, AppSetting>()
        for ((key, value) in prefs.asMap()) {
            val name = key.name
            when {
                name.startsWith(LEVEL_PREFIX) -> {
                    val pkg = name.removePrefix(LEVEL_PREFIX)
                    result[pkg] = (result[pkg] ?: AppSetting()).copy(level = value as Int)
                }
                name.startsWith(MUTED_PREFIX) -> {
                    val pkg = name.removePrefix(MUTED_PREFIX)
                    result[pkg] = (result[pkg] ?: AppSetting()).copy(muted = value as Boolean)
                }
            }
        }
        result
    }

    suspend fun save(packageName: String, setting: AppSetting) {
        context.levelsDataStore.edit { prefs ->
            prefs[intPreferencesKey(LEVEL_PREFIX + packageName)] = setting.level
            prefs[booleanPreferencesKey(MUTED_PREFIX + packageName)] = setting.muted
        }
    }

    private companion object {
        const val LEVEL_PREFIX = "level:"
        const val MUTED_PREFIX = "muted:"
    }
}
