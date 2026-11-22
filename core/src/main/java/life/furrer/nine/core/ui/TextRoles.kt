package life.furrer.nine.core.ui

import android.graphics.Color
import android.widget.TextView

enum class TextRole { TITLE, ROW, META, SECTION, HINT }

/** Applies one of the suite's five text roles, so screens that live in :core never set
 *  textSize/setTextColor/padding by hand. Core-only, like the [Dimens] type scale: app screens
 *  size their own text. Screens that need more than a role (calendar's
 *  bar/time/title composite row) keep styling themselves — these are for the common case, not a
 *  rendering framework. Rows still get their selected/unselected color from
 *  [applyRowSelectionStyle] on top of this; [TextRole.ROW]'s own color is just the unselected
 *  baseline. */
fun TextView.applyRole(role: TextRole) {
    when (role) {
        TextRole.TITLE -> {
            textSize = Dimens.TITLE_SP
            setTextColor(Color.WHITE)
        }
        TextRole.ROW -> {
            textSize = Dimens.ROW_SP
            setTextColor(Palette.GRAY_DIM)
        }
        TextRole.META -> {
            textSize = Dimens.META_SP
            setTextColor(Palette.GRAY_DIM)
        }
        TextRole.SECTION -> {
            textSize = Dimens.SECTION_SP
            setTextColor(Palette.GRAY_MUTED)
            letterSpacing = Dimens.SECTION_LETTER_SPACING
        }
        TextRole.HINT -> {
            textSize = Dimens.HINT_SP
            setTextColor(Palette.GRAY_FAINT)
        }
    }
}
