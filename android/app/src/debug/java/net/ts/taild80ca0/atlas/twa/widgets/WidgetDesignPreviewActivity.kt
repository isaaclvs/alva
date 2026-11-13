package net.ts.taild80ca0.atlas.twa.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import net.ts.taild80ca0.atlas.twa.R
import net.ts.taild80ca0.atlas.twa.QuickAddWidgetProvider
import net.ts.taild80ca0.atlas.twa.access.*
import net.ts.taild80ca0.atlas.twa.data.list.Category
import net.ts.taild80ca0.atlas.twa.online.*
import net.ts.taild80ca0.atlas.twa.preferences.DevicePreferencesStore
import net.ts.taild80ca0.atlas.twa.sync.*
import net.ts.taild80ca0.atlas.twa.ui.theme.*

/** Debug-only visual fixture: real RemoteViews/Compose, sample data, no network or production access. */
class WidgetDesignPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = intent.data?.getQueryParameter("dark") == "true"
        val color = if (intent.data?.getQueryParameter("color") == "teal") AlvaColor.Teal else AlvaColor.Pink
        val habits = listOf("Leite", "Café", "Ovos", "Queijo", "Carne", "Farofa branca", "Frango", "Macarrão", "Mortadela", "Peixe")
            .mapIndexed { i, name -> SnapshotHabitual("habit-$i", name, name.lowercase(), 0) }
        val categories = listOf(Category("dairy", "Laticínios", 1), Category("clean", "Limpeza", 2), Category("other", "Outros", 3))
        val items = listOf("Leite", "Sabonete para as mãos", "Carne bovina").mapIndexed { i, name ->
            SnapshotItem("item-$i", name, name.lowercase(), categories[i].publicId, 0, null, null, 0, 0, 1, null)
        }
        val sample = ServerSnapshot(1, 1, 1, "design-preview", "Nossa casa", categories, emptyList(), items, emptyList(), emptyList(), habits, emptyList())
        val access = HouseAccess(object : CredentialVault {
            override fun read() = VaultRead.Found(StoredAccess(AccessCredential(1, "debug-preview-only")))
            override fun write(access: StoredAccess) = Unit
        }, object : HouseAccessApi {
            override suspend fun pair(password: String, deviceName: String, previousToken: String?): ApiResult<AccessCredential> = error("Preview has no network")
            override suspend fun identifyHousehold(credential: AccessCredential): ApiResult<HouseholdIdentity> = error("Preview has no network")
        }, object : HouseholdBinding {
            override suspend fun current() = sample.householdPublicId
            override suspend fun bind(householdPublicId: String) = BindResult.Bound
        })
        val repo = OnlineRepository(OnlineAccess.of(access), object : SnapshotTransport {
            override suspend fun fetch(credential: AccessCredential) = SnapshotOutcome.Received(sample)
        }, object : CommandTransport {
            override suspend fun send(credential: AccessCredential, commandId: String, document: String) = CommandOutcome.Refused(422, "preview_only")
        }, lifecycleScope)
        AccessGraph.install(access, online = repo)
        val settings = HabitualWidgetSettings("Cozinha", habits.take(4).map { it.publicId })
        lifecycleScope.launch {
            val prefs = DevicePreferencesStore(this@WidgetDesignPreviewActivity)
            prefs.dark(dark); prefs.color(color)
            access.refresh(); WidgetRuntime.bind(this@WidgetDesignPreviewActivity, repo); repo.refresh()
            delay(150)
            HabitualWidgetSettings.save(this@WidgetDesignPreviewActivity, PREVIEW_ID, settings)
            setContent {
                var configure by remember { mutableStateOf(intent.data?.getQueryParameter("screen") == "config") }
                AlvaTheme(dark, color) {
                    SideEffect { applyAlvaSystemBars(AlvaPalettes.of(dark, color)) }
                    if (configure) WidgetConfiguration(settings, habits, true, null, { configure = false }, {}, { configure = false })
                    else Column(Modifier.fillMaxSize().background(Color(0xff24212a)).windowInsetsPadding(WindowInsets.safeDrawing)
                        .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = { configure = true }) { Text("Configurar atalhos") }
                        AndroidView(factory = { context -> ShoppingListWidgetProvider.render(context, PREVIEW_ID).apply(context, FrameLayout(context)).also { root ->
                            val rows = ListFactory(context).apply { onDataSetChanged() }
                            root.findViewById<ListView>(R.id.widget_list_rows).adapter = object : BaseAdapter() {
                                override fun getCount() = rows.count
                                override fun getItem(position: Int) = position
                                override fun getItemId(position: Int) = position.toLong()
                                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = rows.getViewAt(position)!!.apply(context, parent)
                            }
                        } }, modifier = Modifier.fillMaxWidth().height(335.dp))
                        AndroidView(factory = { context -> HabitualWidgetProvider.render(context, PREVIEW_ID, 340, 160).apply(context, FrameLayout(context)) }, modifier = Modifier.fillMaxWidth().height(160.dp))
                        AndroidView(factory = { context -> QuickAddWidgetProvider.render(context, PREVIEW_ID).apply(context, FrameLayout(context)) }, modifier = Modifier.fillMaxWidth().height(96.dp))
                        if (android.os.Build.VERSION.SDK_INT >= 26 && AppWidgetManager.getInstance(this@WidgetDesignPreviewActivity).isRequestPinAppWidgetSupported) {
                            TextButton(onClick = {
                                AppWidgetManager.getInstance(this@WidgetDesignPreviewActivity).requestPinAppWidget(ComponentName(this@WidgetDesignPreviewActivity, ShoppingListWidgetProvider::class.java), null, null)
                            }) { Text("Fixar lista no launcher") }
                        }
                    }
                }
            }
        }
    }
    companion object { const val PREVIEW_ID = 88001 }
}
