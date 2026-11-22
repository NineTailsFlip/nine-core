package life.furrer.nine.contacts

import life.furrer.nine.contacts.GroupSync.BaselineEntry
import life.furrer.nine.contacts.GroupSync.ContactState
import life.furrer.nine.contacts.GroupSync.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The pure three-way merge, no provider involved. Contact N's raw contact in the group's
 *  account is 100+N with SOURCE_ID "vN", unless a test says otherwise. */
class GroupSyncReconcileTest {

    private fun joinable(id: Long, hasProperty: Boolean, inGroup: Boolean) =
        ContactState(id, hasProperty, inGroup, mapOf(100 + id to "v$id"))

    private fun local(id: Long, hasProperty: Boolean) = ContactState(id, hasProperty, inGroup = false, accountRawContacts = emptyMap())

    private fun entry(id: Long) = BaselineEntry(100 + id, "v$id")

    private fun plan(
        give: Set<Long> = emptySet(),
        takeAway: Set<Long> = emptySet(),
        add: Set<Long> = emptySet(),
        remove: Set<Long> = emptySet(),
        baseline: Set<BaselineEntry> = emptySet(),
    ) = Plan(give, takeAway, add, remove, baseline)

    // --- First run (no baseline) --------------------------------------------------------------

    @Test
    fun `first run merges both sides`() {
        val result = GroupSync.reconcile(
            listOf(
                joinable(1, hasProperty = true, inGroup = false),
                joinable(2, hasProperty = false, inGroup = true),
                joinable(3, hasProperty = true, inGroup = true),
            ),
            baseline = null,
        )
        assertEquals(plan(give = setOf(2), add = setOf(1), baseline = setOf(entry(1), entry(2), entry(3))), result)
    }

    @Test
    fun `first run with an empty baseline behaves the same as no baseline`() {
        val contacts = listOf(joinable(1, hasProperty = true, inGroup = false), joinable(2, hasProperty = false, inGroup = true))
        assertEquals(GroupSync.reconcile(contacts, null), GroupSync.reconcile(contacts, emptySet()))
    }

    // --- Additions -----------------------------------------------------------------------------

    @Test
    fun `starring on the phone adds to the group`() {
        val result = GroupSync.reconcile(listOf(joinable(1, hasProperty = true, inGroup = false)), baseline = emptySet())
        assertEquals(plan(add = setOf(1), baseline = setOf(entry(1))), result)
    }

    @Test
    fun `joining the group on the server stars on the phone`() {
        val result = GroupSync.reconcile(listOf(joinable(1, hasProperty = false, inGroup = true)), baseline = emptySet())
        assertEquals(plan(give = setOf(1), baseline = setOf(entry(1))), result)
    }

    @Test
    fun `an unchanged favorite needs no writes`() {
        val result = GroupSync.reconcile(listOf(joinable(1, hasProperty = true, inGroup = true)), setOf(entry(1)))
        assertEquals(plan(baseline = setOf(entry(1))), result)
        assertFalse(result.hasWrites)
    }

    // --- Removals ------------------------------------------------------------------------------

    @Test
    fun `unstarring on the phone removes from the group`() {
        val result = GroupSync.reconcile(listOf(joinable(1, hasProperty = false, inGroup = true)), setOf(entry(1)))
        assertEquals(plan(remove = setOf(1)), result)
    }

    @Test
    fun `leaving the group on the server unstars on the phone`() {
        val result = GroupSync.reconcile(listOf(joinable(1, hasProperty = true, inGroup = false)), setOf(entry(1)))
        assertEquals(plan(takeAway = setOf(1)), result)
    }

    @Test
    fun `removed on both sides just drops out of the baseline`() {
        // Neither starred nor a member, so the runner doesn't even pass it in.
        val result = GroupSync.reconcile(emptyList(), setOf(entry(1)))
        assertEquals(plan(), result)
    }

    @Test
    fun `re-adding after a removal is an addition again`() {
        val afterRemoval = GroupSync.reconcile(listOf(joinable(1, hasProperty = true, inGroup = false)), setOf(entry(1)))
        assertEquals(setOf(1L), afterRemoval.takeAway)

        val reStarred = GroupSync.reconcile(listOf(joinable(1, hasProperty = true, inGroup = false)), afterRemoval.baseline)
        assertEquals(plan(add = setOf(1), baseline = setOf(entry(1))), reStarred)
    }

