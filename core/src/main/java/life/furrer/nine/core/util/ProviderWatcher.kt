package life.furrer.nine.core.util

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper

/** A screen's watch on one provider [uri]: [onChange] runs on the main thread whenever something
 *  under it changes (a sync landing, another app's write, a host's own write through the store),
 *  between [start] (onResume) and [stop] (onPause). Both are idempotent, so a screen can call
 *  [start] on every resume and [stop] on every pause without tracking whether it is registered.
 *
 *  [onChange] fires once per provider notification, and a sync sends many — pair it with a
 *  [Reloader] rather than a plain [Async.run], so a burst costs one extra read, not one each.
 *
 *  Registering on a provider the app has no read permission for throws a SecurityException
 *  rather than failing quietly like a query does (the calendar provider's does, see C9's
 *  `CalendarActivity.onResume`), so screens only [start] once their permission is granted. */
class ProviderWatcher(
    private val context: Context,
    private val uri: Uri,
    private val notifyForDescendants: Boolean = true,
    private val onChange: () -> Unit,
) {
    private var observer: ContentObserver? = null

    fun start() {
        if (observer != null) return
        val newObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = this@ProviderWatcher.onChange()
        }
        context.contentResolver.registerContentObserver(uri, notifyForDescendants, newObserver)
        observer = newObserver
    }

    fun stop() {
        val current = observer ?: return
        context.contentResolver.unregisterContentObserver(current)
        observer = null
    }
}
