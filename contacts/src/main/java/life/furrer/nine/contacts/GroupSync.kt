package life.furrer.nine.contacts

import android.content.ContentProviderOperation
import android.content.Context
import life.furrer.nine.contacts.ContactStore.ContactGroup
import life.furrer.nine.core.Nine
import life.furrer.nine.core.prefs.NinePrefs
import org.json.JSONArray
import org.json.JSONObject

/** A per-contact property that Android keeps on this phone only and a [GroupSync] carries to and
 *  from a CardDAV server as membership of one chosen group — DAVx5 syncs group membership, but
 *  none of these contact options. [StarredProperty] (favorites) and [VoicemailProperty] are the
 *  two there are. */
interface GroupBackedProperty {
    /** Names the stored group setting (`contacts_<key>_group`) and the baseline file
     *  (`<app>_<key>_sync`). Never change one: it would forget the user's choice. */
    val key: String

    /** Every contact that has the property, by aggregate id. */
    fun queryHolders(context: Context): Set<Long>

    /** Those of [holders] (just read by [queryHolders]) whose property isn't fully in effect and
     *  needs writing again — see [VoicemailProperty]. A [GroupSync] re-applies these on every
     *  line-up, group or not. */
    fun queryIncomplete(context: Context, holders: Set<Long>): Set<Long> = emptySet()

    /** Whether [queryIncomplete] can ever return anything, i.e. whether a line-up has work to
     *  do even without a chosen group. */
    val repairs: Boolean get() = false

    /** Gives [contactId] the property, or takes it away. */
    fun writeOp(contactId: Long, on: Boolean): ContentProviderOperation
}

/** Favorites: Android's STARRED, what every Nine screen shows as a favorite. Starred on the
 *  aggregate means starred on any raw contact, so nothing is ever half-applied. */
object StarredProperty : GroupBackedProperty {
    override val key = "favorites"
    override fun queryHolders(context: Context) = ContactGroups.queryStarredContactIds(context)
    override fun writeOp(contactId: Long, on: Boolean) = ContactGroups.starOp(contactId, on)
}

/** "Send calls to voicemail": Android's SEND_TO_VOICEMAIL, which Telecom applies itself by
 *  rejecting a matching incoming call before it rings — no dialer is involved.
 *
 *  Unlike STARRED, the aggregate only has it when *every* one of its raw contacts does. So a
 *  raw contact joining later (WhatsApp/Signal mirroring the contact, an address-book
 *  re-download) silently switches it off. A holder is therefore any contact with the flag on at
 *  least one raw contact, and one whose aggregate lost it is [queryIncomplete] — rewritten on the
 *  aggregate, which the provider propagates to every raw contact. Read as "the aggregate", a
 *  contact losing it that way would look like the user turning it off, and the line-up would
 *  take it out of the group.
 *
 *  A read-only raw contact doesn't count: the provider never writes the option to one, so its
 *  flag could never be cleared, and every "off" would come straight back as a repair. */
object VoicemailProperty : GroupBackedProperty {
    override val key = "voicemail"
    override val repairs = true
    override fun queryHolders(context: Context) = ContactGroups.queryVoicemailRawContactOwners(context)
    override fun queryIncomplete(context: Context, holders: Set<Long>): Set<Long> =
        if (holders.isEmpty()) emptySet() else holders - ContactGroups.queryVoicemailContactIds(context)
    override fun writeOp(contactId: Long, on: Boolean) = ContactGroups.voicemailOp(contactId, on)
}

/** Favorites: STARRED kept in step with the favorites group chosen in C9's settings. */
val FavoritesSync = GroupSync(StarredProperty)

/** Send-to-voicemail kept in step with its own chosen group. */
val VoicemailSync = GroupSync(VoicemailProperty)

/** Keeps a [GroupBackedProperty] and one chosen contact group in step, so the property travels
 *  to and from a CardDAV server through DAVx5.
 *
 *  The line-up is three-way against a baseline, the holders as of the last successful line-up:
 *  whichever side changed since then wins. Without a baseline (first run for a group) both sides
 *  are simply merged.
 *
 *  Only a contact with a raw contact in the group's account can be a member ("joinable", see
 *  [GroupMembership]). Everyone else — local-only contacts, messaging-app mirrors, other address
 *  books — keeps whatever the property says on this phone, never loses it for not being in the
 *  group, and never enters the baseline. Should such a contact become joinable later, it then
 *  looks like a fresh addition on the property side and joins the group, rather than looking
 *  like a member that was removed.
 *
 *  Baseline identity: the raw contact ids in the group's account, each paired with its SOURCE_ID.
 *  Not the aggregate contact `_ID` (changes whenever Android joins or splits contacts) and not
 *  the aggregate LOOKUP_KEY (also changes on join/split, and resolving one takes a query per
 *  key). The raw contact in the group's account is exactly what membership hangs off, so its id
 *  survives re-aggregation, and since only joinable contacts are in the baseline every entry has
 *  one. The SOURCE_ID (DAVx5's vCard file name) guards against SQLite reusing a freed row id or a
 *  baseline restored onto another phone: an entry only counts while that raw contact still has
 *  the same SOURCE_ID (or the entry was recorded before the vCard was first uploaded, when there
 *  was none yet). An entry that no longer matches is just dropped, which can only ever make a
 *  contact look newly added, never removed — the safe direction. */
