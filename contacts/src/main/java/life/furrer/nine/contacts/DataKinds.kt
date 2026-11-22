package life.furrer.nine.contacts

import android.content.ContentValues
import android.content.res.Resources
import android.provider.ContactsContract.CommonDataKinds as CDK

/** One row of contact data as read from the `Data` table — the shape every kind's row is
 *  normalized into before kind-specific formatting (below) takes over for list/detail/editor
 *  presentation. */
data class DataRow(
    val id: Long,
    val rawContactId: Long,
    val mimetype: String,
    val values: ContentValues,
)

/** The `Data` table's TYPE/LABEL columns ("data2"/"data3") are the *same underlying columns*
 *  for every kind below that has a type — [CommonDataKinds.CommonColumns] is one shared
 *  interface all of them implement, so `Phone.TYPE`/`Phone.LABEL` are valid stand-ins for
 *  "the TYPE/LABEL column" regardless of which kind's row is actually being read. */
private val TYPE_COLUMN = CDK.Phone.TYPE
private val LABEL_COLUMN = CDK.Phone.LABEL

/** The mimetype → field table driving list, detail, and editor per §6.2 — one entry per
 *  CardDAV-relevant kind the Android contacts provider understands. [sectionLabel] is the
 *  editor's section header (§9.11); a `Data` row whose mimetype matches none of these is shown
 *  read-only under "Other" instead of being dropped (§6.2) — see [fromMimetype]. */
enum class DataKind(val mimetype: String, val sectionLabel: String) {
    NAME(CDK.StructuredName.CONTENT_ITEM_TYPE, "Name"),
    NICKNAME(CDK.Nickname.CONTENT_ITEM_TYPE, "Nickname"),
    PHONE(CDK.Phone.CONTENT_ITEM_TYPE, "Phone"),
    EMAIL(CDK.Email.CONTENT_ITEM_TYPE, "Email"),
    POSTAL(CDK.StructuredPostal.CONTENT_ITEM_TYPE, "Address"),
    ORGANIZATION(CDK.Organization.CONTENT_ITEM_TYPE, "Organisation"),
    EVENT(CDK.Event.CONTENT_ITEM_TYPE, "Event"),
    RELATION(CDK.Relation.CONTENT_ITEM_TYPE, "Relation"),
    WEBSITE(CDK.Website.CONTENT_ITEM_TYPE, "Website"),
    IM(CDK.Im.CONTENT_ITEM_TYPE, "IM"),
    SIP(CDK.SipAddress.CONTENT_ITEM_TYPE, "SIP"),
    NOTE(CDK.Note.CONTENT_ITEM_TYPE, "Note"),
    IDENTITY(CDK.Identity.CONTENT_ITEM_TYPE, "Identity"),
    GROUP_MEMBERSHIP(CDK.GroupMembership.CONTENT_ITEM_TYPE, "Groups"),
    PHOTO(CDK.Photo.CONTENT_ITEM_TYPE, "Photo"),
    ;

    companion object {
        fun fromMimetype(mimetype: String): DataKind? = entries.find { it.mimetype == mimetype }
    }
}

/** What the call key / OK / a numbered detail row should *do* with a [DataRow] — the detail
 *  screen dispatches on this rather than re-deriving it from [DataKind] itself, since e.g. a
 *  Website row's action (open URL) has nothing to do with its section grouping. */
enum class DataAction { DIAL, EMAIL, MAP, WEB, NONE }

fun DataKind.action(): DataAction = when (this) {
    DataKind.PHONE -> DataAction.DIAL
    DataKind.EMAIL -> DataAction.EMAIL
    DataKind.POSTAL -> DataAction.MAP
    DataKind.WEBSITE -> DataAction.WEB
    else -> DataAction.NONE
}

/** Kind-specific label/value formatting for the detail and editor screens. Pure functions of a
 *  [DataRow]'s already-read [ContentValues] — no provider access here. */
object DataFormat {

    /** "MOBILE", "WORK", or a custom label uppercased — for kinds with a TYPE column. Kinds
     *  without a meaningful type distinction (Note, Website, Identity, ...) return null. Display
     *  only: anything that edits the label must start from [typeName], or a custom "GitLab"
     *  comes back as "GITLAB". */
    fun typeLabel(resources: Resources, kind: DataKind, values: ContentValues): String? =
        typeName(resources, kind, values)?.uppercase()

