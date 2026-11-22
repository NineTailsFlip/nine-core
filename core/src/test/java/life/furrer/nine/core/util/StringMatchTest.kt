package life.furrer.nine.core.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StringMatchTest {

    // --- matchesName -------------------------------------------------------------------

    @Test
    fun `empty query matches everything`() {
        assertTrue(StringMatch.matchesName("Andreas Furrer", ""))
    }

    @Test
    fun `matches first word by prefix`() {
        assertTrue(StringMatch.matchesName("Andreas Furrer", "and"))
    }

    @Test
    fun `matches second word by prefix, not just the first`() {
        assertTrue(StringMatch.matchesName("Sam Anderson", "and"))
    }

    @Test
    fun `does not match a substring that is not a word prefix`() {
        // "and" appears inside "Sandra" starting at index 1, not at a word boundary — this is
        // word-prefix matching, not substring matching, specifically so a query doesn't match
        // "every contact containing those letters".
        assertFalse(StringMatch.matchesName("Sandra Keller", "and"))
    }

    @Test
    fun `is case-insensitive`() {
        assertTrue(StringMatch.matchesName("Andreas Furrer", "AND"))
        assertTrue(StringMatch.matchesName("ANDREAS FURRER", "and"))
    }

    @Test
    fun `full word match works, not just strict prefix`() {
        assertTrue(StringMatch.matchesName("Ana Beck", "ana"))
    }

    @Test
    fun `no match when no word starts with the query`() {
        assertFalse(StringMatch.matchesName("Bea Lombardi", "xyz"))
    }

    @Test
    fun `splits on punctuation, not just spaces`() {
        assertTrue(StringMatch.matchesName("O'Brien-Smith", "brien"))
        assertTrue(StringMatch.matchesName("O'Brien-Smith", "smith"))
    }

    @Test
    fun `diacritics match plain ascii query`() {
        assertTrue(StringMatch.matchesName("Müller", "muller"))
        assertTrue(StringMatch.matchesName("Müller", "mull"))
    }

    @Test
    fun `diacritic query matches plain ascii name`() {
        assertTrue(StringMatch.matchesName("Muller", "müller".take(4)))
        assertTrue(StringMatch.matchesName("Muller", "mül"))
    }

    @Test
    fun `diacritics match on both sides simultaneously`() {
        assertTrue(StringMatch.matchesName("Müller", "mü"))
    }

    @Test
    fun `other diacritic forms normalize too`() {
        assertTrue(StringMatch.matchesName("François", "franc"))
        assertTrue(StringMatch.matchesName("José", "jose"))
        assertTrue(StringMatch.matchesName("Åsa", "asa"))
    }

    @Test
    fun `blank name never matches a non-empty query`() {
        assertFalse(StringMatch.matchesName("", "a"))
    }

    // --- matchesPhoneNumber --------------------------------------------------------------

    @Test
    fun `empty query matches any phone number`() {
        assertTrue(StringMatch.matchesPhoneNumber("+41 79 263 45 67", ""))
    }

    @Test
    fun `digit substring matches ignoring punctuation on the number side`() {
        assertTrue(StringMatch.matchesPhoneNumber("+41 79 263 45 67", "7926345"))
    }

    @Test
    fun `digit substring matches ignoring punctuation on the query side`() {
        assertTrue(StringMatch.matchesPhoneNumber("+41792634567", "79-263-45-67"))
    }

    @Test
    fun `matches anywhere as a substring, not just a prefix`() {
        assertTrue(StringMatch.matchesPhoneNumber("+41 79 263 45 67", "4567"))
    }

    @Test
    fun `no match when digits are not a substring`() {
        assertFalse(StringMatch.matchesPhoneNumber("+41 79 263 45 67", "999"))
    }

    @Test
    fun `letters-only query never matches by number`() {
        assertFalse(StringMatch.matchesPhoneNumber("+41 79 263 45 67", "abc"))
    }

    // --- isAllDigits -----------------------------------------------------------------------

    @Test
    fun `isAllDigits true for pure digits`() {
        assertTrue(StringMatch.isAllDigits("79263"))
    }

    @Test
    fun `isAllDigits false for empty or punctuated or lettered queries`() {
        assertFalse(StringMatch.isAllDigits(""))
        assertFalse(StringMatch.isAllDigits("79-263"))
        assertFalse(StringMatch.isAllDigits("abc"))
    }

    // --- looksDialable -----------------------------------------------------------------------

    @Test
    fun `looksDialable true for plain digits`() {
        assertTrue(StringMatch.looksDialable("0791234567"))
    }

    @Test
    fun `looksDialable true for T9-style punctuated numbers`() {
        assertTrue(StringMatch.looksDialable("+41 79 123 45 67"))
        assertTrue(StringMatch.looksDialable("(079) 123-4567"))
    }

    @Test
    fun `looksDialable false for letters or empty`() {
        assertFalse(StringMatch.looksDialable(""))
        assertFalse(StringMatch.looksDialable("andreas"))
        assertFalse(StringMatch.looksDialable("+"))
    }
}
