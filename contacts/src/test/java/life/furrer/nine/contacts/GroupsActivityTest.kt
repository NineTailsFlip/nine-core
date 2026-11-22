package life.furrer.nine.contacts

import android.app.Activity
import android.os.Looper
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import life.furrer.nine.contacts.ContactStore.ContactGroup
import life.furrer.nine.core.Nine
import life.furrer.nine.core.NineApp
import life.furrer.nine.core.NineSurface
import life.furrer.nine.core.ui.MenuItem
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

/** The shared groups list: rows, empty state, OK into the member view, and the `*` menu in a
 *  read-only host (P9's shape) and in a host with group actions (C9's, step 2). Ported from C9's
 *  former filter screen: the per-group visibility toggle now lives in this screen's menu. */
@RunWith(RobolectricTestRunner::class)
class GroupsActivityTest {

    private val context = RuntimeEnvironment.getApplication()
    private val dav = "me@dav" to "bitfire.at.davdroid"

    @Before
    fun setUp() {
        ContactsHost.init(null)
        Nine.init(
            NineApp(
                id = "groups-activity-test", name = "Test", tagline = "",
                surfaces = listOf(NineSurface("", "", "", emptyList())),
                settingsActivity = Any::class.java,
            ),
        )
        FakeContactsProvider.reset()
        Robolectric.buildContentProvider(FakeContactsProvider::class.java).create("com.android.contacts")
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_CONTACTS)
    }

    @After
    fun tearDown() {
        ContactsHost.init(null)
    }

    private fun awaitUntil(timeoutMs: Long = 3000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() <= deadline) { "condition not met within ${timeoutMs}ms" }
            Thread.sleep(5)
        }
    }

    /** Family (2 members, one of whom is in Work too) and Work, both in the DAV address book. */
    private fun twoGroups() {
        FakeContactsProvider.putGroup(1L, "Family", dav.first, dav.second)
        FakeContactsProvider.putGroup(2L, "Work", dav.first, dav.second)
        FakeContactsProvider.putContact(10L, "Mara")
        FakeContactsProvider.putContact(11L, "Timo")
        FakeContactsProvider.putMembership(100L, contactId = 10L, rawContactId = 1000L, groupId = 1L)
        FakeContactsProvider.putMembership(101L, contactId = 11L, rawContactId = 1100L, groupId = 1L)
        FakeContactsProvider.putMembership(102L, contactId = 11L, rawContactId = 1100L, groupId = 2L)
    }

    @Suppress("UNCHECKED_CAST")
    private fun rowsOf(activity: GroupsActivity): List<GroupRow> {
        val field = GroupsActivity::class.java.getDeclaredField("rows")
        field.isAccessible = true
        return field.get(activity) as List<GroupRow>
    }

    private fun open(): GroupsActivity {
        val activity = Robolectric.buildActivity(GroupsActivity::class.java).setup().get()
        awaitUntil { isLoaded(activity) }
        return activity
    }

    private fun isLoaded(activity: GroupsActivity): Boolean {
        val field = GroupsActivity::class.java.getDeclaredField("loaded")
        field.isAccessible = true
        return field.getBoolean(activity)
    }

    private fun root(activity: Activity) = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as FrameLayout

    private fun menuSheetOf(activity: Activity) = root(activity).getChildAt(1) as MenuSheet

    private fun menuLabels(activity: Activity): List<String> {
        val column = menuSheetOf(activity).getChildAt(0) as LinearLayout
        val list = column.getChildAt(1) as LinearLayout
        return (0 until list.childCount).map { (list.getChildAt(it) as TextView).text.toString() }
    }

    private fun press(activity: Activity, keyCode: Int) {
        activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0))
        activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun emptyTextOf(activity: GroupsActivity): TextView {
        val mainColumn = root(activity).getChildAt(0) as LinearLayout
        return (mainColumn.getChildAt(1) as FrameLayout).getChildAt(1) as TextView
    }

    @Test
    fun `lists groups with member counts and no heading for a single address book`() {
        twoGroups()
        val activity = open()

        assertEquals(
            listOf("Family · 2", "Work · 1"),
            rowsOf(activity).map { groupEntryLabel(it as GroupRow.Entry) },
        )
        assertFalse(emptyTextOf(activity).isShown)
    }

    @Test
    fun `no groups shows the empty state`() {
        val activity = open()

        assertTrue(rowsOf(activity).isEmpty())
        assertEquals("No groups yet", emptyTextOf(activity).text.toString())
        assertEquals(android.view.View.VISIBLE, emptyTextOf(activity).visibility)
    }

    @Test
    fun `untitled and deleted groups are not listed`() {
        twoGroups()
        FakeContactsProvider.putGroup(3L, null, dav.first, dav.second)
        FakeContactsProvider.putGroup(4L, "Gone", dav.first, dav.second, deleted = true)

        assertEquals(listOf(1L, 2L), rowsOf(open()).map { (it as GroupRow.Entry).group.id })
    }

    @Test
    fun `OK opens the selected group's member view`() {
        twoGroups()
        val activity = open()

        press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(ContactsActivity::class.java.name, started.component!!.className)
        assertEquals(2L, started.getLongExtra(ContactsActivity.EXTRA_GROUP_ID, -1L))
    }

    @Test
    fun `a read-only host's menu offers the visibility toggle and Settings`() {
        twoGroups()
        val activity = open()

        press(activity, KeyEvent.KEYCODE_STAR)

        assertTrue(menuSheetOf(activity).isShowing)
        assertEquals(listOf("1 Show in contacts list: yes", "2 Settings"), menuLabels(activity))
    }

    @Test
    fun `the toggle hides the group from the contacts list and shows it again`() {
        twoGroups()
        val activity = open()

        press(activity, KeyEvent.KEYCODE_STAR)
        press(activity, KeyEvent.KEYCODE_1)

        assertEquals(setOf(1L), ContactStore.hiddenGroupIds(context))
        awaitUntil { !(rowsOf(activity).first() as GroupRow.Entry).shown }
        press(activity, KeyEvent.KEYCODE_STAR)
        assertEquals("1 Show in contacts list: no", menuLabels(activity).first())

        press(activity, KeyEvent.KEYCODE_1)
        assertEquals(emptySet<Long>(), ContactStore.hiddenGroupIds(context))
    }

    @Test
    fun `host items come first and get the selected group, or null when there are none`() {
        val seen = mutableListOf<ContactGroup?>()
        ContactsHost.init(
            object : ContactsActions {
                override fun listMenuItems(activity: Activity, selected: ContactEntry?) = emptyList<MenuItem>()
                override fun detailMenuItems(activity: Activity, contact: ContactDetail) = emptyList<MenuItem>()
                override fun groupsListMenuItems(activity: Activity, selected: ContactGroup?): List<MenuItem> {
                    seen += selected
                    return listOf(MenuItem("New group") {})
                }
            },
        )
        val empty = open()
        press(empty, KeyEvent.KEYCODE_STAR)
        assertEquals(listOf("1 New group", "2 Settings"), menuLabels(empty))

        twoGroups()
        val activity = open()
        press(activity, KeyEvent.KEYCODE_STAR)
        assertEquals(listOf("1 New group", "2 Show in contacts list: yes", "3 Settings"), menuLabels(activity))
        assertEquals(listOf(null, 1L), seen.map { it?.id })
    }

    @Test
    fun `the favorites group is starred and always listed first, the rest A to Z`() {
        twoGroups()
        FavoritesSync.setGroup(context, ContactGroup(2L, "Work", dav.first, dav.second))

        assertEquals(
            listOf("★ Work · 1", "Family · 2"),
            rowsOf(open()).filterIsInstance<GroupRow.Entry>().map { groupEntryLabel(it) },
        )
    }

    @Test
    fun `a group added while the screen is open shows up via the ContentObserver`() {
        twoGroups()
        shadowOf(context).grantPermissions(android.Manifest.permission.WRITE_CONTACTS)
        val activity = open()

        ContactGroups.createGroup(context, ContactStore.WritableAccount(dav.first, dav.second), "Club")
        context.contentResolver.notifyChange(android.provider.ContactsContract.Groups.CONTENT_URI, null)

        awaitUntil { rowsOf(activity).size == 3 }
    }
}
