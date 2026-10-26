package com.owlacoustics.domain.units

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext

/** Exact rational number used to preserve entered decimals and fractions without rounding drift. */
class Rational private constructor(val num: BigInteger, val den: BigInteger) : Comparable<Rational> {

    operator fun plus(o: Rational) = of(num * o.den + o.num * den, den * o.den)
    operator fun minus(o: Rational) = of(num * o.den - o.num * den, den * o.den)
    operator fun times(o: Rational) = of(num * o.num, den * o.den)
    operator fun div(o: Rational): Rational {
        require(o.num.signum() != 0) { "division by zero" }
        return of(num * o.den, den * o.num)
    }
    operator fun unaryMinus() = of(-num, den)

    val signum: Int get() = num.signum()

    fun toDouble(): Double = BigDecimal(num).divide(BigDecimal(den), MathContext.DECIMAL128).toDouble()

    override fun compareTo(other: Rational): Int = (num * other.den).compareTo(other.num * den)
    override fun equals(other: Any?) = other is Rational && num == other.num && den == other.den
    override fun hashCode() = 31 * num.hashCode() + den.hashCode()
    override fun toString() = if (den == BigInteger.ONE) "$num" else "$num/$den"

    companion object {
        val ZERO = of(BigInteger.ZERO, BigInteger.ONE)
        val ONE = of(BigInteger.ONE, BigInteger.ONE)

        fun of(n: BigInteger, d: BigInteger): Rational {
            require(d.signum() != 0) { "zero denominator" }
            var nn = n
            var dd = d
            if (dd.signum() < 0) { nn = -nn; dd = -dd }
            val g = nn.gcd(dd)
            return if (g.signum() == 0) Rational(BigInteger.ZERO, BigInteger.ONE) else Rational(nn / g, dd / g)
        }

        fun of(n: Long, d: Long = 1) = of(BigInteger.valueOf(n), BigInteger.valueOf(d))

        /** Parses a plain decimal such as "12", "12.375", ".5" exactly. Returns null for anything else. */
        fun parseDecimal(text: String): Rational? {
            val t = text.trim()
            if (t.isEmpty() || !Regex("""[+-]?(\d+(\.\d*)?|\.\d+)""").matches(t)) return null
            val bd = BigDecimal(t)
            return if (bd.scale() > 0) of(bd.unscaledValue(), BigInteger.TEN.pow(bd.scale()))
            else of(bd.toBigIntegerExact(), BigInteger.ONE)
        }

        fun parse(n: String, d: String) = of(BigInteger(n), BigInteger(d))
    }
}
