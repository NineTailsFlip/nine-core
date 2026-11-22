package life.furrer.nine.core.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

private const val GRID_COLS = 5
private const val CELL_OUTER_DP = 34
private const val CELL_INNER_DP = 28
private const val NONE_CELL_COLOR = 0xFF2E2E2E.toInt()

private enum class PickerFocus { GRID, CUSTOM }

/** A grid-of-swatches color picker, same shape/conventions as [DatePickerActivity] (its own doc
 *  comment's "reused for any other date field" — this is that same idea for color): a fixed-size
 *  grid navigable by the d-pad, OK confirms the cursor's cell immediately (no separate "save"
 *  step), Back cancels. Replaces C9's event Color field's Left/Right stepping one-by-one through
 *  the calendar's provider-supplied colors, which was unfriendly for the simple reason that there
 *  was no way to see more than one option at a time.
 *
 *  Two independent selection modes, both optional and composable:
 *  - **Presets**: [EXTRA_COLORS] (required unless only custom entry is wanted) is the exhaustive,
 *    ordered list of pickable ARGB swatches — for C9 this is literally the CalDAV account's own
 *    `Colors` table via `EventStore.queryEventColors` (an opaque, provider-defined set: nothing
 *    else is legal to write back), for M9 a curated constant list. [EXTRA_LABELS], if given, must
 *    be the same length and supplies a friendly name per swatch (e.g. a CalDAV color's own key,
 *    or "Tomato") shown in the caption while that swatch has the cursor; without it the caption
 *    just shows the swatch's own hex.
 *  - **Custom RGB**: [EXTRA_ALLOW_CUSTOM] opens a second row below the grid for typing an exact
 *    R/G/B triplet — three digits each, typed as one continuous 9-digit run exactly like
 *    [DatePickerActivity]'s own `ddmmyyyy`, so this needs no on-screen keyboard/IME at all (and
 *    so no exposure to the on-device Autofill/key-dispatch bug `KNOWN_ISSUE_T9_IME.md` documents
 *    for every real `EditText` in this suite — a deliberate reason to model this as typed digits
 *    rather than a text field, not just a style choice). Not offered to C9's event color at all:
 *    an arbitrary RGB triple isn't a legal `EVENT_COLOR_KEY`, only one of the provider's own
 *    listed colors is.
 *
 *  Result is reported via three extras rather than a single "the color" int, because a caller
 *  needs to know *which kind* of pick happened to store it correctly — C9 must map a preset pick
 *  back to its opaque provider key (only [EXTRA_RESULT_INDEX] can do that; the argb alone isn't
 *  enough to recover which `EventColorOption` it came from), while M9 only ever cares about
 *  [EXTRA_RESULT_ARGB]:
 *  - [EXTRA_RESULT_IS_NONE] true: the "none" cell was chosen — every other result extra is unset.
 *  - [EXTRA_RESULT_IS_NONE] false, [EXTRA_RESULT_INDEX] >= 0: a preset was chosen, at that index
 *    into the [EXTRA_COLORS] the caller supplied; [EXTRA_RESULT_ARGB] is that same swatch's color.
 *  - [EXTRA_RESULT_IS_NONE] false, [EXTRA_RESULT_INDEX] == -1: the typed custom RGB was chosen;
 *    [EXTRA_RESULT_ARGB] is that color. */
class ColorPickerActivity : NineActivity() {

    private var colors: IntArray = IntArray(0)
    private var labels: Array<String>? = null
    private var allowNone: Boolean = true
    private var noneLabel: String = "none"
    private var allowCustom: Boolean = false

    private var cursorIndex: Int = 0
    private var pickerFocus: PickerFocus = PickerFocus.GRID
    private var typedDigits: String = ""

