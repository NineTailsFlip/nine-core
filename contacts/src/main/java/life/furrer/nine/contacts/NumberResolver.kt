package life.furrer.nine.contacts

import android.content.Context
import android.telephony.PhoneNumberUtils
import android.provider.ContactsContract.CommonDataKinds as CDK

/** A phone number (as it appeared on a call or an SMS) resolved to the contact that owns it. */
data class ResolvedNumber(val contactId: Long, val displayName: String)

/** One phone data row as [matchNumbers] sees it. */
data class PhoneOwner(val number: String, val contactId: Long, val displayName: String)

/** Trailing digits two numbers must share before they're even compared — the same "min match"
 *  the platform's own PhoneLookup indexes on, so `+41 79 123 45 67` in the address book and
 *  `079 123 45 67` on a call land in the same bucket. */
private const val MIN_MATCH = 7

private fun minMatchKey(number: String): String = number.filter { it.isDigit() }.takeLast(MIN_MATCH)

/** The pure half of [ContactStore.resolveNumbers]: buckets [phones] by their trailing digits and
 *  confirms a candidate with [PhoneNumberUtils.compare] (the loose, trunk-prefix-aware comparison
 *  that treats `079…` and `+4179…` as the same number). An exact digit match beats a loose one
 *  when two contacts share a number's tail. Blank numbers and numbers without a single digit
 *  (alphanumeric SMS senders) never match anything. */
fun matchNumbers(numbers: Collection<String>, phones: List<PhoneOwner>): Map<String, ResolvedNumber> {
    val byKey = phones.groupBy { minMatchKey(it.number) }
    val result = mutableMapOf<String, ResolvedNumber>()
    numbers.forEach { number ->
        val key = minMatchKey(number)
        if (key.isEmpty()) return@forEach
        val candidates = byKey[key] ?: return@forEach
        val digits = number.filter { it.isDigit() }
        val match = candidates.firstOrNull { it.number.filter { c -> c.isDigit() } == digits }
            ?: candidates.firstOrNull { PhoneNumberUtils.compare(number, it.number) }
            ?: return@forEach
        result[number] = ResolvedNumber(match.contactId, match.displayName)
    }
    return result
}

/** Resolves a whole batch of numbers with one read of the phone table, instead of one
 *  `PhoneLookup` query per number. Measured on the daily phone (2026-10-01): each PhoneLookup
 *  round trip costs ~15ms, so 23 distinct numbers in P9's history took ~370ms per load and grew
 *  linearly with every new correspondent, while this is a single query regardless of how many
 *  numbers are asked about. Empty without READ_CONTACTS, never an exception. */
fun ContactStore.resolveNumbers(context: Context, numbers: Collection<String>): Map<String, ResolvedNumber> {
    val wanted = numbers.filter { it.any(Char::isDigit) }.toSet()
    if (wanted.isEmpty() || !hasPermission(context)) return emptyMap()

    val phones = mutableListOf<PhoneOwner>()
    context.contentResolver.query(
        CDK.Phone.CONTENT_URI,
        arrayOf(CDK.Phone.CONTACT_ID, CDK.Phone.NUMBER, CDK.Phone.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        val contactIdIndex = cursor.getColumnIndexOrThrow(CDK.Phone.CONTACT_ID)
        val numberIndex = cursor.getColumnIndexOrThrow(CDK.Phone.NUMBER)
        val nameIndex = cursor.getColumnIndexOrThrow(CDK.Phone.DISPLAY_NAME)
        while (cursor.moveToNext()) {
            val number = cursor.getString(numberIndex) ?: continue
            val name = cursor.getString(nameIndex) ?: continue
            phones.add(PhoneOwner(number, cursor.getLong(contactIdIndex), name))
        }
    }
    return matchNumbers(wanted, phones)
}
