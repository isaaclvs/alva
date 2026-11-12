package net.ts.taild80ca0.atlas.twa.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaTheme

/** Touch-only left swipe, like the PWA. Vertical scrolling and mouse gestures keep their usual behavior. */
internal fun Modifier.swipeToRemove(enabled: Boolean, onRemove: () -> Unit): Modifier =
    if (!enabled) this else pointerInput(onRemove) {
        var distance = 0f
        var touch = false
        val threshold = 80 * density
        detectHorizontalDragGestures(
            onDragStart = { distance = 0f; touch = false },
            onHorizontalDrag = { change, amount ->
                if (change.type == PointerType.Touch) {
                    touch = true
                    distance += amount
                    change.consume()
                }
            },
            onDragEnd = { if (touch && distance <= -threshold) onRemove() },
            onDragCancel = { distance = 0f },
        )
    }

/** Reused by the inline correction form (#100); removal has no separate confirmation. */
@Composable
fun RemoveItemButton(enabled: Boolean = true, onRemove: () -> Unit) {
    TextButton(onClick = onRemove, enabled = enabled, modifier = Modifier.testTag("remove-item")) {
        Text("Remover", color = AlvaTheme.colors.danger)
    }
}
