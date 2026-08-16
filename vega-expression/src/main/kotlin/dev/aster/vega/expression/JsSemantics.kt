package dev.aster.vega.expression

import dev.aster.vega.model.Decimals
import dev.aster.vega.model.VegaValue
import kotlin.math.truncate

/**
 * JavaScript's coercion and comparison rules, as Vega's expression language inherits them.
 *
 * Vega compiles expressions to JavaScript, so `1 + "2"` is `"12"`, `1 == "1"` is true, `"10" < "9"`
 * is true, and `-7 % 3` is `-1`. A port that quietly applied Kotlin's rules instead would produce
 * charts that are subtly and inexplicably wrong, so these rules are implemented deliberately and
 * pinned by reference vectors generated from upstream Vega (see `JsSemanticsTest`).
 *
 * This is the messiest corner of the language and the one most worth being explicit about.
 */
public object JsSemantics {

  // ---- truthiness -----------------------------------------------------------

  /**
   * JavaScript truthiness: `null`, `false`, `0`, `NaN` and `""` are falsey; everything else is not.
   */
  public fun truthy(value: VegaValue): Boolean =
    when (value) {
      is VegaValue.Null -> false
      is VegaValue.Bool -> value.value
      is VegaValue.Num -> value.value != 0.0 && !value.value.isNaN()
      is VegaValue.Timestamp -> value.epochMillis != 0.0 && !value.epochMillis.isNaN()
      is VegaValue.Str -> value.value.isNotEmpty()
      is VegaValue.Arr -> true
      is VegaValue.Obj -> true
      // An object, and every object is truthy.
      is VegaValue.Pattern -> true
    }

  // ---- number coercion ------------------------------------------------------

  /**
   * `Number(value)`.
   *
   * Note the JavaScript-specific cases: `Number(null)` is 0 rather than NaN, `Number("")` is 0, and
   * an array coerces via its string form, so `Number([5])` is 5 but `Number([1,2])` is NaN.
   */
  public fun toNumber(value: VegaValue): Double =
    when (value) {
      is VegaValue.Null -> 0.0
      is VegaValue.Bool -> if (value.value) 1.0 else 0.0
      is VegaValue.Num -> value.value
      is VegaValue.Timestamp -> value.epochMillis
      is VegaValue.Str -> stringToNumber(value.value)
      is VegaValue.Arr ->
        when (value.values.size) {
          0 -> 0.0 // Number([]) === 0
          1 -> toNumber(value.values[0])
          else -> Double.NaN
        }
      is VegaValue.Obj -> Double.NaN
      // `Number(/a/)` is NaN.
      is VegaValue.Pattern -> Double.NaN
    }

  /** `Number(string)`: trims, accepts hex and binary, and treats the empty string as 0. */
  private fun stringToNumber(text: String): Double {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return 0.0
    if (trimmed == "Infinity" || trimmed == "+Infinity") return Double.POSITIVE_INFINITY
    if (trimmed == "-Infinity") return Double.NEGATIVE_INFINITY
    if (trimmed.length > 2 && (trimmed.startsWith("0x") || trimmed.startsWith("0X"))) {
      return trimmed.substring(2).toLongOrNull(16)?.toDouble() ?: Double.NaN
    }
    if (trimmed.length > 2 && (trimmed.startsWith("0b") || trimmed.startsWith("0B"))) {
      return trimmed.substring(2).toLongOrNull(2)?.toDouble() ?: Double.NaN
    }
    // Kotlin accepts trailing 'd'/'f' suffixes and leading/trailing whitespace forms that
    // JavaScript
    // rejects, so screen the text before converting.
    if (!NUMERIC.matches(trimmed)) return Double.NaN
    return trimmed.toDoubleOrNull() ?: Double.NaN
  }

  // ---- string coercion ------------------------------------------------------

  /** `String(value)`, including JavaScript's array and object forms. */
  public fun toStringValue(value: VegaValue): String =
    when (value) {
      is VegaValue.Null -> "null"
      is VegaValue.Bool -> value.value.toString()
      is VegaValue.Num -> numberToString(value.value)
      is VegaValue.Timestamp -> numberToString(value.epochMillis)
      is VegaValue.Str -> value.value
      // `String([1,2])` is "1,2", and `String([null])` is "" — null elements stringify to empty.
      is VegaValue.Arr ->
        value.values.joinToString(",") {
          if (it is VegaValue.Null) "" else toStringValue(it)
        }
      is VegaValue.Obj -> "[object Object]"
      // Not `[object Object]`: a RegExp has a `toString` of its own, and it is the literal —
      // `'' + regexp('a.b','i')` is `/a.b/i`, probed against upstream.
      is VegaValue.Pattern -> value.text
    }

  /**
   * JavaScript's number-to-string conversion for the cases that matter here.
   *
   * Whole numbers lose their decimal point (`1` not `1.0`), which matters because string
   * concatenation of a scaled value is common in labels and tooltips.
   */
  public fun numberToString(value: Double): String = Decimals.jsString(value)

  // ---- arithmetic -----------------------------------------------------------

