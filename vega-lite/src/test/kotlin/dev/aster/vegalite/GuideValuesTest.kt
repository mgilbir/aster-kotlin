package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The ticks a guide was **told** to draw, each made into something Vega can read.
 *
 * ```js
 * export function valueArray(fieldOrDatumDef, values) {
 *   const {type} = fieldOrDatumDef;
 *   return values.map((v) => {
 *     const timeUnit = isFieldDef(fieldOrDatumDef) && !isBinnedTimeUnit(fieldOrDatumDef.timeUnit)
 *       ? fieldOrDatumDef.timeUnit : undefined;
 *     const expr = valueExpr(v, {timeUnit, type, undefinedIfExprNotRequired: true});
 *     if (expr !== undefined) { return {signal: expr}; }
 *     return v;
 *   });
 * }
 * ```
 *
 * An **instant** is not a value Vega can be handed. `{"year": 2019, "month": "Jan"}` is a way of
 * writing a date down and not a number, and a date written as text is text until something builds
 * it; every one of them becomes the signal that does. This compiler copied the list through as it
 * stood, so Vega was handed an object where it wanted a number and drew no ticks at all where the
 * specification had listed them — three specifications in the wild corpus list them that way.
 *
 * A tick on a guide over a **single** unit is a reading of that unit rather than a date: `4` on an
 * axis of hours is four o'clock and `"Jan"` on an axis of months is January. A number under ten
 * thousand is such a reading, and so is a string with no digit in it.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class GuideValuesTest {

  /** The ticks the axis was given, as written. */
  private fun ticks(x: String): String {
    val spec =
      """{"data":{"values":[{"t":"2019-01-04","v":2,"c":"x"}]},"mark":"point",
         "encoding":{"x":$x,"y":{"field":"v","type":"quantitative"}}}"""
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    val values =
      (compiled.fields["axes"] as VegaValue.Arr)
        .values
        .map { it as VegaValue.Obj }
        .firstNotNullOf { it.fields["values"] }
    return VegaJson.write(values).replace(Regex("""\n\s*"""), "")
  }

  /** The reported shape: an axis told to tick at two dates written out. */
  @Test
  fun `a date written out becomes the expression that builds it`() {
    assertEquals(
      """[{"signal": "datetime(2019, 0, 4, 0, 0, 0, 0)"},""" +
        """{"signal": "datetime(2019, 1, 1, 0, 0, 0, 0)"}]""",
      ticks(
        """{"field":"t","type":"temporal","axis":{"values":[
             {"year":2019,"month":"Jan","date":4},{"year":2019,"month":"Feb","date":1}]}}"""
      ),
    )
  }

  /** A date written as **text** over an instant is text until `datetime` builds it. */
  @Test
  fun `a date written as text becomes an expression too`() {
    assertEquals(
      """[{"signal": "datetime(\"2019-01-04\")"},{"signal": "datetime(\"2019-02-01\")"}]""",
      ticks("""{"field":"t","type":"temporal","axis":{"values":["2019-01-04","2019-02-01"]}}"""),
    )
  }

  /** And a number over an instant is epoch milliseconds, which still has to be built. */
  @Test
  fun `a number over an instant becomes an expression`() {
    assertEquals(
      """[{"signal": "datetime(4)"},{"signal": "datetime(8)"}]""",
      ticks("""{"field":"t","type":"temporal","axis":{"values":[4,8]}}"""),
    )
  }

  /** A measured column's ticks are numbers and stay numbers, which is every other chart. */
  @Test
  fun `numbers over a measure are left as they are`() {
    assertEquals(
      "[1,2,3]",
      ticks("""{"field":"v","type":"quantitative","axis":{"values":[1,2,3]}}"""),
    )
  }

  /** So do a category's own labels. */
  @Test
  fun `strings over a category are left as they are`() {
    assertEquals(
      """["x","y"]""",
      ticks("""{"field":"c","type":"nominal","axis":{"values":["x","y"]}}"""),
    )
  }

  /** A **reading** of a single unit: four on an axis of hours is four o'clock. */
  @Test
  fun `a number on an axis of hours is a reading of the clock`() {
    assertEquals(
      """[{"signal": "datetime(2012, 0, 1, 4, 0, 0, 0)"},""" +
        """{"signal": "datetime(2012, 0, 1, 8, 0, 0, 0)"}]""",
      ticks("""{"field":"t","type":"temporal","timeUnit":"hours","axis":{"values":[4,8]}}"""),
    )
  }

  /** And a name on an axis of months is that month. */
  @Test
  fun `a name on an axis of months is that month`() {
    assertEquals(
      """[{"signal": "datetime(2012, 0, 1, 0, 0, 0, 0)"},""" +
        """{"signal": "datetime(2012, 1, 1, 0, 0, 0, 0)"}]""",
      ticks(
        """{"field":"t","type":"temporal","timeUnit":"month","axis":{"values":["Jan","Feb"]}}"""
      ),
    )
  }

  /**
   * `normalizeTimeUnit` reads the `utc` out of the unit's name before asking whether it is a single
   * one, so an axis of `utcmonth` reads its ticks as months just the same.
   */
  @Test
  fun `a universal unit is still a single unit`() {
    assertEquals(
      """[{"signal": "datetime(2012, 0, 1, 0, 0, 0, 0)"},""" +
        """{"signal": "datetime(2012, 1, 1, 0, 0, 0, 0)"}]""",
      ticks(
        """{"field":"t","type":"temporal","timeUnit":"utcmonth","axis":{"values":["Jan","Feb"]}}"""
      ),
    )
  }

  /** A unit of **several** fields has no single reading, so a number is a date as it stands. */
  @Test
  fun `a number on an axis of year-months is a date`() {
    assertEquals(
      """[{"signal": "datetime(4)"},{"signal": "datetime(8)"}]""",
      ticks("""{"field":"t","type":"temporal","timeUnit":"yearmonth","axis":{"values":[4,8]}}"""),
    )
  }

  /** Nor is a number too large to be a reading of one. */
  @Test
  fun `a large number on an axis of hours is a date`() {
    assertEquals(
      """[{"signal": "datetime(20000)"}]""",
      ticks("""{"field":"t","type":"temporal","timeUnit":"hours","axis":{"values":[20000]}}"""),
    )
  }

  /** A date the runtime can read is a date and not a reading, whatever unit the axis measures. */
  @Test
  fun `a date written out on an axis of months is still a date`() {
    assertEquals(
      """[{"signal": "datetime(\"2019-01-04\")"}]""",
      ticks(
        """{"field":"t","type":"temporal","timeUnit":"month","axis":{"values":["2019-01-04"]}}"""
      ),
    )
  }

  /** A **bucketed** unit is not one to read from: its values are the bucket's own edges. */
  @Test
  fun `a bucketed unit reads its ticks as dates`() {
    assertEquals(
      """[{"signal": "datetime(4)"}]""",
      ticks(
        """{"field":"t","type":"temporal","timeUnit":"binnedyearmonth","axis":{"values":[4]}}"""
      ),
    )
  }

  /**
   * And a bucketed unit is not a unit at all for this: over a column typed as a **category** there
   * is nothing temporal left, so the ticks are the categories they were written as. A unit that is
   * not bucketed still makes them instants, whatever the column is typed.
   */
  @Test
  fun `a bucketed unit over a category leaves its ticks alone`() {
    assertEquals(
      "[4]",
      ticks(
        """{"field":"t","type":"ordinal","timeUnit":"binnedyearmonth","axis":{"values":[4]}}"""
      ),
    )
    assertEquals(
      """[{"signal": "datetime(2012, 0, 1, 4, 0, 0, 0)"}]""",
      ticks("""{"field":"t","type":"ordinal","timeUnit":"hours","axis":{"values":[4]}}"""),
    )
  }

  /** A **legend** is told its ticks the same way and reads them the same way. */
  @Test
  fun `a legend's stated values are read the same way`() {
    val spec =
      """{"data":{"values":[{"t":"2019-01-04","v":2}]},"mark":"point",
         "encoding":{"x":{"field":"v","type":"quantitative"},
                     "color":{"field":"t","type":"temporal",
                              "legend":{"values":[{"year":2019,"month":"Jan","date":4}]}}}}"""
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    val values =
      (compiled.fields["legends"] as VegaValue.Arr)
        .values
        .map { it as VegaValue.Obj }
        .firstNotNullOf { it.fields["values"] }
    assertEquals(
      """[{"signal": "datetime(2019, 0, 4, 0, 0, 0, 0)"}]""",
      VegaJson.write(values).replace(Regex("""\n\s*"""), ""),
    )
  }
}
