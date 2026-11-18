package net.ts.taild80ca0.atlas.twa.ui.theme

import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The motion scale: the durations and easings are the CSS ones, and a device that asked for no
 * animations gets `snap` everywhere, so nothing on the screen waits for a duration (CI emulators run
 * with `ANIMATOR_DURATION_SCALE` 0).
 */
class AlvaMotionTest {
    @Test fun theStateChangeDurationsAreTheSharedScale() {
        assertEquals(120, AlvaMotion.Fast)
        assertEquals(180, AlvaMotion.Base)
        assertEquals(300, AlvaMotion.Slow)
        assertEquals(150, AlvaMotion.Switch)
        // The tokens are the CSS names, so a screen can only pick one of them.
        assertEquals(
            mapOf("--duration-fast" to 120, "--duration-base" to 180, "--duration-slow" to 300, "--duration-switch" to 150),
            AlvaMotion.durations,
        )
    }

    @Test fun theEasingsAreTheCssCurves() {
        assertEquals(listOf(0.2f, 0f, 0f, 1f), AlvaMotion.StandardControl)
        assertEquals(listOf(0.4f, 0f, 1f, 1f), AlvaMotion.ExitControl)
        assertEquals(AlvaMotion.StandardControl, AlvaMotion.easings.getValue("--ease-standard"))
        assertEquals(AlvaMotion.ExitControl, AlvaMotion.easings.getValue("--ease-exit"))
        // A curve is a smooth progression from 0 to 1, not a step.
        assertTrue(AlvaMotion.Standard.transform(0.5f) in 0.5f..1f)
        assertTrue(AlvaMotion.Exit.transform(0.5f) < 0.5f)
    }

    @Test fun noAnimationScaleSnapsEverySpec() {
        assertEquals(tween<Float>(AlvaMotion.Fast, easing = AlvaMotion.Standard), AlvaMotion.spec<Float>(true, AlvaMotion.Fast))
        assertEquals(snap<Float>(), AlvaMotion.spec<Float>(false, AlvaMotion.Fast))
        assertNotNull(AlvaMotion.placement(true))
        assertNull(AlvaMotion.placement(false))
        assertNotNull(AlvaMotion.fadeIn(true))
        assertNull(AlvaMotion.fadeOut(false))
    }
}
