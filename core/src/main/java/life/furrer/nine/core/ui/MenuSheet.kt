package life.furrer.nine.core.ui

import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** A [enabled] = false row keeps its number (so the other rows don't shift) but is dimmed and
 *  can't be chosen: its number key and OK do nothing, and Up/Down skip over it. */
data class MenuItem(val label: String, val enabled: Boolean = true, val action: () -> Unit)

/** Black at ~82% opacity — leaves the content behind at roughly the ~18% visibility §9.6 calls
 *  for, without touching that content view's own alpha (and having to restore it on close). */
private const val SCRIM_COLOR = 0xD2000000.toInt()
private const val ROW_TEXT_SIZE_SP = 18f
private const val ROW_SIDE_PADDING_DP = Dimens.SIDE_PADDING_DP
// Matches Dimens.ROW_VERTICAL_PADDING_DP, the same per-row padding every other list in the suite
// uses — the menu previously used a much larger 10dp here, which read as oddly loose next to
// those other rows.
private const val ROW_VERTICAL_PADDING_DP = Dimens.ROW_VERTICAL_PADDING_DP
private const val HINT_TEXT_SIZE_SP = 11f
private const val QUESTION_TEXT_SIZE_SP = 15f
private const val QUESTION_TOP_PADDING_DP = 10

/** The numbered `*` menu overlay from §9.6. Hosting activities must be FrameLayout-rooted so
 *  this can sit on top as an extra child added once in onCreate, and must check
 *  [interceptKeyEvent] (from dispatchKeyEvent) or [handleKeyDown] (from onKeyDown) before their
 *  own key handling — the same "modal intercepts input first" shape as an AlertDialog, just
 *  drawn in-window instead of a separate window (needed so the screen behind stays visible,
 *  dimmed, rather than being covered opaquely). */
class MenuSheet(context: Context) : FrameLayout(context) {

    private var items: List<MenuItem> = emptyList()
    private var selectedIndex: Int = 0
    private val listContainer: LinearLayout
    private val hintText: TextView
    private val questionText: TextView

    var isShowing: Boolean = false
        private set

    /** The key whose ACTION_DOWN the menu handled, until its ACTION_UP arrives — see
     *  [interceptKeyEvent]. */
    private var consumedKeyUp: Int? = null

    /** The key whose ACTION_DOWN the menu handled last, so that key's auto-repeat DOWNs - which
     *  keep coming after a held OK or digit has already chosen a row and closed the sheet - are
     *  swallowed instead of reaching the host (a held digit typing into a search field behind
     *  it, a held OK opening the row behind it). Cleared by the next fresh DOWN. */
    private var repeatingKey: Int? = null

    init {
        setBackgroundColor(SCRIM_COLOR)
        visibility = View.GONE

        listContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        questionText = TextView(context).apply {
            textSize = QUESTION_TEXT_SIZE_SP
            setTextColor(Palette.GRAY_DIM)
            setPadding(dp(ROW_SIDE_PADDING_DP), dp(QUESTION_TOP_PADDING_DP), dp(ROW_SIDE_PADDING_DP), dp(ROW_VERTICAL_PADDING_DP))
            visibility = View.GONE
        }
        hintText = TextView(context).apply {
            text = "press a number · ↑↓ ok · ‹ close"
            textSize = HINT_TEXT_SIZE_SP
            setTextColor(Palette.GRAY_FAINT)
            setPadding(dp(ROW_SIDE_PADDING_DP), dp(ROW_VERTICAL_PADDING_DP), dp(ROW_SIDE_PADDING_DP), dp(ROW_VERTICAL_PADDING_DP))
        }
        val rule = View(context).apply { setBackgroundColor(Palette.DIVIDER) }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BLACK)
            addView(rule, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
            addView(questionText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(listContainer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(hintText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        addView(
            column,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )
    }

    /** Shows [items] numbered 1..9, then 0 for a 10th row, the keypad's own order; any row past
     *  that has no number and is reached with Up/Down. Per §9.6, callers should only pass items that apply right
     *  now (an empty selection means no Edit/Delete row) — the menu numbers what's shown, not a
     *  fixed slot list.
     *
     *  [question], when given, is shown unnumbered above the rows: what a confirmation asks, the
     *  suite's answer to an AlertDialog (see [confirm]). */
    fun show(items: List<MenuItem>, question: String? = null) {
        questionText.text = question.orEmpty()
        questionText.visibility = if (question.isNullOrEmpty()) View.GONE else View.VISIBLE
        this.items = items
        this.selectedIndex = items.indexOfFirst { it.enabled }.coerceAtLeast(0)
        renderItems()
        visibility = View.VISIBLE
        isShowing = true
    }

    private fun renderItems() {
        listContainer.removeAllViews()
        items.forEachIndexed { index, item ->
            val row = TextView(context).apply {
                text = "${numberLabel(index)} ${item.label}"
                textSize = ROW_TEXT_SIZE_SP
                setPadding(dp(ROW_SIDE_PADDING_DP), dp(ROW_VERTICAL_PADDING_DP), dp(ROW_SIDE_PADDING_DP), dp(ROW_VERTICAL_PADDING_DP))
                applyRowSelectionStyle(selected = index == selectedIndex && item.enabled)
                if (!item.enabled) setTextColor(Palette.GRAY_FAINT)
            }
            listContainer.addView(row)
        }
    }

    /** Asks before something that can't be undone, on the sheet rather than in a dialog — the way
     *  M9, P9 and C9 already asked: [question] above, then "1 [yes]" and "2 [no]". Back or [no]
     *  closes it with nothing done. */
    fun confirm(question: String, yes: String, no: String = "Cancel", onYes: () -> Unit) {
        show(listOf(MenuItem(yes, action = onYes), MenuItem(no) {}), question)
    }

    fun hide() {
        visibility = View.GONE
        isShowing = false
    }

    /** For hosts that route keys through `dispatchKeyEvent`: call first, and return true when this
     *  does. While shown it takes every event, acting on ACTION_DOWN via [handleKeyDown].
     *
     *  It also swallows the ACTION_UP of the key it handled last. Choosing a row via OK or a digit
     *  closes the sheet while handling that key's ACTION_DOWN, so by the time the matching
     *  ACTION_UP arrives [isShowing] is already false, and it would otherwise fall through into
     *  the host's own OK ACTION_UP handling (confirmed on-device: choosing "New contact" via OK
     *  also opened the previously-selected contact's detail screen). Tracked by key code rather
     *  than by [isShowing] so that trailing UP is swallowed whatever the menu's visibility became
     *  while handling the DOWN. Hosts that use onKeyDown never see that UP as a press, so they keep
     *  calling [handleKeyDown] directly. */
    // Not an override of View.dispatchKeyEvent: the sheet never holds focus, so the framework
    // never routes keys to it; its host hands them over explicitly.
    fun interceptKeyEvent(event: KeyEvent): Boolean {
        if (isShowing) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                handleKeyDown(event.keyCode, event)
                consumedKeyUp = event.keyCode
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == consumedKeyUp) {
            consumedKeyUp = null
            return true
        }
        return event.action == KeyEvent.ACTION_DOWN && handleKeyDown(event.keyCode, event)
    }

    /** @return true if the key was consumed — callers check this before their own onKeyDown
     *  handling while [isShowing]. Swallows everything while shown, even keys it doesn't
     *  otherwise act on, so nothing behind the scrim can react to a keypress meant for the menu.
     *  A number jumps straight to and invokes that row; Up/Down move the highlight and OK invokes
     *  whichever row is currently highlighted — both are equally valid ways to choose. */
    fun handleKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isShowing) {
            if (event.repeatCount > 0 && keyCode == repeatingKey) return true
            repeatingKey = null
            return false
        }
        repeatingKey = keyCode
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> hide()
            KeyEvent.KEYCODE_DPAD_UP -> move(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> move(1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                choose(items.getOrNull(selectedIndex))
            }
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> {
                val index = (event.number.digitToIntOrNull() ?: 0) - 1
                choose(items.getOrNull(index))
            }
            KeyEvent.KEYCODE_0 -> choose(items.getOrNull(9))
        }
        return true
    }

    private fun numberLabel(index: Int): String = when {
        index < 9 -> "${index + 1}"
        index == 9 -> "0"
        else -> " "
    }

    /** Moves the highlight one enabled row in [direction], wrapping. */
    private fun move(direction: Int) {
        if (items.none { it.enabled }) return
        do {
            selectedIndex = (selectedIndex + direction + items.size) % items.size
        } while (!items[selectedIndex].enabled)
        renderItems()
    }

    /** A disabled row is left alone (the menu stays open); anything else closes it and runs. */
    private fun choose(item: MenuItem?) {
        if (item != null && !item.enabled) return
        hide()
        item?.action?.invoke()
    }
}
