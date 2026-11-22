package io.github.ninetailsflip.update

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdateInfoTest {
    private val json = """
        {"app": "tg9", "versionCode": 4, "versionName": "0.1.3", "notes": "## What's Changed\n* Videos play",
         "apks": [
           {"abi": "arm64-v8a", "url": "https://x/tg9-arm64.apk", "size": 24000000, "sha256": "AB12"},
           {"abi": "armeabi-v7a", "url": "https://x/tg9-v7a.apk", "size": 21000000, "sha256": "cd34"},
           {"abi": "universal", "url": "https://x/tg9.apk", "size": 41000000, "sha256": "ef56"}
         ]}
    """.trimIndent()

    @Test
    fun parsesAndRoundTrips() {
        val info = UpdateInfo.parse(json)
        assertEquals(4L, info.versionCode)
        assertEquals("0.1.3", info.versionName)
        assertEquals(3, info.apks.size)
        assertEquals("ab12", info.apks[0].sha256)
        assertEquals(info, UpdateInfo.parse(info.toJson()))
    }

    @Test
    fun newerByVersionCode() {
        val info = UpdateInfo.parse(json)
        assertTrue(info.isNewerThan(3))
        assertFalse(info.isNewerThan(4))
        assertFalse(info.isNewerThan(5))
    }

    @Test
    fun picksTheApkForThePhone() {
        val info = UpdateInfo.parse(json)
        assertEquals("armeabi-v7a", info.apkFor(listOf("armeabi-v7a", "armeabi"))!!.abi)
        assertEquals("arm64-v8a", info.apkFor(listOf("arm64-v8a", "armeabi-v7a"))!!.abi)
        assertEquals("universal", info.apkFor(listOf("x86_64"))!!.abi)
        val single = info.copy(apks = info.apks.take(1))
        assertEquals("arm64-v8a", single.apkFor(listOf("x86_64"))!!.abi)
        assertNull(info.copy(apks = emptyList()).apkFor(listOf("arm64-v8a")))
    }

    @Test
    fun checkIsDueDaily() {
        val day = Updates.CHECK_INTERVAL_MS
        assertTrue(isCheckDue(0, 1_000_000_000_000))
        assertFalse(isCheckDue(1000, 1000 + day - 1))
        assertTrue(isCheckDue(1000, 1000 + day))
        assertTrue(isCheckDue(5000, 1000)) // clock went backwards
    }

    @Test
    fun sha256OfAFile() {
        val file = File.createTempFile("update", ".apk").apply { writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex(file))
        file.delete()
    }

    @Test
    fun releaseNotesBecomePlainLines() {
        val notes = "## What's Changed\n* Videos play by @someone in https://github.com/x/pull/1\n" +
            "* See [the docs](https://example.com)\n\n\n\n**Full Changelog**: https://github.com/x/compare/v1...v2"
        assertEquals("What's Changed\n• Videos play\n• See the docs", plainNotes(notes))
    }
}
