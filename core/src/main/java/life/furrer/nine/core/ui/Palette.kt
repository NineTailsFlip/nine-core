package life.furrer.nine.core.ui

import kotlin.math.roundToInt

/** The all-black, dim-gray, pastel-accent color system every Nine app shares. */
object Palette {
    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val GRAY_DIM = 0xFF8A8A8A.toInt()
    const val GRAY_MUTED = 0xFF666666.toInt()
    const val GRAY_FAINT = 0xFF4D4D4D.toInt()
    const val DIVIDER = 0xFF262626.toInt()
    const val GREEN = 0xFF89E1A7.toInt()
    const val RED = 0xFFCF6679.toInt()

    private const val PASTEL_MAX_SATURATION = 0.45
    private const val PASTEL_LIGHTNESS = 0.72

    /** CalDAV servers hand out saturated web colors (#FF0000, #0000FF) that glare on true
     *  black. Converts to HSL, clamps saturation to at most 0.45 and fixes lightness at ~0.72 —
     *  a consistent pastel band regardless of how saturated or dark the source color was —
     *  while preserving hue, so calendars stay visually distinguishable. */
    fun pastelize(argb: Int): Int {
        val a = (argb ushr 24) and 0xFF
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF

        val (h, s, _) = rgbToHsl(r, g, b)
        val (nr, ng, nb) = hslToRgb(h, minOf(s, PASTEL_MAX_SATURATION), PASTEL_LIGHTNESS)
        return (a shl 24) or (nr shl 16) or (ng shl 8) or nb
    }

    /** @return (hue in [0, 360), saturation in [0, 1], lightness in [0, 1]) */
    fun rgbToHsl(r: Int, g: Int, b: Int): Triple<Double, Double, Double> {
        val rf = r / 255.0
        val gf = g / 255.0
        val bf = b / 255.0
        val max = maxOf(rf, gf, bf)
        val min = minOf(rf, gf, bf)
        val l = (max + min) / 2.0
        if (max == min) return Triple(0.0, 0.0, l)

        val d = max - min
        val s = if (l > 0.5) d / (2.0 - max - min) else d / (max + min)
        val h = 60.0 * when (max) {
            rf -> ((gf - bf) / d + (if (gf < bf) 6.0 else 0.0))
            gf -> (bf - rf) / d + 2.0
            else -> (rf - gf) / d + 4.0
        }
        return Triple(h, s, l)
    }

    /** Inverse of [rgbToHsl]. @return (r, g, b) each in [0, 255]. */
    fun hslToRgb(h: Double, s: Double, l: Double): Triple<Int, Int, Int> {
        if (s == 0.0) {
            val v = toByte(l)
            return Triple(v, v, v)
        }
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        val hk = h / 360.0
        return Triple(
            toByte(hueToRgbChannel(p, q, hk + 1.0 / 3.0)),
            toByte(hueToRgbChannel(p, q, hk)),
            toByte(hueToRgbChannel(p, q, hk - 1.0 / 3.0)),
        )
    }

    private fun hueToRgbChannel(p: Double, q: Double, tIn: Double): Double {
        var t = tIn
        if (t < 0) t += 1.0
        if (t > 1) t -= 1.0
        return when {
            t < 1.0 / 6.0 -> p + (q - p) * 6.0 * t
            t < 1.0 / 2.0 -> q
            t < 2.0 / 3.0 -> p + (q - p) * (2.0 / 3.0 - t) * 6.0
            else -> p
        }
    }

    private fun toByte(value: Double): Int = (value * 255.0).roundToInt().coerceIn(0, 255)

    /** Sort key for arranging a set of colors into a visually smooth rainbow — what a
     *  color-picker grid should order its swatches by, instead of whatever order the data
     *  happened to arrive in (alphabetical by a CalDAV color's own name, insertion order of a
     *  hand-authored preset list, ...). Used by both `ColorPickerActivity` callers, C9's
     *  `EventStore.queryEventColors` and M9's unread-marker presets, rather than each sorting
     *  independently, so "what order do swatches appear in" stays one shared rule.
     *
     *  Primarily hue, but a near-zero-saturation color (gray, or close to it) gets pushed after
     *  every real hue instead of sorting by its own [rgbToHsl] hue value — [rgbToHsl] defines hue
     *  as `0` whenever `max == min` (saturation 0), which would otherwise land every gray right
     *  next to pure reds (hue `0` too) rather than grouped sensibly with the other neutrals. */
    fun paletteSortKey(argb: Int): Double {
        val (h, s, l) = rgbToHsl((argb ushr 16) and 0xFF, (argb ushr 8) and 0xFF, argb and 0xFF)
        return if (s < 0.15) 1000.0 + l else h
    }
}