class GroupSync(val property: GroupBackedProperty) {

    /** One contact as the reconcile step sees it. [accountRawContacts] are its raw contacts in
     *  the group's account (id → SOURCE_ID); empty means not joinable. */
    data class ContactState(
        val contactId: Long,
        val hasProperty: Boolean,
        val inGroup: Boolean,
        val accountRawContacts: Map<Long, String?>,
    ) {
        val joinable: Boolean get() = accountRawContacts.isNotEmpty()
    }

    /** One remembered holder: a raw contact in the group's account, plus its SOURCE_ID then. */
    data class BaselineEntry(val rawContactId: Long, val sourceId: String?)

    /** What a line-up writes, by aggregate contact id, and the baseline to keep once written. */
    data class Plan(
        val give: Set<Long>,
        val takeAway: Set<Long>,
        val addToGroup: Set<Long>,
        val removeFromGroup: Set<Long>,
        val baseline: Set<BaselineEntry>,
    ) {
        val hasWrites: Boolean get() =
            give.isNotEmpty() || takeAway.isNotEmpty() || addToGroup.isNotEmpty() || removeFromGroup.isNotEmpty()
    }

    sealed interface LineUpResult {
        /** No group chosen: the property alone counts, there is no group to sync. Any
         *  [GroupBackedProperty.queryIncomplete] repair has still been written. */
        data object NoGroup : LineUpResult

        /** READ_CONTACTS or WRITE_CONTACTS missing; nothing read or written. */
        data object NoPermission : LineUpResult

        /** The chosen group is gone (deleted on this phone or the server, or the setting came
         *  from another phone). Nothing but a repair was written; the host should tell the user
         *  and/or [setGroup] to null or another group. */
        data class GroupMissing(val stored: ContactGroup) : LineUpResult

        /** Another [GroupSync] has chosen the same group (a restored backup, say — the picker
         *  never offers it). Syncing both through it would make each property's members the
         *  other's, so nothing but a repair was written. */
        data class GroupShared(val group: ContactGroup) : LineUpResult

        /** The provider rejected the batch; nothing (or, at worst, part of an un-transacted
         *  batch) was written and the baseline is unchanged, so the next run retries. */
        data object Failed : LineUpResult

        data class Done(val plan: Plan) : LineUpResult
    }

    sealed interface GroupState {
        data object None : GroupState
        data class Missing(val stored: ContactGroup) : GroupState

        /** [group] carries the group's current title, which may differ from the stored one. */
        data class Present(val group: ContactGroup) : GroupState
    }

    /** Lines the property and the chosen group up, and re-applies an incomplete property. Safe
     *  to call as often as the contacts DB changes: it converges, so the change notification its
     *  own writes cause leads to a run that writes nothing. Reads the whole group and holder set
     *  (a few queries), writes everything in one batch, and only then records the new baseline.
     *  Blocking — call off the main thread. */
    fun lineUp(context: Context): LineUpResult = synchronized(lock) {
        if (!ContactGroups.canWrite(context)) return LineUpResult.NoPermission
        val state = groupState(context)
        val membership = (state as? GroupState.Present)?.group
            ?.takeUnless { isShared(context, it) }
            ?.let { ContactGroups.queryMembership(context, it) }
        // Read last, right before the batch, so the repair acts on the freshest holders.
        val holders = property.queryHolders(context)
        val incomplete = if (property.repairs) property.queryIncomplete(context, holders) else emptySet()
        if (membership == null) {
            val repaired = repair(context, incomplete)
            return when {
                !repaired -> LineUpResult.Failed
                state is GroupState.Missing -> LineUpResult.GroupMissing(state.stored)
                state is GroupState.Present -> LineUpResult.GroupShared(state.group)
                else -> LineUpResult.NoGroup
            }
        }
        val group = membership.group
        val states = (holders + membership.memberContactIds).map { stateOf(it, holders, membership) }
        val baseline = readBaseline(context, group.id)
        val plan = reconcile(states, baseline)

        // A contact the plan keeps as a holder but whose property isn't fully in effect is
        // written again; one the plan takes it away from is already being written.
        val repairs = incomplete - plan.takeAway - plan.give
        val ops = (plan.give + repairs).map { property.writeOp(it, true) } +
            plan.takeAway.map { property.writeOp(it, false) } +
            ContactGroups.addOps(membership, plan.addToGroup) +
            ContactGroups.removeOps(membership, plan.removeFromGroup)
        if (!ContactGroups.applyBatch(context, ops)) return LineUpResult.Failed
        if (plan.baseline != baseline) writeBaseline(context, group.id, plan.baseline)
        LineUpResult.Done(plan)
    }

