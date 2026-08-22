package dev.aster.vega.expression

import dev.aster.vega.model.Decimals
import dev.aster.vega.model.locale.VegaLocale
import dev.aster.vega.model.roundHalfUp
import io.github.mgilbir.ecma262.number.toEcmaString
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * d3-format's specifier language, whole.
 *
 * This used to be a *subset* — `d`, `f`, `e`, `g`, `%` and a grouping comma — and the subset was
 * visible from outside: `format(x, "s")` fell through to plain number text, and a specifier with a
 * width or a sign was rejected the same way. Replaying d3's own corpus counted the gap at **122
 * vectors asking for `s` and 71 for `d` variants this grammar would not accept**, which is not a
 * rounding error in coverage; it is every axis that wants "1.2M" instead of "1200000".
 *
 * So the grammar is transcribed rather than approximated:
 * ```
 * [[fill]align][sign][symbol][0][width][,][.precision][~][type]
 * ```
 *
 * with every type d3 has — `e f g r s % p b o d x X c n` and the typeless form that means `.12~g`.
 * The pieces that look decorative are the ones that carry meaning:
 *
 * - **`align`** decides where padding goes, and `=` puts it *between* the sign and the digits,
 *   which is what makes a column of signed numbers line up.
 * - **`sign`** has four forms, and `(` writes a negative in parentheses, as an accountant does.
 * - **`symbol`** is `$` for currency or `#` for a radix prefix — `#x` writes `0xff`.
 * - **`s`** carries an SI prefix chosen from the value's own magnitude, and the prefix is part of
 *   the *suffix*, so it survives trimming and lands outside the grouped digits.
 * - **grouping** happens before padding unless the fill is `0`, in which case it happens after, so
 *   `08,d` of 1234 is `0,001,234` rather than `0001,234`.
 *
 * Numbers are formatted through [Decimals], which expands the double exactly, so the rounding here
 * is JavaScript's rather than the platform's.
 */
public object NumberFormat {

  /** A parsed specifier. Every field is d3's, including the defaults it fills in. */
  public data class Spec(
    val fill: Char = ' ',
    val align: Char = '>',
    val sign: Char = '-',
    val symbol: Char? = null,
    val zero: Boolean = false,
    val width: Int? = null,
    val comma: Boolean = false,
    val precision: Int? = null,
    val trim: Boolean = false,
    val type: Char? = null,
  )

  private val PATTERN =
    Regex("""^(?:(.)?([<>=^]))?([+\-( ])?([$#])?(0)?(\d+)?(,)?(\.\d+)?(~)?([a-zA-Z%])?$""")

  /** The SI prefixes, from yocto to yotta; the empty one at index 8 is "no prefix". */
  private val PREFIXES =
    listOf("y", "z", "a", "f", "p", "n", "µ", "m", "", "k", "M", "G", "T", "P", "E", "Z", "Y")

  private const val MINUS = "−"

  /** The types d3 knows. Anything else is the typeless form, `.12~g`. */
  private const val KNOWN_TYPES = "efgrs%pbodxXc"

  public fun parse(specifier: String): Spec? {
    val match = PATTERN.matchEntire(specifier) ?: return null
    // Ten groups, in the grammar's own order: fill, align, sign, symbol, zero, width, comma,
    // precision, trim, type. Read by index rather than destructured, because a ten-way
    // destructuring
    // needs a carrier type and the carrier is worth less than the indices are clear.
    val groups = match.groupValues
    return Spec(
      fill = groups[1].firstOrNull() ?: ' ',
      align = groups[2].firstOrNull() ?: '>',
      sign = groups[3].firstOrNull() ?: '-',
      symbol = groups[4].firstOrNull(),
      zero = groups[5].isNotEmpty(),
      width = groups[6].takeIf { it.isNotEmpty() }?.toIntOrNull(),
      comma = groups[7].isNotEmpty(),
      precision = groups[8].takeIf { it.isNotEmpty() }?.drop(1)?.toIntOrNull(),
      trim = groups[9].isNotEmpty(),
      type = groups[10].firstOrNull(),
    )
  }

  /** Formats [value] with [specifier], as `d3.format` does. */
  public fun format(
    value: Double,
    specifier: String,
    locale: VegaLocale = VegaLocale.EnglishUS,
  ): String {
    val parsed =
      parse(specifier)
        ?: return digits(
          withTypographicMinus(JsSemantics.numberToString(value), locale.minus),
          locale,
        )
    return format(value, parsed, locale = locale)
  }

