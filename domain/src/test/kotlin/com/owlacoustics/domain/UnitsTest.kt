package com.owlacoustics.domain

import com.owlacoustics.domain.units.LengthFormat
import com.owlacoustics.domain.units.LengthParser
import com.owlacoustics.domain.units.LengthUnit
import com.owlacoustics.domain.units.ParseResult
import com.owlacoustics.domain.units.Rational
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnitsTest {
    private fun meters(s: String, u: LengthUnit = LengthUnit.FOOT) = (LengthParser.parse(s, u) as ParseResult.Ok).value.exactMeters

    @Test fun exactImperialForms() {
        // 12' 3 1/2" = 147.5 in = 3.7465 m exactly
        val expected = Rational.of(147) * Rational.of(254, 10000) + Rational.of(1, 2) * Rational.of(254, 10000)
        assertEquals(expected, meters("12' 3 1/2\""))
        assertEquals(expected, meters("12 ft 3.5 in"))
        assertEquals(expected, meters("12ft 3-1/2in"))
        assertEquals(Rational.parseDecimal("3.7465"), meters("12' 3 1/2"))
        assertEquals(Rational.of(3, 4) * Rational.of(254, 10000), meters("3/4\""))
        assertEquals(Rational.of(59, 8) * Rational.of(254, 10000), meters("7 3/8 in"))
    }

    @Test fun metricForms() {
        assertEquals(Rational.parseDecimal("3.5"), meters("3.5 m"))
        assertEquals(Rational.parseDecimal("3.5"), meters("350cm"))
        assertEquals(Rational.parseDecimal("3.505"), meters("3 m 50 cm 5 mm"))
        assertEquals(Rational.parseDecimal("2.4"), meters("2.4", LengthUnit.METER))
    }

    @Test fun bareNumberUsesDefaultUnit() {
        assertEquals(Rational.of(3048, 1000), meters("10", LengthUnit.FOOT))
        assertEquals(Rational.of(254, 1000), meters("10", LengthUnit.INCH))
    }

    @Test fun invalidAndUnknown() {
        assertTrue(LengthParser.parse("", LengthUnit.FOOT) is ParseResult.Unknown)
        assertTrue(LengthParser.parse("   ", LengthUnit.FOOT) is ParseResult.Unknown)
        assertTrue(LengthParser.parse("3/0 in", LengthUnit.FOOT) is ParseResult.Invalid)
        assertTrue(LengthParser.parse("-2 ft", LengthUnit.FOOT) is ParseResult.Invalid)
        assertTrue(LengthParser.parse("NaN", LengthUnit.FOOT) is ParseResult.Invalid)
        assertTrue(LengthParser.parse("Infinity", LengthUnit.FOOT) is ParseResult.Invalid)
        assertTrue(LengthParser.parse("abc", LengthUnit.FOOT) is ParseResult.Invalid)
        assertTrue(LengthParser.parse("3 in 2 ft", LengthUnit.FOOT) is ParseResult.Invalid)
        assertTrue(LengthParser.parse("3 4", LengthUnit.FOOT) is ParseResult.Invalid)
        assertTrue(LengthParser.parse("1.5 1/2 in", LengthUnit.FOOT) is ParseResult.Invalid)
        // zero is a real value (valid for offsets), distinct from unknown
        val z = LengthParser.parse("0", LengthUnit.FOOT)
        assertTrue(z is ParseResult.Ok && z.value.exactMeters == Rational.ZERO)
    }

    @Test fun repeatedConversionDoesNotDrift() {
        // an exact entry survives any number of metric/imperial display round trips because it is stored exactly
        val e = (LengthParser.parse("10' 7 13/16\"", LengthUnit.FOOT) as ParseResult.Ok).value
        var x = e.exactMeters
        repeat(1000) {
            val inches = x / LengthUnit.INCH.metersPerUnit
            x = inches * LengthUnit.INCH.metersPerUnit
            val mm = x / LengthUnit.MILLIMETER.metersPerUnit
            x = mm * LengthUnit.MILLIMETER.metersPerUnit
        }
        assertEquals(e.exactMeters, x)
        assertEquals("10' 7 13/16\"", LengthFormat.formatFeetInches(e.meters))
    }

    @Test fun nonTerminatingFractionsStayExact() {
        val third = Rational.of(1, 3)
        assertEquals(Rational.ONE, third + third + third)
    }

    @Test fun displayFormatting() {
        assertEquals("12' 0\"", LengthFormat.formatFeetInches(12 * 0.3048))
        assertEquals("3 1/2\"", LengthFormat.formatFeetInches(3.5 * 0.0254))
        assertEquals("3.000 m", LengthFormat.format(3.0, com.owlacoustics.domain.units.DisplayUnits.METRIC))
        assertEquals("250 mm", LengthFormat.format(0.25, com.owlacoustics.domain.units.DisplayUnits.METRIC))
    }
}
