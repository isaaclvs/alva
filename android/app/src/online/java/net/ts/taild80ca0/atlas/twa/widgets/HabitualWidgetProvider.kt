package net.ts.taild80ca0.atlas.twa.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.withTimeout
import net.ts.taild80ca0.atlas.twa.R

class HabitualWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        update(context)
        WidgetRuntime.refresh(context, goAsync())
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = update(context)
    override fun onDeleted(context: Context, ids: IntArray) { ids.forEach { HabitualWidgetSettings.delete(context, it) } }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ADD) return
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val habitualId = intent.getStringExtra("habitual_id") ?: return
        // An obsolete PendingIntent may outlive configuration; never add a deselected item.
        if (habitualId !in HabitualWidgetSettings.read(context, widgetId).ids) return
        val pending = goAsync()
        WidgetRuntime.launch(context) {
            try { withTimeout(8_000) { WidgetRuntime.addHabitual(context, habitualId) } } finally { pending.finish() }
        }
    }
    companion object {
        private const val ADD = "net.ts.taild80ca0.atlas.twa.widgets.ADD_HABITUAL"
        @JvmStatic fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, HabitualWidgetProvider::class.java)).forEach { widgetId ->
                val options = manager.getAppWidgetOptions(widgetId)
                manager.updateAppWidget(widgetId, render(context, widgetId,
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180),
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180)))
            }
        }
        fun render(context: Context, widgetId: Int, width: Int, height: Int): RemoteViews {
            val snapshot = WidgetRuntime.snapshot(context)
            val palette = WidgetRuntime.palette(context)
                val settings = HabitualWidgetSettings.read(context, widgetId)
                val habits = snapshot?.habitualItems.orEmpty().associateBy { it.publicId }
                val ids = settings.selected(habits.keys)
                val fontScale = context.resources.configuration.fontScale.coerceAtLeast(1f)
                val cellHeight = (48 * fontScale).toInt()
                val rows = ((height - 76 * fontScale) / cellHeight).toInt().coerceIn(1, 6)
                val columns = ((width - 16) / (56 * fontScale)).toInt().coerceIn(1, 4)
                val visible = ids.take(rows * columns)
                val views = RemoteViews(context.packageName, R.layout.habitual_widget)
                views.setInt(R.id.habitual_widget_background, "setColorFilter", palette.background)
                views.setTextViewText(R.id.habitual_widget_title, settings.title)
                views.setTextColor(R.id.habitual_widget_title, palette.ink)
                views.setTextColor(R.id.habitual_widget_configure, palette.accent)
                val configure = Intent(context, HabitualWidgetConfigureActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId).setData(Uri.parse("alva-widget://habitual/configure/$widgetId"))
                views.setOnClickPendingIntent(R.id.habitual_widget_configure, PendingIntent.getActivity(context, widgetId, configure, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                val message = WidgetRuntime.status(context) ?: when {
                    snapshot == null -> "Abra o Alva para conectar e atualizar."
                    ids.isEmpty() -> "Escolha seus atalhos na engrenagem."
                    ids.size > visible.size -> "${ids.size - visible.size} atalhos a mais · amplie o widget"
                    else -> "Toque para adicionar"
                }
                views.setTextViewText(R.id.habitual_widget_message, message)
                views.setTextColor(R.id.habitual_widget_message, palette.soft)
                views.removeAllViews(R.id.habitual_widget_rows)
                visible.chunked(columns).forEach { pair ->
                    val row = RemoteViews(context.packageName, R.layout.habitual_widget_row)
                    listOf(R.id.habitual_widget_cell_left, R.id.habitual_widget_cell_right, R.id.habitual_widget_cell_third, R.id.habitual_widget_cell_fourth).forEachIndexed { index, cell ->
                        val habit = pair.getOrNull(index)?.let(habits::get)
                        if (habit == null) row.setViewVisibility(cell, View.GONE) else {
                            val busy = WidgetRuntime.busyHabitual(context, habit.publicId)
                            val pending = snapshot?.listItems.orEmpty().any { it.status == 0 && it.normalizedName == habit.normalizedName }
                            row.setTextViewText(cell, "${if (busy) "…" else if (pending) "✓" else "+"} ${habit.name}")
                            row.setTextColor(cell, if (pending) palette.soft else palette.accent)
                            row.setContentDescription(cell, if (busy) "Adicionando ${habit.name}" else if (pending) "${habit.name} já está na lista" else "Adicionar ${habit.name}")
                            row.setBoolean(cell, "setEnabled", !busy)
                            val add = Intent(context, HabitualWidgetProvider::class.java).setAction(ADD)
                                .setData(Uri.parse("alva-widget://habitual/$widgetId/${habit.publicId}"))
                                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId).putExtra("habitual_id", habit.publicId)
                            row.setOnClickPendingIntent(cell, PendingIntent.getBroadcast(context, widgetId, add, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                        }
                    }
                    views.addView(R.id.habitual_widget_rows, row)
                }
                return views
        }
    }
}
