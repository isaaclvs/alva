package net.ts.taild80ca0.atlas.twa.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaColors
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaShapes
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaTheme

/**
 * One failure on screen: the message, an optional way to run the same action again, and an [id] that tells two
 * messages apart. A newer message always replaces the one before it, so the auto-hide of the older one can never
 * take the newer one away.
 */
class FeedbackMessage(val text: String, val id: Long, val retry: (() -> Unit)? = null)

/** How long a failure stays if nobody closes it: long enough to read, short enough not to become furniture. */
const val FEEDBACK_MILLIS = 8_000L

/**
 * The dismissible failure of the screens. Only one is shown at a time (the newest). It goes away when the person
 * closes it, when the next action starts ([dismiss]), when the auto-hide of [FEEDBACK_MILLIS] runs out, or when a
 * success replaces it — so a failure never outlives the situation that caused it, as the PWA's fixed
 * `#mutation_status` did.
 */
@Stable
class FeedbackState {
    var current by mutableStateOf<FeedbackMessage?>(null)
        private set

    private var count = 0L

    fun show(text: String, retry: (() -> Unit)? = null) {
        current = FeedbackMessage(text, ++count, retry)
    }

    fun dismiss() {
        current = null
    }

    /** The auto-hide of [message]: only the message still on screen is taken away. */
    fun dismiss(message: FeedbackMessage) {
        if (current?.id == message.id) current = null
    }
}

@Composable
fun rememberFeedbackState() = remember { FeedbackState() }

/** `mutation_status` of the PWA, as a card instead of bare text over the content: `surface-2`, a hairline and the
 * tone in the icon, with the message in `ink` so it stays readable over either theme. The actions sit under the
 * message, each with 48dp of touch, like the undo toast. */
@Composable
fun NoticeCard(
    message: String,
    tone: NoticeTone,
    tag: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = AlvaTheme.colors
    Column(modifier.fillMaxWidth().background(c.surface2, AlvaShapes.Card).border(1.dp, c.line, AlvaShapes.Card)
        .padding(start = 12.dp, top = 10.dp, end = 4.dp, bottom = 0.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NoticeIcon(tone)
            Text(message, style = AlvaTheme.type.bodySmall, color = c.ink, maxLines = 4, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp).weight(1f)
                    .testTag(tag).semantics { liveRegion = LiveRegionMode.Polite })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** Which voice a [NoticeCard] speaks in: a stale list (warn) or a failed action (danger). */
enum class NoticeTone(val color: (AlvaColors) -> Color) {
    Warn({ it.warn }),
    Danger({ it.danger }),
}

/** A text action of a [NoticeCard]: 48dp tall, no ripple, no Material button chrome. */
@Composable
fun NoticeAction(label: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, style = AlvaTheme.type.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = color)
    }
}

/** The `!` in a circle, in the tone's color: the token that says how serious the card is. */
@Composable
private fun NoticeIcon(tone: NoticeTone) {
    val color = tone.color(AlvaTheme.colors)
    Canvas(Modifier.size(16.dp).clearAndSetSemantics { }) {
        val unit = size.width / 16f
        drawCircle(color, 6.5f * unit, center = center, style = Stroke(1.5f * unit))
        drawLine(color, Offset(8f * unit, 4.5f * unit), Offset(8f * unit, 9f * unit), 1.5f * unit, StrokeCap.Round)
        drawCircle(color, 0.9f * unit, Offset(8f * unit, 11.5f * unit))
    }
}

/**
 * The failure of the host, with "Fechar" always and "Tentar de novo" when the caller can repeat the action. It hides
 * itself after [FEEDBACK_MILLIS]; a message that arrived meanwhile keeps its own time.
 */
@Composable
fun FeedbackBanner(message: FeedbackMessage, onDismiss: () -> Unit, modifier: Modifier = Modifier,
    retryLabel: String = "Tentar de novo") {
    LaunchedEffect(message.id) {
        delay(FEEDBACK_MILLIS)
        onDismiss()
    }
    val c = AlvaTheme.colors
    // The add screen keeps its own `#feedback` line under the field, so the host's card has its own tag: the two can
    // be on screen at once (a mark answered while Adicionar is open).
    NoticeCard(message.text, NoticeTone.Danger, "feedback-message", modifier.testTag("feedback-banner")) {
        message.retry?.let { retry -> NoticeAction(retryLabel, c.accentStrong, Modifier.testTag("feedback-retry"), retry) }
        NoticeAction("Fechar", c.inkSoft, Modifier.testTag("feedback-close"), onDismiss)
    }
}
