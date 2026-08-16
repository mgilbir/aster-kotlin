package dev.aster.vega.model

/**
 * Fixed-precision decimal formatting, in common Kotlin and with no platform seam.
 *
 * Rounding a decimal at *N* places has to round the double's **exact** binary value, not its
 * shortest printable form. The two disagree: `2.675` is stored as `2.67499999999999982…`, so `%.2f`
 * gives `2.67` while rounding the string `"2.675"` gives `2.68`. JavaScript's `toFixed` rounds the
 * exact value, so d3 does, so this engine must.
 *
 * That used to be the argument for a `BigDecimal` — and for the core's one non-portable file. It is
 * not, and the reason is a fact about binary floating point rather than a trick: a finite double is
 * *exactly* `m × 2^e` for a 53-bit integer `m`, so its decimal expansion is **finite**. For a
 * negative exponent, `m × 2^-k` is `(m × 5^k) × 10^-k` — an integer with the point moved `k` places
 * — and `k` is at most 1074, so the widest expansion any double has is around 767 digits. Producing
 * it needs one operation this file implements in eighty lines: multiplying a natural number by a
 * value that fits in a word.
 *
 * So there is no arbitrary-precision *library* here and no arithmetic beyond schoolbook multiply,
 * shift and divide-by-small. What there is instead:
 * - [roundedScaled], which is what [fixed] and [trimmed] are. Rounding `|v|` at *N* places is
 *   `round(|v| × 10^N)`, and `|v| × 10^N = (m × 5^N) × 2^(N-k)` — so when `N ≥ k` it is an exact
 *   integer with nothing to round, and otherwise the discarded part is a run of **bits**. Half-up
 *   is then a single bit test: the remainder reaches half exactly when the topmost discarded bit is
 *   set. `5^N` for a plausible *N* is a handful of words, which is why the common path stays cheap.
 * - [expansion], the full exact digit string, which [exponential] and [significant] round *as text*
 *   — correct precisely because the text is exact rather than shortest.
 *
 * Verified the only way this can be: `DecimalsTest` runs both against `java.math.BigDecimal` over a
 * hundred thousand random doubles and every awkward case anyone has written down. Keeping that
 * oracle in a test is the point — the engine no longer contains it.
 *
 * Non-finite values never arrive here; every caller spells `NaN` and `Infinity` itself, because d3
 * writes them the way JavaScript does and that is a formatting decision rather than an arithmetic
 * one. They are answered rather than thrown on anyway, since a formatter that throws would take a
 * whole chart down for one bad datum.
 */
public object Decimals {

  /** `%.Nf`: [decimals] places, half-up on the exact value, no thousands separators. */
  public fun fixed(value: Double, decimals: Int): String {
    nonFinite(value)?.let {
      return it
    }
    val digits = roundedScaled(value, decimals)
    val text = withPoint(digits, decimals)
    // A magnitude that rounds away to nothing loses its sign, which is what `BigDecimal` does: it
    // has no negative zero, so `-0.001` at two places is `0.00` rather than `-0.00`.
    return if (isNegative(value) && digits.any { it != '0' }) "-$text" else text
  }

  /**
   * JavaScript's `toExponential`: scientific notation with [decimals] places after the point.
   *
   * The exponent carries a sign and no padding — `5e-3`, never `5e-03` — because that is what
   * JavaScript writes and d3 formats an `e` by calling `toExponential` directly.
   */
  public fun exponential(value: Double, decimals: Int): String {
    nonFinite(value)?.let {
      return it
    }
    val keep = decimals + 1
    val (digits, exponent) =
      if (value == 0.0) "0".repeat(keep) to 0
      else {
        val expansion = expansion(value)
        val rounded = round(expansion.digits, keep)
        rounded.digits to (expansion.exponent + if (rounded.carried) 1 else 0)
      }
    val mantissa =
      if (decimals > 0) "${digits[0]}.${digits.substring(1)}" else digits.substring(0, 1)
    val sign = if (exponent < 0) "-" else "+"
    return (if (isNegative(value)) "-" else "") + mantissa + "e" + sign + abs(exponent)
  }

