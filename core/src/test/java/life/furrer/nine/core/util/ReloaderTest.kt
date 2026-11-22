package life.furrer.nine.core.util

import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ReloaderTest {

    private fun awaitUntil(timeoutMs: Long = 3000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() <= deadline) { "condition not met within ${timeoutMs}ms" }
            Thread.sleep(5)
        }
    }

    @Test
    fun `requests arriving during a load collapse into exactly one follow-up load`() {
        val gate = CountDownLatch(1)
        val runs = AtomicInteger()
        val results = mutableListOf<Int>()
        val reloader = Reloader(
            background = {
                val run = runs.incrementAndGet()
                if (run == 1) gate.await(3, TimeUnit.SECONDS)
                run
            },
            onResult = { results.add(it) },
        )

        reloader.request()
        awaitUntil { runs.get() == 1 }
        repeat(5) { reloader.request() }
        gate.countDown()

        awaitUntil { results.size == 2 }
        Thread.sleep(50)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(1, 2), results)
        assertEquals(2, runs.get())
    }

    @Test
    fun `a request after a load finished starts a fresh one`() {
        val runs = AtomicInteger()
        val reloader = Reloader(background = { runs.incrementAndGet() }, onResult = {})

        reloader.request()
        awaitUntil { runs.get() == 1 }
        shadowOf(Looper.getMainLooper()).idle()
        reloader.request()

        awaitUntil { runs.get() == 2 }
    }

    @Test
    fun `only the result a fresh load follows is reported stale`() {
        val gate = CountDownLatch(1)
        val runs = AtomicInteger()
        val staleness = mutableListOf<Boolean>()
        lateinit var reloader: Reloader<Int>
        reloader = Reloader(
            background = {
                val run = runs.incrementAndGet()
                if (run == 1) gate.await(3, TimeUnit.SECONDS)
                run
            },
            onResult = { staleness.add(reloader.isStale) },
        )

        reloader.request()
        awaitUntil { runs.get() == 1 }
        reloader.request()
        gate.countDown()

        awaitUntil { staleness.size == 2 }
        assertEquals(listOf(true, false), staleness)
    }
}