    /** [typeLabel] before uppercasing: a custom label exactly as stored, or the platform's own
     *  word for a preset type ("Mobile", "Birthday") — what the editor edits. */
    fun typeName(resources: Resources, kind: DataKind, values: ContentValues): String? {
        // Not every kind has a generic "OTHER" type (Relation's TYPE_* are all specific
        // relations, e.g. TYPE_SPOUSE, with no catch-all) — BaseTypes.TYPE_CUSTOM is the one
        // constant every kind shares, and getTypeLabel just falls back to [customLabel] (or a
        // generic word) for it, so it's a safe default for the rare row missing a TYPE value.
        val type = values.getAsInteger(TYPE_COLUMN) ?: CDK.BaseTypes.TYPE_CUSTOM
        val customLabel = values.getAsString(LABEL_COLUMN)
        val label = when (kind) {
            DataKind.PHONE -> CDK.Phone.getTypeLabel(resources, type, customLabel)
            DataKind.EMAIL -> CDK.Email.getTypeLabel(resources, type, customLabel)
            DataKind.POSTAL -> CDK.StructuredPostal.getTypeLabel(resources, type, customLabel)
            DataKind.ORGANIZATION -> values.getAsInteger(TYPE_COLUMN)?.let { CDK.Organization.getTypeLabel(resources, it, customLabel) }
            DataKind.EVENT -> CDK.Event.getTypeLabel(resources, type, customLabel)
            DataKind.RELATION -> CDK.Relation.getTypeLabel(resources, type, customLabel)
            DataKind.IM -> CDK.Im.getTypeLabel(resources, type, customLabel)
            DataKind.SIP -> CDK.SipAddress.getTypeLabel(resources, type, customLabel)
            else -> null
        }
        return label?.toString()
    }

    /** The IM protocol name ("SKYPE", "JABBER", or a custom protocol) — separate from
     *  [typeLabel] (home/work/other) since IM rows have both a type *and* a protocol. Display
     *  only, like [typeLabel]; [imProtocolName] is the as-stored form. */
    fun imProtocolLabel(resources: Resources, values: ContentValues): String? =
        imProtocolName(resources, values)?.uppercase()

    /** [imProtocolLabel] before uppercasing. */
    fun imProtocolName(resources: Resources, values: ContentValues): String? {
        val protocol = values.getAsInteger(CDK.Im.PROTOCOL) ?: return null
        val customProtocol = values.getAsString(CDK.Im.CUSTOM_PROTOCOL)
        return CDK.Im.getProtocolLabel(resources, protocol, customProtocol)?.toString()
    }

    /** The single-line display value per kind — what §9.10's detail rows and §9.11's editor
     *  rows both show after the label. Structured kinds (name, address) are joined with the
     *  punctuation a person would actually write, not a raw field dump. */
    fun valueText(kind: DataKind, values: ContentValues): String = when (kind) {
        DataKind.NAME -> values.getAsString(CDK.StructuredName.DISPLAY_NAME).orEmpty()
        DataKind.NICKNAME -> values.getAsString(CDK.Nickname.NAME).orEmpty()
        DataKind.PHONE -> values.getAsString(CDK.Phone.NUMBER).orEmpty()
        DataKind.EMAIL -> values.getAsString(CDK.Email.ADDRESS).orEmpty()
        DataKind.POSTAL -> formatPostalAddress(values)
        DataKind.ORGANIZATION -> formatOrganization(values)
        DataKind.EVENT -> formatEventDate(values.getAsString(CDK.Event.START_DATE))
        DataKind.RELATION -> values.getAsString(CDK.Relation.NAME).orEmpty()
        DataKind.WEBSITE -> values.getAsString(CDK.Website.URL).orEmpty()
        DataKind.IM -> values.getAsString(CDK.Im.DATA).orEmpty()
        DataKind.SIP -> values.getAsString(CDK.SipAddress.SIP_ADDRESS).orEmpty()
        DataKind.NOTE -> values.getAsString(CDK.Note.NOTE).orEmpty()
        DataKind.IDENTITY -> values.getAsString(CDK.Identity.IDENTITY).orEmpty()
        DataKind.GROUP_MEMBERSHIP -> "" // resolved against the Groups table by the caller, not here
        DataKind.PHOTO -> "" // binary; never rendered as text
    }

