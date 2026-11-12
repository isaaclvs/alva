package net.ts.taild80ca0.atlas.twa.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import net.ts.taild80ca0.atlas.twa.sync.SnapshotSuggestion
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaTheme

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BuyAgainShortcuts(suggestions: List<SnapshotSuggestion>, enabled: Boolean, onAdd: (String) -> Unit) {
    val colors = AlvaTheme.colors
    Column(Modifier.fillMaxWidth().testTag("buy-again")) {
        Text("Comprar de novo", style = AlvaTheme.type.caption.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp), color = colors.inkSoft,
            modifier = Modifier.padding(bottom = 8.dp).semantics { heading() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.take(5).forEach { suggestion ->
                Row(Modifier.background(colors.surface, CircleShape).border(1.dp, colors.line, CircleShape)
                    .clickable(enabled = enabled, role = Role.Button) { onAdd(suggestion.name) }
                    .testTag("buy-again-${suggestion.name}").padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("+", style = AlvaTheme.type.caption, color = colors.accent)
                    Text(suggestion.name, style = AlvaTheme.type.caption, color = colors.ink)
                }
            }
        }
    }
}
