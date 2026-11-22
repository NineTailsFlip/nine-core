package io.github.ninetailsflip.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import life.furrer.nine.core.ui.Dimens
import life.furrer.nine.core.ui.HintBar
import life.furrer.nine.core.ui.MenuItem
import life.furrer.nine.core.ui.MenuSheet
import life.furrer.nine.core.ui.NineActivity
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.dp

/** "<App> 0.2.0 is out": what's new, what it costs to download, and OK to update.
 *
 *  OK downloads the APK for this phone's ABI from the GitHub release, checks its SHA-256 against
 *  update.json, and hands it to Android's package installer, which asks the user to confirm.
 *  The first time, Android also wants this app allowed to install apps ("Install unknown
 *  apps"); this screen explains that and opens the right settings page. */
class UpdateActivity : NineActivity() {

    private enum class State { OFFER, NEEDS_PERMISSION, DOWNLOADING, INSTALLING, FAILED }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private var info: UpdateInfo? = null
    private var apk: UpdateApk? = null
    private var state = State.OFFER
    @Volatile private var cancelled = false

    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var scroll: ScrollView
    private lateinit var body: TextView
    private lateinit var status: TextView
    private lateinit var hintBar: HintBar
    private lateinit var menuSheet: MenuSheet

    private val appName: String get() = applicationInfo.loadLabel(packageManager).toString()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = TextView(this).apply {
            textSize = 20f
            setTextColor(Palette.WHITE)
            setPadding(dp(Dimens.SIDE_PADDING_DP), dp(12), dp(Dimens.SIDE_PADDING_DP), 0)
        }
        subtitle = TextView(this).apply {
            textSize = 12f
            setTextColor(Palette.GRAY_MUTED)
            setPadding(dp(Dimens.SIDE_PADDING_DP), dp(2), dp(Dimens.SIDE_PADDING_DP), dp(10))
        }
        body = TextView(this).apply {
            textSize = 14f
            setTextColor(Palette.GRAY_DIM)
            setPadding(dp(Dimens.SIDE_PADDING_DP), 0, dp(Dimens.SIDE_PADDING_DP), dp(12))
        }
        scroll = ScrollView(this).apply {
            isFocusable = false
            addView(body)
        }
        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Palette.WHITE)
            setPadding(dp(Dimens.SIDE_PADDING_DP), dp(8), dp(Dimens.SIDE_PADDING_DP), dp(8))
        }
        hintBar = HintBar(this)
        menuSheet = MenuSheet(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(subtitle)
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(status)
            addView(hintBar)
        }
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Palette.BLACK)
                addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(menuSheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            },
        )
        load()
        handleInstallStatus(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInstallStatus(intent)
    }

    override fun onResume() {
        super.onResume()
        // Back from "Install unknown apps": carry on if it was allowed.
        if (state == State.NEEDS_PERMISSION && packageManager.canRequestPackageInstalls()) download()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelled = true
        worker.shutdownNow()
    }

    private fun load() {
        val available = Updates.available(this)
        info = available
        apk = available?.apkFor(Build.SUPPORTED_ABIS.toList())
        if (available == null) {
            title.text = appName
            subtitle.text = "version ${Updates.installedVersionName(this)}"
            body.text = "This is the latest version."
            show(State.FAILED, null)
            hintBar.setText("‹ back")
            return
        }
        title.text = "$appName ${available.versionName}"
        subtitle.text = listOfNotNull(
            "you have ${Updates.installedVersionName(this)}",
            apk?.size?.takeIf { it > 0 }?.let { sizeText(it) },
        ).joinToString(" · ")
        body.text = plainNotes(available.notes).ifEmpty { "No notes for this version." }
        show(State.OFFER, null)
    }

    private fun show(newState: State, statusText: String?) {
        state = newState
        status.text = statusText.orEmpty()
        status.visibility = if (statusText.isNullOrEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        hintBar.setText(
            when (newState) {
                State.OFFER -> "ok update · ↑↓ scroll · * menu · ‹ later"
                State.NEEDS_PERMISSION -> "ok open settings · ‹ later"
                State.DOWNLOADING -> "‹ cancel"
                State.INSTALLING -> "confirm on the next screen"
                State.FAILED -> if (info != null) "ok try again · ‹ back" else "‹ back"
            },
        )
    }

    // ── Updating ────────────────────────────────────────────────────────────────────────────

    private fun start() {
        if (apk == null) return
        if (!packageManager.canRequestPackageInstalls()) {
            show(
                State.NEEDS_PERMISSION,
                "Android needs your OK first. On the next screen, turn on \"Allow from this source\" for $appName, then press Back.",
            )
            return
        }
        download()
    }

    private fun openInstallSettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
    }

    private fun download() {
        val target = apk ?: return
        val version = info?.versionName ?: return
        cancelled = false
        show(State.DOWNLOADING, "Downloading…")
        val dir = File(cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "update-$version.apk")
        worker.execute {
            val error = try {
                downloadTo(target.url, file, target.size)
                if (cancelled) "cancelled" else if (sha256Hex(file) != target.sha256) "the download is damaged (checksum mismatch)" else null
            } catch (e: Exception) {
                Log.w("Updates", "download", e)
                e.message ?: e.javaClass.simpleName
            }
            main.post {
                if (isDestroyed) return@post
                if (error != null) {
                    file.delete()
                    show(State.FAILED, "Couldn't download: $error")
                } else {
                    install(file)
                }
            }
        }
    }

    private fun downloadTo(url: String, file: File, expectedSize: Long) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "NineTailsFlip-updater")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: expectedSize
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPercent = -1
                    while (!cancelled) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        val percent = if (total > 0) (done * 100 / total).toInt() else -1
                        if (percent != lastPercent) {
                            lastPercent = percent
                            main.post { if (state == State.DOWNLOADING) status.text = "Downloading $percent% of ${sizeText(total)}" }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Through a PackageInstaller session; its result comes back to this activity (onNewIntent). */
    private fun install(file: File) {
        show(State.INSTALLING, "Installing…")
        try {
            val installer = packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(packageName)
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("update.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val callback = PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, UpdateActivity::class.java).setAction(ACTION_INSTALL_STATUS),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                session.commit(callback.intentSender)
            }
        } catch (e: Exception) {
            Log.w("Updates", "install", e)
            show(State.FAILED, "Couldn't install: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun handleInstallStatus(intent: Intent?) {
        if (intent?.action != ACTION_INSTALL_STATUS) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)?.let { startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> show(State.FAILED, "Updated.")
            PackageInstaller.STATUS_FAILURE_ABORTED -> show(State.OFFER, "Not installed.")
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                // The usual cause: a build signed with another key (a debug build, or the original app).
                show(State.FAILED, "Android refused the update. $message".trim())
            }
        }
    }

    // ── Keys ────────────────────────────────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (menuSheet.handleKeyDown(keyCode, event)) return true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> when (state) {
                State.OFFER, State.FAILED -> start()
                State.NEEDS_PERMISSION -> openInstallSettings()
                else -> {}
            }
            KeyEvent.KEYCODE_DPAD_UP -> scroll.smoothScrollBy(0, -scroll.height * 2 / 3)
            KeyEvent.KEYCODE_DPAD_DOWN -> scroll.smoothScrollBy(0, scroll.height * 2 / 3)
            KeyEvent.KEYCODE_STAR -> openMenu()
            KeyEvent.KEYCODE_BACK -> {
                cancelled = true
                finish()
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun openMenu() {
        val current = info
        val items = mutableListOf<MenuItem>()
        if (current != null && state != State.DOWNLOADING && state != State.INSTALLING) {
            items += MenuItem("Update now") { start() }
            items += MenuItem("Skip ${current.versionName}") {
                Updates.skip(this, current.versionCode)
                finish()
            }
        }
        items += MenuItem("Remind me later") { finish() }
        items += MenuItem(if (Updates.autoCheck(this)) "Stop checking for updates" else "Check for updates daily") {
            Updates.setAutoCheck(this, !Updates.autoCheck(this))
            finish()
        }
        menuSheet.show(items)
    }

    private fun sizeText(bytes: Long): String = when {
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    }

    companion object {
        private const val ACTION_INSTALL_STATUS = "io.github.ninetailsflip.update.INSTALL_STATUS"

        fun intentFor(context: Context): Intent = Intent(context, UpdateActivity::class.java)
    }
}
