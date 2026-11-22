package life.furrer.nine.contacts

import life.furrer.nine.contacts.ContactStore.ContactGroup
import org.junit.Assert.assertEquals
import org.junit.Test

class GroupRowsTest {

    private val family = ContactGroup(1L, "Family", "me@dav", "bitfire.at.davdroid")
    private val work = ContactGroup(2L, "Work", "me@dav", "bitfire.at.davdroid")
    private val club = ContactGroup(3L, "Club", "other@dav", "bitfire.at.davdroid")

    @Test
    fun `a single address book gets no heading, and counts default to zero`() {
        val rows = buildGroupRows(listOf(family, work), mapOf(1L to 12), favoritesGroupId = null, hiddenGroupIds = emptySet())

        assertEquals(
            listOf(
                GroupRow.Entry(family, 12, isFavorites = false, shown = true),
                GroupRow.Entry(work, 0, isFavorites = false, shown = true),
            ),
            rows,
        )
    }

    @Test
    fun `groups from several address books sit under one heading each, order kept`() {
        val rows = buildGroupRows(listOf(club, family, work), emptyMap(), null, emptySet())

        assertEquals(
            listOf("other@dav", "Club", "me@dav", "Family", "Work"),
            rows.map { if (it is GroupRow.AccountHeader) it.accountName else (it as GroupRow.Entry).group.title },
        )
        assertEquals(listOf(false, true, false, true, true), rows.map { it.isSelectable() })
    }

    @Test
    fun `the favorites group is starred and a hidden group is marked`() {
        val rows = buildGroupRows(listOf(family, work), mapOf(1L to 3, 2L to 5), favoritesGroupId = 1L, hiddenGroupIds = setOf(2L))
            .filterIsInstance<GroupRow.Entry>()

        assertEquals(listOf("★ Family · 3", "Work · 5"), rows.map { groupEntryLabel(it) })
        assertEquals(listOf(true, false), rows.map { it.shown })
    }

    @Test
    fun `the favorites group comes first, the rest A to Z ignoring case`() {
        val zoo = ContactGroup(4L, "zoo", "me@dav", "bitfire.at.davdroid")
        val rows = buildGroupRows(listOf(zoo, work, family), emptyMap(), favoritesGroupId = 2L, hiddenGroupIds = emptySet())

        assertEquals(listOf("Work", "Family", "zoo"), rows.filterIsInstance<GroupRow.Entry>().map { it.group.title })
    }

    @Test
    fun `with several address books, the favorites group's own one leads`() {
        val rows = buildGroupRows(listOf(club, family, work), emptyMap(), favoritesGroupId = 2L, hiddenGroupIds = emptySet())

        assertEquals(
            listOf("me@dav", "Work", "Family", "other@dav", "Club"),
            rows.map { if (it is GroupRow.AccountHeader) it.accountName else (it as GroupRow.Entry).group.title },
        )
    }
}
