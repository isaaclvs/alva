package net.ts.taild80ca0.atlas.twa.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import net.ts.taild80ca0.atlas.twa.R
import net.ts.taild80ca0.atlas.twa.QuickAddActivity
import net.ts.taild80ca0.atlas.twa.data.matching.TextNormalizer
import net.ts.taild80ca0.atlas.twa.sync.SnapshotItem
import kotlinx.coroutines.withTimeout

class ShoppingListWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { update(context) }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: android.os.Bundle) {
        manager.updateAppWidget(id, render(context, id))
    }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            AppWidgetManager.ACTION_APPWIDGET_UPDATE, REFRESH -> {
                WidgetRuntime.refresh(context, goAsync())
            }
            ACTION -> {
                val pending = goAsync()
                WidgetRuntime.launch(context) {
                    try {
                        withTimeout(8_000) {
                            when (intent.getStringExtra("operation")) {
                                "mark" -> WidgetRuntime.mark(context, intent.getStringExtra("item") ?: return@withTimeout,
                                    intent.getIntExtra("version", -1), intent.getStringExtra("house") ?: return@withTimeout)
                                "undo" -> WidgetRuntime.undoMark(context, intent.getStringExtra("token") ?: return@withTimeout)
                                "open" -> WidgetRuntime.openList(context).send()
                            }
                        }
                    } finally { pending.finish() }
                }
            }
        }
    }
    companion object {
        const val ACTION = "net.ts.taild80ca0.atlas.twa.widgets.LIST_ACTION"
        const val REFRESH = "net.ts.taild80ca0.atlas.twa.widgets.LIST_REFRESH"
        fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, ShoppingListWidgetProvider::class.java)).forEach { id ->
                manager.updateAppWidget(id, render(context, id))
                manager.notifyAppWidgetViewDataChanged(id, R.id.widget_list_rows)
            }
        }
        fun render(context: Context, id: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.shopping_list_widget)
            val p = WidgetRuntime.palette(context)
            val snapshot = WidgetRuntime.snapshot(context)
            val count = snapshot?.listItems?.count { it.status == 0 } ?: 0
            views.setInt(R.id.widget_list_background, "setColorFilter", p.background)
            for (view in listOf(R.id.widget_list_title, R.id.widget_list_summary, R.id.widget_list_empty)) views.setTextColor(view, p.ink)
            for (view in listOf(R.id.widget_list_add, R.id.widget_list_refresh, R.id.widget_list_undo)) views.setTextColor(view, p.accent)
            views.setTextColor(R.id.widget_list_status, p.soft)
            views.setTextViewText(R.id.widget_list_summary, if (snapshot == null) "Alva" else if (count == 1) "1 pendente" else "$count pendentes")
            views.setViewVisibility(R.id.widget_list_rows, if (count > 0) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_list_empty, if (count > 0) View.GONE else View.VISIBLE)
            views.setTextViewText(R.id.widget_list_empty, if (snapshot == null) "Abra o app para conectar a casa" else if (snapshot.listItems.isEmpty()) "Nada para comprar" else "Tudo pego · Abrir lista")
            views.setTextViewText(R.id.widget_list_status, WidgetRuntime.status(context) ?: "Abrir lista")
            views.setOnClickPendingIntent(R.id.widget_list_title, WidgetRuntime.openList(context))
            views.setOnClickPendingIntent(R.id.widget_list_empty, WidgetRuntime.openList(context))
            views.setOnClickPendingIntent(R.id.widget_list_status, WidgetRuntime.openList(context))
            views.setOnClickPendingIntent(R.id.widget_list_add, PendingIntent.getActivity(context, 601, Intent(context, QuickAddActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            views.setOnClickPendingIntent(R.id.widget_list_refresh, PendingIntent.getBroadcast(context, id,
                Intent(context, ShoppingListWidgetProvider::class.java).setAction(REFRESH), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            val adapter = Intent(context, ShoppingListWidgetService::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            adapter.data = Uri.parse("alva-widget://list/$id")
            views.setRemoteAdapter(R.id.widget_list_rows, adapter)
            val template = Intent(context, WidgetListActionActivity::class.java).setAction(ACTION).setData(Uri.parse("alva-widget://action/$id"))
            views.setPendingIntentTemplate(R.id.widget_list_rows, PendingIntent.getActivity(context, id, template,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
            val undo = WidgetRuntime.undo?.takeIf { it.expiresAt > System.currentTimeMillis() }
            views.setViewVisibility(R.id.widget_list_undo, if (undo == null) View.GONE else View.VISIBLE)
            if (undo != null) views.setOnClickPendingIntent(R.id.widget_list_undo, PendingIntent.getBroadcast(context, id,
                Intent(context, ShoppingListWidgetProvider::class.java).setAction(ACTION).setData(Uri.parse("alva-widget://undo/${undo.token}"))
                    .putExtra("operation", "undo").putExtra("token", undo.token), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            return views
        }
    }
}

class ShoppingListWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = ListFactory(applicationContext)
}

internal class ListFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {
    private var rows: List<Pair<String?, SnapshotItem>> = emptyList()
    private var house: String = ""
    override fun onCreate() { onDataSetChanged(); WidgetRuntime.refresh(context) }
    override fun onDataSetChanged() {
        val snapshot = WidgetRuntime.snapshot(context)
        house = snapshot?.householdPublicId ?: ""
        rows = WidgetListRows.of(snapshot)
    }
    override fun onDestroy() { rows = emptyList() }
    override fun getCount() = rows.size
    override fun getViewTypeCount() = 1
    override fun getLoadingView(): RemoteViews? = null
    override fun hasStableIds() = false
    override fun getItemId(position: Int) = position.toLong()
    override fun getViewAt(position: Int): RemoteViews? {
        val (category, item) = rows.getOrNull(position) ?: return null
        val p = WidgetRuntime.palette(context)
        return RemoteViews(context.packageName, R.layout.shopping_list_widget_row).apply {
            setTextViewText(R.id.widget_row_name, item.name)
            setTextColor(R.id.widget_row_name, p.ink)
            setTextColor(R.id.widget_row_check, p.accent)
            setTextColor(R.id.widget_row_category, p.soft)
            setViewVisibility(R.id.widget_row_category, if (category == null) View.GONE else View.VISIBLE)
            setTextViewText(R.id.widget_row_category, category ?: "")
            setTextViewText(R.id.widget_row_check, if (WidgetRuntime.isBusy(item.publicId)) "…" else "□")
            setContentDescription(R.id.widget_row_check, "Marcar ${item.name} como comprado")
            setOnClickFillInIntent(R.id.widget_row_check, Intent().putExtra("operation", "mark")
                .putExtra("item", item.publicId).putExtra("version", item.lockVersion).putExtra("house", house))
            setOnClickFillInIntent(R.id.widget_row_name, Intent().putExtra("operation", "open"))
        }
    }
}

/** Stable category/name order, with a heading only on the first pending item of a category. */
object WidgetListRows {
    fun of(snapshot: net.ts.taild80ca0.atlas.twa.sync.ServerSnapshot?): List<Pair<String?, SnapshotItem>> {
        if (snapshot == null) return emptyList()
        val categories = snapshot.categories.associateBy { it.publicId }
        val items = snapshot.listItems.filter { it.status == 0 }.sortedWith(compareBy<SnapshotItem> {
            categories[it.categoryPublicId]?.position ?: Int.MAX_VALUE
        }.thenBy { TextNormalizer.plain(it.name) }.thenBy { it.publicId })
        return items.mapIndexed { index, item ->
            val heading = index == 0 || items[index - 1].categoryPublicId != item.categoryPublicId
            (if (heading) categories[item.categoryPublicId]?.name ?: "Sem categoria" else null) to item
        }
    }
}

/** Collection rows share an activity template: opening a row never relies on a background trampoline. */
class WidgetListActionActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getStringExtra("operation") == "open") {
            startActivity(Intent(this, net.ts.taild80ca0.atlas.twa.MainActivity::class.java)
                .putExtra("widget_screen", "list").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        } else if (intent.getStringExtra("operation") == "mark") {
            sendBroadcast(Intent(this, ShoppingListWidgetProvider::class.java).setAction(ShoppingListWidgetProvider.ACTION)
                .putExtras(intent.extras ?: android.os.Bundle()))
        }
        finish()
    }
}
