package dev.tvsopen.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class CallerNumberFormatterTest {
    @Test
    fun splitsIndianE164NumberIntoNationalNumberAndCountryCode() {
        val parts = CallerNumberFormatter.split("+91 82200 61501", null)
        assertEquals("8220061501", parts.nationalNumber)
        assertEquals("91", parts.countryCode)
    }

    @Test
    fun splitsNorthAmericanE164Number() {
        val parts = CallerNumberFormatter.split("+1 202 555 0147", null)
        assertEquals("2025550147", parts.nationalNumber)
        assertEquals("1", parts.countryCode)
    }

    @Test
    fun usesSimRegionForNationalNumbers() {
        val parts = CallerNumberFormatter.split("82200 61501", "IN")
        assertEquals("8220061501", parts.nationalNumber)
        assertEquals("91", parts.countryCode)
    }

    @Test
    fun doesNotInventCountryCodeWhenRegionIsUnavailable() {
        val parts = CallerNumberFormatter.split("1234567", null)
        assertEquals("1234567", parts.nationalNumber)
        assertEquals(null, parts.countryCode)
    }
}
