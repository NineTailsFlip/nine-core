package life.furrer.nine.contacts

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds as CDK

private const val AUTHORITY = "com.android.contacts"
private const val CONTACTS = 1
private const val CONTACTS_ID = 2
private const val DATA = 3
private const val DATA_PHONES = 4
private const val RAW_CONTACTS = 5
private const val GROUPS = 6
private const val PHONE_LOOKUP = 7
private const val GROUPS_ID = 8

/** How many trailing digits have to agree for [FakeContactsProvider]'s PhoneLookup to call it a
 *  match. The real provider's matching is considerably more subtle (it is country- and
 *  carrier-aware, and normalizes through libphonenumber), but "the last seven digits agree" is
 *  the property every caller in this repo actually depends on: `+41791234567` written in the
 *  address book must match `079 123 45 67` as it arrives on an SMS or a call. */
private const val PHONE_LOOKUP_SIGNIFICANT_DIGITS = 7

// The exact selection strings ContactGroups sends (its own constants are internal to the main
// source set), matched exactly the same way RAW_CONTACTS already tells its query shapes apart.
private const val ACCOUNT_RAW_CONTACTS_SELECTION =
    "${ContactsContract.RawContacts.ACCOUNT_NAME}=? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND " +
        "${ContactsContract.RawContacts.DELETED}=0"
private const val GROUP_BY_ID_SELECTION = "${ContactsContract.Groups._ID}=? AND ${ContactsContract.Groups.DELETED}=0"
private const val CONTACT_MEMBERSHIP_SELECTION =
    "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?"
private const val VOICEMAIL_SELECTION = "${ContactsContract.Contacts.SEND_TO_VOICEMAIL}=1"
private const val VOICEMAIL_RAW_CONTACTS_SELECTION =
    "${ContactsContract.RawContacts.SEND_TO_VOICEMAIL}=1 AND ${ContactsContract.RawContacts.DELETED}=0 AND " +
        "${ContactsContract.RawContacts.RAW_CONTACT_IS_READ_ONLY}=0"
private const val MEMBERSHIP_ROW_SELECTION =
    "${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${CDK.GroupMembership.GROUP_ROW_ID}=?"

/** A minimal in-memory contacts provider for tests — confirmed empirically (same technique as
 *  FakeCalendarProvider) that Robolectric has no working ContactsProvider2, and the real URI
 *  paths this matches ("contacts", "data", "data/phones", "raw_contacts") were themselves
 *  confirmed by printing the real constants under Robolectric rather than assumed. This covers
 *  only what ContactStore's read path needs: the flat contacts list, a bulk phone-number query,
 *  a per-contact Data row dump, and per-contact RawContacts/account rows — plus ContactGroups'
 *  shapes: starred contacts, raw contacts by account, group insert/lookup by id, and
 *  GroupMembership rows inserted and deleted per raw contact, one contact's memberships, and a
 *  group renamed or deleted by id (a delete behaves like the real provider's non-sync-adapter
 *  one: the row is only marked DELETED and its membership rows go) — and SEND_TO_VOICEMAIL as the
 *  real provider has it: a flag per raw contact, the aggregate's only on while it has a
 *  non-deleted raw contact and all of them carry it, contacts with SEND_TO_VOICEMAIL=1 and raw
 *  contacts with "send_to_voicemail=1 AND deleted=0 AND raw_contact_is_read_only=0" queried (read-only
 *  raw contacts never written through), and an update of the aggregate
 *  propagated to every one of its raw contacts. */
class FakeContactsProvider : ContentProvider() {

