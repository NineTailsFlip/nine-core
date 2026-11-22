package life.furrer.nine.contacts

import android.content.ContentValues
import android.provider.ContactsContract.CommonDataKinds as CDK
import life.furrer.nine.core.Nine
import life.furrer.nine.core.NineApp
import life.furrer.nine.core.NineSurface
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
class ContactStoreTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        Nine.init(NineApp(id = "contactstore-test", name = "Test", tagline = "", surfaces = listOf(NineSurface("", "", "", emptyList())), settingsActivity = Any::class.java))
        FakeContactsProvider.reset()
        Robolectric.buildContentProvider(FakeContactsProvider::class.java).create("com.android.contacts")
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_CONTACTS)
    }

    @Test
    fun `permission is denied by default in a fresh test environment`() {
        val freshContext = RuntimeEnvironment.getApplication()
        shadowOf(freshContext).denyPermissions(android.Manifest.permission.READ_CONTACTS)
        assertFalse(ContactStore.hasPermission(freshContext))
    }

    @Test
    fun `queryContacts returns nothing without contacts permission`() {
        shadowOf(context).denyPermissions(android.Manifest.permission.READ_CONTACTS)
        assertTrue(ContactStore.queryContacts(context).isEmpty())
    }

    @Test
    fun `hasPermission reflects a granted READ_CONTACTS permission`() {
        assertTrue(ContactStore.hasPermission(context))
    }

    @Test
    fun `queryContacts returns the flat contact list`() {
        FakeContactsProvider.putContact(1L, "Andreas Furrer", starred = true)
        FakeContactsProvider.putContact(2L, "Bea Lombardi")

        val result = ContactStore.queryContacts(context)

        assertEquals(2, result.size)
        assertEquals("Andreas Furrer", result[0].displayName)
        assertTrue(result[0].starred)
        assertEquals("Bea Lombardi", result[1].displayName)
        assertFalse(result[1].starred)
    }

    @Test
    fun `queryContacts populates sortKey from SORT_KEY_PRIMARY, not just the display name`() {
        // "Dr. med. Mara Furrer" is a real contact whose SORT_KEY_PRIMARY ("Mara Furrer") already
        // has the honorific prefix stripped -- ContactRows' own letter-grouping relies on sortKey
        // being this, not displayName, or the contact gets a stray "D" header (see
        // ContactRowsTest's regression test for the actual grouping bug this was found from).
        FakeContactsProvider.putContact(1L, "Dr. med. Mara Furrer", sortKey = "Mara Furrer")

        val result = ContactStore.queryContacts(context).single()

        assertEquals("Dr. med. Mara Furrer", result.displayName)
        assertEquals("Mara Furrer", result.sortKey)
    }

    @Test
    fun `queryContacts falls back to the display name when SORT_KEY_PRIMARY is blank`() {
        FakeContactsProvider.putContact(1L, "Andreas Furrer", sortKey = "")

        val result = ContactStore.queryContacts(context).single()

        assertEquals("Andreas Furrer", result.sortKey)
    }

    @Test
    fun `queryContacts bulk-loads phone numbers per contact for number search`() {
        FakeContactsProvider.putContact(1L, "Andreas Furrer")
        FakeContactsProvider.putData(
            id = 10L, contactId = 1L, rawContactId = 100L, mimetype = CDK.Phone.CONTENT_ITEM_TYPE,
            values = ContentValues().apply {
                put(CDK.Phone.NUMBER, "+41 79 123 45 67")
                put(CDK.Phone.TYPE, CDK.Phone.TYPE_MOBILE)
                put(CDK.Phone.IS_SUPER_PRIMARY, 1)
            },
        )
        FakeContactsProvider.putData(
            id = 11L, contactId = 1L, rawContactId = 100L, mimetype = CDK.Phone.CONTENT_ITEM_TYPE,
            values = ContentValues().apply {
                put(CDK.Phone.NUMBER, "+41 44 987 65 43")
                put(CDK.Phone.TYPE, CDK.Phone.TYPE_WORK)
            },
        )

        val result = ContactStore.queryContacts(context)

        val numbers = result.single().phoneNumbers
        assertEquals(listOf("+41 79 123 45 67", "+41 44 987 65 43"), numbers.map { it.number })
        assertEquals("MOBILE", numbers[0].label)
        assertTrue(numbers[0].isSuperPrimary)
        assertEquals("WORK", numbers[1].label)
        assertFalse(numbers[1].isSuperPrimary)
    }

    @Test
    fun `queryContacts collapses a number an app like WhatsApp mirrors onto its own linked raw contact`() {
        FakeContactsProvider.putContact(1L, "Andreas Furrer")
        FakeContactsProvider.putData(
            id = 10L, contactId = 1L, rawContactId = 100L, mimetype = CDK.Phone.CONTENT_ITEM_TYPE,
            values = ContentValues().apply {
                put(CDK.Phone.NUMBER, "+41 79 123 45 67")
                put(CDK.Phone.TYPE, CDK.Phone.TYPE_MOBILE)
            },
        )
        // Same number, no formatting, from a second raw contact (WhatsApp's own mirror).
        FakeContactsProvider.putData(
            id = 11L, contactId = 1L, rawContactId = 101L, mimetype = CDK.Phone.CONTENT_ITEM_TYPE,
            values = ContentValues().apply {
                put(CDK.Phone.NUMBER, "+41791234567")
                put(CDK.Phone.TYPE, CDK.Phone.TYPE_MOBILE)
                put(CDK.Phone.IS_SUPER_PRIMARY, 1)
            },
        )

        val numbers = ContactStore.queryContacts(context).single().phoneNumbers

        assertEquals(listOf("+41 79 123 45 67"), numbers.map { it.number })
        // The super-primary flag from the dropped duplicate isn't lost.
        assertTrue(numbers.single().isSuperPrimary)
    }

    @Test
    fun `queryContacts gives a contact with no phone numbers an empty list, not a crash`() {
        FakeContactsProvider.putContact(1L, "Bea Lombardi")
        assertTrue(ContactStore.queryContacts(context).single().phoneNumbers.isEmpty())
    }

    @Test
    fun `queryContactDetail returns null without permission`() {
        shadowOf(context).denyPermissions(android.Manifest.permission.READ_CONTACTS)
        assertNull(ContactStore.queryContactDetail(context, 1L))
    }

    @Test
    fun `queryContactDetail returns null for an unknown contact id`() {
        assertNull(ContactStore.queryContactDetail(context, 999L))
    }

    @Test
    fun `queryContactDetail reads every Data row for the contact`() {
        FakeContactsProvider.putContact(1L, "Andreas Furrer", lookupKey = "abc123")
        FakeContactsProvider.putRawContact(100L, contactId = 1L, accountName = "fastmail", accountType = "com.fastmail")
        FakeContactsProvider.putData(
            id = 10L, contactId = 1L, rawContactId = 100L, mimetype = CDK.Phone.CONTENT_ITEM_TYPE,
            values = ContentValues().apply {
                put(CDK.Phone.NUMBER, "+41 79 123 45 67")
                put(CDK.Phone.TYPE, CDK.Phone.TYPE_MOBILE)
            },
        )
        FakeContactsProvider.putData(
            id = 11L, contactId = 1L, rawContactId = 100L, mimetype = CDK.Email.CONTENT_ITEM_TYPE,
            values = ContentValues().apply { put(CDK.Email.ADDRESS, "andreas@acme.ch") },
        )

        val detail = ContactStore.queryContactDetail(context, 1L)!!

        assertEquals("Andreas Furrer", detail.displayName)
        assertEquals("abc123", detail.lookupKey)
        assertEquals(2, detail.rows.size)
        val phoneRow = detail.rows.first { it.mimetype == CDK.Phone.CONTENT_ITEM_TYPE }
        assertEquals("+41 79 123 45 67", phoneRow.values.getAsString(CDK.Phone.NUMBER))
        assertEquals(CDK.Phone.TYPE_MOBILE, phoneRow.values.getAsInteger(CDK.Phone.TYPE))
    }

    @Test
    fun `queryContactDetail reads sendToVoicemail as the aggregate has it`() {
        FakeContactsProvider.putContact(1L, "Flagged")
        FakeContactsProvider.putRawContact(100L, 1L, "me@dav", "dav", sendToVoicemail = true)
        FakeContactsProvider.putContact(2L, "Partly")
        FakeContactsProvider.putRawContact(200L, 2L, "me@dav", "dav", sendToVoicemail = true)
        FakeContactsProvider.putRawContact(201L, 2L, "wa", "com.whatsapp")
        FakeContactsProvider.putContact(3L, "Plain")
        FakeContactsProvider.putRawContact(300L, 3L, "me@dav", "dav")

        assertTrue(ContactStore.queryContactDetail(context, 1L)!!.sendToVoicemail)
        assertFalse(ContactStore.queryContactDetail(context, 2L)!!.sendToVoicemail)
        assertFalse(ContactStore.queryContactDetail(context, 3L)!!.sendToVoicemail)
    }

    @Test
    fun `queryContactDetail preserves rows of an unknown mimetype rather than dropping them`() {
        FakeContactsProvider.putContact(1L, "Andreas Furrer")
        FakeContactsProvider.putData(
            id = 10L, contactId = 1L, rawContactId = 100L, mimetype = "vnd.android.cursor.item/vendor.custom.field",
            values = ContentValues().apply { put("data1", "some vendor value") },
        )

        val detail = ContactStore.queryContactDetail(context, 1L)!!

        assertEquals(1, detail.rows.size)
        assertNull(DataKind.fromMimetype(detail.rows.single().mimetype))
        assertEquals("some vendor value", detail.rows.single().values.getAsString("data1"))
    }

    @Test
    fun `queryContactDetail with a single raw contact is not linked`() {
        FakeContactsProvider.putContact(1L, "Andreas Furrer")
        FakeContactsProvider.putRawContact(100L, contactId = 1L, accountName = "fastmail", accountType = "com.fastmail")

        val detail = ContactStore.queryContactDetail(context, 1L)!!

        assertFalse(detail.isLinked)
        assertEquals(1, detail.rawContacts.size)
    }

    @Test
    fun `queryContactDetail with multiple raw contacts is linked`() {
        FakeContactsProvider.putContact(1L, "Andreas Furrer")
        FakeContactsProvider.putRawContact(100L, contactId = 1L, accountName = "fastmail", accountType = "com.fastmail")
        FakeContactsProvider.putRawContact(101L, contactId = 1L, accountName = "work", accountType = "com.work")

        val detail = ContactStore.queryContactDetail(context, 1L)!!

        assertTrue(detail.isLinked)
        assertEquals(setOf("fastmail", "work"), detail.rawContacts.map { it.accountName }.toSet())
    }

    @Test
    fun `queryContactDetail has no pending sync when every raw contact is clean`() {
        FakeContactsProvider.putContact(1L, "Andreas Furrer")
        FakeContactsProvider.putRawContact(100L, contactId = 1L, accountName = "fastmail", accountType = "com.fastmail")

        val detail = ContactStore.queryContactDetail(context, 1L)!!

        assertFalse(detail.hasPendingSync)
    }

    @Test
    fun `queryContactDetail has a pending sync when a syncable raw contact is dirty`() {
        FakeContactsProvider.putContact(1L, "Andreas Furrer")
        FakeContactsProvider.putRawContact(100L, contactId = 1L, accountName = "fastmail", accountType = "com.fastmail", dirty = true)

        val detail = ContactStore.queryContactDetail(context, 1L)!!

        assertTrue(detail.hasPendingSync)
    }

    @Test
    fun `queryContactDetail ignores a dirty higher-id raw contact when the lower-id one is clean`() {
        // Confirmed on-device: a linked messaging-app mirror (e.g. WhatsApp) can stay DIRTY
        // forever regardless of real sync outcomes, so only the lowest-id raw contact — the real
        // synced source per the NAME-row resolution's own precedent — should drive this.
        FakeContactsProvider.putContact(1L, "Andreas Furrer")
        FakeContactsProvider.putRawContact(100L, contactId = 1L, accountName = "fastmail", accountType = "com.fastmail", dirty = false)
        FakeContactsProvider.putRawContact(101L, contactId = 1L, accountName = "WhatsApp", accountType = "com.whatsapp", dirty = true)

        val detail = ContactStore.queryContactDetail(context, 1L)!!

        assertFalse(detail.hasPendingSync)
    }

    @Test
    fun `queryContactDetail reports pending sync when the lower-id raw contact is dirty`() {
        FakeContactsProvider.putContact(1L, "Andreas Furrer")
        FakeContactsProvider.putRawContact(100L, contactId = 1L, accountName = "fastmail", accountType = "com.fastmail", dirty = true)
        FakeContactsProvider.putRawContact(101L, contactId = 1L, accountName = "WhatsApp", accountType = "com.whatsapp", dirty = false)

        val detail = ContactStore.queryContactDetail(context, 1L)!!

        assertTrue(detail.hasPendingSync)
    }

    @Test
    fun `queryContactDetail ignores dirty on a local-only raw contact with no account`() {
        FakeContactsProvider.putContact(1L, "Andreas Furrer")
        FakeContactsProvider.putRawContact(100L, contactId = 1L, accountName = null, accountType = null, dirty = true)

        val detail = ContactStore.queryContactDetail(context, 1L)!!

        assertFalse(detail.hasPendingSync)
    }

    @Test
    fun `queryContactDetail prefers the lowest raw contact id's own NAME row over CP2's aggregated display name`() {
        // Reproduces a real on-device bug: a synced contact (raw id 91) and WhatsApp's own linked
        // mirror (raw id 445) each have an individually-correct StructuredName.DISPLAY_NAME, but
        // CP2's own Contacts.DISPLAY_NAME_PRIMARY aggregate combined their disagreeing structured
        // components into "Dr. med. Dr. med. Mara Furrer". The two raw NAME rows are given
        // different text here (unlike on the real device, where they happen to match) to prove
        // the fix actually picks the lower raw contact id rather than coincidentally agreeing.
        FakeContactsProvider.putContact(1L, "Dr. med. Dr. med. Mara Furrer")
        FakeContactsProvider.putRawContact(91L, contactId = 1L, accountName = "family", accountType = "com.fastmail")
        FakeContactsProvider.putRawContact(445L, contactId = 1L, accountName = "WhatsApp", accountType = "com.whatsapp")
        FakeContactsProvider.putData(
            id = 10L, contactId = 1L, rawContactId = 91L, mimetype = CDK.StructuredName.CONTENT_ITEM_TYPE,
            values = ContentValues().apply { put(CDK.StructuredName.DISPLAY_NAME, "Dr. med. Mara Furrer") },
        )
        FakeContactsProvider.putData(
            id = 11L, contactId = 1L, rawContactId = 445L, mimetype = CDK.StructuredName.CONTENT_ITEM_TYPE,
            values = ContentValues().apply { put(CDK.StructuredName.DISPLAY_NAME, "WhatsApp's own guess") },
        )

        val detail = ContactStore.queryContactDetail(context, 1L)!!

        assertEquals("Dr. med. Mara Furrer", detail.displayName)
    }

    @Test
    fun `queryContactDetail falls back to the aggregated display name when no raw contact has a NAME row`() {
        FakeContactsProvider.putContact(1L, "Acme AG")
        FakeContactsProvider.putRawContact(100L, contactId = 1L, accountName = "fastmail", accountType = "com.fastmail")

        val detail = ContactStore.queryContactDetail(context, 1L)!!

        assertEquals("Acme AG", detail.displayName)
    }

    @Test
    fun `queryContacts prefers the lowest raw contact id's own NAME row, same as queryContactDetail`() {
        FakeContactsProvider.putContact(1L, "Dr. med. Dr. med. Mara Furrer")
        FakeContactsProvider.putRawContact(91L, contactId = 1L, accountName = "family", accountType = "com.fastmail")
        FakeContactsProvider.putRawContact(445L, contactId = 1L, accountName = "WhatsApp", accountType = "com.whatsapp")
        // Inserted out of raw-id order to prove the fix picks the lowest id rather than "whichever
        // row the provider happened to return first".
        FakeContactsProvider.putData(
            id = 10L, contactId = 1L, rawContactId = 445L, mimetype = CDK.StructuredName.CONTENT_ITEM_TYPE,
            values = ContentValues().apply { put(CDK.StructuredName.DISPLAY_NAME, "WhatsApp's own guess") },
        )
        FakeContactsProvider.putData(
            id = 11L, contactId = 1L, rawContactId = 91L, mimetype = CDK.StructuredName.CONTENT_ITEM_TYPE,
            values = ContentValues().apply { put(CDK.StructuredName.DISPLAY_NAME, "Dr. med. Mara Furrer") },
        )

        val result = ContactStore.queryContacts(context).single()

        assertEquals("Dr. med. Mara Furrer", result.displayName)
    }

    @Test
    fun `setStarred updates the STARRED column`() {
        FakeContactsProvider.putContact(1L, "Andreas Furrer", starred = false)

        val updated = ContactStore.setStarred(context, 1L, true)

        assertTrue(updated)
        assertTrue(ContactStore.queryContacts(context).single().starred)
    }

    // --- Groups filter (§7.4 `*` -> Groups) -------------------------------------------------

    @Test
    fun `queryGroups excludes deleted groups and groups with no title`() {
        FakeContactsProvider.putGroup(1L, "Family", "fastmail", "com.fastmail")
        FakeContactsProvider.putGroup(2L, "Old group", "fastmail", "com.fastmail", deleted = true)
        FakeContactsProvider.putGroup(3L, null, "fastmail", "com.fastmail")

        val groups = ContactStore.queryGroups(context)

        assertEquals(listOf("Family"), groups.map { it.title })
    }

    @Test
    fun `groupsByAccount groups by account name`() {
        FakeContactsProvider.putGroup(1L, "Family", "fastmail", "com.fastmail")
        FakeContactsProvider.putGroup(2L, "Work", "work", "com.work")
        FakeContactsProvider.putGroup(3L, "Friends", "fastmail", "com.fastmail")

        val grouped = ContactStore.groupsByAccount(ContactStore.queryGroups(context))

        assertEquals(setOf("fastmail", "work"), grouped.map { it.first }.toSet())
        assertEquals(2, grouped.single { it.first == "fastmail" }.second.size)
    }

    @Test
    fun `hidden groups default to none and toggle per group`() {
        assertEquals(emptySet<Long>(), ContactStore.hiddenGroupIds(context))
        assertTrue(ContactStore.isGroupShown(context, 1L))

        ContactStore.setGroupShown(context, 1L, shown = false)
        ContactStore.setGroupShown(context, 2L, shown = false)
        ContactStore.setGroupShown(context, 1L, shown = true)

        assertEquals(setOf(2L), ContactStore.hiddenGroupIds(context))
        assertTrue(ContactStore.isGroupShown(context, 1L))
        assertFalse(ContactStore.isGroupShown(context, 2L))
    }

    @Test
    fun `the old shown-groups setting converts to hidden groups, leaving later groups shown`() {
        FakeContactsProvider.putGroup(1L, "Family", "fastmail", "com.fastmail")
        FakeContactsProvider.putGroup(2L, "Work", "fastmail", "com.fastmail")
        life.furrer.nine.core.prefs.NinePrefs.of(context).edit().putString("contact_group_ids", "[1]").commit()

        assertEquals(setOf(2L), ContactStore.hiddenGroupIds(context))
        FakeContactsProvider.putGroup(3L, "New", "fastmail", "com.fastmail")
        assertEquals(setOf(2L), ContactStore.hiddenGroupIds(context))
        assertFalse(life.furrer.nine.core.prefs.NinePrefs.of(context).contains("contact_group_ids"))
    }

    @Test
    fun `the old setting waits for READ_CONTACTS rather than converting against no groups`() {
        FakeContactsProvider.putGroup(1L, "Family", "fastmail", "com.fastmail")
        FakeContactsProvider.putGroup(2L, "Work", "fastmail", "com.fastmail")
        life.furrer.nine.core.prefs.NinePrefs.of(context).edit().putString("contact_group_ids", "[1]").commit()

        shadowOf(context).denyPermissions(android.Manifest.permission.READ_CONTACTS)
        assertEquals(emptySet<Long>(), ContactStore.hiddenGroupIds(context))
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_CONTACTS)
        assertEquals(setOf(2L), ContactStore.hiddenGroupIds(context))
    }

    @Test
    fun `a contact is shown if any of its live groups is shown, and always when ungrouped`() {
        val live = setOf(1L, 2L, 3L)
        val hidden = setOf(2L)
        assertTrue(ContactStore.isShownByGroups(emptySet(), hidden, live))
        assertTrue(ContactStore.isShownByGroups(setOf(1L, 2L), hidden, live))
        assertFalse(ContactStore.isShownByGroups(setOf(2L), hidden, live))
        // A membership of a deleted/system group (not live) neither hides nor shows.
        assertFalse(ContactStore.isShownByGroups(setOf(2L, 99L), hidden, live))
        assertTrue(ContactStore.isShownByGroups(setOf(99L), hidden, live))
    }

    @Test
    fun `queryContactIdsInGroups matches only contacts with a GroupMembership row in the given groups`() {
        FakeContactsProvider.putContact(1L, "In group")
        FakeContactsProvider.putContact(2L, "Not in group")
        FakeContactsProvider.putRawContact(100L, contactId = 1L, accountName = "fastmail", accountType = "com.fastmail")
        FakeContactsProvider.putData(
            1L, contactId = 1L, rawContactId = 100L, mimetype = CDK.GroupMembership.CONTENT_ITEM_TYPE,
            values = ContentValues().apply { put(CDK.GroupMembership.GROUP_ROW_ID, 5L) },
        )

        assertEquals(setOf(1L), ContactStore.queryContactIdsInGroups(context, setOf(5L)))
        assertEquals(emptySet<Long>(), ContactStore.queryContactIdsInGroups(context, setOf(6L)))
        assertEquals(emptySet<Long>(), ContactStore.queryContactIdsInGroups(context, emptySet()))
    }

    // --- Accounts filter (§7.4 `*` -> Accounts) ----------------------------------------------

    @Test
    fun `queryAccounts returns every distinct account with a raw contact`() {
        FakeContactsProvider.putRawContact(100L, contactId = 1L, accountName = "fastmail", accountType = "com.fastmail")
        FakeContactsProvider.putRawContact(101L, contactId = 2L, accountName = "whatsapp", accountType = "com.whatsapp")
        FakeContactsProvider.putRawContact(102L, contactId = 3L, accountName = "fastmail", accountType = "com.fastmail")

        val accounts = ContactStore.queryAccounts(context)

        assertEquals(setOf("fastmail", "whatsapp"), accounts.map { it.name }.toSet())
    }

    @Test
    fun `queryAccounts requires only READ_CONTACTS, unlike queryWritableAccounts`() {
        shadowOf(context).denyPermissions(android.Manifest.permission.WRITE_CONTACTS)
        FakeContactsProvider.putRawContact(100L, contactId = 1L, accountName = "fastmail", accountType = "com.fastmail")

        assertTrue(ContactStore.queryWritableAccounts(context).isEmpty())
        assertEquals(1, ContactStore.queryAccounts(context).size)
    }

    @Test
    fun `selected accounts round trip through prefs -- null, empty, and non-empty`() {
        assertNull(ContactStore.getSelectedAccounts(context))

        ContactStore.setSelectedAccounts(context, emptySet())
        assertEquals(emptySet<ContactStore.WritableAccount>(), ContactStore.getSelectedAccounts(context))

        val account = ContactStore.WritableAccount("fastmail", "com.fastmail")
        ContactStore.setSelectedAccounts(context, setOf(account))
        assertEquals(setOf(account), ContactStore.getSelectedAccounts(context))
    }

    @Test
    fun `queryContactIdsInAccounts matches only contacts with a raw contact in the given accounts`() {
        FakeContactsProvider.putRawContact(100L, contactId = 1L, accountName = "fastmail", accountType = "com.fastmail")
        FakeContactsProvider.putRawContact(101L, contactId = 2L, accountName = "whatsapp", accountType = "com.whatsapp")

        val fastmail = ContactStore.WritableAccount("fastmail", "com.fastmail")
        assertEquals(setOf(1L), ContactStore.queryContactIdsInAccounts(context, setOf(fastmail)))
        assertEquals(emptySet<Long>(), ContactStore.queryContactIdsInAccounts(context, emptySet()))
    }
}
