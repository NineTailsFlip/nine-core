package life.furrer.nine.core.ui

import android.app.Activity
import android.view.WindowInsets
import android.view.WindowInsetsController

/** Keeps the all-black immersive look on every screen. Must be called after setContentView
 *  (DecorView doesn't exist yet in onCreate before that) and again from onWindowFocusChanged,
 *  since a swipe-reveal or focus change can bring the status bar back. [NineActivity] does both. */
fun Activity.hideStatusBar() {
    window.setDecorFitsSystemWindows(false)
    window.insetsController?.apply {
        hide(WindowInsets.Type.statusBars())
        systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
