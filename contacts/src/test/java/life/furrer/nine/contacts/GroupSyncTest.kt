package life.furrer.nine.contacts

import life.furrer.nine.contacts.ContactStore.ContactGroup
import life.furrer.nine.contacts.GroupSync.BaselineEntry
import life.furrer.nine.contacts.GroupSync.GroupState
import life.furrer.nine.contacts.GroupSync.LineUpResult
import life.furrer.nine.core.Nine
import life.furrer.nine.core.NineApp
import life.furrer.nine.core.NineSurface
import life.furrer.nine.core.prefs.NinePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class GroupSyncTest {

    private val context = RuntimeEnvironment.getApplication()
    private val dav = "me@dav" to "bitfire.at.davdroid"
    private val group = ContactGroup(5L, "Favorites", dav.first, dav.second)

    @Before
    fun setUp() {
        Nine.init(NineApp(id = "favorites-test", name = "Test", tagline = "", surfaces = listOf(NineSurface("", "", "", emptyList())), settingsActivity = Any::class.java))
        NinePrefs.of(context).edit().clear().commit()
        FavoritesSync.clearBaseline(context)
        FakeContactsProvider.reset()
        Robolectric.buildContentProvider(FakeContactsProvider::class.java).create("com.android.contacts")
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS)
        FakeContactsProvider.putGroup(5L, "Favorites", dav.first, dav.second)
    }

    /** Contact [id] with its vCard raw contact 100+id in the DAV account. */
    private fun davContact(id: Long, starred: Boolean = false, member: Boolean = false) {
        FakeContactsProvider.putContact(id, "Contact $id", starred = starred)
        FakeContactsProvider.putRawContact(100 + id, contactId = id, accountName = dav.first, accountType = dav.second, sourceId = "v$id")
        if (member) FakeContactsProvider.putMembership(500 + id, contactId = id, rawContactId = 100 + id, groupId = 5L)
    }

    private fun localContact(id: Long, starred: Boolean) {
        FakeContactsProvider.putContact(id, "Local $id", starred = starred)
        FakeContactsProvider.putRawContact(100 + id, contactId = id, accountName = null, accountType = null)
    }

    private fun members() = FakeContactsProvider.memberRawContactIds(5L)
    private fun starred(id: Long) = FakeContactsProvider.isStarred(id)

    // --- The chosen group ----------------------------------------------------------------------

    @Test
    fun `the chosen group round trips and reports present, missing or none`() {
        assertNull(FavoritesSync.group(context))
        assertEquals(GroupState.None, FavoritesSync.groupState(context))

        FavoritesSync.setGroup(context, group)
        assertEquals(group, FavoritesSync.group(context))
        assertEquals(GroupState.Present(group), FavoritesSync.groupState(context))

        FakeContactsProvider.groups.clear()
        assertEquals(GroupState.Missing(group), FavoritesSync.groupState(context))

        FavoritesSync.setGroup(context, null)
        assertEquals(GroupState.None, FavoritesSync.groupState(context))
    }

    @Test
    fun `a renamed group is present with its current title`() {
        FavoritesSync.setGroup(context, group)
        FakeContactsProvider.putGroup(5L, "Faves", dav.first, dav.second)
        assertEquals(GroupState.Present(group.copy(title = "Faves")), FavoritesSync.groupState(context))
    }

    @Test
    fun `a group id now belonging to another account counts as missing`() {
        FavoritesSync.setGroup(context, group)
        FakeContactsProvider.putGroup(5L, "Favorites", "other", "com.other")
        assertEquals(GroupState.Missing(group), FavoritesSync.groupState(context))
    }

    @Test
    fun `choosing another group forgets the baseline, re-choosing the same one keeps it`() {
        FavoritesSync.setGroup(context, group)
        FavoritesSync.writeBaseline(context, 5L, setOf(BaselineEntry(101, "v1")))

        FavoritesSync.setGroup(context, group.copy(title = "renamed"))
        assertEquals(setOf(BaselineEntry(101, "v1")), FavoritesSync.readBaseline(context, 5L))

        FavoritesSync.setGroup(context, ContactGroup(6L, "Other", dav.first, dav.second))
        assertNull(FavoritesSync.readBaseline(context, 5L))
    }

    @Test
    fun `a baseline recorded for another group is ignored`() {
        FavoritesSync.writeBaseline(context, 6L, setOf(BaselineEntry(101, "v1")))
        assertNull(FavoritesSync.readBaseline(context, 5L))
    }

    @Test
    fun `the baseline is kept out of the backed-up prefs file`() {
        FavoritesSync.setGroup(context, group)
        FavoritesSync.writeBaseline(context, 5L, setOf(BaselineEntry(101, "v1")))
        assertEquals(setOf("contacts_favorites_group"), NinePrefs.of(context).all.keys)
    }

    // --- lineUp --------------------------------------------------------------------------------

    @Test
    fun `lineUp does nothing without a chosen group`() {
        davContact(1, member = true)
        assertEquals(LineUpResult.NoGroup, FavoritesSync.lineUp(context))
        assertFalse(starred(1))
    }

    @Test
    fun `lineUp fails closed without WRITE_CONTACTS`() {
        FavoritesSync.setGroup(context, group)
        davContact(1, member = true)
        shadowOf(context).denyPermissions(android.Manifest.permission.WRITE_CONTACTS)
        assertEquals(LineUpResult.NoPermission, FavoritesSync.lineUp(context))
        assertFalse(starred(1))
    }

    @Test
    fun `lineUp reports a missing group and writes nothing`() {
        FavoritesSync.setGroup(context, group)
        davContact(1, starred = true)
        FakeContactsProvider.groups.clear()
        assertEquals(LineUpResult.GroupMissing(group), FavoritesSync.lineUp(context))
        assertTrue(members().isEmpty())
    }

    @Test
    fun `first lineUp merges both sides and records the baseline`() {
        FavoritesSync.setGroup(context, group)
        davContact(1, starred = true)
        davContact(2, member = true)
        davContact(3)
        localContact(4, starred = true)

        val result = FavoritesSync.lineUp(context) as LineUpResult.Done

        assertTrue(starred(1) && starred(2) && !starred(3) && starred(4))
        assertEquals(setOf(101L, 102L), members())
        assertEquals(setOf(BaselineEntry(101, "v1"), BaselineEntry(102, "v2")), FavoritesSync.readBaseline(context, 5L))
        assertTrue(result.plan.hasWrites)
    }

    @Test
    fun `a second lineUp right after the first writes nothing`() {
        FavoritesSync.setGroup(context, group)
        davContact(1, starred = true)
        davContact(2, member = true)
        FavoritesSync.lineUp(context)

        val again = FavoritesSync.lineUp(context) as LineUpResult.Done
        assertFalse(again.plan.hasWrites)
    }

    @Test
    fun `lineUp propagates removals from either side`() {
        FavoritesSync.setGroup(context, group)
        davContact(1, starred = true, member = true)
        davContact(2, starred = true, member = true)
        FavoritesSync.lineUp(context)

        // Unstarred on the phone; removed from the group on the server.
        FakeContactsProvider.putContact(1L, "Contact 1", starred = false)
        FakeContactsProvider.data.remove(502L)
        FavoritesSync.lineUp(context)

        assertFalse(starred(1))
        assertFalse(starred(2))
        assertTrue(members().isEmpty())
        assertEquals(emptySet<BaselineEntry>(), FavoritesSync.readBaseline(context, 5L))
    }

    @Test
    fun `lineUp never unstars a contact that can't join the group`() {
        FavoritesSync.setGroup(context, group)
        localContact(1, starred = true)
        FavoritesSync.lineUp(context)
        FavoritesSync.lineUp(context)
        assertTrue(starred(1))
        assertTrue(members().isEmpty())
    }

    @Test
    fun `a failed batch leaves the baseline untouched so the next run retries`() {
        FavoritesSync.setGroup(context, group)
        davContact(1, starred = true)
        FakeContactsProvider.failWrites = true

        assertEquals(LineUpResult.Failed, FavoritesSync.lineUp(context))
        assertNull(FavoritesSync.readBaseline(context, 5L))

        FakeContactsProvider.failWrites = false
        FavoritesSync.lineUp(context)
        assertEquals(setOf(101L), members())
    }

    // --- setFavorite ---------------------------------------------------------------------------

    @Test
    fun `setFavorite without a group only stars`() {
        davContact(1)
        assertTrue(FavoritesSync.setProperty(context, 1L, true))
        assertTrue(starred(1))
        assertTrue(members().isEmpty())
    }

    @Test
    fun `setFavorite writes both sides and the baseline so lineUp finds nothing to do`() {
        FavoritesSync.setGroup(context, group)
        davContact(1)
        davContact(2, starred = true, member = true)
        FavoritesSync.lineUp(context)

        assertTrue(FavoritesSync.setProperty(context, 1L, true))
        assertTrue(FavoritesSync.setProperty(context, 2L, false))

        assertTrue(starred(1))
        assertFalse(starred(2))
        assertEquals(setOf(101L), members())
        assertEquals(setOf(BaselineEntry(101, "v1")), FavoritesSync.readBaseline(context, 5L))
        assertFalse((FavoritesSync.lineUp(context) as LineUpResult.Done).plan.hasWrites)
    }

    @Test
    fun `setFavorite on a contact that can't join just stars it`() {
        FavoritesSync.setGroup(context, group)
        localContact(1, starred = false)
        FavoritesSync.lineUp(context)

        assertTrue(FavoritesSync.setProperty(context, 1L, true))
        assertTrue(starred(1))
        assertTrue(members().isEmpty())
        assertEquals(emptySet<BaselineEntry>(), FavoritesSync.readBaseline(context, 5L))
    }

    @Test
    fun `setFavorite with a missing group just stars`() {
        FavoritesSync.setGroup(context, group)
        davContact(1)
        FakeContactsProvider.groups.clear()

        assertTrue(FavoritesSync.setProperty(context, 1L, true))
        assertTrue(starred(1))
        assertTrue(members().isEmpty())
    }

    @Test
    fun `setFavorite fails closed without WRITE_CONTACTS`() {
        davContact(1)
        shadowOf(context).denyPermissions(android.Manifest.permission.WRITE_CONTACTS)
        assertFalse(FavoritesSync.setProperty(context, 1L, true))
        assertFalse(starred(1))
    }
}