  /**
   * The host's numbering system, applied to a formatted number.
   *
   * At the very end, so grouping, padding, the decimal separator and any currency or SI affix are
   * already in place: this transliterates digits and cannot move a separator or change a width,
   * both of which the specification's own format decided. A `$`, a `%` or an SI `M` carries no
   * digits and is therefore untouched by a rule that maps digits.
   */
  private fun digits(text: String, locale: VegaLocale): String = locale.rules?.digits(text) ?: text

  /**
   * Formats [value] with an already-parsed specifier, and an [extra] suffix that participates in
   * padding.
   *
   * The suffix is a parameter rather than something the caller appends because d3 passes it *into*
   * the formatter: `formatPrefix(" $12,.1s", 1e6)(-4.2e7)` is twelve characters wide **including**
   * the `M`, and `formatPrefix("($~s", 1000)(-1000)` writes `($1k)` with the prefix inside the
   * parentheses. Appending afterwards gets both wrong.
   */
  public fun format(
    value: Double,
    requested: Spec,
    extra: String = "",
    locale: VegaLocale = VegaLocale.EnglishUS,
  ): String {
    var spec = requested
    var type = spec.type
    var comma = spec.comma
    var trim = spec.trim
    var precision = spec.precision

    when {
      // `n` is `,g` and nothing else.
      type == 'n' -> {
        comma = true
        type = 'g'
      }
      // No type — or one d3 does not know — means `.12~g`: twelve significant digits, trimmed.
      type == null || type !in KNOWN_TYPES -> {
        if (precision == null) precision = 12
        trim = true
        type = 'g'
      }
    }
    // Zero fill puts the padding after the sign and before the digits.
    var fill = spec.fill
    var align = spec.align
    var zero = spec.zero
    if (zero || (fill == '0' && align == '=')) {
      zero = true
      fill = '0'
      align = '='
    }
    spec = spec.copy(fill = fill, align = align, zero = zero)

    // A significant-digit type takes [1, 21]; a fixed one takes [0, 20]. The cap is JavaScript's
    // own.
    precision =
      when {
        precision == null -> 6
        type in "gprs" -> max(1, min(21, precision))
        else -> max(0, min(20, precision))
      }

    var prefix =
      when {
        spec.symbol == '$' -> "$"
        spec.symbol == '#' && type in "boxX" -> "0" + type.lowercaseChar()
        else -> ""
      }
    var suffix = (if (type == '%' || type == 'p') "%" else "") + extra

    var body: String
    if (type == 'c') {
      suffix = JsSemantics.numberToString(value) + suffix
      body = ""
    } else {
      // `-0` is not less than zero, but one divided by it is.
      var negative = value < 0 || 1.0 / value < 0
      var prefixExponent: Int? = null
      body =
        if (value.isNaN()) {
          "NaN"
        } else {
          val magnitude = abs(value)
          when (type) {
            '%' -> Decimals.fixed(magnitude * 100.0, precision)
            'b' -> wholeInRadix(magnitude, 2)
            'c' -> JsSemantics.numberToString(magnitude)
            'd' -> wholeNumber(magnitude)
            'e' -> Decimals.exponential(magnitude, precision)
            'f' -> Decimals.fixed(magnitude, precision)
            'g' -> Decimals.significant(magnitude, precision)
            'o' -> wholeInRadix(magnitude, 8)
            'p' -> rounded(magnitude * 100.0, precision)
            'r' -> rounded(magnitude, precision)
            's' -> {
              val (text, exponent) = siPrefixed(magnitude, precision)
              prefixExponent = exponent
              text
            }
            'x' -> wholeInRadix(magnitude, 16)
            'X' -> wholeInRadix(magnitude, 16).uppercase()
            else -> Decimals.fixed(magnitude, precision)
          }
        }
      if (trim) body = trimInsignificant(body)
      // A negative that rounds away to nothing loses its sign, unless one was asked for.
      if (negative && body.toDoubleOrNull() == 0.0 && spec.sign != '+') negative = false

      prefix =
        (if (negative) {
          if (spec.sign == '(') "(" else locale.minus
        } else if (spec.sign == '-' || spec.sign == '(') {
          ""
        } else {
          spec.sign.toString()
        }) + prefix
      suffix =
        (if (type == 's' && prefixExponent != null) PREFIXES[8 + prefixExponent / 3] else "") +
          suffix +
          (if (negative && spec.sign == '(') ")" else "")

      // Only the leading digits are grouped and padded; a fraction or an exponent goes to the
      // suffix. The **decimal separator** is substituted here, at the split, which is where d3 does
      // it: the fraction leaves the numeric part at this point, so this is the last moment anything
      // knows which `.` is a decimal point and which belongs to an exponent's `e-3`.
      if (type in "defgprs%") {
        val split = body.indexOfFirst { it < '0' || it > '9' }
        if (split >= 0) {
          suffix =
            (if (body[split] == '.') locale.decimal + body.substring(split + 1)
            else body.substring(split)) + suffix
          body = body.substring(0, split)
        }
      }
    }

    if (comma && !spec.zero) body = group(body, Int.MAX_VALUE, locale)

    val length = prefix.length + body.length + suffix.length
    var padding =
      if (spec.width != null && length < spec.width) fill.toString().repeat(spec.width - length)
      else ""

    if (comma && spec.zero) {
      body =
        group(
          padding + body,
          if (padding.isNotEmpty()) (spec.width ?: 0) - suffix.length else Int.MAX_VALUE,
          locale,
        )
      padding = ""
    }

    // The host's numbering system last of all, so it sees the number the specification asked for
    // and can only change which digits write it.
    return digits(
      when (spec.align) {
        '<' -> prefix + body + suffix + padding
        '=' -> prefix + padding + body + suffix
        '^' -> {
          val half = padding.length / 2
          padding.substring(0, half) + prefix + body + suffix + padding.substring(half)
        }
        else -> padding + prefix + body + suffix
      },
      locale,
    )
  }

