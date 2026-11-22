package life.furrer.nine.core.util

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import life.furrer.nine.core.prefs.NinePrefs

/** Whether this app holds a `RoleManager` role ([roleName], e.g. `ROLE_DIALER`, `ROLE_SMS`), the
 *  request flow to get it, and the "asked once already" flag kept under [askedKey] in the app's
 *  prefs. What *not* holding the role costs is each app's own story, told where its instance is
 *  declared (P9's `DialerRole`, M9's `SmsRole`). */
class SystemRole(private val roleName: String, private val askedKey: String) {
    fun isHeld(context: Context): Boolean =
        roleManager(context)?.isRoleHeld(roleName) ?: false

    fun isAvailable(context: Context): Boolean =
        roleManager(context)?.isRoleAvailable(roleName) ?: false

    /** Null when the role can't be requested here at all (no `RoleManager`, or the role isn't
     *  [isAvailable] on this device). Start it with `startActivityForResult`, never plain
     *  `startActivity`: the system's role request screen finishes immediately, showing nothing,
     *  when it has no calling activity to answer. The result code itself isn't meaningful to
     *  check — the only reliable way to know it took is to re-check [isHeld] once control
     *  returns. */
    fun requestIntent(context: Context): Intent? {
        val manager = roleManager(context) ?: return null
        if (!manager.isRoleAvailable(roleName)) return null
        return manager.createRequestRoleIntent(roleName)
    }

    /** First run asks once; after that, granting the role lives as a Settings row rather than a
     *  prompt on every launch. */
    fun hasAskedBefore(context: Context): Boolean =
        NinePrefs.of(context).getBoolean(askedKey, false)

    fun markAsked(context: Context) {
        NinePrefs.of(context).edit().putBoolean(askedKey, true).apply()
    }

    private fun roleManager(context: Context): RoleManager? = context.getSystemService(RoleManager::class.java)
}
