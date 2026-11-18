package net.ts.taild80ca0.atlas.twa.preferences

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import net.ts.taild80ca0.atlas.twa.data.matching.TextNormalizer
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaColor

private val Context.deviceDataStore by preferencesDataStore("device_preferences",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() })

/**
 * [buyAgainDismissed] is this device's "Agora não" on the due "Comprar de novo" suggestions (#163):
 * normalized name → the epoch millis when hiding ends. One phone's opinion, never the house's.
 */
data class DevicePreferences(
    val dark: Boolean = true,
    val color: AlvaColor = AlvaColor.Teal,
    val screen: String = "add",
    val buyAgainDismissed: Map<String, Long> = emptyMap(),
)

/** One DataStore per application, containing only device personalization, never credentials or list data. */
class DevicePreferencesStore(context: Context) {
    private val store = context.applicationContext.deviceDataStore
    val values = store.data.catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }.map {
        DevicePreferences(it[DARK] ?: true, if (it[COLOR] == "pink") AlvaColor.Pink else AlvaColor.Teal,
            if (it[SCREEN] == "list") "list" else "add", live(it[DISMISSED]))
    }
    suspend fun dark(value: Boolean) { store.edit { it[DARK] = value } }
    suspend fun color(value: AlvaColor) { store.edit { it[COLOR] = if (value == AlvaColor.Pink) "pink" else "teal" } }
    suspend fun screen(value: String) { require(value == "list" || value == "add"); store.edit { it[SCREEN] = value } }

    /**
     * "Agora não" for one due suggestion on this device: hides it for [DISMISS_FOR_MS], keyed by the
     * matching normalization (`TextNormalizer.plain`, the twin of Rails' plain). Expired entries are
     * dropped here, so the set cannot grow forever.
     */
    suspend fun dismissBuyAgain(name: String) {
        val key = TextNormalizer.plain(name)
        if (key.isEmpty()) return
        val now = System.currentTimeMillis()
        val until = now + DISMISS_FOR_MS
        store.edit { preferences ->
            val kept = (preferences[DISMISSED] ?: emptySet()).mapNotNull { decode(it, now) }
            preferences[DISMISSED] = (kept.filter { (entryKey, _) -> entryKey != key }.map { (entryKey, end) -> "$entryKey$SEPARATOR$end" } +
                "$key$SEPARATOR$until").toSet()
        }
    }

    /** Also used by instrumentation to isolate each installed-app scenario. */
    suspend fun reset() { store.edit { it.clear() } }

    private fun live(entries: Set<String>?): Map<String, Long> {
        val now = System.currentTimeMillis()
        return (entries ?: emptySet()).mapNotNull { decode(it, now) }.toMap()
    }

    /** One stored entry (`name|until`) as a pair, or null when it is malformed or already over. */
    private fun decode(entry: String, now: Long): Pair<String, Long>? {
        val separator = entry.indexOf(SEPARATOR)
        if (separator < 0) return null
        val until = entry.substring(separator + 1).toLongOrNull()
        if (until == null || until <= now) return null
        return entry.substring(0, separator) to until
    }

    private companion object {
        val DARK = booleanPreferencesKey("dark")
        val COLOR = stringPreferencesKey("color")
        val SCREEN = stringPreferencesKey("screen")
        val DISMISSED = stringSetPreferencesKey("buy_again_dismissed")
        const val SEPARATOR = '|'
        const val DISMISS_FOR_MS = 3L * 24 * 60 * 60 * 1000
    }
}
