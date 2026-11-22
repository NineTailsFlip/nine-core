package life.furrer.nine.contacts

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PhotoLoaderTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `no downscale needed when the image is already at or below the target size`() {
        assertEquals(1, PhotoLoader.calculateInSampleSize(64, 64, 68))
    }

    @Test
    fun `downscales by the largest power of two that still meets the target size`() {
        // 272x272 halved once is 136 (>= 68), halved twice is 68 (>= 68), halved three times is
        // 34 (< 68) — so sampleSize 4 is the largest downscale that still meets the target.
        assertEquals(4, PhotoLoader.calculateInSampleSize(272, 272, 68))
    }

    @Test
    fun `uses the smaller dimension's headroom so neither side ends up under target`() {
        // A tall image: width alone would allow sampleSize 2 (200/2=100 >= 68), but height only
        // allows sampleSize 1 (150/2=75 >= 68, 150/4=37 < 68) — wait, both allow 2. Use a case
        // where they genuinely differ: width allows 4, height only allows 1.
        assertEquals(1, PhotoLoader.calculateInSampleSize(400, 100, 68))
    }

    @Test
    fun `a non-positive target size never triggers downscaling`() {
        assertEquals(1, PhotoLoader.calculateInSampleSize(1000, 1000, 0))
    }

    @Test
    fun `load calls back with null when there is no photo, without crashing`() {
        // Async's background executor is a real thread (not a Robolectric-controlled looper),
        // so a single idle() call would race it — poll instead, same pattern as
        // EventEditActivityTest's awaitUntil.
        var called = false
        var result: Any? = null
        PhotoLoader.load(context, contactId = 1L) {
            called = true
            result = it
        }
        val deadline = System.currentTimeMillis() + 3000
        while (!called) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() <= deadline) { "load() never called back within 3000ms" }
            Thread.sleep(5)
        }
        assertNull(result)
    }
}
