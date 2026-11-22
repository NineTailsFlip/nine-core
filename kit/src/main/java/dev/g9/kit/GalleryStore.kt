package dev.g9.kit

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Size

/** The phone's photos and videos, read through MediaStore. Blocking: everything here runs on a
 *  background thread. */
object GalleryStore {
    /** Enough to scroll back months without holding the whole library — for a list of everything.
     *  A folder is queried on its own, so this doesn't cut folders short. */
    const val RECENT_LIMIT = 5000

    /** Everything, for counting folders. Rows are small; a phone rarely holds more. */
    const val ALL_LIMIT = 50_000

    /** Where any change to photos and videos is announced, for a [life.furrer.nine.core.util.ProviderWatcher]. */
    val CHANGES: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /** Android 14 lets the user grant all photos and videos or only some; either is enough to
     *  list what we may read. */
    val PERMISSIONS = arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )

    fun hasAccess(context: Context): Boolean =
        PERMISSIONS.any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Only some photos allowed (Android 14's "Select photos"): screens offer to change the choice. */
    fun hasPartialAccess(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED

    fun contentUri(item: GalleryItem): Uri = ContentUris.withAppendedId(
        if (item.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        item.id,
    )

    /** Newest first. [bucketId] narrows it to one folder; [trashed] lists the trash instead. */
    @Suppress("DEPRECATION") // DATA: still the readable path on Android 14, used only when it is.
    fun load(context: Context, bucketId: Long? = null, trashed: Boolean = false, limit: Int = RECENT_LIMIT): List<GalleryItem> {
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.ORIENTATION,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_EXPIRES,
        )
        val image = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
        val video = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
        val selection = StringBuilder("${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)")
        val selectionArgs = mutableListOf(image.toString(), video.toString())
        if (bucketId != null) {
            selection.append(" AND ${MediaStore.MediaColumns.BUCKET_ID} = ?")
            selectionArgs += bucketId.toString()
        }
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection.toString())
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs.toTypedArray())
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_ADDED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            if (trashed) putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }
        val items = ArrayList<GalleryItem>()
        try {
            context.contentResolver.query(CHANGES, projection, args, null)?.use { c ->
                val col = projection.associateWith { c.getColumnIndexOrThrow(it) }
                fun idx(name: String) = col.getValue(name)
                while (c.moveToNext()) {
                    val isVideo = c.getInt(idx(MediaStore.Files.FileColumns.MEDIA_TYPE)) == video
                    val sideways = c.getInt(idx(MediaStore.MediaColumns.ORIENTATION)).let { it == 90 || it == 270 }
                    val w = c.getInt(idx(MediaStore.MediaColumns.WIDTH))
                    val h = c.getInt(idx(MediaStore.MediaColumns.HEIGHT))
                    items += GalleryItem(
                        id = c.getLong(idx(MediaStore.Files.FileColumns._ID)),
                        isVideo = isVideo,
                        addedAtMs = c.getLong(idx(MediaStore.MediaColumns.DATE_ADDED)) * 1000L,
                        durationMs = if (isVideo) c.getLong(idx(MediaStore.MediaColumns.DURATION)) else 0L,
                        sizeBytes = c.getLong(idx(MediaStore.MediaColumns.SIZE)),
                        width = if (sideways) h else w,
                        height = if (sideways) w else h,
                        bucketId = c.getLong(idx(MediaStore.MediaColumns.BUCKET_ID)),
                        folder = c.getString(idx(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)).orEmpty(),
                        path = c.getString(idx(MediaStore.MediaColumns.DATA)),
                        mimeType = c.getString(idx(MediaStore.MediaColumns.MIME_TYPE)) ?: if (isVideo) "video/mp4" else "image/jpeg",
                        displayName = c.getString(idx(MediaStore.MediaColumns.DISPLAY_NAME)).orEmpty(),
                        expiresAtMs = c.getLong(idx(MediaStore.MediaColumns.DATE_EXPIRES)) * 1000L,
                    )
                }
            }
        } catch (e: Exception) {
            Log.w("G9", "gallery query", e)
        }
        return items
    }

    /** MediaStore's own cached thumbnail, the fast path for list rows. */
    fun thumbnail(context: Context, uri: Uri, sidePx: Int): Bitmap? = try {
        context.contentResolver.loadThumbnail(uri, Size(sidePx, sidePx), null)
    } catch (e: Exception) {
        null
    }

    fun thumbnail(context: Context, item: GalleryItem, sidePx: Int): Bitmap? = thumbnail(context, contentUri(item), sidePx)

    /** A still for a full-screen preview: the photo itself, sampled to about [longestPx], or a
     *  video's large thumbnail. */
    fun preview(context: Context, item: GalleryItem, longestPx: Int): Bitmap? = try {
        if (item.isVideo) {
            context.contentResolver.loadThumbnail(contentUri(item), Size(longestPx, longestPx), null)
        } else {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, contentUri(item))) { decoder, info, _ ->
                decoder.setTargetSampleSize(sampleSize(info.size, longestPx))
            }
        }
    } catch (e: Exception) {
        Log.w("G9", "gallery preview", e)
        null
    }

    /** The picture behind [uri] for a full-screen viewer, sampled to about [longestPx]: a GIF or
     *  animated WebP comes back as an AnimatedImageDrawable, ready to start. Applies EXIF rotation. */
    fun decodeForViewing(context: Context, uri: Uri, longestPx: Int): Drawable? = try {
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.setTargetSampleSize(sampleSize(info.size, longestPx))
        }
    } catch (e: Exception) {
        Log.w("G9", "decode $uri", e)
        null
    }

    /** The largest power-of-two step down that still leaves at least [longestPx] on the long side. */
    fun sampleSize(size: Size, longestPx: Int): Int {
        var sample = 1
        while (maxOf(size.width, size.height) / (sample * 2) >= longestPx) sample *= 2
        return sample
    }
}
