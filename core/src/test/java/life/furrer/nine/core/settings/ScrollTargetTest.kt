package life.furrer.nine.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollTargetTest {

    @Test
    fun `the first row of a section scrolls to its heading`() {
        assertEquals(300, scrollTarget(rowTop = 340, headerTop = 300))
    }

    @Test
    fun `any other row scrolls to itself`() {
        assertEquals(500, scrollTarget(rowTop = 500, headerTop = null))
    }
}
