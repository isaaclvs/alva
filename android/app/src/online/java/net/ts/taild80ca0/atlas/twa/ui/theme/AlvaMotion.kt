package net.ts.taild80ca0.atlas.twa.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset

/**
 * The motion layer: three durations and two easings, the same numbers the PWA declares in its
 * `@theme` (`--duration-*`, `--ease-*`). Screens name a token instead of writing `tween(150)`.
 *
 * A transition only ever explains a state change; it never delays one. When the device asks for no
 * animations (`Settings.Global.ANIMATOR_DURATION_SCALE == 0`, common on CI emulators and when
 * "Remove animations" is on), every spec here is [snap]: the same state changes render at once, so
 * neither the person nor a Compose test waits for a duration.
 */
object AlvaMotion {
    /** 120ms: the direct answer to a touch (check, strike, dimming while "sending", leaving). */
    const val Fast = 120

    /** 180ms: a state settling in (toast entering, selected tab of the capsule, the list making room). */
    const val Base = 180

    /** 300ms: a measured move across the screen (the shopping progress bar). */
    const val Slow = 300

    /** 150ms: the "Só pendentes" switch, the one duration the PWA already used before the scale. */
    const val Switch = 150

    /** `cubic-bezier(0.2, 0, 0, 1)`, the CSS `--ease-standard`: decelerating, the default. */
    val StandardControl = listOf(0.2f, 0f, 0f, 1f)

    /** `cubic-bezier(0.4, 0, 1, 1)`, the CSS `--ease-exit`: accelerating, for what leaves. */
    val ExitControl = listOf(0.4f, 0f, 1f, 1f)

    val Standard: Easing = CubicBezierEasing(
        StandardControl[0], StandardControl[1], StandardControl[2], StandardControl[3],
    )

    val Exit: Easing = CubicBezierEasing(
        ExitControl[0], ExitControl[1], ExitControl[2], ExitControl[3],
    )

    /** The tick of the round check lands slightly past its size and settles, like the PWA's pop. */
    const val CheckDamping = 0.7f

    /** A state change at [durationMillis], or [snap] when the device draws no animations. */
    fun <T> spec(enabled: Boolean, durationMillis: Int, easing: Easing = Standard): AnimationSpec<T> =
        if (enabled) tween(durationMillis, easing = easing) else snap()

    /** The check's spring, or [snap] when the device draws no animations. */
    fun <T> springSpec(enabled: Boolean, dampingRatio: Float = CheckDamping): AnimationSpec<T> =
        if (enabled) spring(dampingRatio = dampingRatio) else snap()

    /** The list's placement spec: a row slides to where the change put it, or jumps there instantly. */
    fun placement(enabled: Boolean): FiniteAnimationSpec<IntOffset>? =
        if (enabled) tween(Base, easing = Standard) else null

    /** A row fading in, and its shorter fade out, or nothing at all when animations are off. */
    fun fadeIn(enabled: Boolean): FiniteAnimationSpec<Float>? =
        if (enabled) tween(Base, easing = Standard) else null

    fun fadeOut(enabled: Boolean): FiniteAnimationSpec<Float>? =
        if (enabled) tween(Fast, easing = Exit) else null

    /** The durations the CSS declares as `--duration-*`; [PwaThemeParityTest] compares the two. */
    val durations: Map<String, Int>
        get() = mapOf(
            "--duration-fast" to Fast, "--duration-base" to Base,
            "--duration-slow" to Slow, "--duration-switch" to Switch,
        )

    /** The easings the CSS declares as `--ease-*`, in `cubic-bezier()` order. */
    val easings: Map<String, List<Float>>
        get() = mapOf("--ease-standard" to StandardControl, "--ease-exit" to ExitControl)
}

/**
 * True when the device draws animations: `Settings.Global.ANIMATOR_DURATION_SCALE` is above zero.
 * Read once per call site, because the scale is a device setting and not a per-frame value.
 */
@Composable
fun alvaAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
}
