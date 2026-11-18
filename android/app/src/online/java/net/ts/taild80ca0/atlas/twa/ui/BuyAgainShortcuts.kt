package net.ts.taild80ca0.atlas.twa.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.ts.taild80ca0.atlas.twa.data.matching.TextNormalizer
import net.ts.taild80ca0.atlas.twa.sync.SnapshotSuggestion
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaTheme
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaShapes
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaSize
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaSpacing

/**
 * How a due suggestion reads under its chip (#163): the median days between purchases the server computed
 * from the archive. One day is "todo dia"; the rest are "a cada ~N dias".
 */
internal fun buyAgainCadence(days: Int): String = if (days <= 1) "costuma acabar todo dia" else "costuma acabar a cada ~$days dias"

/**
 * The "Comprar de novo" chips, in the server's order: a name whose cadence already passed comes first and
 * says how often it runs out. "Agora não" hides that suggestion on this device for a few days (DataStore,
 * never the house), so the hint does not become noise. The chips keep their 48dp touch target and shape.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BuyAgainShortcuts(
    suggestions: List<SnapshotSuggestion>,
    enabled: Boolean,
    onAdd: (String) -> Unit,
    dismissed: Map<String, Long> = emptyMap(),
    onDismiss: (String) -> Unit = {},
) {
    val colors = AlvaTheme.colors
    val now = System.currentTimeMillis()
    val shown = suggestions.filter { suggestion -> !dismissed.isDismissed(suggestion.name, now) }.take(5)
    if (shown.isEmpty()) return

    Column(Modifier.fillMaxWidth().testTag("buy-again")) {
        Text("Comprar de novo", style = AlvaTheme.type.caption.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp), color = colors.inkSoft,
            modifier = Modifier.padding(bottom = AlvaSpacing.sm).semantics { heading() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AlvaSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(AlvaSpacing.sm)) {
            shown.forEach { suggestion ->
                Column(Modifier.testTag("buy-again-suggestion-${suggestion.name}")) {
                    Row(Modifier.heightIn(min = AlvaSize.TouchMin).background(colors.surface, AlvaShapes.Full)
                        .border(1.dp, colors.line, AlvaShapes.Full)
                        .clickable(enabled = enabled, role = Role.Button) { onAdd(suggestion.name) }
                        .testTag("buy-again-${suggestion.name}").padding(horizontal = AlvaSpacing.md, vertical = AlvaSpacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(AlvaSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
                        Text("+", style = AlvaTheme.type.caption, color = colors.accent)
                        Text(suggestion.name, style = AlvaTheme.type.caption, color = colors.ink)
                    }
                    if (suggestion.due && suggestion.cadenceDays != null) {
                        Text(buyAgainCadence(suggestion.cadenceDays), style = AlvaTheme.type.caption, color = colors.inkFaint,
                            modifier = Modifier.testTag("buy-again-due-${suggestion.name}"))
                        Text("Agora não", style = AlvaTheme.type.caption, color = colors.inkFaint,
                            modifier = Modifier.heightIn(min = AlvaSize.TouchMin).clickable(role = Role.Button) { onDismiss(suggestion.name) }
                                .testTag("buy-again-dismiss-${suggestion.name}"))
                    }
                }
            }
        }
    }
}

/** Whether this device already answered "Agora não" to [name] and the window has not closed yet. */
private fun Map<String, Long>.isDismissed(name: String, now: Long): Boolean =
    (this[TextNormalizer.plain(name)] ?: 0L) > now
