package dev.g9.kit

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryRowsTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 22)
    private fun at(day: Int, hour: Int) = LocalDate.of(2026, 11, day).atTime(hour, 0).toInstant(zone).toEpochMilli()

    private fun item(id: Long, addedAtMs: Long, video: Boolean = false) = GalleryItem(
        id = id,
        isVideo = video,
        addedAtMs = addedAtMs,
        durationMs = if (video) 65_000 else 0,
        sizeBytes = 4_300_000,
        width = 1920,
        height = 1080,
        bucketId = 1L,
        folder = "Camera",
        path = null,
        mimeType = if (video) "video/mp4" else "image/jpeg",
    )

    private val sample = listOf(item(1, at(20, 9)), item(3, at(22, 14)), item(2, at(22, 8)))

    @Test
    fun newestFirstUnderDayHeaders() {
        val rows = buildGalleryRows(sample, today, zone)
        assertTrue(rows[0] is GalleryRow.DayHeader)
        assertEquals(listOf(3L, 2L, 1L), rows.filterIsInstance<GalleryRow.Item>().map { it.item.id })
        assertEquals(2, rows.count { it is GalleryRow.DayHeader })
    }

    @Test
    fun dayJumpMovesBetweenDays() {
        // [header 22nd, 3, 2, header 20th, 1]
        val rows = buildGalleryRows(sample, today, zone)
        assertEquals(4, galleryDayJump(rows, 2, 1))
        assertEquals(1, galleryDayJump(rows, 4, -1))
        assertNull(galleryDayJump(rows, 1, -1))
        assertNull(galleryDayJump(rows, 4, 1))
    }

    @Test
    fun labels() {
        assertEquals("14:00  Video 1:05", galleryTitle(item(1, at(22, 14), video = true), zone))
        assertEquals("Camera · 4.1 MB · 1920×1080", galleryMeta(item(1, at(22, 14))))
        assertEquals("", galleryMeta(item(1, 0).copy(folder = "", sizeBytes = 0, width = 0)))
    }

    @Test
    fun marksKeepOrderAndCap() {
        val marks = GalleryMarks(max = 2)
        assertEquals(GalleryMarks.Result.ADDED, marks.toggle(5))
        assertEquals(GalleryMarks.Result.ADDED, marks.toggle(2))
        assertEquals(GalleryMarks.Result.FULL, marks.toggle(9))
        assertEquals(GalleryMarks.Result.REMOVED, marks.toggle(5))
        assertEquals(GalleryMarks.Result.ADDED, marks.toggle(9))
        val items = listOf(item(2, 0), item(9, 0)).associateBy { it.id }
        assertEquals(listOf(2L, 9L), marks.resolve(items).map { it.id })
        marks.retainAll(setOf(9L))
        assertEquals(1, marks.size)
    }

    @Test
    fun foldersNewestFirstWithCounts() {
        val screenshots = item(4, at(22, 15)).copy(bucketId = 2L, folder = "Screenshots")
        val folders = buildFolders(sample + screenshots)
        assertEquals(listOf("Screenshots", "Camera"), folders.map { it.name })
        assertEquals(3, folders[1].count)
        assertEquals(3L, folders[1].newest.id)
    }

    @Test
    fun counts() {
        assertEquals("2 photos · 1 video", galleryCount(listOf(item(1, 0), item(2, 0), item(3, 0, video = true))))
        assertEquals("empty", galleryCount(emptyList()))
    }
}
