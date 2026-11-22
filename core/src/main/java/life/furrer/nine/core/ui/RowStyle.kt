package life.furrer.nine.core.ui

import android.graphics.Color
import android.graphics.Paint
import android.widget.TextView

/** The suite's selection convention (white + underline vs. dim gray) for a plain single-line
 *  row. Rows with more than one styled element (calendar's bar/time/title) don't fit this and
 *  style themselves directly instead. */
fun TextView.applyRowSelectionStyle(selected: Boolean) {
    setTextColor(if (selected) Color.WHITE else Palette.GRAY_DIM)
    paintFlags = if (selected) {
        paintFlags or Paint.UNDERLINE_TEXT_FLAG
    } else {
        paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
    }
}
