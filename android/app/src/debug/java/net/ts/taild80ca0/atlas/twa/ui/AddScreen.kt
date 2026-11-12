package net.ts.taild80ca0.atlas.twa.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.ts.taild80ca0.atlas.twa.data.list.AddOutcome
import net.ts.taild80ca0.atlas.twa.data.list.Category
import net.ts.taild80ca0.atlas.twa.data.list.ListItem
import net.ts.taild80ca0.atlas.twa.data.matching.DuplicateHint
import net.ts.taild80ca0.atlas.twa.online.ActionResult
import net.ts.taild80ca0.atlas.twa.online.AddItems
import net.ts.taild80ca0.atlas.twa.online.AddedItem
import net.ts.taild80ca0.atlas.twa.online.CorrectItem
import net.ts.taild80ca0.atlas.twa.online.OnlineMessages
import net.ts.taild80ca0.atlas.twa.online.OnlineRepository
import net.ts.taild80ca0.atlas.twa.online.OnlineState
import net.ts.taild80ca0.atlas.twa.online.addedItems
import net.ts.taild80ca0.atlas.twa.online.liveItems
import net.ts.taild80ca0.atlas.twa.sync.ServerSnapshot
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaShapes
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaTheme
import net.ts.taild80ca0.atlas.twa.ui.theme.CategoryIcon
import net.ts.taild80ca0.atlas.twa.ui.theme.rowDivider

/** What the line under the add field says, and in which tone: `text-accent-strong`, `text-warn` or `text-danger`. */
data class AddFeedback(val text: String, val tone: Tone, val id: Long) {
    enum class Tone {
        /** Something went on the list. */
        Added,

        /** Nothing new: the names were already there. */
        Already,

        /** The add did not happen. The text is still in the field. */
        Problem,
    }
}

/**
 * Everything the Adicionar screen remembers: the draft, what the last add said, what this device just added and
 * the actions in flight. It lives above the screen (see [rememberAddScreenState]) so that switching to the list
 * and back keeps the draft, like the PWA's two panels that stay on the page, and so that a request already sent
 * is not cancelled by leaving the screen. Only the draft survives a recreation; the rest is device-local feedback
 * that the PWA does not keep across a reload either.
 */
@Stable
class AddScreenState internal constructor(
    initialDraft: String,
    private val repository: OnlineRepository,
    private val scope: CoroutineScope,
) {
    var draft by mutableStateOf(initialDraft)

    var feedback by mutableStateOf<AddFeedback?>(null)
        private set

    var saving by mutableStateOf(false)
        private set

    /** The items this device just added or revived, newest first, at most [RECENT_LIMIT] (`recent_adds_controller.js`). */
    var recent by mutableStateOf<List<String>>(emptyList())
        private set

    private val correcting = mutableStateListOf<String>()
    private var feedbackCount = 0L

    fun isCorrecting(itemPublicId: String) = itemPublicId in correcting

    /** Sends what is in the field. The text leaves the field only when the server took it. */
    fun submit() {
        if (saving) return
        val submitted = draft
        saving = true
        scope.launch {
            try {
                when (val result = repository.execute(AddItems.fromText(submitted))) {
                    is ActionResult.Success -> {
                        // Do not erase text typed while the request was in flight.
                        if (draft == submitted) draft = ""
                        val added = result.receipt.addedItems()
                        val snapshot = (repository.state.value as? OnlineState.Ready)?.snapshot
                        say(
                            OnlineMessages.addFeedback(added) { snapshot?.categoryLabel(it.itemPublicId) ?: NO_CATEGORY },
                            if (added.any { it.outcome != AddOutcome.DUPLICATE }) AddFeedback.Tone.Added else AddFeedback.Tone.Already,
                        )
                        remember(added)
                    }
                    else -> say(OnlineMessages.failure(result, keepsText = true), AddFeedback.Tone.Problem)
                }
            } finally {
                saving = false
            }
        }
    }

    /** The picker under "Acabou de adicionar": the category chosen by hand, which also teaches the dictionary. */
    fun correct(itemPublicId: String, category: Category) {
        if (itemPublicId in correcting) return
        correcting += itemPublicId
        scope.launch {
            try {
                val result = repository.execute(CorrectItem(itemPublicId, categoryPublicId = category.publicId))
                if (result !is ActionResult.Success) say(OnlineMessages.failure(result), AddFeedback.Tone.Problem)
            } finally {
                correcting -= itemPublicId
            }
        }
    }

    /** The PWA's toast removes the message after a few seconds; a problem stays until the next add. */
    internal fun dismiss(feedback: AddFeedback) {
        if (this.feedback?.id == feedback.id) this.feedback = null
    }

    private fun say(text: String, tone: AddFeedback.Tone) {
        feedback = AddFeedback(text, tone, ++feedbackCount)
    }

    /** `turbo_stream.prepend`: the last name typed goes first; only the newest three stay. */
    private fun remember(added: List<AddedItem>) {
        val fresh = added.filter { it.outcome != AddOutcome.DUPLICATE }.map { it.itemPublicId }.reversed().distinct()
        if (fresh.isEmpty()) return
        recent = (fresh.take(RECENT_LIMIT) + recent.filterNot { it in fresh }).take(RECENT_LIMIT)
    }

    companion object {
        const val RECENT_LIMIT = 3
        const val NO_CATEGORY = "Sem categoria"
        const val FEEDBACK_MILLIS = 6_000L
    }
}

