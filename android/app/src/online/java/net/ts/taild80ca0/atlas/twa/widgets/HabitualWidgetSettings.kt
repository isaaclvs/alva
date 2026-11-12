package net.ts.taild80ca0.atlas.twa.widgets

import android.content.Context
import org.json.JSONArray

/** Only presentation choices; never a pending command or an independent habitual catalogue. */
data class HabitualWidgetSettings(val title: String = "Atalhos de casa", val ids: List<String> = emptyList()) {
    fun selected(availableIds: Set<String>): List<String> = ids.distinct().filter { it in availableIds }
    companion object {
        private fun prefs(context: Context) = context.getSharedPreferences("habitual_widgets", Context.MODE_PRIVATE)
        fun read(context: Context, widgetId: Int): HabitualWidgetSettings {
            val prefs = prefs(context)
            val ids = runCatching {
                val array = JSONArray(prefs.getString("$widgetId.ids", "[]"))
                (0 until array.length()).map { array.getString(it) }.distinct()
            }.getOrDefault(emptyList())
            return HabitualWidgetSettings(prefs.getString("$widgetId.title", "Atalhos de casa") ?: "Atalhos de casa", ids)
        }
        fun save(context: Context, widgetId: Int, settings: HabitualWidgetSettings) {
            prefs(context).edit().putString("$widgetId.title", settings.title.trim().take(40).ifBlank { "Atalhos de casa" })
                .putString("$widgetId.ids", JSONArray(settings.ids.distinct()).toString()).apply()
        }
        fun delete(context: Context, widgetId: Int) {
            prefs(context).edit().remove("$widgetId.title").remove("$widgetId.ids").apply()
        }
    }
}
