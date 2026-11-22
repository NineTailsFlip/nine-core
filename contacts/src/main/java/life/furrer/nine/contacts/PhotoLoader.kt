package life.furrer.nine.contacts

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.ContactsContract
import android.util.LruCache
import life.furrer.nine.core.util.Async

private const val DETAIL_PHOTO_SIZE_DP = 68

/** A small LRU is enough — the photo is only ever shown on the detail screen (§9.8: no list
 *  avatars, "they'd halve the row count and add nothing actionable"), so at most a handful of
 *  distinct contacts' photos are ever in memory at once. */
private const val CACHE_SIZE = 24

/** Contact photo loading per §6.2: read via `Contacts.openContactPhotoInputStream(..., preferHighres
 *  = true)`, decoded off the main thread with `inSampleSize` matched to the target thumbnail size
 *  rather than decoding at full resolution just to immediately downscale it, cached by contact id
 *  in a small LRU. */
object PhotoLoader {
    private val cache = LruCache<Long, Bitmap>(CACHE_SIZE)

    fun load(context: Context, contactId: Long, targetSizeDp: Int = DETAIL_PHOTO_SIZE_DP, onResult: (Bitmap?) -> Unit) {
        cache.get(contactId)?.let {
            onResult(it)
            return
        }
        val targetSizePx = (targetSizeDp * context.resources.displayMetrics.density).toInt()
        Async.run(
            background = { decode(context, contactId, targetSizePx) },
            onResult = { bitmap ->
                if (bitmap != null) cache.put(contactId, bitmap)
                onResult(bitmap)
            },
        )
    }

    fun clearCache() = cache.evictAll()

    private fun decode(context: Context, contactId: Long, targetSizePx: Int): Bitmap? {
        val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
        val bytes = ContactsContract.Contacts.openContactPhotoInputStream(context.contentResolver, uri, true)
            ?.use { it.readBytes() }
            ?: return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, targetSizePx)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /** Largest power-of-two downscale that still leaves both dimensions at or above
     *  [targetSizePx] — the standard Android pattern for decoding "close enough, then let the
     *  ImageView do the final fit" rather than an exact-size decode. Internal (not private) so
     *  it's directly testable without needing a real decoded bitmap. */
    internal fun calculateInSampleSize(width: Int, height: Int, targetSizePx: Int): Int {
        if (targetSizePx <= 0) return 1
        var sampleSize = 1
        while (width / (sampleSize * 2) >= targetSizePx && height / (sampleSize * 2) >= targetSizePx) {
            sampleSize *= 2
        }
        return sampleSize
    }
}
