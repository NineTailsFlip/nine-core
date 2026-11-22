package life.furrer.nine.contacts

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.ContactsContract
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import life.furrer.nine.core.ui.Dimens
import life.furrer.nine.core.ui.HintBar
import life.furrer.nine.core.ui.NineActivity
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.RowBinder
import life.furrer.nine.core.ui.RowListView
import life.furrer.nine.core.ui.SearchField
import life.furrer.nine.core.ui.applyRowSelectionStyle
import life.furrer.nine.core.ui.dp
import life.furrer.nine.core.util.Async
import life.furrer.nine.core.util.StringMatch

private const val REQUEST_CONTACTS_PERMISSION = 300
private val RESERVED_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
)

/** A typed query counts as "add this address directly" once it has an `@` and something that
 *  looks like a domain after it — deliberately loose (no real RFC 5322 validation) since the
 *  provider/sync adapter is the real authority on whether an address is well-formed, the same way
 *  [StringMatch.looksDialable] leaves real validation to the dialer. [DataKind.EMAIL]'s half of
 *  the typed-pseudo-row predicate; [DataKind.PHONE]'s half is [StringMatch.looksDialable] itself,
 *  reused as-is rather than reimplemented here. */
private fun looksLikeEmail(query: String): Boolean {
    val at = query.indexOf('@')
    if (at <= 0 || at == query.length - 1) return false
    return query.substring(at + 1).contains('.')
}

/** One resolved value ready to hand back to the caller once a contact is picked — a phone number
 *  with its "mobile"/"home" type label, or an email address with its own type label, depending on
 *  [ContactValuePickerActivity.kind]. [label] follows [ContactPhoneNumber.label]/
 *  [DataFormat.typeLabel]'s own "null means no meaningful type" convention rather than inventing a
 *  placeholder string here — [returnMultipleResult] is the one place that turns a missing label
 *  into the empty string [EXTRA_LABELS] actually carries. */
private data class PickedValue(val value: String, val label: String?)

/**
 * Picks one of a contact's values for [kind] — an email address (RFC 5545 ATTENDEE, C9's "Add
 * attendee") or a phone number (M9's recipient field) — from the contacts list (by name) or typed
 * directly, per both features' own "choose from contacts, or provide a value directly" requirement.
 * Originally C9-only (as `AttendeePickerActivity`, EMAIL-only); generalized here so a second app
 * doesn't have to re-implement the identical screen for PHONE — see docs/m9-plan.md's "Prerequisite
 * suite work" §1 for the full rationale. [kind] is required (see [EXTRA_KIND]): there is no sane
 * default between "pick an email" and "pick a number", so a caller that forgets it crashes
 * immediately rather than silently defaulting to the wrong kind.
 *
 * Reuses the contacts module's own [ContactRow]/[buildContactRows]/[bindContactRow] rather than a
 * bespoke flat list, so this looks and drives exactly like the real contacts browse screen —
 * letter-grouped, Left/Right letter-jump, underline-on-selected — with one extra pseudo-row
 * (typed value) prepended when the query itself looks like a usable [kind] value, the same shape
 * L9's AppPickerActivity already gives its own picker.
 *
 * A contact with more than one value for [kind] doesn't get a chooser menu *here* — confirmed
 * on-device (originally for the email case; equally true of phone numbers, since the quirk lives
 * in the IME, not in what's being typed) that showing one while [searchField]'s EditText still
 * holds a real, non-empty-query IME connection lets this hardware's T9 IME keep composing into
 * that field independently of the menu (the same "processes keys independently of Activity
 * dispatch" quirk [SearchField]'s own doc describes elsewhere, just a different symptom: composing
 * garbage into the field instead of merely swallowing a key). Returning every candidate via
 * [EXTRA_VALUES]/[EXTRA_LABELS] and letting the caller — which has no search field of its own to
 * fight with — show that chooser instead sidesteps the whole quirk rather than working around it
 * here. A contact with no value for [kind] can't be picked at all (there's nothing to return).
 *
 * With [EXTRA_PICK_CONTACT] instead of [EXTRA_KIND] it picks a whole contact rather than one of
 * its values — every contact is pickable, there's no typed pseudo-row, and the result is
 * [EXTRA_CONTACT_ID] plus [EXTRA_NAME], with the contact's lookup Uri as the result data. C9's
 * editor uses this for "add this number to an existing contact", where the contact may well have
 * no phone number yet; C9's manifest also routes the platform's `ACTION_PICK` here (same mode).
 */
