package life.furrer.nine.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Pure Kotlin, no Android dependency — plain JUnit, no Robolectric needed. */
class PaletteTest {

    private fun hueOf(argb: Int): Double {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return Palette.rgbToHsl(r, g, b).first
    }

    private fun saturationOf(argb: Int): Double {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return Palette.rgbToHsl(r, g, b).second
    }

    private fun lightnessOf(argb: Int): Double {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return Palette.rgbToHsl(r, g, b).third
    }

    @Test
    fun `pastelizing a saturated color lands within the target saturation and lightness band`() {
        val red = 0xFFFF0000.toInt()
        val pastelRed = Palette.pastelize(red)

        assertTrue("saturation should be clamped low, was ${saturationOf(pastelRed)}", saturationOf(pastelRed) <= 0.46)
        assertEquals(0.72, lightnessOf(pastelRed), 0.02)
    }

    @Test
    fun `pastelizing preserves hue for red, green, and blue`() {
        assertEquals(0.0, hueOf(Palette.pastelize(0xFFFF0000.toInt())), 2.0)
        assertEquals(120.0, hueOf(Palette.pastelize(0xFF00FF00.toInt())), 2.0)
        assertEquals(240.0, hueOf(Palette.pastelize(0xFF0000FF.toInt())), 2.0)
    }

    @Test
    fun `pastelizing preserves the alpha channel`() {
        val input = 0x80FF0000.toInt()
        val output = Palette.pastelize(input)
        assertEquals(0x80, (output ushr 24) and 0xFF)
    }

    @Test
    fun `an already-pastel color is not pushed further away from its saturation`() {
        // A color already within the target band (S well under 0.45) shouldn't get more
        // saturated — pastelize only ever clamps down, never up.
        val (r, g, b) = Palette.hslToRgb(200.0, 0.2, 0.6)
        val input = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        val output = Palette.pastelize(input)

        assertTrue(saturationOf(output) <= 0.2 + 0.02)
    }

    @Test
    fun `an achromatic gray stays achromatic after pastelizing`() {
        val gray = 0xFF808080.toInt()
        val output = Palette.pastelize(gray)
        val r = (output ushr 16) and 0xFF
        val g = (output ushr 8) and 0xFF
        val b = output and 0xFF

        assertEquals(r, g)
        assertEquals(g, b)
        assertEquals(0.0, saturationOf(output), 0.01)
    }

    @Test
    fun `rgbToHsl and hslToRgb round trip within rounding tolerance`() {
        val original = 0xFF3366CC.toInt()
        val r = (original ushr 16) and 0xFF
        val g = (original ushr 8) and 0xFF
        val b = original and 0xFF

        val (h, s, l) = Palette.rgbToHsl(r, g, b)
        val (rr, rg, rb) = Palette.hslToRgb(h, s, l)

        assertTrue(abs(r - rr) <= 1)
        assertTrue(abs(g - rg) <= 1)
        assertTrue(abs(b - rb) <= 1)
    }
}
