package life.furrer.nine.contacts

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import life.furrer.nine.contacts.ContactStore.ContactGroup
import life.furrer.nine.core.ui.Dimens
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.dp

/** One row of the groups list ([GroupsActivity]) — pure data, built by [buildGroupRows] and
 *  rendered by [bindGroupRow], the same split as [ContactRow]/[buildContactRows]. */
sealed interface GroupRow {
    /** The address book a run of groups belongs to. Only emitted when groups come from more than
     *  one account: with a single address book (the usual case — WhatsApp/Signal mirrors have no
     *  groups) a heading naming it on every screen is noise. */
    data class AccountHeader(val accountName: String) : GroupRow

    data class Entry(
        val group: ContactGroup,
        val memberCount: Int,
        /** This app's favorites ([FavoritesSync]) group — marked ★ so it's clear why starring someone also
         *  changes this group. */
        val isFavorites: Boolean,
        /** False when "Show in contacts list" is off for it ([ContactStore.setGroupShown]). */
        val shown: Boolean,
    ) : GroupRow
}

fun GroupRow.isSelectable(): Boolean = this is GroupRow.Entry

/** [groups] as [ContactStore.queryGroups] returns them (already untitled-free). The favorites
 *  group always comes first — it's the one used most — then the rest A–Z, ignoring case; with
 *  several address books, the favorites group's own one leads. [memberCounts] from
 *  [ContactGroups.queryMemberCounts] — a group missing there has no members. */
fun buildGroupRows(
    groups: List<ContactGroup>,
    memberCounts: Map<Long, Int>,
    favoritesGroupId: Long?,
    hiddenGroupIds: Set<Long>,
): List<GroupRow> {
    val ordered = groups.sortedWith(compareBy({ it.id != favoritesGroupId }, { it.title.lowercase() }))
    val byAccount = ordered.groupBy { it.accountName to it.accountType }
    val withHeaders = byAccount.size > 1
    val rows = mutableListOf<GroupRow>()
    byAccount.forEach { (account, accountGroups) ->
        if (withHeaders) rows.add(GroupRow.AccountHeader(account.first))
        accountGroups.forEach { group ->
            rows.add(
                GroupRow.Entry(
                    group = group,
                    memberCount = memberCounts[group.id] ?: 0,
                    isFavorites = group.id == favoritesGroupId,
                    shown = group.id !in hiddenGroupIds,
                ),
            )
        }
    }
    return rows
}

/** "Family · 12", or "★ Favorites · 3" for the favorites group. */
fun groupEntryLabel(row: GroupRow.Entry): String {
    val star = if (row.isFavorites) "★ " else ""
    return "$star${row.group.title} · ${row.memberCount}"
}

/** The right-hand note on a row: only a group hidden from the contacts list has one. */
fun groupStatusLabel(row: GroupRow.Entry): String? = if (row.shown) null else "hidden"

/** The dim "‹ name" line atop a shared screen that's one step in from the contacts list, the
 *  same shape as the detail screen's "‹ contact". */
fun backTitle(label: String): String = "‹ $label"

private const val HEADER_ROW_HEIGHT_DP = 24

fun groupRowHeightDp(row: GroupRow): Int = when (row) {
    is GroupRow.AccountHeader -> HEADER_ROW_HEIGHT_DP
    is GroupRow.Entry -> CONTACT_ROW_HEIGHT_DP
}

fun createGroupRowContainer(context: Context): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

/** Styled like the contacts list's own rows (18sp name, white + underline when selected) and
 *  letter headers, so moving between the two screens doesn't change the visual language. A group
 *  hidden from the contacts list says so on the right, the way C9's old filter screen did. */
fun bindGroupRow(container: LinearLayout, row: GroupRow, selected: Boolean) {
    container.removeAllViews()
    val context = container.context
    val side = context.dp(Dimens.SIDE_PADDING_DP)
    when (row) {
        is GroupRow.AccountHeader -> container.addView(
            TextView(context).apply {
                text = row.accountName.uppercase()
                textSize = 11f
                letterSpacing = 0.11f
                maxLines = 1
                setTextColor(Palette.GRAY_FAINT)
                setPadding(side, 0, side, 0)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        is GroupRow.Entry -> {
            container.addView(
                TextView(context).apply {
                    text = groupEntryLabel(row)
                    textSize = 18f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(side, 0, side, 0)
                    setTextColor(if (selected) Color.WHITE else Palette.GRAY_DIM)
                    paintFlags = if (selected) paintFlags or Paint.UNDERLINE_TEXT_FLAG else paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            groupStatusLabel(row)?.let { status ->
                container.addView(
                    TextView(context).apply {
                        text = status
                        textSize = 13f
                        setTextColor(Palette.GRAY_MUTED)
                        setPadding(0, 0, side, 0)
                    },
                )
            }
        }
    }
}