  /**
   * JavaScript's `toPrecision`: [digits] significant digits.
   *
   * The notation switch is the part worth writing down, because it is not "whichever is shorter":
   * JavaScript goes exponential when the decimal exponent is below -7 or at least [digits], and
   * fixed otherwise. That is what makes `1e-7` print as `1e-7` while `0.000001234` prints in full,
   * and what makes a fifteen-digit integer come out as `1.23456789012e+14` at twelve digits.
   */
  public fun significant(value: Double, digits: Int): String {
    if (value == 0.0 || !value.isFinite()) return fixed(value, digits - 1)
    val expansion = expansion(value)
    // Taken *after* rounding, because rounding 9.99 to two digits moves it into the next decade.
    val rounded = round(expansion.digits, digits)
    val exponent = expansion.exponent + if (rounded.carried) 1 else 0
    return if (exponent < -6 || exponent >= digits) {
      exponential(value, digits - 1)
    } else {
      fixed(value, digits - 1 - exponent)
    }
  }

  /**
   * [fixed] with trailing zeros and a trailing point removed, so `1.0` and `1` agree.
   *
   * This is what canonical snapshots and SVG attributes are written with, where two runs that mean
   * the same number have to produce the same bytes.
   */
  public fun trimmed(value: Double, decimals: Int): String {
    val text = fixed(value, decimals)
    if ('.' !in text) return text
    return text.trimEnd('0').trimEnd('.')
  }

  /**
   * The **fewest** digits that read back as this double, and where the point goes.
   *
   * This is the other half of printing a number, and the half that is not obvious. [fixed] and
   * [exponential] are told how many digits to write; `String(x)` is not, and JavaScript's answer is
   * *the shortest decimal that no other double is nearer to* — which is why `0.1 + 0.2` prints as
   * `0.30000000000000004` (seventeen digits are genuinely needed) while `0.3` prints as `0.3`.
   *
   * The platform's own `toString` is not a substitute, on two counts. It writes at least two
   * significant digits, so `Double.MIN_VALUE` comes out as `4.9E-324` where `5e-324` reads back to
   * the same bits and is what JavaScript prints. And it switches to exponential notation at 10^7,
   * where JavaScript switches at 10^21 — a difference that shows up on any axis label past ten
   * million.
   *
   * Finding the count is a search rather than an algorithm here, and it is cheap because the
   * expansion is already exact: rounding it to *k* digits and asking whether that reads back is a
   * string round-trip, and round-tripping is **monotone** in *k* — a longer decimal is never
   * further from the double than a shorter one — so the fewest that work can be halved out in five
   * tries. Seventeen always work, which is what makes the search terminate.
   */
  public fun shortest(value: Double): Shortest {
    val magnitude = kotlin.math.abs(value)
    val expansion = expansion(magnitude)
    var low = 1
    var high = 17
    while (low < high) {
      val mid = (low + high) / 2
      if (readsBack(expansion, mid, magnitude)) high = mid else low = mid + 1
    }
    val rounded = round(expansion.digits, low, tieToEven = true)
    val exponent = expansion.exponent + if (rounded.carried) 1 else 0
    val digits = rounded.digits.trimEnd('0').ifEmpty { "0" }
    return Shortest(digits, exponent + 1)
  }

  /**
   * `String(x)`: [shortest]'s digits with ECMA-262's point placement.
   *
   * Plain decimal notation holds from 10^-6 up to 10^21 and exponential takes over outside it,
   * which is not where Kotlin's own `toString` switches.
   */
  public fun jsString(value: Double): String =
    when {
      value.isNaN() -> "NaN"
      value == Double.POSITIVE_INFINITY -> "Infinity"
      value == Double.NEGATIVE_INFINITY -> "-Infinity"
      value == 0.0 -> "0"
      // Up to 2^53 an integral double *is* one integer and prints as itself, which is the common
      // case and costs a single conversion. Past that it is not: 2^53 + 2 is stored exactly and
      // JavaScript still writes the shortest decimal that names it, so the general path takes over.
      // The bound was 1e21 once and was wrong twice — `toLong` saturates at 9.2e18, so every
      // integral double above that printed as `-9223372036854775808`.
      value == kotlin.math.floor(value) && kotlin.math.abs(value) <= 9007199254740992.0 ->
        value.toLong().toString()
      else -> {
        val shortest = shortest(value)
        (if (value < 0) "-" else "") + place(shortest.digits, shortest.exponent)
      }
    }

