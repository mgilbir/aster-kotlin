package dev.aster.vega.expression

import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * A field a row does not have is `undefined`, and `undefined` is not `null`.
 *
 * The value model used to carry one absent value and it behaved as `null`, which is the opposite of
 * what a chart needs: `Number(null)` is 0 and `Number(undefined)` is NaN, so a filter `datum.x <
 * 10` over rows with no `x` at all **kept** every one of them where upstream drops them. Ordinary
 * dirty data, and a different chart, with nothing said about it.
 *
 * Every expectation below was read off upstream Vega running the same expression as a `formula`
 * over a row that has `nul: null` and no `missing` at all:
 * ```
 * node --input-type=module -e "import * as vega from 'vega';
 *   const view = new vega.View(vega.parse({data: [{name: 't',
 *     values: [{nul: null}],
 *     transform: [{type: 'formula', expr: 'datum.missing < 10', as: 'r'}]}]}), {renderer: 'none'});
 *   await view.runAsync(); console.log(view.data('t')[0].r)"
 * ```
 *
 * `undefined` below is what that prints where `JSON.stringify` writes nothing at all.
 */
class UndefinedSemanticsTest {

  private object RowScope : ExpressionScope {
    /** One row with a **null** field and no `missing` field, which is the whole of the point. */
    override val datum: VegaValue =
      VegaValue.Obj(linkedMapOf("nul" to VegaValue.Null, "n" to VegaValue.Num(5.0)))

    override fun signal(name: String): VegaValue = VegaValue.Null

    override fun dataset(name: String): List<VegaValue> = emptyList()
  }

  private fun evaluate(source: String): VegaValue {
    val result = VegaExpressionCompiler().compile(source)
    return (result as ExpressionResult.Compiled).expression.evaluate(RowScope)
  }

  private fun text(source: String): String =
    when (val value = evaluate(source)) {
      is VegaValue.Null -> "null"
      is VegaValue.Undefined -> "undefined"
      is VegaValue.Bool -> value.value.toString()
      is VegaValue.Num -> if (!value.value.isFinite()) "null" else value.value.toString()
      is VegaValue.Str -> value.value
      else -> value.toString()
    }

  @ParameterizedTest(name = "{0} => {1}")
  @CsvSource(
    delimiter = '|',
    value =
      [
        // The comparison C1 is about: NaN against a number is false, both ways round.
        "datum.missing < 10|false",
        "datum.missing > 10|false",
        "datum.missing <= 10|false",
        "datum.missing >= 10|false",
        // A field that is *present and null* keeps coercing as null, because it is one.
        "datum.nul < 10|true",
        // Arithmetic. `undefined + 1` is NaN; `null + 1` is 1.
        "datum.missing + 1|null",
        "datum.nul + 1|1.0",
        "-datum.missing|null",
        "datum.missing * 2|null",
        // Text. `'' + undefined` is "undefined", not "null".
        "'' + datum.missing|undefined",
        "'' + datum.nul|null",
        // The two predicates whose whole job is telling them apart.
        "isDefined(datum.missing)|false",
        "isDefined(datum.nul)|true",
        "isValid(datum.missing)|false",
        "isValid(datum.nul)|false",
        // Loose equality puts them in one box; strict equality does not.
        "datum.missing == null|true",
        "datum.nul == datum.missing|true",
        "datum.missing === datum.missing|true",
        // Truthiness.
        "datum.missing ? 1 : 2|2.0",
        "!datum.missing|true",
        // Reading a property of nothing. Upstream throws a TypeError and takes the chart with it;
        // this answers what a further read would have answered anyway.
        "datum.n.x|undefined",
        "datum.missing.deeper|undefined",
        // Every type predicate answers false, including the two that answer true for an object.
        "isNumber(datum.missing)|false",
        "isObject(datum.missing)|false",
        "isString(datum.missing)|false",
        "isDate(datum.missing)|false",
        // The coercion helpers all screen with `_ == null`, so both kinds of nothing give null.
        "toNumber(datum.missing)|null",
        "toString(datum.missing)|null",
        "toBoolean(datum.missing)|null",
        // `Array.prototype.join` writes an empty string for either.
        "join([datum.missing, 1], '-')|-1",
        // d3's extent skips both.
        "'' + extent([datum.missing, 1, 2])|1,2",
      ],
  )
  fun `undefined is not null`(source: String, expected: String) {
    assertEquals(expected, text(source), source)
  }

  /**
   * `timeParse` is the one function that must **not** treat them alike.
   *
   * Upstream's wrapper is `value === null ? 'null' : locale[method](spec)(value)` — a strict test,
   * so an undefined input goes on to the parser, which reads `"undefined"` and fails. Probed:
   * `timeParse(datum.nul, '%Y')` is the string `"null"` and `timeParse(datum.missing, '%Y')` is
   * null.
   */
  @Test
  fun `timeParse tells the two apart because upstream's wrapper is strict`() {
    assertEquals(VegaValue.Str("null"), evaluate("timeParse(datum.nul, '%Y')"))
    assertEquals(VegaValue.Null, evaluate("timeParse(datum.missing, '%Y')"))
  }

  /**
   * `|` is the table's own delimiter, so the bitwise coercion lives here.
   *
   * `undefined | 0` is 0: `ToInt32` of NaN is 0, which is the one place the arithmetic does not
   * propagate the NaN.
   */
  @Test
  fun `a bitwise operator coerces undefined to zero`() {
    assertEquals(VegaValue.Num(0.0), evaluate("datum.missing | 0"))
  }

  /** An index outside an array, and a key an object does not carry, are the same absence. */
  @Test
  fun `an element outside an array is undefined`() {
    assertEquals(VegaValue.Undefined, evaluate("[1, 2][9]"))
    assertEquals(VegaValue.Undefined, evaluate("'ab'[9]"))
    assertEquals(VegaValue.Num(2.0), evaluate("[1, 2][1]"))
  }
}
