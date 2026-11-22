package life.furrer.nine.contacts

import android.content.ContentValues
import android.provider.ContactsContract.CommonDataKinds as CDK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DataKindsTest {

    private val resources = RuntimeEnvironment.getApplication().resources

    @Test
    fun `fromMimetype resolves a known mimetype`() {
        assertEquals(DataKind.PHONE, DataKind.fromMimetype(CDK.Phone.CONTENT_ITEM_TYPE))
    }

    @Test
    fun `fromMimetype returns null for an unknown mimetype (shown under Other, not dropped)`() {
        assertNull(DataKind.fromMimetype("vnd.android.cursor.item/vendor.custom.field"))
    }

    @Test
    fun `action maps kinds with a real verb`() {
        assertEquals(DataAction.DIAL, DataKind.PHONE.action())
        assertEquals(DataAction.EMAIL, DataKind.EMAIL.action())
        assertEquals(DataAction.MAP, DataKind.POSTAL.action())
        assertEquals(DataAction.WEB, DataKind.WEBSITE.action())
        assertEquals(DataAction.NONE, DataKind.NOTE.action())
    }

    @Test
    fun `typeLabel formats a standard phone type`() {
        val values = ContentValues().apply { put(CDK.Phone.TYPE, CDK.Phone.TYPE_MOBILE) }
        assertEquals("MOBILE", DataFormat.typeLabel(resources, DataKind.PHONE, values))
    }

    @Test
    fun `typeLabel formats a custom label`() {
        val values = ContentValues().apply {
            put(CDK.Phone.TYPE, CDK.Phone.TYPE_CUSTOM)
            put(CDK.Phone.LABEL, "Yacht")
        }
        assertEquals("YACHT", DataFormat.typeLabel(resources, DataKind.PHONE, values))
    }

    @Test
    fun `typeName keeps a custom label's case and a preset's platform spelling`() {
        val custom = ContentValues().apply {
            put(CDK.Event.TYPE, CDK.Event.TYPE_CUSTOM)
            put(CDK.Event.LABEL, "GitLab")
        }
        assertEquals("GitLab", DataFormat.typeName(resources, DataKind.EVENT, custom))
        assertEquals("GITLAB", DataFormat.typeLabel(resources, DataKind.EVENT, custom))
        val preset = ContentValues().apply { put(CDK.Phone.TYPE, CDK.Phone.TYPE_MOBILE) }
        assertEquals("Mobile", DataFormat.typeName(resources, DataKind.PHONE, preset))
    }

    @Test
    fun `typeLabel returns null for kinds without a meaningful type`() {
        val values = ContentValues().apply { put(CDK.Note.NOTE, "hello") }
        assertNull(DataFormat.typeLabel(resources, DataKind.NOTE, values))
    }

    @Test
    fun `imProtocolLabel formats a standard protocol`() {
        val values = ContentValues().apply { put(CDK.Im.PROTOCOL, CDK.Im.PROTOCOL_SKYPE) }
        assertEquals("SKYPE", DataFormat.imProtocolLabel(resources, values))
    }

    @Test
    fun `valueText reads the plain-value kinds directly`() {
        assertEquals(
            "Andreas Furrer",
            DataFormat.valueText(
                DataKind.NAME,
                ContentValues().apply { put(CDK.StructuredName.DISPLAY_NAME, "Andreas Furrer") },
            ),
        )
        assertEquals(
            "+41 79 123 45 67",
            DataFormat.valueText(DataKind.PHONE, ContentValues().apply { put(CDK.Phone.NUMBER, "+41 79 123 45 67") }),
        )
        assertEquals(
            "andreas@acme.ch",
            DataFormat.valueText(DataKind.EMAIL, ContentValues().apply { put(CDK.Email.ADDRESS, "andreas@acme.ch") }),
        )
        assertEquals(
            "acme.ch/andreas",
            DataFormat.valueText(DataKind.WEBSITE, ContentValues().apply { put(CDK.Website.URL, "acme.ch/andreas") }),
        )
        assertEquals(
            "a note",
            DataFormat.valueText(DataKind.NOTE, ContentValues().apply { put(CDK.Note.NOTE, "a note") }),
        )
    }

    @Test
    fun `valueText prefers FORMATTED_ADDRESS when present`() {
        val values = ContentValues().apply {
            put(CDK.StructuredPostal.FORMATTED_ADDRESS, "Seestrasse 4, 6300 Zug")
            put(CDK.StructuredPostal.STREET, "Seestrasse 4")
        }
        assertEquals("Seestrasse 4, 6300 Zug", DataFormat.valueText(DataKind.POSTAL, values))
    }

    @Test
    fun `valueText composes an address from parts when FORMATTED_ADDRESS is absent`() {
        val values = ContentValues().apply {
            put(CDK.StructuredPostal.STREET, "Seestrasse 4")
            put(CDK.StructuredPostal.CITY, "Zug")
            put(CDK.StructuredPostal.POSTCODE, "6300")
        }
        assertEquals("Seestrasse 4, Zug, 6300", DataFormat.valueText(DataKind.POSTAL, values))
    }

    @Test
    fun `valueText composes organization as company and title`() {
        val values = ContentValues().apply {
            put(CDK.Organization.COMPANY, "Acme AG")
            put(CDK.Organization.TITLE, "CTO")
        }
        assertEquals("Acme AG · CTO", DataFormat.valueText(DataKind.ORGANIZATION, values))
    }

    @Test
    fun `valueText handles organization with only company or only title`() {
        assertEquals(
            "Acme AG",
            DataFormat.valueText(DataKind.ORGANIZATION, ContentValues().apply { put(CDK.Organization.COMPANY, "Acme AG") }),
        )
        assertEquals(
            "CTO",
            DataFormat.valueText(DataKind.ORGANIZATION, ContentValues().apply { put(CDK.Organization.TITLE, "CTO") }),
        )
    }

    @Test
    fun `valueText formats a full event date`() {
        val values = ContentValues().apply { put(CDK.Event.START_DATE, "1984-03-12") }
        assertEquals("12 Mar 1984", DataFormat.valueText(DataKind.EVENT, values))
    }

    @Test
    fun `valueText formats a no-year event date`() {
        val values = ContentValues().apply { put(CDK.Event.START_DATE, "--03-12") }
        assertEquals("Mar 12", DataFormat.valueText(DataKind.EVENT, values))
    }

    @Test
    fun `valueText falls back to the raw string for an unparseable date rather than dropping it`() {
        val values = ContentValues().apply { put(CDK.Event.START_DATE, "not-a-date") }
        assertEquals("not-a-date", DataFormat.valueText(DataKind.EVENT, values))
    }

    @Test
    fun `valueText returns empty for a blank or missing event date`() {
        assertEquals("", DataFormat.valueText(DataKind.EVENT, ContentValues()))
    }

    @Test
    fun `typeFromLabel maps recognized labels per kind`() {
        assertEquals(CDK.StructuredPostal.TYPE_WORK, typeFromLabel(DataKind.POSTAL, "Work"))
        assertEquals(CDK.Event.TYPE_BIRTHDAY, typeFromLabel(DataKind.EVENT, "Birthday"))
        assertEquals(CDK.Event.TYPE_ANNIVERSARY, typeFromLabel(DataKind.EVENT, "Anniversary"))
        assertEquals(CDK.Relation.TYPE_SPOUSE, typeFromLabel(DataKind.RELATION, "Spouse"))
        assertEquals(CDK.SipAddress.TYPE_HOME, typeFromLabel(DataKind.SIP, "Home"))
    }

    @Test
    fun `typeFromLabel is case- and whitespace-insensitive`() {
        assertEquals(CDK.Event.TYPE_BIRTHDAY, typeFromLabel(DataKind.EVENT, "  birthday  "))
        assertEquals(CDK.Event.TYPE_BIRTHDAY, typeFromLabel(DataKind.EVENT, "BIRTHDAY"))
    }

    @Test
    fun `typeFromLabel falls back to that kind's own TYPE_CUSTOM for unrecognized text`() {
        assertEquals(CDK.StructuredPostal.TYPE_CUSTOM, typeFromLabel(DataKind.POSTAL, "Cabin"))
        assertEquals(CDK.Event.TYPE_CUSTOM, typeFromLabel(DataKind.EVENT, "Graduation"))
        assertEquals(CDK.Relation.TYPE_CUSTOM, typeFromLabel(DataKind.RELATION, "Godparent"))
        assertEquals(CDK.SipAddress.TYPE_CUSTOM, typeFromLabel(DataKind.SIP, "Cabin"))
    }

    @Test
    fun `changeableTypeLabels offers Birthday, Anniversary, Other, Custom for Event`() {
        assertEquals(listOf("Birthday", "Anniversary", "Other", "Custom"), changeableTypeLabels(DataKind.EVENT))
    }

    @Test
    fun `changeableTypeLabels offers Mobile, Work, Home, Other, Custom for Phone`() {
        assertEquals(listOf("Mobile", "Work", "Home", "Other", "Custom"), changeableTypeLabels(DataKind.PHONE))
    }

    @Test
    fun `phoneTypeFromLabel round-trips every platform phone type, whatever its case`() {
        val resources = RuntimeEnvironment.getApplication().resources
        (CDK.Phone.TYPE_HOME..CDK.Phone.TYPE_MMS).forEach { type ->
            val label = CDK.Phone.getTypeLabel(resources, type, null).toString()
            assertEquals(label, type, phoneTypeFromLabel(resources, label.uppercase()))
        }
        assertEquals(CDK.Phone.TYPE_WORK, phoneTypeFromLabel(resources, " work "))
    }

    @Test
    fun `phoneTypeFromLabel falls back to TYPE_CUSTOM for unrecognized text`() {
        val resources = RuntimeEnvironment.getApplication().resources
        assertEquals(CDK.Phone.TYPE_CUSTOM, phoneTypeFromLabel(resources, "Signal"))
    }

    @Test
    fun `emailTypeFromLabel round-trips every platform email type and falls back to TYPE_CUSTOM`() {
        val resources = RuntimeEnvironment.getApplication().resources
        (CDK.Email.TYPE_HOME..CDK.Email.TYPE_MOBILE).forEach { type ->
            val label = CDK.Email.getTypeLabel(resources, type, null).toString()
            assertEquals(label, type, emailTypeFromLabel(resources, label.uppercase()))
        }
        assertEquals(CDK.Email.TYPE_CUSTOM, emailTypeFromLabel(resources, "Club"))
    }

    @Test
    fun `changeableTypeLabels is empty for kinds with no Change type action yet`() {
        assertEquals(emptyList<String>(), changeableTypeLabels(DataKind.EMAIL))
        assertEquals(emptyList<String>(), changeableTypeLabels(DataKind.RELATION))
        assertEquals(emptyList<String>(), changeableTypeLabels(DataKind.NAME))
    }

    @Test
    fun `imProtocolFromLabel maps recognized protocol names`() {
        assertEquals(CDK.Im.PROTOCOL_SKYPE, imProtocolFromLabel("Skype"))
        assertEquals(CDK.Im.PROTOCOL_JABBER, imProtocolFromLabel("jabber"))
        assertEquals(CDK.Im.PROTOCOL_GOOGLE_TALK, imProtocolFromLabel("Hangouts"))
    }

    @Test
    fun `imProtocolFromLabel falls back to PROTOCOL_CUSTOM for a modern messenger name`() {
        assertEquals(CDK.Im.PROTOCOL_CUSTOM, imProtocolFromLabel("WhatsApp"))
        assertEquals(CDK.Im.PROTOCOL_CUSTOM, imProtocolFromLabel("Signal"))
    }
}