@Composable
fun rememberAddScreenState(repository: OnlineRepository): AddScreenState {
    val scope = rememberCoroutineScope()
    return rememberSaveable(
        repository,
        saver = Saver(save = { it.draft }, restore = { AddScreenState(it, repository, scope) }),
    ) { AddScreenState("", repository, scope) }
}

/**
 * The Adicionar screen of the PWA (`#add_screen`, below its header): what was just added, the question, the
 * field with its duplicate hint, and the line that says what the last add did, all anchored at the bottom, next
 * to the thumb and the keyboard. The field takes focus when the screen opens.
 *
 * [buyAgain] and [habituals] are the places of "Comprar de novo" and "Habituais" (#107, #105): empty until
 * those land, where the PWA puts them (between the title and the field, and under the feedback line).
 */
@Composable
fun AddScreen(
    state: AddScreenState,
    snapshot: ServerSnapshot,
    modifier: Modifier = Modifier,
    buyAgain: (@Composable () -> Unit)? = null,
    habituals: (@Composable () -> Unit)? = null,
) {
    val items = remember(snapshot) { snapshot.liveItems() }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val colors = AlvaTheme.colors
    val type = AlvaTheme.type

    state.feedback?.let { current ->
        LaunchedEffect(current.id) {
            if (current.tone != AddFeedback.Tone.Problem) {
                delay(AddScreenState.FEEDBACK_MILLIS)
                state.dismiss(current)
            }
        }
    }

    // Anchored at the bottom: when the content is taller than the space left by the keyboard it scrolls from the
    // bottom, so the field and its button stay in view and what was added scrolls away above them.
    Column(modifier.verticalScroll(rememberScrollState(), reverseScrolling = true), verticalArrangement = Arrangement.Bottom) {
        Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 20.dp)) {
            val rows = remember(state.recent, items) { state.recent.mapNotNull { id -> items.firstOrNull { it.publicId == id } } }
            if (rows.isNotEmpty()) {
                RecentAdds(state, rows, snapshot.categories)
                Spacer(Modifier.height(24.dp))
            }
            Text("O que está faltando em casa?", style = type.pageTitle.copy(lineHeight = 32.sp), color = colors.ink,
                modifier = Modifier.semantics { heading() })
            Text("Lembrou, adicionou.", style = type.bodySmall, color = colors.inkSoft, modifier = Modifier.padding(top = 2.dp))

            Spacer(Modifier.height(16.dp))
            if (buyAgain != null) {
                buyAgain()
                Spacer(Modifier.height(12.dp))
            }
            AddField(state, focus)

            val duplicates = DuplicateHint.alreadyPending(state.draft, items)
            if (duplicates.isNotEmpty()) {
                Text(OnlineMessages.alreadyOnList(duplicates), style = type.caption.copy(fontWeight = FontWeight.Medium), color = colors.warn,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp).testTag("duplicate-hint"))
            }
            Text("Separe por vírgula para adicionar vários.", style = type.caption, color = colors.inkFaint,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp))
            val feedback = state.feedback
            Text(
                feedback?.text.orEmpty(),
                style = type.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = when (feedback?.tone) {
                    AddFeedback.Tone.Added -> colors.accentStrong
                    AddFeedback.Tone.Already -> colors.warn
                    AddFeedback.Tone.Problem -> colors.danger
                    null -> colors.ink
                },
                modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp).fillMaxWidth().heightIn(min = 20.dp)
                    .testTag("feedback").semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (habituals != null) {
                Box(Modifier.padding(top = 8.dp)) { habituals() }
            }
        }
    }

    // The screen is composed with the field's node attached by now: ask for focus from here, not from the field.
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
}

