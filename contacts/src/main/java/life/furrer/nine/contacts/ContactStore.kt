package life.furrer.nine.contacts

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.DatabaseUtils
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds as CDK
import life.furrer.nine.core.prefs.NinePrefs
import life.furrer.nine.core.util.bool
import life.furrer.nine.core.util.firstRow
import life.furrer.nine.core.util.long
import life.furrer.nine.core.util.mapRows
import life.furrer.nine.core.util.mapRowsNotNull
import life.furrer.nine.core.util.string
import life.furrer.nine.core.util.stringOr

data class ContactPhoneNumber(
    val number: String,
    val label: String?,
    val isSuperPrimary: Boolean,
)

data class ContactEntry(
    val id: Long,
    val lookupKey: String,
    val displayName: String,
    /** Android's own SORT_KEY_PRIMARY — the name with honorific prefixes ("Dr. med."),
     *  generational suffixes etc. already stripped, the way the platform's own contacts app
     *  computes it. [displayName] keeps those for a name shown in full; grouping/sorting should
     *  use this instead, or a contact like "Dr. med. Mara Furrer" gets its own stray "D" letter
     *  header instead of sitting under "M" with everyone else named Mara/Furrer. Defaults to
     *  [displayName] for callers (mostly tests) that never fetch a real one from the provider. */
    val sortKey: String = displayName,
    val starred: Boolean,
    val photoThumbnailUri: String?,
    /** Every phone number this contact has — for the number-match half of search (§6.3) and the
     *  call-key dial target, including which one is the primary (§6.2: "Data.IS_SUPER_PRIMARY
     *  marks the primary number — that's what the call key dials"). Loaded once alongside the
     *  main list query, not per-keystroke (§6.2), and not re-queried again just to dial. */
    val phoneNumbers: List<ContactPhoneNumber> = emptyList(),
)

data class RawContactAccount(
    val rawContactId: Long,
    val accountName: String?,
    val accountType: String?,
    /** `RawContacts.DIRTY` — true when this raw contact has local edits not yet pushed upstream.
     *  Meaningless for a local-only raw contact ([accountType] null), which never syncs at all. */
    val dirty: Boolean,
    /** False for a messaging app's mirror (WhatsApp, Signal) — see [AccountTypes]. */
    val editable: Boolean = true,
)

/** The full `Data`-table read for the detail/editor screens (§6.2): every row across every raw
 *  contact this aggregated contact links to, plus which account each raw contact belongs to.
 *  [rows] includes rows whose mimetype isn't in [DataKind] — [DataKind.fromMimetype] returns
 *  null for those and the caller shows them read-only under "Other" instead of dropping them. */
data class ContactDetail(
    val id: Long,
    val lookupKey: String,
    val displayName: String,
    val starred: Boolean,
    val photoThumbnailUri: String?,
    val rows: List<DataRow>,
    val rawContacts: List<RawContactAccount>,
    /** Android's SEND_TO_VOICEMAIL as Telecom reads it: on only while every raw contact has it
     *  (see [VoicemailProperty]). */
    val sendToVoicemail: Boolean = false,
) {
    /** More than one raw contact aggregated into this one — the §9.10 "Linked · fastmail,
     *  work" line, and the editor only needs to ask which raw contact a new field belongs to
     *  when this is true. */
    val isLinked: Boolean get() = rawContacts.size > 1

    /** The raw contact that *is* this contact for reading its name and writing new fields: the
     *  lowest-id editable one (a real address book), falling back to the lowest id overall.
     *  "Lowest id" alone used to be the rule, on the theory that the synced source predates any
     *  messaging-app mirror linked to it — until an address book re-download on the phone gave
     *  every synced raw contact a new, higher id, and the mirrors won. */
    val primaryRawContact: RawContactAccount? get() = primaryOf(rawContacts)

    /** Only the primary raw contact's own DIRTY counts. A mirror's is meaningless — confirmed
     *  on-device that WhatsApp's linked raw contact never clears DIRTY (its adapter never
     *  uploads), which kept "NOT YET SYNCED" on permanently for contacts whose oldest copy was
     *  WhatsApp's. A device-local primary never syncs, so it's never "pending" either. */
    val hasPendingSync: Boolean get() = primaryRawContact?.takeIf { it.accountType != null }?.dirty ?: false
}

