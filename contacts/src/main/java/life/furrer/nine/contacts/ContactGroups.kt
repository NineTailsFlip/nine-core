package life.furrer.nine.contacts

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds as CDK
import life.furrer.nine.contacts.ContactStore.ContactGroup
import life.furrer.nine.contacts.ContactStore.WritableAccount
import life.furrer.nine.core.util.firstRow
import life.furrer.nine.core.util.stringOr

/** Who is in one group, and who *could* be — read in one go so a caller deciding many
 *  memberships at once (favorites line-up, a membership editor) never queries per contact.
 *
 *  A group belongs to exactly one account, and the provider only lets a raw contact of that same
 *  account carry its GroupMembership row (a CardDAV adapter like DAVx5 can only ever upload a
 *  membership on a vCard it owns). So membership is a raw-contact fact, and an aggregate contact
 *  is "joinable" only through a raw contact of its own in the group's account — a local-only
 *  contact, a WhatsApp/Signal mirror or a contact from another address book can't be a member,
 *  however it's linked. */
data class GroupMembership(
    val group: ContactGroup,
    /** Aggregate contact id → its non-deleted raw contact ids in [group]'s account. A contact
     *  missing here is not joinable. */
    val accountRawContacts: Map<Long, Set<Long>>,
    /** Raw contact ids (all within the group's account) carrying a membership row for [group]. */
    val memberRawContactIds: Set<Long>,
    /** `RawContacts.SOURCE_ID` of each raw contact in [accountRawContacts] — the sync adapter's
     *  own id for it (DAVx5: the vCard's file name), null until first uploaded. Lets a caller
     *  that remembers raw contact ids tell "still the same vCard" from a reused row id. */
    val sourceIds: Map<Long, String?> = emptyMap(),
) {
    val memberContactIds: Set<Long> by lazy {
        accountRawContacts.filterValues { raws -> raws.any { it in memberRawContactIds } }.keys
    }

    fun isJoinable(contactId: Long): Boolean = accountRawContacts[contactId].orEmpty().isNotEmpty()

    fun isMember(contactId: Long): Boolean = contactId in memberContactIds
}

/** Result of adding/removing one contact to/from a group. Both directions are idempotent:
 *  asking for what's already true is [UNCHANGED], not an error. */
enum class MembershipResult { CHANGED, UNCHANGED, NOT_JOINABLE, FAILED }

/** Generic contact-group primitives (create/rename/delete, membership, member counts, starred,
 *  send-to-voicemail) shared by C9's [GroupSync]s, its group management and the shared groups screens. Everything writes through the normal (non-sync-adapter) provider
 *  API, so DIRTY is set and the owning sync adapter uploads the change. Reads need READ_CONTACTS,
 *  writes additionally WRITE_CONTACTS; without them reads come back empty and writes fail
 *  closed, the same convention as [ContactStore]. */
object ContactGroups {

    /** Creates a titled, visible group in [account]. Null on failure or missing permission. */
    fun createGroup(context: Context, account: WritableAccount, title: String): ContactGroup? {
        if (!canWrite(context)) return null
        val values = ContentValues().apply {
            put(ContactsContract.Groups.TITLE, title)
            put(ContactsContract.Groups.ACCOUNT_NAME, account.name)
            put(ContactsContract.Groups.ACCOUNT_TYPE, account.type)
            put(ContactsContract.Groups.GROUP_VISIBLE, 1)
        }
        val uri = try {
            context.contentResolver.insert(ContactsContract.Groups.CONTENT_URI, values)
        } catch (e: Exception) {
            null
        } ?: return null
        return ContactGroup(ContentUris.parseId(uri), title, account.name, account.type)
    }

    /** The group as it is now (current title), or null if it no longer exists — deleted, or
     *  marked deleted pending sync. */
    fun queryGroup(context: Context, groupId: Long): ContactGroup? {
        if (!ContactStore.hasPermission(context)) return null
        return context.contentResolver.query(
            ContactsContract.Groups.CONTENT_URI,
            arrayOf(
                ContactsContract.Groups._ID, ContactsContract.Groups.TITLE,
                ContactsContract.Groups.ACCOUNT_NAME, ContactsContract.Groups.ACCOUNT_TYPE,
            ),
            GROUP_BY_ID_SELECTION,
            arrayOf(groupId.toString()),
            null,
        ).firstRow { cursor ->
            ContactGroup(
                id = groupId,
                title = cursor.stringOr(ContactsContract.Groups.TITLE, ""),
                accountName = cursor.stringOr(ContactsContract.Groups.ACCOUNT_NAME, ""),
                accountType = cursor.stringOr(ContactsContract.Groups.ACCOUNT_TYPE, ""),
            )
        }
    }