  /**
   * The decimal exponent of [value] — the `n` in `d.ddd × 10^n` — exactly.
   *
   * `floor(log10(x))` is the obvious way to get this and is wrong at the decade boundaries, which
   * are precisely the values a tick step lands on: `log10(1e-7)` is not `-7` in binary floating
   * point. d3 reads the exponent off `toExponential()` instead, and [Decimals.shortest] is that
   * same question already answered.
   */
  private fun decimalExponent(value: Double): Int = Decimals.shortest(value).exponent - 1

  /** d3's `precisionFixed`: decimals enough to tell values [step] apart, for `f` and `%`. */
  public fun precisionFixed(step: Double): Int {
    if (step == 0.0 || !step.isFinite()) return 0
    return max(0, -decimalExponent(abs(step)))
  }

  /**
   * d3's `precisionRound`: **significant** digits enough to tell values [step] apart at
   * [magnitude].
   *
   * The `+ 1` is not a fudge: telling `1.02` from `1.03` at a step of `0.01` needs three digits,
   * one more than the two decades between them.
   */
  public fun precisionRound(step: Double, magnitude: Double): Int {
    if (step == 0.0 || !step.isFinite() || !magnitude.isFinite()) return 0
    // `abs(max) - step`, and the subtraction is the whole subtlety: the largest value an axis
    // actually *labels* is a step below its bound, so `precisionRound(0.01, 1)` is 2 rather than 3
    // — the digits needed for 0.99, not for 1.00. Dropping it puts an extra decimal on every `g`,
    // `p`, `r` and `e` axis.
    val reach = abs(abs(magnitude) - abs(step))
    val distance = decimalExponent(reach) - decimalExponent(abs(step))
    return max(0, distance) + 1
  }

  /**
   * d3's `precisionPrefix`: decimals enough to tell values [step] apart *once an SI prefix has been
   * applied*, which is what makes an axis over two million read `0.5M` rather than `0M`.
   */
  public fun precisionPrefix(step: Double, reference: Double): Int {
    if (step == 0.0 || !step.isFinite() || !reference.isFinite()) return 0
    return max(0, prefixExponentFor(reference) - decimalExponent(abs(step)))
  }

  /** The SI prefix's exponent for a magnitude: a multiple of three, clamped to yocto…yotta. */
  private fun prefixExponentFor(reference: Double): Int =
    max(-8, min(8, floor(decimalExponent(abs(reference)) / 3.0).toInt())) * 3

