package life.furrer.nine.contacts

import android.content.Intent
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ContactHandoffTest {

    @Test
    fun `new contact is an insert on the contacts directory carrying the number and type`() {
        val intent = ContactHandoff.newContactIntent("+41791234567", Phone.TYPE_WORK)

        assertEquals(Intent.ACTION_INSERT, intent.action)
        assertEquals(ContactsContract.Contacts.CONTENT_URI, intent.data)
        assertEquals("+41791234567", intent.getStringExtra(ContactsContract.Intents.Insert.PHONE))
        assertEquals(Phone.TYPE_WORK, intent.getIntExtra(ContactsContract.Intents.Insert.PHONE_TYPE, -1))
    }

    @Test
    fun `add to existing is insert-or-edit on the contact item type`() {
        val intent = ContactHandoff.addToExistingIntent("0791234567", Phone.TYPE_MOBILE)

        assertEquals(Intent.ACTION_INSERT_OR_EDIT, intent.action)
        assertEquals(ContactsContract.Contacts.CONTENT_ITEM_TYPE, intent.type)
        assertNull(intent.data)
        assertEquals("0791234567", intent.getStringExtra(ContactsContract.Intents.Insert.PHONE))
    }
}
