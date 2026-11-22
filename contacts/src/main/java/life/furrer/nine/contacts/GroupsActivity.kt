package life.furrer.nine.contacts

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import life.furrer.nine.contacts.ContactStore.ContactGroup
import life.furrer.nine.core.Nine
import life.furrer.nine.core.settings.openNineSettings
import life.furrer.nine.core.ui.Dimens
import life.furrer.nine.core.ui.HintBar
import life.furrer.nine.core.ui.LongPressKey
import life.furrer.nine.core.ui.MenuItem
import life.furrer.nine.core.ui.MenuSheet
import life.furrer.nine.core.ui.NineActivity
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.RowBinder
import life.furrer.nine.core.ui.RowListView
import life.furrer.nine.core.ui.dp
import life.furrer.nine.core.util.ProviderWatcher
import life.furrer.nine.core.util.Reloader

/** The groups list, reached from the contacts list's `*` → Groups in every host. Groups are
 *  labels here: OK opens the group as a filtered contacts list (`ContactsActivity` with
 *  [ContactsActivity.EXTRA_GROUP_ID]), and the `*` menu carries the host's group management
 *  ([ContactsActions.groupsListMenuItems] — none in P9/M9) followed by this screen's own
 *  "Show in contacts list" toggle, which is a per-app preference rather than a contacts write and
 *  so works in a read-only host too.
 *
 *  No search field: a phone has a handful of groups, not thousands of contacts, so none of
 *  `ContactsActivity`'s IME/Autofill workarounds apply, and keys go through plain onKeyDown. */
class GroupsActivity : NineActivity(), ContactsScreen {

    private var rows: List<GroupRow> = emptyList()
    private var loaded = false

    private lateinit var rowListView: RowListView
    private lateinit var emptyText: TextView
    private lateinit var hintBar: HintBar
    private lateinit var menuSheet: MenuSheet
    // The whole authority, like the other shared screens: a group created/renamed/deleted by the
    // host (or by DAVx5 syncing) notifies under .../groups, a membership change under .../data —
    // both change what this list shows.
    private val providerWatcher = ProviderWatcher(this, ContactsContract.AUTHORITY_URI) { reloader.request() }

    private val starKey = LongPressKey(::openMenu) { openNineSettings(Nine.app.contactsSurfaceId) }

    private class Loaded(val groups: List<ContactGroup>, val counts: Map<Long, Int>, val favoritesGroupId: Long?, val hidden: Set<Long>)

    private val reloader = Reloader(
        background = {
            Loaded(
                groups = ContactStore.queryGroups(this),
                counts = ContactGroups.queryMemberCounts(this),
                favoritesGroupId = (FavoritesSync.groupState(this) as? GroupSync.GroupState.Present)?.group?.id,
                hidden = ContactStore.hiddenGroupIds(this),
            )
        },
        onResult = { show(it) },
    )

    private val binder = object : RowBinder {
        override fun rowCount(): Int = rows.size
        override fun createRowView(context: Context): View = createGroupRowContainer(context)
        override fun bind(view: View, index: Int, selected: Boolean) = bindGroupRow(view as LinearLayout, rows[index], selected)
        override fun rowHeight(index: Int): Int = groupRowHeightDp(rows[index])
        override fun isSelectable(index: Int): Boolean = rows[index].isSelectable()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val side = dp(Dimens.SIDE_PADDING_DP)
        val title = TextView(this).apply {
            text = backTitle("groups")
            textSize = 13f
            setTextColor(Palette.GRAY_FAINT)
            setPadding(side, dp(10), side, dp(6))
        }
        rowListView = RowListView(this).apply { setBinder(binder) }
        emptyText = TextView(this).apply {
            textSize = 16f
            setTextColor(Palette.GRAY_DIM)
            setPadding(side, dp(30), side, dp(30))
            visibility = View.GONE
        }
        hintBar = HintBar(this).apply { setText("* menu · ok open · ‹ back") }
        menuSheet = MenuSheet(this)

        val listFrame = FrameLayout(this).apply {
            addView(rowListView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(emptyText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(listFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(hintBar)
        }
        setContentView(
            FrameLayout(this).apply {
                addView(mainColumn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(menuSheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            },
        )
    }

    override fun onResume() {
        super.onResume()
        hintBar.setText("* menu · ok open · ‹ back")
        providerWatcher.start()
        reloader.request()
    }

    override fun onPause() {
        super.onPause()
        providerWatcher.stop()
        starKey.reset()
    }

    override fun reload() = reloader.request()

    /** Keeps the highlight on the same group across a reload (a rename re-sorts the list, a
     *  delete removes a row above it) rather than on the same row index. */
    private fun show(result: Loaded) {
        val previous = selectedGroup()?.id
        rows = buildGroupRows(result.groups, result.counts, result.favoritesGroupId, result.hidden)
        loaded = true
        rowListView.notifyDataChanged()
        val index = rows.indexOfFirst { it is GroupRow.Entry && it.group.id == previous }
        if (index >= 0) rowListView.setSelectedIndex(index)
        emptyText.text = if (ContactStore.hasPermission(this)) "No groups yet" else "No access to contacts"
        emptyText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun selectedGroup(): ContactGroup? = (rows.getOrNull(rowListView.selectedIndex) as? GroupRow.Entry)?.group

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (menuSheet.handleKeyDown(keyCode, event)) return true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> rowListView.moveSelection(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> rowListView.moveSelection(1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> openGroup()
            KeyEvent.KEYCODE_STAR -> starKey.onDown(event.repeatCount)
            KeyEvent.KEYCODE_BACK -> finish()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    // The UP that follows a `*` press the open menu swallowed finds starKey without a DOWN, so it
    // fires nothing — no extra bookkeeping needed for the menu-consumed key like ContactsActivity's.
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_STAR) {
            starKey.onUp()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun openGroup() {
        val group = selectedGroup() ?: return
        startActivity(Intent(this, ContactsActivity::class.java).putExtra(ContactsActivity.EXTRA_GROUP_ID, group.id))
    }

    private fun openMenu() {
        if (!loaded) return
        val selected = rows.getOrNull(rowListView.selectedIndex) as? GroupRow.Entry
        val items = (ContactsHost.actions?.groupsListMenuItems(this, selected?.group) ?: emptyList()).toMutableList()
        if (selected != null) {
            val group = selected.group
            items.add(
                MenuItem("Show in contacts list: ${if (selected.shown) "yes" else "no"}") {
                    ContactStore.setGroupShown(this, group.id, !selected.shown)
                    reloader.request()
                },
            )
        }
        items.add(MenuItem("Settings") { openNineSettings(Nine.app.contactsSurfaceId) })
        menuSheet.show(items)
    }

    override fun showMenu(items: List<MenuItem>) = menuSheet.show(items)
}
