package net.ts.taild80ca0.atlas.twa.widgets

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.ts.taild80ca0.atlas.twa.MainActivity
import net.ts.taild80ca0.atlas.twa.access.AccessGraph
import net.ts.taild80ca0.atlas.twa.online.OnlineState
import net.ts.taild80ca0.atlas.twa.online.OnlineProblem
import net.ts.taild80ca0.atlas.twa.preferences.DevicePreferencesStore
import net.ts.taild80ca0.atlas.twa.sync.SnapshotHabitual
import net.ts.taild80ca0.atlas.twa.ui.theme.*

/** Device-local choices; the preview does not save settings or send commands. */
class HabitualWidgetConfigureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val preferences = DevicePreferencesStore(this)
        val repository = AccessGraph.onlineRepository(this)
        val settings = HabitualWidgetSettings.read(this, id)
        lifecycleScope.launch {
            val initial = preferences.values.first()
            setContent {
                val prefs by preferences.values.collectAsState(initial)
                val state = repository?.state?.collectAsState()?.value
                val ready = (state as? OnlineState.Ready)?.takeUnless { it.problem == OnlineProblem.NO_ACCESS }
                AlvaTheme(prefs.dark, prefs.color) {
                    SideEffect { applyAlvaSystemBars(AlvaPalettes.of(prefs.dark, prefs.color)) }
                    WidgetConfiguration(
                        settings = settings,
                        habituals = ready?.snapshot?.habitualItems.orEmpty(),
                        loaded = ready != null,
                        problem = (state as? OnlineState.Ready)?.problem?.message ?: (state as? OnlineState.Failed)?.problem?.message,
                        back = ::finish,
                        manage = { startActivity(Intent(this@HabitualWidgetConfigureActivity, MainActivity::class.java).putExtra("widget_screen", "add")) },
                        save = { chosen ->
                            HabitualWidgetSettings.save(this@HabitualWidgetConfigureActivity, id, chosen)
                            HabitualWidgetProvider.update(this@HabitualWidgetConfigureActivity)
                            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                            finish()
                        },
                    )
                }
            }
        }
    }
    override fun onResume() { super.onResume(); WidgetRuntime.refresh(this) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WidgetConfiguration(
    settings: HabitualWidgetSettings,
    habituals: List<SnapshotHabitual>,
    loaded: Boolean,
    problem: String?,
    back: () -> Unit,
    manage: () -> Unit,
    save: (HabitualWidgetSettings) -> Unit,
) {
    val c = AlvaTheme.colors
    var name by rememberSaveable { mutableStateOf(settings.title) }
    var selected by rememberSaveable { mutableStateOf(ArrayList(settings.ids)) }
    var compact by rememberSaveable { mutableStateOf(false) }
    var reorder by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(habituals, loaded) {
        if (loaded) selected = ArrayList(selected.filter { id -> habituals.any { it.publicId == id } })
    }
    val chosen = selected.mapNotNull { id -> habituals.find { it.publicId == id } }
    Column(Modifier.fillMaxSize().background(c.canvas).windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = back, modifier = Modifier.semantics { contentDescription = "Voltar" }) { Text("‹", fontSize = 28.sp, lineHeight = 36.sp) }
            Text("Configurar atalhos", fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold, color = c.ink, modifier = Modifier.weight(1f))
            Text("alva", color = c.accent, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 10.dp))
        }
        HorizontalDivider(color = c.line)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("PRÉVIA NA TELA INICIAL", color = c.inkSoft, fontSize = 10.sp, lineHeight = 13.sp, modifier = Modifier.weight(1f))
                Row(Modifier.background(c.surface, CircleShape).padding(3.dp)) {
                    listOf(true to "2 × 2", false to "4 × 2").forEach { (small, label) ->
                        Text(label, fontSize = 11.sp, lineHeight = 14.sp, color = if (compact == small) c.accent else c.inkSoft,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.background(if (compact == small) c.accentSoft else c.surface, CircleShape)
                                .clickable { compact = small }.padding(horizontal = 12.dp, vertical = 9.dp))
                    }
                }
            }
            Box(Modifier.fillMaxWidth().background(c.surface2.copy(alpha = .5f), RoundedCornerShape(24.dp)).padding(14.dp), contentAlignment = Alignment.Center) {
                Column(Modifier.width(if (compact) 190.dp else 340.dp).background(c.canvas, RoundedCornerShape(22.dp))
                    .border(1.dp, c.line, RoundedCornerShape(22.dp)).padding(13.dp).testTag("widget-preview")) {
                    Text(name.ifBlank { "Atalhos de casa" }, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, color = c.ink)
                    Spacer(Modifier.height(9.dp))
                    if (chosen.isEmpty()) Text("Escolha os atalhos abaixo", color = c.inkSoft, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(vertical = 18.dp))
                    chosen.take(if (compact) 4 else 8).chunked(if (compact) 2 else 4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                            row.forEach { habit ->
                                Column(Modifier.weight(1f).background(c.accentSoft, RoundedCornerShape(14.dp)).padding(horizontal = 4.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("+", fontSize = 22.sp, lineHeight = 29.sp, color = c.accent)
                                    Text(habit.name, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, color = c.ink, maxLines = 2)
                                }
                            }
                            repeat((if (compact) 2 else 4) - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text("Nome do widget", color = c.ink, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("widget-name").semantics { contentDescription = "Nome do widget" }, shape = RoundedCornerShape(14.dp),
                textStyle = LocalTextStyle.current.copy(fontSize = 15.sp, lineHeight = 20.sp),
                colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = c.surface, focusedContainerColor = c.surface, unfocusedBorderColor = c.line))
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Escolha os atalhos", color = c.ink, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
                Text("${selected.size} selecionados", color = c.accent, fontSize = 12.sp, lineHeight = 16.sp)
            }
            Text("Toque nos itens que você quer ter à mão.", color = c.inkSoft, fontSize = 12.sp, lineHeight = 16.sp)
            problem?.let { Text(it, color = c.danger, fontSize = 12.sp, lineHeight = 16.sp) }
            error?.let { Text(it, color = c.danger, fontSize = 12.sp, lineHeight = 16.sp) }
            if (!loaded && problem == null) Text("Carregando habituais…", color = c.inkSoft, fontSize = 13.sp, lineHeight = 17.sp)
            if (loaded && habituals.isEmpty()) Text("Salve seus itens frequentes nos habituais do Alva.", color = c.inkSoft, fontSize = 13.sp, lineHeight = 17.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                habituals.sortedBy { it.normalizedName }.forEach { habit ->
                    val active = habit.publicId in selected
                    FilterChip(selected = active, onClick = {
                        if (active) selected = ArrayList(selected - habit.publicId)
                        else if (selected.size < 8) selected = ArrayList(selected + habit.publicId)
                        else error = "Escolha até 8 atalhos por widget."
                        if (selected.size < 8) error = null
                    }, label = { Text("${if (active) "✓" else "+"} ${habit.name}", fontSize = 12.sp, lineHeight = 16.sp) },
                        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = active, borderColor = c.line, selectedBorderColor = c.accent),
                        shape = CircleShape, modifier = Modifier.testTag("habit-${habit.publicId}"),
                        colors = FilterChipDefaults.filterChipColors(containerColor = c.surface, labelColor = c.ink, selectedContainerColor = c.accentSoft, selectedLabelColor = c.accent))
                }
            }
            HorizontalDivider(color = c.line)
            TextButton(onClick = { reorder = !reorder }, contentPadding = PaddingValues(0.dp)) {
                Text("Reordenar atalhos ${if (reorder) "⌃" else "⌄"}", fontWeight = FontWeight.Bold, color = c.ink)
            }
            if (reorder) {
                Text("Mova os itens para ajustar a ordem da prévia.", fontSize = 12.sp, lineHeight = 16.sp, color = c.inkSoft)
                chosen.forEachIndexed { index, habit ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}.  ${habit.name}", modifier = Modifier.weight(1f), color = c.ink, fontSize = 13.sp, lineHeight = 17.sp)
                        listOf(-1 to "↑", 1 to "↓").forEach { (step, arrow) ->
                            TextButton(onClick = {
                                selected = ArrayList(selected).apply { removeAt(index); add(index + step, habit.publicId) }
                            }, enabled = index + step in chosen.indices,
                                modifier = Modifier.semantics { contentDescription = "Mover ${habit.name} ${if (step < 0) "para cima" else "para baixo"}" }) { Text(arrow) }
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = c.line)
        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Button(onClick = {
                if (selected.isEmpty()) error = "Escolha pelo menos um habitual."
                else save(HabitualWidgetSettings(name, selected.toList()))
            }, enabled = loaded && selected.isNotEmpty(), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
                Text("Salvar widget", fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = manage, modifier = Modifier.fillMaxWidth()) { Text("Gerenciar habituais no Alva →", color = c.inkSoft, fontSize = 12.sp, lineHeight = 16.sp) }
        }
    }
}
