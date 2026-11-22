package life.furrer.nine.contacts

import android.app.Activity
import life.furrer.nine.contacts.ContactStore.ContactGroup
import life.furrer.nine.core.ui.MenuItem

/** What a host app adds to the shared contacts screens beyond read-only browsing. C9 registers
 *  New/Edit/Delete/Accounts/Sync/favorite and its group management, built from its own
 *  activities; P9 and M9 register nothing, so their menus show only the read-only items ("Groups",
 *  the per-group "Show in contacts list" toggle, "Settings" — supplied by the screens themselves)
 *  and their permission gate asks for READ_CONTACTS alone. Null (the default) is what makes a host
 *  read-only — there is no separate capability flag to keep in sync with it. */
object ContactsHost {
    var actions: ContactsActions? = null
        private set

    fun init(actions: ContactsActions?) {
        this.actions = actions
    }
}

/** The host's menu items for each shared screen. Every method is called on the main thread each
 *  time `*` opens that screen's menu, with the screen's current selection, so the items can name
 *  it ("Edit \"Mara\"") and only appear when they apply. The returned items come *first* in the
 *  menu; the screen appends its own read-only items after them.
 *
 *  Items do their work directly (start an activity, or write through [ContactStore]/
 *  [ContactGroups] on [life.furrer.nine.core.util.Async]) and never need to tell the screen: every
 *  shared screen registers a ContentObserver on the whole contacts authority while resumed and
 *  reloads on any provider change — contacts, data rows, groups, memberships alike — and also
 *  reloads in onResume, which covers an item that opened one of the host's own activities and
 *  changed only a preference. A write that removes what a screen shows (deleting the contact on
 *  its detail screen, deleting the group whose members are listed) makes that screen close
 *  itself on the reload. [ContactsScreen] is the way back into the screen for the rest: a
 *  follow-up menu (a delete confirmation) on the same sheet, or an explicit [ContactsScreen.reload]
 *  after a change no observer sees. */
interface ContactsActions {
    /** The contacts list (`ContactsActivity` without a group). [selected] is the highlighted
     *  contact, null when nothing is (an empty list, or the typed-number row). */
    fun listMenuItems(activity: Activity, selected: ContactEntry?): List<MenuItem>

    /** The contacts list's items after the screen's own "Groups" and before "Settings" (e.g.
     *  C9's Accounts/Sync now), so the contact-level [listMenuItems] come first. */
    fun listMenuTrailingItems(activity: Activity): List<MenuItem> = emptyList()

    fun detailMenuItems(activity: Activity, contact: ContactDetail): List<MenuItem>

    /** The groups list ([GroupsActivity]). [selected] is the highlighted group, null when there
     *  are no groups — the moment a "New group" item matters most, so it should not depend on a
     *  selection. Followed by the screen's own "Show in contacts list" toggle (when a group is
     *  selected) and "Settings". */
    fun groupsListMenuItems(activity: Activity, selected: ContactGroup?): List<MenuItem> = emptyList()

    /** The member view of [group] (`ContactsActivity` opened with [ContactsActivity.EXTRA_GROUP_ID]),
     *  replacing [listMenuItems] there. [group] carries the current title; [selected] is the
     *  highlighted member, or null. Followed by the screen's own "Settings". */
    fun groupMembersMenuItems(activity: Activity, group: ContactGroup, selected: ContactEntry?): List<MenuItem> = emptyList()
}

/** Implemented by every shared contacts screen, so a host's menu item can act on the screen it
 *  was opened from without knowing which concrete activity that is:
 *  `(activity as? ContactsScreen)?.showMenu(confirmItems)`. */
interface ContactsScreen {
    /** Shows [items] on this screen's own menu sheet, e.g. a confirmation after "Delete group". */
    fun showMenu(items: List<MenuItem>)

    /** Reloads from the provider and this app's preferences. Rarely needed — see [ContactsActions]
     *  for the ContentObserver/onResume reloads that already cover provider writes. */
    fun reload()
}
