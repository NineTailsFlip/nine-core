package dev.g9.kit

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import life.furrer.nine.core.util.DateFmt

/** One photo or video on the phone, as MediaStore lists it. No Android types, so the row
 *  building and labels below stay unit-testable; [GalleryStore] turns it back into a content URI. */
data class GalleryItem(
    val id: Long,
    val isVideo: Boolean,
    /** When it was added to the phone, which is also the order the gallery lists it in. */
    val addedAtMs: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    /** As shown: already swapped for a video recorded on its side. */
    val width: Int,
    val height: Int,
    /** MediaStore's folder ("bucket"): its id, and its name — "Camera", "Screenshots", "TG9"… */
    val bucketId: Long,
    val folder: String,
    /** The file's path, when MediaStore gives one; reading it directly is faster than a copy. */
    val path: String?,
    val mimeType: String,
    val displayName: String = "",
    /** For an item in the trash: when Android deletes it for good. 0 otherwise. */
    val expiresAtMs: Long = 0L,
)

/** A folder on the folders screen: its newest item stands in as its thumbnail. */
data class GalleryFolder(val bucketId: Long, val name: String, val count: Int, val newest: GalleryItem)

sealed interface GalleryRow {
    fun isSelectable(): Boolean

    data class DayHeader(val label: String) : GalleryRow {
        override fun isSelectable() = false
    }

    data class Item(val item: GalleryItem) : GalleryRow {
        override fun isSelectable() = true
    }
}

/** Formatting shared by every screen that lists photos and videos. */
object Format {
    /** "0:07", "1:05", "1:02:03". */
    fun duration(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** "820 KB", "4.1 MB" — what a file costs on a keypad phone's storage or data plan. */
    fun fileSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    }
}

/** Newest first, as a phone's own "recent" album: what you just took is what you most likely
 *  want. A day header above each day, the same headers the suite's lists use. */
fun buildGalleryRows(items: List<GalleryItem>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<GalleryRow> {
    val rows = ArrayList<GalleryRow>(items.size + 32)
    var lastDay: LocalDate? = null
    for (item in items.sortedByDescending { it.addedAtMs }) {
        val day = Instant.ofEpochMilli(item.addedAtMs).atZone(zone).toLocalDate()
        if (day != lastDay) {
            rows += GalleryRow.DayHeader(DateFmt.dayLabel(day, today))
            lastDay = day
        }
        rows += GalleryRow.Item(item)
    }
    return rows
}

/** The folders that hold anything, the most recently added-to first. */
fun buildFolders(items: List<GalleryItem>): List<GalleryFolder> = items
    .groupBy { it.bucketId }
    .map { (id, inFolder) ->
        val newest = inFolder.maxBy { it.addedAtMs }
        GalleryFolder(id, newest.folder.ifBlank { "Other" }, inFolder.size, newest)
    }
    .sortedByDescending { it.newest.addedAtMs }

/** "14:02  Video 1:05" / "14:02  Photo". */
fun galleryTitle(item: GalleryItem, zone: ZoneId = ZoneId.systemDefault()): String {
    val time = DateFmt.formatTime(item.addedAtMs, zone)
    val kind = if (item.isVideo) "Video ${Format.duration((item.durationMs / 1000).toInt())}" else "Photo"
    return "$time  $kind"
}

/** "Camera · 4.1 MB · 1920×1080": where it's from and what it weighs. */
fun galleryMeta(item: GalleryItem): String = listOfNotNull(
    item.folder.takeIf { it.isNotBlank() },
    item.sizeBytes.takeIf { it > 0 }?.let { Format.fileSize(it) },
    if (item.width > 0 && item.height > 0) "${item.width}×${item.height}" else null,
).joinToString(" · ")

/** "photos 12 · videos 3" style count for a header line. */
fun galleryCount(items: Collection<GalleryItem>): String {
    val videos = items.count { it.isVideo }
    val photos = items.size - videos
    return listOfNotNull(
        when (photos) { 0 -> null; 1 -> "1 photo"; else -> "$photos photos" },
        when (videos) { 0 -> null; 1 -> "1 video"; else -> "$videos videos" },
    ).joinToString(" · ").ifEmpty { "empty" }
}

/** The first item of the day above ([direction] -1, newer) or below (+1, older) the one at
 *  [fromIndex] — Left/Right in a gallery list, the same day jump as in a chat. */
fun galleryDayJump(rows: List<GalleryRow>, fromIndex: Int, direction: Int): Int? {
    if (fromIndex !in rows.indices) return null
    var header = fromIndex
    while (header > 0 && rows[header] !is GalleryRow.DayHeader) header--
    if (direction < 0) {
        if (header == 0) return null
        var previous = header - 1
        while (previous > 0 && rows[previous] !is GalleryRow.DayHeader) previous--
        return (previous + 1).takeIf { it < rows.size && rows[it] is GalleryRow.Item }
    }
    var next = header + 1
    while (next < rows.size && rows[next] !is GalleryRow.DayHeader) next++
    return (next + 1).takeIf { it < rows.size }
}

/** What `#` has marked, in the order it was marked (the order an album arrives in, or the order
 *  files are shared). [max] caps it where the destination has a limit — Telegram's albums take 10. */
class GalleryMarks(private val max: Int = Int.MAX_VALUE) {
    enum class Result { ADDED, REMOVED, FULL }

    private val ids = LinkedHashSet<Long>()

    val size: Int get() = ids.size

    fun isMarked(id: Long): Boolean = id in ids

    fun toggle(id: Long): Result = when {
        ids.remove(id) -> Result.REMOVED
        ids.size >= max -> Result.FULL
        else -> {
            ids.add(id)
            Result.ADDED
        }
    }

    fun clear() = ids.clear()

    /** Forgets marks on items that are gone (moved to the trash, say). */
    fun retainAll(present: Set<Long>) {
        ids.retainAll(present)
    }

    /** The marked items still on the phone, in marking order. */
    fun resolve(items: Map<Long, GalleryItem>): List<GalleryItem> = ids.mapNotNull { items[it] }
}
