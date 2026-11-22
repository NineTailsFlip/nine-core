package life.furrer.nine.contacts

import android.content.ContentValues
import android.provider.ContactsContract.CommonDataKinds as CDK
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
class ContactStoreWriteTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        FakeContactsProvider.reset()
        Robolectric.buildContentProvider(FakeContactsProvider::class.java).create("com.android.contacts")
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS)
    }

    @Test
    fun `hasWritePermission reflects a granted WRITE_CONTACTS permission`() {
        assertTrue(ContactStore.hasWritePermission(context))
    }

    @Test
    fun `write operations fail closed without WRITE_CONTACTS`() {
        shadowOf(context).denyPermissions(android.Manifest.permission.WRITE_CONTACTS)
        assertNull(ContactStore.insertContact(context, null, emptyList()))
        assertFalse(ContactStore.deleteContact(context, 1L))
        assertFalse(ContactStore.applyFieldChanges(context, listOf(ContactStore.FieldChange.Delete(1L))))
    }

    @Test
    fun `queryWritableAccounts returns the distinct accounts behind existing raw contacts`() {
        FakeContactsProvider.putRawContact(100L, contactId = 1L, accountName = "fastmail", accountType = "com.fastmail")
        FakeContactsProvider.putRawContact(101L, contactId = 2L, accountName = "fastmail", accountType = "com.fastmail")
        FakeContactsProvider.putRawContact(102L, contactId = 3L, accountName = "work", accountType = "com.work")

        val accounts = ContactStore.queryWritableAccounts(context)

        assertEquals(
            setOf(ContactStore.WritableAccount("fastmail", "com.fastmail"), ContactStore.WritableAccount("work", "com.work")),
            accounts.toSet(),
        )
    }

    @Test
    fun `insertContact writes a raw contact and every field in one batch`() {
        val contactId = ContactStore.insertContact(
            context,
            account = ContactStore.WritableAccount("fastmail", "com.fastmail"),
            fields = listOf(
                DataKind.NAME to ContentValues().apply { put(CDK.StructuredName.DISPLAY_NAME, "Andreas Furrer") },
                DataKind.PHONE to ContentValues().apply { put(CDK.Phone.NUMBER, "+41 79 123 45 67") },
            ),
        )

        assertTrue(contactId != null)
        val detail = ContactStore.queryContactDetail(context, contactId!!)!!
        assertEquals(2, detail.rows.size)
        assertEquals(1, detail.rawContacts.size)
        assertEquals("fastmail", detail.rawContacts.single().accountName)
        val phoneRow = detail.rows.first { it.mimetype == CDK.Phone.CONTENT_ITEM_TYPE }
        assertEquals("+41 79 123 45 67", phoneRow.values.getAsString(CDK.Phone.NUMBER))
    }

    @Test
    fun `insertContact with no account still creates a local contact`() {
        val contactId = ContactStore.insertContact(
            context, account = null,
            fields = listOf(DataKind.NAME to ContentValues().apply { put(CDK.StructuredName.DISPLAY_NAME, "Local Contact") }),
        )
        assertTrue(contactId != null)
    }

    @Test
    fun `applyFieldChanges inserts a new field onto an existing raw contact`() {
        FakeContactsProvider.putContact(1L, "Andreas Furrer")
        FakeContactsProvider.putRawContact(100L, contactId = 1L, accountName = "fastmail", accountType = "com.fastmail")

        val success = ContactStore.applyFieldChanges(
            context,
            listOf(
                ContactStore.FieldChange.Insert(
                    rawContactId = 100L, kind = DataKind.EMAIL,
                    values = ContentValues().apply { put(CDK.Email.ADDRESS, "andreas@acme.ch") },
                ),
            ),
        )

        assertTrue(success)
        val detail = ContactStore.queryContactDetail(context, 1L)!!
        assertEquals("andreas@acme.ch", detail.rows.single().values.getAsString(CDK.Email.ADDRESS))
    }

    @Test
    fun `applyFieldChanges updates an existing field in place`() {
        FakeContactsProvider.putContact(1L, "Andreas Furrer")
        FakeContactsProvider.putData(
            id = 10L, contactId = 1L, rawContactId = 100L, mimetype = CDK.Phone.CONTENT_ITEM_TYPE,
            values = ContentValues().apply { put(CDK.Phone.NUMBER, "+41 79 000 00 00") },
        )

        val success = ContactStore.applyFieldChanges(
            context,
            listOf(ContactStore.FieldChange.Update(10L, ContentValues().apply { put(CDK.Phone.NUMBER, "+41 79 123 45 67") })),
        )

        assertTrue(success)
        val detail = ContactStore.queryContactDetail(context, 1L)!!
        assertEquals("+41 79 123 45 67", detail.rows.single().values.getAsString(CDK.Phone.NUMBER))
    }

    @Test
    fun `applyFieldChanges deletes a field`() {
        FakeContactsProvider.putContact(1L, "Andreas Furrer")
        FakeContactsProvider.putData(
            id = 10L, contactId = 1L, rawContactId = 100L, mimetype = CDK.Phone.CONTENT_ITEM_TYPE,
            values = ContentValues().apply { put(CDK.Phone.NUMBER, "+41 79 123 45 67") },
        )

        val success = ContactStore.applyFieldChanges(context, listOf(ContactStore.FieldChange.Delete(10L)))

        assertTrue(success)
        assertTrue(ContactStore.queryContactDetail(context, 1L)!!.rows.isEmpty())
    }

    @Test
    fun `applyFieldChanges never touches a row of an unknown mimetype it was not told about`() {
        FakeContactsProvider.putContact(1L, "Andreas Furrer")
        FakeContactsProvider.putData(
            id = 10L, contactId = 1L, rawContactId = 100L, mimetype = "vnd.android.cursor.item/vendor.custom.field",
            values = ContentValues().apply { put("data1", "vendor value") },
        )
        FakeContactsProvider.putData(
            id = 11L, contactId = 1L, rawContactId = 100L, mimetype = CDK.Phone.CONTENT_ITEM_TYPE,
            values = ContentValues().apply { put(CDK.Phone.NUMBER, "+41 79 000 00 00") },
        )

        val success = ContactStore.applyFieldChanges(
            context,
            listOf(ContactStore.FieldChange.Update(11L, ContentValues().apply { put(CDK.Phone.NUMBER, "+41 79 123 45 67") })),
        )

        assertTrue(success)
        val detail = ContactStore.queryContactDetail(context, 1L)!!
        val unknownRow = detail.rows.first { it.mimetype == "vnd.android.cursor.item/vendor.custom.field" }
        assertEquals("vendor value", unknownRow.values.getAsString("data1"))
    }

    @Test
    fun `deleteContact removes every raw contact and data row behind an aggregated contact`() {
        FakeContactsProvider.putContact(1L, "Andreas Furrer")
        FakeContactsProvider.putRawContact(100L, contactId = 1L, accountName = "fastmail", accountType = "com.fastmail")
        FakeContactsProvider.putRawContact(101L, contactId = 1L, accountName = "work", accountType = "com.work")
        FakeContactsProvider.putData(
            id = 10L, contactId = 1L, rawContactId = 100L, mimetype = CDK.Phone.CONTENT_ITEM_TYPE,
            values = ContentValues().apply { put(CDK.Phone.NUMBER, "+41 79 123 45 67") },
        )

        val deleted = ContactStore.deleteContact(context, 1L)

        assertTrue(deleted)
        assertTrue(FakeContactsProvider.rawContacts.isEmpty())
        assertTrue(FakeContactsProvider.data.isEmpty())
        assertTrue(FakeContactsProvider.contacts.isEmpty())
    }

    @Test
    fun `deleteContact for an unknown id returns false`() {
        assertFalse(ContactStore.deleteContact(context, 999L))
    }
}
