package net.ts.taild80ca0.atlas.twa.preferences

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaColor

private val Context.deviceDataStore by preferencesDataStore("device_preferences",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() })

data class DevicePreferences(val dark: Boolean = true, val color: AlvaColor = AlvaColor.Teal, val screen: String = "add")

/** One DataStore per application, containing only device personalization, never credentials or list data. */
class DevicePreferencesStore(context: Context) {
    private val store = context.applicationContext.deviceDataStore
    val values = store.data.catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }.map {
        DevicePreferences(it[DARK] ?: true, if (it[COLOR] == "pink") AlvaColor.Pink else AlvaColor.Teal,
            if (it[SCREEN] == "list") "list" else "add")
    }
    suspend fun dark(value: Boolean) { store.edit { it[DARK] = value } }
    suspend fun color(value: AlvaColor) { store.edit { it[COLOR] = if (value == AlvaColor.Pink) "pink" else "teal" } }
    suspend fun screen(value: String) { require(value == "list" || value == "add"); store.edit { it[SCREEN] = value } }
    /** Also used by instrumentation to isolate each installed-app scenario. */
    suspend fun reset() { store.edit { it.clear() } }
    private companion object {
        val DARK = booleanPreferencesKey("dark")
        val COLOR = stringPreferencesKey("color")
        val SCREEN = stringPreferencesKey("screen")
    }
}
