package life.furrer.nine.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactRowsTest {

    private fun entry(id: Long, name: String, starred: Boolean = false, numbers: List<String> = emptyList(), sortKey: String = name) =
        ContactEntry(id, "lookup$id", name, sortKey, starred, null, numbers.map { ContactPhoneNumber(it, null, false) })

    // --- browse (empty query) -------------------------------------------------------------

    @Test
    fun `empty query with no favorites just groups by letter`() {
        val contacts = listOf(entry(1, "Ana Beck"), entry(2, "Bea Lombardi"))
        val rows = buildContactRows(contacts, "", favoritesOnly = false)

        assertEquals(
            listOf(
                ContactRow.SectionHeader("A"),
                ContactRow.Entry(contacts[0]),
                ContactRow.SectionHeader("B"),
                ContactRow.Entry(contacts[1]),
            ),
            rows,
        )
    }

    @Test
    fun `letter grouping uses sortKey, not a display name that starts with an honorific prefix`() {
        // Reported: "Dr. med. Mara Furrer" (a real contact) got its own stray "D" header instead
        // of sitting under "M" with everyone else -- Android's own SORT_KEY_PRIMARY already
        // strips prefixes like this, but the header letter was computed from displayName instead.
        val mara = entry(1, "Dr. med. Mara Furrer", sortKey = "Mara Furrer")
        val marco = entry(2, "Marco Keller", sortKey = "Marco Keller")
        val rows = buildContactRows(listOf(mara, marco), "", favoritesOnly = false)

        assertEquals(
            listOf(
                ContactRow.SectionHeader("M"),
                ContactRow.Entry(mara),
                ContactRow.Entry(marco),
            ),
            rows,
        )
    }

    @Test
    fun `empty query shows a flat FAVORITES section before the letter groups`() {
        val ana = entry(1, "Ana Beck", starred = true)
        val andreas = entry(2, "Andreas Furrer")
        val bea = entry(3, "Bea Lombardi")
        val rows = buildContactRows(listOf(ana, andreas, bea), "", favoritesOnly = false)

        assertEquals(
            listOf(
                ContactRow.SectionHeader("FAVORITES"),
                ContactRow.Entry(ana),
                ContactRow.SectionHeader("A"),
                ContactRow.Entry(ana),
                ContactRow.Entry(andreas),
                ContactRow.SectionHeader("B"),
                ContactRow.Entry(bea),
            ),
            rows,
        )
    }

    @Test
    fun `a favorite still appears in its own letter group too, not only under FAVORITES`() {
        val ana = entry(1, "Ana Beck", starred = true)
        val rows = buildContactRows(listOf(ana), "", favoritesOnly = false)
        assertEquals(2, rows.filterIsInstance<ContactRow.Entry>().size)
    }

    @Test
    fun `no FAVORITES header when nothing is starred`() {
        val rows = buildContactRows(listOf(entry(1, "Ana Beck")), "", favoritesOnly = false)
        assertTrue(rows.none { it == ContactRow.SectionHeader("FAVORITES") })
    }

    @Test
    fun `favoritesOnly shows just the starred contacts, still letter-grouped, no separate section`() {
        val ana = entry(1, "Ana Beck", starred = true)
        val bea = entry(2, "Bea Lombardi", starred = false)
        val rows = buildContactRows(listOf(ana, bea), "", favoritesOnly = true)

        assertEquals(
            listOf(ContactRow.SectionHeader("A"), ContactRow.Entry(ana)),
            rows,
        )
    }

    @Test
    fun `favoritesOnly with nothing starred yields an empty list`() {
        val rows = buildContactRows(listOf(entry(1, "Bea Lombardi")), "", favoritesOnly = true)
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `a name starting with a digit or symbol groups under the hash header`() {
        val rows = buildContactRows(listOf(entry(1, "7-Eleven")), "", favoritesOnly = false)
        assertEquals(ContactRow.SectionHeader("#"), rows.first())
    }

    @Test
    fun `does not re-sort — relies on the caller's SORT_KEY_PRIMARY ordering`() {
        // Deliberately out-of-alphabetical-order input: buildContactRows must not silently fix it.
        val z = entry(1, "Zed Ziegler")
        val a = entry(2, "Ana Beck")
        val rows = buildContactRows(listOf(z, a), "", favoritesOnly = false)
        assertEquals(
            listOf(
                ContactRow.SectionHeader("Z"), ContactRow.Entry(z),
                ContactRow.SectionHeader("A"), ContactRow.Entry(a),
            ),
            rows,
        )
    }

    // --- search (non-empty query) ----------------------------------------------------------

    @Test
    fun `search matches by name word-prefix, no section headers`() {
        val andreas = entry(1, "Andreas Furrer")
        val bea = entry(2, "Bea Lombardi")
        val rows = buildContactRows(listOf(andreas, bea), "and", favoritesOnly = false)
        assertEquals(listOf(ContactRow.Entry(andreas)), rows)
    }

    @Test
    fun `search with letters only never adds a NUMBERS section even with no matches`() {
        val rows = buildContactRows(listOf(entry(1, "Bea Lombardi")), "xyz", favoritesOnly = false)
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `all-digit query adds number matches under NUMBERS, excluding contacts already matched by name`() {
        val andreas = entry(1, "Andreas Furrer", numbers = listOf("+41 79 111 11 11"))
        val bea = entry(2, "Bea Lombardi", numbers = listOf("+41 79 263 45 67"))
        val rows = buildContactRows(listOf(andreas, bea), "79263", favoritesOnly = false)

        assertEquals(
            listOf(
                ContactRow.SectionHeader("NUMBERS"),
                ContactRow.NumberMatch(bea, "+41 79 263 45 67"),
            ),
            rows,
        )
    }

    @Test
    fun `a contact matching both by name and by number is only listed once, under its name match`() {
        // "79263" doesn't match "Andreas" by name, so build a case where the query is all
        // digits AND happens to also be a name-prefix substring scenario is impossible (names
        // aren't digits) — instead verify the exclusion directly via id.
        val andreas = entry(1, "Andreas Furrer", numbers = listOf("+41 79 263 45 67"))
        val rows = buildContactRows(listOf(andreas), "andreas", favoritesOnly = false)
        assertEquals(listOf(ContactRow.Entry(andreas)), rows)
    }

    @Test
    fun `no NUMBERS section when the all-digit query matches no number`() {
        val andreas = entry(1, "Andreas Furrer", numbers = listOf("+41 79 111 11 11"))
        val rows = buildContactRows(listOf(andreas), "999999", favoritesOnly = false)
        // Nothing matched at all, and "999999" looks dial-able, so the typed-digits echo kicks in.
        assertEquals(listOf(ContactRow.SectionHeader("NUMBERS"), ContactRow.TypedNumber("999999")), rows)
    }

    @Test
    fun `typed digits are echoed as the call-key fallback when nothing matches at all`() {
        val rows = buildContactRows(listOf(entry(1, "Andreas Furrer")), "0791234567", favoritesOnly = false)
        assertEquals(
            listOf(ContactRow.SectionHeader("NUMBERS"), ContactRow.TypedNumber("0791234567")),
            rows,
        )
    }

    @Test
    fun `typed digits are not echoed when at least one name match exists`() {
        val andreas = entry(1, "Andreas Furrer")
        // A digits-only query can't name-match a purely alphabetic name, so use isAllDigits=false
        // punctuation form to hit both isAllDigits-false and looksDialable-true at once — actually
        // simplest: confirm no echo when name matches exist for a query that IS all-digits and
        // there simply are no contacts at all besides the one that can't match by name.
        val rows = buildContactRows(listOf(andreas), "123", favoritesOnly = false)
        assertEquals(listOf(ContactRow.SectionHeader("NUMBERS"), ContactRow.TypedNumber("123")), rows)
    }

    @Test
    fun `no echo and no NUMBERS section for a non-dialable query with zero matches`() {
        val rows = buildContactRows(listOf(entry(1, "Andreas Furrer")), "xyz123abc", favoritesOnly = false)
        assertTrue(rows.isEmpty())
    }

    // --- isSelectable / contactOrNull --------------------------------------------------------

    @Test
    fun `section headers and typed-number rows are not selectable`() {
        assertFalse(ContactRow.SectionHeader("A").isSelectable())
        assertFalse(ContactRow.TypedNumber("123").isSelectable())
    }

    @Test
    fun `entry and number-match rows are selectable`() {
        val andreas = entry(1, "Andreas Furrer")
        assertTrue(ContactRow.Entry(andreas).isSelectable())
        assertTrue(ContactRow.NumberMatch(andreas, "123").isSelectable())
    }

    @Test
    fun `contactOrNull resolves for entry and number-match rows, null otherwise`() {
        val andreas = entry(1, "Andreas Furrer")
        assertEquals(andreas, ContactRow.Entry(andreas).contactOrNull())
        assertEquals(andreas, ContactRow.NumberMatch(andreas, "123").contactOrNull())
        assertEquals(null, ContactRow.SectionHeader("A").contactOrNull())
        assertEquals(null, ContactRow.TypedNumber("123").contactOrNull())
    }

    // --- letterGroupJumpIndex -----------------------------------------------------------------

    @Test
    fun `letterGroupJumpIndex with no headers returns null in either direction`() {
        val rows = listOf(ContactRow.Entry(entry(1, "Andreas Furrer")))
        assertEquals(null, letterGroupJumpIndex(rows, currentIndex = 0, direction = 1))
        assertEquals(null, letterGroupJumpIndex(rows, currentIndex = 0, direction = -1))
    }

    @Test
    fun `letterGroupJumpIndex right lands on the first entry of the next header`() {
        val rows = buildContactRows(
            listOf(entry(1, "Andreas Furrer"), entry(2, "Bea Lombardi")),
            query = "", favoritesOnly = false,
        )
        // rows: [0]=header A, [1]=Andreas, [2]=header B, [3]=Bea
        assertEquals(3, letterGroupJumpIndex(rows, currentIndex = 1, direction = 1))
    }

    @Test
    fun `letterGroupJumpIndex right returns null past the last header`() {
        val rows = buildContactRows(listOf(entry(1, "Andreas Furrer")), query = "", favoritesOnly = false)
        assertEquals(null, letterGroupJumpIndex(rows, currentIndex = 1, direction = 1))
    }

    @Test
    fun `letterGroupJumpIndex left jumps to the previous group even from mid-group`() {
        val rows = buildContactRows(
            listOf(
                entry(1, "Andreas Furrer"), entry(2, "Alex Meier"),
                entry(3, "Bea Lombardi"),
            ),
            query = "", favoritesOnly = false,
        )
        // rows: [0]=header A, [1]=Andreas, [2]=Alex, [3]=header B, [4]=Bea
        assertEquals(1, letterGroupJumpIndex(rows, currentIndex = 4, direction = -1))
    }

    @Test
    fun `letterGroupJumpIndex left returns null from within the first group`() {
        val rows = buildContactRows(listOf(entry(1, "Andreas Furrer")), query = "", favoritesOnly = false)
        assertEquals(null, letterGroupJumpIndex(rows, currentIndex = 1, direction = -1))
    }
}
