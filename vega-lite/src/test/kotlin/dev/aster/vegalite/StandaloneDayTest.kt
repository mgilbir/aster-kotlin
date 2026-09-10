package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A weekday only means anything on its own.
 *
 * `dateTimeParts` drops it otherwise, before it reads any of the rest:
 * ```js
 * if (normalize && d.day !== undefined) {
 *   if (keys(d).length > 1) {
 *     log.warn(log.message.droppedDay(d));
 *     d = duplicate(d);
 *     delete d.day;
 *   }
 * }
 * ```
 *
 * Upstream's comment further down says why: "HACK: Day only works as a standalone unit. This is
 * only correct because we always set year to 2006 for day." A weekday is a position in a *week*,
 * and the `day + 1` that places it is arithmetic that makes sense only when nothing else is pinned.
 * Written beside a year and a month it is nonsense — and carrying it there moved the date by a day,
 * so `{"year": 1900, "month": 1, "day": 1}` came out as the second of January.
 *
 * The literal also goes through `dateTimeToExpr` rather than its parts alone, which is what writes
 * an instant marked `utc` as `utc(…)` instead of as local time. Both were wrong in the same line.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class StandaloneDayTest {

  private fun filterExpression(range: String): String {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"D":"2020-01-01"}]},
               "transform":[{"filter":{"field":"D","range":$range}}],
               "mark":"point","encoding":{"x":{"field":"D","type":"temporal"}}}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    return (compiled.fields["data"] as VegaValue.Arr)
      .values
      .mapNotNull { it as? VegaValue.Obj }
      .flatMap { (it.fields["transform"] as? VegaValue.Arr)?.values.orEmpty() }
      .mapNotNull { it as? VegaValue.Obj }
      .first { (it.fields["type"] as? VegaValue.Str)?.value == "filter" }
      .let { (it.fields["expr"] as VegaValue.Str).value }
  }

  /** The reported shape: a weekday written beside a year and a month is dropped. */
  @Test
  fun `a day beside other parts is dropped`() {
    assertEquals(
      """inrange(datum["D"], [time(datetime(1900, 0, 1, 0, 0, 0, 0)), """ +
        """time(datetime(2023, 10, 1, 0, 0, 0, 0))])""",
      filterExpression("""[{"year":1900,"month":1,"day":1},{"year":2023,"month":11,"day":1}]"""),
      "the first of January, not the second",
    )
  }

  /** On its own it still places the weekday, which is the whole of what it is for. */
  @Test
  fun `a day on its own still places the weekday`() {
    assertEquals(
      """inrange(datum["D"], [time(datetime(2012, 0, 2, 0, 0, 0, 0)), """ +
        """time(datetime(2012, 0, 4, 0, 0, 0, 0))])""",
      filterExpression("""[{"day":1},{"day":3}]"""),
      "Monday is the second of the default year, which begins on a Sunday",
    )
  }

  /** A `date` is a day of the *month* and is never dropped. */
  @Test
  fun `a date beside other parts is kept`() {
    assertEquals(
      """inrange(datum["D"], [time(datetime(1900, 0, 5, 0, 0, 0, 0)), """ +
        """time(datetime(2023, 10, 9, 0, 0, 0, 0))])""",
      filterExpression("""[{"year":1900,"month":1,"date":5},{"year":2023,"month":11,"date":9}]"""),
    )
  }

  /**
   * `utc` counts as one of the keys, so a weekday beside it is dropped too — and the instant is
   * written with `utc(…)`, which the parts alone could not say.
   */
  @Test
  fun `a day beside utc is dropped and the instant is written in utc`() {
    assertEquals(
      """inrange(datum["D"], [time(utc(2012, 0, 1, 0, 0, 0, 0)), """ +
        """time(utc(2012, 0, 1, 0, 0, 0, 0))])""",
      filterExpression("""[{"day":1,"utc":true},{"day":3,"utc":true}]"""),
    )
  }
}
