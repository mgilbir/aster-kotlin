package dev.aster.vega.expression

import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `toDate` has **two** answers for nothing and one for everything else.
 *
 * ```js
 * const defaultParser = _ => isNumber(_) ? _ : isDate(_) ? _ : Date.parse(_);
 * export default function toDate(_, parser) {
 *   parser = parser || defaultParser;
 *   return _ == null || _ === '' ? null : parser(_);
 * }
 * ```
 *
 * Only `null` and the empty string get nothing. Everything else goes to `Date.parse`, which answers
 * **`NaN`** for a string that is not a date — a number, not an absence. This engine's
 * [dev.aster.vega.model.time.DateValues] is a parser and rightly says "no" by answering null, and
 * turning that into a null *value* made an unreadable date vanish where upstream keeps a NaN.
 *
 * The difference is observable, and a chart reaches it without contrivance: `formatType: "time"`
 * over a column of words is enough, because Vega-Lite then writes `toDate(datum[...])` over that
 * column. `a-date-that-is-not-a-date.vl.json` carries the NaN half, where a nominal text channel
 * writes `isValid(datum["a"]) ? datum["a"] : "" + datum["a"]` and prints `NaN` against `null`.
 *
 * **The empty string is pinned here rather than there**, and the reason is worth recording: a row
 * carrying one puts a genuine null into the chart, and upstream keys a stack's groups by
 * `JSON.stringify(groupby.map(get))` — under which `[NaN]` and `[null]` are the same string, so a
 * NaN stacks with the nulls. That is a second divergence with a different cause, and a fixture
 * carrying both would fail for the wrong reason.
 */
class ToDateTest {

  private val compiler = VegaExpressionCompiler()

  private object EmptyScope : ExpressionScope {
    override val datum: VegaValue = VegaValue.EmptyObject

    override fun signal(name: String): VegaValue = VegaValue.Null

    override fun dataset(name: String): List<VegaValue> = emptyList()
  }

  private fun toDate(argument: String): VegaValue {
    val result = compiler.compile("toDate($argument)")
    assertTrue(result is ExpressionResult.Compiled, "failed to parse toDate($argument): $result")
    return (result as ExpressionResult.Compiled).expression.evaluate(EmptyScope)
  }

  @Test
  fun `a string that is not a date is NaN, not nothing`() {
    val answer = toDate("'one'")
    assertTrue(answer is VegaValue.Num, "expected a number, got $answer")
    assertTrue((answer as VegaValue.Num).value.isNaN(), "expected NaN, got ${answer.value}")
  }

  @Test
  fun `nothing and the empty string are the two that answer nothing`() {
    assertEquals(VegaValue.Null, toDate("null"))
    assertEquals(VegaValue.Null, toDate("''"))
  }

  @Test
  fun `a number is itself and a date it can read is an instant`() {
    // `isNumber(_) ? _` — handed back before `Date.parse` is reached, so a chart that already holds
    // epoch milliseconds keeps them rather than having them re-read as text.
    assertEquals(VegaValue.Num(1_580_515_200_000.0), toDate("1580515200000"))
    assertTrue(toDate("'2020-02-01'") is VegaValue.Num)
  }

  /**
   * A value that is neither, which `Date.parse` coerces to text before failing.
   *
   * `Date.parse(true)` is `NaN`, not an error and not nothing — worth a line because a boolean is
   * what a column of `"true"`/`"false"` becomes once anything has parsed it.
   */
  @Test
  fun `a value that is not text at all is still NaN`() {
    val answer = toDate("true")
    assertTrue(answer is VegaValue.Num && answer.value.isNaN(), "expected NaN, got $answer")
  }
}
