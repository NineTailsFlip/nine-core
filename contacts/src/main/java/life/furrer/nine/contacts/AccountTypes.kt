package life.furrer.nine.contacts

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.provider.ContactsContract
import org.xmlpull.v1.XmlPullParser

/**
 * Which contact account types hold *editable* contacts, decided the way Android's own Contacts
 * app decides it: an account's sync adapter may declare its contacts structure in
 * `android.provider.CONTACTS_STRUCTURE` metadata, and only one that includes an `EditSchema` there
 * is writable. Messaging apps that mirror your contacts into their own account (WhatsApp, Signal)
 * declare display-only structures — their rows exist to add "Message"/"Call" actions, and edits
 * to them never reach a real address book. An account with no such metadata at all (DAVx5, plain
 * local contacts) is editable, again matching the platform's fallback.
 *
 * Needed because "the lowest raw contact id is the real address book" stopped being true on the
 * phone: re-downloading an address book (DAVx5 does it on a group-method change) gives every
 * synced raw contact a new, higher id than the mirrors linked to it, so the editor started
 * writing into WhatsApp's/Signal's copies. No app or account type is named anywhere.
 *
 * Computed once per process from the installed sync adapters; app installs are rare enough not
 * to warrant invalidation.
 */
object AccountTypes {
    private const val SYNC_ADAPTER_ACTION = "android.content.SyncAdapter"
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private val STRUCTURE_METADATA = listOf("android.provider.CONTACTS_STRUCTURE", "android.provider.ALTERNATE_CONTACTS_STRUCTURE")

    @Volatile private var readOnlyTypes: Set<String>? = null

    /** Null (a device-local raw contact) is always editable. */
    fun isEditable(context: Context, accountType: String?): Boolean =
        accountType == null || accountType !in readOnly(context)

    /** Tests only: Robolectric has no sync adapters to read, so tests state the answer. Null
     *  goes back to computing it. */
    fun overrideReadOnlyTypes(types: Set<String>?) {
        readOnlyTypes = types
    }

    private fun readOnly(context: Context): Set<String> =
        readOnlyTypes ?: computeReadOnly(context.applicationContext).also { readOnlyTypes = it }

    /** Every contacts sync adapter that declares a display-only structure. Found by walking the
     *  installed SyncAdapter services the way Android's Contacts app does: each service's
     *  `android.content.SyncAdapter` metadata names the account type and authority it syncs, and
     *  the same service carries the contacts-structure metadata. */
    private fun computeReadOnly(context: Context): Set<String> {
        val pm = context.packageManager
        val result = mutableSetOf<String>()
        val services = pm.queryIntentServices(Intent(SYNC_ADAPTER_ACTION), PackageManager.GET_META_DATA)
        for (resolved in services) {
            val info = resolved.serviceInfo ?: continue
            val accountType = contactsAccountType(pm, info) ?: continue
            if (declaresReadOnly(pm, info)) result.add(accountType)
        }
        return result
    }

    /** The account type [info] syncs, when it syncs contacts at all. */
    private fun contactsAccountType(pm: PackageManager, info: ServiceInfo): String? {
        val parser = load(pm, info, SYNC_ADAPTER_ACTION) ?: return null
        return try {
            val resources = pm.getResourcesForApplication(info.applicationInfo)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "sync-adapter") {
                    val authority = attribute(parser, resources, "contentAuthority")
                    val type = attribute(parser, resources, "accountType")
                    return if (authority == ContactsContract.AUTHORITY) type else null
                }
                event = parser.next()
            }
            null
        } catch (e: Exception) {
            null
        } finally {
            parser.close()
        }
    }

    private fun declaresReadOnly(pm: PackageManager, info: ServiceInfo): Boolean {
        for (key in STRUCTURE_METADATA) {
            val parser = load(pm, info, key) ?: continue
            return try {
                !declaresEditSchema(parser)
            } finally {
                parser.close()
            }
        }
        return false
    }

    private fun load(pm: PackageManager, info: ServiceInfo, key: String): XmlResourceParser? = try {
        info.loadXmlMetaData(pm, key)
    } catch (e: RuntimeException) {
        null
    }

    /** An `android:` attribute's value, following a `@string/...` reference into the owning
     *  app's resources (sync adapters commonly declare their account type that way). */
    private fun attribute(parser: XmlResourceParser, resources: Resources, name: String): String? {
        val resId = parser.getAttributeResourceValue(ANDROID_NS, name, 0)
        if (resId != 0) return try {
            resources.getString(resId)
        } catch (e: Resources.NotFoundException) {
            null
        }
        return parser.getAttributeValue(ANDROID_NS, name)
    }

    /** True when a contacts-structure document contains an `EditSchema` element. A document that
     *  can't be read is given the benefit of the doubt (editable), never the other way round:
     *  wrongly calling a real address book read-only would make its contacts uneditable. */
    internal fun declaresEditSchema(parser: XmlPullParser): Boolean = try {
        var event = parser.eventType
        var found = false
        while (event != XmlPullParser.END_DOCUMENT && !found) {
            if (event == XmlPullParser.START_TAG && parser.name == "EditSchema") found = true
            event = parser.next()
        }
        found
    } catch (e: Exception) {
        true
    }
}
