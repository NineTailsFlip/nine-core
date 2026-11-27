package life.furrer.nine.core.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import life.furrer.nine.core.Nine
import life.furrer.nine.core.NineSurface
import life.furrer.nine.core.manual.ManualActivity
import life.furrer.nine.core.prefs.BackupFlow
import life.furrer.nine.core.prefs.NinePrefs
import life.furrer.nine.core.ui.Dimens
import life.furrer.nine.core.ui.MenuSheet
import life.furrer.nine.core.ui.NineActivity
import life.furrer.nine.core.ui.TextRole
import life.furrer.nine.core.ui.applyRole
import life.furrer.nine.core.ui.applyRowSelectionStyle
import life.furrer.nine.core.ui.dp
import life.furrer.nine.core.ui.recreateIfFontChanged
import life.furrer.nine.core.util.NumberFormat
import life.furrer.nine.core.util.PhoneFormat

/** Renders a [SettingsScreen], the same row shape every Nine app's Settings uses: app-specific
 *  sections first (from [buildAppSections]), then Theme, Backup and About, always, supplied by
 *  core. Apps override [buildAppSections] (required) and [buildThemeExtras] (optional) — every-
 *  thing else (row construction, Up/Down selection with wrap, Left/Right/OK dispatch to the
 *  focused row, scroll-into-view, the theme, the font row's live `recreate()`, export/import's
 *  SAF flow and result handling) lives here once. */
abstract class NineSettingsActivity : NineActivity() {

    /** The app-specific sections. Core appends Theme, Backup and About after them. */
    protected abstract fun buildAppSections(screen: SettingsScreen, surface: NineSurface)

    /** Optional extra rows inside the shared Theme section (e.g. L9's calendar divider, black
     *  wallpapers). Default: none. */
    protected open fun buildThemeExtras(scope: SectionScope) {}

    /** The "Number format" row, for an app that shows phone numbers (P9, C9, M9) to place in its
     *  own section. Stored per app like every other setting; see [NinePrefs.numberFormat]. */
    protected fun numberFormatRow(scope: SectionScope) {
        scope.choice(
            label = "Number format",
            options = { NumberFormat.entries },
            get = { NinePrefs.numberFormat(this) },
            set = { it?.let { format -> NinePrefs.setNumberFormat(this, format) } },
            // The home country is shown because it's the one thing "by country" has to assume:
            // which country a number without a country code (079 …) belongs to.
            render = { format ->
                val home = PhoneFormat.homeCountry(this).takeIf { format == NumberFormat.BY_COUNTRY }
                if (home != null) "${format.label} (home: $home)" else format.label
            },
        )
    }

    private lateinit var scrollView: ScrollView

    /** For [confirm]: Settings asks on the same numbered sheet as every other screen. */
    private lateinit var menuSheet: MenuSheet
    private val rowViews = mutableListOf<TextView>()
    private val rowSpecs = mutableListOf<RowSpec>()
    // The heading of the section whose first row is at this index, so selecting that row can
    // scroll the heading into view with it instead of tucking it above the viewport.
    private val sectionHeaders = mutableMapOf<Int, View>()
    private var selectedIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val surface = Nine.surface(intent.getStringExtra(EXTRA_SURFACE))
        val screen = SettingsScreen()
        buildAppSections(screen, surface)
        screen.section("Theme") { buildThemeSection(this) }
        screen.section("Backup") { buildBackupSection(this) }
        screen.section("About") { buildAboutSection(this, surface) }