    private fun formatPostalAddress(values: ContentValues): String {
        values.getAsString(CDK.StructuredPostal.FORMATTED_ADDRESS)?.takeIf { it.isNotBlank() }?.let { return it }
        return listOfNotNull(
            values.getAsString(CDK.StructuredPostal.STREET),
            values.getAsString(CDK.StructuredPostal.NEIGHBORHOOD),
            values.getAsString(CDK.StructuredPostal.POBOX),
            values.getAsString(CDK.StructuredPostal.CITY),
            values.getAsString(CDK.StructuredPostal.REGION),
            values.getAsString(CDK.StructuredPostal.POSTCODE),
            values.getAsString(CDK.StructuredPostal.COUNTRY),
        ).filter { it.isNotBlank() }.joinToString(", ")
    }

    private fun formatOrganization(values: ContentValues): String {
        val company = values.getAsString(CDK.Organization.COMPANY)?.takeIf { it.isNotBlank() }
        val title = values.getAsString(CDK.Organization.TITLE)?.takeIf { it.isNotBlank() }
        return listOfNotNull(company, title).joinToString(" · ")
    }

    /** vCard/CardDAV `Event.START_DATE` is either a full ISO date (`yyyy-MM-dd`) or a
     *  no-year form (`--MM-dd`, used for birthdays whose year is unknown). Anything else is
     *  shown verbatim rather than dropped — an unparseable date is still better than nothing. */
    private fun formatEventDate(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val noYear = Regex("""--(\d{2})-(\d{2})""").matchEntire(raw)
        if (noYear != null) {
            val (month, day) = noYear.destructured
            return "${monthName(month.toInt())} ${day.toInt()}"
        }
        val full = Regex("""(\d{4})-(\d{2})-(\d{2})""").matchEntire(raw)
        if (full != null) {
            val (year, month, day) = full.destructured
            return "${day.toInt()} ${monthName(month.toInt())} $year"
        }
        return raw
    }

    private fun monthName(month: Int): String = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    ).getOrElse(month - 1) { "" }
}

/** The write-side counterpart of [DataFormat.typeLabel] — maps a free-typed
 *  [life.furrer.c9.contacts.EditorField]-style label back to the kind's own TYPE_* constant, for
 *  every kind [ContactEditActivity] added past M5's original Phone/Email/Website set. Unrecognized
 *  text becomes that kind's TYPE_CUSTOM (the caller pairs it with the kind's own LABEL column)
 *  rather than silently coercing to a wrong type. */
fun typeFromLabel(kind: DataKind, label: String): Int {
    val upper = label.trim().uppercase()
    return when (kind) {
        DataKind.POSTAL -> when (upper) {
            "HOME" -> CDK.StructuredPostal.TYPE_HOME
            "WORK" -> CDK.StructuredPostal.TYPE_WORK
            "OTHER" -> CDK.StructuredPostal.TYPE_OTHER
            else -> CDK.StructuredPostal.TYPE_CUSTOM
        }
        DataKind.EVENT -> when (upper) {
            "BIRTHDAY" -> CDK.Event.TYPE_BIRTHDAY
            "ANNIVERSARY" -> CDK.Event.TYPE_ANNIVERSARY
            "OTHER" -> CDK.Event.TYPE_OTHER
            else -> CDK.Event.TYPE_CUSTOM
        }
        DataKind.RELATION -> when (upper) {
            "SPOUSE" -> CDK.Relation.TYPE_SPOUSE
            "CHILD" -> CDK.Relation.TYPE_CHILD
            "MOTHER" -> CDK.Relation.TYPE_MOTHER
            "FATHER" -> CDK.Relation.TYPE_FATHER
            "PARENT" -> CDK.Relation.TYPE_PARENT
            "BROTHER" -> CDK.Relation.TYPE_BROTHER
            "SISTER" -> CDK.Relation.TYPE_SISTER
            "FRIEND" -> CDK.Relation.TYPE_FRIEND
            "RELATIVE" -> CDK.Relation.TYPE_RELATIVE
            "MANAGER" -> CDK.Relation.TYPE_MANAGER
            "ASSISTANT" -> CDK.Relation.TYPE_ASSISTANT
            "PARTNER" -> CDK.Relation.TYPE_PARTNER
            "DOMESTIC PARTNER" -> CDK.Relation.TYPE_DOMESTIC_PARTNER
            "REFERRED BY" -> CDK.Relation.TYPE_REFERRED_BY
            else -> CDK.BaseTypes.TYPE_CUSTOM
        }
        DataKind.SIP -> when (upper) {
            "HOME" -> CDK.SipAddress.TYPE_HOME
            "WORK" -> CDK.SipAddress.TYPE_WORK
            "OTHER" -> CDK.SipAddress.TYPE_OTHER
            else -> CDK.SipAddress.TYPE_CUSTOM
        }
        else -> CDK.BaseTypes.TYPE_CUSTOM
    }
}

