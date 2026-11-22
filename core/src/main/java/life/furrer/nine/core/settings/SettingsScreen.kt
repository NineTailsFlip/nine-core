package life.furrer.nine.core.settings

/** The row DSL every Nine app's Settings screen is built from — a set of sections, each a list
 *  of rows that own their own render/adjust logic, so the row set can vary (by app, by surface,
 *  by whether a permission is granted) without any index constants naming a fixed position. */
class SettingsScreen internal constructor() {
    internal val sections = mutableListOf<SectionSpec>()

    fun section(title: String, build: SectionScope.() -> Unit) {
        val scope = SectionScopeImpl()
        scope.build()
        sections.add(SectionSpec(title, scope.rows))
    }
}

internal class SectionSpec(val title: String, val rows: List<RowSpec>)

/** [adjust] drives Left/Right (and OK, when [onSelect] is null); [onSelect], when set, makes OK
 *  invoke it directly instead, and Left/Right become inert — for a row that navigates rather than
 *  cycles a value in place. */
internal class RowSpec(
    val render: () -> String,
    val adjust: (direction: Int) -> Unit = {},
    val onSelect: (() -> Unit)? = null,
)

interface SectionScope {
    /** Boolean flip on Left/Right/OK. */
    fun toggle(label: String, get: () -> Boolean, set: (Boolean) -> Unit, on: String = "yes", off: String = "no")

    /** Wrap-around pick from a list; `nullLabel` adds a leading "unset" option (e.g. "ask each
     *  time"). A [get] value no longer present in [options] resolves to the first option, the
     *  same "fall back rather than get stuck" behavior a stale saved id already needs. */
    fun <T> choice(
        label: String,
        options: () -> List<T>,
        get: () -> T?,
        set: (T?) -> Unit,
        render: (T) -> String,
        nullLabel: String? = null,
        emptyLabel: String = "none available",
    )

    /** Clamped integer with a step. */
    fun intRange(
        label: String,
        min: Int,
        max: Int,
        step: Int = 1,
        get: () -> Int,
        set: (Int) -> Unit,
        render: (Int) -> String = Int::toString,
    )

    /** OK invokes; Left/Right do nothing. [value] is the trailing status text (e.g. "Notification
     *  access: granted"); an empty value renders the row as just its bare label. */
    fun action(label: String, value: () -> String = { "" }, onSelect: () -> Unit)
}

private class SectionScopeImpl : SectionScope {
    val rows = mutableListOf<RowSpec>()

    override fun toggle(label: String, get: () -> Boolean, set: (Boolean) -> Unit, on: String, off: String) {
        rows.add(
            RowSpec(
                render = { "$label: ${if (get()) on else off}" },
                adjust = { set(!get()) },
            ),
        )
    }

    override fun <T> choice(
        label: String,
        options: () -> List<T>,
        get: () -> T?,
        set: (T?) -> Unit,
        render: (T) -> String,
        nullLabel: String?,
        emptyLabel: String,
    ) {
        val hasNull = nullLabel != null

        // Position 0 is the null pseudo-option when there is one, otherwise the option list
        // starts at 0 directly — a `get()` that no longer matches any option (a stale saved id)
        // falls back to the first real option rather than to null, same as a plain unset value.
        fun position(): Int {
            val opts = options()
            if (opts.isEmpty()) return 0
            val current = get()
            if (current == null && hasNull) return 0
            val index = current?.let { opts.indexOf(it) } ?: -1
            val resolved = if (index >= 0) index else 0
            return if (hasNull) resolved + 1 else resolved
        }

        rows.add(
            RowSpec(
                render = {
                    val opts = options()
                    val text = when {
                        opts.isEmpty() -> emptyLabel
                        get() == null && hasNull -> nullLabel!!
                        else -> opts.getOrNull(position() - if (hasNull) 1 else 0)?.let(render) ?: emptyLabel
                    }
                    "$label: $text"
                },
                adjust = { direction ->
                    val opts = options()
                    if (opts.isNotEmpty()) {
                        val optionCount = opts.size + if (hasNull) 1 else 0
                        val next = (position() + direction + optionCount) % optionCount
                        if (hasNull && next == 0) set(null) else set(opts[next - if (hasNull) 1 else 0])
                    }
                },
            ),
        )
    }

    override fun intRange(
        label: String,
        min: Int,
        max: Int,
        step: Int,
        get: () -> Int,
        set: (Int) -> Unit,
        render: (Int) -> String,
    ) {
        rows.add(
            RowSpec(
                render = { "$label: ${render(get())}" },
                adjust = { direction -> set((get() + direction * step).coerceIn(min, max)) },
            ),
        )
    }

    override fun action(label: String, value: () -> String, onSelect: () -> Unit) {
        rows.add(
            RowSpec(
                render = { val v = value(); if (v.isEmpty()) label else "$label: $v" },
                onSelect = onSelect,
            ),
        )
    }
}