  /**
   * d3's `formatPrefix`: a formatter whose SI prefix is fixed by [reference] rather than chosen per
   * value.
   *
   * This is the difference between an axis that reads `0.5M | 1.0M | 1.5M | 2.0M` and one that
   * reads `500k | 1M | 1.5M | 2M`, and it cannot be expressed as a specifier string — the prefix is
   * a property of the *span*, not of the number. d3 achieves it by rewriting the type to `f`,
   * scaling every value by the prefix's power of ten, and appending the prefix itself.
   */
  public fun prefixed(
    spec: Spec,
    reference: Double,
    locale: VegaLocale = VegaLocale.EnglishUS,
  ): (Double) -> String {
    val exponent = prefixExponentFor(reference)
    val scale = 10.0.pow(-exponent)
    val prefix = PREFIXES[8 + exponent / 3]
    val fixed = spec.copy(type = 'f')
    return { value -> format(scale * value, fixed, prefix, locale) }
  }

  /**
   * The shortest decimal digits of [value] and the exponent that places them, or null for a
   * non-finite value.
   *
   * d3's `formatDecimalParts`: with a precision it asks for that many significant digits, and
   * without one it asks for the fewest that identify the double.
   */
  private fun decimalParts(value: Double, precision: Int?): Pair<String, Int>? {
    if (!value.isFinite()) return null
    // `p ? x.toExponential(p - 1) : x.toExponential()` — a precision of **zero is falsy** in
    // JavaScript, so it asks for the shortest form rather than for minus-one digits. Passing the
    // zero through crashed the expansion, and `siPrefixed` reaches it: its fallback asks for
    // `max(0, precision + index - 1)` digits.
    val text =
      if (precision != null && precision > 0) Decimals.exponential(value, precision - 1)
      else shortestExponential(value)
    val marker = text.indexOf('e')
    if (marker < 0) return null
    val coefficient = text.substring(0, marker)
    val digits =
      if (coefficient.length > 1) coefficient[0] + coefficient.substring(2) else coefficient
    return digits to (text.substring(marker + 1).removePrefix("+").toIntOrNull() ?: 0)
  }

  /**
   * JavaScript's `toExponential()` with no argument: the fewest digits that identify the double.
   */
  private fun shortestExponential(value: Double): String {
    if (value == 0.0) return "0e+0"
    // Asked of `Decimals` rather than re-derived from the printed form: this used to read
    // `numberToString` back apart, which meant it inherited that function's notation threshold and
    // could not see a digit the printer had already rounded away.
    val shortest = Decimals.shortest(value)
    val digits = shortest.digits
    val exponent = shortest.exponent - 1
    val mantissa = if (digits.length > 1) "${digits[0]}.${digits.substring(1)}" else digits
    return (if (value < 0) "-" else "") +
      mantissa +
      "e" +
      (if (exponent < 0) "-" else "+") +
      abs(exponent)
  }

  /** d3's `formatRounded`: [precision] significant digits, never in exponential notation. */
  private fun rounded(value: Double, precision: Int): String {
    val parts = decimalParts(value, precision) ?: return JsSemantics.numberToString(value)
    val (digits, exponent) = parts
    return when {
      exponent < 0 -> "0." + "0".repeat(-exponent - 1) + digits
      digits.length > exponent + 1 ->
        digits.substring(0, exponent + 1) + "." + digits.substring(exponent + 1)
      else -> digits + "0".repeat(exponent - digits.length + 2 - 1)
    }
  }

  /**
   * d3's `formatPrefixAuto`: the digits for an SI-prefixed number, and the prefix's exponent.
   *
   * The exponent is a multiple of three chosen from the value's own magnitude, which is what turns
   * 1,200,000 into "1.2M" — and it is returned rather than appended, because the prefix belongs to
   * the *suffix* and has to survive trimming and stay outside the grouped digits.
   */
  private fun siPrefixed(value: Double, precision: Int): Pair<String, Int?> {
    val parts =
      decimalParts(value, precision) ?: return Decimals.significant(value, precision) to null
    val (coefficient, exponent) = parts
    val prefixExponent = max(-8, min(8, floor(exponent / 3.0).toInt())) * 3
    val index = exponent - prefixExponent + 1
    val length = coefficient.length
    val text =
      when {
        index == length -> coefficient
        index > length -> coefficient + "0".repeat(index - length)
        index > 0 -> coefficient.substring(0, index) + "." + coefficient.substring(index)
        else -> {
          val smaller = decimalParts(value, max(0, precision + index - 1))?.first ?: coefficient
          "0." + "0".repeat(-index) + smaller
        }
      }
    return text to prefixExponent
  }

