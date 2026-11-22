package life.furrer.nine.contacts

import android.content.ContentValues
import android.provider.ContactsContract.CommonDataKinds as CDK
import android.util.Xml
import java.io.StringReader
import life.furrer.nine.core.Nine
import life.furrer.nine.core.NineApp
import life.furrer.nine.core.NineSurface
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** On the phone: after DAVx5 re-downloaded its address book, every synced raw contact had a
 *  higher id than the WhatsApp/Signal mirrors linked to it, and "lowest id" picked the mirror. */
@RunWith(RobolectricTestRunner::class)
class AccountTypesTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        Nine.init(NineApp(id = "accounttypes-test", name = "Test", tagline = "", surfaces = listOf(NineSurface("", "", "", emptyList())), settingsActivity = Any::class.java))
        FakeContactsProvider.reset()
        Robolectric.buildContentProvider(FakeContactsProvider::class.java).create("com.android.contacts")
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS)
        AccountTypes.overrideReadOnlyTypes(setOf("com.whatsapp"))
    }

    @After
    fun tearDown() = AccountTypes.overrideReadOnlyTypes(null)

    private fun parser(xml: String) = Xml.newPullParser().apply { setInput(StringReader(xml)) }

    @Test
    fun `a structure with an EditSchema is editable, one without is display-only`() {
        assertTrue(AccountTypes.declaresEditSchema(parser("<ContactsAccountType><EditSchema><DataKind kind=\"name\"/></EditSchema></ContactsAccountType>")))
        assertFalse(AccountTypes.declaresEditSchema(parser("<ContactsAccountType><ContactsDataKind android:mimeType=\"vnd.x\" xmlns:android=\"http://schemas.android.com/apk/res/android\"/></ContactsAccountType>")))
    }

    @Test
    fun `an unreadable structure is treated as editable, never the other way round`() {
        assertTrue(AccountTypes.declaresEditSchema(parser("<ContactsAccountType><unclosed")))
    }

    @Test
    fun `local and unknown account types are editable`() {
        assertTrue(AccountTypes.isEditable(context, null))
        assertTrue(AccountTypes.isEditable(context, "bitfire.at.davdroid"))
        assertFalse(AccountTypes.isEditable(context, "com.whatsapp"))
    }

    /** A contact linked across a mirror (lower id) and the real address book (higher id). */
    private fun putLinkedContact() {
        FakeContactsProvider.putContact(1L, "Jann Furrer")
        FakeContactsProvider.putRawContact(408L, contactId = 1L, accountName = "WhatsApp", accountType = "com.whatsapp", dirty = true)
        FakeContactsProvider.putRawContact(837L, contactId = 1L, accountName = "Family", accountType = "bitfire.at.davdroid")
        FakeContactsProvider.putData(
            10L, 1L, 408L, CDK.StructuredName.CONTENT_ITEM_TYPE,
            ContentValues().apply { put(CDK.StructuredName.DISPLAY_NAME, "Jann (WhatsApp)") },
        )
        FakeContactsProvider.putData(
            11L, 1L, 837L, CDK.StructuredName.CONTENT_ITEM_TYPE,
            ContentValues().apply { put(CDK.StructuredName.DISPLAY_NAME, "Jann Furrer") },
        )
    }

    @Test
    fun `the real address book's copy is the primary one, even with a higher id`() {
        putLinkedContact()
        val detail = ContactStore.queryContactDetail(context, 1L)!!
        assertEquals(837L, detail.primaryRawContact?.rawContactId)
        assertEquals("Jann Furrer", detail.displayName)
    }

    @Test
    fun `a mirror's never-cleared DIRTY doesn't make the contact look unsynced`() {
        putLinkedContact()
        assertFalse(ContactStore.queryContactDetail(context, 1L)!!.hasPendingSync)
    }

    @Test
    fun `the contacts list names a linked contact from its real address book copy`() {
        putLinkedContact()
        assertEquals("Jann Furrer", ContactStore.queryContacts(context).single().displayName)
    }

    @Test
    fun `mirror accounts aren't offered as writable`() {
        putLinkedContact()
        assertEquals(listOf("Family"), ContactStore.queryWritableAccounts(context).map { it.name })
    }

    @Test
    fun `with no editable copy at all, the lowest id is still the primary`() {
        val raws = listOf(
            RawContactAccount(9L, "WhatsApp", "com.whatsapp", dirty = false, editable = false),
            RawContactAccount(4L, "Signal", "org.signal", dirty = false, editable = false),
        )
        assertEquals(4L, primaryOf(raws)?.rawContactId)
    }
}
