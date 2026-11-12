package net.ts.taild80ca0.atlas.twa.ui.theme

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlvaTypographyTest {
    private fun font(name: String): ByteBuffer {
        val file = listOf("src/debug/res/font/$name.ttf", "app/src/debug/res/font/$name.ttf").map(::File).firstOrNull { it.isFile }
            ?: error("$name.ttf not found from ${File(".").absoluteFile}")
        return ByteBuffer.wrap(file.readBytes())
    }

    /** `usWeightClass` of the OS/2 table, to prove the packaged file really is that weight. */
    private fun weightClass(font: ByteBuffer): Int {
        assertEquals("TrueType scaler type", 0x00010000, font.getInt(0))
        for (i in 0 until font.getShort(4).toInt()) {
            val entry = 12 + i * 16
            val tag = ByteArray(4) { font.get(entry + it) }.toString(Charsets.US_ASCII)
            if (tag == "OS/2") return font.getShort(font.getInt(entry + 8) + 4).toInt() and 0xFFFF
        }
        error("no OS/2 table")
    }

    @Test fun nunitoSansIsPackagedInTheThreeWeightsOfThePwa() {
        assertEquals(400, weightClass(font("nunito_sans_regular")))
        assertEquals(600, weightClass(font("nunito_sans_semibold")))
        assertEquals(700, weightClass(font("nunito_sans_bold")))
    }

    @Test fun textStylesUseNunitoSansAtThePwaSizes() {
        val t = AlvaTypographyTokens
        assertEquals(28.sp, t.pageTitle.fontSize)
        assertEquals(FontWeight.SemiBold, t.pageTitle.fontWeight)
        assertEquals(16.sp, t.body.fontSize)
        assertEquals(14.sp, t.bodySmall.fontSize)
        assertEquals(12.sp, t.caption.fontSize)
        assertEquals(18.sp, t.input.fontSize)
        assertEquals(FontWeight.SemiBold, t.sectionLabel.fontWeight)
        for (style in listOf(t.pageTitle, t.heading, t.brand, t.body, t.bodySmall, t.caption, t.input, t.row, t.sectionLabel, t.summary, t.navigation, t.button)) {
            assertEquals(NunitoSans, style.fontFamily)
            assertTrue(style.lineHeight.value >= style.fontSize.value)
        }
    }

    @Test fun materialSlotsSpeakNunitoSansToo() {
        val material = alvaMaterialTypography()
        for (style in listOf(material.bodyLarge, material.bodyMedium, material.labelLarge, material.headlineMedium, material.titleMedium)) {
            assertEquals(NunitoSans, style.fontFamily)
        }
    }
}