/** [ContactDetail.primaryRawContact]'s rule, for callers holding just the raw contacts. */
fun primaryOf(rawContacts: List<RawContactAccount>): RawContactAccount? =
    rawContacts.filter { it.editable }.minByOrNull { it.rawContactId } ?: rawContacts.minByOrNull { it.rawContactId }

object ContactStore {
    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** The list query per §6.2 — this runs on every keystroke once search exists (M4), so it
     *  stays a single flat query with no joins and no per-contact follow-up queries. Sorted by
     *  SORT_KEY_PRIMARY, the collation Android itself uses for a name-ordered contact list.
     *  Phone numbers are bulk-loaded once here (a separate, single query) rather than per
     *  keystroke, since search matches by number as well as name (§6.3). */
    fun queryContacts(context: Context): List<ContactEntry> {
        if (!hasPermission(context)) return emptyList()

        val phoneNumbersByContact = queryAllPhoneNumbers(context)
        val primaryDisplayNames = queryPrimaryDisplayNames(context)

        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.SORT_KEY_PRIMARY,
            ContactsContract.Contacts.STARRED,
            ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
        )

        val results = mutableListOf<ContactEntry>()
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.Contacts.SORT_KEY_PRIMARY,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val lookupIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY)
            val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            val sortKeyIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.SORT_KEY_PRIMARY)
            val starredIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.STARRED)
            val photoIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val displayName = primaryDisplayNames[id] ?: cursor.getString(nameIndex) ?: "(no name)"
                results.add(
                    ContactEntry(
                        id = id,
                        lookupKey = cursor.getString(lookupIndex) ?: "",
                        displayName = displayName,
                        sortKey = cursor.getString(sortKeyIndex)?.takeIf { it.isNotBlank() } ?: displayName,
                        starred = cursor.getInt(starredIndex) != 0,
                        photoThumbnailUri = cursor.getString(photoIndex),
                        phoneNumbers = phoneNumbersByContact[id] ?: emptyList(),
                    ),
                )
            }
        }
        return results
    }

    private fun queryAllPhoneNumbers(context: Context): Map<Long, List<ContactPhoneNumber>> {
        val result = mutableMapOf<Long, MutableList<ContactPhoneNumber>>()
        val projection = arrayOf(
            CDK.Phone.CONTACT_ID, CDK.Phone.NUMBER, CDK.Phone.TYPE, CDK.Phone.LABEL, CDK.Phone.IS_SUPER_PRIMARY,
        )
        context.contentResolver.query(CDK.Phone.CONTENT_URI, projection, null, null, null)?.use { cursor ->
            val contactIdIndex = cursor.getColumnIndexOrThrow(CDK.Phone.CONTACT_ID)
            val numberIndex = cursor.getColumnIndexOrThrow(CDK.Phone.NUMBER)
            val typeIndex = cursor.getColumnIndexOrThrow(CDK.Phone.TYPE)
            val labelIndex = cursor.getColumnIndexOrThrow(CDK.Phone.LABEL)
            val superPrimaryIndex = cursor.getColumnIndexOrThrow(CDK.Phone.IS_SUPER_PRIMARY)
            while (cursor.moveToNext()) {
                val number = cursor.getString(numberIndex) ?: continue
                val type = if (cursor.isNull(typeIndex)) null else cursor.getInt(typeIndex)
                val customLabel = cursor.getString(labelIndex)
                val label = DataFormat.typeLabel(
                    context.resources,
                    DataKind.PHONE,
                    ContentValues().apply {
                        if (type != null) put(CDK.Phone.TYPE, type)
                        put(CDK.Phone.LABEL, customLabel)
                    },
                )
                result.getOrPut(cursor.getLong(contactIdIndex)) { mutableListOf() }.add(
                    ContactPhoneNumber(
                        number = number,
                        label = label,
                        isSuperPrimary = cursor.getInt(superPrimaryIndex) != 0,
                    ),
                )
            }
        }
        return result.mapValues { (_, numbers) -> dedupeByDigits(numbers) }
    }

    /** A bulk, single-query alternative to [ContactsContract.Contacts.DISPLAY_NAME_PRIMARY] for
     *  every contact at once — CP2's own aggregate can combine two raw contacts' structured name
     *  components into a nonsensical result ("Dr. med. Dr. med. Mara Furrer") when a synced
     *  contact and a messaging app's own linked mirror (WhatsApp, Signal) each carry their own
     *  StructuredName row that parses the same visible name differently, even though each raw
     *  contact's own DISPLAY_NAME is individually correct. Picks the same raw contact
     *  [queryContactDetail] does — an editable one (a real address book) over a messaging-app
     *  mirror, then the lowest id — so the list and detail screens agree on one name. Contacts
     *  with no StructuredName row at all (org-only contacts) are simply absent here, falling back
     *  to the platform aggregate. */
    private fun queryPrimaryDisplayNames(context: Context): Map<Long, String> {
        val mirrors = queryMirrorRawContactIds(context)
        val rank = compareBy<Long>({ it in mirrors }, { it })
        val best = mutableMapOf<Long, Pair<Long, String>>()
        val projection = arrayOf(
            ContactsContract.Data.CONTACT_ID,
            ContactsContract.Data.RAW_CONTACT_ID,
            CDK.StructuredName.DISPLAY_NAME,
        )
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            projection,
            "${ContactsContract.Data.MIMETYPE}=?",
            arrayOf(CDK.StructuredName.CONTENT_ITEM_TYPE),
            null,
        )?.use { cursor ->
            val contactIdIndex = cursor.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
            val rawContactIdIndex = cursor.getColumnIndexOrThrow(ContactsContract.Data.RAW_CONTACT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(CDK.StructuredName.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex)?.takeIf { it.isNotBlank() } ?: continue
                val contactId = cursor.getLong(contactIdIndex)
                val rawContactId = cursor.getLong(rawContactIdIndex)
                val current = best[contactId]
                if (current == null || rank.compare(rawContactId, current.first) < 0) {
                    best[contactId] = rawContactId to name
                }
            }
        }
        return best.mapValues { it.value.second }
    }

    /** Raw contacts that belong to a read-only (messaging-app mirror) account; see [AccountTypes]. */
    private fun queryMirrorRawContactIds(context: Context): Set<Long> {
        val result = mutableSetOf<Long>()
        context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.ACCOUNT_TYPE),
            null,
            null,
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.RawContacts._ID)
            val typeIndex = cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.ACCOUNT_TYPE)
            while (cursor.moveToNext()) {
                if (!AccountTypes.isEditable(context, cursor.getString(typeIndex))) result.add(cursor.getLong(idIndex))
            }
        }
        return result
    }

    /** Collapses numbers that are the same once formatting is stripped — apps like WhatsApp and
     *  Signal mirror a contact's real number into their own linked raw contact (so they can
     *  attach their own quick-action icon to it), which otherwise shows the exact same number
     *  two or three times over, e.g. in the call key's "which number?" menu. Keeps the first
     *  occurrence, but a later super-primary duplicate still marks the kept one primary rather
     *  than being silently dropped. */
    private fun dedupeByDigits(numbers: List<ContactPhoneNumber>): List<ContactPhoneNumber> {
        val byDigits = LinkedHashMap<String, ContactPhoneNumber>()
        for (n in numbers) {
            val key = n.number.filter { it.isDigit() }
            val existing = byDigits[key]
            if (existing == null) {
                byDigits[key] = n
            } else if (n.isSuperPrimary && !existing.isSuperPrimary) {
                byDigits[key] = existing.copy(isSuperPrimary = true)
            }
        }
        return byDigits.values.toList()
    }

    fun queryContactDetail(context: Context, contactId: Long): ContactDetail? {
        if (!hasPermission(context)) return null

        var lookupKey = ""
        var displayName = "(no name)"
        var starred = false
        var sendToVoicemail = false
        var photoUri: String? = null
        val contactProjection = arrayOf(
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.STARRED,
            ContactsContract.Contacts.SEND_TO_VOICEMAIL,
            ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
        )
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            contactProjection,
            "${ContactsContract.Contacts._ID}=?",
            arrayOf(contactId.toString()),
            null,
        ).firstRow { cursor ->
            lookupKey = cursor.stringOr(ContactsContract.Contacts.LOOKUP_KEY, "")
            displayName = cursor.stringOr(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, "(no name)")
            starred = cursor.bool(ContactsContract.Contacts.STARRED)
            sendToVoicemail = cursor.bool(ContactsContract.Contacts.SEND_TO_VOICEMAIL)
            photoUri = cursor.string(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI)
            true
        } ?: return null

        // A plain "SELECT *" over Data for this contact — every kind's own NUMBER/ADDRESS/URL/…
        // accessor is just an alias for the same underlying data1..data15 column names
        // (CommonDataKinds.CommonColumns.DATA == Phone.NUMBER == "data1", etc.), so copying the
        // whole cursor row into a ContentValues bag keyed by its real column names is enough for
        // every DataFormat function to read the right value back out by its typed accessor.
        val rows = context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            null,
            "${ContactsContract.Data.CONTACT_ID}=?",
            arrayOf(contactId.toString()),
            null,
        ).mapRows { cursor ->
            val values = ContentValues()
            DatabaseUtils.cursorRowToContentValues(cursor, values)
            DataRow(
                id = cursor.long(ContactsContract.Data._ID),
                rawContactId = cursor.long(ContactsContract.Data.RAW_CONTACT_ID),
                mimetype = cursor.stringOr(ContactsContract.Data.MIMETYPE, ""),
                values = values,
            )
        }

        val rawContacts = context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(
                ContactsContract.RawContacts._ID,
                ContactsContract.RawContacts.ACCOUNT_NAME,
                ContactsContract.RawContacts.ACCOUNT_TYPE,
                ContactsContract.RawContacts.DIRTY,
            ),
            "${ContactsContract.RawContacts.CONTACT_ID}=?",
            arrayOf(contactId.toString()),
            null,
        ).mapRows { cursor ->
            val accountType = cursor.string(ContactsContract.RawContacts.ACCOUNT_TYPE)
            RawContactAccount(
                rawContactId = cursor.long(ContactsContract.RawContacts._ID),
                accountName = cursor.string(ContactsContract.RawContacts.ACCOUNT_NAME),
                accountType = accountType,
                dirty = cursor.bool(ContactsContract.RawContacts.DIRTY),
                editable = AccountTypes.isEditable(context, accountType),
            )
        }

        // Prefers a raw contact's own StructuredName.DISPLAY_NAME over the platform's aggregated
        // Contacts.DISPLAY_NAME_PRIMARY already loaded above, when one exists — confirmed
        // on-device that CP2's own name aggregation can produce a nonsensical combined name
        // ("Dr. med. Dr. med. Mara Furrer") when two raw contacts (a real synced one and a
        // messaging app's own linked mirror) parse the same visible name into structured
        // components differently, even though each raw contact's own DISPLAY_NAME is
        // individually correct. Picks the primary raw contact's NAME row (see
        // [ContactDetail.primaryRawContact]: a real address book over a messaging-app mirror),
        // else the lowest-id one.
        val primaryRawId = primaryOf(rawContacts)?.rawContactId
        rows.filter { DataKind.fromMimetype(it.mimetype) == DataKind.NAME }
            .minWithOrNull(compareBy({ it.rawContactId != primaryRawId }, { it.rawContactId }))
            ?.values?.getAsString(CDK.StructuredName.DISPLAY_NAME)
            ?.takeIf { it.isNotBlank() }
            ?.let { displayName = it }

        return ContactDetail(
            id = contactId,
            lookupKey = lookupKey,
            displayName = displayName,
            starred = starred,
            photoThumbnailUri = photoUri,
            rows = rows,
            rawContacts = rawContacts,
            sendToVoicemail = sendToVoicemail,
        )
    }

    fun setStarred(context: Context, contactId: Long, starred: Boolean): Boolean {
        val values = ContentValues().apply { put(ContactsContract.Contacts.STARRED, if (starred) 1 else 0) }
        val uri = android.content.ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
        return context.contentResolver.update(uri, values, null, null) > 0
    }

    /** "Sync now" per §5.3: every distinct account behind the address books currently on the
     *  device (never `AccountManager` — accounts come from the provider's own columns), each
     *  requested as a manual, expedited sync. */
    fun requestSync(context: Context) {
        if (!hasPermission(context)) return
        val accounts = mutableSetOf<Pair<String, String>>()
        context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts.ACCOUNT_NAME, ContactsContract.RawContacts.ACCOUNT_TYPE),
            null,
            null,
            null,
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.ACCOUNT_NAME)
            val typeIndex = cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.ACCOUNT_TYPE)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex) ?: continue
                val type = cursor.getString(typeIndex) ?: continue
                accounts.add(name to type)
            }
        }
        val extras = android.os.Bundle().apply {
            putBoolean(android.content.ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(android.content.ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        accounts.forEach { (name, type) ->
            android.content.ContentResolver.requestSync(android.accounts.Account(name, type), ContactsContract.AUTHORITY, extras)
        }
    }

    fun hasWritePermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** A writable address book to create a new contact in (§6.2: "Creating a contact asks which
     *  account/address book to write to... If exactly one writable account exists, don't ask"). */
    data class WritableAccount(val name: String, val type: String)

    /** Messaging-app mirror accounts are left out: Android lets an app write to them, but
     *  they're read-only by their own declaration ([AccountTypes]) and nothing written there
     *  reaches an address book — on the phone they showed up as WhatsApp/Signal choices. */
    fun queryWritableAccounts(context: Context): List<WritableAccount> {
        if (!hasWritePermission(context)) return emptyList()
        return queryDistinctAccounts(context).filter { AccountTypes.isEditable(context, it.type) }
    }

    /** Every distinct account with at least one raw contact — the read-only equivalent of
     *  [queryWritableAccounts], for the Accounts visibility filter (§7.4 `*` → Accounts), which
     *  only needs READ_CONTACTS, not WRITE_CONTACTS. */
    fun queryAccounts(context: Context): List<WritableAccount> {
        if (!hasPermission(context)) return emptyList()
        return queryDistinctAccounts(context)
    }

    private fun queryDistinctAccounts(context: Context): List<WritableAccount> {
        val accounts = mutableSetOf<WritableAccount>()
        context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts.ACCOUNT_NAME, ContactsContract.RawContacts.ACCOUNT_TYPE),
            null,
            null,
            null,
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.ACCOUNT_NAME)
            val typeIndex = cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.ACCOUNT_TYPE)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex) ?: continue
                val type = cursor.getString(typeIndex) ?: continue
                accounts.add(WritableAccount(name, type))
            }
        }
        return accounts.sortedBy { it.name.lowercase() }
    }

    /** Null means the user has never customized this — callers should default to "all accounts
     *  shown", same convention as [CalendarStore]'s selected-calendar-ids. */
    fun getSelectedAccounts(context: Context): Set<WritableAccount>? {
        val json = prefs(context).getString(KEY_ACCOUNTS, null) ?: return null
        val array = org.json.JSONArray(json)
        val result = mutableSetOf<WritableAccount>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            result.add(WritableAccount(obj.getString("name"), obj.getString("type")))
        }
        return result
    }

    fun setSelectedAccounts(context: Context, accounts: Set<WritableAccount>) {
        val array = org.json.JSONArray()
        accounts.forEach { account ->
            array.put(org.json.JSONObject().apply { put("name", account.name); put("type", account.type) })
        }
        prefs(context).edit().putString(KEY_ACCOUNTS, array.toString()).apply()
    }

    /** Contact ids with at least one raw contact in one of [accounts] — used to apply the
     *  Accounts filter to [queryContacts]' results in memory, the same "filter in memory" style
     *  as everything else here (§6.2). */
    fun queryContactIdsInAccounts(context: Context, accounts: Set<WritableAccount>): Set<Long> {
        if (accounts.isEmpty()) return emptySet()
        val result = mutableSetOf<Long>()
        context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(
                ContactsContract.RawContacts.CONTACT_ID,
                ContactsContract.RawContacts.ACCOUNT_NAME,
                ContactsContract.RawContacts.ACCOUNT_TYPE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val contactIdIndex = cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.CONTACT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.ACCOUNT_NAME)
            val typeIndex = cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.ACCOUNT_TYPE)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex) ?: continue
                val type = cursor.getString(typeIndex) ?: continue
                if (WritableAccount(name, type) in accounts) result.add(cursor.getLong(contactIdIndex))
            }
        }
        return result
    }

    /** A CardDAV group, grouped for display by the account it belongs to (§6.2's GroupMembership
     *  row, §7.4's `*` → Groups). Groups with no [title] are system bookkeeping rows (e.g. some
     *  accounts' own "all contacts" auto-group) rather than anything a user created — never shown
     *  as a filterable row. */
    data class ContactGroup(val id: Long, val title: String, val accountName: String, val accountType: String)

    fun queryGroups(context: Context): List<ContactGroup> {
        if (!hasPermission(context)) return emptyList()
        val projection = arrayOf(
            ContactsContract.Groups._ID,
            ContactsContract.Groups.TITLE,
            ContactsContract.Groups.ACCOUNT_NAME,
            ContactsContract.Groups.ACCOUNT_TYPE,
        )
        val selection = "${ContactsContract.Groups.DELETED}=0 AND ${ContactsContract.Groups.TITLE} IS NOT NULL"
        val result = context.contentResolver.query(ContactsContract.Groups.CONTENT_URI, projection, selection, null, null)
            .mapRowsNotNull { cursor ->
                ContactGroup(
                    id = cursor.long(ContactsContract.Groups._ID),
                    title = cursor.string(ContactsContract.Groups.TITLE) ?: return@mapRowsNotNull null,
                    accountName = cursor.stringOr(ContactsContract.Groups.ACCOUNT_NAME, ""),
                    accountType = cursor.stringOr(ContactsContract.Groups.ACCOUNT_TYPE, ""),
                )
            }
        return result.sortedWith(compareBy({ it.accountName.lowercase() }, { it.title.lowercase() }))
    }

    fun groupsByAccount(groups: List<ContactGroup>): List<Pair<String, List<ContactGroup>>> =
        groups.groupBy { it.accountName }.toList()

    /** Groups whose "Show in contacts list" the user turned off (the groups screen's `*` menu).
     *  Stored as the *hidden* set, so a group created later — here or on the server — is shown
     *  without anyone having to opt it in.
     *
     *  Replaces an older "shown groups" setting ([KEY_LEGACY_SHOWN_GROUP_IDS], written by C9's
     *  former Groups filter screen), which is converted the first time it's read with
     *  READ_CONTACTS: every then-existing group not in it becomes hidden. Without permission the
     *  old setting is left alone for a later read rather than converted against an empty group
     *  list (which would hide nothing and lose it). */
    fun hiddenGroupIds(context: Context): Set<Long> {
        val prefs = prefs(context)
        prefs.getString(KEY_HIDDEN_GROUP_IDS, null)?.let { return parseIds(it) }
        val legacy = prefs.getString(KEY_LEGACY_SHOWN_GROUP_IDS, null) ?: return emptySet()
        if (!hasPermission(context)) return emptySet()
        val shown = parseIds(legacy)
        val hidden = queryGroups(context).map { it.id }.filterNot { it in shown }.toSet()
        prefs.edit().putString(KEY_HIDDEN_GROUP_IDS, idsJson(hidden)).remove(KEY_LEGACY_SHOWN_GROUP_IDS).apply()
        return hidden
    }

    fun isGroupShown(context: Context, groupId: Long): Boolean = groupId !in hiddenGroupIds(context)

    /** A per-app preference (each host's own [NinePrefs] file), not a contacts write — so a
     *  read-only host like P9 gets its own filter too, and nothing reaches the server. */
    fun setGroupShown(context: Context, groupId: Long, shown: Boolean) {
        val hidden = hiddenGroupIds(context).toMutableSet()
        if (shown) hidden.remove(groupId) else hidden.add(groupId)
        prefs(context).edit().putString(KEY_HIDDEN_GROUP_IDS, idsJson(hidden)).apply()
    }

    /** Whether a contact in [contactGroupIds] is listed, given the [hidden] groups and the
     *  [liveGroupIds] that still exist ([queryGroups]). Android's own rule for group visibility:
     *  a contact is shown if it's in *any* shown group, so hiding "Work" doesn't hide a colleague
     *  who is also in "Family". A contact in no group at all is always shown — hiding groups
     *  never makes ungrouped people disappear — and memberships of groups that no longer exist
     *  (or are system bookkeeping) count for nothing either way. */
    fun isShownByGroups(contactGroupIds: Set<Long>, hidden: Set<Long>, liveGroupIds: Set<Long>): Boolean {
        val live = contactGroupIds intersect liveGroupIds
        if (live.isEmpty()) return true
        return live.any { it !in hidden }
    }

    private fun parseIds(json: String): Set<Long> = try {
        val array = org.json.JSONArray(json)
        (0 until array.length()).map { array.getLong(it) }.toSet()
    } catch (e: org.json.JSONException) {
        emptySet()
    }

    private fun idsJson(ids: Set<Long>): String = org.json.JSONArray().apply { ids.sorted().forEach { put(it) } }.toString()

    /** Contact ids belonging to at least one of [groupIds], via the Data table's own
     *  GroupMembership rows (§6.2) — never a hardcoded join against a specific sync adapter's
     *  own tables. */
    fun queryContactIdsInGroups(context: Context, groupIds: Set<Long>): Set<Long> {
        if (groupIds.isEmpty()) return emptySet()
        val projection = arrayOf(ContactsContract.Data.CONTACT_ID)
        val placeholders = groupIds.joinToString(",") { "?" }
        val selection = "${ContactsContract.Data.MIMETYPE}=? AND ${CDK.GroupMembership.GROUP_ROW_ID} IN ($placeholders)"
        val args = arrayOf(CDK.GroupMembership.CONTENT_ITEM_TYPE) + groupIds.map { it.toString() }
        val result = mutableSetOf<Long>()
        context.contentResolver.query(ContactsContract.Data.CONTENT_URI, projection, selection, args, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
            while (cursor.moveToNext()) result.add(cursor.getLong(idIndex))
        }
        return result
    }

    private const val KEY_HIDDEN_GROUP_IDS = "contact_hidden_group_ids"
    private const val KEY_LEGACY_SHOWN_GROUP_IDS = "contact_group_ids"
    private const val KEY_ACCOUNTS = "contact_accounts"

    // Each host's own prefs file (NinePrefs.of), not a hardcoded "c9" one — this store is shared
    // with P9, and each app's Groups/Accounts filter must stay its own.
    private fun prefs(context: Context) = NinePrefs.of(context)

    /** One in-progress change to a single `Data` row, batched together in [applyFieldChanges] so
     *  an edit to several fields at once is atomic. Rows never referenced by a [FieldChange] —
     *  every mimetype this app doesn't understand — are simply never touched, which is exactly
     *  how §6.2's "unknown mimetypes are shown read-only... never dropped" is upheld on write:
     *  there is no "rewrite everything" step for this to accidentally lose them in. */
    sealed interface FieldChange {
        data class Update(val dataRowId: Long, val values: ContentValues) : FieldChange
        data class Insert(val rawContactId: Long, val kind: DataKind, val values: ContentValues) : FieldChange
        data class Delete(val dataRowId: Long) : FieldChange
    }

    fun applyFieldChanges(context: Context, changes: List<FieldChange>): Boolean {
        if (!hasWritePermission(context)) return false
        if (changes.isEmpty()) return true
        val ops = ArrayList<android.content.ContentProviderOperation>(changes.size)
        changes.forEach { change ->
            ops.add(
                when (change) {
                    is FieldChange.Update -> android.content.ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                        .withSelection("${ContactsContract.Data._ID}=?", arrayOf(change.dataRowId.toString()))
                        .withValues(change.values)
                        .build()
                    is FieldChange.Insert -> android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValue(ContactsContract.Data.RAW_CONTACT_ID, change.rawContactId)
                        .withValue(ContactsContract.Data.MIMETYPE, change.kind.mimetype)
                        .withValues(change.values)
                        .build()
                    is FieldChange.Delete -> android.content.ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                        .withSelection("${ContactsContract.Data._ID}=?", arrayOf(change.dataRowId.toString()))
                        .build()
                },
            )
        }
        return try {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** A brand-new contact, created in one atomic batch: a RawContact row for [account] (or an
     *  account-less local contact if null — the provider handles that same as any other app's
     *  "no account" contact), then every field in [fields] as a Data row back-referencing it via
     *  its position in the batch — never `CALLER_IS_SYNCADAPTER`, so DIRTY is set for real sync,
     *  same as calendar's own write path (§6.1.2's contacts equivalent). */
    fun insertContact(context: Context, account: WritableAccount?, fields: List<Pair<DataKind, ContentValues>>): Long? {
        if (!hasWritePermission(context)) return null
        val ops = ArrayList<android.content.ContentProviderOperation>(fields.size + 1)
        ops.add(
            android.content.ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, account?.name)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, account?.type)
                .build(),
        )
        fields.forEach { (kind, values) ->
            ops.add(
                android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, kind.mimetype)
                    .withValues(values)
                    .build(),
            )
        }
        val results = try {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        } catch (e: Exception) {
            return null
        }
        val rawContactUri = results.firstOrNull()?.uri ?: return null
        val rawContactId = android.content.ContentUris.parseId(rawContactUri)
        return queryContactIdForRawContact(context, rawContactId)
    }

    private fun queryContactIdForRawContact(context: Context, rawContactId: Long): Long? {
        return context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts.CONTACT_ID),
            "${ContactsContract.RawContacts._ID}=?",
            arrayOf(rawContactId.toString()),
            null,
        ).firstRow { it.long(ContactsContract.RawContacts.CONTACT_ID) }
    }

    /** Deletes every RawContact behind this (possibly linked/aggregated) contact — never
     *  `CALLER_IS_SYNCADAPTER`, so the provider marks them deleted for sync rather than the row
     *  vanishing locally with the server never told (§6.1.2's contacts equivalent). */
    fun deleteContact(context: Context, contactId: Long): Boolean {
        if (!hasWritePermission(context)) return false
        val count = context.contentResolver.delete(
            ContactsContract.RawContacts.CONTENT_URI,
            "${ContactsContract.RawContacts.CONTACT_ID}=?",
            arrayOf(contactId.toString()),
        )
        return count > 0
    }
}
