package net.ts.taild80ca0.atlas.twa.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaTheme

/** Touch-only left swipe, like the PWA. Vertical scrolling and mouse gestures keep their usual behavior. */
internal fun Modifier.swipeToRemove(enabled: Boolean, onRemove: () -> Unit): Modifier =
    composed {
        val remove by rememberUpdatedState(onRemove)
        var dragging by remember { mutableStateOf(false) }
        var offset by remember { mutableFloatStateOf(0f) }
        val translation by animateFloatAsState(if (dragging) offset else 0f,
            animationSpec = if (dragging) snap() else tween(150), label = "swipe-reset")
        graphicsLayer { translationX = translation }.pointerInput(enabled) {
            var distance = 0f
            var touch = false
            detectHorizontalDragGestures(
                onDragStart = { distance = 0f; touch = false; dragging = true },
                onHorizontalDrag = { change, amount ->
                    if (change.type == PointerType.Touch) {
                        touch = true
                        distance += amount
                        if (enabled) offset = distance.coerceAtMost(0f)
                        change.consume()
                    }
                },
                // Still consume a horizontal drag in shopping mode so it cannot become a checkbox tap.
                onDragEnd = {
                    dragging = false
                    if (enabled && touch && distance <= -size.width * 0.4f) remove()
                },
                onDragCancel = { distance = 0f; dragging = false },
            )
        }
    }

/** Reused by the inline correction form (#100); removal has no separate confirmation. */
@Composable
fun RemoveItemButton(enabled: Boolean = true, onRemove: () -> Unit) {
    TextButton(onClick = onRemove, enabled = enabled, modifier = Modifier.testTag("remove-item")) {
        Text("Remover", color = AlvaTheme.colors.danger)
    }
}