    /** Renames [group] in place. Non-sync-adapter, so the group row turns DIRTY and DAVx5 uploads
     *  the new name (in "groups are separate vCards" mode, the group vCard's FN). A blank [title]
     *  is refused rather than written: [ContactStore.queryGroups] hides untitled groups as system
     *  bookkeeping, so blanking one would make it vanish from every screen. False on failure,
     *  missing permission, or a group that no longer exists. */
    fun renameGroup(context: Context, group: ContactGroup, title: String): Boolean {
        val trimmed = title.trim()
        if (trimmed.isEmpty() || !canWrite(context)) return false
        val values = ContentValues().apply { put(ContactsContract.Groups.TITLE, trimmed) }
        return try {
            context.contentResolver.update(
                ContentUris.withAppendedId(ContactsContract.Groups.CONTENT_URI, group.id), values, null, null,
            ) > 0
        } catch (e: Exception) {
            false
        }
    }

    /** Deletes [group] the way a user's delete should go: *not* as the sync adapter, so the
     *  provider only marks the row DELETED (it disappears from every query here at once) and
     *  drops its membership rows, and DAVx5 removes the group on the server at its next sync
     *  before purging the row for good. The member contacts themselves are untouched — a group is
     *  a label, deleting it never deletes people. A caller that had chosen this group for
     *  [GroupSync] will see it as missing afterwards. False on failure or missing permission. */
    fun deleteGroup(context: Context, group: ContactGroup): Boolean {
        if (!canWrite(context)) return false
        return try {
            context.contentResolver.delete(ContentUris.withAppendedId(ContactsContract.Groups.CONTENT_URI, group.id), null, null) > 0
        } catch (e: Exception) {
            false
        }
    }

