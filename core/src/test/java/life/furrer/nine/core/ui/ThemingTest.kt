package life.furrer.nine.core.ui

import android.app.Activity
import life.furrer.nine.core.Nine
import life.furrer.nine.core.R
import life.furrer.nine.core.NineApp
import life.furrer.nine.core.NineSurface
import life.furrer.nine.core.prefs.NinePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ThemingTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        Nine.init(NineApp(id = "theming-test", name = "Test", tagline = "", surfaces = listOf(NineSurface("", "", "", emptyList())), settingsActivity = Any::class.java))
    }

    @Test
    fun `applyConfiguredTheme applies the monospace theme by default and reports it`() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        assertTrue(activity.applyConfiguredTheme())
    }

    @Test
    fun `applyConfiguredTheme reports false once monospace is turned off`() {
        NinePrefs.setUseMonospaceFont(context, false)
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        assertFalse(activity.applyConfiguredTheme())
    }

    @Test
    fun `recreateIfFontChanged is a no-op when the setting matches what was applied`() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        assertFalse(activity.recreateIfFontChanged(appliedMonospaceFont = true))
    }

    @Test
    fun `recreateIfFontChanged reports true when the setting no longer matches`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        NinePrefs.setUseMonospaceFont(context, false)
        assertTrue(activity.recreateIfFontChanged(appliedMonospaceFont = true))
    }

    @Test
    fun `dialogThemeResId follows the monospace font setting`() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        NinePrefs.setUseMonospaceFont(context, true)
        assertEquals(R.style.Theme_Nine_Dialog_Monospace, activity.dialogThemeResId())

        NinePrefs.setUseMonospaceFont(context, false)
        assertEquals(R.style.Theme_Nine_Dialog, activity.dialogThemeResId())
    }
}
