package life.furrer.nine.core.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import life.furrer.nine.core.KeyHelp
import life.furrer.nine.core.Nine
import life.furrer.nine.core.NineApp
import life.furrer.nine.core.NineSurface
import life.furrer.nine.core.prefs.NinePrefs
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** A minimal app under test: one toggle row of its own, plus whatever core's Theme/About
 *  sections always add. */
class FakeSettingsActivity : NineSettingsActivity() {
    var flag = false
    var themeExtraCalls = 0
    var recreateCalls = 0

    override fun recreate() {
        recreateCalls++
    }

    fun deliverResult(requestCode: Int, resultCode: Int, data: Intent?) = onActivityResult(requestCode, resultCode, data)

    override fun buildAppSections(screen: SettingsScreen, surface: NineSurface) {
        screen.section("App") {
            toggle("Flag", get = { flag }, set = { flag = it })
        }
    }

    override fun buildThemeExtras(scope: SectionScope) {
        themeExtraCalls++
        scope.action("Extra", onSelect = {})
    }
}

@RunWith(RobolectricTestRunner::class)
class NineSettingsActivityTest {

    @Before
    fun setUp() {
        Nine.init(
            NineApp(
                id = "settings-test",
                name = "Test",
                tagline = "",
                hasHintBar = true,
                settingsActivity = Any::class.java,
                surfaces = listOf(NineSurface("", "Test", "intro", listOf(KeyHelp("OK", "does a thing")))),
            ),
        )
    }

    private fun buildActivity(): FakeSettingsActivity =
        Robolectric.buildActivity(FakeSettingsActivity::class.java).setup().get()

    private fun rowContainer(activity: FakeSettingsActivity): LinearLayout {
        val scrollView = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ScrollView
        return scrollView.getChildAt(0) as LinearLayout
    }

    /** Section headers are the app's own uppercase title (APP/THEME/ABOUT); every actual row —
     *  colon or bare action label — isn't. */
    private fun isSectionHeader(text: CharSequence): Boolean =
        text.isNotEmpty() && text.toString() == text.toString().uppercase()

    private fun rows(activity: FakeSettingsActivity): List<TextView> =
        (0 until rowContainer(activity).childCount)
            .map { rowContainer(activity).getChildAt(it) as TextView }
            .filterNot { isSectionHeader(it.text) }

    private fun press(activity: FakeSettingsActivity, keyCode: Int) {
        activity.onKeyDown(keyCode, KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0))
    }

    @Test
    fun `app section renders first, Theme and About always follow`() {
        val activity = buildActivity()
        val headers = (0 until rowContainer(activity).childCount)
            .map { rowContainer(activity).getChildAt(it) as TextView }
            .filter { isSectionHeader(it.text) }
            .map { it.text.toString() }
        assertEquals(listOf("APP", "THEME", "BACKUP", "ABOUT"), headers)
    }

    @Test
    fun `About section has Intro, Key mapping, About, Open website in every app`() {
        val activity = buildActivity()
        val labels = rows(activity).map { it.text.toString() }
        assertTrue(labels.contains("Intro"))
        assertTrue(labels.contains("Key mapping"))
        assertTrue(labels.contains("About"))
        assertTrue(labels.contains("Open website"))
    }

    @Test
    fun `Theme section includes Hint bar only when the app declares hasHintBar`() {
        val withHintBar = buildActivity()
        assertTrue(rows(withHintBar).any { it.text.contains("Hint bar") })

        Nine.init(
            NineApp(id = "no-hint-bar", name = "Test", tagline = "", hasHintBar = false, surfaces = listOf(NineSurface("", "", "", emptyList())), settingsActivity = Any::class.java),
        )
        val withoutHintBar = buildActivity()
        assertTrue(rows(withoutHintBar).none { it.text.contains("Hint bar") })
    }

    @Test
    fun `buildThemeExtras rows land inside the Theme section`() {
        val activity = buildActivity()
        assertEquals(1, activity.themeExtraCalls)
        assertTrue(rows(activity).any { it.text == "Extra" })
    }

    @Test
    fun `Down wraps from the last row back to the first`() {
        val activity = buildActivity()
        val count = rows(activity).size
        repeat(count) { press(activity, KeyEvent.KEYCODE_DPAD_DOWN) }
        // Back to the first row: Left/Right toggles Flag, proving selection wrapped exactly once.
        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(activity.flag)
    }

    @Test
    fun `Left-Right toggles the focused row`() {
        val activity = buildActivity()
        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(activity.flag)
        press(activity, KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue(!activity.flag)
    }

    @Test
    fun `Back finishes the activity`() {
        val activity = buildActivity()
        press(activity, KeyEvent.KEYCODE_BACK)
        assertTrue(activity.isFinishing)
    }

    /** What BackupFlow's import picker hands back: a backup of this test app's prefs. */
    private fun importResult(activity: FakeSettingsActivity, monospace: Boolean) {
        val file = File(activity.cacheDir, "backup.json")
        file.writeText("""{"app":"settings-test","version":1,"preferences":{"use_monospace_font":$monospace}}""")
        // BackupFlow's import request code.
        activity.deliverResult(9002, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))
    }

    @Test
    fun `an import that flips the font rebuilds the screen, like the Font row`() {
        val activity = buildActivity()
        assertTrue(NinePrefs.useMonospaceFont(activity)) // the default
        importResult(activity, monospace = false)
        assertFalse(NinePrefs.useMonospaceFont(activity))
        assertEquals(1, activity.recreateCalls)
    }

    @Test
    fun `an import that keeps the font only re-renders`() {
        val activity = buildActivity()
        importResult(activity, monospace = true)
        assertEquals(0, activity.recreateCalls)
    }
}
