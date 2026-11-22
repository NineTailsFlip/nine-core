package life.furrer.nine.core.ui

import android.app.Activity
import android.content.Intent

/** Replaces this screen with a genuinely fresh instance started from [intent], invisibly: the
 *  escape from this device's hardware T9 IME capturing `*`/`#` once a [SearchField] (or any
 *  EditText) has had a live input connection.
 *
 *  Confirmed on-device (three escalating attempts, in order): unfocusing/un-focusabling the field
 *  is not enough — once the IME has ever had a real connection to it, it keeps swallowing `*`/`#`
 *  before dispatchKeyEvent ever sees them again, view focus notwithstanding. Neither does
 *  `InputMethodManager.restartInput()` (the documented, lighter-weight "drop and re-establish the
 *  IME session" call) — it runs, but the swallow persists. Nor does `Activity.recreate()` (the fix
 *  that *did* work for the unrelated Autofill-popup key-dispatch corruption in EventEditActivity/
 *  ContactEditActivity — see KNOWN_ISSUE_T9_IME.md) — confirmed live it makes no difference here,
 *  even though a genuine rotation-triggered recreate of the very same screen does clear it. The
 *  difference: `recreate()` with no actual configuration change takes an in-place relaunch path
 *  that reuses the existing window/surface, while a real config change (or a fresh
 *  `startActivity`) tears down and rebuilds the window from scratch — that full teardown is what
 *  actually releases whatever the device's IME has cached. **What works, confirmed live:**
 *  `finish()` immediately followed by `startActivity` on a fresh Intent to the same Activity, with
 *  `overridePendingTransition(0, 0)` on both so it's not a visible flicker.
 *
 *  Any state worth keeping (a favorites/unread filter, a group, a thread) must ride along in
 *  [intent]'s extras: `onRetainNonConfigurationInstance` only carries state across an in-place
 *  relaunch, which this deliberately isn't. */
fun Activity.relaunch(intent: Intent) {
    finish()
    overridePendingTransition(0, 0)
    startActivity(intent)
    overridePendingTransition(0, 0)
}
