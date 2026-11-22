package life.furrer.nine.core.util

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** One background thread for every provider query in an app, so calendar/contacts/etc. reads
 *  never block the main thread — not even in onCreate — while still running in a predictable
 *  order relative to each other. Results are posted back through a main-thread Handler. No
 *  coroutines: that would be a dependency this suite doesn't otherwise need. */
object Async {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun <T> run(background: () -> T, onResult: (T) -> Unit) {
        executor.execute {
            val result = background()
            mainHandler.post { onResult(result) }
        }
    }
}