  /** Where ECMA-262 puts the point, once [shortest] has settled what the digits are. */
  private fun place(digits: String, exponent: Int): String {
    val count = digits.length
    return when {
      exponent in count..21 -> digits + "0".repeat(exponent - count)
      exponent in 1..21 -> digits.substring(0, exponent) + "." + digits.substring(exponent)
      exponent in -5..0 -> "0." + "0".repeat(-exponent) + digits
      else -> {
        val mantissa = if (count == 1) digits else "${digits[0]}.${digits.substring(1)}"
        mantissa + "e" + (if (exponent > 21) "+" else "-") + kotlin.math.abs(exponent - 1)
      }
    }
  }

  /** The value is `0.digits × 10^exponent`, with [digits] carrying no leading or trailing zero. */
  public class Shortest internal constructor(public val digits: String, public val exponent: Int)

  /** Whether [expansion] rounded to [keep] digits still names the same double. */
  private fun readsBack(expansion: Expansion, keep: Int, magnitude: Double): Boolean {
    val rounded = round(expansion.digits, keep, tieToEven = true)
    val exponent = expansion.exponent + if (rounded.carried) 1 else 0
    // Written with the point after every digit rather than after the first, so the text stays a
    // plain integer-and-exponent that every platform's parser reads the same way.
    return "${rounded.digits}e${exponent - keep + 1}".toDouble() == magnitude
  }

  /**
   * The digits of `round(|value| × 10^decimals)`, half-up on the exact binary value.
   *
   * `|value| = m × 2^-k` for a negative binary exponent, so `|value| × 10^decimals` is `(m ×
   * 5^decimals) × 2^(decimals - k)`: a left shift when `decimals ≥ k`, and otherwise a right shift
   * whose discarded bits decide the rounding. `m × 5^decimals` is small for any precision a format
   * string can ask for, which keeps the common case to a few words of arithmetic.
   */
  private fun roundedScaled(value: Double, decimals: Int): String {
    val bits = value.toRawBits()
    val exponentBits = ((bits ushr 52) and 0x7FF).toInt()
    val fraction = bits and 0x000FFFFFFFFFFFFFL
    // A subnormal has no implicit leading one, and its exponent is the smallest normal's.
    val significand = if (exponentBits == 0) fraction else fraction or (1L shl 52)
    if (significand == 0L) return "0"
    val binaryExponent = if (exponentBits == 0) -1074 else exponentBits - 1075

    val natural = Natural(significand)
    if (binaryExponent >= 0) {
      natural.shiftLeft(binaryExponent)
      natural.multiplyPowerOfTen(decimals)
    } else {
      natural.multiplyPowerOfFive(decimals)
      val shift = -binaryExponent - decimals
      if (shift <= 0) {
        natural.shiftLeft(-shift)
      } else {
        // Everything below `shift` is discarded, so the remainder reaches half exactly when the
        // topmost discarded bit is set — and half-up rounds up on the tie as well as above it.
        val roundUp = natural.bit(shift - 1)
        natural.shiftRight(shift)
        if (roundUp) natural.increment()
      }
    }
    return natural.toDecimalString()
  }

  /** `|value|` as `d.ddd… × 10^exponent`, exactly and in full. */
  private fun expansion(value: Double): Expansion {
    val bits = value.toRawBits()
    val exponentBits = ((bits ushr 52) and 0x7FF).toInt()
    val fraction = bits and 0x000FFFFFFFFFFFFFL
    val significand = if (exponentBits == 0) fraction else fraction or (1L shl 52)
    if (significand == 0L) return Expansion("0", 0)
    val binaryExponent = if (exponentBits == 0) -1074 else exponentBits - 1075

    val natural = Natural(significand)
    return if (binaryExponent >= 0) {
      natural.shiftLeft(binaryExponent)
      val digits = natural.toDecimalString()
      Expansion(digits, digits.length - 1)
    } else {
      // `m × 2^-k` is `(m × 5^k) × 10^-k`: the same digits with the point moved k places left.
      val places = -binaryExponent
      natural.multiplyPowerOfFive(places)
      val digits = natural.toDecimalString()
      Expansion(digits, digits.length - 1 - places)
    }
  }