    private fun repair(context: Context, incomplete: Set<Long>): Boolean =
        ContactGroups.applyBatch(context, incomplete.map { property.writeOp(it, true) })

    /** A host's own toggle: sets the property and, when [group] (default: the chosen group)
     *  still exists and the contact is joinable, its membership too — one batch — and records
     *  the result in the baseline so the next [lineUp] finds nothing to do. Taking it away
     *  removes every membership row the contact has in the group. A non-joinable contact, no
     *  group, or a missing group: just the property. False on failure or missing permission. */
    fun setProperty(
        context: Context,
        contactId: Long,
        on: Boolean,
        group: ContactGroup? = group(context),
    ): Boolean = synchronized(lock) {
        if (!ContactGroups.canWrite(context)) return false
        val write = property.writeOp(contactId, on)
        val live = (if (group == null) null else ContactGroups.queryGroup(context, group.id)?.takeIf { it.sameAccountAs(group) })
            ?.takeUnless { isShared(context, it) }
            ?: return ContactGroups.applyBatch(context, listOf(write))

        val membership = ContactGroups.queryMembership(context, live)
        val membershipOps = if (on) {
            ContactGroups.addOps(membership, setOf(contactId))
        } else {
            ContactGroups.removeOps(membership, setOf(contactId))
        }
        if (!ContactGroups.applyBatch(context, listOf(write) + membershipOps)) return false

        // No baseline yet means the first line-up will merge both sides anyway; both sides
        // already agree on this contact, so there's nothing to record ahead of it.
        val baseline = readBaseline(context, live.id) ?: return true
        val raws = membership.accountRawContacts[contactId].orEmpty()
        if (raws.isEmpty()) return true
        val updated = if (on) {
            baseline + raws.map { BaselineEntry(it, membership.sourceIds[it]) }
        } else {
            baseline.filterNot { it.rawContactId in raws }.toSet()
        }
        writeBaseline(context, live.id, updated)
        true
    }

    private fun stateOf(contactId: Long, holders: Set<Long>, membership: GroupMembership) = ContactState(
        contactId = contactId,
        hasProperty = contactId in holders,
        inGroup = membership.isMember(contactId),
        accountRawContacts = membership.accountRawContacts[contactId].orEmpty().associateWith { membership.sourceIds[it] },
    )

    // --- The chosen group (a setting, in the host's own prefs file) -------------------------

    /** The chosen group as stored (title as of when it was chosen), or null. */
    fun group(context: Context): ContactGroup? {
        val json = NinePrefs.of(context).getString(groupKey, null) ?: return null
        return try {
            val o = JSONObject(json)
            ContactGroup(o.getLong("id"), o.getString("title"), o.getString("accountName"), o.getString("accountType"))
        } catch (e: Exception) {
            null
        }
    }

    /** Whether [group] is the chosen one. Reads the stored setting alone (no provider query), so
     *  it is safe on the main thread when a menu is being built. */
    fun isGroup(context: Context, group: ContactGroup): Boolean {
        val chosen = group(context) ?: return false
        return chosen.id == group.id && chosen.sameAccountAs(group)
    }

    /** Chooses (or with null, clears) the group. Choosing a different group than before forgets
     *  the baseline, so the next [lineUp] merges both sides afresh; re-choosing the same group
     *  keeps it. Doesn't run a line-up itself. */
    fun setGroup(context: Context, group: ContactGroup?) {
        val previous = group(context)
        val editor = NinePrefs.of(context).edit()
        if (group == null) {
            editor.remove(groupKey)
        } else {
            editor.putString(
                groupKey,
                JSONObject()
                    .put("id", group.id).put("title", group.title)
                    .put("accountName", group.accountName).put("accountType", group.accountType)
                    .toString(),
            )
        }
        editor.apply()
        if (group == null || previous == null || previous.id != group.id || !previous.sameAccountAs(group)) clearBaseline(context)
    }

    /** Whether the chosen group still exists, for Settings to show "X" or "group not found". A
     *  group id now belonging to a different account counts as missing. */
    fun groupState(context: Context): GroupState {
        val stored = group(context) ?: return GroupState.None
        val live = ContactGroups.queryGroup(context, stored.id)?.takeIf { it.sameAccountAs(stored) }
        return if (live != null) GroupState.Present(live) else GroupState.Missing(stored)
    }

