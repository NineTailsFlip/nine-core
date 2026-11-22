package life.furrer.nine.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure Kotlin, no Android dependency — the DSL itself doesn't touch a View. */
class SettingsScreenTest {

    private fun rows(build: SectionScope.() -> Unit): List<RowSpec> {
        val screen = SettingsScreen()
        screen.section("Test", build)
        return screen.sections.single().rows
    }

    @Test
    fun `toggle renders on-off label and flips on any direction`() {
        var value = false
        val row = rows { toggle("Flag", get = { value }, set = { value = it }) }.single()

        assertEquals("Flag: no", row.render())
        row.adjust(1)
        assertEquals(true, value)
        assertEquals("Flag: yes", row.render())
        row.adjust(-1)
        assertEquals(false, value)
    }

    @Test
    fun `toggle supports custom on-off labels`() {
        var value = true
        val row = rows { toggle("Font", get = { value }, set = { value = it }, on = "monospace", off = "proportional") }.single()
        assertEquals("Font: monospace", row.render())
    }

    @Test
    fun `intRange clamps at both ends and steps`() {
        var value = 4
        val row = rows { intRange("Slots", min = 1, max = 9, get = { value }, set = { value = it }) }.single()

        row.adjust(1)
        assertEquals(5, value)
        repeat(10) { row.adjust(1) }
        assertEquals(9, value)
        repeat(20) { row.adjust(-1) }
        assertEquals(1, value)
    }

    @Test
    fun `intRange honors a custom step and render`() {
        var value = 60
        val row = rows {
            intRange("Duration", min = 15, max = 480, step = 15, get = { value }, set = { value = it }, render = { "${it}m" })
        }.single()

        assertEquals("Duration: 60m", row.render())
        row.adjust(1)
        assertEquals(75, value)
    }

    @Test
    fun `choice wraps forward and back without a null option`() {
        var selected = "b"
        val row = rows {
            choice("Pick", options = { listOf("a", "b", "c") }, get = { selected }, set = { selected = it!! }, render = { it })
        }.single()

        assertEquals("Pick: b", row.render())
        row.adjust(1)
        assertEquals("c", selected)
        row.adjust(1)
        assertEquals("a", selected) // wraps forward past the last option
        row.adjust(-1)
        assertEquals("c", selected) // wraps back the other way
    }

    @Test
    fun `choice places the null option first when nullLabel is set`() {
        var selected: String? = null
        val row = rows {
            choice(
                "Account",
                options = { listOf("x", "y") },
                get = { selected },
                set = { selected = it },
                render = { it },
                nullLabel = "ask each time",
            )
        }.single()

        assertEquals("Account: ask each time", row.render())
        row.adjust(-1) // wraps back from null to the last real option
        assertEquals("y", selected)
        row.adjust(1)
        assertEquals(null, selected) // wraps forward past the last option back to null
    }

    @Test
    fun `choice falls back to the first option when the current value is no longer present`() {
        var selected = "stale"
        val row = rows {
            choice("Pick", options = { listOf("a", "b") }, get = { selected }, set = { selected = it!! }, render = { it })
        }.single()

        assertEquals("Pick: a", row.render())
        row.adjust(1)
        assertEquals("b", selected)
    }

    @Test
    fun `choice shows emptyLabel and does nothing on adjust when there are no options`() {
        var selected: String? = null
        var setCalls = 0
        val row = rows {
            choice(
                "Pick",
                options = { emptyList() },
                get = { selected },
                set = { selected = it; setCalls++ },
                render = { it },
                emptyLabel = "none available",
            )
        }.single()

        assertEquals("Pick: none available", row.render())
        row.adjust(1)
        assertEquals(0, setCalls)
    }

    @Test
    fun `action renders bare label when value is empty and the full label-value otherwise`() {
        val bare = rows { action("Intro", onSelect = {}) }.single()
        assertEquals("Intro", bare.render())

        val withValue = rows { action("Notification access", value = { "granted" }, onSelect = {}) }.single()
        assertEquals("Notification access: granted", withValue.render())
    }

    @Test
    fun `action invokes onSelect and is a no-op on adjust`() {
        var invoked = 0
        val row = rows { action("Do it", onSelect = { invoked++ }) }.single()

        row.adjust(1)
        row.adjust(-1)
        assertEquals(0, invoked)

        row.onSelect?.invoke()
        assertEquals(1, invoked)
    }
}
