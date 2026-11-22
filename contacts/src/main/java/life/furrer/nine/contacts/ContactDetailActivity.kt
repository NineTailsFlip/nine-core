package life.furrer.nine.contacts

import android.content.Intent
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import life.furrer.nine.core.ui.Dimens
import life.furrer.nine.core.ui.NineActivity
import life.furrer.nine.core.ui.dp
import life.furrer.nine.core.ui.HintBar
import life.furrer.nine.core.ui.MenuItem
import life.furrer.nine.core.ui.MenuSheet
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.util.PhoneFormat
import life.furrer.nine.core.util.ProviderWatcher
import life.furrer.nine.core.util.Reloader

private const val PHOTO_SIZE_DP = 68
private const val REQUEST_CALL_PHONE_PERMISSION = 101

/** Read-only contact detail per §9.10 — every row is numbered 1-9 and is a verb: pressing its
 *  digit performs the row's [DataAction] directly (dial/mail/map/open), matching §7.4's "no
 *  search field here, so 1-9 are free". OK performs the currently *selected* row's action (same
 *  verb, reached via Up/Down instead of a digit) — the two are equivalent, not two different
 *  features. Edit/Delete aren't offered yet (M5 owns the editor); `*` only toggles the
 *  favorite, the one real write this read milestone can still offer safely. */
class ContactDetailActivity : NineActivity(), ContactsScreen {

    private var contactId: Long = -1L
    private var detail: ContactDetail? = null
    private var rows: List<ContactDetailRow> = emptyList()
    /** Every selectable row in order — data rows, then group rows. */
    private var items: List<ContactDetailRow> = emptyList()
    private var selectedIndex: Int = 0
    private var pendingDialNumber: String? = null

    private lateinit var photoView: ImageView
    private lateinit var initialsView: TextView
    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var voicemailText: TextView
    private lateinit var rowContainer: LinearLayout
    private lateinit var linkedLine: TextView
    private lateinit var scrollView: android.widget.ScrollView
    private lateinit var hintBar: HintBar
    private lateinit var menuSheet: MenuSheet
    private val rowViews = mutableListOf<LinearLayout>()