        val rowContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollView = ScrollView(this).apply {
            addView(rowContainer)
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        menuSheet = MenuSheet(this)
        setContentView(
            FrameLayout(this).apply {
                addView(scrollView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(menuSheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            },
        )

        // A section with no rows (e.g. Backup, before a later phase gives every app one) renders
        // nothing at all, header included, rather than an empty heading with nothing under it.
        val nonEmptySections = screen.sections.filter { it.rows.isNotEmpty() }
        nonEmptySections.forEachIndexed { sectionIndex, section ->
            val header = addSectionHeader(rowContainer, section.title, isFirst = sectionIndex == 0)
            sectionHeaders[rowViews.size] = header
            section.rows.forEach { spec ->
                val view = makeRowView()
                rowViews.add(view)
                rowSpecs.add(spec)
                rowContainer.addView(view)
            }
        }
        render()
    }

    private fun buildThemeSection(scope: SectionScope) {
        scope.toggle(
            label = "Font",
            get = { NinePrefs.useMonospaceFont(this) },
            set = { value ->
                NinePrefs.setUseMonospaceFont(this, value)
                // The theme's default TextView style only applies to views created after it's
                // set, so this screen needs to be rebuilt for the change to show immediately.
                recreate()
            },
            on = "monospace",
            off = "proportional",
        )
        if (Nine.app.hasHintBar) {
            scope.toggle(
                label = "Hint bar",
                get = { NinePrefs.showHintBar(this) },
                set = { value -> NinePrefs.setShowHintBar(this, value) },
            )
        }
        buildThemeExtras(scope)
    }

    private fun buildBackupSection(scope: SectionScope) {
        scope.action("Export settings") { BackupFlow.export(this) }
        scope.action("Import settings") {
            confirm("Replace all current ${Nine.app.name} settings with the contents of a file?", "Choose file") {
                BackupFlow.chooseImportFile(this)
            }
        }
    }

    private fun buildAboutSection(scope: SectionScope, surface: NineSurface) {
        scope.action("Intro") {
            startActivity(ManualActivity.intentFor(this, ManualActivity.PAGE_INTRO, surface.id))
        }
        scope.action("Key mapping") {
            startActivity(ManualActivity.intentFor(this, ManualActivity.PAGE_KEY_MAPPING, surface.id))
        }
        scope.action("About") {
            startActivity(ManualActivity.intentFor(this, ManualActivity.PAGE_ABOUT, surface.id))
        }
        scope.action("Open website") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Nine.app.websiteUrl)))
        }
    }

    private fun addSectionHeader(container: LinearLayout, title: String, isFirst: Boolean): View {
        val header = TextView(this).apply {
            text = title.uppercase()
            applyRole(TextRole.SECTION)
            setPadding(
                dp(Dimens.SIDE_PADDING_DP),
                dp(if (isFirst) Dimens.SECTION_FIRST_TOP_DP else Dimens.SECTION_TOP_DP),
                dp(Dimens.SIDE_PADDING_DP),
                dp(4),
            )
        }
        container.addView(header)
        return header
    }

    private fun makeRowView(): TextView = TextView(this).apply {
        applyRole(TextRole.ROW)
        setPadding(
            dp(Dimens.SIDE_PADDING_DP),
            dp(Dimens.ROW_VERTICAL_PADDING_DP),
            dp(Dimens.SIDE_PADDING_DP),
            dp(Dimens.ROW_VERTICAL_PADDING_DP),
        )
    }

    /** Re-renders every row's text and selection style from current state. [onKeyDown] already
     *  calls this after every dispatch; a subclass needs it too whenever state changes outside a
     *  keypress — an answer TDLib or a server sends back later, or [onActivityResult] after a
     *  picker returns, neither of which retriggers [onResume]. */
    protected fun refresh() {
        render()
    }

    /** Asks [question] on the numbered sheet ([MenuSheet.confirm]) — the suite's way of asking,
     *  in place of an AlertDialog — and runs [onYes] if the answer is [yes]. The rows re-render
     *  either way. */
    protected fun confirm(question: String, yes: String, no: String = "Cancel", onYes: () -> Unit) {
        menuSheet.confirm(question, yes, no) {
            onYes()
            render()
        }
    }

    private fun render() {
        rowSpecs.forEachIndexed { index, spec ->
            val view = rowViews[index]
            view.text = spec.render()
            view.applyRowSelectionStyle(index == selectedIndex)
        }
        rowViews.getOrNull(selectedIndex)?.let { row ->
            scrollView.post { scrollView.smoothScrollTo(0, scrollTarget(row.top, sectionHeaders[selectedIndex]?.top)) }
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        BackupFlow.handleActivityResult(this, requestCode, resultCode, data, onImported = ::onImported)
    }

    /** An import can flip the font too, which, like the Font row, only shows on a rebuilt screen;
     *  anything else just needs the rows re-rendered. */
    private fun onImported() {
        if (!recreateIfFontChanged(appliedMonospaceFont)) refresh()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (menuSheet.handleKeyDown(keyCode, event)) return true
        if (rowSpecs.isEmpty()) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                finish()
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                selectedIndex = (selectedIndex - 1 + rowSpecs.size) % rowSpecs.size
                render()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                selectedIndex = (selectedIndex + 1) % rowSpecs.size
                render()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                rowSpecs[selectedIndex].adjust(-1)
                render()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                rowSpecs[selectedIndex].adjust(1)
                render()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val spec = rowSpecs[selectedIndex]
                val onSelect = spec.onSelect
                if (onSelect != null) onSelect() else spec.adjust(1)
                render()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                finish()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val EXTRA_SURFACE = "surface"

        fun intentFor(context: Context, activityClass: Class<*>, surfaceId: String = ""): Intent =
            Intent(context, activityClass).putExtra(EXTRA_SURFACE, surfaceId)
    }
}

/** Opens the running app's own Settings ([life.furrer.nine.core.NineApp.settingsActivity]) for
 *  [surfaceId] — what hold-`*` and every menu's "Settings" item do, from app screens and the
 *  shared ones in `:contacts` alike. */
fun Activity.openNineSettings(surfaceId: String = "") {
    startActivity(NineSettingsActivity.intentFor(this, Nine.app.settingsActivity, surfaceId))
}

/** Where to scroll to show a selected row: its section heading top when it is the first row
 *  of that section (headerTop non-null), so the heading is not hidden above it, else the row top. */
internal fun scrollTarget(rowTop: Int, headerTop: Int?): Int = headerTop ?: rowTop