    private lateinit var captionView: TextView
    private val cellFrames = mutableListOf<FrameLayout>()
    private val cellSwatches = mutableListOf<TextView>()
    private lateinit var customRowView: TextView
    private lateinit var customPreview: View
    private lateinit var hintBar: HintBar
    private lateinit var scrollView: ScrollView
    private lateinit var gridContainer: LinearLayout
    private val rowContainers = mutableListOf<LinearLayout>()
    private var customRowContainer: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        colors = intent.getIntArrayExtra(EXTRA_COLORS) ?: IntArray(0)
        labels = intent.getStringArrayExtra(EXTRA_LABELS)
        allowNone = intent.getBooleanExtra(EXTRA_ALLOW_NONE, true)
        noneLabel = intent.getStringExtra(EXTRA_NONE_LABEL) ?: "none"
        allowCustom = intent.getBooleanExtra(EXTRA_ALLOW_CUSTOM, false)

        // A fixed header, not part of the scrolled content below — a real account's colors can
        // run to the hundreds (confirmed on-device: 147 for one test account), so the grid is
        // routinely taller than the screen; the caption (name/hex of whatever's under the
        // cursor) needs to stay on screen through all of that, not scroll away right when it's
        // most useful. DatePickerActivity's own title doesn't need this same treatment — its
        // grid is always a fixed 6 rows, never taller than the screen in practice.
        captionView = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(10), dp(16), dp(8))
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        gridContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val totalCells = totalCells()
        val rowCount = (totalCells + GRID_COLS - 1) / GRID_COLS
        repeat(rowCount.coerceAtLeast(1)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            repeat(GRID_COLS) {
                val swatch = TextView(this).apply {
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(Palette.GRAY_DIM)
                }
                cellSwatches.add(swatch)
                val frame = FrameLayout(this)
                frame.addView(swatch, FrameLayout.LayoutParams(dp(CELL_INNER_DP), dp(CELL_INNER_DP), Gravity.CENTER))
                cellFrames.add(frame)
                row.addView(frame, LinearLayout.LayoutParams(0, dp(CELL_OUTER_DP), 1f))
            }
            rowContainers.add(row)
            gridContainer.addView(row)
        }
        root.addView(gridContainer)

        val rule = View(this).apply { setBackgroundColor(Palette.DIVIDER) }
        root.addView(
            rule,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            },
        )

        if (allowCustom) {
            val customRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(4), dp(16), dp(4))
            }
            customRowView = TextView(this).apply { textSize = 16f }
            customRow.addView(customRowView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            customPreview = View(this)
            customRow.addView(customPreview, LinearLayout.LayoutParams(dp(20), dp(20)))
            root.addView(customRow)
            customRowContainer = customRow
        }

        hintBar = HintBar(this)
        scrollView = ScrollView(this).apply {
            addView(root)
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(captionView)
            addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(hintBar)
        }
        setContentView(outer)

        applyInitialSelection()
        render()
    }

    /** Exactly one of: land on a preset already selected ([EXTRA_INITIAL_INDEX]), land on the
     *  custom row pre-filled with a color that isn't one of the presets
     *  ([EXTRA_INITIAL_CUSTOM_ARGB]), or land on "none"/the first cell (neither extra given). */
    private fun applyInitialSelection() {
        val initialIndex = intent.getIntExtra(EXTRA_INITIAL_INDEX, -1)
        if (initialIndex >= 0) {
            cursorIndex = (if (allowNone) initialIndex + 1 else initialIndex).coerceIn(0, (totalCells() - 1).coerceAtLeast(0))
            pickerFocus = PickerFocus.GRID
        } else if (allowCustom && intent.hasExtra(EXTRA_INITIAL_CUSTOM_ARGB)) {
            val argb = intent.getIntExtra(EXTRA_INITIAL_CUSTOM_ARGB, 0)
            typedDigits = "%03d%03d%03d".format(Color.red(argb), Color.green(argb), Color.blue(argb))
            pickerFocus = PickerFocus.CUSTOM
        } else {
            cursorIndex = 0
            pickerFocus = PickerFocus.GRID
        }
    }

    private fun totalCells(): Int = (if (allowNone) 1 else 0) + colors.size

    private fun presetIndexAt(cellIndex: Int): Int = if (allowNone) cellIndex - 1 else cellIndex

    private fun render() {
        val total = totalCells()
        cellFrames.forEachIndexed { i, frame ->
            if (i >= total) {
                frame.visibility = View.INVISIBLE
                return@forEachIndexed
            }
            frame.visibility = View.VISIBLE
            frame.setBackgroundColor(if (pickerFocus == PickerFocus.GRID && i == cursorIndex) Color.WHITE else Color.TRANSPARENT)
            val swatch = cellSwatches[i]
            if (allowNone && i == 0) {
                swatch.text = "×"
                swatch.setTextColor(Palette.GRAY_DIM)
                swatch.setBackgroundColor(NONE_CELL_COLOR)
            } else {
                swatch.text = ""
                swatch.setBackgroundColor(Palette.pastelize(colors[presetIndexAt(i)]))
            }
        }

        captionView.text = captionFor(cursorIndex)

        if (allowCustom) {
            val padded = typedDigits.padEnd(9, '_')
            customRowView.text = "RGB: R:${padded.substring(0, 3)} G:${padded.substring(3, 6)} B:${padded.substring(6, 9)}"
            val selected = pickerFocus == PickerFocus.CUSTOM
            customRowView.setTextColor(if (selected) Color.WHITE else Palette.GRAY_DIM)
            val r = padded.substring(0, 3).toIntOrNull() ?: 0
            val g = padded.substring(3, 6).toIntOrNull() ?: 0
            val b = padded.substring(6, 9).toIntOrNull() ?: 0
            customPreview.setBackgroundColor(Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255)))
            hintBar.setText("type RRRGGGBBB · ↑↓←→ move · ok pick")
        } else {
            hintBar.setText("↑↓←→ move · ok pick")
        }

        scrollToFocus()
    }

    /** A real account's colors can run to the hundreds (confirmed on-device: 147 for one test
     *  account) — the grid is routinely taller than the screen, so the cursor moving past the
     *  visible viewport must scroll to follow it, the same "keep the selection on screen" job
     *  every other selectable list in the suite already does for its own selected row
     *  (`RowListView.scrollToKeepSelectionVisible`, `TaskEditActivity`/`TemplateEditActivity`'s
     *  own `scrollView.smoothScrollTo`). [gridContainer]/[customRowContainer] are both direct
     *  children of the scrolled content, so their own `top` is already the right coordinate
     *  space; a grid row is one level deeper, so its target is [gridContainer]'s own top plus the
     *  row's top within it. */
    private fun scrollToFocus() {
        val targetY = if (pickerFocus == PickerFocus.CUSTOM) {
            customRowContainer?.top
        } else {
            rowContainers.getOrNull(cursorIndex / GRID_COLS)?.let { row -> gridContainer.top + row.top }
        } ?: return
        scrollView.post { scrollView.smoothScrollTo(0, targetY) }
    }

    private fun captionFor(cellIndex: Int): String {
        if (allowNone && cellIndex == 0) return noneLabel
        val index = presetIndexAt(cellIndex)
        val color = colors.getOrNull(index) ?: return ""
        val hex = "#%06X".format(color and 0xFFFFFF)
        val label = labels?.getOrNull(index)
        return if (label != null) "$label   $hex" else hex
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (allowCustom && keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            if (typedDigits.length < 9) typedDigits += event.number
            pickerFocus = PickerFocus.CUSTOM
            render()
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                movePickerFocusUp()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                movePickerFocusDown()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (pickerFocus == PickerFocus.GRID) moveCursor(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (pickerFocus == PickerFocus.GRID) moveCursor(1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                confirm()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (typedDigits.isNotEmpty()) {
                    typedDigits = ""
                    render()
                } else {
                    setResult(RESULT_CANCELED)
                    finish()
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Clears [typedDigits] first: once the grid cursor is genuinely being navigated, whatever
     *  was typed (including a pre-fill from [EXTRA_INITIAL_CUSTOM_ARGB] — the current value,
     *  landed on automatically, not something this interaction actually typed) must stop
     *  shadowing the grid selection, or [confirm]'s "typed digits always win" rule (mirrored from
     *  [DatePickerActivity.confirm]) would silently re-pick that old value no matter which cell
     *  the user just navigated to. */
    private fun moveCursor(delta: Int) {
        typedDigits = ""
        val total = totalCells()
        if (total == 0) return
        cursorIndex = (cursorIndex + delta).coerceIn(0, total - 1)
        render()
    }

    /** Same "only drop to the row below once genuinely on the grid's last occupied row" shape as
     *  [DatePickerActivity.moveFocusDown] uses to reach its own Tomorrow/Next Monday rows. */
    private fun movePickerFocusUp() {
        when (pickerFocus) {
            PickerFocus.GRID -> moveCursor(-GRID_COLS)
            PickerFocus.CUSTOM -> {
                typedDigits = ""
                pickerFocus = PickerFocus.GRID
            }
        }
        render()
    }

    private fun movePickerFocusDown() {
        when (pickerFocus) {
            PickerFocus.GRID -> {
                val total = totalCells()
                val lastRowStart = ((total - 1).coerceAtLeast(0) / GRID_COLS) * GRID_COLS
                if (cursorIndex < lastRowStart) return moveCursor(GRID_COLS)
                // Already on the grid's last occupied row — only somewhere to go if there's a
                // custom row to drop into; otherwise this is simply the bottom of the grid.
                if (allowCustom) pickerFocus = PickerFocus.CUSTOM else return
            }
            PickerFocus.CUSTOM -> {}
        }
        render()
    }

    /** [typedDigits] wins over whatever [pickerFocus] happens to be, exactly like
     *  [DatePickerActivity.confirm] — same reasoning: once the user has started typing, that's
     *  clearly their intent, and a plain OK with an incomplete typed value is a no-op rather than
     *  silently falling back to whatever the grid cursor happens to be sitting on. */
    private fun confirm() {
        if (typedDigits.isNotEmpty()) {
            parseCustomRgb()?.let { finishWithCustom(it) }
            return
        }
        when (pickerFocus) {
            PickerFocus.GRID -> {
                if (allowNone && cursorIndex == 0) finishWithNone() else finishWithPreset(presetIndexAt(cursorIndex))
            }
            PickerFocus.CUSTOM -> {}
        }
    }

    private fun parseCustomRgb(): Int? {
        if (typedDigits.length < 9) return null
        val r = typedDigits.substring(0, 3).toInt().coerceIn(0, 255)
        val g = typedDigits.substring(3, 6).toInt().coerceIn(0, 255)
        val b = typedDigits.substring(6, 9).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun finishWithNone() {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_IS_NONE, true))
        finish()
    }

    private fun finishWithPreset(index: Int) {
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(EXTRA_RESULT_IS_NONE, false)
                .putExtra(EXTRA_RESULT_INDEX, index)
                .putExtra(EXTRA_RESULT_ARGB, colors[index]),
        )
        finish()
    }

    private fun finishWithCustom(argb: Int) {
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(EXTRA_RESULT_IS_NONE, false)
                .putExtra(EXTRA_RESULT_INDEX, -1)
                .putExtra(EXTRA_RESULT_ARGB, argb),
        )
        finish()
    }

    companion object {
        /** Required unless the caller only wants [EXTRA_ALLOW_CUSTOM] with no presets at all. */
        const val EXTRA_COLORS = "colors"

        /** Optional, must be the same length as [EXTRA_COLORS] if given. */
        const val EXTRA_LABELS = "labels"
        const val EXTRA_ALLOW_NONE = "allow_none"
        const val EXTRA_NONE_LABEL = "none_label"
        const val EXTRA_ALLOW_CUSTOM = "allow_custom"

        /** Index into the caller's own [EXTRA_COLORS] that's currently selected, if any. */
        const val EXTRA_INITIAL_INDEX = "initial_index"

        /** The currently-selected color when it's a custom one, not one of [EXTRA_COLORS]. */
        const val EXTRA_INITIAL_CUSTOM_ARGB = "initial_custom_argb"

        const val EXTRA_RESULT_IS_NONE = "result_is_none"
        const val EXTRA_RESULT_INDEX = "result_index"
        const val EXTRA_RESULT_ARGB = "result_argb"
    }
}
