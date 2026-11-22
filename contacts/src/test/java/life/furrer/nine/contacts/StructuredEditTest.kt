package life.furrer.nine.contacts

import android.provider.ContactsContract.CommonDataKinds as CDK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StructuredEditTest {

    private fun name(prefix: String = "", given: String = "", middle: String = "", family: String = "", suffix: String = "") = mapOf(
        CDK.StructuredName.PREFIX to prefix,
        CDK.StructuredName.GIVEN_NAME to given,
        CDK.StructuredName.MIDDLE_NAME to middle,
        CDK.StructuredName.FAMILY_NAME to family,
        CDK.StructuredName.SUFFIX to suffix,
    )

    private val muster = name("Dr.", "Hans", "Peter", "Muster", "med.")

    @Test
    fun `a new name splits into first and last`() {
        assertEquals(name(given = "Hans", family = "Muster Meier"), NameParts.fromText(emptyMap(), "Hans  Muster Meier"))
        assertEquals(name(given = "Madonna"), NameParts.fromText(emptyMap(), "Madonna"))
    }

    @Test
    fun `prefix suffix and middle name still in the text are kept`() {
        assertEquals(name("Dr.", "Hans", "Peter", "Meier", "med."), NameParts.fromText(muster, "Dr. Hans Peter Meier med."))
        assertEquals(name("Dr.", "Hans", "Peter", "Meier", "med."), NameParts.fromText(muster, "Dr. Hans Peter Meier, med."))
    }

    @Test
    fun `parts missing from the text are cleared`() {
        assertEquals(name(given = "Hans", family = "Muster"), NameParts.fromText(muster, "Hans Muster"))
    }

    @Test
    fun `a compound first name or last name survives an edit of the other`() {
        val old = name(given = "Anna Maria", family = "Muster")
        assertEquals(name(given = "Anna Maria", family = "Meier"), NameParts.fromText(old, "Anna Maria Meier"))
        assertEquals(name(given = "Eva Maria", family = "Muster"), NameParts.fromText(old, "Eva Maria Muster"))
    }

    @Test
    fun `diff holds only changed columns and blanks become null`() {
        val diff = StructuredEdit.diff(muster, name("Dr.", "Hans", "", "Muster", "med."))
        assertEquals(setOf(CDK.StructuredName.MIDDLE_NAME), diff.keySet())
        assertTrue(diff.get(CDK.StructuredName.MIDDLE_NAME) == null)
        assertEquals(0, StructuredEdit.diff(muster, muster).size())
    }

    @Test
    fun `display and address line skip blank parts`() {
        assertEquals("Dr. Hans Peter Muster med.", NameParts.display(muster))
        assertEquals(
            "Bahnhofstrasse 1, Zürich",
            PostalParts.line(mapOf(CDK.StructuredPostal.STREET to "Bahnhofstrasse 1", CDK.StructuredPostal.CITY to "Zürich", CDK.StructuredPostal.REGION to "")),
        )
    }

    @Test
    fun `organisation text uses the loaded row to tell a title from a company`() {
        val titleOnly = mapOf(CDK.Organization.COMPANY to "", CDK.Organization.TITLE to "Engineer")
        assertEquals("Senior Engineer", OrganizationParts.fromText(titleOnly, "Senior Engineer")[CDK.Organization.TITLE])
        val companyOnly = mapOf(CDK.Organization.COMPANY to "ACME", CDK.Organization.TITLE to "")
        assertEquals("ACME2", OrganizationParts.fromText(companyOnly, "ACME2")[CDK.Organization.COMPANY])
        assertEquals("ACME", OrganizationParts.fromText(emptyMap(), "ACME · Boss")[CDK.Organization.COMPANY])
        assertFalse(OrganizationParts.fromText(emptyMap(), "ACME").containsValue("Boss"))
    }
}
