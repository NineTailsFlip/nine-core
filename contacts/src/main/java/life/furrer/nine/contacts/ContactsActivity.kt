package life.furrer.nine.contacts

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import life.furrer.nine.core.ui.NineActivity
import life.furrer.nine.core.ui.dp
import life.furrer.nine.core.ui.relaunch
import life.furrer.nine.core.ui.HintBar
import life.furrer.nine.core.ui.LongPressKey
import life.furrer.nine.core.ui.MenuItem
import life.furrer.nine.core.ui.MenuSheet
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.PermissionScreen
import life.furrer.nine.core.ui.RowBinder
import life.furrer.nine.core.ui.RowListView
import life.furrer.nine.core.ui.SearchField
import life.furrer.nine.core.util.ProviderWatcher
import life.furrer.nine.core.util.Reloader
import life.furrer.nine.core.util.StringMatch
import life.furrer.nine.core.util.PhoneFormat

private const val REQUEST_CONTACTS_PERMISSION = 100
private const val REQUEST_CALL_PHONE_PERMISSION = 101

/** Reserved per §5.4/§7.4: Up/Down/OK are always this screen's own list navigation, never the
 *  search field's — a single-line search box has no use for them. C9's own deliberate deviation
 *  from L9's set additionally reserves Left/Right (letter-group jump), `*` (menu), `#`
 *  (favorites toggle), and the call key, so none of them ever leak into the T9 field either. */
private val RESERVED_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_STAR, KeyEvent.KEYCODE_POUND,
    KeyEvent.KEYCODE_CALL,
)

/** The contacts list per §9.8/§9.9/§7.4 — browse/search, favorites, letter groups and the call
 *  key are read-only and always available; New/Edit/Delete/Accounts/Sync only appear when
 *  the host app registers a [ContactsActions] with [ContactsHost] (P9 doesn't, so its `*` menu
 *  offers only "Groups" and "Settings").
 *
 *  Started with [EXTRA_GROUP_ID] it is instead one group's member view, opened from
 *  [GroupsActivity]: the same list, search, `#` favorites and call key, limited to that group's
 *  members and titled with its name. The "Show in contacts list" and Accounts filters don't apply
 *  there — opening a group asks for exactly its members — and the `*` menu carries
 *  [ContactsActions.groupMembersMenuItems] instead of [ContactsActions.listMenuItems]. */
class ContactsActivity : NineActivity(), ContactsScreen {

    private var allContacts: List<ContactEntry> = emptyList()
    private var rows: List<ContactRow> = emptyList()
    private var favoritesOnly = false

    /** Set in the member view: the group's id from the intent, and the group itself (current
     *  title) once loaded. Null [group] after a load means the group is gone and the screen closes. */
    private var groupId: Long? = null
    private var group: ContactGroup? = null
    private var pendingDialNumber: String? = null

    // `*`'s short-tap-vs-hold distinction (§7.2: hold `*` → Settings) is normally the framework's
    // own job via onKeyLongPress — but that relies on the key actually reaching onKeyDown via
    // the default dispatch, which this screen's dispatchKeyEvent override bypasses entirely for
    // reserved keys (see below). Tracked by `:core`'s [LongPressKey] instead — see its own doc
    // comment for the "start a delayed callback on DOWN, cancel and check a flag on UP" shape,
    // and for why this screen found it first.
    private val starKey = LongPressKey(::openMenu) { openNineSettings(Nine.app.contactsSurfaceId) }

    // Confirmed on-device (§12): once the search EditText has real IME focus, this device's
    // hardware T9 IME swallows KEYCODE_STAR for its own case-cycling before it ever reaches
    // dispatchKeyEvent — tap-`*` silently does nothing while actively searching (query non-empty).
    // §5.4 used to document a hold-OK fallback for this; removed after confirming on-device that
    // a genuinely long-held OK is itself claimed at the system level on this hardware for the
    // physical call/send button (it fires the CALL_BUTTON intent — opening P9's dialer instead of
    // this screen's menu — before dispatchKeyEvent here ever sees it, regardless of which app is
    // in the foreground). [SearchField.onDeactivated] below is the only recovery now: once the
    // query goes back to empty, [restartFresh] relaunches the screen, which restores `*`/`#` —
    // see [relaunch].

    private lateinit var rowListView: RowListView
    private lateinit var searchField: SearchField
    private lateinit var countLabel: TextView
    private lateinit var titleLine: TextView
    private lateinit var hintBar: HintBar
    private lateinit var menuSheet: MenuSheet
    private lateinit var permissionScreen: PermissionScreen
    private val providerWatcher = ProviderWatcher(this, ContactsContract.AUTHORITY_URI) { loadContacts() }

