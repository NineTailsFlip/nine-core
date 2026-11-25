package io.github.ninetailsflip.update

import android.app.Activity
import life.furrer.nine.core.Nine
import life.furrer.nine.core.NineApp
import life.furrer.nine.core.NineSurface
import life.furrer.nine.core.prefs.NinePrefs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SetupChoicesTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        Nine.init(NineApp("test", "Test", "", listOf(NineSurface("", "Test", "", emptyList())), settingsActivity = Activity::class.java))
    }

    private fun choices(revision: Long, monospace: Boolean, hintBar: Boolean) = mapOf(
        SetupChoices.KEY_REVISION to revision.toString(),
        SetupChoices.KEY_MONOSPACE_FONT to monospace.toString(),
        SetupChoices.KEY_SHOW_HINT_BAR to hintBar.toString(),
    )

    @Test
    fun `new choices become the app's own settings, once`() {
        assertTrue(SetupChoices.apply(context, choices(100, monospace = false, hintBar = false)))
        assertFalse(NinePrefs.useMonospaceFont(context))
        assertFalse(NinePrefs.showHintBar(context))

        // The user changes it in the app afterwards: the same setup doesn't undo that.
        NinePrefs.setShowHintBar(context, true)
        assertFalse(SetupChoices.apply(context, choices(100, monospace = false, hintBar = false)))
        assertTrue(NinePrefs.showHintBar(context))

        // A later setup does apply.
        assertTrue(SetupChoices.apply(context, choices(200, monospace = true, hintBar = false)))
        assertTrue(NinePrefs.useMonospaceFont(context))
        assertFalse(NinePrefs.showHintBar(context))
    }

    @Test
    fun `nothing without a revision, and no wizard is no problem`() {
        assertFalse(SetupChoices.apply(context, mapOf(SetupChoices.KEY_SHOW_HINT_BAR to "false")))
        assertTrue(NinePrefs.showHintBar(context))
        SetupChoices.adopt(context)
        assertTrue(NinePrefs.showHintBar(context))
    }
}
