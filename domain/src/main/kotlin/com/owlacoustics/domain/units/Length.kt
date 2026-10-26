package com.owlacoustics.domain.units

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

@Serializable
enum class LengthUnit(val symbol: String) {
    METER("m"), CENTIMETER("cm"), MILLIMETER("mm"), FOOT("ft"), INCH("in");

    /** Exact metres per unit (international inch/foot definitions). */
    val metersPerUnit: Rational
        get() = when (this) {
            METER -> Rational.ONE
            CENTIMETER -> Rational.of(1, 100)
            MILLIMETER -> Rational.of(1, 1000)
            FOOT -> Rational.of(3048, 10000)
            INCH -> Rational.of(254, 10000)
        }
}

@Serializable
enum class DisplayUnits { IMPERIAL, METRIC }

/**
 * A value exactly as the person entered it: original text, the unit it was interpreted in and the
 * exact value in metres (stored as a reduced rational). Computation coordinates are separate Doubles.
 */
@Serializable
data class EnteredValue(val text: String, val unit: LengthUnit, val metersNum: String, val metersDen: String) {
    val exactMeters: Rational get() = Rational.parse(metersNum, metersDen)
    val meters: Double get() = exactMeters.toDouble()

    companion object {
        fun of(text: String, unit: LengthUnit, meters: Rational) =
            EnteredValue(text.trim(), unit, meters.num.toString(), meters.den.toString())
    }
}

sealed interface ParseResult {
    data class Ok(val value: EnteredValue) : ParseResult
    data class Invalid(val reason: String) : ParseResult
    /** The field was cleared: the value is unknown, never zero. */
    data object Unknown : ParseResult
}

object LengthParser {
    private val unitAliases = listOf(
        "millimeters" to LengthUnit.MILLIMETER, "millimetres" to LengthUnit.MILLIMETER, "mm" to LengthUnit.MILLIMETER,
        "centimeters" to LengthUnit.CENTIMETER, "centimetres" to LengthUnit.CENTIMETER, "cm" to LengthUnit.CENTIMETER,
        "meters" to LengthUnit.METER, "metres" to LengthUnit.METER, "m" to LengthUnit.METER,
        "feet" to LengthUnit.FOOT, "foot" to LengthUnit.FOOT, "ft" to LengthUnit.FOOT, "'" to LengthUnit.FOOT, "’" to LengthUnit.FOOT,
        "inches" to LengthUnit.INCH, "inch" to LengthUnit.INCH, "in" to LengthUnit.INCH, "\"" to LengthUnit.INCH, "”" to LengthUnit.INCH,
    )

    /**
     * Parses metric ("3.5 m", "350cm"), feet/inches ("12' 3 1/2\"", "12 ft 3.5 in", "7 3/8 in", "3/4\"")
     * or a bare number interpreted in [defaultUnit]. A bare number in IMPERIAL default is feet.
     */
    fun parse(input: String, defaultUnit: LengthUnit): ParseResult {
        val text = input.trim()
        if (text.isEmpty()) return ParseResult.Unknown
        if (text.startsWith("-")) return ParseResult.Invalid("Length cannot be negative")
        val tokens = tokenize(text) ?: return ParseResult.Invalid("Could not read \"$text\"")
        // tokens: list of (number, unit?) pairs
        if (tokens.isEmpty()) return ParseResult.Invalid("No number found")
        var total = Rational.ZERO
        var lastUnit: LengthUnit? = null
        var firstUnit: LengthUnit? = null
        val units = tokens.map { it.second }
        if (units.count { it == null } > 1) return ParseResult.Invalid("Add units, for example 12 ft 3 in")
        for ((i, tok) in tokens.withIndex()) {
            val (value, u0) = tok
            val u = u0 ?: when {
                // "12' 3" → trailing bare number after feet is inches
                i > 0 && lastUnit == LengthUnit.FOOT -> LengthUnit.INCH
                tokens.size == 1 -> defaultUnit
                else -> return ParseResult.Invalid("Add units, for example 12 ft 3 in")
            }
            if (lastUnit != null) {
                val ok = lastUnit == LengthUnit.FOOT && u == LengthUnit.INCH ||
                    lastUnit == LengthUnit.METER && (u == LengthUnit.CENTIMETER || u == LengthUnit.MILLIMETER) ||
                    lastUnit == LengthUnit.CENTIMETER && u == LengthUnit.MILLIMETER
                if (!ok) return ParseResult.Invalid("Units must go from larger to smaller, for example 12 ft 3 in")
            }
            total += value * u.metersPerUnit
            if (firstUnit == null) firstUnit = u
            lastUnit = u
        }
        return ParseResult.Ok(EnteredValue.of(text, firstUnit ?: defaultUnit, total))
    }

