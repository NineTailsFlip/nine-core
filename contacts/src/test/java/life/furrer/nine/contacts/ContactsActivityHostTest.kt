package life.furrer.nine.contacts

import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import life.furrer.nine.core.Nine
import life.furrer.nine.core.NineApp
import life.furrer.nine.core.NineSurface
import life.furrer.nine.core.ui.MenuSheet
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

/** A host that never registers [ContactsActions] (P9's shape) must get a read-only ContactsActivity:
 *  no write permission requested, and the `*` menu offers only "Settings". */
@RunWith(RobolectricTestRunner::class)
class ContactsActivityHostTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        ContactsHost.init(null)
        Nine.init(
            NineApp(
                id = "contacts-host-test", name = "Test", tagline = "",
                surfaces = listOf(NineSurface("", "", "", emptyList())),
                settingsActivity = Any::class.java,
            ),
        )
        FakeContactsProvider.reset()
        Robolectric.buildContentProvider(FakeContactsProvider::class.java).create("com.android.contacts")
    }

    @After
    fun tearDown() {
        ContactsHost.init(null)
    }

    private fun menuSheetOf(activity: ContactsActivity): MenuSheet {
        val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as FrameLayout
        return root.getChildAt(1) as MenuSheet
    }

    private fun menuLabels(menuSheet: MenuSheet): List<String> {
        val column = menuSheet.getChildAt(0) as LinearLayout
        val listContainer = column.getChildAt(1) as LinearLayout
        return (0 until listContainer.childCount).map { (listContainer.getChildAt(it) as TextView).text.toString() }
    }

    @Test
    fun `a read-only host never asks for WRITE_CONTACTS, only READ`() {
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_CONTACTS)
        shadowOf(context).denyPermissions(android.Manifest.permission.WRITE_CONTACTS)

        val activity = Robolectric.buildActivity(ContactsActivity::class.java).setup().get()

        // No WRITE_CONTACTS and no host registered -> the permission screen must not be showing;
        // a host that writes nothing has everything it needs with READ_CONTACTS alone.
        val permissionScreen = (activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as FrameLayout)
            .getChildAt(2) as life.furrer.nine.core.ui.PermissionScreen
        assertFalse(permissionScreen.isShowing)
    }

    @Test
    fun `a read-only host's menu offers only Groups and Settings`() {
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_CONTACTS)
        val activity = Robolectric.buildActivity(ContactsActivity::class.java).setup().get()

        activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_STAR, 0))
        activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_STAR, 0))

        val menuSheet = menuSheetOf(activity)
        assertTrue(menuSheet.isShowing)
        assertEquals(listOf("1 Groups", "2 Settings"), menuLabels(menuSheet))
    }

    @Test
    fun `a writing host's list items come before Groups and its trailing items after`() {
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS)
        ContactsHost.init(
            object : ContactsActions {
                override fun listMenuItems(activity: android.app.Activity, selected: ContactEntry?) =
                    listOf(life.furrer.nine.core.ui.MenuItem("New contact") {})
                override fun listMenuTrailingItems(activity: android.app.Activity) =
                    listOf(life.furrer.nine.core.ui.MenuItem("Accounts") {}, life.furrer.nine.core.ui.MenuItem("Sync now") {})
                override fun detailMenuItems(activity: android.app.Activity, contact: ContactDetail) =
                    emptyList<life.furrer.nine.core.ui.MenuItem>()
            },
        )
        val activity = Robolectric.buildActivity(ContactsActivity::class.java).setup().get()

        activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_STAR, 0))
        activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_STAR, 0))

        assertEquals(
            listOf("1 New contact", "2 Groups", "3 Accounts", "4 Sync now", "5 Settings"),
            menuLabels(menuSheetOf(activity)),
        )
    }

    // --- Group member view and the "Show in contacts list" filter ----------------------------

    private fun awaitUntil(timeoutMs: Long = 3000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() <= deadline) { "condition not met within ${timeoutMs}ms" }
            Thread.sleep(5)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun listedNames(activity: ContactsActivity): List<String> {
        val field = ContactsActivity::class.java.getDeclaredField("allContacts")
        field.isAccessible = true
        return (field.get(activity) as List<ContactEntry>).map { it.displayName }
    }

    /** Mara: Family. Timo: Family + Work. Uwe: Work only. Zoe: no group. */
    private fun contactsInGroups() {
        FakeContactsProvider.putGroup(1L, "Family", "me@dav", "bitfire.at.davdroid")
        FakeContactsProvider.putGroup(2L, "Work", "me@dav", "bitfire.at.davdroid")
        listOf(10L to "Mara", 11L to "Timo", 12L to "Uwe", 13L to "Zoe").forEach { (id, name) -> FakeContactsProvider.putContact(id, name) }
        FakeContactsProvider.putMembership(100L, contactId = 10L, rawContactId = 1000L, groupId = 1L)
        FakeContactsProvider.putMembership(101L, contactId = 11L, rawContactId = 1100L, groupId = 1L)
        FakeContactsProvider.putMembership(102L, contactId = 11L, rawContactId = 1100L, groupId = 2L)
        FakeContactsProvider.putMembership(103L, contactId = 12L, rawContactId = 1200L, groupId = 2L)
    }

    private fun openGroup(groupId: Long) = Robolectric.buildActivity(
        ContactsActivity::class.java,
        android.content.Intent(context, ContactsActivity::class.java).putExtra(ContactsActivity.EXTRA_GROUP_ID, groupId),
    ).setup().get()

    private fun titleLineOf(activity: ContactsActivity): TextView {
        val mainColumn = (activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as FrameLayout).getChildAt(0) as LinearLayout
        return mainColumn.getChildAt(0) as TextView
    }

    @Test
    fun `hiding a group drops only contacts in no shown group`() {
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_CONTACTS)
        contactsInGroups()
        ContactStore.setGroupShown(context, 2L, shown = false)

        val activity = Robolectric.buildActivity(ContactsActivity::class.java).setup().get()

        awaitUntil { listedNames(activity).isNotEmpty() }
        assertEquals(listOf("Mara", "Timo", "Zoe"), listedNames(activity))
        assertEquals(android.view.View.GONE, titleLineOf(activity).visibility)
    }

    @Test
    fun `the member view lists just the group's members under its name, ignoring the hidden filter`() {
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_CONTACTS)
        contactsInGroups()
        ContactStore.setGroupShown(context, 2L, shown = false)

        val activity = openGroup(2L)

        awaitUntil { listedNames(activity).isNotEmpty() }
        assertEquals(listOf("Timo", "Uwe"), listedNames(activity))
        assertEquals("‹ Work", titleLineOf(activity).text.toString())
    }

    @Test
    fun `the member view closes when its group is gone`() {
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_CONTACTS)
        contactsInGroups()

        val activity = openGroup(99L)

        awaitUntil { activity.isFinishing }
    }

    @Test
    fun `the member view's menu has the host's group items instead of the list's, and no Groups`() {
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS)
        contactsInGroups()
        val seen = mutableListOf<Pair<Long, String?>>()
        ContactsHost.init(
            object : ContactsActions {
                override fun listMenuItems(activity: android.app.Activity, selected: ContactEntry?) =
                    listOf(life.furrer.nine.core.ui.MenuItem("New contact") {})
                override fun detailMenuItems(activity: android.app.Activity, contact: ContactDetail) =
                    emptyList<life.furrer.nine.core.ui.MenuItem>()
                override fun groupMembersMenuItems(
                    activity: android.app.Activity,
                    group: ContactStore.ContactGroup,
                    selected: ContactEntry?,
                ): List<life.furrer.nine.core.ui.MenuItem> {
                    seen += group.id to selected?.displayName
                    return listOf(life.furrer.nine.core.ui.MenuItem("Add contacts…") {})
                }
            },
        )
        val activity = openGroup(1L)
        awaitUntil { listedNames(activity).isNotEmpty() }

        activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_STAR, 0))
        activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_STAR, 0))

        assertEquals(listOf("1 Add contacts…", "2 Settings"), menuLabels(menuSheetOf(activity)))
        assertEquals(listOf(1L to "Mara"), seen)
    }

    @Test
    fun `restarting the member view after search keeps its group`() {
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_CONTACTS)
        contactsInGroups()
        val activity = openGroup(1L)

        val restart = ContactsActivity::class.java.getDeclaredMethod("restartFresh")
        restart.isAccessible = true
        restart.invoke(activity)

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(1L, started.getLongExtra(ContactsActivity.EXTRA_GROUP_ID, -1L))
    }
}