private fun ServerSnapshot.categoryLabel(itemPublicId: String): String {
    val item = listItems.firstOrNull { it.publicId == itemPublicId }
    return categories.firstOrNull { it.publicId == item?.categoryPublicId }?.name ?: AddScreenState.NO_CATEGORY
}

/** `#list_item_form`: `rounded-2xl border-line bg-surface`, accent border and a 4px `accent-soft` ring on focus. */
@Composable
private fun AddField(state: AddScreenState, focus: FocusRequester) {
    val colors = AlvaTheme.colors
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .drawBehind {
                if (focused) {
                    val ring = 4.dp.toPx()
                    drawRoundRect(colors.accentSoft, Offset(-ring, -ring), Size(size.width + 2 * ring, size.height + 2 * ring),
                        CornerRadius(16.dp.toPx() + ring))
                }
            }
            .background(colors.surface, AlvaShapes.Card)
            .border(1.dp, if (focused) colors.accent else colors.line, AlvaShapes.Card)
            .padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = state.draft,
            onValueChange = { state.draft = it },
            modifier = Modifier.weight(1f).focusRequester(focus).onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = "Item" }.testTag("add-input"),
            textStyle = AlvaTheme.type.input.copy(color = colors.ink),
            cursorBrush = SolidColor(colors.accent),
            // Names may also come on separate lines (pasted); the field grows up to four, then scrolls.
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { state.submit() }),
            decorationBox = { field ->
                Box(Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
                    if (state.draft.isEmpty()) Text("Ex.: leite, pão, café", style = AlvaTheme.type.input, color = colors.inkFaint, maxLines = 1)
                    field()
                }
            },
        )
        Box(
            Modifier.size(48.dp).clip(AlvaShapes.Control).background(colors.accent)
                .alpha(if (state.saving) 0.8f else 1f)
                .clickable(role = Role.Button, onClickLabel = "Adicionar item") { state.submit() }
                .semantics { contentDescription = "Adicionar item"; if (state.saving) disabled() }.testTag("save-add"),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(24.dp)) {
                val unit = size.width / 20f
                val stroke = 2.5f * unit
                drawLine(colors.accentInk, Offset(10 * unit, 4 * unit), Offset(10 * unit, 16 * unit), stroke, StrokeCap.Round)
                drawLine(colors.accentInk, Offset(4 * unit, 10 * unit), Offset(16 * unit, 10 * unit), stroke, StrokeCap.Round)
            }
        }
    }
}

