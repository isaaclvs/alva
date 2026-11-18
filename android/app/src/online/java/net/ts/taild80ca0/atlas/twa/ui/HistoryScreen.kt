package net.ts.taild80ca0.atlas.twa.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.TimeZone
import kotlinx.coroutines.launch
import net.ts.taild80ca0.atlas.twa.online.ActionResult
import net.ts.taild80ca0.atlas.twa.online.HistoryPage
import net.ts.taild80ca0.atlas.twa.online.HistoryRead
import net.ts.taild80ca0.atlas.twa.online.HistoryReadd
import net.ts.taild80ca0.atlas.twa.online.HistoryTrip
import net.ts.taild80ca0.atlas.twa.online.OnlineMessages
import net.ts.taild80ca0.atlas.twa.online.OnlineProblem
import net.ts.taild80ca0.atlas.twa.online.OnlineRepository
import net.ts.taild80ca0.atlas.twa.online.readdHistory
import net.ts.taild80ca0.atlas.twa.sync.ServerSnapshot
import net.ts.taild80ca0.atlas.twa.ui.theme.*

/**
 * What the "Compras" screen shows: a page of history, the month being read and the last failure. It is
 * separate from [net.ts.taild80ca0.atlas.twa.online.OnlineState] on purpose — history never takes the
 * list away and the list never changes because of it.
 */
@Stable
class HistoryScreenState {
    var page by mutableStateOf<HistoryPage?>(null)
        private set

    /** The month on screen (null = the newest one with a trip), so a reload keeps the page. */
    var month by mutableStateOf<String?>(null)
        private set

    var loading by mutableStateOf(false)
        private set

    var problem by mutableStateOf<OnlineProblem?>(null)
        private set

    /** The trip whose "Adicionar tudo de novo" is on its way. */
    var readding by mutableStateOf<String?>(null)
        private set

    fun start(month: String?) {
        this.month = month
        loading = true
        problem = null
    }

    fun received(read: HistoryRead) {
        loading = false
        when (read) {
            is HistoryRead.Received -> {
                page = read.page
                month = read.page.month
                problem = null
            }
            is HistoryRead.Transient -> problem = if (read.offline) OnlineProblem.OFFLINE else OnlineProblem.SERVER
            HistoryRead.Unauthorized -> problem = OnlineProblem.NO_ACCESS
            is HistoryRead.Invalid -> problem = OnlineProblem.UNEXPECTED
        }
    }

    /** Opening the screen reads the newest page again: history changes on the other device too. */
    fun forget() {
        page = null
        month = null
        problem = null
    }

    fun readdStarted(publicId: String) {
        readding = publicId
    }

    fun readdFinished() {
        readding = null
    }
}

@Composable
fun rememberHistoryScreenState() = remember { HistoryScreenState() }

/**
 * The read-only "Compras" screen, reachable from the ⋯ menu: the trips "Compra feita" archived, one
 * month per page, newest first. Each trip opens into its items by category and its one action,
 * "Adicionar tudo de novo", which sends the same `add_items` command as the add field. Nothing here
 * edits history.
 */