class ContactValuePickerActivity : NineActivity() {

    /** Null in whole-contact mode ([EXTRA_PICK_CONTACT]). */
    private var kind: DataKind? = null

    private var contacts: List<ContactEntry> = emptyList()
    private var contactRows: List<ContactRow> = emptyList()
    /** The typed-query pseudo-row's text, or null when the query doesn't look like a usable
     *  [kind] value — occupies row 0 ahead of [contactRows] whenever non-null. */
    private var typedValueQuery: String? = null

    private lateinit var searchField: SearchField
    private lateinit var rowListView: RowListView
    private lateinit var hintBar: HintBar

    private val rowOffset: Int get() = if (typedValueQuery != null) 1 else 0

    private val binder = object : RowBinder {
        override fun rowCount(): Int = rowOffset + contactRows.size
        override fun createRowView(context: Context): View = createContactRowContainer(context)
        override fun bind(view: View, index: Int, selected: Boolean) {
            val typed = typedValueQuery
            if (typed != null && index == 0) {
                bindTypedValueRow(view as LinearLayout, typed, selected)
            } else {
                bindContactRow(view as LinearLayout, contactRows[index - rowOffset], selected)
            }
        }
        override fun rowHeight(index: Int): Int {
            val typed = typedValueQuery
            return if (typed != null && index == 0) CONTACT_ROW_HEIGHT_DP else contactRowHeightDp(contactRows[index - rowOffset])
        }
        override fun isSelectable(index: Int): Boolean {
            val typed = typedValueQuery
            return (typed != null && index == 0) || contactRows[index - rowOffset].isSelectable()
        }
    }

