package life.furrer.nine.contacts

import android.content.res.Resources

/** One row of the detail screen (§9.10, mockups.html's own reference render) — either a section
 *  header naming the kind ("PHONE", "EMAIL", ...) or a numbered action row: every row is a verb,
 *  `3` mails, `4` opens the map. [Item.displayIndex] is 1-9 for the first nine *item* rows
 *  (headers never consume a number, the same convention [CalendarRow] and [ContactRow] both use
 *  for their own header rows), then null past the ninth — still reachable by Up/Down, just not
 *  by a direct digit press. */
sealed interface ContactDetailRow {
    data class SectionHeader(val label: String) : ContactDetailRow

    /** One group the contact is in, under its own GROUPS header after every data row — numbered
     *  like any other row, and its verb is "open this group's members". */
    data class GroupItem(
        val group: ContactStore.ContactGroup,
        val favorite: Boolean,
        val displayIndex: Int?,
        /** The group chosen to carry send-to-voicemail ([VoicemailSync]). */
        val voicemail: Boolean = false,
    ) : ContactDetailRow
    data class Item(
        val dataRow: DataRow,
        val kind: DataKind?,
        val displayIndex: Int?,
        val typeLabel: String,
        val value: String,
    ) : ContactDetailRow
}

fun ContactDetailRow.isSelectable(): Boolean = this is ContactDetailRow.Item || this is ContactDetailRow.GroupItem

/** The 1-9 digit a selectable row answers to, whichever kind of row it is. */
val ContactDetailRow.digit: Int?
    get() = when (this) {
        is ContactDetailRow.Item -> displayIndex
        is ContactDetailRow.GroupItem -> displayIndex
        is ContactDetailRow.SectionHeader -> null
    }

/** Builds the numbered rows from a [ContactDetail]'s raw [DataRow]s, grouped under a section
 *  header per kind — mobile/work under "PHONE", home/work under "EMAIL", and so on, matching
 *  mockups.html's contact editor render (§9.11) applied to this read-only screen too. Rows are
 *  grouped by [DataKind.ordinal] via a *stable* sort, not raw `Data`-table order, so multiple
 *  rows of the same kind (two phone numbers) stay grouped even when the provider doesn't already
 *  return them adjacent to each other, and their own relative order is preserved. Every unknown
 *  mimetype sorts after every known kind, under a single "OTHER" header, rather than one header
 *  per row (§6.2: unknown mimetypes are never dropped).
 *
 *  Name and Organization are shown in the header instead (title/subtitle), and Photo has no
 *  text row. GroupMembership rows carry only a group id, so they aren't turned into rows here;
 *  [groups] (as [ContactGroups.queryGroupsOfContact] returns them) become a GROUPS section of
 *  [ContactDetailRow.GroupItem]s after everything else, the favorites group keeping its ★ via
 *  [favoritesGroupId] and the voicemail group marked via [voicemailGroupId]. A row with a known [DataKind] but a blank
 *  formatted value (e.g. an Organization row that turned out to be name-only, already covered by
 *  the header) is skipped rather than shown empty. */