  /** An exact expansion: `digits[0].digits[1..] × 10^exponent`, with no leading zero. */
  private class Expansion(val digits: String, val exponent: Int)

  /**
   * [digits] rounded to [keep] of them, and whether that carried into a new decade.
   *
   * Half-up, except when [tieToEven] — which only [shortest] asks for, because that is the one
   * place the language rounds differently. `toFixed` and `toExponential` round a tie away from
   * zero; `String(x)` breaks it towards the **even** digit, so `170255292857.578125` prints as
   * `170255292857.57812` rather than `…13`, both of which read back to the same double.
   */
  private fun round(digits: String, keep: Int, tieToEven: Boolean = false): Rounded {
    if (digits.length <= keep) return Rounded(digits.padEnd(keep, '0'), carried = false)
    // The expansion is exact, so the first discarded digit decides it: five or more rounds up, and
    // a tie — five followed by nothing — rounds up too, which is what half-up means.
    if (digits[keep] < '5') return Rounded(digits.substring(0, keep), carried = false)
    if (
      tieToEven &&
        digits[keep] == '5' &&
        digits.asSequence().drop(keep + 1).all { it == '0' } &&
        (digits[keep - 1] - '0') % 2 == 0
    ) {
      return Rounded(digits.substring(0, keep), carried = false)
    }
    val kept = digits.substring(0, keep).toCharArray()
    var index = keep - 1
    while (index >= 0) {
      if (kept[index] != '9') {
        kept[index]++
        return Rounded(kept.concatToString(), carried = false)
      }
      kept[index] = '0'
      index--
    }
    // All nines: 999 becomes 1000, which is one digit wider and one decade further along.
    return Rounded("1" + kept.concatToString().substring(0, keep - 1), carried = true)
  }

  private class Rounded(val digits: String, val carried: Boolean)

  /** Places the point [decimals] digits from the right, padding a short number with zeros. */
  private fun withPoint(digits: String, decimals: Int): String {
    if (decimals == 0) return digits
    val padded = if (digits.length <= decimals) digits.padStart(decimals + 1, '0') else digits
    val split = padded.length - decimals
    return padded.substring(0, split) + "." + padded.substring(split)
  }

  /** The sign bit, which is what tells `-0.0` from `0.0`; `<` cannot. */
  private fun isNegative(value: Double): Boolean = (value.toRawBits() and Long.MIN_VALUE) != 0L

  private fun nonFinite(value: Double): String? =
    when {
      value.isNaN() -> "NaN"
      value == Double.POSITIVE_INFINITY -> "Infinity"
      value == Double.NEGATIVE_INFINITY -> "-Infinity"
      else -> null
    }

  private fun abs(value: Int): String = if (value < 0) (-value).toString() else value.toString()
}

/**
 * A natural number wide enough for any double's exact expansion, and no wider in ambition.
 *
 * Base 2^32, little-endian, with the four operations the expansion actually needs: multiply by a
 * value that fits in a word, shift, test a bit, and divide by a billion to read the digits off. A
 * general big-integer would be a larger thing to get right and none of it would be used.
 */
private class Natural(value: Long) {

  private var words = IntArray(INITIAL_WORDS)
  private var size = 0

  init {
    var remaining = value
    while (remaining != 0L) {
      append((remaining and MASK).toInt())
      remaining = remaining ushr 32
    }
  }

  fun multiplyPowerOfFive(power: Int) {
    var remaining = power
    while (remaining > 0) {
      val step = if (remaining < POWER_OF_FIVE_STEP) remaining else POWER_OF_FIVE_STEP
      multiply(POWERS_OF_FIVE[step])
      remaining -= step
    }
  }

