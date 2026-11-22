package life.furrer.nine.core.manual

import android.view.KeyEvent
import android.widget.ScrollView
import android.widget.TextView
import life.furrer.nine.core.KeyHelp
import life.furrer.nine.core.Nine
import life.furrer.nine.core.NineApp
import life.furrer.nine.core.NineSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ManualActivityTest {

    @Before
    fun setUp() {
        Nine.init(
            NineApp(
                id = "manual-test",
                name = "X9",
                tagline = "Testing on 9 keys",
                settingsActivity = Any::class.java,
                sourceUrl = "https://example.invalid/source",
                trackerUrl = "https://example.invalid/tracker",
                surfaces = listOf(
                    NineSurface(
                        id = "",
                        name = "X9",
                        introText = "Welcome to X9.",
                        keyMap = listOf(KeyHelp("OK", "Does the thing.")),
                    ),
                ),
            ),
        )
    }

    private fun allText(activity: ManualActivity): String {
        val contentFrame = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        val scrollView = contentFrame.getChildAt(0) as ScrollView
        fun collect(view: android.view.View): String = when (view) {
            is TextView -> view.text.toString()
            is android.view.ViewGroup -> (0 until view.childCount).joinToString(" ") { collect(view.getChildAt(it)) }
            else -> ""
        }
        return collect(scrollView)
    }

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `intro page shows the surface's intro text`() {
        val activity = Robolectric.buildActivity(
            ManualActivity::class.java,
            ManualActivity.intentFor(context, ManualActivity.PAGE_INTRO),
        ).setup().get()
        assertTrue(allText(activity).contains("Welcome to X9."))
    }

    @Test
    fun `key mapping page shows the surface's key map`() {
        val activity = Robolectric.buildActivity(
            ManualActivity::class.java,
            ManualActivity.intentFor(context, ManualActivity.PAGE_KEY_MAPPING),
        ).setup().get()
        val text = allText(activity)
        assertTrue(text.contains("OK"))
        assertTrue(text.contains("Does the thing."))
    }

    @Test
    fun `about page is generated from the app descriptor`() {
        val activity = Robolectric.buildActivity(
            ManualActivity::class.java,
            ManualActivity.intentFor(context, ManualActivity.PAGE_ABOUT),
        ).setup().get()
        val text = allText(activity)
        assertTrue(text.contains("X9"))
        assertTrue(text.contains("Testing on 9 keys"))
        assertTrue(text.contains("https://example.invalid/source"))
        assertTrue(text.contains("https://example.invalid/tracker"))
    }

    @Test
    fun `back finishes the activity`() {
        val activity = Robolectric.buildActivity(ManualActivity::class.java).setup().get()
        activity.onKeyDown(KeyEvent.KEYCODE_BACK, KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))
        assertTrue(activity.isFinishing)
    }

    @Test
    fun reflowJoinsWrappedLinesButKeepsParagraphs() {
        val text = "First line\nwraps here.\n\nSecond   \n  paragraph."
        assertEquals("First line wraps here.\n\nSecond paragraph.", ManualActivity.reflow(text))
    }
}
