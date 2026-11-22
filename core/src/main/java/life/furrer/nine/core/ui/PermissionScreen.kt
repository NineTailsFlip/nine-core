package life.furrer.nine.core.ui

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** §9.13's "first run" screen — a plain black screen naming what's needed and why, with no
 *  persisted "have I shown this before" flag: showing it any time the activity's required
 *  permission isn't currently granted (not just on the very first-ever launch) naturally covers
 *  both the first run and "explains and offers to ask again" after a denial in one screen. `OK`
 *  (`DPAD_CENTER`/`ENTER`) re-issues the permission request; `BACK` leaves the app, since there's
 *  nothing useful to show without it. Hosting activities add this as an extra `FrameLayout` child
 *  on top of their normal content (same shape as [MenuSheet]) and check [interceptKeyEvent]
 *  (from dispatchKeyEvent) or [handleKeyDown] (from onKeyDown) before their own key handling. */
class PermissionScreen(context: Context) : FrameLayout(context) {

    var onGrant: () -> Unit = {}
    var onLeave: () -> Unit = {}

    var isShowing: Boolean = false
        private set

    private val messageView: TextView = TextView(context).apply {
        textSize = 15f
        setTextColor(Palette.GRAY_DIM)
        setPadding(dp(24), dp(24), dp(24), dp(24))
    }

    init {
        setBackgroundColor(Palette.BLACK)
        visibility = View.GONE

        val hint = TextView(context).apply {
            text = "ok grant access · ‹ exit"
            textSize = 11f
            setTextColor(Palette.GRAY_FAINT)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(messageView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(hint)
        }
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun show(message: String) {
        messageView.text = message
        visibility = View.VISIBLE
        isShowing = true
    }

    fun hide() {
        visibility = View.GONE
        isShowing = false
    }

    /** For hosts that route keys through `dispatchKeyEvent`: call first, and return true when this
     *  does. While shown it swallows every event, so nothing behind it reacts, and acts on
     *  ACTION_DOWN via [handleKeyDown]. */
    fun interceptKeyEvent(event: KeyEvent): Boolean {
        if (!isShowing) return false
        if (event.action == KeyEvent.ACTION_DOWN) handleKeyDown(event.keyCode)
        return true
    }

    /** @return true if the key was consumed — callers check this before their own key handling
     *  while [isShowing], the same "modal intercepts input first" shape as [MenuSheet]. */
    fun handleKeyDown(keyCode: Int): Boolean {
        if (!isShowing) return false
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> onGrant()
            KeyEvent.KEYCODE_BACK -> onLeave()
        }
        return true
    }
}