    @Test
    fun `mixed changes in one run are handled per contact`() {
        val result = GroupSync.reconcile(
            listOf(
                joinable(1, hasProperty = true, inGroup = true), // unchanged
                joinable(2, hasProperty = false, inGroup = true), // unstarred locally
                joinable(3, hasProperty = true, inGroup = false), // removed remotely
                joinable(4, hasProperty = true, inGroup = false), // newly starred
                joinable(5, hasProperty = false, inGroup = true), // newly added remotely
            ),
            setOf(entry(1), entry(2), entry(3)),
        )
        assertEquals(
            plan(give = setOf(5), takeAway = setOf(3), add = setOf(4), remove = setOf(2), baseline = setOf(entry(1), entry(4), entry(5))),
            result,
        )
    }

    // --- Non-joinable contacts -----------------------------------------------------------------

    @Test
    fun `a starred contact that can't join stays starred and stays out of the baseline`() {
        assertEquals(plan(), GroupSync.reconcile(listOf(local(1, hasProperty = true)), baseline = null))
        assertEquals(plan(), GroupSync.reconcile(listOf(local(1, hasProperty = true)), baseline = emptySet()))
    }

    @Test
    fun `a non-joinable contact is never unstarred, even if its id was once in the baseline`() {
        // Its raw contact in the account is gone (unlinked or deleted on the server).
        val result = GroupSync.reconcile(listOf(local(1, hasProperty = true)), setOf(entry(1)))
        assertEquals(plan(), result)
    }

    @Test
    fun `a starred contact that becomes joinable joins the group instead of being unstarred`() {
        // It was starred while local-only, so it was never recorded in the baseline.
        val baseline = GroupSync.reconcile(listOf(local(1, hasProperty = true)), baseline = emptySet()).baseline
        val result = GroupSync.reconcile(listOf(joinable(1, hasProperty = true, inGroup = false)), baseline)
        assertEquals(plan(add = setOf(1), baseline = setOf(entry(1))), result)
    }

    @Test
    fun `non-joinable contacts don't affect joinable ones`() {
        val result = GroupSync.reconcile(
            listOf(local(1, hasProperty = true), local(2, hasProperty = false), joinable(3, hasProperty = true, inGroup = true)),
            setOf(entry(3)),
        )
        assertEquals(plan(baseline = setOf(entry(3))), result)
    }

    // --- Identity ------------------------------------------------------------------------------

    @Test
    fun `a contact deleted entirely drops out of the baseline`() {
        val result = GroupSync.reconcile(listOf(joinable(2, hasProperty = true, inGroup = true)), setOf(entry(1), entry(2)))
        assertEquals(plan(baseline = setOf(entry(2))), result)
    }

    @Test
    fun `the baseline follows the raw contact, not the aggregate id`() {
        // Raw contact 101 was favorite under contact 1; Android re-aggregated it into contact 7.
        val moved = ContactState(7, hasProperty = true, inGroup = false, accountRawContacts = mapOf(101L to "v1"))
        val result = GroupSync.reconcile(listOf(moved), setOf(entry(1)))
        assertEquals(plan(takeAway = setOf(7)), result)
    }

    @Test
    fun `a reused raw contact id with a different SOURCE_ID is not mistaken for the old favorite`() {
        val reused = ContactState(9, hasProperty = true, inGroup = false, accountRawContacts = mapOf(101L to "other-vcard"))
        val result = GroupSync.reconcile(listOf(reused), setOf(entry(1)))
        assertEquals(plan(add = setOf(9), baseline = setOf(BaselineEntry(101, "other-vcard"))), result)
    }

    @Test
    fun `an entry recorded before the first upload still matches once a SOURCE_ID is assigned`() {
        val result = GroupSync.reconcile(listOf(joinable(1, hasProperty = true, inGroup = false)), setOf(BaselineEntry(101, null)))
        assertEquals(plan(takeAway = setOf(1)), result)
    }

    @Test
    fun `a contact with two raw contacts in the account records both and matches on either`() {
        val twice = ContactState(1, hasProperty = true, inGroup = true, accountRawContacts = mapOf(101L to "a", 102L to "b"))
        val first = GroupSync.reconcile(listOf(twice), baseline = null)
        assertEquals(setOf(BaselineEntry(101, "a"), BaselineEntry(102, "b")), first.baseline)

        // Split apart again: each half still counts as a remembered favorite.
        val halfA = ContactState(1, hasProperty = true, inGroup = false, accountRawContacts = mapOf(101L to "a"))
        val halfB = ContactState(2, hasProperty = true, inGroup = true, accountRawContacts = mapOf(102L to "b"))
        val result = GroupSync.reconcile(listOf(halfA, halfB), first.baseline)
        assertEquals(plan(takeAway = setOf(1), baseline = setOf(BaselineEntry(102, "b"))), result)
    }
}
