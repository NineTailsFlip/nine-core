package life.furrer.nine.core.ui

import android.content.Context
import android.view.View
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

private const val ROW_HEIGHT_DP = 32

@RunWith(RobolectricTestRunner::class)
class RowListViewTest {

    private val context = RuntimeEnvironment.getApplication()

    private class FakeBinder(
        private val count: Int,
        private val unselectable: Set<Int> = emptySet(),
    ) : RowBinder {
        val boundSelections = mutableMapOf<Int, Boolean>()
        override fun rowCount(): Int = count
        override fun createRowView(context: Context): View = TextView(context)
        override fun bind(view: View, index: Int, selected: Boolean) {
            boundSelections[index] = selected
            (view as TextView).text = "row $index"
            view.applyRowSelectionStyle(selected)
        }
        override fun rowHeight(index: Int): Int = ROW_HEIGHT_DP
        override fun isSelectable(index: Int): Boolean = index !in unselectable
    }

    /** Lays the view out at a size showing exactly [visibleRows] rows — the initial framework
     *  layout pass that establishes the viewport size moveSelection/notifyDataChanged then rely
     *  on for their own synchronous repositioning. */
    private fun layoutShowing(view: RowListView, visibleRows: Int) {
        val heightPx = context.dp(ROW_HEIGHT_DP) * visibleRows
        view.measure(
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 300, heightPx)
    }

    @Test
    fun `binds only rows within the visible window plus the recycle margin`() {
        val binder = FakeBinder(count = 100)
        val view = RowListView(context)
        view.setBinder(binder)
        layoutShowing(view, visibleRows = 5)

        // Visible rows 0-4, plus a margin of 2 on each side (clamped at 0) -> 0-6.
        assertEquals(7, view.childCount)
        assertEquals("row 0", (view.getChildAt(0) as TextView).text)
        assertEquals("row 6", (view.getChildAt(view.childCount - 1) as TextView).text)
    }

    @Test
    fun `moveSelection clamps at the first row`() {
        val view = RowListView(context)
        view.setBinder(FakeBinder(count = 5))
        layoutShowing(view, visibleRows = 5)

        view.moveSelection(-1)
        assertEquals(0, view.selectedIndex)
    }

    @Test
    fun `moveSelection clamps at the last row`() {
        val view = RowListView(context)
        view.setBinder(FakeBinder(count = 5))
        layoutShowing(view, visibleRows = 5)

        repeat(10) { view.moveSelection(1) }
        assertEquals(4, view.selectedIndex)
    }

    @Test
    fun `moveSelection with no rows does not crash`() {
        val view = RowListView(context)
        view.setBinder(FakeBinder(count = 0))
        layoutShowing(view, visibleRows = 5)

        view.moveSelection(1)
        assertEquals(0, view.selectedIndex)
        assertEquals(0, view.childCount)
    }

    @Test
    fun `moveSelection skips non-selectable rows`() {
        val view = RowListView(context)
        // Row 1 is a gap label, row 3 is a now-line — neither reachable by the d-pad.
        view.setBinder(FakeBinder(count = 5, unselectable = setOf(1, 3)))
        layoutShowing(view, visibleRows = 5)

        view.moveSelection(1)
        assertEquals(2, view.selectedIndex)
        view.moveSelection(1)
        assertEquals(4, view.selectedIndex)
    }

    @Test
    fun `setSelectedIndex jumps directly to the given row`() {
        val view = RowListView(context)
        view.setBinder(FakeBinder(count = 10))
        layoutShowing(view, visibleRows = 5)

        view.setSelectedIndex(7)

        assertEquals(7, view.selectedIndex)
    }

    @Test
    fun `setSelectedIndex clamps to the nearest selectable row`() {
        val view = RowListView(context)
        // Row 3 (a section header) is not itself selectable.
        view.setBinder(FakeBinder(count = 10, unselectable = setOf(3)))
        layoutShowing(view, visibleRows = 5)

        view.setSelectedIndex(3)

        assertEquals(4, view.selectedIndex)
    }

    @Test
    fun `setSelectedIndex coerces out-of-range input instead of crashing`() {
        val view = RowListView(context)
        view.setBinder(FakeBinder(count = 5))
        layoutShowing(view, visibleRows = 5)

        view.setSelectedIndex(999)
        assertEquals(4, view.selectedIndex)

        view.setSelectedIndex(-5)
        assertEquals(0, view.selectedIndex)
    }

    @Test
    fun `canMoveSelection is false when only non-selectable rows remain in that direction`() {
        val view = RowListView(context)
        view.setBinder(FakeBinder(count = 3, unselectable = setOf(2)))
        layoutShowing(view, visibleRows = 3)

        assertTrue(view.canMoveSelection(1))
        view.moveSelection(1)
        assertEquals(1, view.selectedIndex)
        assertFalse(view.canMoveSelection(1))
    }

    @Test
    fun `setBinder lands the initial selection on the first selectable row`() {
        val view = RowListView(context)
        view.setBinder(FakeBinder(count = 5, unselectable = setOf(0, 1)))
        layoutShowing(view, visibleRows = 5)

        assertEquals(2, view.selectedIndex)
    }

    @Test
    fun `scrolling the selection past the viewport recycles rows that fell out of view`() {
        val binder = FakeBinder(count = 100)
        val view = RowListView(context)
        view.setBinder(binder)
        layoutShowing(view, visibleRows = 5)

        repeat(50) { view.moveSelection(1) }
        assertEquals(50, view.selectedIndex)

        val boundTexts = (0 until view.childCount).map { (view.getChildAt(it) as TextView).text }
        assertTrue("row 0" !in boundTexts)
        assertTrue("row 50" in boundTexts)
    }

    @Test
    fun `notifyDataChanged clamps a selection that is now out of range`() {
        val binder = FakeBinder(count = 10)
        val view = RowListView(context)
        view.setBinder(binder)
        layoutShowing(view, visibleRows = 5)
        repeat(9) { view.moveSelection(1) }
        assertEquals(9, view.selectedIndex)

        view.setBinder(FakeBinder(count = 3))
        assertEquals(0, view.selectedIndex)
    }

    @Test
    fun `bind receives selected true only for the selected row`() {
        val binder = FakeBinder(count = 5)
        val view = RowListView(context)
        view.setBinder(binder)
        layoutShowing(view, visibleRows = 5)

        assertEquals(true, binder.boundSelections[0])
        assertEquals(false, binder.boundSelections[1])
    }

    @Test
    fun `the selected row is styled white and underlined, idle rows are dim`() {
        val view = RowListView(context)
        view.setBinder(FakeBinder(count = 5))
        layoutShowing(view, visibleRows = 5)

        val selectedRow = view.getChildAt(0) as TextView
        assertEquals(android.graphics.Color.WHITE, selectedRow.currentTextColor)
        assertTrue(selectedRow.paintFlags and android.graphics.Paint.UNDERLINE_TEXT_FLAG != 0)

        val idleRow = view.getChildAt(1) as TextView
        assertEquals(0xFF8A8A8A.toInt(), idleRow.currentTextColor)
        assertTrue(idleRow.paintFlags and android.graphics.Paint.UNDERLINE_TEXT_FLAG == 0)
    }
}
