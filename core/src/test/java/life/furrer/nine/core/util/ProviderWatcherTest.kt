package life.furrer.nine.core.util

import android.net.Uri
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ProviderWatcherTest {

    private val context = RuntimeEnvironment.getApplication()
    private val root = Uri.parse("content://life.furrer.nine.test")
    private val child = Uri.withAppendedPath(root, "items/7")
    private var changes = 0

    private fun observersOn(uri: Uri) = shadowOf(context.contentResolver).getContentObservers(uri).size

    private fun notify(uri: Uri) {
        context.contentResolver.notifyChange(uri, null)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `a started watcher hears changes under its uri`() {
        val watcher = ProviderWatcher(context, root) { changes++ }
        watcher.start()
        notify(child)
        assertEquals(1, changes)
    }

    @Test
    fun `start and stop are idempotent`() {
        val watcher = ProviderWatcher(context, root) { changes++ }
        watcher.start()
        watcher.start()
        assertEquals(1, observersOn(root))
        notify(root)
        assertEquals(1, changes)

        watcher.stop()
        watcher.stop()
        assertEquals(0, observersOn(root))
        notify(root)
        assertEquals(1, changes)
    }

    @Test
    fun `a stopped watcher can start again`() {
        val watcher = ProviderWatcher(context, root) { changes++ }
        watcher.start()
        watcher.stop()
        watcher.start()
        notify(root)
        assertEquals(1, changes)
    }

    @Test
    fun `stop before any start does nothing`() {
        ProviderWatcher(context, root) { changes++ }.stop()
        assertEquals(0, observersOn(root))
    }

    @Test
    fun `without descendants only the exact uri counts`() {
        val watcher = ProviderWatcher(context, root, notifyForDescendants = false) { changes++ }
        watcher.start()
        notify(child)
        assertEquals(0, changes)
        notify(root)
        assertEquals(1, changes)
    }
}
