package dev.g9.kit

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.abs
import life.furrer.nine.core.ui.Dimens
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.dp

const val THUMB_HEADER_HEIGHT_DP = 24
const val THUMB_ROW_HEIGHT_DP = 64
const val THUMB_DP = 52

/** One list row with a thumbnail — a photo, a video, a folder — or a day header, built once and
 *  re-bound in place (RowListView re-binds every visible row on every key press, and a keypad
 *  phone's CPU notices allocations there). The thumbnail sits left, then a title line over a dim
 *  meta line. The selected row is lit and the rest sit back, as in every list of the suite. */
class ThumbRowView(context: Context) : LinearLayout(context) {
    private val side = context.dp(Dimens.SIDE_PADDING_DP)
    private val header = TextView(context).apply {
        textSize = 11f
        letterSpacing = 0.11f
        gravity = Gravity.CENTER
        setTextColor(Palette.GRAY_FAINT)
    }
    private val thumb = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(Palette.DIVIDER)
    }
    private val title = TextView(context).apply {
        textSize = 16f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val meta = TextView(context).apply {
        textSize = 12f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(Palette.GRAY_MUTED)
    }
    private val row = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(side, 0, side, 0)
        val size = context.dp(THUMB_DP)
        addView(thumb, LayoutParams(size, size))
        addView(
            LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(context.dp(12), 0, 0, 0)
                addView(title)
                addView(meta)
            },
            LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
    }

    private var boundKey: Any? = null

    init {
        orientation = VERTICAL
        addView(header, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun bindHeader(label: String) {
        if (boundKey == label) return
        boundKey = label
        header.text = label
        header.visibility = VISIBLE
        row.visibility = GONE
    }

    /** [marked] shows a green tick in front of the title — `#`'s mark for sharing, sending or the
     *  trash. */
    fun bindRow(titleText: String, metaText: String, selected: Boolean, marked: Boolean, bitmap: Bitmap?) {
        val key = Bound(titleText, metaText, selected, marked, bitmap)
        if (boundKey == key) return
        boundKey = key
        header.visibility = GONE
        row.visibility = VISIBLE
        thumb.setImageBitmap(bitmap)
        thumb.alpha = if (selected) 1f else 0.6f
        title.text = if (marked) "✓ $titleText" else titleText
        title.setTextColor(
            when {
                marked -> Palette.GREEN
                selected -> Palette.WHITE
                else -> Palette.GRAY_DIM
            },
        )
        meta.text = metaText
    }

    /** Bitmaps compare by identity, which is what a fresh thumbnail arriving should break. */
    private data class Bound(val title: String, val meta: String, val selected: Boolean, val marked: Boolean, val bitmap: Bitmap?)
}

/** Loads list thumbnails one at a time off the main thread, keeps the last [cacheSize] in memory,
 *  and calls [onLoaded] on the main thread when one arrives so the list can re-bind.
 *
 *  Holding Down through a long gallery asks for far more thumbnails than anyone looks at: a
 *  request whose row has since scrolled more than [windowRows] from [focusRow] is skipped when its
 *  turn comes, and asked for again if that row is shown again. */
class ThumbnailLoader(
    context: Context,
    private val sidePx: Int,
    private val cacheSize: Int = 80,
    private val windowRows: Int = 12,
    private val onLoaded: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val cache = LruCache<Long, Bitmap>(cacheSize)
    private val loading = HashSet<Long>()

    /** Which row each item is in, and which row is selected — read on the loader's thread. */
    @Volatile var rowOf: Map<Long, Int> = emptyMap()
    @Volatile var focusRow: Int = 0

    private var closed = false

    /** The cached thumbnail, or null after queueing a load for it. Main thread. */
    fun get(item: GalleryItem): Bitmap? = get(item.id) { GalleryStore.thumbnail(appContext, item, sidePx) }

    fun get(key: Long, load: () -> Bitmap?): Bitmap? {
        cache.get(key)?.let { return it }
        if (closed || !loading.add(key)) return null
        executor.execute {
            val row = rowOf[key]
            val wanted = row == null || abs(row - focusRow) <= windowRows
            val bitmap = if (wanted) load() else null
            main.post {
                loading.remove(key)
                if (closed || bitmap == null) return@post
                cache.put(key, bitmap)
                onLoaded()
            }
        }
        return null
    }

    fun peek(key: Long): Bitmap? = cache.get(key)

    fun close() {
        closed = true
        executor.shutdownNow()
        cache.evictAll()
    }
}
