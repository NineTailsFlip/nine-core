package life.furrer.nine.core.ui

import android.content.Context
import android.util.SparseArray
import android.view.View
import android.view.ViewGroup

/** Supplies row content to a [RowListView]. [rowHeight] is in dp — per IMPLEMENTATION.md §5.2,
 *  every row is the same height except the selected row, which may be taller to fit a metadata
 *  line; this is not meant for arbitrary per-row heights. [createRowView] lets each screen use
 *  whatever row shape it needs (a plain TextView for a simple list, a composite bar+time+title
 *  row for calendar) — [RowListView] doesn't assume any particular view type or apply any
 *  selection styling itself, since that differs per screen (a single dim-gray/white TextView
 *  for contacts vs. several differently-styled sub-elements for a calendar row); [bind] receives
 *  [selected] so each binder can style itself. [isSelectable] lets a binder mark some rows (gap
 *  labels, a now-line, an empty-day placeholder) as unreachable by [RowListView.moveSelection]. */
interface RowBinder {
    fun rowCount(): Int
    fun createRowView(context: Context): View
    fun bind(view: View, index: Int, selected: Boolean)
    fun rowHeight(index: Int): Int
    fun isSelectable(index: Int): Boolean = true
}

/** A d-pad-driven list that recycles real View children instead of inflating one view per row —
 *  a LinearLayout-in-a-ScrollView (L9's approach, fine for its short lists) doesn't hold up for
 *  a 2,000-contact list. There's no touch/fling handling: scrolling only ever follows
 *  [moveSelection], which is driven by the d-pad, so a plain recycling window keyed off the
 *  current scroll offset is enough — no velocity tracking needed. */
class RowListView(context: Context) : ViewGroup(context) {

    private var binder: RowBinder? = null
    var selectedIndex: Int = 0
        private set

    private var scrollOffsetPx: Int = 0

    /** rowTopsPx[i] is the top of row i; rowTopsPx[count] is the total content height —
     *  recomputed whenever the data or the selection (which can change the selected row's
     *  height) changes. A plain prefix-sum recompute is cheap even at a few thousand rows and
     *  only runs on data/selection changes, never per frame. */
    private var rowTopsPx: IntArray = IntArray(1)

    private val recycledViews = ArrayDeque<View>()
    private val activeViews = SparseArray<View>()

    /** Rows kept bound beyond the visible range on each side, per §5.2's "visible + 2". */
    private val recycleMarginRows = 2

    /** Set once the view has a real size (from onLayout) — cached so moveSelection/
     *  notifyDataChanged can reposition rows immediately, synchronously, without waiting for the
     *  framework to schedule another layout traversal off the back of requestLayout(). */
    private var viewportWidthPx: Int = 0
    private var viewportHeightPx: Int = 0

    fun setBinder(binder: RowBinder) {
        this.binder = binder
        recycleAll()
        selectedIndex = nearestSelectableIndex(0)
        scrollOffsetPx = 0
        recomputeRowTops()
        refreshRows()
    }

    fun rowCount(): Int = binder?.rowCount() ?: 0

    /** Selects the first selectable row — e.g. after rolling forward into a new day/page, where
     *  landing near the top reads as "continuing on". */
    fun selectFirstSelectable() {
        selectedIndex = nearestSelectableIndex(0)
        recomputeRowTops()
        scrollToKeepSelectionVisible()
        refreshRows()
    }

    /** Selects the last selectable row — e.g. after rolling backward into the previous day/page,
     *  where landing near the bottom reads as "continuing on" in that direction. */
    fun selectLastSelectable() {
        selectedIndex = nearestSelectableIndex((rowCount() - 1).coerceAtLeast(0))
        recomputeRowTops()
        scrollToKeepSelectionVisible()
        refreshRows()
    }

    /** Call after the underlying data changes but the binder instance stays the same (e.g. a
     *  fresh provider query result). Clamps the selection into range rather than resetting it,
     *  so a background refresh doesn't visibly jump the user's place in the list. */
    fun notifyDataChanged() {
        val count = rowCount()
        selectedIndex = nearestSelectableIndex(selectedIndex.coerceIn(0, (count - 1).coerceAtLeast(0)))
        recomputeRowTops()
        scrollToKeepSelectionVisible()
        refreshRows()
    }

    /** Moves the selection to the nearest selectable row in [direction] (±1), skipping rows
     *  like gap labels or a now-line that aren't reachable by the d-pad. Does nothing if there's
     *  no selectable row further in that direction. */
    fun moveSelection(direction: Int) {
        val binder = this.binder ?: return
        val count = binder.rowCount()
        if (count == 0) return
        var index = selectedIndex
        var found = -1
        while (true) {
            index += direction
            if (index !in 0 until count) break
            if (binder.isSelectable(index)) {
                found = index
                break
            }
        }
        if (found == -1) return
        selectedIndex = found
        recomputeRowTops()
        scrollToKeepSelectionVisible()
        refreshRows()
    }

    /** Jumps directly to [index] (clamped to the nearest selectable row) — for movement that
     *  isn't a simple ±1 step, like contacts' Left/Right letter-group jump. Coerces out-of-range
     *  input rather than requiring the caller to bounds-check first. */
    fun setSelectedIndex(index: Int) {
        val count = rowCount()
        if (count == 0) return
        selectedIndex = nearestSelectableIndex(index.coerceIn(0, count - 1))
        recomputeRowTops()
        scrollToKeepSelectionVisible()
        refreshRows()
    }

