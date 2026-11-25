package io.github.ninetailsflip.update

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.FileNotFoundException
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Updates from the app's GitHub releases.
 *
 *  Each app calls [init] once from Application.onCreate with its repository, after Nine.init
 *  (it also adopts the first-time setup's choices then: [SetupChoices]). From then on, while
 *  update checks are on (Settings → Updates; on by default), the first time a day that the app's
 *  main screen comes up, this fetches the latest release's update.json. When it's newer than
 *  what's installed — and the user hasn't skipped that version — [UpdateActivity] opens once,
 *  offering it. "Main screen" means an activity opened from the launcher or as the home screen,
 *  so a call, a reminder or a share sheet is never interrupted.
 *
 *  It uses the URL github.com/<repo>/releases/latest/download/update.json, which isn't counted
 *  against GitHub's API rate limit. */
object Updates {
    const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
    private const val TAG = "Updates"
    private const val PREFS = "nine_updates"
    private const val KEY_AUTO = "auto_check"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_LATEST = "latest"
    private const val KEY_SKIPPED = "skipped_version_code"
    private const val TIMEOUT_MS = 15_000

    sealed interface Result {
        data object UpToDate : Result
        data class Available(val info: UpdateInfo) : Result
        data class Failed(val message: String) : Result
    }

    lateinit var repo: String
        private set

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var appContext: Context
    private var checking = false
    private var promptedThisProcess = false
    private var resumed: WeakReference<Activity>? = null

    /** [repo] is "owner/name" on GitHub, e.g. "NineTailsFlip/g9". */
    fun init(app: Application, repo: String) {
        appContext = app.applicationContext
        this.repo = repo
        // The suite-wide look chosen in the phone's first-time setup, if there was one.
        SetupChoices.adopt(appContext)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = onResumed(activity)
            override fun onActivityPaused(activity: Activity) {
                if (resumed?.get() === activity) resumed = null
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    val releasesUrl: String get() = "https://github.com/$repo/releases"
    private val manifestUrl: String get() = "https://github.com/$repo/releases/latest/download/update.json"

    // ── Settings ────────────────────────────────────────────────────────────────────────────

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun autoCheck(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO, true)

    fun setAutoCheck(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO, on).apply()
    }

    fun skip(context: Context, versionCode: Long) {
        prefs(context).edit().putLong(KEY_SKIPPED, versionCode).apply()
    }

    fun installedVersionCode(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    fun installedVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    /** The last release seen, if it's newer than what's installed. */
    fun available(context: Context): UpdateInfo? {
        val json = prefs(context).getString(KEY_LATEST, null) ?: return null
        val info = runCatching { UpdateInfo.parse(json) }.getOrNull() ?: return null
        return info.takeIf { it.isNewerThan(installedVersionCode(context)) }
    }

    // ── Checking ────────────────────────────────────────────────────────────────────────────

    /** Fetches update.json now, whatever the schedule; [onDone] runs on the main thread. */
    fun check(onDone: (Result) -> Unit = {}) {
        if (checking) return
        checking = true
        executor.execute {
            val result = try {
                val info = UpdateInfo.parse(fetch(manifestUrl))
                prefs(appContext).edit().putString(KEY_LATEST, info.toJson()).apply()
                if (info.isNewerThan(installedVersionCode(appContext))) Result.Available(info) else Result.UpToDate
            } catch (_: FileNotFoundException) {
                Result.UpToDate // No release yet, or none with an update.json.
            } catch (e: Exception) {
                Log.i(TAG, "update check failed: $e")
                Result.Failed(e.message ?: e.javaClass.simpleName)
            }
            if (result !is Result.Failed) {
                prefs(appContext).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            }
            main.post {
                checking = false
                onDone(result)
            }
        }
    }

    private fun fetch(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "NineTailsFlip-updater")
            connection.setRequestProperty("Accept", "application/json, application/octet-stream")
            if (connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND) throw FileNotFoundException(url)
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    // ── Prompting ───────────────────────────────────────────────────────────────────────────

    private fun onResumed(activity: Activity) {
        resumed = WeakReference(activity)
        if (activity is UpdateActivity || !isMainScreen(activity.intent)) return
        if (autoCheck(activity) && isCheckDue(prefs(activity).getLong(KEY_LAST_CHECK, 0L), System.currentTimeMillis())) {
            check { result ->
                val current = resumed?.get()
                if (result is Result.Available && current != null && isMainScreen(current.intent)) prompt(current)
            }
        } else {
            prompt(activity)
        }
    }

    private fun prompt(activity: Activity) {
        if (promptedThisProcess || !autoCheck(activity)) return
        val info = available(activity) ?: return
        if (info.versionCode == prefs(activity).getLong(KEY_SKIPPED, -1L)) return
        promptedThisProcess = true
        activity.startActivity(UpdateActivity.intentFor(activity))
    }

    /** Opened from the launcher, or as the home screen (L9). */
    private fun isMainScreen(intent: Intent?): Boolean = intent != null &&
        intent.action == Intent.ACTION_MAIN &&
        (intent.hasCategory(Intent.CATEGORY_LAUNCHER) || intent.hasCategory(Intent.CATEGORY_HOME))
}
