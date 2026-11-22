package life.furrer.nine.core.util

import android.app.role.RoleManager
import life.furrer.nine.core.Nine
import life.furrer.nine.core.NineApp
import life.furrer.nine.core.NineSurface
import life.furrer.nine.core.prefs.NinePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

// Intent.EXTRA_ROLE_NAME is @SystemApi, so not reachable from here; this is its value.
private const val EXTRA_ROLE_NAME = "android.intent.extra.ROLE_NAME"

@RunWith(RobolectricTestRunner::class)
class SystemRoleTest {

    private val context = RuntimeEnvironment.getApplication()
    private val role = SystemRole(RoleManager.ROLE_SMS, "asked_test_role")
    private val shadowRoles get() = shadowOf(context.getSystemService(RoleManager::class.java))

    @Before
    fun setUp() {
        Nine.init(NineApp(id = "systemrole-test", name = "Test", tagline = "", surfaces = listOf(NineSurface("", "", "", emptyList())), settingsActivity = Any::class.java))
    }

    @Test
    fun `fails closed when the role is neither held nor available`() {
        assertFalse(role.isHeld(context))
        assertFalse(role.isAvailable(context))
    }

    @Test
    fun `isHeld and isAvailable follow RoleManager for this role only`() {
        shadowRoles.addHeldRole(RoleManager.ROLE_SMS)
        shadowRoles.addAvailableRole(RoleManager.ROLE_SMS)
        assertTrue(role.isHeld(context))
        assertTrue(role.isAvailable(context))

        val other = SystemRole(RoleManager.ROLE_DIALER, "asked_other_role")
        assertFalse(other.isHeld(context))
        assertFalse(other.isAvailable(context))
    }

    @Test
    fun `requestIntent is null when the role is unavailable`() {
        assertNull(role.requestIntent(context))
    }

    @Test
    fun `requestIntent asks for this role when it is available`() {
        shadowRoles.addAvailableRole(RoleManager.ROLE_SMS)
        assertEquals(RoleManager.ROLE_SMS, role.requestIntent(context)?.getStringExtra(EXTRA_ROLE_NAME))
    }

    @Test
    fun `hasAskedBefore defaults to false and persists under the given key`() {
        assertFalse(role.hasAskedBefore(context))
        role.markAsked(context)
        assertTrue(role.hasAskedBefore(context))
        // The key is what existing installs already store, so it has to be used verbatim.
        assertTrue(NinePrefs.of(context).getBoolean("asked_test_role", false))
        assertFalse(SystemRole(RoleManager.ROLE_SMS, "asked_other_role").hasAskedBefore(context))
    }
}