  /** `b`, `o`, `x` and `X`: the rounded value in that radix, as JavaScript's `toString(radix)`. */
  private fun wholeInRadix(value: Double, radix: Int): String {
    val rounded = roundHalfUp(value)
    if (!rounded.isFinite()) return JsSemantics.numberToString(rounded)
    // `Number.prototype.toString(radix)`, from the library: `toLong().toString(radix)` agreed for
    // every value a format string reaches but is not the same function — it saturates at `Long`'s
    // range where the specification keeps going, the same way `toLong()` once broke `String(x)`.
    return rounded.toEcmaString(radix)
  }

  /**
   * A whole number written out, the way d3's `formatDecimal` writes one.
   *
   * Anything from 1e21 up goes through the **shortest** representation rather than the double's
   * exact value: 1.3e27 is `1300000000000000000000000000` upstream, where that double's exact value
   * is `1300000000000000044761612288`.
   */
  private fun wholeNumber(value: Double): String {
    val rounded = roundHalfUp(value)
    if (!rounded.isFinite()) return Decimals.fixed(rounded, 0)
    if (abs(rounded) < 1e21) return Decimals.fixed(rounded, 0)
    val shortest = JsSemantics.numberToString(rounded)
    val marker = shortest.indexOf('e')
    if (marker < 0) return shortest
    val exponent = shortest.substring(marker + 1).removePrefix("+").toIntOrNull() ?: return shortest
    val mantissa = shortest.substring(0, marker)
    val negative = mantissa.startsWith("-")
    val bare = mantissa.removePrefix("-")
    val digits = bare.replace(".", "")
    val pointAt = bare.indexOf('.').let { if (it < 0) 1 else it }
    return (if (negative) "-" else "") +
      digits +
      "0".repeat(max(0, exponent - (digits.length - pointAt)))
  }

  /**
   * d3's `formatTrim`: drops insignificant zeros, keeping whatever follows them.
   *
   * Transcribed rather than written afresh because it has to leave an exponent alone — `1.2000e+3`
   * trims to `1.2e+3`, not to `1.2` — and because "insignificant" means *after a point*, so `1200`
   * keeps its zeros.
   */
  private fun trimInsignificant(text: String): String {
    var pointOrFirstZero = -1
    var lastZero = 0
    var index = 1
    while (index < text.length) {
      when (text[index]) {
        '.' -> {
          pointOrFirstZero = index
          lastZero = index
        }
        '0' -> {
          if (pointOrFirstZero == 0) pointOrFirstZero = index
          lastZero = index
        }
        else -> {
          if (!text[index].isDigit()) break
          if (pointOrFirstZero > 0) pointOrFirstZero = 0
        }
      }
      index++
    }
    return if (pointOrFirstZero > 0)
      text.substring(0, pointOrFirstZero) + text.substring(lastZero + 1)
    else text
  }

  /**
   * Group separators, right to left, with d3's width limit for the zero-fill case.
   *
   * The group **sizes** come from the locale and the last one repeats, which is d3's own walk:
   * `[3]` never changes and `[3, 2]` gives thousands, hundreds of thousands, then lakhs. The width
   * limit is the zero-fill rule — a `08,d` is eight characters *including* its separators — so the
   * last group is narrowed rather than overflowing the field.
   */
  private fun group(value: String, width: Int, locale: VegaLocale): String {
    val pieces = mutableListOf<String>()
    var index = value.length
    var length = 0
    var group = 0
    var size = locale.grouping[0]
    while (index > 0 && size > 0) {
      if (length + size + 1 > width) size = max(1, width - length)
      pieces += value.substring(max(0, index - size), index)
      index -= size
      length += size + 1
      if (length > width) break
      group++
      size = locale.grouping[min(group, locale.grouping.size - 1)]
    }
    return pieces.reversed().joinToString(locale.thousands)
  }

  /**
   * d3 writes a negative with U+2212 by default, which is a different glyph from the hyphen an
   * exponent uses — and which a locale may replace.
   */
  private fun withTypographicMinus(text: String, minus: String = MINUS): String =
    if (text.startsWith("-")) minus + text.substring(1) else text
}
