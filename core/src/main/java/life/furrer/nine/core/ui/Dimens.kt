package life.furrer.nine.core.ui

import android.content.Context
import android.util.TypedValue
import android.view.View

/** The spacing/size scale for core-owned screens (Settings, Manual, the pickers).
 *
 *  Only [SIDE_PADDING_DP] is suite-wide: it is the one side gutter every screen and row renderer
 *  uses, so lists, menus and headers line up across apps. The rest - the type scale and
 *  [TextRole]/[applyRole] built on it - is deliberately core-only: app screens keep their own
 *  text sizes, and moving them onto this scale would be a visible restyle, not a refactor. */
object Dimens {
    const val SIDE_PADDING_DP = 16
    const val ROW_VERTICAL_PADDING_DP = 6
    const val SECTION_TOP_DP = 24
    const val SECTION_FIRST_TOP_DP = 14
    const val DIVIDER_HEIGHT_DP = 1

    const val TITLE_SP = 24f
    const val ROW_SP = 16f
    const val META_SP = 13f
    const val SECTION_SP = 11f
    const val HINT_SP = 11f
    const val SECTION_LETTER_SPACING = 0.11f
}

fun Context.dp(value: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

fun View.dp(value: Int): Int = context.dp(value)
