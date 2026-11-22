package life.furrer.nine.core.prefs

import android.net.Uri
import java.io.File
import life.furrer.nine.core.Nine
import life.furrer.nine.core.NineApp
import life.furrer.nine.core.NineSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class BackupTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        Nine.init(
            NineApp(id = "l9", name = "L9", tagline = "", surfaces = listOf(NineSurface("", "", "", emptyList())), settingsActivity = Any::class.java),
        )
    }

    private fun fileUri(name: String): Uri = Uri.fromFile(File(context.cacheDir, name))

    @Test
    fun `exported settings round trip through import, restoring every preference type`() {
        val prefs = NinePrefs.of(context)
        prefs.edit()
            .putInt("slots", 6)
            .putBoolean("flag", false)
            .putString("alignment", "RIGHT")
            .apply()

        val uri = fileUri("backup.json")
        assertTrue(Backup.exportTo(context, uri))

        // Wipe every preference the export just captured, to prove import (not leftover state)
        // is what restores them.
        prefs.edit().clear().apply()

        assertTrue(Backup.importFrom(context, uri))

        assertEquals(6, prefs.getInt("slots", -1))
        assertFalse(prefs.getBoolean("flag", true))
        assertEquals("RIGHT", prefs.getString("alignment", null))
    }

    @Test
    fun `import replaces existing preferences rather than merging with them`() {
        val prefs = NinePrefs.of(context)
        prefs.edit().putInt("slots", 5).apply()
        val uri = fileUri("backup.json")
        assertTrue(Backup.exportTo(context, uri))

        // Change state after export but before import — import should overwrite this, not merge.
        prefs.edit().putInt("slots", 9).putBoolean("flag", false).apply()

        assertTrue(Backup.importFrom(context, uri))

        assertEquals(5, prefs.getInt("slots", -1))
        // "flag" wasn't part of the exported snapshot's non-default state at export time —
        // clear() during import means it's simply absent afterward, not the post-export value.
        assertFalse(prefs.contains("flag"))
    }

    @Test
    fun `import rejects a file whose app id doesn't match the running app`() {
        val file = File(context.cacheDir, "not-l9.json")
        file.writeText("""{"app":"some-other-app","version":1,"preferences":{}}""")

        NinePrefs.of(context).edit().putInt("slots", 5).apply()
        assertFalse(Backup.importFrom(context, Uri.fromFile(file)))
        // Rejected import must leave existing settings untouched.
        assertEquals(5, NinePrefs.of(context).getInt("slots", -1))
    }

    @Test
    fun `import rejects malformed JSON without crashing`() {
        val file = File(context.cacheDir, "garbage.json")
        file.writeText("not json at all")

        assertFalse(Backup.importFrom(context, Uri.fromFile(file)))
    }

    @Test
    fun `import of a missing file fails gracefully`() {
        val uri = fileUri("does-not-exist.json")
        assertFalse(Backup.importFrom(context, uri))
    }

    @Test
    fun `a file exported by L9's old app-specific BackupRepository still imports`() {
        // The exact shape L9's own BackupRepository (pre-:core) wrote and read, byte for byte —
        // moving the code into core must not orphan backup files people already made.
        val file = File(context.cacheDir, "old-l9-backup.json")
        file.writeText(
            """
            {
              "app": "l9",
              "version": 1,
              "preferences": {
                "slot_count": 7,
                "show_indices": false,
                "list_alignment": "CENTER"
              }
            }
            """.trimIndent(),
        )

        assertTrue(Backup.importFrom(context, Uri.fromFile(file)))

        val prefs = NinePrefs.of(context)
        assertEquals(7, prefs.getInt("slot_count", -1))
        assertFalse(prefs.getBoolean("show_indices", true))
        assertEquals("CENTER", prefs.getString("list_alignment", null))
    }
}