    companion object {
        /** Each entry's ContentValues also carries CONTACT_ID (an ordinary column here, exactly
         *  like FakeCalendarProvider treats DIRTY/DELETED) so the Data/RawContacts fakes below
         *  can filter by it the same way the real provider's join views do. */
        val contacts = mutableMapOf<Long, ContentValues>()
        val data = mutableMapOf<Long, ContentValues>()
        val rawContacts = mutableMapOf<Long, ContentValues>()
        val groups = mutableMapOf<Long, ContentValues>()
        private var nextRawContactId = 1000L
        private var nextDataId = 1000L
        private var nextGroupId = 1000L

        /** When true every insert/update/delete throws, standing in for a provider-side failure
         *  mid-batch — so a test can check that a caller doesn't record what it never wrote. */
        var failWrites = false

        /** Every Data-row id an update was applied to, in order — lets a test assert that a row
         *  was not rewritten at all (not even with equal values). */
        val dataUpdates = mutableListOf<Long>()

        fun reset() {
            dataUpdates.clear()
            contacts.clear()
            data.clear()
            rawContacts.clear()
            groups.clear()
            nextRawContactId = 1000L
            nextDataId = 1000L
            nextGroupId = 1000L
            failWrites = false
        }

        /** A GroupMembership Data row putting [rawContactId] (and so its contact) in [groupId]. */
        fun putMembership(id: Long, contactId: Long, rawContactId: Long, groupId: Long) {
            putData(
                id, contactId, rawContactId, CDK.GroupMembership.CONTENT_ITEM_TYPE,
                ContentValues().apply { put(CDK.GroupMembership.GROUP_ROW_ID, groupId) },
            )
        }

        /** Raw contact ids carrying a membership row for [groupId] — for asserting on writes. */
        fun memberRawContactIds(groupId: Long): Set<Long> =
            data.values.filter {
                it.getAsString(ContactsContract.Data.MIMETYPE) == CDK.GroupMembership.CONTENT_ITEM_TYPE &&
                    it.getAsLong(CDK.GroupMembership.GROUP_ROW_ID) == groupId
            }.map { it.getAsLong(ContactsContract.Data.RAW_CONTACT_ID) }.toSet()

        fun isStarred(contactId: Long): Boolean =
            contacts[contactId]?.getAsInteger(ContactsContract.Contacts.STARRED) == 1

        /** The aggregate's SEND_TO_VOICEMAIL, computed like ContactsProvider2: on only when the
         *  contact has at least one non-deleted raw contact and all of them have it. A contact
         *  without raw contacts in the fake falls back to its own stored value. */
        fun isSendToVoicemail(contactId: Long): Boolean {
            val raws = rawContacts.values.filter {
                it.getAsLong(ContactsContract.RawContacts.CONTACT_ID) == contactId &&
                    it.getAsInteger(ContactsContract.RawContacts.DELETED) != 1
            }
            return if (raws.isEmpty()) {
                contacts[contactId]?.getAsInteger(ContactsContract.Contacts.SEND_TO_VOICEMAIL) == 1
            } else {
                raws.all { it.getAsInteger(ContactsContract.RawContacts.SEND_TO_VOICEMAIL) == 1 }
            }
        }

        /** One raw contact's own SEND_TO_VOICEMAIL flag. */
        fun rawSendToVoicemail(rawId: Long): Boolean =
            rawContacts[rawId]?.getAsInteger(ContactsContract.RawContacts.SEND_TO_VOICEMAIL) == 1

        private fun contactColumn(id: Long, values: ContentValues, col: String): Any? = when (col) {
            ContactsContract.Contacts._ID -> id
            ContactsContract.Contacts.SEND_TO_VOICEMAIL -> if (isSendToVoicemail(id)) 1 else 0
            else -> values.get(col)
        }

        fun putContact(
            id: Long,
            displayName: String,
            starred: Boolean = false,
            lookupKey: String = "lookup$id",
            photoThumbnailUri: String? = null,
            sortKey: String? = null,
            sendToVoicemail: Boolean = false,
        ) {
            contacts[id] = ContentValues().apply {
                put(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, displayName)
                put(ContactsContract.Contacts.SORT_KEY_PRIMARY, sortKey ?: displayName)
                put(ContactsContract.Contacts.STARRED, if (starred) 1 else 0)
                put(ContactsContract.Contacts.SEND_TO_VOICEMAIL, if (sendToVoicemail) 1 else 0)
                put(ContactsContract.Contacts.LOOKUP_KEY, lookupKey)
                put(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI, photoThumbnailUri)
            }
        }

        fun putData(id: Long, contactId: Long, rawContactId: Long, mimetype: String, values: ContentValues) {
            data[id] = ContentValues(values).apply {
                put(ContactsContract.Data.MIMETYPE, mimetype)
                put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                put(CDK.Phone.CONTACT_ID, contactId) // same underlying column as Data.CONTACT_ID
            }
        }

        fun putRawContact(
            id: Long,
            contactId: Long,
            accountName: String?,
            accountType: String?,
            dirty: Boolean = false,
            deleted: Boolean = false,
            sourceId: String? = null,
            sendToVoicemail: Boolean = false,
            readOnly: Boolean = false,
        ) {
            rawContacts[id] = ContentValues().apply {
                put(ContactsContract.RawContacts.CONTACT_ID, contactId)
                put(ContactsContract.RawContacts.ACCOUNT_NAME, accountName)
                put(ContactsContract.RawContacts.ACCOUNT_TYPE, accountType)
                put(ContactsContract.RawContacts.DIRTY, if (dirty) 1 else 0)
                put(ContactsContract.RawContacts.DELETED, if (deleted) 1 else 0)
                put(ContactsContract.RawContacts.SOURCE_ID, sourceId)
                put(ContactsContract.RawContacts.SEND_TO_VOICEMAIL, if (sendToVoicemail) 1 else 0)
                put(ContactsContract.RawContacts.RAW_CONTACT_IS_READ_ONLY, if (readOnly) 1 else 0)
            }
        }

        fun putGroup(id: Long, title: String?, accountName: String, accountType: String, deleted: Boolean = false) {
            groups[id] = ContentValues().apply {
                put(ContactsContract.Groups.TITLE, title)
                put(ContactsContract.Groups.ACCOUNT_NAME, accountName)
                put(ContactsContract.Groups.ACCOUNT_TYPE, accountType)
                put(ContactsContract.Groups.DELETED, if (deleted) 1 else 0)
            }
        }
    }

    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(AUTHORITY, "contacts", CONTACTS)
        addURI(AUTHORITY, "contacts/#", CONTACTS_ID)
        addURI(AUTHORITY, "data", DATA)
        addURI(AUTHORITY, "data/phones", DATA_PHONES)
        addURI(AUTHORITY, "raw_contacts", RAW_CONTACTS)
        addURI(AUTHORITY, "groups", GROUPS)
        addURI(AUTHORITY, "groups/#", GROUPS_ID)
        addURI(AUTHORITY, "phone_lookup/*", PHONE_LOOKUP)
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = when (matcher.match(uri)) {
        CONTACTS -> {
            val cols = projection ?: arrayOf(
                ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.STARRED,
                ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
            )
            val cursor = MatrixCursor(cols)
            // ContactGroups.queryStarredContactIds' "starred=1" is the only selection honored
            // here; every other caller wants the whole list.
            val starredOnly = selection == "${ContactsContract.Contacts.STARRED}=1"
            val voicemailOnly = selection == VOICEMAIL_SELECTION
            // queryContactDetail's "_id=?" single-contact lookup.
            val byId = selection == "${ContactsContract.Contacts._ID}=?"
            contacts.entries.sortedBy { it.key }
                .filter { (_, v) -> !starredOnly || v.getAsInteger(ContactsContract.Contacts.STARRED) == 1 }
                .filter { (id, _) -> !voicemailOnly || isSendToVoicemail(id) }
                .filter { (id, _) -> !byId || id == selectionArgs?.firstOrNull()?.toLongOrNull() }
                .forEach { (id, values) ->
                cursor.addRow(cols.map { col -> contactColumn(id, values, col) })
            }
            cursor
        }
        // Resolves against the Phone rows already in [data], so a test sets a contact up exactly
        // once (putContact + putData with a Phone mimetype) and both the bulk phone query and
        // PhoneLookup see it — rather than a second, separately-maintained lookup table that
        // could disagree with the address book it claims to describe.
        PHONE_LOOKUP -> {
            val cols = projection ?: arrayOf(
                ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.DISPLAY_NAME,
            )
            val cursor = MatrixCursor(cols)
            val queried = significantDigits(Uri.decode(uri.lastPathSegment.orEmpty()))
            val match = data.values
                .filter { it.getAsString(ContactsContract.Data.MIMETYPE) == CDK.Phone.CONTENT_ITEM_TYPE }
                .firstOrNull { row ->
                    val stored = significantDigits(row.getAsString(CDK.Phone.NUMBER).orEmpty())
                    queried.isNotEmpty() && stored == queried
                }
                ?.getAsLong(CDK.Phone.CONTACT_ID)
            if (match != null) {
                cursor.addRow(
                    cols.map { col ->
                        when (col) {
                            ContactsContract.PhoneLookup._ID -> match
                            ContactsContract.PhoneLookup.DISPLAY_NAME ->
                                contacts[match]?.getAsString(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                            else -> null
                        }
                    },
                )
            }
            cursor
        }
        CONTACTS_ID -> {
            val id = ContentUris.parseId(uri)
            val values = contacts[id]
            val cols = projection ?: arrayOf(
                ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.STARRED, ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
            )
            val cursor = MatrixCursor(cols)
            if (values != null) cursor.addRow(cols.map { col -> contactColumn(id, values, col) })
            cursor
        }
        DATA -> {
            // Three shapes share the DATA URI: queryContactIdsInGroups' "mimetype=? AND
            // group_id IN (?,?,...)", queryPrimaryDisplayNames' plain "mimetype=?", and
            // queryContactDetail's own "contact_id=?" -- distinguish by the selection string
            // itself, same exact-match convention as RAW_CONTACTS below.
            val matching = when {
                selection == CONTACT_MEMBERSHIP_SELECTION -> {
                    val contactId = selectionArgs?.getOrNull(0)?.toLongOrNull()
                    val mimetype = selectionArgs?.getOrNull(1)
                    data.entries.filter { (_, v) ->
                        v.getAsLong(CDK.Phone.CONTACT_ID) == contactId && v.getAsString(ContactsContract.Data.MIMETYPE) == mimetype
                    }
                }
                selection == "${ContactsContract.Data.MIMETYPE}=?" -> {
                    val mimetype = selectionArgs?.getOrNull(0)
                    data.entries.filter { (_, v) -> v.getAsString(ContactsContract.Data.MIMETYPE) == mimetype }
                }
                selection?.startsWith(ContactsContract.Data.MIMETYPE) == true -> {
                    val mimetype = selectionArgs?.getOrNull(0)
                    val groupIds = selectionArgs.orEmpty().drop(1).mapNotNull { it.toLongOrNull() }.toSet()
                    data.entries.filter { (_, v) ->
                        v.getAsString(ContactsContract.Data.MIMETYPE) == mimetype &&
                            v.getAsLong(CDK.GroupMembership.GROUP_ROW_ID) in groupIds
                    }
                }
                else -> {
                    val contactId = selectionArgs?.firstOrNull()?.toLongOrNull()
                    data.entries.filter { (_, v) -> contactId == null || v.getAsLong(CDK.Phone.CONTACT_ID) == contactId }
                }
            }
            val cols = projection ?: (
                listOf(ContactsContract.Data._ID, ContactsContract.Data.RAW_CONTACT_ID, ContactsContract.Data.MIMETYPE) +
                    matching.flatMap { it.value.keySet() }.distinct()
                ).distinct().toTypedArray()
            val cursor = MatrixCursor(cols)
            matching.forEach { (id, values) ->
                cursor.addRow(cols.map { col -> if (col == ContactsContract.Data._ID) id else values.get(col) })
            }
            cursor
        }
        DATA_PHONES -> {
            val matching = data.entries.filter { (_, v) -> v.getAsString(ContactsContract.Data.MIMETYPE) == CDK.Phone.CONTENT_ITEM_TYPE }
            val cols = projection ?: arrayOf(CDK.Phone.CONTACT_ID, CDK.Phone.NUMBER)
            val cursor = MatrixCursor(cols)
            // DISPLAY_NAME on the real phones view is the owning contact's own name (a join), not
            // a column of the data row — served the same way here.
            matching.forEach { (_, values) ->
                cursor.addRow(
                    cols.map { col ->
                        if (col == CDK.Phone.DISPLAY_NAME) {
                            contacts[values.getAsLong(CDK.Phone.CONTACT_ID)]?.getAsString(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                        } else {
                            values.get(col)
                        }
                    },
                )
            }
            cursor
        }
        RAW_CONTACTS -> {
            // Both real query shapes this fake needs to support share one filter value but
            // differ in *which* column it filters — exact-match the selection string (rather
            // than substring-check it) since "contact_id" itself contains "_id".
            val filterValue = selectionArgs?.firstOrNull()?.toLongOrNull()
            val matching = when {
                selection == VOICEMAIL_RAW_CONTACTS_SELECTION -> rawContacts.entries.filter { (_, v) ->
                    v.getAsInteger(ContactsContract.RawContacts.SEND_TO_VOICEMAIL) == 1 &&
                        v.getAsInteger(ContactsContract.RawContacts.DELETED) != 1 &&
                        v.getAsInteger(ContactsContract.RawContacts.RAW_CONTACT_IS_READ_ONLY) != 1
                }
                selection == ACCOUNT_RAW_CONTACTS_SELECTION -> rawContacts.entries.filter { (_, v) ->
                    v.getAsString(ContactsContract.RawContacts.ACCOUNT_NAME) == selectionArgs?.getOrNull(0) &&
                        v.getAsString(ContactsContract.RawContacts.ACCOUNT_TYPE) == selectionArgs?.getOrNull(1) &&
                        v.getAsInteger(ContactsContract.RawContacts.DELETED) != 1
                }
                filterValue == null -> rawContacts.entries
                selection == "${ContactsContract.RawContacts._ID}=?" -> rawContacts.entries.filter { it.key == filterValue }
                else -> rawContacts.entries.filter { (_, v) -> v.getAsLong(ContactsContract.RawContacts.CONTACT_ID) == filterValue }
            }
            val cols = projection ?: arrayOf(
                ContactsContract.RawContacts._ID, ContactsContract.RawContacts.ACCOUNT_NAME, ContactsContract.RawContacts.ACCOUNT_TYPE,
                ContactsContract.RawContacts.CONTACT_ID,
            )
            val cursor = MatrixCursor(cols)
            matching.forEach { (id, values) ->
                cursor.addRow(cols.map { col -> if (col == ContactsContract.RawContacts._ID) id else values.get(col) })
            }
            cursor
        }
        GROUPS -> {
            // queryGroups' own hardcoded selection ("deleted=0 AND title IS NOT NULL") is the
            // only shape this fake ever needs to serve, so it's applied directly rather than
            // actually parsed.
            // ...plus ContactGroups.queryGroup's single-group lookup, which keeps untitled groups.
            val byId = if (selection == GROUP_BY_ID_SELECTION) selectionArgs?.firstOrNull()?.toLongOrNull() else null
            val matching = groups.entries.filter { (id, v) ->
                v.getAsInteger(ContactsContract.Groups.DELETED) == 0 &&
                    if (byId != null) id == byId else v.getAsString(ContactsContract.Groups.TITLE) != null
            }
            val cols = projection ?: arrayOf(
                ContactsContract.Groups._ID, ContactsContract.Groups.TITLE,
                ContactsContract.Groups.ACCOUNT_NAME, ContactsContract.Groups.ACCOUNT_TYPE,
            )
            val cursor = MatrixCursor(cols)
            matching.forEach { (id, values) ->
                cursor.addRow(cols.map { col -> if (col == ContactsContract.Groups._ID) id else values.get(col) })
            }
            cursor
        }
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = if (failWrites) error("failWrites") else when (matcher.match(uri)) {
        GROUPS -> {
            val id = nextGroupId++
            groups[id] = ContentValues(values).apply {
                if (!containsKey(ContactsContract.Groups.DELETED)) put(ContactsContract.Groups.DELETED, 0)
            }
            ContentUris.withAppendedId(ContactsContract.Groups.CONTENT_URI, id)
        }
        RAW_CONTACTS -> {
            val rawId = nextRawContactId++
            // No real aggregation logic in this fake — each new raw contact becomes its own
            // aggregate contact 1:1, which is enough for testing the write path's plumbing
            // (back-references, batching) without reimplementing ContactsProvider2's matching.
            val contactId = rawId
            val row = ContentValues(values)
            row.put(ContactsContract.RawContacts.CONTACT_ID, contactId)
            rawContacts[rawId] = row
            contacts[contactId] = ContentValues().apply {
                put(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, "(no name)")
                put(ContactsContract.Contacts.STARRED, 0)
                put(ContactsContract.Contacts.LOOKUP_KEY, "lookup$contactId")
            }
            ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawId)
        }
        DATA -> {
            val id = nextDataId++
            val row = ContentValues(values)
            val rawContactId = row.getAsLong(ContactsContract.Data.RAW_CONTACT_ID)
            val contactId = rawContacts[rawContactId]?.getAsLong(ContactsContract.RawContacts.CONTACT_ID)
            if (contactId != null) row.put(CDK.Phone.CONTACT_ID, contactId)
            data[id] = row
            ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, id)
        }
        else -> null
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        if (failWrites) error("failWrites") else when (matcher.match(uri)) {
            CONTACTS_ID -> {
                val id = ContentUris.parseId(uri)
                val row = contacts[id]
                if (row == null) {
                    0
                } else {
                    row.putAll(values ?: ContentValues())
                    // Like the real provider (updateContactOptions), the aggregate's flag is
                    // written through to every one of its raw contacts that isn't read-only.
                    values?.getAsInteger(ContactsContract.Contacts.SEND_TO_VOICEMAIL)?.let { flag ->
                        rawContacts.values
                            .filter { it.getAsLong(ContactsContract.RawContacts.CONTACT_ID) == id }
                            .filter { it.getAsInteger(ContactsContract.RawContacts.RAW_CONTACT_IS_READ_ONLY) != 1 }
                            .forEach { it.put(ContactsContract.RawContacts.SEND_TO_VOICEMAIL, flag) }
                    }
                    1
                }
            }
            GROUPS_ID -> {
                val row = groups[ContentUris.parseId(uri)]?.takeIf { it.getAsInteger(ContactsContract.Groups.DELETED) == 0 }
                if (row == null) {
                    0
                } else {
                    row.putAll(values ?: ContentValues())
                    1
                }
            }
            DATA -> {
                val id = selectionArgs?.firstOrNull()?.toLongOrNull()
                val row = data[id]
                if (row == null) {
                    0
                } else {
                    dataUpdates.add(id!!)
                    row.putAll(values ?: ContentValues())
                    1
                }
            }
            else -> 0
        }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        if (failWrites) error("failWrites") else when (matcher.match(uri)) {
            GROUPS_ID -> {
                val id = ContentUris.parseId(uri)
                val row = groups[id]?.takeIf { it.getAsInteger(ContactsContract.Groups.DELETED) == 0 }
                if (row == null) {
                    0
                } else {
                    row.put(ContactsContract.Groups.DELETED, 1)
                    data.entries.filter { (_, v) ->
                        v.getAsString(ContactsContract.Data.MIMETYPE) == CDK.GroupMembership.CONTENT_ITEM_TYPE &&
                            v.getAsLong(CDK.GroupMembership.GROUP_ROW_ID) == id
                    }.map { it.key }.forEach { data.remove(it) }
                    1
                }
            }
            DATA -> if (selection == MEMBERSHIP_ROW_SELECTION) {
                // ContactGroups' "this raw contact's membership row for this group".
                val (mimetype, rawId, groupId) = selectionArgs!!.toList()
                val toRemove = data.entries.filter { (_, v) ->
                    v.getAsString(ContactsContract.Data.MIMETYPE) == mimetype &&
                        v.getAsLong(ContactsContract.Data.RAW_CONTACT_ID) == rawId.toLong() &&
                        v.getAsLong(CDK.GroupMembership.GROUP_ROW_ID) == groupId.toLong()
                }.map { it.key }
                toRemove.forEach { data.remove(it) }
                toRemove.size
            } else {
                val id = selectionArgs?.firstOrNull()?.toLongOrNull()
                if (id != null && data.remove(id) != null) 1 else 0
            }
            RAW_CONTACTS -> {
                val contactId = selectionArgs?.firstOrNull()?.toLongOrNull()
                val toRemove = rawContacts.entries.filter { (_, v) -> v.getAsLong(ContactsContract.RawContacts.CONTACT_ID) == contactId }.map { it.key }
                toRemove.forEach { rawContacts.remove(it) }
                data.entries.filter { (_, v) -> v.getAsLong(ContactsContract.Data.RAW_CONTACT_ID) in toRemove }.map { it.key }.forEach { data.remove(it) }
                if (contactId != null) contacts.remove(contactId)
                toRemove.size
            }
            else -> 0
        }

    override fun getType(uri: Uri): String? = null

    /** The trailing digits of a number, ignoring spaces, `+`, parens and country prefix — the
     *  normalization [PHONE_LOOKUP_SIGNIFICANT_DIGITS] describes. A number with fewer digits than
     *  that (a short code like `343`) is compared whole, since there is nothing to truncate. */
    private fun significantDigits(number: String): String {
        val digits = number.filter { it.isDigit() }
        return if (digits.length <= PHONE_LOOKUP_SIGNIFICANT_DIGITS) digits else digits.takeLast(PHONE_LOOKUP_SIGNIFICANT_DIGITS)
    }
}