    /** Every aggregate contact's group ids, from one query over all GroupMembership rows — the
     *  one read behind the groups list's member counts and the contacts list's "Show in contacts
     *  list" filter, so neither queries per group. A row with no CONTACT_ID is skipped: that is a
     *  raw contact the user deleted (the provider detaches it from its aggregate while it waits
     *  for the sync adapter to purge it), and it shows up nowhere else either. Ids of untitled or
     *  deleted groups can appear here; callers match against [ContactStore.queryGroups]. */
    fun queryContactGroupIds(context: Context): Map<Long, Set<Long>> {
        if (!ContactStore.hasPermission(context)) return emptyMap()
        val result = mutableMapOf<Long, MutableSet<Long>>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.CONTACT_ID, CDK.GroupMembership.GROUP_ROW_ID),
            MIMETYPE_SELECTION,
            arrayOf(CDK.GroupMembership.CONTENT_ITEM_TYPE),
            null,
        )?.use { cursor ->
            val contactIndex = cursor.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
            val groupIndex = cursor.getColumnIndexOrThrow(CDK.GroupMembership.GROUP_ROW_ID)
            while (cursor.moveToNext()) {
                if (cursor.isNull(contactIndex) || cursor.isNull(groupIndex)) continue
                result.getOrPut(cursor.getLong(contactIndex)) { mutableSetOf() }.add(cursor.getLong(groupIndex))
            }
        }
        return result
    }

    /** Group id → number of distinct contacts in it, for every group with at least one member. A
     *  contact counts once even when two of its raw contacts both carry the membership. */
    fun queryMemberCounts(context: Context): Map<Long, Int> = memberCounts(queryContactGroupIds(context))

    internal fun memberCounts(contactGroupIds: Map<Long, Set<Long>>): Map<Long, Int> {
        val counts = mutableMapOf<Long, Int>()
        contactGroupIds.values.forEach { groupIds -> groupIds.forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        return counts
    }

    /** The visible groups (titled, not deleted — [ContactStore.queryGroups]' set) [contactId]
     *  belongs to through any of its raw contacts, sorted by title. */
    fun queryGroupsOfContact(context: Context, contactId: Long): List<ContactGroup> {
        if (!ContactStore.hasPermission(context)) return emptyList()
        val groupIds = mutableSetOf<Long>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(CDK.GroupMembership.GROUP_ROW_ID),
            CONTACT_MEMBERSHIP_SELECTION,
            arrayOf(contactId.toString(), CDK.GroupMembership.CONTENT_ITEM_TYPE),
            null,
        )?.use { cursor ->
            val groupIndex = cursor.getColumnIndexOrThrow(CDK.GroupMembership.GROUP_ROW_ID)
            while (cursor.moveToNext()) if (!cursor.isNull(groupIndex)) groupIds.add(cursor.getLong(groupIndex))
        }
        if (groupIds.isEmpty()) return emptyList()
        return ContactStore.queryGroups(context).filter { it.id in groupIds }.sortedBy { it.title.lowercase() }
    }

    /** Aggregate ids of every starred contact. */
    fun queryStarredContactIds(context: Context): Set<Long> {
        if (!ContactStore.hasPermission(context)) return emptySet()
        val result = mutableSetOf<Long>()
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID),
            STARRED_SELECTION,
            null,
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            while (cursor.moveToNext()) result.add(cursor.getLong(idIndex))
        }
        return result
    }

    /** Aggregate ids of every contact Android itself sends to voicemail — only those whose raw
     *  contacts *all* carry SEND_TO_VOICEMAIL, see [VoicemailProperty]. */
    fun queryVoicemailContactIds(context: Context): Set<Long> {
        if (!ContactStore.hasPermission(context)) return emptySet()
        val result = mutableSetOf<Long>()
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID),
            VOICEMAIL_SELECTION,
            null,
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            while (cursor.moveToNext()) result.add(cursor.getLong(idIndex))
        }
        return result
    }

    /** Aggregate ids of every contact with SEND_TO_VOICEMAIL on at least one non-deleted,
     *  writable raw contact, whether or not the aggregate has it. */
    fun queryVoicemailRawContactOwners(context: Context): Set<Long> {
        if (!ContactStore.hasPermission(context)) return emptySet()
        val result = mutableSetOf<Long>()
        context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts.CONTACT_ID),
            VOICEMAIL_RAW_CONTACTS_SELECTION,
            null,
            null,
        )?.use { cursor ->
            val contactIdIndex = cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.CONTACT_ID)
            while (cursor.moveToNext()) if (!cursor.isNull(contactIdIndex)) result.add(cursor.getLong(contactIdIndex))
        }
        return result
    }

    /** Two queries regardless of address book size: every raw contact in the group's account,
     *  then every membership row of the group. Membership rows on raw contacts outside the
     *  account (or marked deleted) are ignored — they can't be synced as members anyway. */
    fun queryMembership(context: Context, group: ContactGroup): GroupMembership {
        if (!ContactStore.hasPermission(context)) return GroupMembership(group, emptyMap(), emptySet())

        val accountRawContacts = mutableMapOf<Long, MutableSet<Long>>()
        val rawToContact = mutableMapOf<Long, Long>()
        val sourceIds = mutableMapOf<Long, String?>()
        context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.CONTACT_ID, ContactsContract.RawContacts.SOURCE_ID),
            ACCOUNT_RAW_CONTACTS_SELECTION,
            arrayOf(group.accountName, group.accountType),
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.RawContacts._ID)
            val contactIdIndex = cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.CONTACT_ID)
            val sourceIdIndex = cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.SOURCE_ID)
            while (cursor.moveToNext()) {
                // A raw contact the aggregator hasn't placed yet has no CONTACT_ID; it can't be
                // addressed as a contact until it has one.
                if (cursor.isNull(contactIdIndex)) continue
                val rawId = cursor.getLong(idIndex)
                val contactId = cursor.getLong(contactIdIndex)
                accountRawContacts.getOrPut(contactId) { mutableSetOf() }.add(rawId)
                rawToContact[rawId] = contactId
                sourceIds[rawId] = cursor.getString(sourceIdIndex)
            }
        }

        val members = mutableSetOf<Long>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.RAW_CONTACT_ID),
            "${ContactsContract.Data.MIMETYPE}=? AND ${CDK.GroupMembership.GROUP_ROW_ID} IN (?)",
            arrayOf(CDK.GroupMembership.CONTENT_ITEM_TYPE, group.id.toString()),
            null,
        )?.use { cursor ->
            val rawIndex = cursor.getColumnIndexOrThrow(ContactsContract.Data.RAW_CONTACT_ID)
            while (cursor.moveToNext()) {
                val rawId = cursor.getLong(rawIndex)
                if (rawId in rawToContact) members.add(rawId)
            }
        }
        return GroupMembership(group, accountRawContacts, members, sourceIds)
    }

    fun isJoinable(context: Context, contactId: Long, group: ContactGroup): Boolean =
        queryMembership(context, group).isJoinable(contactId)

    fun addToGroup(context: Context, contactId: Long, group: ContactGroup): MembershipResult =
        setMember(context, contactId, group, member = true)

    fun removeFromGroup(context: Context, contactId: Long, group: ContactGroup): MembershipResult =
        setMember(context, contactId, group, member = false)

    private fun setMember(context: Context, contactId: Long, group: ContactGroup, member: Boolean): MembershipResult {
        if (!canWrite(context)) return MembershipResult.FAILED
        val membership = queryMembership(context, group)
        if (!membership.isJoinable(contactId)) return MembershipResult.NOT_JOINABLE
        val ops = if (member) addOps(membership, setOf(contactId)) else removeOps(membership, setOf(contactId))
        if (ops.isEmpty()) return MembershipResult.UNCHANGED
        return if (applyBatch(context, ops)) MembershipResult.CHANGED else MembershipResult.FAILED
    }

    /** Several membership changes to one group in one atomic batch. Returns the contacts in [add]
     *  that couldn't be added because they aren't joinable (they are skipped, not failed), or
     *  null if the batch itself failed or permission is missing. Removing a non-joinable contact
     *  is trivially already true and not reported. */
    fun updateMembership(context: Context, group: ContactGroup, add: Set<Long>, remove: Set<Long>): Set<Long>? {
        if (!canWrite(context)) return null
        val membership = queryMembership(context, group)
        val notJoinable = add.filterNot { membership.isJoinable(it) }.toSet()
        val ops = addOps(membership, add - notJoinable) + removeOps(membership, remove)
        if (ops.isNotEmpty() && !applyBatch(context, ops)) return null
        return notJoinable
    }

    /** One membership row per contact not already a member, on its lowest-id raw contact in the
     *  account — the same "lowest id is the real synced source" choice [ContactStore] makes for
     *  names. Skips non-joinable contacts silently; callers check [GroupMembership.isJoinable]. */
    internal fun addOps(membership: GroupMembership, contactIds: Set<Long>): List<ContentProviderOperation> =
        contactIds.filterNot { membership.isMember(it) }.mapNotNull { contactId ->
            val rawId = membership.accountRawContacts[contactId]?.minOrNull() ?: return@mapNotNull null
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                .withValue(ContactsContract.Data.MIMETYPE, CDK.GroupMembership.CONTENT_ITEM_TYPE)
                .withValue(CDK.GroupMembership.GROUP_ROW_ID, membership.group.id)
                .build()
        }

    /** Deletes the membership row from *every* raw contact of the contact that carries one — a
     *  contact aggregated from two vCards of the same address book can be a member twice, and
     *  leaving either row would leave it in the group. */
    internal fun removeOps(membership: GroupMembership, contactIds: Set<Long>): List<ContentProviderOperation> =
        contactIds.flatMap { contactId ->
            membership.accountRawContacts[contactId].orEmpty()
                .filter { it in membership.memberRawContactIds }
                .map { rawId ->
                    ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                        .withSelection(
                            MEMBERSHIP_ROW_SELECTION,
                            arrayOf(CDK.GroupMembership.CONTENT_ITEM_TYPE, rawId.toString(), membership.group.id.toString()),
                        )
                        .build()
                }
        }

    /** Setting STARRED on the aggregate; the provider propagates it to every raw contact. */
    internal fun starOp(contactId: Long, starred: Boolean): ContentProviderOperation =
        ContentProviderOperation.newUpdate(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId))
            .withValue(ContactsContract.Contacts.STARRED, if (starred) 1 else 0)
            .build()

    /** Setting SEND_TO_VOICEMAIL on the aggregate; the provider propagates it to every raw
     *  contact, which is also what repairs one whose aggregate lost it. */
    internal fun voicemailOp(contactId: Long, on: Boolean): ContentProviderOperation =
        ContentProviderOperation.newUpdate(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId))
            .withValue(ContactsContract.Contacts.SEND_TO_VOICEMAIL, if (on) 1 else 0)
            .build()

    internal fun applyBatch(context: Context, ops: List<ContentProviderOperation>): Boolean {
        if (ops.isEmpty()) return true
        return try {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(ops))
            true
        } catch (e: Exception) {
            false
        }
    }

    internal fun canWrite(context: Context): Boolean =
        ContactStore.hasPermission(context) && ContactStore.hasWritePermission(context)

    internal const val GROUP_BY_ID_SELECTION = "${ContactsContract.Groups._ID}=? AND ${ContactsContract.Groups.DELETED}=0"
    internal const val MIMETYPE_SELECTION = "${ContactsContract.Data.MIMETYPE}=?"
    internal const val CONTACT_MEMBERSHIP_SELECTION =
        "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?"
    internal const val STARRED_SELECTION = "${ContactsContract.Contacts.STARRED}=1"
    internal const val VOICEMAIL_SELECTION = "${ContactsContract.Contacts.SEND_TO_VOICEMAIL}=1"
    internal const val VOICEMAIL_RAW_CONTACTS_SELECTION =
        "${ContactsContract.RawContacts.SEND_TO_VOICEMAIL}=1 AND ${ContactsContract.RawContacts.DELETED}=0 AND " +
            "${ContactsContract.RawContacts.RAW_CONTACT_IS_READ_ONLY}=0"
    internal const val ACCOUNT_RAW_CONTACTS_SELECTION =
        "${ContactsContract.RawContacts.ACCOUNT_NAME}=? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND " +
            "${ContactsContract.RawContacts.DELETED}=0"
    internal const val MEMBERSHIP_ROW_SELECTION =
        "${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${CDK.GroupMembership.GROUP_ROW_ID}=?"
}
