package life.furrer.nine.core.util

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Runs the platform's own libphonenumber data (Robolectric ships the real framework classes). */
@RunWith(RobolectricTestRunner::class)
class PhoneFormatTest {

    private fun byCountry(number: String, home: String? = "CH") = PhoneFormat.format(number, NumberFormat.BY_COUNTRY, home)
    private fun typing(digits: String, home: String? = "CH") = PhoneFormat.formatTyping(digits, NumberFormat.BY_COUNTRY, home)

    @Test
    fun `numbers without a country code follow the home country`() {
        assertEquals("041 700 41 51", byCountry("0417004151"))
        assertEquals("079 123 45 67", byCountry("079 123 4567"))
        assertEquals("0800 123 456", byCountry("0800123456"))
        assertEquals("07751 234023", byCountry("07751234023", home = "GB"))
    }

    @Test
    fun `international numbers follow their own country, whatever the home country`() {
        assertEquals("+41 79 123 45 67", byCountry("+41791234567", home = "GB"))
        // On-device: grouped in plain threes, this read "+44 775 123 402 3".
        assertEquals("+44 7751 234023", byCountry("+447751234023"))
        assertEquals("+49 30 123456", byCountry("+4930123456"))
    }

    @Test
    fun `a 00 prefix is formatted like a plus and kept as written`() {
        assertEquals("0041 79 123 45 67", byCountry("0041791234567"))
    }

    @Test
    fun `without a known home country, national numbers are left as saved but international ones still format`() {
        assertEquals("0791234567", byCountry("0791234567", home = null))
        assertEquals("+44 7751 234023", byCountry("+447751234023", home = null))
    }

    @Test
    fun `anything that isn't a plain number is left untouched`() {
        assertEquals("Migros", byCountry("Migros"))
        assertEquals("*#06#", byCountry("*#06#"))
        assertEquals("0791234567,1234", byCountry("0791234567,1234"))
        assertEquals("112", byCountry("112"))
        assertEquals("", byCountry(""))
    }

    @Test
    fun `a number being typed fills its groups left to right`() {
        assertEquals("041", typing("041"))
        assertEquals("041 7", typing("0417"))
        assertEquals("041 700 4", typing("0417004"))
        assertEquals("041 700 41 51", typing("0417004151"))
        assertEquals("+41", typing("+41"))
        assertEquals("+41 79 123", typing("+4179123"))
        assertEquals("0041 79 123", typing("004179123"))
        assertEquals("*#06#", typing("*#06#"))
    }

    @Test
    fun `as saved returns the stored text`() {
        assertEquals("079 123 4567", PhoneFormat.format(" 079 123 4567 ", NumberFormat.AS_SAVED, "CH"))
        assertEquals("0417004", PhoneFormat.formatTyping("0417004", NumberFormat.AS_SAVED, "CH"))
    }
}
