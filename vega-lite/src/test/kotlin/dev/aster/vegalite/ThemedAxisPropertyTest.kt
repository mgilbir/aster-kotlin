package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * An axis property the theme states in a block **Vega** knows is left off the axis.
 *
 * ```js
 * const {configValue = undefined, configFrom = undefined} = …getAxisConfig(property, …);
 * if (hasValue && !hasConfigValue) {
 *   // only set property if it is explicitly set or has no config value
 *   // (otherwise we will accidentally override config)
 *   axisComponent.set(property, value, explicit);
 * } else if (!(configFrom === 'vgAxisConfig') || …) {
 *   axisComponent.set(property, configValue, false);
 * }
 * ```
 *
 * `config.axis`, `config.axisX` and `config.axisBottom` all go out in Vega's own config block, and
 * Vega applies them from there to every axis at once — so writing a *derived* value onto this axis
 * as well would settle the property for it alone, and settle it with a default. A theme asking for
 * `labelOverlap: false` was overruled by the `true` this compiler had worked out.
 *
 * The **Vega-Lite-only** blocks are the other half. `config.axisQuantitative` and its per-direction
 * twins are named after a kind of scale rather than a place, Vega has never heard of them, and
 * their values therefore have to be written onto the axis or nothing would apply them at all. That
 * is why the two families are kept apart rather than merged into one lookup.
 *
 * This is the axis half of the same rule `ThemedLegendPropertyTest` describes. One arm is **not**
 * ported: `propsToAlwaysIncludeConfig` — `grid`, `translate`, `format`, `formatType`, `orient`,
 * `labelExpr`, `tickCount`, `position` and `tickMinStep` — has the theme's value written out even
 * from a Vega block, where this compiler still writes its own derived one. No specification in the
 * wild corpus exercises it, and the properties above are what it reaches.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class ThemedAxisPropertyTest {

  /** Each drawn axis (the gridline axes left out) as `scale[property=value, …]`. */
  private fun axes(config: String, encoding: String, mark: String = "point"): List<String> {
    val theme = if (config.isEmpty()) "" else ""","config":$config"""
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"a":1,"b":2,"c":"x"}]},"mark":"$mark"$theme,
               "encoding":$encoding}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    return (compiled.fields["axes"] as VegaValue.Arr)
      .values
      .mapNotNull { it as? VegaValue.Obj }
      .filterNot { it.fields.containsKey("gridScale") }
      .map { axis ->
        listOf("labelOverlap", "labelFlush", "labelAngle", "labelAlign", "labelBaseline")
          .mapNotNull { key -> axis.fields[key]?.let { "$key=${text(it)}" } }
          .joinToString(",", prefix = "${axis.string("scale")}[", postfix = "]")
      }
  }

  private fun text(value: VegaValue) =
    when (value) {
      is VegaValue.Str -> value.value
      is VegaValue.Bool -> value.value.toString()
      is VegaValue.Num -> canonical(value.value)
      else -> value.toString()
    }

  private fun canonical(number: Double) =
    if (number == number.toLong().toDouble()) number.toLong().toString() else number.toString()

  private val measured =
    """{"x":{"field":"a","type":"quantitative"},"y":{"field":"b","type":"quantitative"}}"""

  /** With no theme, the derived properties are written as they always were. */
  @Test
  fun `a chart with no theme keeps its derived properties`() {
    assertEquals(
      listOf("x[labelOverlap=true,labelFlush=true]", "y[labelOverlap=true]"),
      axes("", measured),
    )
  }

  /** The reported shape: a theme that turns label thinning off for every axis. */
  @Test
  fun `a theme that states label overlap leaves it off both axes`() {
    assertEquals(
      listOf("x[labelFlush=true]", "y[]"),
      axes("""{"axis":{"labelOverlap":false}}""", measured),
    )
  }

  /** One direction at a time, `config.axisX` being a Vega block too. */
  @Test
  fun `a theme that states it for one direction leaves the other alone`() {
    assertEquals(
      listOf("x[labelFlush=true]", "y[labelOverlap=true]"),
      axes("""{"axisX":{"labelOverlap":false}}""", measured),
    )
  }

  /** And one edge, which is the block named after where the axis is drawn. */
  @Test
  fun `a theme that states a property for one edge leaves it off that axis`() {
    assertEquals(
      listOf("x[labelOverlap=true]", "y[labelOverlap=true]"),
      axes("""{"axisBottom":{"labelFlush":false}}""", measured),
      "the bottom axis is the x one, and its flush is now the theme's to settle",
    )
  }

  /**
   * A **Vega-Lite-only** block is the other half: Vega has never heard of
   * `config.axisQuantitative`, so its value is written onto the axis instead of being left to a
   * config that would ignore it.
   */
  @Test
  fun `a theme that states it for a kind of scale writes it onto the axis`() {
    assertEquals(
      listOf("x[labelOverlap=false,labelFlush=true]", "y[labelOverlap=false]"),
      axes("""{"axisQuantitative":{"labelOverlap":false}}""", measured),
    )
  }

  /**
   * A themed **label angle** goes the same way, and the alignment *derived from* it does not: that
   * one is this compiler's answer to a question the theme did not ask.
   */
  @Test
  fun `a themed label angle leaves the angle off and keeps what it implies`() {
    assertEquals(
      listOf(
        "x[labelOverlap=true,labelFlush=true,labelAlign=left,labelBaseline=top]",
        "y[labelOverlap=true,labelAlign=right]",
      ),
      axes("""{"axis":{"labelAngle":45}}""", measured),
    )
    assertEquals(
      listOf(
        "x[labelOverlap=true,labelFlush=true,labelAngle=45,labelAlign=left,labelBaseline=top]",
        "y[labelOverlap=true,labelAngle=45,labelAlign=right]",
      ),
      axes("""{"axisQuantitative":{"labelAngle":45}}""", measured),
      "and from a Vega-Lite-only block the angle is written out",
    )
  }

  /** A specification that states the property itself is explicit, and outranks the theme. */
  @Test
  fun `a stated property outranks the theme`() {
    assertEquals(
      listOf("x[labelOverlap=greedy,labelFlush=true]", "y[]"),
      axes(
        """{"axis":{"labelOverlap":false}}""",
        """{"x":{"field":"a","type":"quantitative","axis":{"labelOverlap":"greedy"}},
            "y":{"field":"b","type":"quantitative"}}""",
      ),
    )
  }
}
