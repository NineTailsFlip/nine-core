package life.furrer.nine.core.util

/** A screen's "reload everything" read, run on [Async]'s thread at most once at a time. A
 *  [request] while a load is already in flight doesn't queue a second full load behind it — it
 *  only marks the running one stale, and exactly one fresh load follows it, however many
 *  requests arrived meanwhile. The in-flight result is still delivered first, so the screen shows
 *  *something* as early as possible.
 *
 *  Exists because [Async] is one shared serial thread: a screen whose onResume, ContentObserver
 *  and post-write callbacks each call a plain `Async.run` stacks one full provider read per
 *  trigger, and every other screen's read (a detail screen opened meanwhile, say) waits behind
 *  all of them — measured on the daily phone at over a second of pure queueing on P9's dialer.
 *
 *  Main thread only, like everything else that touches a screen's state. */
class Reloader<T>(
    private val background: () -> T,
    private val onResult: (T) -> Unit,
) {
    private var running = false
    private var stale = false

    /** True inside [onResult] when that result is already outdated: a [request] arrived while it
     *  was loading, so a fresh load follows right after it. Lets a screen hold back something
     *  that belongs to the newest request only (a selection jump, say) for the result that
     *  actually reflects it. */
    val isStale: Boolean get() = stale

    fun request() {
        if (running) {
            stale = true
            return
        }
        running = true
        Async.run(background) { result ->
            running = false
            onResult(result)
            if (stale) {
                stale = false
                request()
            }
        }
    }
}
