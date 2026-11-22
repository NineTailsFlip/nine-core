package life.furrer.nine.core.manual

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import life.furrer.nine.core.Nine
import life.furrer.nine.core.ui.Dimens
import life.furrer.nine.core.ui.NineActivity
import life.furrer.nine.core.ui.Palette

private const val ROW_SIDE_PADDING = 28
private const val KEY_COLUMN_WIDTH = 170

/** Read-only scrollable text screen used for all three Manual pages (Intro, Key mapping, About)
 *  — the content differs per app/surface, the chrome (title + body + Back-to-close) doesn't, so
 *  one Activity covers all of them for every Nine app. Intro and Key mapping come from the
 *  running [Nine.app]'s [life.furrer.nine.core.NineSurface]; About is generated entirely from
 *  [Nine.app] plus [android.content.pm.PackageInfo], which is exactly why every app's About page
 *  matches — no app supplies its own About content. */
class ManualActivity : NineActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val page = intent.getStringExtra(EXTRA_PAGE) ?: PAGE_INTRO
        val surface = Nine.surface(intent.getStringExtra(EXTRA_SURFACE))
        val title = when (page) {
            PAGE_KEY_MAPPING -> "Key mapping"
            PAGE_ABOUT -> "About"
            else -> "Intro"
        }

        val titleView = TextView(this).apply {
            text = title
            textSize = Dimens.TITLE_SP
            setTextColor(Color.WHITE)
            setPadding(ROW_SIDE_PADDING, 30, ROW_SIDE_PADDING, 20)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(titleView)
            if (page == PAGE_KEY_MAPPING) {
                val keyColumnWidth = keyColumnWidth(surface.keyMap.map { it.keys })
                surface.keyMap.forEach { addView(keyMappingRow(it.keys, it.description, keyColumnWidth)) }
                addView(View(this@ManualActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 30)
                })
            } else {
                val body = if (page == PAGE_ABOUT) aboutText() else reflow(surface.introText)
                addView(
                    TextView(this@ManualActivity).apply {
                        text = body
                        textSize = Dimens.ROW_SP
                        setTextColor(Palette.GRAY_DIM)
                        setLineSpacing(8f, 1f)
                        setPadding(ROW_SIDE_PADDING, 0, ROW_SIDE_PADDING, 30)
                    },
                )
            }
        }
        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(container)
            },
        )
    }

    /** One row: a fixed-width key-combo column and a wrapping description column, aligned like a
     *  borderless table rather than the plain-paragraph style of the other two pages. */
    private fun keyMappingRow(key: String, description: String, keyColumnWidth: Int): LinearLayout {
        val keyView = keyTextView().apply { text = key }
        val descriptionView = TextView(this).apply {
            text = description
            textSize = Dimens.ROW_SP
            setTextColor(Palette.GRAY_DIM)
            setLineSpacing(4f, 1f)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(ROW_SIDE_PADDING, 10, ROW_SIDE_PADDING, 10)
            addView(keyView, LinearLayout.LayoutParams(keyColumnWidth, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(descriptionView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun keyTextView() = TextView(this).apply {
        textSize = Dimens.ROW_SP
        setTextColor(Color.WHITE)
    }

    /** Wide enough that no single word of any key label has to break mid-word ("Reor / der"),
     *  since [KEY_COLUMN_WIDTH] alone is too narrow at higher densities; whole words still wrap.
     *  Capped at half the screen so the descriptions keep room. */
    private fun keyColumnWidth(keys: List<String>): Int {
        val paint = keyTextView().paint
        val widestWord = keys.flatMap { it.split(Regex("\\s+")) }
            .maxOfOrNull { paint.measureText(it) }
            ?.let { kotlin.math.ceil(it).toInt() + 1 } ?: 0
        val cap = resources.displayMetrics.widthPixels / 2
        return widestWord.coerceAtLeast(KEY_COLUMN_WIDTH).coerceAtMost(cap)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun aboutText(): String {
        val app = Nine.app
        val info = packageManager.getPackageInfo(packageName, 0)
        return """
            ${app.name} — ${app.tagline}

            Version ${info.versionName} (build ${info.longVersionCode})

            Source: ${app.sourceUrl}
            Tickets: ${app.trackerUrl}

            Licensed under the GNU General Public License v3.0 or later. See the LICENSE file included with the source for the full text.
        """.trimIndent()
    }

    companion object {
        /** Joins each paragraph's source-wrapped lines into one, so the TextView wraps it to the
         *  screen instead of breaking at the fixed width the text was typed at — which re-wraps
         *  into ragged two-word lines on a narrow display. Blank lines still separate paragraphs. */
        fun reflow(text: String): String =
            text.split(Regex("\n[ \t]*\n")).joinToString("\n\n") { paragraph ->
                paragraph.lines().joinToString(" ") { it.trim() }.trim()
            }

        const val EXTRA_PAGE = "page"
        const val EXTRA_SURFACE = "surface"
        const val PAGE_INTRO = "intro"
        const val PAGE_KEY_MAPPING = "key_mapping"
        const val PAGE_ABOUT = "about"

        fun intentFor(context: Context, page: String, surfaceId: String = ""): Intent =
            Intent(context, ManualActivity::class.java)
                .putExtra(EXTRA_PAGE, page)
                .putExtra(EXTRA_SURFACE, surfaceId)
    }
}
