package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A time unit written as an object is named in the order its parameters were **written**.
 *
 * ```js
 * const {utc, ...rest} = normalizeTimeUnit(tu);
 * return (utc ? 'utc' : '') +
 *   keys(rest).map((p) => varName(`${p === 'unit' ? '' : `_${p}_`}${rest[p]}`)).join('');
 * ```
 *
 * `keys` walks the object's own order, which `normalizeTimeUnit` preserves — its one rewrite is
 * `{...timeUnit, ...{unit}}`, and putting an existing key back leaves it where it was. So `{"step":
 * 5, "unit": "minutes"}` is called `_step_5minutes` and `{"unit": "minutes", "step": 5}` is called
 * `minutes_step_5`: the same bucketing, two names.
 *
 * And the name is what every column and every expression downstream is spelled with. Written
 * unit-first regardless, a specification that put the step first named a column upstream never
 * writes — so the transform wrote one column, the mark read another, and every row came out empty.
 * One specification in the wild corpus writes its step first.
 *
 * `utc` is not a parameter but a **prefix**, being destructured out before the walk; a compiler
 * that merely skipped it dropped the prefix and named a local bucketing where a UTC one was asked
 * for.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class TimeUnitNameTest {

  /** What the `timeunit` transform writes its two columns as. */
  private fun columns(timeUnit: String): String {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """{"data":{"values":[{"d":"2020-01-01T00:03:00","v":1}]},"mark":"bar",
                 "encoding":{"x":{"field":"d","type":"temporal","timeUnit":$timeUnit},
                             "y":{"field":"v","type":"quantitative"}}}"""
            )
            .toJson()
        ) {
          "no output"
        }
      ) as VegaValue.Obj
    return (compiled.fields["data"] as VegaValue.Arr)
      .values
      .map { it as VegaValue.Obj }
      .flatMap { (it.fields["transform"] as? VegaValue.Arr)?.values.orEmpty() }
      .map { it as VegaValue.Obj }
      .filter { it.string("type") == "timeunit" }
      .joinToString(";") { VegaJson.write(it.fields["as"]!!).replace(Regex("""\n\s*"""), "") }
  }

  /** The reported shape: the step written first, and named first. */
  @Test
  fun `a step written first is named first`() {
    assertEquals(
      """["_step_5minutes_d","_step_5minutes_d_end"]""",
      columns("""{"step":5,"unit":"minutes"}"""),
    )
  }

  /** The same parameters the other way about are a different name. */
  @Test
  fun `a step written second is named second`() {
    assertEquals(
      """["minutes_step_5_d","minutes_step_5_d_end"]""",
      columns("""{"unit":"minutes","step":5}"""),
    )
  }

  /** A unit on its own, however it was written, is the word itself. */
  @Test
  fun `a unit on its own is its own name`() {
    assertEquals("""["minutes_d","minutes_d_end"]""", columns(""""minutes""""))
    assertEquals("""["minutes_d","minutes_d_end"]""", columns("""{"unit":"minutes"}"""))
  }

  /** `utc` is a prefix and takes no place in the walk, wherever it was written. */
  @Test
  fun `utc is a prefix`() {
    assertEquals(
      """["utcminutes_step_5_d","utcminutes_step_5_d_end"]""",
      columns("""{"unit":"minutes","utc":true,"step":5}"""),
    )
    assertEquals(
      """["utc_step_5minutes_d","utc_step_5minutes_d_end"]""",
      columns("""{"utc":true,"step":5,"unit":"minutes"}"""),
    )
  }

  /** A unit spelled `utc…` carries its own prefix, which is the same answer by another route. */
  @Test
  fun `a utc unit keeps its prefix`() {
    assertEquals("""["utcminutes_d","utcminutes_d_end"]""", columns(""""utcminutes""""))
  }

  /** A compound unit is one word, and the step still goes where it was written. */
  @Test
  fun `a compound unit is one word`() {
    assertEquals(
      """["_step_2yearmonth_d","_step_2yearmonth_d_end"]""",
      columns("""{"step":2,"unit":"yearmonth"}"""),
    )
  }
}