  fun multiplyPowerOfTen(power: Int) {
    multiplyPowerOfFive(power)
    shiftLeft(power)
  }

  fun shiftLeft(bits: Int) {
    if (bits == 0 || size == 0) return
    val wordShift = bits ushr 5
    val bitShift = bits and 31
    val grown = size + wordShift + 1
    ensure(grown)
    // Copied from the top down so a source word is read before its destination overwrites it.
    for (index in size - 1 downTo 0) {
      val word = words[index].toLong() and MASK
      val shifted = if (bitShift == 0) word else word shl bitShift
      val target = index + wordShift
      words[target + 1] = ((words[target + 1].toLong() and MASK) or (shifted ushr 32)).toInt()
      words[target] = shifted.toInt()
    }
    for (index in 0 until wordShift) words[index] = 0
    size = grown
    normalize()
  }

  fun shiftRight(bits: Int) {
    val wordShift = bits ushr 5
    if (wordShift >= size) {
      size = 0
      return
    }
    val bitShift = bits and 31
    for (index in 0 until size - wordShift) {
      var word = (words[index + wordShift].toLong() and MASK) ushr bitShift
      if (bitShift != 0 && index + wordShift + 1 < size) {
        word = word or ((words[index + wordShift + 1].toLong() and MASK) shl (32 - bitShift))
      }
      words[index] = word.toInt()
    }
    size -= wordShift
    normalize()
  }

  fun bit(index: Int): Boolean {
    val word = index ushr 5
    if (word >= size) return false
    return (words[word] ushr (index and 31)) and 1 == 1
  }

  fun increment() {
    var index = 0
    while (index < size) {
      val sum = (words[index].toLong() and MASK) + 1L
      words[index] = sum.toInt()
      if (sum <= MASK) return
      index++
    }
    append(1)
  }

  /** Repeated division by a billion, which is nine digits a pass. */
  fun toDecimalString(): String {
    if (size == 0) return "0"
    val chunks = mutableListOf<Int>()
    val scratch = words.copyOf(size)
    var length = size
    while (length > 0) {
      var remainder = 0L
      for (index in length - 1 downTo 0) {
        // `remainder` is below a billion, so this stays well inside a signed 64-bit value.
        val current = (remainder shl 32) or (scratch[index].toLong() and MASK)
        scratch[index] = (current / BILLION).toInt()
        remainder = current % BILLION
      }
      chunks += remainder.toInt()
      while (length > 0 && scratch[length - 1] == 0) length--
    }
    val builder = StringBuilder()
    builder.append(chunks.last())
    for (index in chunks.size - 2 downTo 0) {
      builder.append(chunks[index].toString().padStart(9, '0'))
    }
    return builder.toString()
  }

  private fun multiply(factor: Int) {
    if (size == 0) return
    val multiplier = factor.toLong() and MASK
    var carry = 0L
    for (index in 0 until size) {
      val product = (words[index].toLong() and MASK) * multiplier + carry
      words[index] = product.toInt()
      carry = product ushr 32
    }
    while (carry != 0L) {
      append((carry and MASK).toInt())
      carry = carry ushr 32
    }
  }

  private fun append(word: Int) {
    ensure(size + 1)
    words[size] = word
    size++
  }

  private fun ensure(capacity: Int) {
    if (capacity <= words.size) return
    var grown = words.size
    while (grown < capacity) grown *= 2
    words = words.copyOf(grown)
  }

  private fun normalize() {
    while (size > 0 && words[size - 1] == 0) size--
  }

  private companion object {
    const val MASK = 0xFFFFFFFFL
    const val BILLION = 1_000_000_000L
    // 5^1074 is the widest power any double needs, which is 83 of these steps.
    const val POWER_OF_FIVE_STEP = 13
    // A double's exact expansion is at most 1080 bits, and growth doubles from here anyway.
    const val INITIAL_WORDS = 8
    val POWERS_OF_FIVE =
      IntArray(POWER_OF_FIVE_STEP + 1).also {
        var power = 1L
        for (index in 0..POWER_OF_FIVE_STEP) {
          it[index] = power.toInt()
          power *= 5L
        }
      }
  }
}