    // Parallel to `rowViews`/`items` — the section header view immediately preceding an item's
    // own row, or null when it isn't the first of its kind. Lets applySelectionStyle() scroll a
    // group's header into view together with its first item, instead of pinning the item's own
    // top to y=0 and scrolling the header (which sits just above it) off-screen.
    private val headerBeforeItem = mutableListOf<View?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        contactId = intent.getLongExtra(EXTRA_CONTACT_ID, -1L)
        if (contactId == -1L) {
            finish()
            return
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(Dimens.SIDE_PADDING_DP), dp(10), dp(Dimens.SIDE_PADDING_DP), dp(4))
        }
        header.addView(
            TextView(this).apply {
                text = "‹ contact"
                textSize = 13f
                setTextColor(Palette.GRAY_FAINT)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        val photoSize = dp(PHOTO_SIZE_DP)
        val photoFrame = FrameLayout(this).apply {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
        }
        initialsView = TextView(this).apply {
            textSize = 24f
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
        }
        photoView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        photoFrame.addView(initialsView, FrameLayout.LayoutParams(photoSize, photoSize))
        photoFrame.addView(photoView, FrameLayout.LayoutParams(photoSize, photoSize))

        titleText = TextView(this).apply {
            textSize = 20f
            setTextColor(android.graphics.Color.WHITE)
        }
        subtitleText = TextView(this).apply {
            textSize = 14f
            setTextColor(Palette.GRAY_DIM)
            visibility = View.GONE
        }
        // Telecom turns this contact's calls away before they ring; said up front, since nothing
        // else on the phone would explain the missing calls.
        voicemailText = TextView(this).apply {
            text = "CALLS GO TO VOICEMAIL"
            textSize = 11f
            letterSpacing = 0.06f
            setTextColor(Palette.GRAY_FAINT)
            visibility = View.GONE
        }
        val nameColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(titleText)
            addView(subtitleText)
            addView(voicemailText)
        }

        val identityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(Dimens.SIDE_PADDING_DP), dp(8), dp(Dimens.SIDE_PADDING_DP), dp(8))
            addView(photoFrame, LinearLayout.LayoutParams(photoSize, photoSize))
            addView(nameColumn)
        }

        rowContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        linkedLine = TextView(this).apply {
            textSize = 11f
            letterSpacing = 0.06f
            setTextColor(Palette.GRAY_FAINT)
            setPadding(dp(Dimens.SIDE_PADDING_DP), dp(12), dp(Dimens.SIDE_PADDING_DP), dp(8))
            visibility = View.GONE
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(identityRow)
            addView(rowContainer)
            addView(linkedLine)
        }
        scrollView = android.widget.ScrollView(this).apply {
            addView(content)
            isFocusable = false
            isFocusableInTouchMode = false
        }

        hintBar = HintBar(this).apply { setText("* menu · ok act · call dial") }
        menuSheet = MenuSheet(this)
        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(hintBar)
        }
        val root = FrameLayout(this).apply {
            addView(mainColumn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(menuSheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(root)
    }

    // A host's ContactsActions write (delete, favorite toggle) runs against ContactStore
    // directly, with no callback back into this screen — this is what notices the change and
    // reloads instead. A delete makes [load] find nothing and finish(); a favorite toggle just
    // refreshes with the new starred state.
    private val providerWatcher = ProviderWatcher(this, ContactsContract.AUTHORITY_URI) { load() }

    override fun onResume() {
        super.onResume()
        if (contactId != -1L) {
            load()
            providerWatcher.start()
        }
    }

    override fun onPause() {
        super.onPause()
        providerWatcher.stop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CALL_PHONE_PERMISSION) {
            val number = pendingDialNumber
            pendingDialNumber = null
            if (number != null && checkSelfPermission(android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                placeCall(number)
            }
        }
    }

    override fun reload() = load()

    private class Loaded(
        val detail: ContactDetail?,
        val groups: List<ContactStore.ContactGroup>,
        val favoritesGroupId: Long?,
        val voicemailGroupId: Long?,
    )

    // Coalesced rather than a plain Async.run per trigger: the provider watcher fires on every
    // change to the whole contacts authority, many times over during a CardDAV sync, and every
    // other screen's read would queue behind one detail read each. [contactId] is set once in
    // onCreate, so reading it from the load itself is safe.
    private val detailLoader = Reloader(
        background = {
            val id = contactId
            val detail = ContactStore.queryContactDetail(this, id)
            val groups = if (detail == null) emptyList() else ContactGroups.queryGroupsOfContact(this, id)
            fun chosenGroupId(sync: GroupSync) =
                if (groups.isEmpty()) null else (sync.groupState(this) as? GroupSync.GroupState.Present)?.group?.id
            Loaded(detail, groups, chosenGroupId(FavoritesSync), chosenGroupId(VoicemailSync))
        },
        onResult = { result ->
            val loaded = result.detail
            if (loaded == null) {
                finish()
            } else {
                detail = loaded
                rows = buildDetailRows(resources, loaded, result.groups, result.favoritesGroupId, result.voicemailGroupId)
                items = rows.filter { it.isSelectable() }
                selectedIndex = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
                render(loaded)
                PhotoLoader.load(this, contactId) { bitmap -> if (bitmap != null) showPhoto(bitmap) }
            }
        },
    )

    private fun load() = detailLoader.request()

    private fun render(detail: ContactDetail) {
        titleText.text = detail.displayName
        initialsView.text = initialsOf(detail.displayName)
        initialsView.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Palette.pastelize(pastelSeedColor(detail.displayName)))
        }
        photoView.visibility = View.GONE
        initialsView.visibility = View.VISIBLE

        val org = detail.rows.firstOrNull { DataKind.fromMimetype(it.mimetype) == DataKind.ORGANIZATION }
        val orgText = org?.let { DataFormat.valueText(DataKind.ORGANIZATION, it.values) }?.takeIf { it.isNotBlank() }
        subtitleText.text = orgText
        subtitleText.visibility = if (orgText != null) View.VISIBLE else View.GONE
        voicemailText.visibility = if (detail.sendToVoicemail) View.VISIBLE else View.GONE

        rowContainer.removeAllViews()
        rowViews.clear()
        headerBeforeItem.clear()
        var pendingHeader: View? = null
        rows.forEach { row ->
            when (row) {
                is ContactDetailRow.SectionHeader -> {
                    val headerView = buildSectionHeaderView(row.label)
                    rowContainer.addView(headerView)
                    pendingHeader = headerView
                }
                is ContactDetailRow.Item, is ContactDetailRow.GroupItem -> {
                    val rowView = buildRowView(row)
                    rowViews.add(rowView)
                    headerBeforeItem.add(pendingHeader)
                    pendingHeader = null
                    rowContainer.addView(rowView)
                }
            }
        }
        applySelectionStyle()

        linkedLine.visibility = if (detail.isLinked || detail.hasPendingSync) View.VISIBLE else View.GONE
        if (detail.isLinked) {
            val base = "LINKED · " + detail.rawContacts.mapNotNull { it.accountName }.distinct().joinToString(", ").uppercase()
            linkedLine.text = if (detail.hasPendingSync) "$base · NOT YET SYNCED" else base
        } else if (detail.hasPendingSync) {
            linkedLine.text = "NOT YET SYNCED"
        }
        linkedLine.setTextColor(if (detail.hasPendingSync) Palette.RED else Palette.GRAY_FAINT)
    }

    private fun showPhoto(bitmap: android.graphics.Bitmap) {
        photoView.setImageBitmap(bitmap)
        photoView.visibility = View.VISIBLE
        initialsView.visibility = View.GONE
    }

    // A section header per kind, matching mockups.html's `.sect` exactly (10dp top / 16dp side /
    // 3dp bottom padding, 11sp, letter-spacing .11em) — the same convention already used for the
    // contact list's own letter-group headers (ContactRowRenderer.bindHeader).
    private fun buildSectionHeaderView(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 11f
        letterSpacing = 0.11f
        setTextColor(Palette.GRAY_FAINT)
        setPadding(dp(Dimens.SIDE_PADDING_DP), dp(10), dp(Dimens.SIDE_PADDING_DP), dp(3))
    }

    // Dimensions/type sizes match mockups.html's `.arow` exactly (§9.10's own reference render:
    // idx 12dp/13sp, label 52dp/11sp with letter-spacing, value 15sp, 4dp vertical row padding,
    // 7dp gaps) — every value gets its own numbered row with its own specific type label
    // (mobile/work/email/...), never merged into one multi-line "Phone" row.
    //
    // A group row uses the same three columns — number, a label (★ for the favorites group,
    // "voicemail" for the send-to-voicemail one, else blank), the group's name — so GROUPS reads exactly like PHONE or EMAIL above it.
    private fun buildRowView(row: ContactDetailRow): LinearLayout = LinearLayout(this).apply {
        val (label, value) = when (row) {
            is ContactDetailRow.Item ->
                row.typeLabel to if (row.kind == DataKind.PHONE) PhoneFormat.display(this@ContactDetailActivity, row.value) else row.value
            is ContactDetailRow.GroupItem -> (if (row.favorite) "★" else if (row.voicemail) "voicemail" else "") to row.group.title
            is ContactDetailRow.SectionHeader -> error("headers have their own view")
        }
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(Dimens.SIDE_PADDING_DP), dp(4), dp(Dimens.SIDE_PADDING_DP), dp(4))
        addView(
            TextView(this@ContactDetailActivity).apply {
                text = row.digit?.toString().orEmpty()
                textSize = 13f
                setTextColor(Palette.GRAY_FAINT)
                minWidth = dp(12)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(7) },
        )
        addView(
            TextView(this@ContactDetailActivity).apply {
                text = label
                textSize = 11f
                letterSpacing = 0.06f
                minWidth = dp(52)
                setTextColor(Palette.GRAY_MUTED)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(7) },
        )
        addView(
            TextView(this@ContactDetailActivity).apply {
                text = value
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(Palette.GRAY_DIM)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
    }

    private fun applySelectionStyle() {
        rowViews.forEachIndexed { index, rowView ->
            val selected = index == selectedIndex
            for (i in 0 until rowView.childCount) {
                val child = rowView.getChildAt(i) as TextView
                if (i == rowView.childCount - 1) {
                    child.setTextColor(if (selected) android.graphics.Color.WHITE else Palette.GRAY_DIM)
                    child.paintFlags = if (selected) {
                        child.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                    } else {
                        child.paintFlags and android.graphics.Paint.UNDERLINE_TEXT_FLAG.inv()
                    }
                }
            }
        }
        // Unlike RowListView's own recycling window, this screen's rows are plain views in a
        // ScrollView (deliberately — the row count here is small, per §5.2's "detail screens...
        // stay plain LinearLayout in a ScrollView"), so nothing scrolls to follow the selection
        // unless asked explicitly — a contact with more fields than fit on screen would otherwise
        // let the selection move silently off-screen.
        val scrollTarget = headerBeforeItem.getOrNull(selectedIndex) ?: rowViews.getOrNull(selectedIndex)
        // The last row scrolls all the way to the bottom rather than just to its own top, so
        // trailing content below the row list — the LINKED/sync-status line — is never left
        // clipped below the viewport on a contact with enough fields to fill the screen.
        if (selectedIndex == rowViews.lastIndex) {
            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        } else {
            scrollTarget?.let { view -> scrollView.post { scrollView.smoothScrollTo(0, view.top) } }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (menuSheet.handleKeyDown(keyCode, event)) return true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                moveSelection(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                moveSelection(1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                items.getOrNull(selectedIndex)?.let { performAction(it) }
                return true
            }
            KeyEvent.KEYCODE_STAR -> {
                openMenu()
                return true
            }
            KeyEvent.KEYCODE_CALL -> {
                dialPrimaryNumber()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                finish()
                return true
            }
        }
        if (keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
            val n = keyCode - KeyEvent.KEYCODE_0
            items.firstOrNull { it.digit == n }?.let { row ->
                selectedIndex = items.indexOf(row)
                applySelectionStyle()
                performAction(row)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun moveSelection(direction: Int) {
        if (items.isEmpty()) return
        selectedIndex = (selectedIndex + direction + items.size) % items.size
        applySelectionStyle()
    }

    private fun performAction(row: ContactDetailRow) {
        if (row is ContactDetailRow.GroupItem) {
            startActivity(Intent(this, ContactsActivity::class.java).putExtra(ContactsActivity.EXTRA_GROUP_ID, row.group.id))
            return
        }
        if (row !is ContactDetailRow.Item) return
        val kind = row.kind ?: return
        when (kind.action()) {
            // A phone number is the one row with more than one obvious thing to do to it, so OK
            // asks instead of assuming. Calling someone and texting them are not near-misses of
            // each other — an accidental call is a conversation you didn't want to have — and on
            // a phone with no touchscreen there is no "long press for options" to fall back on.
            //
            // The call key still dials in one press (see dialPrimaryNumber), so nothing about
            // reaching someone quickly got slower; what changed is that the *ambiguous* key stopped
            // guessing.
            DataAction.DIAL -> chooseNumberAction(row.value)
            DataAction.EMAIL -> startActivitySafely(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${row.value}")))
            DataAction.MAP -> startActivitySafely(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(row.value)}")))
            DataAction.WEB -> {
                val url = if (row.value.contains("://")) row.value else "http://${row.value}"
                startActivitySafely(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            DataAction.NONE -> {}
        }
    }

    private fun startActivitySafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "Nothing can handle that", Toast.LENGTH_SHORT).show()
        }
    }

    /** The chooser OK opens on a phone row. Same shape as the one M9's compose screen shows when
     *  a picked contact has several numbers — a MenuSheet of plainly-labelled choices — so
     *  "something about this number" always looks the same across the suite.
     *
     *  "Message" is a platform ACTION_SENDTO hand-off, exactly like P9's "Add to contacts": it
     *  lands in whatever messaging app the phone has, and hides itself when nothing resolves. This
     *  module never names M9 — that would be the runtime coupling the core plan forbids — so on a
     *  phone with no SMS app at all, OK on a number simply offers Call. */
    private fun chooseNumberAction(number: String) {
        val items = mutableListOf(MenuItem("Call ${PhoneFormat.display(this, number)}") { placeCallOrRequestPermission(number) })
        val messageIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number"))
        if (messageIntent.resolveActivity(packageManager) != null) {
            items += MenuItem("Message ${PhoneFormat.display(this, number)}") { startActivitySafely(messageIntent) }
        }
        // The menu is shown even when Call is its only entry. A one-item menu looks like
        // ceremony, but the alternative is a code path where a mis-detected messaging app turns
        // OK back into an immediate call — which is exactly what happened on-device before the
        // <queries> entry above was added, and exactly what this chooser exists to prevent.
        // Confirming a call is cheap; an unintended one is a phone ringing in someone's pocket.
        menuSheet.show(items)
    }

    /** The call key always dials the contact's primary number regardless of which row is
     *  selected (§7.4's blanket rule for the call key, not conditioned on row selection). */
    private fun dialPrimaryNumber() {
        val phoneRows = items.filterIsInstance<ContactDetailRow.Item>().filter { it.kind == DataKind.PHONE }
        if (phoneRows.isEmpty()) return
        val selectedPhone = (items.getOrNull(selectedIndex) as? ContactDetailRow.Item)?.takeIf { it.kind == DataKind.PHONE }
        val target = phoneRows.firstOrNull { it.value == selectedPhone?.value }
            ?: phoneRows.first()
        placeCallOrRequestPermission(target.value)
    }

    private fun placeCallOrRequestPermission(number: String) {
        if (checkSelfPermission(android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            placeCall(number)
        } else {
            pendingDialNumber = number
            requestPermissions(arrayOf(android.Manifest.permission.CALL_PHONE), REQUEST_CALL_PHONE_PERMISSION)
        }
    }

    private fun placeCall(number: String) {
        startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")))
    }

    private fun openMenu() {
        val current = detail ?: return
        val items = (ContactsHost.actions?.detailMenuItems(this, current) ?: emptyList()).toMutableList()
        // No "Message" item here: OK on a phone row already offers Call/Message (see
        // chooseNumberAction), and two routes to the same action on the same row is one too many.
        menuSheet.show(items)
    }

    /** Lets a host's [ContactsActions] menu items (e.g. C9's Delete) show a follow-up confirm
     *  menu on this same sheet, without this screen needing to know what those items are. */
    override fun showMenu(items: List<MenuItem>) = menuSheet.show(items)

    private fun initialsOf(displayName: String): String {
        val words = displayName.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return words.take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("")
    }

    private fun pastelSeedColor(displayName: String): Int {
        val hue = (displayName.hashCode() and 0xFFFFFF) % 360
        val (r, g, b) = Palette.hslToRgb(hue.toDouble(), 0.6, 0.5)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    companion object {
        const val EXTRA_CONTACT_ID = "contact_id"
    }
}
