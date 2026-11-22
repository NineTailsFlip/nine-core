package life.furrer.nine.core.ui

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup

/** The base every full-screen Nine activity extends, owning the boilerplate each one would
 *  otherwise repeat: the configured (optionally monospace) theme, applied in [onCreate] so a
 *  subclass's own onCreate builds its views under it after calling super, and the hidden status
 *  bar, hidden right after every [setContentView] (the DecorView only exists from then on) and
 *  again whenever the window regains focus (a swipe-reveal or a dialog can bring the bar back).
 *
 *  Not for screens that must not look like this: translucent trampolines without UI, or anything
 *  whose theme isn't the configured one. */
abstract class NineActivity : Activity() {

    /** What [applyConfiguredTheme] applied in [onCreate], for a long-lived screen's
     *  [recreateIfFontChanged] check in onResume. */
    protected var appliedMonospaceFont = false
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appliedMonospaceFont = applyConfiguredTheme()
    }

    override fun setContentView(view: View?) {
        super.setContentView(view)
        hideStatusBar()
    }

    override fun setContentView(view: View?, params: ViewGroup.LayoutParams?) {
        super.setContentView(view, params)
        hideStatusBar()
    }

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        hideStatusBar()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }
}
