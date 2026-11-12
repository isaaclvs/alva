package net.ts.taild80ca0.atlas.twa.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.ts.taild80ca0.atlas.twa.ui.theme.*

@Composable
internal fun FinishShoppingButton(purchased: Int, enabled: Boolean, onClick: () -> Unit) {
    val colors = AlvaTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp)) {
        Button(onClick, enabled = enabled, shape = AlvaShapes.Card, border = BorderStroke(1.dp, colors.accent),
            colors = ButtonDefaults.buttonColors(containerColor = colors.accentSoft, contentColor = colors.accentStrong),
            modifier = Modifier.fillMaxWidth().testTag("finish-shopping"), contentPadding = PaddingValues(vertical = 12.dp)) {
            Text("Compra feita", style = AlvaTheme.type.body.copy(fontWeight = FontWeight.SemiBold))
        }
        Text("Arquiva $purchased ${if (purchased == 1) "item comprado" else "itens comprados"}; pendentes continuam na lista.",
            style = AlvaTheme.type.caption, color = colors.inkFaint, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}