fun buildDetailRows(
    resources: Resources,
    detail: ContactDetail,
    groups: List<ContactStore.ContactGroup> = emptyList(),
    favoritesGroupId: Long? = null,
    voicemailGroupId: Long? = null,
): List<ContactDetailRow> {
    data class Parsed(val dataRow: DataRow, val kind: DataKind?, val typeLabel: String, val value: String)

    val parsed = detail.rows.mapNotNull { dataRow ->
        val kind = DataKind.fromMimetype(dataRow.mimetype)
        if (kind == DataKind.NAME || kind == DataKind.ORGANIZATION || kind == DataKind.PHOTO || kind == DataKind.GROUP_MEMBERSHIP) return@mapNotNull null
        // A sync adapter's private bookkeeping, not data meant for people: Android's convention
        // for a displayable custom kind is a `vnd.android.cursor.item/...` mimetype (WhatsApp's
        // and Signal's rows follow it), anything else is internal. Confirmed on-device: DAVx5
        // keeps an `x.davdroid/cached-group-membership` row per group membership, which showed
        // up here as "OTHER 3" (the group's row id) as soon as a contact joined a group.
        if (kind == null && !dataRow.mimetype.startsWith(DISPLAYABLE_MIMETYPE_PREFIX)) return@mapNotNull null

        val value = if (kind != null) DataFormat.valueText(kind, dataRow.values) else unknownMimetypeValue(dataRow)
        if (value.isBlank()) return@mapNotNull null

        val specificLabel = when {
            kind == null -> null
            kind == DataKind.IM -> DataFormat.imProtocolLabel(resources, dataRow.values) ?: DataFormat.typeLabel(resources, kind, dataRow.values)
            else -> DataFormat.typeLabel(resources, kind, dataRow.values)
        }
        val typeLabel = specificLabel ?: kind?.sectionLabel?.uppercase() ?: "OTHER"

        Parsed(dataRow, kind, typeLabel, value)
    }.sortedBy { it.kind?.ordinal ?: Int.MAX_VALUE }
        .distinctBy { it.kind to canonicalValue(it.kind, it.value) }

    // A second pass, across kinds this time: an unknown-mimetype row whose value is nothing but
    // digits already shown as a real phone number (a WhatsApp JID or a Signal vendor row both
    // reduce to exactly the phone number's digits once punctuation/domain text is stripped) is
    // dropped too — it has no information the PHONE row above it doesn't already carry.
    val phoneDigits = parsed.filter { it.kind == DataKind.PHONE }.map { digitsOnly(it.value) }.toSet()
    val deduped = parsed.filterNot { it.kind == null && digitsOnly(it.value).let { d -> d.isNotEmpty() && d in phoneDigits } }

    val rows = mutableListOf<ContactDetailRow>()
    var index = 0
    var lastGroup: DataKind? = null
    var unknownGroupShown = false
    deduped.forEach { p ->
        val newGroup = if (p.kind == null) !unknownGroupShown else p.kind != lastGroup
        if (newGroup) {
            rows.add(ContactDetailRow.SectionHeader(p.kind?.sectionLabel?.uppercase() ?: "OTHER"))
            lastGroup = p.kind
            if (p.kind == null) unknownGroupShown = true
        }
        index++
        rows.add(
            ContactDetailRow.Item(
                dataRow = p.dataRow,
                kind = p.kind,
                displayIndex = if (index <= 9) index else null,
                typeLabel = p.typeLabel,
                value = p.value,
            ),
        )
    }
    if (groups.isNotEmpty()) {
        rows.add(ContactDetailRow.SectionHeader("GROUPS"))
        groups.forEach { group ->
            index++
            rows.add(
                ContactDetailRow.GroupItem(group, group.id == favoritesGroupId, if (index <= 9) index else null, group.id == voicemailGroupId),
            )
        }
    }
    return rows
}

/** Collapses same-kind rows that would render identically — apps like WhatsApp and Signal mirror
 *  a contact's number into their own linked raw contact (so they can attach their own quick-action
 *  icon to it) and additionally write one unknown-mimetype row per action they offer (message,
 *  voice call, video call), each carrying the same vendor identifier. That otherwise shows up here
 *  as the same phone number twice or three times over, or the same unreadable vendor value (a
 *  WhatsApp JID, a Signal-formatted number) repeated once per action. Phone numbers dedupe by
 *  digits alone so formatting differences between the synced copy and a messaging app's mirror
 *  (spaces/dashes vs. none) still collapse; everything else dedupes by its exact rendered value.
 *  This only changes what this read-only screen renders — [ContactStore.queryContactDetail]'s own
 *  rows stay untouched so the editor can still see and write every underlying raw row. */
/** "Same value, formatting aside" for [kind] — phone numbers compare by digits alone (so a
 *  synced "+41 79 123 45 67" and a messaging app's mirrored "+41791234567" count as the same
 *  number), everything else compares by its exact rendered text. Shared with
 *  [life.furrer.c9.contacts.ContactEditActivity], which uses it to mark a field that's a
 *  duplicate of another raw contact's own row (e.g. WhatsApp's own mirror of a synced number) as
 *  read-only rather than silently dropping it the way this read-only screen's own dedup does —
 *  the editor still needs every row visible so each can be individually deleted. */
fun canonicalValue(kind: DataKind?, value: String): String =
    if (kind == DataKind.PHONE) digitsOnly(value) else value

/** A WhatsApp JID (`14162309119@s.whatsapp.net`) or a Signal vendor value that happens to be the
 *  plain number reduce to exactly a phone number's digits once the non-digit parts are stripped —
 *  used both for [canonicalValue] and to catch one of those mirrors under OTHER even when the
 *  matching PHONE row's own text isn't identical (different formatting). */
fun digitsOnly(value: String): String = value.filter { it.isDigit() }

private const val DISPLAYABLE_MIMETYPE_PREFIX = "vnd.android.cursor.item/"

/** Best-effort text for a `Data` row whose mimetype isn't one this app understands — "data1" is
 *  the conventional primary-value column for arbitrary vendor mimetypes, so it's a reasonable
 *  first guess; if it's absent there's nothing sensible to show and the row is skipped here
 *  (the row itself is still preserved, just not rendered as text on this screen). */
private fun unknownMimetypeValue(dataRow: DataRow): String = dataRow.values.getAsString("data1").orEmpty()
