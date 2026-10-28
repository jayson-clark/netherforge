package dev.netherforge.plugin.lua

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * A float as the prelude's Lua↔JSON codec writes it (`json_number` in
 * `prelude/json.lua`), so JSON Kotlin reads from Lua values itself
 * ([LuaHost.fieldsJson], [LuaHost.json]) is the codec's very text: `%.14g`,
 * or `%.17g` when that doesn't read back as the same number, and a `.0` when
 * neither has a point or an exponent, so it comes back a float.
 *
 * `%g` is the C library's: the exact binary value rounded to [precision]
 * significant digits (half to even, as glibc and macOS round), fixed notation
 * when the exponent is from -4 to below the precision and scientific
 * otherwise, trailing zeros dropped. `CodecNumbersTest` holds it to Lua's own
 * `string.format`.
 */
object CodecNumbers {
    /** [value]'s JSON text as the codec writes a float, or null for one JSON can't hold (NaN, infinities). */
    fun json(value: Double): String? {
        if (!value.isFinite()) return null
        var text = g(value, 14)
        if (text.toDouble() != value) text = g(value, 17)
        return if (text.none { it == '.' || it == 'e' || it == 'E' || it == 'n' }) "$text.0" else text
    }

    /** C's `%.<precision>g` of a finite [value]. */
    fun g(value: Double, precision: Int): String {
        if (value == 0.0) return if (1.0 / value < 0) "-0" else "0"
        val rounded = BigDecimal(value).round(MathContext(precision, RoundingMode.HALF_EVEN))
        // The exponent of the leading digit, once rounded (9.99…5 can round up to 10).
        val exponent = rounded.precision() - rounded.scale() - 1
        if (exponent < -4 || exponent >= precision) {
            val digits = rounded.unscaledValue().abs().toString().trimEnd('0')
            val mantissa = if (digits.length > 1) "${digits[0]}.${digits.substring(1)}" else digits
            val sign = if (rounded.signum() < 0) "-" else ""
            val power = if (exponent < 0) "-" else "+"
            return "$sign${mantissa}e$power${Math.abs(exponent).toString().padStart(2, '0')}"
        }
        val fixed = rounded.setScale(maxOf(precision - 1 - exponent, 0), RoundingMode.HALF_EVEN).toPlainString()
        return if ('.' in fixed) fixed.trimEnd('0').trimEnd('.') else fixed
    }
}
