package io.github.ninetailsflip.update

import android.app.Activity
import life.furrer.nine.core.settings.SettingsScreen

/** The "Updates" section every app's Settings ends its own sections with:
 *
 *      updatesSection(screen, this, ::refresh)
 *
 *  from NineSettingsActivity.buildAppSections. [refresh] is that activity's own refresh, so the
 *  "Check now" row can show its result in place. */
fun updatesSection(screen: SettingsScreen, activity: Activity, refresh: () -> Unit) {
    var checkStatus = ""
    screen.section("Updates") {
        action("Version", value = { Updates.installedVersionName(activity) }) {}
        toggle(
            label = "Check for updates",
            get = { Updates.autoCheck(activity) },
            set = { Updates.setAutoCheck(activity, it) },
            on = "daily, on GitHub",
            off = "off",
        )
        action("Check now", value = { checkStatus }) {
            checkStatus = "checking…"
            refresh()
            Updates.check { result ->
                checkStatus = when (result) {
                    Updates.Result.UpToDate -> "up to date"
                    is Updates.Result.Available -> "${result.info.versionName} available"
                    is Updates.Result.Failed -> "couldn't check"
                }
                refresh()
                if (result is Updates.Result.Available && !activity.isDestroyed) {
                    activity.startActivity(UpdateActivity.intentFor(activity))
                }
            }
        }
    }
}