    private fun isShared(context: Context, group: ContactGroup) = instances.any { it !== this && it.isGroup(context, group) }

    /** Whether a background line-up has anything to do: a group is chosen, or the property can
     *  need repairing without one. */
    fun needsLineUps(context: Context): Boolean = property.repairs || group(context) != null

    private fun ContactGroup.sameAccountAs(other: ContactGroup) =
        accountName == other.accountName && accountType == other.accountType

    // --- The baseline (device-local state, deliberately outside the NinePrefs file) ---------

    /** Forgets the baseline; the next [lineUp] merges holders and members (nothing is removed). */
    fun clearBaseline(context: Context) {
        stateFile(context).edit().remove(KEY_BASELINE).apply()
    }

    /** Null when there is none, or it was recorded for a different group. */
    internal fun readBaseline(context: Context, groupId: Long): Set<BaselineEntry>? {
        val json = stateFile(context).getString(KEY_BASELINE, null) ?: return null
        return try {
            val o = JSONObject(json)
            if (o.getLong("groupId") != groupId) return null
            val entries = o.getJSONArray("entries")
            (0 until entries.length()).map {
                val e = entries.getJSONObject(it)
                BaselineEntry(e.getLong("raw"), if (e.has("source")) e.getString("source") else null)
            }.toSet()
        } catch (e: Exception) {
            null
        }
    }

    internal fun writeBaseline(context: Context, groupId: Long, entries: Set<BaselineEntry>) {
        val array = JSONArray()
        entries.sortedBy { it.rawContactId }.forEach { entry ->
            array.put(JSONObject().put("raw", entry.rawContactId).apply { entry.sourceId?.let { put("source", it) } })
        }
        val json = JSONObject().put("groupId", groupId).put("entries", array).toString()
        // commit(), not apply(): a line-up that ran must not be forgotten if the process dies
        // right after, or the next run would see the same changes again with an older baseline.
        stateFile(context).edit().putString(KEY_BASELINE, json).commit()
    }

    /** Its own file rather than [NinePrefs.of]: the baseline is bookkeeping about this phone's
     *  contacts database, and core's Backup exports/imports that whole file — restoring someone's
     *  baseline (row ids) onto another phone or an older state must not be possible. */
    private fun stateFile(context: Context) =
        context.getSharedPreferences("${Nine.app.id}_${property.key}_sync", Context.MODE_PRIVATE)

    private val groupKey = "contacts_${property.key}_group"

    init {
        synchronized(instances) { instances += this }
    }

    companion object {
        /** Every sync there is, so one can tell its group is also another's. */
        private val instances = mutableListOf<GroupSync>()

        /** One lock for every instance: line-ups of different properties write to the same
         *  contacts, and a provider batch each is easier to reason about than interleaved ones. */
        private val lock = Any()

        private const val KEY_BASELINE = "baseline"

        /** The pure three-way merge. [contacts] needs every contact that has the property or is
         *  in the group; anyone else is on neither side and can only drop out of the baseline.
         *  [baseline] null means "never lined up with this group": holders become holders ∪
         *  members.
         *
         *  With a baseline, per joinable contact: one that was a holder stays one only while it
         *  still both has the property and is a member (a removal on either side wins); one that
         *  wasn't becomes one if it has the property or is a member (an addition on either side
         *  wins). */
        fun reconcile(contacts: Collection<ContactState>, baseline: Set<BaselineEntry>?): Plan {
            val give = mutableSetOf<Long>()
            val takeAway = mutableSetOf<Long>()
            val add = mutableSetOf<Long>()
            val remove = mutableSetOf<Long>()
            val newBaseline = mutableSetOf<BaselineEntry>()
            for (c in contacts) {
                if (!c.joinable) continue
                val wasHolder = baseline != null && baseline.any { it.matches(c) }
                val holder = if (wasHolder) c.hasProperty && c.inGroup else c.hasProperty || c.inGroup
                if (holder) {
                    if (!c.hasProperty) give += c.contactId
                    if (!c.inGroup) add += c.contactId
                    c.accountRawContacts.forEach { (rawId, sourceId) -> newBaseline += BaselineEntry(rawId, sourceId) }
                } else {
                    if (c.hasProperty) takeAway += c.contactId
                    if (c.inGroup) remove += c.contactId
                }
            }
            return Plan(give, takeAway, add, remove, newBaseline)
        }

        private fun BaselineEntry.matches(c: ContactState): Boolean =
            c.accountRawContacts.containsKey(rawContactId) &&
                (sourceId == null || sourceId == c.accountRawContacts[rawContactId])
    }
}
