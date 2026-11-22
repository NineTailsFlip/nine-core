package life.furrer.nine.core.ui

import android.app.Activity
import android.content.Intent
import android.view.KeyEvent
import java.time.LocalDate
import life.furrer.nine.core.Nine
import life.furrer.nine.core.NineApp
import life.furrer.nine.core.NineSurface
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class DatePickerActivityTest {

    @Before
    fun setUp() {
        Nine.init(NineApp(id = "date-picker-test", name = "Test", tagline = "", surfaces = listOf(NineSurface("", "", "", emptyList())), settingsActivity = Any::class.java))
    }

    private fun pickWithOk(intent: Intent): LocalDate {
        val activity = Robolectric.buildActivity(DatePickerActivity::class.java, intent).setup().get()
        activity.onKeyDown(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        val result = shadowOf(activity)
        assertEquals(Activity.RESULT_OK, result.resultCode)
        return LocalDate.ofEpochDay(result.resultIntent.getLongExtra(DatePickerActivity.EXTRA_EPOCH_DAY, -1L))
    }

    @Test
    fun `opens on today by default`() {
        val intent = Intent(RuntimeEnvironment.getApplication(), DatePickerActivity::class.java)
        assertEquals(LocalDate.now(), pickWithOk(intent))
    }

    @Test
    fun `opens on the initial day when given one`() {
        val day = LocalDate.of(2027, 3, 14)
        val intent = Intent(RuntimeEnvironment.getApplication(), DatePickerActivity::class.java)
            .putExtra(DatePickerActivity.EXTRA_INITIAL_EPOCH_DAY, day.toEpochDay())
        assertEquals(day, pickWithOk(intent))
    }
}