    /** True if [moveSelection] can still move further in [direction] — lets a screen detect
     *  "at the edge" and roll into an adjacent day/page instead. */
    fun canMoveSelection(direction: Int): Boolean {
        val binder = this.binder ?: return false
        val count = binder.rowCount()
        var index = selectedIndex
        while (true) {
            index += direction
            if (index !in 0 until count) return false
            if (binder.isSelectable(index)) return true
        }
    }

    private fun nearestSelectableIndex(from: Int): Int {
        val binder = this.binder ?: return from
        val count = binder.rowCount()
        if (count == 0) return from
        val clamped = from.coerceIn(0, count - 1)
        if (binder.isSelectable(clamped)) return clamped
        for (i in clamped until count) if (binder.isSelectable(i)) return i
        for (i in clamped downTo 0) if (binder.isSelectable(i)) return i
        return clamped
    }

    private fun recomputeRowTops() {
        val binder = this.binder
        val count = binder?.rowCount() ?: 0
        val tops = IntArray(count + 1)
        for (i in 0 until count) {
            tops[i + 1] = tops[i] + dp(binder!!.rowHeight(i))
        }
        rowTopsPx = tops
    }

    private fun rowTop(index: Int): Int = rowTopsPx[index]

    private fun rowBottom(index: Int): Int = rowTopsPx[index + 1]

    private fun totalContentHeightPx(): Int = rowTopsPx[rowTopsPx.size - 1]

    /** Scrolls just enough to keep the selection on screen, with a one-row lookahead margin on
     *  the side it's moving toward, per §5.2/§7.5. */
    private fun scrollToKeepSelectionVisible() {
        val viewportHeight = viewportHeightPx
        if (viewportHeight == 0) return
        val count = rowCount()
        if (count == 0) return
        val aboveIndex = (selectedIndex - 1).coerceAtLeast(0)
        val belowIndex = (selectedIndex + 1).coerceAtMost(count - 1)
        val desiredTop = rowTop(aboveIndex)
        val desiredBottom = rowBottom(belowIndex)

        if (desiredBottom - scrollOffsetPx > viewportHeight) {
            scrollOffsetPx = desiredBottom - viewportHeight
        }
        if (desiredTop < scrollOffsetPx) {
            scrollOffsetPx = desiredTop
        }

        // The selection's own row wins over the lookahead margins whenever they conflict. Both
        // adjustments above are courtesies — showing a neighbour so there's somewhere visible to
        // move next — and with short rows they never fight. With a tall one they do: the
        // "show the row above" pass runs last and would push the selected row's *bottom* off
        // screen, taking whatever sits at the end of it with it.
        //
        // Found in M9, whose message rows run to six lines: a long message's timestamp and its
        // sent/delivered tick simply weren't there, and the row looked like it had lost them
        // rather than been scrolled past them. A row taller than the viewport still can't be
        // shown whole, and this deliberately favours its end — which is where a message row
        // keeps the information about the message.
        if (rowBottom(selectedIndex) - scrollOffsetPx > viewportHeight) {
            scrollOffsetPx = rowBottom(selectedIndex) - viewportHeight
        }

        val maxScroll = (totalContentHeightPx() - viewportHeight).coerceAtLeast(0)
        scrollOffsetPx = scrollOffsetPx.coerceIn(0, maxScroll)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        viewportWidthPx = r - l
        viewportHeightPx = b - t
        scrollToKeepSelectionVisible()
        refreshRows()
    }

    /** Recomputes which rows should be bound and positions them — called both from onLayout
     *  (real framework layout passes: initial layout, a window resize) and directly from
     *  moveSelection/notifyDataChanged/setBinder, so a selection change repaints immediately
     *  instead of waiting on a requestLayout()-triggered traversal that may not run for another
     *  frame. Positioning children outside of the ViewGroup's own top-down layout pass like this
     *  is safe as long as it invalidates afterward, which it does. */
    private fun refreshRows() {
        val binder = this.binder
        val count = binder?.rowCount() ?: 0
        if (binder == null || count == 0 || viewportHeightPx == 0) {
            recycleAll()
            return
        }

        var firstVisible = 0
        while (firstVisible < count - 1 && rowBottom(firstVisible) <= scrollOffsetPx) firstVisible++
        var lastVisible = firstVisible
        while (lastVisible < count - 1 && rowTop(lastVisible + 1) < scrollOffsetPx + viewportHeightPx) lastVisible++

        val windowStart = (firstVisible - recycleMarginRows).coerceAtLeast(0)
        val windowEnd = (lastVisible + recycleMarginRows).coerceAtMost(count - 1)

        val outOfWindow = mutableListOf<Int>()
        for (i in 0 until activeViews.size()) {
            val index = activeViews.keyAt(i)
            if (index < windowStart || index > windowEnd) outOfWindow.add(index)
        }
        outOfWindow.forEach { index ->
            val view = activeViews.get(index)
            activeViews.remove(index)
            removeView(view)
            recycledViews.addLast(view)
        }

        for (index in windowStart..windowEnd) {
            var view = activeViews.get(index)
            if (view == null) {
                view = recycledViews.removeLastOrNull() ?: binder.createRowView(context)
                addView(view)
                activeViews.put(index, view)
            }
            binder.bind(view, index, index == selectedIndex)
            val top = rowTop(index) - scrollOffsetPx
            val bottom = rowBottom(index) - scrollOffsetPx
            view.measure(
                MeasureSpec.makeMeasureSpec(viewportWidthPx, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(bottom - top, MeasureSpec.EXACTLY),
            )
            view.layout(0, top, viewportWidthPx, bottom)
        }
        invalidate()
    }

    private fun recycleAll() {
        for (i in 0 until activeViews.size()) {
            removeView(activeViews.valueAt(i))
        }
        activeViews.clear()
        recycledViews.clear()
    }
}
