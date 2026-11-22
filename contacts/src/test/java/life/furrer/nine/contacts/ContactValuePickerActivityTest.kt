package life.furrer.nine.contacts

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.os.Looper
import android.provider.ContactsContract.CommonDataKinds as CDK
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import life.furrer.nine.core.Nine
import life.furrer.nine.core.NineApp
import life.furrer.nine.core.NineSurface
import life.furrer.nine.core.ui.RowListView
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
import org.robolectric.shadows.ShadowToast

/** Covers ContactValuePickerActivity for both [DataKind]s it supports — PHONE and EMAIL share
 *  every line of this screen except the five documented per-kind differences (search hint,
 *  typed-pseudo-row predicate, value source, empty-value toast; nothing else), so the same four
 *  behaviors (one value, several values, no value, typed-row presence) are each exercised against
 *  both kinds below rather than picking one arbitrarily. Async's background executor is a real
 *  thread, so [awaitAsync] polls for its result rather than assuming a single Robolectric idle()
 *  call is enough — the same reason EventEditActivityTest needs it. */
@RunWith(RobolectricTestRunner::class)
class ContactValuePickerActivityTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        Nine.init(
            NineApp(
                id = "contact-value-picker-test", name = "Test", tagline = "",
                surfaces = listOf(NineSurface("", "", "", emptyList())),
                settingsActivity = Any::class.java,
            ),
        )
        FakeContactsProvider.reset()
        Robolectric.buildContentProvider(FakeContactsProvider::class.java).create("com.android.contacts")
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_CONTACTS)
    }

    private fun awaitAsync(timeoutMs: Long = 3000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() <= deadline) { "condition not met within ${timeoutMs}ms" }
            Thread.sleep(5)
        }
    }

    private fun buildActivity(kind: DataKind): ContactValuePickerActivity {
        val intent = Intent(context, ContactValuePickerActivity::class.java).apply {
            putExtra(ContactValuePickerActivity.EXTRA_KIND, kind.name)
        }
        return Robolectric.buildActivity(ContactValuePickerActivity::class.java, intent).setup().get()
    }

    private fun rowListViewOf(activity: ContactValuePickerActivity): RowListView {
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as FrameLayout
        val mainColumn = root.getChildAt(0) as LinearLayout
        return mainColumn.getChildAt(1) as RowListView
    }

    /** The whole screen's root — asserting its childCount stays 1 (just [mainColumn], see
     *  [rowListViewOf]) is how the "the picker never shows a chooser of its own" tests below
     *  prove there's no MenuSheet in this Activity's view tree at all, not just that one didn't
     *  happen to be shown. */
    private fun rootOf(activity: ContactValuePickerActivity): FrameLayout =
        activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as FrameLayout

    /** Sets the search text. The field discards the very first text change if it lands within
     *  the leaked-char window of window focus (see SearchField's own doc), so an initial no-op
     *  edit gets past that guard before the real query is set — the same trick
     *  AppPickerActivityTest uses for L9's own search field. */
    private fun typeSearch(activity: ContactValuePickerActivity, query: String) {
        val root = rootOf(activity)
        val mainColumn = root.getChildAt(0) as LinearLayout
        val editText = mainColumn.getChildAt(0) as android.widget.EditText
        editText.setText(" ")
        editText.setText(query)
    }

    private fun tapOk(activity: ContactValuePickerActivity) {
        activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
    }

    // --- one value -> EXTRA_VALUE, no EXTRA_VALUES ------------------------------------------

    @Test
    fun `PHONE kind returns EXTRA_VALUE, not EXTRA_VALUES, for a contact with exactly one number`() {
        FakeContactsProvider.putContact(1L, "Ana Meier")
        FakeContactsProvider.putData(
            id = 10L, contactId = 1L, rawContactId = 100L, mimetype = CDK.Phone.CONTENT_ITEM_TYPE,
            values = ContentValues().apply {
                put(CDK.Phone.NUMBER, "079 111 11 11")
                put(CDK.Phone.TYPE, CDK.Phone.TYPE_MOBILE)
            },
        )
        val activity = buildActivity(DataKind.PHONE)
        typeSearch(activity, "ana")
        awaitAsync { rowListViewOf(activity).rowCount() == 1 }

        tapOk(activity)
        awaitAsync { activity.isFinishing }

        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        val result = shadowOf(activity).resultIntent!!
        assertEquals("079 111 11 11", result.getStringExtra(ContactValuePickerActivity.EXTRA_VALUE))
        assertEquals("Ana Meier", result.getStringExtra(ContactValuePickerActivity.EXTRA_NAME))
        assertNull(result.getStringArrayExtra(ContactValuePickerActivity.EXTRA_VALUES))
        assertNull(result.getStringArrayExtra(ContactValuePickerActivity.EXTRA_LABELS))
    }

    @Test
    fun `EMAIL kind returns EXTRA_VALUE, not EXTRA_VALUES, for a contact with exactly one address`() {
        FakeContactsProvider.putContact(1L, "Ana Meier")
        FakeContactsProvider.putRawContact(100L, contactId = 1L, accountName = "fastmail", accountType = "com.fastmail")
        FakeContactsProvider.putData(
            id = 10L, contactId = 1L, rawContactId = 100L, mimetype = CDK.Email.CONTENT_ITEM_TYPE,
            values = ContentValues().apply { put(CDK.Email.ADDRESS, "ana@example.com") },
        )
        val activity = buildActivity(DataKind.EMAIL)
        typeSearch(activity, "ana")
        awaitAsync { rowListViewOf(activity).rowCount() == 1 }

        tapOk(activity)
        awaitAsync { activity.isFinishing }

        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        val result = shadowOf(activity).resultIntent!!
        assertEquals("ana@example.com", result.getStringExtra(ContactValuePickerActivity.EXTRA_VALUE))
        assertNull(result.getStringArrayExtra(ContactValuePickerActivity.EXTRA_VALUES))
    }

    // --- several values -> EXTRA_VALUES + EXTRA_LABELS, and no menu of the picker's own ------

    @Test
    fun `PHONE kind returns EXTRA_VALUES plus EXTRA_LABELS for a contact with several numbers, and shows no menu itself`() {
        FakeContactsProvider.putContact(1L, "Bob Keller")
        FakeContactsProvider.putData(
            id = 10L, contactId = 1L, rawContactId = 100L, mimetype = CDK.Phone.CONTENT_ITEM_TYPE,
            values = ContentValues().apply {
                put(CDK.Phone.NUMBER, "079 222 22 22")
                put(CDK.Phone.TYPE, CDK.Phone.TYPE_MOBILE)
            },
        )
        FakeContactsProvider.putData(
            id = 11L, contactId = 1L, rawContactId = 100L, mimetype = CDK.Phone.CONTENT_ITEM_TYPE,
            values = ContentValues().apply {
                put(CDK.Phone.NUMBER, "044 333 33 33")
                put(CDK.Phone.TYPE, CDK.Phone.TYPE_WORK)
            },
        )
        val activity = buildActivity(DataKind.PHONE)
        typeSearch(activity, "bob")
        awaitAsync { rowListViewOf(activity).rowCount() == 1 }

        tapOk(activity)
        awaitAsync { activity.isFinishing }

        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        val result = shadowOf(activity).resultIntent!!
        assertNull(result.getStringExtra(ContactValuePickerActivity.EXTRA_VALUE))
        assertEquals(
            listOf("079 222 22 22", "044 333 33 33"),
            result.getStringArrayExtra(ContactValuePickerActivity.EXTRA_VALUES)?.toList(),
        )
        assertEquals(
            listOf("MOBILE", "WORK"),
            result.getStringArrayExtra(ContactValuePickerActivity.EXTRA_LABELS)?.toList(),
        )
        // No MenuSheet anywhere in this Activity's own view tree -- the disambiguation menu is
        // the caller's job, never the picker's (see the class doc comment for why).
        assertEquals(1, rootOf(activity).childCount)
    }

    @Test
    fun `EMAIL kind returns EXTRA_VALUES plus EXTRA_LABELS for a contact with several addresses, and shows no menu itself`() {
        FakeContactsProvider.putContact(1L, "Bob Keller")
        FakeContactsProvider.putRawContact(100L, contactId = 1L, accountName = "fastmail", accountType = "com.fastmail")
        FakeContactsProvider.putData(
            id = 10L, contactId = 1L, rawContactId = 100L, mimetype = CDK.Email.CONTENT_ITEM_TYPE,
            values = ContentValues().apply {
                put(CDK.Email.ADDRESS, "bob@home.example")
                put(CDK.Email.TYPE, CDK.Email.TYPE_HOME)
            },
        )
        FakeContactsProvider.putData(
            id = 11L, contactId = 1L, rawContactId = 100L, mimetype = CDK.Email.CONTENT_ITEM_TYPE,
            values = ContentValues().apply {
                put(CDK.Email.ADDRESS, "bob@work.example")
                put(CDK.Email.TYPE, CDK.Email.TYPE_WORK)
            },
        )
        val activity = buildActivity(DataKind.EMAIL)
        typeSearch(activity, "bob")
        awaitAsync { rowListViewOf(activity).rowCount() == 1 }

        tapOk(activity)
        awaitAsync { activity.isFinishing }

        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        val result = shadowOf(activity).resultIntent!!
        assertEquals(
            listOf("bob@home.example", "bob@work.example"),
            result.getStringArrayExtra(ContactValuePickerActivity.EXTRA_VALUES)?.toList(),
        )
        assertEquals(
            listOf("HOME", "WORK"),
            result.getStringArrayExtra(ContactValuePickerActivity.EXTRA_LABELS)?.toList(),
        )
        assertEquals(1, rootOf(activity).childCount)
    }

    // --- no value -> nothing returned, no RESULT_OK -------------------------------------------

    @Test
    fun `PHONE kind toasts and returns nothing for a contact with no number`() {
        FakeContactsProvider.putContact(1L, "Carol Weiss")
        val activity = buildActivity(DataKind.PHONE)
        typeSearch(activity, "carol")
        awaitAsync { rowListViewOf(activity).rowCount() == 1 }

        tapOk(activity)
        awaitAsync { ShadowToast.getLatestToast() != null }

        assertEquals("Carol Weiss has no phone number", ShadowToast.getTextOfLatestToast())
        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).resultIntent)
    }

    @Test
    fun `EMAIL kind toasts and returns nothing for a contact with no address`() {
        FakeContactsProvider.putContact(1L, "Carol Weiss")
        val activity = buildActivity(DataKind.EMAIL)
        typeSearch(activity, "carol")
        awaitAsync { rowListViewOf(activity).rowCount() == 1 }

        tapOk(activity)
        awaitAsync { ShadowToast.getLatestToast() != null }

        assertEquals("Carol Weiss has no email address", ShadowToast.getTextOfLatestToast())
        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).resultIntent)
    }

    // --- the typed pseudo-row appears exactly when the kind's own predicate accepts the query -

    @Test
    fun `EMAIL kind offers the typed row and returns it directly when the query looks like an address`() {
        val activity = buildActivity(DataKind.EMAIL)
        typeSearch(activity, "new@example.com")
        awaitAsync { rowListViewOf(activity).rowCount() == 1 }

        tapOk(activity)

        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        val result = shadowOf(activity).resultIntent!!
        assertEquals("new@example.com", result.getStringExtra(ContactValuePickerActivity.EXTRA_VALUE))
        assertNull(result.getStringExtra(ContactValuePickerActivity.EXTRA_NAME))
    }

    @Test
    fun `EMAIL kind offers no typed row, and OK is a no-op, when the query doesn't look like an address`() {
        val activity = buildActivity(DataKind.EMAIL)
        typeSearch(activity, "newexample")
        awaitAsync { rowListViewOf(activity).rowCount() == 0 }

        tapOk(activity)

        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).resultIntent)
    }

    @Test
    fun `PHONE kind offers the typed row and returns it directly when the query looks dialable`() {
        val activity = buildActivity(DataKind.PHONE)
        typeSearch(activity, "+41 79 111 22 33")
        awaitAsync { rowListViewOf(activity).rowCount() >= 1 }

        tapOk(activity)

        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        val result = shadowOf(activity).resultIntent!!
        assertEquals("+41 79 111 22 33", result.getStringExtra(ContactValuePickerActivity.EXTRA_VALUE))
    }

    @Test
    fun `PHONE kind offers no typed row, and OK is a no-op, when the query doesn't look dialable`() {
        val activity = buildActivity(DataKind.PHONE)
        typeSearch(activity, "notanumber")
        awaitAsync { rowListViewOf(activity).rowCount() == 0 }

        tapOk(activity)

        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).resultIntent)
    }

    // C9's "Add contacts…" loop: Bob was just added, so he's left out and the list picks up at
    // the next contact after him in the A-Z part — not in the FAVORITES block above it.
    @Test
    fun `excluded contacts are left out, and the list resumes after the one just added`() {
        FakeContactsProvider.putContact(1L, "Ann", starred = true)
        FakeContactsProvider.putContact(2L, "Bob")
        FakeContactsProvider.putContact(3L, "Cara")
        FakeContactsProvider.putContact(4L, "Dan")
        val intent = Intent(context, ContactValuePickerActivity::class.java)
            .putExtra(ContactValuePickerActivity.EXTRA_PICK_CONTACT, true)
            .putExtra(ContactValuePickerActivity.EXTRA_EXCLUDE_CONTACT_IDS, longArrayOf(2L))
            .putExtra(ContactValuePickerActivity.EXTRA_RESUME_AFTER_CONTACT_ID, 2L)
        val activity = Robolectric.buildActivity(ContactValuePickerActivity::class.java, intent).setup().get()
        // FAVORITES, Ann, A, Ann, C, Cara, D, Dan — no Bob.
        awaitAsync { rowListViewOf(activity).rowCount() == 8 }

        assertEquals(5, rowListViewOf(activity).selectedIndex)
        tapOk(activity)
        assertEquals(3L, shadowOf(activity).resultIntent!!.getLongExtra(ContactValuePickerActivity.EXTRA_CONTACT_ID, -1L))
    }

    @Test
    fun `whole-contact mode returns the contact itself, even one with no phone number or email`() {
        FakeContactsProvider.putContact(1L, "Andreas Furrer")
        val intent = Intent(context, ContactValuePickerActivity::class.java)
            .putExtra(ContactValuePickerActivity.EXTRA_PICK_CONTACT, true)
        val activity = Robolectric.buildActivity(ContactValuePickerActivity::class.java, intent).setup().get()
        typeSearch(activity, "andreas")
        awaitAsync { rowListViewOf(activity).rowCount() == 1 }

        tapOk(activity)

        assertTrue(activity.isFinishing)
        val result = shadowOf(activity).resultIntent!!
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        assertEquals(1L, result.getLongExtra(ContactValuePickerActivity.EXTRA_CONTACT_ID, -1L))
        assertEquals("Andreas Furrer", result.getStringExtra(ContactValuePickerActivity.EXTRA_NAME))
    }

    @Test
    fun `a platform ACTION_PICK returns the picked contact's lookup Uri, readable by the caller`() {
        FakeContactsProvider.putContact(1L, "Andreas Furrer", lookupKey = "lk1")
        val intent = Intent(Intent.ACTION_PICK).setClass(context, ContactValuePickerActivity::class.java)
        val activity = Robolectric.buildActivity(ContactValuePickerActivity::class.java, intent).setup().get()
        typeSearch(activity, "andreas")
        awaitAsync { rowListViewOf(activity).rowCount() == 1 }

        tapOk(activity)

        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        val result = shadowOf(activity).resultIntent!!
        assertEquals(android.provider.ContactsContract.Contacts.getLookupUri(1L, "lk1"), result.data)
        assertTrue(result.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun `a platform ACTION_PICK without READ_CONTACTS asks for it, and cancels if denied`() {
        shadowOf(context).denyPermissions(android.Manifest.permission.READ_CONTACTS)
        val intent = Intent(Intent.ACTION_PICK).setClass(context, ContactValuePickerActivity::class.java)
        val activity = Robolectric.buildActivity(ContactValuePickerActivity::class.java, intent).setup().get()

        val request = shadowOf(activity).lastRequestedPermission
        assertEquals(android.Manifest.permission.READ_CONTACTS, request.requestedPermissions.single())
        activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, intArrayOf(-1))

        assertTrue(activity.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
    }
}