    private val binder = object : RowBinder {
        override fun rowCount(): Int = rows.size
        override fun createRowView(context: Context): View = createContactRowContainer(context)
        override fun bind(view: View, index: Int, selected: Boolean) {
            bindContactRow(view as LinearLayout, rows[index], selected)
        }
        override fun rowHeight(index: Int): Int = contactRowHeightDp(rows[index])
        override fun isSelectable(index: Int): Boolean = rows[index].isSelectable()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        favoritesOnly = intent.getBooleanExtra(EXTRA_FAVORITES_ONLY, false)
        groupId = intent.getLongExtra(EXTRA_GROUP_ID, -1L).takeIf { it != -1L }

        searchField = SearchField(this).apply {
            onQueryChanged = { rebuildRows() }
            onDeactivated = { restartFresh() }
            editText.apply {
                hint = "Type to search"
                textSize = 16f
                setTextColor(android.graphics.Color.WHITE)
                setHintTextColor(Palette.GRAY_FAINT)
                setPadding(dp(Dimens.SIDE_PADDING_DP), dp(10), dp(Dimens.SIDE_PADDING_DP), dp(6))
            }
        }

        rowListView = RowListView(this).apply { setBinder(binder) }
        countLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(Palette.GRAY_MUTED)
            setPadding(dp(Dimens.SIDE_PADDING_DP), dp(4), dp(Dimens.SIDE_PADDING_DP), dp(4))
            visibility = View.GONE
        }
        titleLine = TextView(this).apply {
            textSize = 13f
            setTextColor(Palette.GRAY_FAINT)
            maxLines = 1
            setPadding(dp(Dimens.SIDE_PADDING_DP), dp(10), dp(Dimens.SIDE_PADDING_DP), 0)
            visibility = if (groupId != null) View.VISIBLE else View.GONE
        }
        hintBar = HintBar(this)
        menuSheet = MenuSheet(this)
        permissionScreen = PermissionScreen(this).apply {
            onGrant = { requestContactsPermission() }
            onLeave = { finish() }
        }

        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleLine)
            addView(searchField.editText)
            addView(rowListView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(countLabel)
            addView(hintBar)
        }
        val root = FrameLayout(this).apply {
            addView(mainColumn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(menuSheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(permissionScreen, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        setContentView(root)
        updateHint()

        if (hasNeededPermissions()) {
            loadContacts()
        } else {
            // §9.13's "first run" screen — shown here rather than requesting permission
            // straight away, and shown again from onRequestPermissionsResult on denial, so the
            // same screen covers both "explain before the very first ask" and "explain again and
            // offer to retry after a denial" without a separate persisted flag.
            permissionScreen.show(permissionExplainer())
        }
    }

    // A host with no ContactsActions never writes the address book, so it never needs
    // WRITE_CONTACTS — asking for it anyway would be a permission this app can't act on.
    private fun hasNeededPermissions(): Boolean =
        ContactStore.hasPermission(this) && (ContactsHost.actions == null || ContactStore.hasWritePermission(this))

    private fun permissionExplainer(): String {
        val action = if (ContactsHost.actions != null) "show, search, and edit" else "show and search"
        return "Contacts needs access to your Contacts to $action your address book.\n\n" +
            "There is no network permission — nothing here ever talks to a server."
    }

    // Both requested together when a host writes, not just READ — confirmed on-device (same gap
    // found and fixed for WRITE_CALENDAR during C9's own M3) that WRITE_CONTACTS is not
    // auto-granted alongside READ_CONTACTS, and New/Edit/Delete need it from the first save
    // attempt.
    private fun requestContactsPermission() {
        val permissions = if (ContactsHost.actions != null) {
            arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        } else {
            arrayOf(Manifest.permission.READ_CONTACTS)
        }
        requestPermissions(permissions, REQUEST_CONTACTS_PERMISSION)
    }

    override fun onResume() {
        super.onResume()
        if (hasNeededPermissions()) {
            permissionScreen.hide()
            providerWatcher.start()
            // Picks up a changed "Show in contacts list"/Accounts filter (§7.4) on return from the
            // groups or accounts screen — those are local prefs, not a provider write, so the
            // ContentObserver never fires.
            loadContacts()
        } else if (!permissionScreen.isShowing) {
            // Covers permission revoked via system Settings while this Activity was backgrounded
            // — onCreate's own explainer only runs once, so this is the other path back to it.
            permissionScreen.show(permissionExplainer())
        }
    }

    override fun onPause() {
        super.onPause()
        providerWatcher.stop()
        searchField.onPause()
        starKey.reset()
    }

    /** [SearchField.onDeactivated]'s escape from the captured `*`/`#` — see [relaunch].
     *  `favoritesOnly` and the member view's group are the only state worth keeping (everything
     *  else is either cheap to reload from the provider or, like the search query itself, already
     *  empty at the point this fires — and dropping the group would silently turn a group's member
     *  view into the full list). */
    private fun restartFresh() {
        relaunch(
            Intent(this, ContactsActivity::class.java)
                .putExtra(EXTRA_FAVORITES_ONLY, favoritesOnly)
                .apply { groupId?.let { putExtra(EXTRA_GROUP_ID, it) } },
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) searchField.onWindowFocusGained()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CONTACTS_PERMISSION -> {
                if (hasNeededPermissions()) {
                    permissionScreen.hide()
                    loadContacts()
                } else {
                    permissionScreen.show(permissionExplainer())
                }
            }
            REQUEST_CALL_PHONE_PERMISSION -> {
                val number = pendingDialNumber
                pendingDialNumber = null
                if (number != null && checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                    placeCall(number)
                }
            }
        }
    }

    private class Loaded(val contacts: List<ContactEntry>, val group: ContactGroup?)

    // Coalesced rather than a plain Async.run per trigger: onResume, the provider watcher and a
    // host's reload() all land here, and a CardDAV sync fires the watcher many times over.
    // [groupId] is set once in onCreate, so reading it from the load itself is safe.
    private val contactsLoader = Reloader(
        background = {
            val id = groupId
            if (id != null) {
                val group = ContactGroups.queryGroup(this, id)
                val members = if (group == null) emptySet() else ContactStore.queryContactIdsInGroups(this, setOf(id))
                Loaded(ContactStore.queryContacts(this).filter { it.id in members }, group)
            } else {
                Loaded(filteredContacts(), null)
            }
        },
        onResult = { result ->
            if (groupId != null && result.group == null) {
                // Deleted (here, by the host's own Delete group, or on the server) — there is
                // nothing left for this screen to be a view of.
                finish()
            } else {
                group = result.group
                result.group?.let { titleLine.text = backTitle(it.title) }
                allContacts = result.contacts
                rebuildRows()
            }
        },
    )

    private fun loadContacts() = contactsLoader.request()

    /** The full list as this app's filters leave it: Accounts (§7.4, "null means everyone") and
     *  the groups screen's "Show in contacts list" (see [ContactStore.isShownByGroups]) — both
     *  local preferences, so a fresh install filters nothing. The group filter's two extra
     *  queries only run once a group has actually been hidden. */
    private fun filteredContacts(): List<ContactEntry> {
        val contacts = ContactStore.queryContacts(this)
        val accounts = ContactStore.getSelectedAccounts(this)
        val allowedByAccount = accounts?.let { ContactStore.queryContactIdsInAccounts(this, it) }
        val hidden = ContactStore.hiddenGroupIds(this)
        val contactGroupIds = if (hidden.isEmpty()) emptyMap() else ContactGroups.queryContactGroupIds(this)
        val liveGroupIds = if (hidden.isEmpty()) emptySet() else ContactStore.queryGroups(this).map { it.id }.toSet()
        return contacts.filter { contact ->
            (allowedByAccount == null || contact.id in allowedByAccount) &&
                (hidden.isEmpty() || ContactStore.isShownByGroups(contactGroupIds[contact.id].orEmpty(), hidden, liveGroupIds))
        }
    }

    override fun reload() = loadContacts()

    private fun rebuildRows() {
        rows = buildContactRows(allContacts, searchField.query, favoritesOnly)
        rowListView.notifyDataChanged()
        val query = searchField.query
        countLabel.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
        if (query.isNotEmpty()) {
            val matchCount = rows.count { it.isSelectable() }
            countLabel.text = "$matchCount of ${allContacts.size} contacts"
        }
        updateHint()
    }

    private fun updateHint() {
        hintBar.setText(
            if (searchField.query.isEmpty()) {
                val toggleLabel = if (favoritesOnly) "# all" else "# favorites"
                "* menu · $toggleLabel · ← → letter"
            } else {
                "call dial · ‹ clear · ok open"
            },
        )
    }

    /** See [SearchField]'s own doc comment for the pattern this follows. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (permissionScreen.interceptKeyEvent(event)) return true
        if (menuSheet.interceptKeyEvent(event)) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (searchField.handleBack(event) { super.dispatchKeyEvent(it) }) return true
            onBackNotConsumedByIme()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_STAR) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> starKey.onDown(event.repeatCount)
                KeyEvent.ACTION_UP -> starKey.onUp()
            }
            return true
        }
        if (searchField.handleKeyEvent(event, RESERVED_KEYS)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in RESERVED_KEYS) {
                handleReservedKeyDown(event.keyCode)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Back clears the search; on an already-empty search it leaves (§7.4) — `0` can't mean
     *  "clear" here since digits belong to the live search field. */
    private fun onBackNotConsumedByIme() {
        if (searchField.query.isEmpty()) {
            finish()
        } else {
            searchField.clear()
        }
    }

    // KEYCODE_STAR is handled directly in dispatchKeyEvent instead (it needs its own DOWN/UP-
    // tracked long-press timer, see [starKey]) rather than through this generic ACTION_DOWN-only
    // dispatch. DPAD_CENTER/ENTER don't — see the "Confirmed on-device (§12)" comment above
    // [starKey] for why OK no longer gets one either (the hold-OK fallback it once had).
    private fun handleReservedKeyDown(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> rowListView.moveSelection(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> rowListView.moveSelection(1)
            KeyEvent.KEYCODE_DPAD_LEFT -> jumpLetterGroup(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> jumpLetterGroup(1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> openDetail()
            KeyEvent.KEYCODE_POUND -> toggleFavoritesOnly()
            KeyEvent.KEYCODE_CALL -> dial()
        }
    }

    /** Jumps to the previous/next letter-group header regardless of where within the current
     *  group the selection currently sits (§7.4) — a fast scrub, not a same-group no-op. */
    private fun jumpLetterGroup(direction: Int) {
        val next = letterGroupJumpIndex(rows, rowListView.selectedIndex, direction) ?: return
        rowListView.setSelectedIndex(next)
    }

    private fun toggleFavoritesOnly() {
        favoritesOnly = !favoritesOnly
        rebuildRows()
    }

    private fun openDetail() {
        val contact = rows.getOrNull(rowListView.selectedIndex)?.contactOrNull() ?: return
        startActivity(Intent(this, ContactDetailActivity::class.java).apply {
            putExtra(ContactDetailActivity.EXTRA_CONTACT_ID, contact.id)
        })
    }

    private fun openMenu() {
        val selected = rows.getOrNull(rowListView.selectedIndex)?.contactOrNull()
        val actions = ContactsHost.actions
        val items = mutableListOf<MenuItem>()
        if (groupId != null) {
            // Not before the group has loaded: the host's items need the group itself.
            val group = this.group ?: return
            actions?.let { items.addAll(it.groupMembersMenuItems(this, group, selected)) }
        } else {
            actions?.let { items.addAll(it.listMenuItems(this, selected)) }
            // Every host, read-only ones included: browsing groups (and choosing which show up
            // in this list) needs nothing beyond READ_CONTACTS.
            items.add(MenuItem("Groups") { startActivity(Intent(this, GroupsActivity::class.java)) })
            actions?.let { items.addAll(it.listMenuTrailingItems(this)) }
        }
        items.add(MenuItem("Settings") { openNineSettings(Nine.app.contactsSurfaceId) })
        menuSheet.show(items)
    }

    /** Lets a host's [ContactsActions] menu items (e.g. C9's Delete) show a follow-up confirm
     *  menu on this same sheet, without this screen needing to know what those items are. */
    override fun showMenu(items: List<MenuItem>) = menuSheet.show(items)

    /** The call key's target always follows §7.4: the selected contact's primary number (asking
     *  first if more than one and none is marked primary — "never guess"), or, when nothing at
     *  all is selected/matched, the typed digits verbatim if they're dial-able — so the list
     *  doubles as a plain dialer for numbers not in any contact's address book. */
    private fun dial() {
        when (val selected = rows.getOrNull(rowListView.selectedIndex)) {
            is ContactRow.Entry -> dialContact(selected.contact)
            is ContactRow.NumberMatch -> placeCallOrRequestPermission(selected.matchedNumber)
            else -> {
                val query = searchField.query
                if (StringMatch.looksDialable(query)) placeCallOrRequestPermission(query)
            }
        }
    }

    private fun dialContact(contact: ContactEntry) {
        val numbers = contact.phoneNumbers
        when {
            numbers.isEmpty() -> {}
            numbers.size == 1 -> placeCallOrRequestPermission(numbers.first().number)
            else -> {
                val primary = numbers.firstOrNull { it.isSuperPrimary }
                if (primary != null) {
                    placeCallOrRequestPermission(primary.number)
                } else {
                    menuSheet.show(
                        numbers.map { n -> MenuItem("${n.label ?: "Number"}: ${PhoneFormat.display(this, n.number)}") { placeCallOrRequestPermission(n.number) } },
                    )
                }
            }
        }
    }

    private fun placeCallOrRequestPermission(number: String) {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            placeCall(number)
        } else {
            pendingDialNumber = number
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), REQUEST_CALL_PHONE_PERMISSION)
        }
    }

    private fun placeCall(number: String) {
        startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")))
    }

    companion object {
        private const val EXTRA_FAVORITES_ONLY = "favorites_only"

        /** Opens this screen as the member view of the group with this id (a Long). */
        const val EXTRA_GROUP_ID = "group_id"
    }
}
