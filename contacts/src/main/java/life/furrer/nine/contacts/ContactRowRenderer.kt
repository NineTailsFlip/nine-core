package life.furrer.nine.contacts

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import life.furrer.nine.core.ui.Dimens
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.dp
import life.furrer.nine.core.util.PhoneFormat

const val CONTACT_ROW_HEIGHT_DP = 32
// 48dp came up short on a real device: the name line is weighted to fill whatever's left after
// the reason line's real height is subtracted, and at 48dp that remainder clips the selected
// row's own underline (see P9's CallLogRowRenderer, which hit the identical bug at the identical
// height and needed the identical extra 8dp).
const val CONTACT_ROW_WITH_REASON_HEIGHT_DP = 56
private const val HEADER_ROW_HEIGHT_DP = 24
private const val ROW_SIDE_PADDING_DP = Dimens.SIDE_PADDING_DP

/** Row height in dp for [row] — a plain name match is one line, a [ContactRow.NumberMatch] is
 *  always two (name + the matching number, §9.9's "show *why* a non-obvious match matched" —
 *  shown regardless of selection, unlike calendar's selected-only metadata line, since the
 *  whole point is explaining every non-obvious row at a glance while scanning the list). */
fun contactRowHeightDp(row: ContactRow): Int = when (row) {
    is ContactRow.SectionHeader -> HEADER_ROW_HEIGHT_DP
    is ContactRow.Entry -> CONTACT_ROW_HEIGHT_DP
    is ContactRow.NumberMatch -> CONTACT_ROW_WITH_REASON_HEIGHT_DP
    is ContactRow.TypedNumber -> CONTACT_ROW_HEIGHT_DP
}

fun createContactRowContainer(context: Context): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER_VERTICAL
}

fun bindContactRow(container: LinearLayout, row: ContactRow, selected: Boolean) {
    container.removeAllViews()
    when (row) {
        is ContactRow.SectionHeader -> bindHeader(container, row)
        is ContactRow.Entry -> bindEntry(container, row.contact.displayName, selected, reason = null)
        is ContactRow.NumberMatch -> bindEntry(container, row.contact.displayName, selected, reason = PhoneFormat.display(container.context, row.matchedNumber))
        is ContactRow.TypedNumber -> bindTypedNumber(container, row.digits)
    }
}

private fun bindHeader(container: LinearLayout, row: ContactRow.SectionHeader) {
    val context = container.context
    val label = if (row.label == "FAVORITES") "★ FAVORITES" else row.label
    container.addView(
        TextView(context).apply {
            text = label
            textSize = 11f
            letterSpacing = 0.11f
            setTextColor(Palette.GRAY_FAINT)
            setPadding(context.dp(ROW_SIDE_PADDING_DP), 0, context.dp(ROW_SIDE_PADDING_DP), 0)
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
}

private fun bindEntry(container: LinearLayout, displayName: String, selected: Boolean, reason: String?) {
    val context = container.context
    container.addView(
        TextView(context).apply {
            text = displayName
            textSize = 18f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(context.dp(ROW_SIDE_PADDING_DP), 0, context.dp(ROW_SIDE_PADDING_DP), 0)
            setTextColor(if (selected) Color.WHITE else Palette.GRAY_DIM)
            paintFlags = if (selected) paintFlags or Paint.UNDERLINE_TEXT_FLAG else paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
    )
    if (reason != null) {
        container.addView(
            TextView(context).apply {
                text = reason
                textSize = 12f
                setTextColor(Palette.GRAY_MUTED)
                setPadding(context.dp(ROW_SIDE_PADDING_DP), 0, context.dp(ROW_SIDE_PADDING_DP), context.dp(4))
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }
}

private fun bindTypedNumber(container: LinearLayout, digits: String) {
    val context = container.context
    container.addView(
        TextView(context).apply {
            text = PhoneFormat.displayTyping(context, digits)
            textSize = 18f
            setTextColor(Palette.GRAY_DIM)
            setPadding(context.dp(ROW_SIDE_PADDING_DP), 0, context.dp(ROW_SIDE_PADDING_DP), 0)
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
}