    private fun bindTypedValueRow(container: LinearLayout, value: String, selected: Boolean) {
        container.removeAllViews()
        container.addView(
            TextView(container.context).apply {
                text = "Add \"$value\""
                textSize = 18f
                setPadding(dp(Dimens.SIDE_PADDING_DP), 0, dp(Dimens.SIDE_PADDING_DP), 0)
                applyRowSelectionStyle(selected)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        kind = if (isPlatformPick() || intent.getBooleanExtra(EXTRA_PICK_CONTACT, false)) null else requireSupportedKind()

        searchField = SearchField(this).apply {
            onQueryChanged = { rebuildRows() }
            editText.apply {
                hint = searchHint()
                textSize = 16f
                setTextColor(Color.WHITE)
                setHintTextColor(Palette.GRAY_FAINT)
                setPadding(dp(Dimens.SIDE_PADDING_DP), dp(10), dp(Dimens.SIDE_PADDING_DP), dp(6))
            }
        }
        rowListView = RowListView(this).apply { setBinder(binder) }
        hintBar = HintBar(this)

        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(searchField.editText)
            addView(rowListView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(hintBar)
        }
        val root = FrameLayout(this).apply {
            addView(mainColumn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        setContentView(root)
        updateHint()

        // A platform ACTION_PICK can come from any app, before C9's own permission screen has
        // ever run; in-app callers are always past it already.
        if (ContactStore.hasPermission(this)) {
            loadContacts()
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_CONTACTS_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CONTACTS_PERMISSION) return
        if (ContactStore.hasPermission(this)) {
            loadContacts()
        } else {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    /** The platform's `ACTION_PICK` on the contacts directory — what C9 answers for other apps
     *  wanting "choose a contact". Always whole-contact mode. */
    private fun isPlatformPick(): Boolean = intent.action == Intent.ACTION_PICK

    /** [EXTRA_KIND] must name [DataKind.PHONE] or [DataKind.EMAIL] — anything else (missing,
     *  misspelled, or a real but unsupported [DataKind]) is a caller bug, not a runtime condition
     *  to degrade gracefully from, so this crashes rather than silently falling back to a default
     *  kind nobody asked for. */
    private fun requireSupportedKind(): DataKind {
        val name = intent.getStringExtra(EXTRA_KIND) ?: error("ContactValuePickerActivity requires EXTRA_KIND")
        val parsed = DataKind.valueOf(name)
        require(parsed == DataKind.PHONE || parsed == DataKind.EMAIL) {
            "ContactValuePickerActivity only supports EXTRA_KIND PHONE or EMAIL, got $parsed"
        }
        return parsed
    }

    // The four remaining per-kind differences (search hint, typed-pseudo-row predicate, the
    // per-contact value source, and the empty-value toast) are each a plain `when (kind)` at the
    // one spot they matter, not a forked control flow — everything else in this screen (rendering,
    // key handling, letter-jump, the single/several/none result shape) runs identically for both.

    private fun searchHint(): String = when (kind) {
        null -> "Name"
        DataKind.EMAIL -> "Name or email"
        DataKind.PHONE -> "Name or number"
        else -> error("unsupported DataKind: $kind")
    }

    private fun looksLikeTypedValue(query: String): Boolean = when (kind) {
        null -> false
        DataKind.EMAIL -> looksLikeEmail(query)
        DataKind.PHONE -> StringMatch.looksDialable(query)
        else -> error("unsupported DataKind: $kind")
    }

    private fun emptyValueToast(name: String): String = when (kind) {
        DataKind.EMAIL -> "$name has no email address"
        DataKind.PHONE -> "$name has no phone number"
        else -> error("unsupported DataKind: $kind")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) searchField.onWindowFocusGained()
    }

    override fun onPause() {
        super.onPause()
        searchField.onPause()
    }

    /** Confirmed on-device: picking a contact here pops a "Save password to Bitwarden?" prompt
     *  over whichever screen the result lands on — this screen's search field looks like a
     *  credential field to an Autofill service, and committing it triggers a save prompt. The
     *  same defensive cancel `EventEditActivity`/`TaskEditActivity` already make for their own
     *  editors (see KNOWN_ISSUE_T9_IME.md's siblings), applied here so both this picker's callers
     *  — C9's attendee flow and M9's compose screen — get it without repeating it. */
    override fun onDestroy() {
        super.onDestroy()
        cancelAutofillSession()
    }

    /** Must run *before* [finish], not only from [onDestroy]: the Autofill session commits as
     *  this window goes away, and by the time onDestroy runs the save prompt has already been
     *  triggered — confirmed on-device, where onDestroy alone left the "Save password to
     *  Bitwarden?" dialog appearing over whichever screen the pick landed on. */
    private fun cancelAutofillSession() {
        getSystemService(android.view.autofill.AutofillManager::class.java)?.cancel()
    }

    private fun loadContacts() {
        val excluded = intent.getLongArrayExtra(EXTRA_EXCLUDE_CONTACT_IDS)?.toSet().orEmpty()
        val resumeAfter = intent.getLongExtra(EXTRA_RESUME_AFTER_CONTACT_ID, -1L).takeIf { it != -1L }
        Async.run(
            background = { ContactStore.queryContacts(this) },
            onResult = { loaded ->
                contacts = loaded.filter { it.id !in excluded }
                rebuildRows()
                if (resumeAfter != null && searchField.query.isEmpty()) {
                    resumeIndex(loaded, resumeAfter)?.let { rowListView.setSelectedIndex(it + rowOffset) }
                }
            },
        )
    }

    /** Where to pick up after [resumeAfter] was just added somewhere (C9's "Add contacts…" loop
     *  reopens this picker after every pick): the first remaining contact after it in list order,
     *  in the alphabetical part of the list — never the FAVORITES block at the top, where the
     *  same contacts appear a second time. [all] is the unfiltered, list-ordered contacts, which
     *  still knows where [resumeAfter] sat even though it's excluded now. */
    private fun resumeIndex(all: List<ContactEntry>, resumeAfter: Long): Int? {
        val position = all.indexOfFirst { it.id == resumeAfter }.takeIf { it >= 0 } ?: return null
        val after = all.drop(position + 1).map { it.id }.toSet()
        val alphabeticalStart = contactRows.indexOfFirst { it is ContactRow.SectionHeader && it.label != FAVORITES_HEADER }
        if (alphabeticalStart < 0) return null
        return contactRows.withIndex()
            .drop(alphabeticalStart)
            .firstOrNull { (_, row) -> row is ContactRow.Entry && row.contact.id in after }
            ?.index
            ?: contactRows.indexOfLast { it is ContactRow.Entry }.takeIf { it >= 0 }
    }

    private fun rebuildRows() {
        val query = searchField.query
        contactRows = buildContactRows(contacts, query, favoritesOnly = false)
        typedValueQuery = query.takeIf { looksLikeTypedValue(it) }
        rowListView.notifyDataChanged()
        updateHint()
    }

    private fun updateHint() {
        val ok = if (kind == null) "ok pick" else "ok add"
        hintBar.setText(if (searchField.query.isEmpty()) "‹ clear · $ok · ← → letter" else "‹ clear · $ok")
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (searchField.handleBack(event) { super.dispatchKeyEvent(it) }) return true
            onBackNotConsumedByIme()
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

    private fun onBackNotConsumedByIme() {
        if (searchField.query.isEmpty()) {
            setResult(RESULT_CANCELED)
            finish()
        } else {
            searchField.clear()
        }
    }

    private fun handleReservedKeyDown(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> rowListView.moveSelection(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> rowListView.moveSelection(1)
            KeyEvent.KEYCODE_DPAD_LEFT -> jumpLetterGroup(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> jumpLetterGroup(1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> confirmSelected()
        }
    }

    /** Offsets into/out of [contactRows] around the typed-value pseudo-row exactly like [binder]
     *  does, so a jump always lands on a real header's first entry regardless of whether that
     *  pseudo-row is currently occupying index 0. */
    private fun jumpLetterGroup(direction: Int) {
        val next = letterGroupJumpIndex(contactRows, rowListView.selectedIndex - rowOffset, direction) ?: return
        rowListView.setSelectedIndex(next + rowOffset)
    }

    private fun confirmSelected() {
        val typed = typedValueQuery
        val index = rowListView.selectedIndex
        if (typed != null && index == 0) {
            returnSingleResult(typed, name = null)
            return
        }
        val contact = contactRows.getOrNull(index - rowOffset)?.contactOrNull() ?: return
        if (kind == null) returnContactResult(contact) else resolveValues(contact)
    }

    private fun returnContactResult(contact: ContactEntry) {
        cancelAutofillSession()
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_CONTACT_ID, contact.id)
                putExtra(EXTRA_NAME, contact.displayName)
                // What ACTION_PICK's contract actually promises a caller: the picked contact's
                // Uri as the result data, readable by a caller without READ_CONTACTS of its own
                // (the contacts provider allows grants; C9 holds the permission to grant from).
                data = ContactsContract.Contacts.getLookupUri(contact.id, contact.lookupKey)
                if (isPlatformPick()) addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
        finish()
    }

    /** [kind]'s real "value source" difference, not just a cosmetic one: EMAIL has no presence on
     *  [ContactEntry] itself (only phone numbers get that bulk-loaded treatment — see
     *  [ContactEntry.phoneNumbers]'s own doc), so it still needs [ContactStore.queryContactDetail]'s
     *  per-contact `Data` row read, exactly as before this screen had a [kind] at all. PHONE numbers
     *  are already sitting on [entry] from the very query that built [contacts], so no second
     *  provider round trip happens for it — still wrapped in [Async.run] so [onValuesResolved] has
     *  one call path regardless of kind, not because PHONE has real background work to do. */
    private fun resolveValues(entry: ContactEntry) {
        val kind = kind ?: return
        Async.run(
            background = {
                when (kind) {
                    DataKind.EMAIL -> {
                        val detail = ContactStore.queryContactDetail(this, entry.id)
                        detail?.rows
                            ?.filter { DataKind.fromMimetype(it.mimetype) == DataKind.EMAIL }
                            ?.map { PickedValue(DataFormat.valueText(DataKind.EMAIL, it.values), DataFormat.typeLabel(resources, DataKind.EMAIL, it.values)) }
                            ?.filter { it.value.isNotBlank() }
                            ?.distinctBy { it.value }
                            ?: emptyList()
                    }
                    DataKind.PHONE -> entry.phoneNumbers.map { PickedValue(it.number, it.label) }
                    else -> error("unsupported DataKind: $kind")
                }
            },
            onResult = { values -> onValuesResolved(entry, values) },
        )
    }

    private fun onValuesResolved(entry: ContactEntry, values: List<PickedValue>) {
        when {
            values.isEmpty() -> Toast.makeText(this, emptyValueToast(entry.displayName), Toast.LENGTH_SHORT).show()
            values.size == 1 -> returnSingleResult(values.single().value, entry.displayName)
            else -> returnMultipleResult(values, entry.displayName)
        }
    }

    private fun returnSingleResult(value: String, name: String?) {
        cancelAutofillSession()
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_VALUE, value)
                putExtra(EXTRA_NAME, name)
            },
        )
        finish()
    }

    private fun returnMultipleResult(values: List<PickedValue>, name: String) {
        cancelAutofillSession()
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_VALUES, values.map { it.value }.toTypedArray())
                putExtra(EXTRA_LABELS, values.map { it.label.orEmpty() }.toTypedArray())
                putExtra(EXTRA_NAME, name)
            },
        )
        finish()
    }

    companion object {
        /** The string name of a [DataKind] — [DataKind.PHONE] or [DataKind.EMAIL] only — required
         *  on every launching [Intent]; see [requireSupportedKind]. */
        const val EXTRA_KIND = "kind"
        /** Set when exactly one [kind] value was resolvable — the whole answer, nothing more to
         *  ask. */
        const val EXTRA_VALUE = "value"
        /** Set instead of [EXTRA_VALUE] when a contact has more than one — paired positionally
         *  with [EXTRA_LABELS]; the caller must ask which. */
        const val EXTRA_VALUES = "values"
        /** Parallel to [EXTRA_VALUES]: each entry is that value's type label ("mobile", "work",
         *  ...), or "" when [kind] has none for that particular row — the same labels
         *  [ContactsActivity.dialContact]'s own multi-number chooser already shows, not a new
         *  formatting path. */
        const val EXTRA_LABELS = "labels"
        const val EXTRA_NAME = "name"
        /** Boolean, in place of [EXTRA_KIND]: pick a whole contact, returned as
         *  [EXTRA_CONTACT_ID] (+ [EXTRA_NAME]). */
        const val EXTRA_PICK_CONTACT = "pick_contact"
        /** LongArray of contact ids not to offer at all (e.g. a group's current members). */
        const val EXTRA_EXCLUDE_CONTACT_IDS = "exclude_contact_ids"
        /** Contact id the list should resume just after (see [resumeIndex]). */
        const val EXTRA_RESUME_AFTER_CONTACT_ID = "resume_after_contact_id"
        private const val FAVORITES_HEADER = "FAVORITES"
        const val EXTRA_CONTACT_ID = "contact_id"
    }
}
