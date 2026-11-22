package life.furrer.nine.contacts

import life.furrer.nine.contacts.ContactStore.ContactGroup
import life.furrer.nine.contacts.ContactStore.WritableAccount
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
class ContactGroupsTest {

    private val context = RuntimeEnvironment.getApplication()
    private val dav = WritableAccount("me@dav", "bitfire.at.davdroid")
    private val group = ContactGroup(5L, "Favorites", dav.name, dav.type)

    @Before
    fun setUp() {
        FakeContactsProvider.reset()
        Robolectric.buildContentProvider(FakeContactsProvider::class.java).create("com.android.contacts")
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS)
        FakeContactsProvider.putGroup(5L, "Favorites", dav.name, dav.type)
    }

    /** Contact 1: a DAV vCard plus a WhatsApp mirror. Contact 2: local only. */
    private fun twoContacts() {
        FakeContactsProvider.putContact(1L, "Mara")
        FakeContactsProvider.putRawContact(100L, contactId = 1L, accountName = dav.name, accountType = dav.type)
        FakeContactsProvider.putRawContact(101L, contactId = 1L, accountName = "wa", accountType = "com.whatsapp")
        FakeContactsProvider.putContact(2L, "Local")
        FakeContactsProvider.putRawContact(200L, contactId = 2L, accountName = null, accountType = null)
    }

    @Test
    fun `createGroup inserts a titled group in the account and it can be read back`() {
        val created = ContactGroups.createGroup(context, dav, "Friends")!!

        assertEquals(ContactGroup(created.id, "Friends", dav.name, dav.type), created)
        assertEquals(created, ContactGroups.queryGroup(context, created.id))
        assertTrue(ContactStore.queryGroups(context).any { it.id == created.id })
    }

    @Test
    fun `createGroup fails closed without WRITE_CONTACTS`() {
        shadowOf(context).denyPermissions(android.Manifest.permission.WRITE_CONTACTS)
        assertNull(ContactGroups.createGroup(context, dav, "Friends"))
    }

    @Test
    fun `queryGroup reports a deleted or unknown group as gone`() {
        FakeContactsProvider.putGroup(6L, "Old", dav.name, dav.type, deleted = true)
        assertNull(ContactGroups.queryGroup(context, 6L))
        assertNull(ContactGroups.queryGroup(context, 99L))
    }

    @Test
    fun `queryStarredContactIds returns only starred contacts`() {
        FakeContactsProvider.putContact(1L, "A", starred = true)
        FakeContactsProvider.putContact(2L, "B")
        assertEquals(setOf(1L), ContactGroups.queryStarredContactIds(context))
    }

    @Test
    fun `queryMembership knows joinability and members, ignoring other accounts and deleted raw contacts`() {
        twoContacts()
        FakeContactsProvider.putContact(3L, "Gone")
        FakeContactsProvider.putRawContact(300L, contactId = 3L, accountName = dav.name, accountType = dav.type, deleted = true)
        FakeContactsProvider.putMembership(1L, contactId = 1L, rawContactId = 100L, groupId = 5L)
        FakeContactsProvider.putMembership(2L, contactId = 3L, rawContactId = 300L, groupId = 5L)
        // A stray membership row on a raw contact outside the account doesn't count.
        FakeContactsProvider.putMembership(3L, contactId = 2L, rawContactId = 200L, groupId = 5L)

        val membership = ContactGroups.queryMembership(context, group)

        assertTrue(membership.isJoinable(1L))
        assertFalse(membership.isJoinable(2L))
        assertFalse(membership.isJoinable(3L))
        assertEquals(setOf(1L), membership.memberContactIds)
        assertEquals(setOf(100L), membership.accountRawContacts[1L])
    }

    @Test
    fun `addToGroup adds via the raw contact in the group's account and is idempotent`() {
        twoContacts()

        assertEquals(MembershipResult.CHANGED, ContactGroups.addToGroup(context, 1L, group))
        assertEquals(setOf(100L), FakeContactsProvider.memberRawContactIds(5L))
        assertEquals(MembershipResult.UNCHANGED, ContactGroups.addToGroup(context, 1L, group))
        assertEquals(1, FakeContactsProvider.data.size)
    }

    @Test
    fun `addToGroup reports a contact with no raw contact in the account as not joinable`() {
        twoContacts()
        assertEquals(MembershipResult.NOT_JOINABLE, ContactGroups.addToGroup(context, 2L, group))
        assertTrue(FakeContactsProvider.memberRawContactIds(5L).isEmpty())
    }

    @Test
    fun `addToGroup picks the lowest raw contact id when a contact has two in the account`() {
        twoContacts()
        FakeContactsProvider.putRawContact(99L, contactId = 1L, accountName = dav.name, accountType = dav.type)
        ContactGroups.addToGroup(context, 1L, group)
        assertEquals(setOf(99L), FakeContactsProvider.memberRawContactIds(5L))
    }

    @Test
    fun `removeFromGroup removes every membership row of the contact and is idempotent`() {
        twoContacts()
        FakeContactsProvider.putRawContact(99L, contactId = 1L, accountName = dav.name, accountType = dav.type)
        FakeContactsProvider.putMembership(1L, contactId = 1L, rawContactId = 99L, groupId = 5L)
        FakeContactsProvider.putMembership(2L, contactId = 1L, rawContactId = 100L, groupId = 5L)
        FakeContactsProvider.putMembership(3L, contactId = 1L, rawContactId = 100L, groupId = 7L)

        assertEquals(MembershipResult.CHANGED, ContactGroups.removeFromGroup(context, 1L, group))
        assertTrue(FakeContactsProvider.memberRawContactIds(5L).isEmpty())
        assertEquals(setOf(100L), FakeContactsProvider.memberRawContactIds(7L)) // other group untouched
        assertEquals(MembershipResult.UNCHANGED, ContactGroups.removeFromGroup(context, 1L, group))
    }

    @Test
    fun `membership writes fail closed without WRITE_CONTACTS`() {
        twoContacts()
        shadowOf(context).denyPermissions(android.Manifest.permission.WRITE_CONTACTS)
        assertEquals(MembershipResult.FAILED, ContactGroups.addToGroup(context, 1L, group))
        assertNull(ContactGroups.updateMembership(context, group, add = setOf(1L), remove = emptySet()))
        assertTrue(FakeContactsProvider.data.isEmpty())
    }

    @Test
    fun `addToGroup reports FAILED when the provider write fails`() {
        twoContacts()
        FakeContactsProvider.failWrites = true
        assertEquals(MembershipResult.FAILED, ContactGroups.addToGroup(context, 1L, group))
    }

    @Test
    fun `updateMembership applies adds and removes together and reports the unjoinable`() {
        twoContacts()
        FakeContactsProvider.putContact(3L, "Member")
        FakeContactsProvider.putRawContact(300L, contactId = 3L, accountName = dav.name, accountType = dav.type)
        FakeContactsProvider.putMembership(1L, contactId = 3L, rawContactId = 300L, groupId = 5L)

        val notJoinable = ContactGroups.updateMembership(context, group, add = setOf(1L, 2L), remove = setOf(3L))

        assertEquals(setOf(2L), notJoinable)
        assertEquals(setOf(100L), FakeContactsProvider.memberRawContactIds(5L))
    }

    @Test
    fun `renameGroup changes the title and refuses a blank one`() {
        assertTrue(ContactGroups.renameGroup(context, group, "  Close friends "))
        assertEquals("Close friends", ContactGroups.queryGroup(context, 5L)!!.title)

        assertFalse(ContactGroups.renameGroup(context, group, "   "))
        assertEquals("Close friends", ContactGroups.queryGroup(context, 5L)!!.title)
    }

    @Test
    fun `renameGroup fails for a deleted group and without WRITE_CONTACTS`() {
        FakeContactsProvider.putGroup(6L, "Old", dav.name, dav.type, deleted = true)
        assertFalse(ContactGroups.renameGroup(context, ContactGroup(6L, "Old", dav.name, dav.type), "New"))

        shadowOf(context).denyPermissions(android.Manifest.permission.WRITE_CONTACTS)
        assertFalse(ContactGroups.renameGroup(context, group, "New"))
        assertEquals("Favorites", ContactGroups.queryGroup(context, 5L)!!.title)
    }

    @Test
    fun `deleteGroup marks the group deleted and drops its memberships but keeps the contacts`() {
        twoContacts()
        FakeContactsProvider.putMembership(1L, contactId = 1L, rawContactId = 100L, groupId = 5L)
        FakeContactsProvider.putMembership(2L, contactId = 1L, rawContactId = 100L, groupId = 7L)

        assertTrue(ContactGroups.deleteGroup(context, group))

        assertNull(ContactGroups.queryGroup(context, 5L))
        assertTrue(ContactStore.queryGroups(context).none { it.id == 5L })
        // Marked, not removed: the sync adapter still has to see it to delete it on the server.
        assertEquals(1, FakeContactsProvider.groups[5L]!!.getAsInteger(android.provider.ContactsContract.Groups.DELETED))
        assertTrue(FakeContactsProvider.memberRawContactIds(5L).isEmpty())
        assertEquals(setOf(100L), FakeContactsProvider.memberRawContactIds(7L))
        assertEquals(setOf(1L, 2L), FakeContactsProvider.contacts.keys)
    }

    @Test
    fun `deleteGroup fails closed without WRITE_CONTACTS`() {
        shadowOf(context).denyPermissions(android.Manifest.permission.WRITE_CONTACTS)
        assertFalse(ContactGroups.deleteGroup(context, group))
        assertEquals(group, ContactGroups.queryGroup(context, 5L))
    }

    @Test
    fun `queryMemberCounts counts distinct contacts per group in one go`() {
        twoContacts()
        FakeContactsProvider.putGroup(7L, "Work", dav.name, dav.type)
        FakeContactsProvider.putRawContact(99L, contactId = 1L, accountName = dav.name, accountType = dav.type)
        // Contact 1 is in group 5 through two raw contacts — still one member.
        FakeContactsProvider.putMembership(1L, contactId = 1L, rawContactId = 99L, groupId = 5L)
        FakeContactsProvider.putMembership(2L, contactId = 1L, rawContactId = 100L, groupId = 5L)
        FakeContactsProvider.putMembership(3L, contactId = 1L, rawContactId = 100L, groupId = 7L)
        FakeContactsProvider.putMembership(4L, contactId = 2L, rawContactId = 200L, groupId = 7L)

        assertEquals(mapOf(5L to 1, 7L to 2), ContactGroups.queryMemberCounts(context))
        assertEquals(mapOf(1L to setOf(5L, 7L), 2L to setOf(7L)), ContactGroups.queryContactGroupIds(context))
    }

    @Test
    fun `queryGroupsOfContact lists only the contact's visible groups, by title`() {
        twoContacts()
        FakeContactsProvider.putGroup(7L, "Work", dav.name, dav.type)
        FakeContactsProvider.putGroup(8L, "Archive", dav.name, dav.type, deleted = true)
        FakeContactsProvider.putGroup(9L, null, dav.name, dav.type)
        FakeContactsProvider.putData(
            10L, 1L, 100L, android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            android.content.ContentValues().apply { put(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER, "079 123 45 67") },
        )
        listOf(7L, 5L, 8L, 9L).forEachIndexed { i, groupId ->
            FakeContactsProvider.putMembership(20L + i, contactId = 1L, rawContactId = 100L, groupId = groupId)
        }
        FakeContactsProvider.putMembership(30L, contactId = 2L, rawContactId = 200L, groupId = 7L)

        assertEquals(listOf("Favorites", "Work"), ContactGroups.queryGroupsOfContact(context, 1L).map { it.title })
        assertEquals(listOf("Work"), ContactGroups.queryGroupsOfContact(context, 2L).map { it.title })
    }

    @Test
    fun `group reads come back empty without READ_CONTACTS`() {
        twoContacts()
        FakeContactsProvider.putMembership(1L, contactId = 1L, rawContactId = 100L, groupId = 5L)
        shadowOf(context).denyPermissions(android.Manifest.permission.READ_CONTACTS)

        assertTrue(ContactGroups.queryMemberCounts(context).isEmpty())
        assertTrue(ContactGroups.queryGroupsOfContact(context, 1L).isEmpty())
    }
}
