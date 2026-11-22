package life.furrer.nine.core.ui

import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.WindowInsetsController
import life.furrer.nine.core.Nine
import life.furrer.nine.core.NineApp
import life.furrer.nine.core.NineSurface
import life.furrer.nine.core.R
import life.furrer.nine.core.prefs.NinePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NineActivityTest {

    class TestActivity : NineActivity() {
        val monospace get() = appliedMonospaceFont

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContentView(View(this))
        }
    }

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        Nine.init(NineApp(id = "nine-activity-test", name = "Test", tagline = "", surfaces = listOf(NineSurface("", "", "", emptyList())), settingsActivity = Any::class.java))
    }

    private fun textViewStyle(activity: TestActivity): Int {
        val value = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.textViewStyle, value, true)
        return value.resourceId
    }

    private fun barsBehavior(activity: TestActivity): Int = activity.window.insetsController!!.systemBarsBehavior

    @Test
    fun `onCreate applies the monospace theme and remembers it`() {
        NinePrefs.setUseMonospaceFont(context, true)
        val activity = Robolectric.buildActivity(TestActivity::class.java).create().get()
        assertTrue(activity.monospace)
        assertEquals(R.style.NineTextViewStyle_Monospace, textViewStyle(activity))
    }

    @Test
    fun `onCreate keeps the plain theme with monospace off`() {
        NinePrefs.setUseMonospaceFont(context, false)
        val activity = Robolectric.buildActivity(TestActivity::class.java).create().get()
        assertFalse(activity.monospace)
        assertNotEquals(R.style.NineTextViewStyle_Monospace, textViewStyle(activity))
    }

    @Test
    fun `setContentView hides the status bar`() {
        val activity = Robolectric.buildActivity(TestActivity::class.java).create().get()
        assertEquals(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE, barsBehavior(activity))
    }

    @Test
    fun `regaining window focus hides the status bar again, losing it does not`() {
        val activity = Robolectric.buildActivity(TestActivity::class.java).setup().get()
        val controller = activity.window.insetsController!!
        controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_DEFAULT

        activity.onWindowFocusChanged(false)
        assertEquals(WindowInsetsController.BEHAVIOR_DEFAULT, barsBehavior(activity))

        activity.onWindowFocusChanged(true)
        assertEquals(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE, barsBehavior(activity))
    }
}