@Composable
fun HistoryScreen(
    repository: OnlineRepository,
    snapshot: ServerSnapshot?,
    state: HistoryScreenState = rememberHistoryScreenState(),
    onBack: () -> Unit,
    onReadd: (HistoryReadd) -> Unit,
    modifier: Modifier = Modifier,
    zone: TimeZone = TimeZone.getDefault(),
) {
    val scope = rememberCoroutineScope()
    val categories = remember(snapshot) { snapshot?.categories?.associate { it.publicId to it.name }.orEmpty() }

    fun load(month: String?) {
        state.start(month)
        scope.launch { state.received(repository.history(month)) }
    }

    // The first opening reads the newest month; a resume after an action does not: the page stays.
    LaunchedEffect(Unit) {
        if (state.page == null && !state.loading) load(null)
    }

    Column(modifier.fillMaxSize().testTag("history-screen")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(48.dp).clickable(interactionSource = remember { MutableInteractionSource() },
                    indication = null, role = Role.Button, onClick = onBack)
                    .semantics { contentDescription = "Voltar para a lista" }
                    .testTag("history-back"), contentAlignment = Alignment.Center) { BackIcon() }
                Column(Modifier.weight(1f)) {
                    Text("Compras", style = AlvaTheme.type.pageTitle.copy(lineHeight = 33.6.sp), color = AlvaTheme.colors.ink,
                        modifier = Modifier.testTag("history-title").semantics { heading() })
                    Text("O que já foi comprado, por mês.", style = AlvaTheme.type.caption, color = AlvaTheme.colors.inkFaint)
                }
            }
            val page = state.page
            if (page?.month != null) MonthNavigation(
                month = page.month, older = page.olderMonth, newer = page.newerMonth,
                enabled = !state.loading && state.readding == null, onSelect = ::load,
            )
        }
        state.problem?.let { problem ->
            NoticeCard(problem.message, NoticeTone.Warn, "history-problem", Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                NoticeAction("Tentar de novo", AlvaTheme.colors.inkSoft, Modifier.testTag("history-reload")) { load(state.month) }
            }
        }
        val page = state.page
        when {
            page == null && state.problem != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nenhuma compra para mostrar.", style = AlvaTheme.type.body, color = AlvaTheme.colors.inkSoft)
            }
            page == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Abrindo as compras…", color = AlvaTheme.colors.inkSoft, modifier = Modifier.testTag("history-loading"))
            }
            page.isEmpty -> EmptyHistory(onBack)
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(page.trips, key = { it.publicId }) { trip ->
                    HistoryTripCard(
                        trip = trip, categories = categories, zone = zone,
                        enabled = !state.loading && state.readding == null, reading = state.readding == trip.publicId,
                        onReadd = {
                            state.readdStarted(trip.publicId)
                            scope.launch {
                                try {
                                    onReadd(repository.readdHistory(trip))
                                } finally {
                                    state.readdFinished()
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

/** "Mês anterior" / the month / "Próximo mês": one page at a time, 48dp of touch. */
@Composable
private fun MonthNavigation(month: String, older: String?, newer: String?, enabled: Boolean, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        MonthStep("Mês anterior", older, enabled, onSelect, "history-older", Alignment.CenterStart)
        Text(HistoryDate.monthLabel(month), style = AlvaTheme.type.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = AlvaTheme.colors.ink, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).testTag("history-month"))
        MonthStep("Próximo mês", newer, enabled, onSelect, "history-newer", Alignment.CenterEnd)
    }
}

@Composable
private fun RowScope.MonthStep(
    label: String,
    target: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    tag: String,
    align: Alignment,
) {
    if (target == null) {
        // No page on that side: the space stays, so the month keeps its place.
        Spacer(Modifier.widthIn(min = 96.dp))
        return
    }
    Box(Modifier.heightIn(min = 48.dp).widthIn(min = 96.dp).testTag(tag)
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = enabled, role = Role.Button) { onSelect(target) }
        .padding(horizontal = 8.dp), contentAlignment = align) {
        Text(label, style = AlvaTheme.type.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = if (enabled) AlvaTheme.colors.accentStrong else AlvaTheme.colors.inkFaint)
    }
}

/**
 * One trip: a summary with the date and the size that opens into its items by category and its
 * "Adicionar tudo de novo". Closed by default, like the PWA's `<details>`.
 */
@Composable
private fun HistoryTripCard(
    trip: HistoryTrip,
    categories: Map<String, String>,
    zone: TimeZone,
    enabled: Boolean,
    reading: Boolean,
    onReadd: () -> Unit,
) {
    val colors = AlvaTheme.colors
    var open by remember(trip.publicId) { mutableStateOf(false) }
    val groups = remember(trip, categories) { HistoryGroups.of(categories, trip) }
    Column(Modifier.fillMaxWidth().background(colors.surface, AlvaShapes.Card).border(1.dp, colors.line, AlvaShapes.Card)
        .testTag("history-trip-${trip.publicId}")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { open = !open }
            .pressedSurface(remember { MutableInteractionSource() }, colors.surface2)
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 12.dp)
            .semantics { contentDescription = if (open) "Recolher a compra" else "Abrir a compra" }
            .testTag("history-trip-summary-${trip.publicId}"),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(HistoryDate.label(trip.finishedAtMicros, zone), style = AlvaTheme.type.body.copy(fontWeight = FontWeight.Medium),
                    color = colors.ink)
                Text(HistoryDate.itemsLabel(trip.itemsCount), style = AlvaTheme.type.caption, color = colors.inkFaint,
                    modifier = Modifier.padding(top = 2.dp))
            }
            Chevron(open)
        }
        if (open) {
            Column(Modifier.fillMaxWidth()) {
                groups.forEach { group ->
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CategoryIcon(group.categoryName)
                        Text(group.label, style = AlvaTheme.type.sectionLabel, color = colors.inkSoft,
                            modifier = Modifier.weight(1f).testTag("history-category-${group.label}"))
                        Text("${group.items.size}", style = AlvaTheme.type.caption, color = colors.inkFaint)
                    }
                    group.items.forEachIndexed { index, item ->
                        Text(item.name, style = AlvaTheme.type.row, color = colors.ink,
                            modifier = Modifier.fillMaxWidth()
                                .then(if (index == 0) Modifier else Modifier.rowDivider(colors.line).padding(top = 1.dp))
                                .padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
                Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)) {
                    ReadAgainButton(enabled = enabled, reading = reading, onClick = onReadd)
                }
            }
        }
    }
}

