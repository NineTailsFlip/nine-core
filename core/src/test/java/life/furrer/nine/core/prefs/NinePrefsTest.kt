package life.furrer.nine.core.prefs

import life.furrer.nine.core.Nine
import life.furrer.nine.core.NineApp
import life.furrer.nine.core.NineSurface
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NinePrefsTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        Nine.init(NineApp(id = "nineprefs-test", name = "Test", tagline = "", surfaces = listOf(NineSurface("", "", "", emptyList())), settingsActivity = Any::class.java))
    }

    @Test
    fun `useMonospaceFont defaults to true and round-trips`() {
        assertTrue(NinePrefs.useMonospaceFont(context))
        NinePrefs.setUseMonospaceFont(context, false)
        assertFalse(NinePrefs.useMonospaceFont(context))
        NinePrefs.setUseMonospaceFont(context, true)
        assertTrue(NinePrefs.useMonospaceFont(context))
    }

    @Test
    fun `showHintBar defaults to true and round-trips`() {
        assertTrue(NinePrefs.showHintBar(context))
        NinePrefs.setShowHintBar(context, false)
        assertFalse(NinePrefs.showHintBar(context))
        NinePrefs.setShowHintBar(context, true)
        assertTrue(NinePrefs.showHintBar(context))
    }

    @Test
    fun `of resolves the shared preferences file named after the app id`() {
        NinePrefs.setUseMonospaceFont(context, false)
        assertFalse(NinePrefs.of(context).getBoolean("use_monospace_font", true))
    }
}
