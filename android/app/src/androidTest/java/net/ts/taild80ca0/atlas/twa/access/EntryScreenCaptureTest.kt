package net.ts.taild80ca0.atlas.twa.access

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaColor
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaTheme
import org.junit.Rule
import org.junit.Test

/** Manual tool (not part of the suite): renders the entry screen at 412×915 dp into the app's files dir. */
class EntryScreenCaptureTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun capture() {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "captures").apply { mkdirs() }
        var dark = androidx.compose.runtime.mutableStateOf(true)
        var color = androidx.compose.runtime.mutableStateOf(AlvaColor.Teal)
        var message = androidx.compose.runtime.mutableStateOf<String?>(null)
        val widths = StringBuilder()
        compose.setContent {
            val measurer = androidx.compose.ui.text.rememberTextMeasurer()
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.LaunchedEffect(Unit) {
                for (size in listOf(14f, 140f)) for (t in listOf("Senha da casa", "Use a senha da casa. Este aparelho ficará conectado por")) {
                    val style = net.ts.taild80ca0.atlas.twa.ui.theme.AlvaTypographyTokens.bodySmall.copy(fontSize = size.sp)
                    val w = measurer.measure(t, style).size.width / density.density
                    widths.append("$size $t = $w\n")
                }
            }
            AlvaTheme(dark.value, color.value) {
                Box(Modifier.size(412.dp, 915.dp).testTag("shot")) { EntryScreen(working = false, message = message.value, onSubmit = {}) }
            }
        }
        for (isDark in listOf(true, false)) for (accent in AlvaColor.entries) for (wrong in listOf(false, true)) {
            dark.value = isDark
            color.value = accent
            message.value = if (wrong) AccessMessages.forError(ConnectError.Server(AccessError.WrongPassword)) else null
            compose.waitForIdle()
            val name = "${if (isDark) "dark" else "light"}-${accent.name.lowercase()}-${if (wrong) "wrong" else "login"}.png"
            val bitmap = compose.onNodeWithTag("shot").captureToImage().asAndroidBitmap()
            if (isDark && accent == AlvaColor.Teal && wrong) {
                for (tag in listOf("title", "help", "message", "password", "connect")) {
                    val b = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                    val d = compose.density.density
                    widths.append("$tag x=${b.left / d} y=${b.top / d} w=${b.width / d} h=${b.height / d}\n")
                }
            }
            File(dir, "widths.txt").writeText(widths.toString())
            File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