  /**
   * The `+` operator, which is addition or concatenation depending on the operands.
   *
   * JavaScript applies `ToPrimitive` to both sides first, and arrays and objects primitivize to
   * strings — so `[1] + 1` is `"11"`, not `2`, and `[1,2] + ""` is `"1,2"`. Verified against
   * upstream. Concatenation therefore wins whenever either side is a string, an array or an object;
   * only when both sides are primitives-that-are-not-strings does it add.
   *
   * This is the single most surprising rule in the language and the one most likely to make a
   * ported expression quietly disagree with the browser.
   */
  public fun add(left: VegaValue, right: VegaValue): VegaValue =
    if (concatenates(left) || concatenates(right)) {
      VegaValue.Str(toStringValue(left) + toStringValue(right))
    } else {
      VegaValue.Num(toNumber(left) + toNumber(right))
    }

  private fun concatenates(value: VegaValue): Boolean =
    value is VegaValue.Str || value is VegaValue.Arr || value is VegaValue.Obj

  /** JavaScript's `%`, a remainder that takes the sign of the dividend: `-7 % 3` is `-1`. */
  public fun remainder(left: Double, right: Double): Double {
    if (right == 0.0 || left.isNaN() || right.isNaN() || left.isInfinite()) return Double.NaN
    if (right.isInfinite()) return left
    return left % right
  }

  // ---- comparison -----------------------------------------------------------

  /** Strict equality (`===`): no coercion, and `NaN` is never equal to itself. */
  public fun strictEquals(left: VegaValue, right: VegaValue): Boolean {
    if (left is VegaValue.Num && right is VegaValue.Num) {
      return !left.value.isNaN() && !right.value.isNaN() && left.value == right.value
    }
    if (left::class != right::class) {
      // Timestamps compare as numbers, since that is what they are underneath.
      val leftNumber = (left as? VegaValue.Timestamp)?.epochMillis
      val rightNumber = (right as? VegaValue.Timestamp)?.epochMillis
      if (leftNumber != null && right is VegaValue.Num) return leftNumber == right.value
      if (rightNumber != null && left is VegaValue.Num) return rightNumber == left.value
      return false
    }
    return when (left) {
      is VegaValue.Null -> true
      is VegaValue.Bool -> left.value == (right as VegaValue.Bool).value
      is VegaValue.Str -> left.value == (right as VegaValue.Str).value
      is VegaValue.Timestamp -> left.epochMillis == (right as VegaValue.Timestamp).epochMillis
      // JavaScript compares arrays and objects by reference, so two equal-looking literals are not
      // equal. `VegaValue.Arr` and `Obj` are inline value classes, so the wrapper has no identity
      // of its
      // own; compare the underlying collection's identity instead, which gives the same behaviour.
      is VegaValue.Arr -> left.values === (right as VegaValue.Arr).values
      is VegaValue.Obj -> left.fields === (right as VegaValue.Obj).fields
      is VegaValue.Num -> false // handled above
      // Two patterns are two objects, and JavaScript compares those by reference. Equal literals
      // are
      // therefore *not* equal, which `Pattern` reproduces by comparing identity here rather than
      // its
      // own `equals` — the same choice `Arr` and `Obj` make just above.
      is VegaValue.Pattern -> left === right
    }
  }

  /**
   * Loose equality (`==`).
   *
   * `null == null` is true, `"" == 0` is true, `1 == "1"` is true; a `null` compared with anything
   * else is false. Objects and arrays fall back to strict equality here rather than invoking
   * `valueOf`, which Vega expressions have no way to define.
   */
  public fun looseEquals(left: VegaValue, right: VegaValue): Boolean {
    val leftNull = left is VegaValue.Null
    val rightNull = right is VegaValue.Null
    if (leftNull || rightNull) return leftNull && rightNull

    val leftComposite = left is VegaValue.Arr || left is VegaValue.Obj
    val rightComposite = right is VegaValue.Arr || right is VegaValue.Obj
    if (leftComposite && rightComposite) return strictEquals(left, right)

    if (left is VegaValue.Str && right is VegaValue.Str) return left.value == right.value

    // Anything else compares numerically, which is what JavaScript's abstract equality reduces to.
    val a = toNumber(left)
    val b = toNumber(right)
    return !a.isNaN() && !b.isNaN() && a == b
  }

  /**
   * Relational comparison (`<`, `<=`, `>`, `>=`).
   *
   * Two strings compare lexicographically — so `"10" < "9"` is true — and anything else compares
   * numerically. Returns `null` when the comparison is undefined (a `NaN` operand), which the
   * caller turns into `false`.
   */
  public fun compare(left: VegaValue, right: VegaValue): Int? {
    if (left is VegaValue.Str && right is VegaValue.Str) {
      return left.value.compareTo(right.value)
    }
    val a = toNumber(left)
    val b = toNumber(right)
    if (a.isNaN() || b.isNaN()) return null
    return a.compareTo(b)
  }

  // ---- bitwise --------------------------------------------------------------

  /** `ToInt32`: JavaScript's bitwise operators truncate to a signed 32-bit integer first. */
  public fun toInt32(value: VegaValue): Int {
    val number = toNumber(value)
    if (!number.isFinite()) return 0
    return truncate(number).toLong().toInt()
  }

  /** `ToUint32`, needed for the unsigned right shift. */
  public fun toUint32(value: VegaValue): Long {
    val number = toNumber(value)
    if (!number.isFinite()) return 0L
    return truncate(number).toLong() and 0xFFFFFFFFL
  }

  private val NUMERIC = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$")
}
