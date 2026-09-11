package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A compound time unit is taken apart by **filtering** the parts, not by reading the name.
 *
 * ```js
 * export function getTimeUnitParts(timeUnit) {
 *   return TIMEUNIT_PARTS.filter((part) => containsTimeUnit(timeUnit, part));
 * }
 * ```
 * ```js
 * // exclude milliseconds
 * if (index > 0 && timeUnit === 'seconds' && fullTimeUnit.charAt(index - 1) === 'i') return false;
 * // exclude dayofyear
 * if (fullTimeUnit.length > index + 3 && timeUnit === 'day' && fullTimeUnit.charAt(index + 3) === 'o') {
 *   return false;
 * }
 * if (index > 0 && timeUnit === 'year' && fullTimeUnit.charAt(index - 1) === 'f') return false;
 * ```
 *
 * Three names live **inside** another name: `milliseconds` holds `seconds`, and `dayofyear` holds
 * both `day` and `year`. `containsTimeUnit` writes those three out by hand, and the parts come back
 * in the order the index lists them however the unit was spelled.
 *
 * This compiler read the name left to right and took the first unit that fitted, so `yeardayofyear`
 * came out as a year and a **day**: the transform bucketed the day of the week rather than the day
 * of the year, and the caption said so. One specification in the wild corpus buckets that way.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class TimeUnitPartsTest {

  /** The `timeunit` transform's units, and the axis title that names them. */
  private fun bucketed(unit: String): String {
    val spec =
      """{"data":{"values":[{"t":"2020-01-04","v":2}]},"mark":"point",
         "encoding":{"x":{"field":"t","type":"temporal","timeUnit":"$unit"},
                     "y":{"field":"v","type":"quantitative"}}}"""
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    val units =
      (compiled.fields["data"] as VegaValue.Arr)
        .values
        .flatMap {
          (it as VegaValue.Obj)
            .fields["transform"]
            ?.let { t -> (t as VegaValue.Arr).values }
            .orEmpty()
        }
        .map { it as VegaValue.Obj }
        .firstOrNull { it.string("type") == "timeunit" }
        ?.fields
        ?.get("units")
    val title =
      (compiled.fields["axes"] as VegaValue.Arr)
        .values
        .map { it as VegaValue.Obj }
        .firstNotNullOfOrNull { it.string("title") }
    return "${units?.let { VegaJson.write(it).replace(Regex("""\n\s*"""), "") } ?: "-"} | $title"
  }

  /** The reported shape: a year and the day **of the year**, not the day of the week. */
  @Test
  fun `a year and a day of the year are two parts`() {
    assertEquals("""["year","dayofyear"] | t (year-dayofyear)""", bucketed("yeardayofyear"))
  }

  /** On its own too. */
  @Test
  fun `a day of the year on its own is one part`() {
    assertEquals("""["dayofyear"] | t (dayofyear)""", bucketed("dayofyear"))
  }

  /** And the day of the **week** is still itself. */
  @Test
  fun `a day is still a day`() {
    assertEquals("""["day"] | t (day)""", bucketed("day"))
  }

  /** A week and a day are two parts, the `day` here being no part of anything longer. */
  @Test
  fun `a week and a day are two parts`() {
    assertEquals("""["week","day"] | t (week-day)""", bucketed("weekday"))
  }

  /** `milliseconds` holds `seconds`, which is the second name written out by hand. */
  @Test
  fun `milliseconds are not seconds`() {
    assertEquals("""["milliseconds"] | t (milliseconds)""", bucketed("milliseconds"))
  }

  /** Where both are meant, both are there. */
  @Test
  fun `seconds and milliseconds are two parts`() {
    assertEquals(
      """["seconds","milliseconds"] | t (seconds-milliseconds)""",
      bucketed("secondsmilliseconds"),
    )
  }

  /** The ordinary compound, which is what must not change. */
  @Test
  fun `a year, a month and a date are three parts`() {
    assertEquals("""["year","month","date"] | t (year-month-date)""", bucketed("yearmonthdate"))
  }

  /** The `utc` is read out of the name before the parts are taken, so it says the same. */
  @Test
  fun `a universal unit is taken apart the same way`() {
    assertEquals("""["year","dayofyear"] | t (year-dayofyear)""", bucketed("utcyeardayofyear"))
  }
}
