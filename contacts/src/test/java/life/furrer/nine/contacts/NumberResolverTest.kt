package life.furrer.nine.contacts

import android.content.ContentValues
import android.provider.ContactsContract.CommonDataKinds as CDK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class NumberResolverTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        FakeContactsProvider.reset()
        Robolectric.buildContentProvider(FakeContactsProvider::class.java).create("com.android.contacts")
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_CONTACTS)
    }

    private fun owner(number: String, id: Long, name: String = "Contact $id") = PhoneOwner(number, id, name)

    @Test
    fun `a national number matches the same number stored in international form`() {
        val result = matchNumbers(listOf("079 123 45 67"), listOf(owner("+41 79 123 45 67", 1L, "Ana")))
        assertEquals(ResolvedNumber(1L, "Ana"), result["079 123 45 67"])
    }

    @Test
    fun `numbers that only share their last digits don't match`() {
        val result = matchNumbers(listOf("+41 79 123 45 67"), listOf(owner("+49 30 123 45 67", 1L)))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `an exact digit match beats a loose one when two contacts share a number's tail`() {
        val phones = listOf(owner("+41 79 123 45 67", 1L), owner("079 123 45 67", 2L))
        assertEquals(2L, matchNumbers(listOf("0791234567"), phones)["0791234567"]?.contactId)
    }

    @Test
    fun `short numbers only match exactly`() {
        val phones = listOf(owner("144", 1L), owner("1144", 2L))
        assertEquals(1L, matchNumbers(listOf("144"), phones)["144"]?.contactId)
    }

    @Test
    fun `alphanumeric senders and blank numbers resolve to nothing`() {
        assertTrue(matchNumbers(listOf("Migros", "", "  "), listOf(owner("144", 1L))).isEmpty())
    }

    @Test
    fun `resolveNumbers answers a whole batch from the address book`() {
        FakeContactsProvider.putContact(1L, "Ana Meier")
        FakeContactsProvider.putData(
            id = 10L, contactId = 1L, rawContactId = 1L, mimetype = CDK.Phone.CONTENT_ITEM_TYPE,
            values = ContentValues().apply { put(CDK.Phone.NUMBER, "+41791234567") },
        )

        val result = ContactStore.resolveNumbers(context, listOf("079 123 45 67", "044 555 12 12", "Migros"))

        assertEquals(mapOf("079 123 45 67" to ResolvedNumber(1L, "Ana Meier")), result)
    }

    @Test
    fun `resolveNumbers is empty without READ_CONTACTS`() {
        shadowOf(context).denyPermissions(android.Manifest.permission.READ_CONTACTS)
        FakeContactsProvider.putContact(1L, "Ana Meier")
        FakeContactsProvider.putData(
            id = 10L, contactId = 1L, rawContactId = 1L, mimetype = CDK.Phone.CONTENT_ITEM_TYPE,
            values = ContentValues().apply { put(CDK.Phone.NUMBER, "079 123 45 67") },
        )
        assertTrue(ContactStore.resolveNumbers(context, listOf("079 123 45 67")).isEmpty())
    }
}