    private fun tokenize(s: String): List<Pair<Rational, LengthUnit?>>? {
        val out = mutableListOf<Pair<Rational, LengthUnit?>>()
        var i = 0
        val n = s.length
        fun skipWs() { while (i < n && s[i].isWhitespace()) i++ }
        while (true) {
            skipWs()
            if (i >= n) break
            // number: decimal, or whole + fraction "3 1/2", or fraction "1/2"
            // a bare fraction ("3/4") is tried first so its numerator is not read as a whole number
            val numRe = Regex("""^(\d+)\s*/\s*(\d+)|^(\d+(?:\.\d*)?|\.\d+)(?:\s*(?:\s|-)\s*(\d+)\s*/\s*(\d+))?""")
            val m = numRe.find(s.substring(i)) ?: return null
            val value: Rational = if (m.groups[1] != null) {
                val d = m.groupValues[2]
                if (d.toBigInteger().signum() == 0) return null
                Rational.parse(m.groupValues[1], d)
            } else {
                val whole = Rational.parseDecimal(m.groupValues[3]) ?: return null
                if (m.groups[4] != null) {
                    if (m.groupValues[3].contains('.')) return null
                    val d = m.groupValues[5]
                    if (d.toBigInteger().signum() == 0) return null
                    whole + Rational.parse(m.groupValues[4], d)
                } else whole
            }
            i += m.value.length
            skipWs()
            var unit: LengthUnit? = null
            for ((alias, u) in unitAliases) {
                if (s.regionMatches(i, alias, 0, alias.length, ignoreCase = true)) {
                    val end = i + alias.length
                    val boundary = end >= n || !s[end].isLetter()
                    if (boundary) { unit = u; i = end; break }
                }
            }
            out += value to unit
        }
        return out
    }
}

object LengthFormat {
    /** Formats metres for display. Fractions are display resolution only, never a claim of accuracy. */
    fun format(meters: Double, units: DisplayUnits, fractionDenominator: Int = 16): String {
        if (!meters.isFinite()) return "—"
        return when (units) {
            DisplayUnits.METRIC -> {
                val a = abs(meters)
                if (a < 1.0) "${(meters * 1000).roundToLong()} mm"
                else String.format(java.util.Locale.US, "%.3f m", meters)
            }
            DisplayUnits.IMPERIAL -> formatFeetInches(meters, fractionDenominator)
        }
    }

    fun formatFeetInches(meters: Double, den: Int = 16): String {
        val sign = if (meters < 0) "-" else ""
        val totalIn = abs(meters) / 0.0254
        var units = (totalIn * den).roundToLong()
        val feet = units / (12L * den)
        units -= feet * 12L * den
        val wholeIn = units / den
        var frac = units - wholeIn * den
        var d = den.toLong()
        while (frac != 0L && frac % 2 == 0L) { frac /= 2; d /= 2 }
        val inchPart = buildString {
            append(wholeIn)
            if (frac != 0L) append(" $frac/$d")
            append('"')
        }
        return if (feet > 0) "$sign$feet' $inchPart" else "$sign$inchPart"
    }

    /** Formats an entered value from its exact representation in its own unit, without re-rounding drift. */
    fun formatEntered(v: EnteredValue): String = v.text

    fun formatArea(sqMeters: Double, units: DisplayUnits): String = when (units) {
        DisplayUnits.METRIC -> String.format(java.util.Locale.US, "%.2f m²", sqMeters)
        DisplayUnits.IMPERIAL -> String.format(java.util.Locale.US, "%.1f ft²", sqMeters / (0.3048 * 0.3048))
    }

    fun formatDegrees(deg: Double): String = String.format(java.util.Locale.US, "%.1f°", deg)

    fun floorDiv(a: Double, b: Double) = floor(a / b)
}
