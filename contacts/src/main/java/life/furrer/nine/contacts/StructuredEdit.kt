package life.furrer.nine.contacts

import android.content.ContentValues
import android.provider.ContactsContract.CommonDataKinds as CDK

/** What an editor needs to change a structured `Data` row without rewriting it: the row's
 *  columns as a column -> text map ([read]), a new map derived from what the user typed, and
 *  [diff] to turn the two into a [ContentValues] holding only the columns that differ. A row
 *  whose map is unchanged yields an empty diff, which a caller must not write at all. */
object StructuredEdit {

    fun read(values: ContentValues, columns: List<String>): Map<String, String> =
        columns.associateWith { values.getAsString(it).orEmpty() }

    /** Only the columns whose text differs between [old] and [new]; blank is stored as null. */
    fun diff(old: Map<String, String>, new: Map<String, String>): ContentValues {
        val out = ContentValues()
        new.forEach { (column, value) ->
            if (value != old[column].orEmpty()) {
                if (value.isBlank()) out.putNull(column) else out.put(column, value)
            }
        }
        return out
    }

    /** An editor part: the column it lives in and the label its menu row shows. */
    class Part(val column: String, val label: String)
}

/** Organization rows: the editor shows one line, "Company · Title", or just whichever of the two
 *  is set. A lone job title therefore looks like a lone company — [fromText] uses what the row
 *  held to tell them apart, so editing the title of a contact that has no company keeps it a
 *  title. */
object OrganizationParts {
    val COLUMNS = listOf(CDK.Organization.COMPANY, CDK.Organization.TITLE)

    fun fromText(old: Map<String, String>, text: String): Map<String, String> {
        val company = old[CDK.Organization.COMPANY].orEmpty().trim()
        val title = old[CDK.Organization.TITLE].orEmpty().trim()
        val t = text.trim()
        val separator = t.indexOf(" · ")
        if (separator >= 0) {
            return mapOf(
                CDK.Organization.COMPANY to t.substring(0, separator).trim(),
                CDK.Organization.TITLE to t.substring(separator + 3).trim(),
            )
        }
        val isTitle = (company.isBlank() && title.isNotBlank()) || (company.isNotBlank() && title.isNotBlank() && t == title)
        return if (isTitle) {
            mapOf(CDK.Organization.COMPANY to "", CDK.Organization.TITLE to t)
        } else {
            mapOf(CDK.Organization.COMPANY to t, CDK.Organization.TITLE to "")
        }
    }
}

/** Name rows: the editor shows the display name as one line, but the row is five parts. Typing a
 *  new line re-derives the parts *relative to the loaded ones* ([fromText]) - a prefix, suffix or
 *  middle name that is still in the text stays what it was instead of being swallowed into the
 *  given name ("Dr. Hans Peter Muster" is not given name "Dr."). */
object NameParts {
    val PARTS = listOf(
        StructuredEdit.Part(CDK.StructuredName.PREFIX, "Prefix"),
        StructuredEdit.Part(CDK.StructuredName.GIVEN_NAME, "First"),
        StructuredEdit.Part(CDK.StructuredName.MIDDLE_NAME, "Middle"),
        StructuredEdit.Part(CDK.StructuredName.FAMILY_NAME, "Last"),
        StructuredEdit.Part(CDK.StructuredName.SUFFIX, "Suffix"),
    )
    val COLUMNS = PARTS.map { it.column }

    /** The parts joined the way a person writes the name. */
    fun display(parts: Map<String, String>): String =
        COLUMNS.map { parts[it].orEmpty().trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    fun fromText(old: Map<String, String>, text: String): Map<String, String> {
        var rest = text.trim().replace(Regex("\\s+"), " ")
        var prefix = ""
        var suffix = ""
        val oldPrefix = old[CDK.StructuredName.PREFIX].orEmpty().trim()
        if (oldPrefix.isNotEmpty() && (rest == oldPrefix || rest.startsWith("$oldPrefix "))) {
            prefix = oldPrefix
            rest = rest.removePrefix(oldPrefix).trim()
        }
        val oldSuffix = old[CDK.StructuredName.SUFFIX].orEmpty().trim()
        if (oldSuffix.isNotEmpty()) {
            for (separator in listOf(", ", " ")) {
                if (rest.endsWith(separator + oldSuffix)) {
                    suffix = oldSuffix
                    rest = rest.removeSuffix(separator + oldSuffix).trim()
                    break
                }
            }
        }
        val oldGiven = old[CDK.StructuredName.GIVEN_NAME].orEmpty().trim()
        val oldMiddle = old[CDK.StructuredName.MIDDLE_NAME].orEmpty().trim()
        val oldFamily = old[CDK.StructuredName.FAMILY_NAME].orEmpty().trim()
        var given: String
        var middle = ""
        var family: String
        val at = if (oldMiddle.isEmpty()) -1 else " $rest ".indexOf(" $oldMiddle ")
        val before = if (at > 0) " $rest ".substring(0, at).trim() else ""
        if (at > 0 && before.isNotEmpty()) {
            given = before
            middle = oldMiddle
            family = " $rest ".substring(at + oldMiddle.length + 2).trim()
        } else if (oldGiven.isNotEmpty() && (rest == oldGiven || rest.startsWith("$oldGiven "))) {
            given = oldGiven
            family = rest.removePrefix(oldGiven).trim()
        } else if (oldFamily.isNotEmpty() && (rest == oldFamily || rest.endsWith(" $oldFamily"))) {
            family = oldFamily
            given = rest.removeSuffix(oldFamily).trim()
        } else {
            val words = rest.split(" ", limit = 2)
            given = words[0]
            family = words.getOrElse(1) { "" }
        }
        return mapOf(
            CDK.StructuredName.PREFIX to prefix,
            CDK.StructuredName.GIVEN_NAME to given,
            CDK.StructuredName.MIDDLE_NAME to middle,
            CDK.StructuredName.FAMILY_NAME to family,
            CDK.StructuredName.SUFFIX to suffix,
        )
    }
}

/** Address rows are edited part by part (the editor opens a menu of these) rather than as the
 *  flattened line it shows, which can't be turned back into parts. */
object PostalParts {
    val PARTS = listOf(
        StructuredEdit.Part(CDK.StructuredPostal.STREET, "Street"),
        StructuredEdit.Part(CDK.StructuredPostal.POBOX, "PO box"),
        StructuredEdit.Part(CDK.StructuredPostal.NEIGHBORHOOD, "Neighborhood"),
        StructuredEdit.Part(CDK.StructuredPostal.CITY, "City"),
        StructuredEdit.Part(CDK.StructuredPostal.REGION, "Region"),
        StructuredEdit.Part(CDK.StructuredPostal.POSTCODE, "Postcode"),
        StructuredEdit.Part(CDK.StructuredPostal.COUNTRY, "Country"),
    )
    val COLUMNS = PARTS.map { it.column }

    /** One line, in the same order and punctuation [DataFormat.valueText] falls back to. */
    fun line(parts: Map<String, String>): String =
        COLUMNS.map { parts[it].orEmpty().trim() }.filter { it.isNotEmpty() }.joinToString(", ")
}
