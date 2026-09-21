package dev.aster.vega.expression

import dev.aster.vega.model.VegaValue
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `<`, `>` and `<=` over the operands that are not two plain numbers.
 *
 * ECMA-262 7.2.13 applies `ToPrimitive` with the **number** hint to both sides *before* asking
 * whether it has two strings, and that step is the whole of what this pins. An array primitivizes
 * to its join, an object to `[object Object]`, a regular expression to its literal — so all three
 * can end up in a *string* comparison with something that was never a string in the specification.
 * Reading them as numbers instead gives `NaN` and no answer, which is what this engine did, and an
 * aggregate's `max` over a column of lists kept its first row forever because of it.
 *
 * Every expectation here was run through `node` against the same expression. The rows that look
 * wrong are the interesting ones: `[] <= 0` is true while `[] < 0` is false, because `""` and `0`
 * both reach 0; `[1,2] <= "1,2"` is true because both sides are strings and equal; `[10] < [9]` is
 * true because `"10"` sorts before `"9"`.
 */
class RelationalComparisonTest {

  private fun arr(vararg values: Any): VegaValue =
    VegaValue.Arr(
      values.map {
        when (it) {
          is Int -> VegaValue.Num(it.toDouble())
          is String -> VegaValue.Str(it)
          else -> error("unexpected $it")
        }
      }
    )

  private fun num(value: Double) = VegaValue.Num(value)

  private fun str(value: String) = VegaValue.Str(value)

  /** `a < b`, as the evaluator asks it: a null comparison is false. */
  private fun less(a: VegaValue, b: VegaValue): Boolean =
    JsSemantics.compare(a, b)?.let { it < 0 } ?: false

  private fun greater(a: VegaValue, b: VegaValue): Boolean =
    JsSemantics.compare(a, b)?.let { it > 0 } ?: false

  private fun lessOrEqual(a: VegaValue, b: VegaValue): Boolean =
    JsSemantics.compare(a, b)?.let { it <= 0 } ?: false

  @Test
  fun `an array compares as its join`() {
    // "3" > "1,2": two strings, lexicographic. This is the one an aggregate's max rests on.
    assertEquals(true, greater(arr(3), arr(1, 2)))
    assertEquals(false, less(arr(3), arr(1, 2)))
    assertEquals(true, less(arr(1, 2), arr(3)))
    // "10" < "9", because they are compared as text and not as numbers.
    assertEquals(true, less(arr(10), arr(9)))
  }

  @Test
  fun `an array against a number is numeric so a list of two answers nothing`() {
    // "1,2" is not a number, so every comparison with 4 is false — including `>=`.
    assertEquals(false, less(arr(1, 2), num(4.0)))
    assertEquals(false, greater(arr(1, 2), num(4.0)))
    assertEquals(false, lessOrEqual(arr(1, 2), num(4.0)))
    // A one-element list is its element, so this one does answer.
    assertEquals(true, less(arr(3), num(4.0)))
  }

  @Test
  fun `an empty array is the empty string and so is zero against a number`() {
    assertEquals(false, less(arr(), num(0.0)))
    assertEquals(true, lessOrEqual(arr(), num(0.0)))
    // Against the empty string it is a *string* comparison, and they are equal.
    assertEquals(false, less(arr(), str("")))
    assertEquals(true, lessOrEqual(arr(), str("")))
  }

  @Test
  fun `an object is the words object Object`() {
    assertEquals(true, less(VegaValue.Obj(linkedMapOf()), str("z")))
    // Against a number there is nothing to compare: `[object Object]` is NaN.
    assertEquals(false, less(VegaValue.Obj(linkedMapOf()), num(1.0)))
    assertEquals(false, greater(VegaValue.Obj(linkedMapOf()), num(1.0)))
  }

  @Test
  fun `an array equal to its own join compares equal`() {
    assertEquals(false, less(arr(1, 2), str("1,2")))
    assertEquals(false, greater(arr(1, 2), str("1,2")))
    assertEquals(true, lessOrEqual(arr(1, 2), str("1,2")))
  }

  @Test
  fun `a date compares as its instant under the number hint and not the default one`() {
    assertEquals(true, less(VegaValue.Timestamp(0.0), VegaValue.Timestamp(1.0)))
    // `datetime(0) < 1` is true: the relational comparison reads the time value, where `+` would
    // have read the sentence. The two hints differ for exactly this type.
    assertEquals(true, less(VegaValue.Timestamp(0.0), num(1.0)))
  }

  @Test
  fun `a word and a number still answer nothing`() {
    assertEquals(false, less(str("abc"), num(1.0)))
    assertEquals(false, greater(str("abc"), num(1.0)))
    assertEquals(false, lessOrEqual(str("abc"), num(1.0)))
  }

  @Test
  fun `a flag and a null are read as numbers rather than as words`() {
    // `true < [1]` is false and `true <= [1]` is true: 1 against "1" is not two strings, so both
    // reach 1. A reading that stringified the flag would have compared "true" with "1".
    assertEquals(false, less(VegaValue.Bool(true), arr(1)))
    assertEquals(true, lessOrEqual(VegaValue.Bool(true), arr(1)))
    assertEquals(true, less(VegaValue.Null, arr(1)))
  }
}