/** `#recent_adds`: what this device just added, each with the category it landed in and a picker to fix it. */
@Composable
private fun RecentAdds(state: AddScreenState, rows: List<ListItem>, categories: List<Category>) {
    val colors = AlvaTheme.colors
    Column(Modifier.testTag("recent-adds")) {
        Text("Acabou de adicionar", style = AlvaTheme.type.caption.copy(fontWeight = FontWeight.Medium), color = colors.inkFaint,
            modifier = Modifier.semantics { heading() })
        Column(Modifier.padding(top = 6.dp).heightIn(max = 112.dp).verticalScroll(rememberScrollState())) {
            rows.forEachIndexed { index, item ->
                val category = categories.firstOrNull { it.publicId == item.categoryPublicId }
                Row(
                    Modifier.fillMaxWidth().then(if (index == 0) Modifier else Modifier.rowDivider(colors.line).padding(top = 1.dp)).padding(vertical = 8.dp)
                        .testTag("recent-${item.publicId}"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CategoryIcon(category?.name)
                    Text(item.name, style = AlvaTheme.type.bodySmall, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    CategoryPicker(item, category, categories, enabled = !state.isCorrecting(item.publicId)) { state.correct(item.publicId, it) }
                    Check(colors.accent)
                }
            }
        }
    }
}

/**
 * The `<select class="recent-category">`: text-xs, as wide as its longest option up to `min(9rem, 32vw)`, a
 * transparent border (`border-warn` and `text-warn` when the dictionary did not know the name) and a chevron.
 * An item without any category starts on "Sem categoria".
 */
@Composable
private fun CategoryPicker(item: ListItem, current: Category?, categories: List<Category>, enabled: Boolean, onPick: (Category) -> Unit) {
    val colors = AlvaTheme.colors
    val style = AlvaTheme.type.caption
    var open by remember { mutableStateOf(false) }
    val unknown = current == null || current.name == "Outros"
    val label = current?.name ?: AddScreenState.NO_CATEGORY
    val widest = remember(categories, label) { (categories.map { it.name } + label).maxBy { it.length } }
    val maxWidth = minOf((LocalConfiguration.current.screenWidthDp * 0.32f).dp, 144.dp)
    val tone = if (unknown) colors.warn else colors.inkSoft
    Box {
        Box(
            Modifier.widthIn(max = maxWidth)
                .border(1.dp, if (unknown) colors.warn else Color.Transparent, RoundedCornerShape(8.dp))
                .clickable(enabled = enabled, role = Role.DropdownList) { open = true }
                .semantics { contentDescription = "Categoria de ${item.name}: $label" }
                .testTag("recent-category-${item.publicId}"),
        ) {
            // The 1dp of the border is part of the box in CSS (`border` + `py-1.5 pr-6 pl-1`): 30dp high.
            Box(Modifier.padding(start = 5.dp, top = 7.dp, end = 25.dp, bottom = 7.dp)) {
                Text(widest, style = style, color = Color.Transparent, maxLines = 1, modifier = Modifier.clearAndSetSemantics { })
                Text(label, style = style, color = tone, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.matchParentSize().clearAndSetSemantics { })
            }
            Chevron(if (unknown) colors.warn else colors.inkFaint, Modifier.align(Alignment.CenterEnd).padding(end = 5.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            categories.forEach { category ->
                DropdownMenuItem(
                    text = { Text(category.name, style = AlvaTheme.type.bodySmall, color = colors.ink) },
                    onClick = {
                        open = false
                        if (category.publicId != current?.publicId) onPick(category)
                    },
                    modifier = Modifier.testTag("category-option-${category.name}"),
                )
            }
        }
    }
}

/** `m4 6 4 4 4-4` of a 16×16 box, stroke 2, at 14dp (`size-3.5`). */
@Composable
private fun Chevron(color: Color, modifier: Modifier = Modifier) {
    val path = remember { PathParser().parsePathString("M4 6l4 4 4-4").toPath() }
    Canvas(modifier.size(14.dp)) {
        val scale = size.width / 16f
        withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
            drawPath(path, color, style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** The `✓` after a row of "Acabou de adicionar" (`text-xs text-accent`): a thin tick, 12dp. */
@Composable
private fun Check(color: Color) {
    Canvas(Modifier.size(12.dp).clearAndSetSemantics { }) {
        val unit = size.width / 12f
        val tick = androidx.compose.ui.graphics.Path().apply {
            moveTo(1.5f * unit, 6.5f * unit); lineTo(4.5f * unit, 9.5f * unit); lineTo(10.5f * unit, 2.5f * unit)
        }
        drawPath(tick, color, style = Stroke(1.3f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
