package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A themed value of one of the nine is written onto the axis whatever block it came from.
 *
 * ```js
 * } else if (
 *   // 1. Axis config that aren't available in Vega
 *   !(configFrom === 'vgAxisConfig') ||
 *   // 2. Certain properties are always included (see `propsToAlwaysIncludeConfig`'s declaration for more details)
 *   (propsToAlwaysIncludeConfig.has(property) && hasConfigValue) ||
 *   // 3. Conditional axis values and signals
 *   isConditionalAxisValue(configValue) ||
 *   isSignalRef(configValue)
 * ) {
 *   axisComponent.set(property, configValue, false);
 * }
 * ```
 *
 * The second arm stands beside the block's own source: `grid`, `translate`, `format`, `formatType`,
 * `orient`, `labelExpr`, `tickCount`, `position` and `tickMinStep` are written out **even from a
 * block Vega knows**, because Vega either has no such property or means something else by it.
 *
 * This engine wrote them out only where the property also had a rule with something to say, so a
 * theme asking every date axis for five ticks was read and dropped: `tickCount` has no rule on a
 * band scale — there is no continuum to count along — and `config.axisX` is a block Vega knows.
 *
 * A property that is **not** one of the nine is still left to Vega where the theme names a block it
 * reads: writing it here would settle it for this axis alone.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class ThemedTickCountTest {

  /** The two axes, with the properties a theme might reach. */
  private fun axes(config: String): String {
    val spec =
      """{"data":{"values":[{"a":"2020-01-01","b":2}]},"mark":"point",
         "encoding":{"x":{"field":"a","type":"ordinal","timeUnit":"yearmonthdate"},
                     "y":{"field":"b","type":"quantitative"}},
         "config":$config}"""
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    return (compiled.fields["axes"] as VegaValue.Arr).values.take(2).joinToString(" | ") { entry ->
      entry as VegaValue.Obj
      listOf("scale", "tickCount", "labelPadding", "tickMinStep")
        .mapNotNull { key ->
          entry.fields[key]?.let {
            "$key=${VegaJson.write(it).replace(Regex("""\n\s*"""), "")}"
          }
        }
        .joinToString(",")
    }
  }

  /** What a date axis derives for itself: a step of one day, and no count along a band. */
  private val dayStep =
    """tickMinStep={"signal": "datetime(2001, 0, 2, 0, 0, 0, 0) - """ +
      """datetime(2001, 0, 1, 0, 0, 0, 0)"}"""

  /** The reported shape: a theme asking a band axis for a number of ticks. */
  @Test
  fun `a themed tick count reaches a band axis`() {
    assertEquals(
      """scale="y",tickCount={"signal": "ceil(height/40)"} | scale="x",tickCount=5,$dayStep""",
      axes("""{"axisX":{"tickCount":5}}"""),
    )
  }

  /** A property Vega applies for itself is still left to Vega. */
  @Test
  fun `a themed label padding is left to Vega`() {
    assertEquals(
      """scale="y",tickCount={"signal": "ceil(height/40)"} | scale="x",$dayStep""",
      axes("""{"axisX":{"labelPadding":45}}"""),
    )
  }

  /** A themed value of one of the nine beats the rule's own answer. */
  @Test
  fun `a themed tick step beats the one the unit derives`() {
    assertEquals(
      """scale="y",tickCount={"signal": "ceil(height/40)"} | scale="x",tickMinStep=3""",
      axes("""{"axisX":{"tickMinStep":3}}"""),
    )
  }

  /** And the block it is written in makes no difference: `config.axis` reaches both axes. */
  @Test
  fun `a tick count themed for every axis reaches both`() {
    assertEquals(
      """scale="y",tickCount=5 | scale="x",tickCount=5,$dayStep""",
      axes("""{"axis":{"tickCount":5}}"""),
    )
  }
}
