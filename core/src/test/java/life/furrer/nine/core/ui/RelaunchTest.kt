package life.furrer.nine.core.ui

import android.app.Activity
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class RelaunchTest {

    @Test
    fun `relaunch finishes this screen and starts the given intent`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val intent = Intent(activity, Activity::class.java).putExtra("kept", 7)

        activity.relaunch(intent)

        assertTrue(activity.isFinishing)
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(7, started.getIntExtra("kept", 0))
    }
}
