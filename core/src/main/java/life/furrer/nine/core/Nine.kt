package life.furrer.nine.core

/** One launcher entry / user-facing surface. L9 has one; C9 has two (Calendar and Contacts, see
 *  c9's activity-aliases); P9 and M9 are expected to have one each. Anything that differs per
 *  launcher entry — the key map, the intro text, which Settings sections apply — hangs off a
 *  surface, not off the app. */
data class NineSurface(
    val id: String,
    val name: String,
    val introText: String,
    val keyMap: List<KeyHelp>,
)

data class KeyHelp(val keys: String, val description: String)

data class NineApp(
    /** "l9" / "c9". Doubles as the SharedPreferences file name (both apps already use exactly
     *  this) and as the backup file's `app` guard field. */
    val id: String,
    val name: String,
    val tagline: String,
    val surfaces: List<NineSurface>,
    /** Whether this app draws a hint bar, and therefore whether Settings offers the "Hint bar"
     *  row. */
    val hasHintBar: Boolean = true,
    val websiteUrl: String = "https://nine.furrer.life",
    val sourceUrl: String = "https://git.sr.ht/~timofurrer/nine",
    val trackerUrl: String = "https://todo.sr.ht/~timofurrer/nine",
    /** The app's own SettingsActivity, so a shared screen (:contacts' ContactsActivity, P9's own
     *  call screens) can offer "Settings" in its own `*` menu without knowing the host app. */
    val settingsActivity: Class<*>,
    /** Which surface id a shared contacts screen should open Settings with — "contacts" for C9,
     *  "" for P9 (a single-surface app, like L9). */
    val contactsSurfaceId: String = "",
)

/** Set once, from each app's Application.onCreate. Every core object reads it instead of taking
 *  the descriptor as a parameter — the same "object + Context" style the rest of this codebase
 *  already uses (AppRepository, Prefs, CalendarStore, ...). */
object Nine {
    lateinit var app: NineApp
        private set

    fun init(app: NineApp) {
        this.app = app
    }

    fun surface(id: String?): NineSurface =
        app.surfaces.firstOrNull { it.id == id } ?: app.surfaces.first()
}