@Composable
private fun ReadAgainButton(enabled: Boolean, reading: Boolean, onClick: () -> Unit) {
    val colors = AlvaTheme.colors
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .background(colors.accentSoft, AlvaShapes.Control)
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = enabled && !reading,
            role = Role.Button, onClick = onClick)
        .testTag("history-readd"),
        contentAlignment = Alignment.Center) {
        Text(if (reading) "Adicionando…" else "Adicionar tudo de novo",
            style = AlvaTheme.type.button, color = colors.accentStrong)
    }
}

@Composable
private fun EmptyHistory(onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 64.dp).testTag("history-empty"),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🧾", fontSize = 36.sp, lineHeight = 40.sp)
        Text("Nenhuma compra registrada ainda.", style = AlvaTheme.type.body, color = AlvaTheme.colors.inkSoft,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        Text("Ao tocar em Compra feita, o que foi comprado aparece aqui.", style = AlvaTheme.type.bodySmall,
            color = AlvaTheme.colors.inkFaint, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        Text("Voltar para a lista", style = AlvaTheme.type.button.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
            color = AlvaTheme.colors.accentStrong,
            modifier = Modifier.padding(top = 12.dp).heightIn(min = 48.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onBack)
                .testTag("history-empty-back"))
    }
}

@Composable
private fun BackIcon() {
    val color = AlvaTheme.colors.inkSoft
    androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
        val unit = size.width / 20f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(12.5f * unit, 4f * unit); lineTo(6.5f * unit, 10f * unit); lineTo(12.5f * unit, 16f * unit)
        }
        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(
            1.75f * unit, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

/** The disclosure chevron of the summary: points down when closed, up when open. */
@Composable
private fun Chevron(open: Boolean) {
    val color = AlvaTheme.colors.inkFaint
    androidx.compose.foundation.Canvas(Modifier.size(16.dp)) {
        val unit = size.width / 16f
        val path = androidx.compose.ui.graphics.Path().apply {
            if (open) { moveTo(4f * unit, 10f * unit); lineTo(8f * unit, 6f * unit); lineTo(12f * unit, 10f * unit) }
            else { moveTo(4f * unit, 6f * unit); lineTo(8f * unit, 10f * unit); lineTo(12f * unit, 6f * unit) }
        }
        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(
            1.75f * unit, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

/** The text of the toast "Adicionar tudo de novo" shows: the add summary, or why nothing was added. */
fun historyReaddToast(result: HistoryReadd): String = when (result) {
    is HistoryReadd.Done -> OnlineMessages.added(result.items)
    is HistoryReadd.Failed -> OnlineMessages.failure(result.result)
}