/** The pre-declared type labels the editor's "Change type" menu offers for [kind] — RFC 2426/
 *  vCard-style choices [typeFromLabel]/[phoneTypeFromLabel] already round-trip correctly, plus
 *  this suite's own synthetic "Custom" entry (not a real vCard type) that prompts for free text
 *  instead of picking one of the fixed choices. Empty for a kind with no "Change type" action
 *  yet — every kind but [DataKind.PHONE] and [DataKind.EVENT] for now; add an entry here to bring
 *  the same menu to Email/etc. later, no other plumbing needed since
 *  [ContactEditActivity.openMenu] only shows the action when this is non-empty. */
fun changeableTypeLabels(kind: DataKind): List<String> = when (kind) {
    DataKind.PHONE -> listOf("Mobile", "Work", "Home", "Other", "Custom")
    DataKind.EVENT -> listOf("Birthday", "Anniversary", "Other", "Custom")
    else -> emptyList()
}

/** Phone's own label → TYPE_* mapping, kept apart from [typeFromLabel] because Phone has twenty
 *  platform types and the labels this suite reads back are the platform's *own* localized
 *  strings ([DataFormat.typeLabel] goes through `Phone.getTypeLabel`) — so the reliable inverse
 *  is to ask the platform for each type's label and compare, rather than hardcode English names.
 *  Unrecognized text is TYPE_CUSTOM, for the caller to pair with `Phone.LABEL`. */
fun phoneTypeFromLabel(resources: Resources, label: String): Int =
    typeFromPlatformLabel(label, CDK.Phone.TYPE_HOME..CDK.Phone.TYPE_MMS, CDK.Phone.TYPE_CUSTOM) {
        CDK.Phone.getTypeLabel(resources, it, null)
    }

/** [phoneTypeFromLabel]'s Email counterpart — same reasoning, Email's four platform types. */
fun emailTypeFromLabel(resources: Resources, label: String): Int =
    typeFromPlatformLabel(label, CDK.Email.TYPE_HOME..CDK.Email.TYPE_MOBILE, CDK.Email.TYPE_CUSTOM) {
        CDK.Email.getTypeLabel(resources, it, null)
    }

private fun typeFromPlatformLabel(label: String, types: IntRange, custom: Int, labelOf: (Int) -> CharSequence): Int {
    val wanted = label.trim()
    return types.firstOrNull { labelOf(it).toString().equals(wanted, ignoreCase = true) } ?: custom
}

/** Same idea as [typeFromLabel] but for IM's protocol column, which stands in for the type label
 *  on IM rows (see [ContactEditActivity.fieldToKindValues]) — an unrecognized protocol name
 *  (WhatsApp, Signal, Telegram, ...) becomes PROTOCOL_CUSTOM with the typed text preserved
 *  verbatim as CUSTOM_PROTOCOL, rather than forcing it into one of the handful of legacy
 *  protocols the provider has a constant for. */
fun imProtocolFromLabel(label: String): Int = when (label.trim().uppercase()) {
    "AIM" -> CDK.Im.PROTOCOL_AIM
    "MSN" -> CDK.Im.PROTOCOL_MSN
    "YAHOO" -> CDK.Im.PROTOCOL_YAHOO
    "SKYPE" -> CDK.Im.PROTOCOL_SKYPE
    "QQ" -> CDK.Im.PROTOCOL_QQ
    "GOOGLE TALK", "HANGOUTS" -> CDK.Im.PROTOCOL_GOOGLE_TALK
    "ICQ" -> CDK.Im.PROTOCOL_ICQ
    "JABBER" -> CDK.Im.PROTOCOL_JABBER
    "NETMEETING" -> CDK.Im.PROTOCOL_NETMEETING
    else -> CDK.Im.PROTOCOL_CUSTOM
}
