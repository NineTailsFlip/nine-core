package life.furrer.nine.core.ui

import android.os.Looper
import android.view.ViewConfiguration
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class LongPressKeyTest {

    private var taps = 0
    private var holds = 0

    private fun key(): LongPressKey = LongPressKey(onTap = { taps++ }, onHold = { holds++ })

    // The hold timer is a real posted Handler callback, not something a mock clock can skip —
    // advancing Robolectric's simulated main-thread clock past the long-press timeout is what
    // actually fires it. Same approach as DialerActivityTest's own holdPastLongPressTimeout.
    private fun holdPastLongPressTimeout() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 50L))
    }

    @Test
    fun `a quick down-up fires onTap and not onHold`() {
        val key = key()
        key.onDown()
        key.onUp()

        assertEquals(1, taps)
        assertEquals(0, holds)
    }

    @Test
    fun `a held key fires onHold, and the matching up does not also fire onTap`() {
        val key = key()
        key.onDown()
        holdPastLongPressTimeout()
        key.onUp()

        assertEquals(0, taps)
        assertEquals(1, holds)
    }

    @Test
    fun `an up with no preceding down fires nothing`() {
        val key = key()
        key.onUp()

        assertEquals(0, taps)
        assertEquals(0, holds)
    }

    @Test
    fun `reset forgets an in-flight down without firing either callback`() {
        val key = key()
        key.onDown()
        key.reset()
        key.onUp()

        assertEquals(0, taps)
        assertEquals(0, holds)
    }

    @Test
    fun `auto-repeat downs during a hold fire onHold once and no tap`() {
        val key = key()
        key.onDown()
        holdPastLongPressTimeout()
        key.onDown() // auto-repeat
        holdPastLongPressTimeout()
        key.onDown()
        key.onUp()

        assertEquals(1, holds)
        assertEquals(0, taps)
    }

    @Test
    fun `repeats and the UP arriving after a reset don't read as a fresh tap`() {
        val key = key()
        key.onDown(repeatCount = 0)
        holdPastLongPressTimeout() // hold fires; the screen starts Settings
        key.reset() // onPause
        key.onDown(repeatCount = 1) // the key's remaining auto-repeat, still delivered here
        key.onUp()

        assertEquals(1, holds)
        assertEquals(0, taps)
    }
}
