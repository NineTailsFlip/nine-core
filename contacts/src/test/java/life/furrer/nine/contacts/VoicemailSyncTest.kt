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

/** [VoicemailSync] (and the FakeContactsProvider's SEND_TO_VOICEMAIL model it relies on). */
@RunWith(RobolectricTestRunner::class)
class VoicemailSyncTest {

    private val context = RuntimeEnvironment.getApplication()
    private val dav = "me@dav" to "bitfire.at.davdroid"
    private val wa = "WhatsApp" to "com.whatsapp"
    private val group = ContactGroup(5L, "Voicemail", dav.first, dav.second)

    @Before
    fun setUp() {
        Nine.init(NineApp(id = "voicemail-test", name = "Test", tagline = "", surfaces = listOf(NineSurface("", "", "", emptyList())), settingsActivity = Any::class.java))
        NinePrefs.of(context).edit().clear().commit()
        VoicemailSync.clearBaseline(context)
        FavoritesSync.clearBaseline(context)
        FakeContactsProvider.reset()
        Robolectric.buildContentProvider(FakeContactsProvider::class.java).create("com.android.contacts")
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS)
        FakeContactsProvider.putGroup(5L, "Voicemail", dav.first, dav.second)
    }

    /** Contact [id] with its vCard raw contact 100+id in the DAV account. */
    private fun davContact(id: Long, flagged: Boolean = false, member: Boolean = false) {
        FakeContactsProvider.putContact(id, "Contact $id")
        FakeContactsProvider.putRawContact(100 + id, contactId = id, accountName = dav.first, accountType = dav.second, sourceId = "v$id", sendToVoicemail = flagged)
        if (member) FakeContactsProvider.putMembership(500 + id, contactId = id, rawContactId = 100 + id, groupId = 5L)
    }

    /** A messaging-app mirror raw contact (900+id) linked into contact [id]. */
    private fun mirror(id: Long, flagged: Boolean = false) {
        FakeContactsProvider.putRawContact(900 + id, contactId = id, accountName = wa.first, accountType = wa.second, sendToVoicemail = flagged)
    }

    private fun flagged(id: Long) = FakeContactsProvider.isSendToVoicemail(id)
    private fun members() = FakeContactsProvider.memberRawContactIds(5L)

    // --- Fake provider model ---------------------------------------------------------------------

    @Test
    fun `the fake's aggregate flag needs every raw contact, and an aggregate write reaches all of them`() {
        davContact(1, flagged = true)
        mirror(1)
        assertFalse(flagged(1))
        assertTrue(FakeContactsProvider.rawSendToVoicemail(101))
        assertFalse(FakeContactsProvider.rawSendToVoicemail(901))

        assertTrue(VoicemailSync.setProperty(context, 1, true))
        assertTrue(flagged(1))
        assertTrue(FakeContactsProvider.rawSendToVoicemail(901))
    }

    // The provider never writes a contact option to a read-only raw contact, so a flag on one can't
    // be cleared; counting it would turn every "off" straight back on as a repair.
    @Test
    fun `a flag on a read-only raw contact neither holds nor comes back after off`() {
        davContact(1, flagged = true)
        FakeContactsProvider.putRawContact(901, contactId = 1, accountName = wa.first, accountType = wa.second, sendToVoicemail = true, readOnly = true)
        assertTrue(flagged(1))

        assertTrue(VoicemailSync.setProperty(context, 1, false))
        assertFalse(FakeContactsProvider.rawSendToVoicemail(101))
        assertTrue(FakeContactsProvider.rawSendToVoicemail(901))

        assertEquals(LineUpResult.NoGroup, VoicemailSync.lineUp(context))
        assertFalse(FakeContactsProvider.rawSendToVoicemail(101))
    }

    // A restored backup could point both syncs at one group; syncing both through it would make
    // every favorite go to voicemail.
    @Test
    fun `a group chosen for favorites too is not synced, only repaired`() {
        davContact(1, member = true)
        davContact(2, flagged = true)
        mirror(2)
        FakeContactsProvider.putContact(3, "Contact 3", starred = true)
        VoicemailSync.setGroup(context, group)
        FavoritesSync.setGroup(context, group)

        assertEquals(LineUpResult.GroupShared(group), VoicemailSync.lineUp(context))
        assertFalse(flagged(1))
        assertTrue(flagged(2))
        assertEquals(setOf(101L), members())

        assertTrue(VoicemailSync.setProperty(context, 1, false))
        assertEquals(setOf(101L), members())
    }

    // --- No group ----------------------------------------------------------------------------------

    @Test
    fun `without a group lineUp repairs a half-flagged contact and leaves the others alone`() {
        davContact(1, flagged = true)
        mirror(1) // linked later, not flagged: the aggregate lost the flag
        davContact(2, flagged = true) // fully flagged
        davContact(3) // not flagged at all

        assertEquals(LineUpResult.NoGroup, VoicemailSync.lineUp(context))

        assertTrue(FakeContactsProvider.rawSendToVoicemail(101))
        assertTrue(FakeContactsProvider.rawSendToVoicemail(901))
        assertTrue(flagged(1))
        assertTrue(flagged(2))
        assertFalse(flagged(3))
        assertFalse(FakeContactsProvider.rawSendToVoicemail(103))
    }

    @Test
    fun `without a group lineUp reports Failed when the repair can't be written`() {
        davContact(1, flagged = true)
        mirror(1)
        FakeContactsProvider.failWrites = true

        assertEquals(LineUpResult.Failed, VoicemailSync.lineUp(context))
        assertFalse(flagged(1))
    }

    @Test
    fun `without a group and nothing to repair lineUp writes nothing and is NoGroup`() {
        davContact(1, flagged = true)
        FakeContactsProvider.failWrites = true // would turn any write into Failed

        assertEquals(LineUpResult.NoGroup, VoicemailSync.lineUp(context))
    }

    @Test
    fun `with a missing group the repair is still written`() {
        davContact(1, flagged = true)
        mirror(1)
        VoicemailSync.setGroup(context, ContactGroup(77L, "Gone", dav.first, dav.second))

        assertEquals(LineUpResult.GroupMissing(ContactGroup(77L, "Gone", dav.first, dav.second)), VoicemailSync.lineUp(context))
        assertTrue(flagged(1))
    }

    // --- With a group ------------------------------------------------------------------------------

    @Test
    fun `first line-up merges both sides`() {
        VoicemailSync.setGroup(context, group)
        davContact(1, flagged = true)
        davContact(2, member = true)
        davContact(3)

        val result = VoicemailSync.lineUp(context) as LineUpResult.Done

        assertEquals(setOf(2L), result.plan.give)
        assertEquals(setOf(1L), result.plan.addToGroup)
        assertTrue(flagged(1))
        assertTrue(flagged(2))
        assertFalse(flagged(3))
        assertEquals(setOf(101L, 102L), members())
        assertEquals(setOf(BaselineEntry(101, "v1"), BaselineEntry(102, "v2")), VoicemailSync.readBaseline(context, 5L))
        assertFalse((VoicemailSync.lineUp(context) as LineUpResult.Done).plan.hasWrites)
    }

    @Test
    fun `with a baseline, removal on the phone side takes the contact out of the group`() {
        VoicemailSync.setGroup(context, group)
        davContact(1, flagged = true, member = true)
        VoicemailSync.lineUp(context)

        VoicemailSync.setProperty(context, 1, false, group = null) // flag off, membership untouched
        VoicemailSync.lineUp(context)

        assertFalse(flagged(1))
        assertEquals(emptySet<Long>(), members())
        assertEquals(emptySet<BaselineEntry>(), VoicemailSync.readBaseline(context, 5L))
    }

    @Test
    fun `with a baseline, removal on the server side takes the flag away`() {
        VoicemailSync.setGroup(context, group)
        davContact(1, flagged = true, member = true)
        mirror(1, flagged = true)
        VoicemailSync.lineUp(context)

        FakeContactsProvider.data.remove(501L)
        VoicemailSync.lineUp(context)

        assertFalse(flagged(1))
        assertFalse(FakeContactsProvider.rawSendToVoicemail(101))
        assertFalse(FakeContactsProvider.rawSendToVoicemail(901))
        assertEquals(emptySet<Long>(), members())
    }

    @Test
    fun `with a baseline, joining the group on the server flags the contact`() {
        VoicemailSync.setGroup(context, group)
        davContact(1, flagged = true, member = true)
        VoicemailSync.lineUp(context)

        davContact(2)
        FakeContactsProvider.putMembership(502L, contactId = 2, rawContactId = 102, groupId = 5L)
        VoicemailSync.lineUp(context)

        assertTrue(flagged(2))
        assertTrue(flagged(1))
    }

    @Test
    fun `a member whose aggregate lost the flag to a new raw contact stays in the group and is repaired`() {
        VoicemailSync.setGroup(context, group)
        davContact(1, flagged = true, member = true)
        VoicemailSync.lineUp(context)
        val baseline = VoicemailSync.readBaseline(context, 5L)

        mirror(1) // WhatsApp links in: the aggregate is no longer flagged
        assertFalse(flagged(1))
        val result = VoicemailSync.lineUp(context) as LineUpResult.Done

        assertTrue(flagged(1))
        assertTrue(FakeContactsProvider.rawSendToVoicemail(901))
        assertEquals(setOf(101L), members())
        assertEquals(emptySet<Long>(), result.plan.takeAway)
        assertEquals(emptySet<Long>(), result.plan.removeFromGroup)
        assertEquals(baseline, VoicemailSync.readBaseline(context, 5L))
        assertFalse((VoicemailSync.lineUp(context) as LineUpResult.Done).plan.hasWrites)
    }

    @Test
    fun `a failed batch keeps the baseline`() {
        VoicemailSync.setGroup(context, group)
        davContact(1, flagged = true)
        FakeContactsProvider.failWrites = true

        assertEquals(LineUpResult.Failed, VoicemailSync.lineUp(context))
        assertNull(VoicemailSync.readBaseline(context, 5L))
    }

    // --- setProperty / needsLineUps / separation ----------------------------------------------------

    @Test
    fun `setProperty flags every raw contact and joins the group, and off undoes both`() {
        VoicemailSync.setGroup(context, group)
        davContact(1)
        mirror(1)

        assertTrue(VoicemailSync.setProperty(context, 1, true))
        assertTrue(flagged(1))
        assertTrue(FakeContactsProvider.rawSendToVoicemail(101))
        assertTrue(FakeContactsProvider.rawSendToVoicemail(901))
        assertEquals(setOf(101L), members())

        assertTrue(VoicemailSync.setProperty(context, 1, false))
        assertFalse(FakeContactsProvider.rawSendToVoicemail(101))
        assertFalse(FakeContactsProvider.rawSendToVoicemail(901))
        assertEquals(emptySet<Long>(), members())
    }

    @Test
    fun `setProperty without a group only writes the flag`() {
        davContact(1)

        assertTrue(VoicemailSync.setProperty(context, 1, true))

        assertTrue(flagged(1))
        assertEquals(emptySet<Long>(), members())
    }

    @Test
    fun `setProperty fails when writes fail`() {
        davContact(1)
        FakeContactsProvider.failWrites = true

        assertFalse(VoicemailSync.setProperty(context, 1, true))
    }

    @Test
    fun `needsLineUps is true for voicemail without a group and false for favorites`() {
        assertTrue(VoicemailSync.needsLineUps(context))
        assertFalse(FavoritesSync.needsLineUps(context))

        FavoritesSync.setGroup(context, group)
        assertTrue(FavoritesSync.needsLineUps(context))
    }

    @Test
    fun `favorites and voicemail keep separate groups and baselines`() {
        val other = ContactGroup(6L, "Favorites", dav.first, dav.second)
        FakeContactsProvider.putGroup(6L, "Favorites", dav.first, dav.second)

        VoicemailSync.setGroup(context, group)
        FavoritesSync.setGroup(context, other)
        assertEquals(group, VoicemailSync.group(context))
        assertEquals(other, FavoritesSync.group(context))
        assertTrue(VoicemailSync.isGroup(context, group))
        assertFalse(VoicemailSync.isGroup(context, other))
        assertFalse(FavoritesSync.isGroup(context, group))

        VoicemailSync.writeBaseline(context, 5L, setOf(BaselineEntry(101, "v1")))
        assertNull(FavoritesSync.readBaseline(context, 5L))
        assertNull(FavoritesSync.readBaseline(context, 6L))

        FavoritesSync.setGroup(context, null)
        assertEquals(group, VoicemailSync.group(context))
        assertEquals(setOf(BaselineEntry(101, "v1")), VoicemailSync.readBaseline(context, 5L))
        assertEquals(GroupState.Present(group), VoicemailSync.groupState(context))
        assertEquals(GroupState.None, FavoritesSync.groupState(context))
    }
}
