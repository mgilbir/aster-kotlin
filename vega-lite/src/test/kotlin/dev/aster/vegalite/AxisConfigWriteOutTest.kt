package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A themed axis property Vega cannot apply is written onto the axis, on the parts it belongs to.
 *
 * ```js
 * } else if (hasConfigValue && configFrom !== 'vgAxisConfig') {
 *   // Add config value to axis component if there is no explicit value and the value is not from a
 *   // Vega config
 *   axisComponent.set(property, configValue, false);
 * }
 * ```
 *
 * A family named after a **scale** — `config.axisQuantitative`, `config.axisTemporal` — is
 * Vega-Lite's own, and Vega has never heard of it: a property found there has to be resolved onto
 * this axis or nothing acts on it. A family Vega *does* know is left to Vega, since writing a
 * derived value beside it would settle the property for one axis and beat the theme with a default.
 *
 * This engine wrote out only the handful of properties it had a rule for, so a theme colouring
 * every measured axis or turning its labels to a stated font was read and dropped. Five
 * specifications in the wild corpus theme their axes that way.
 *
 * Which **part** each property lands on is `AXIS_PROPERTY_TYPE`, and four of its `both` entries
 * were being treated as the axis proper's alone — `tickOffset`, upstream's comment says, is "needed
 * to be applied to grid axis too, so the grid will align with ticks".
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class AxisConfigWriteOutTest {

  /** Each axis as `part|properties`, with the ones every axis carries left out. */
  private fun axes(config: String, type: String = "quantitative"): List<String> {
    val spec =
      """{"data":{"values":[{"a":1,"b":2}]},"config":$config,"mark":"point",
         "encoding":{"x":{"field":"a","type":"$type"},
                     "y":{"field":"b","type":"quantitative","axis":null}}}"""
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    val common =
      setOf(
        "scale",
        "orient",
        "gridScale",
        "tickCount",
        "domain",
        "labels",
        "aria",
        "maxExtent",
        "minExtent",
        "ticks",
        "zindex",
        "title",
        "labelFlush",
        "labelOverlap",
      )
    return (compiled.fields["axes"] as VegaValue.Arr)
      .values
      .map { it as VegaValue.Obj }
      .map { axis ->
        val grid = axis.fields["grid"] == VegaValue.Bool(true)
        (if (grid) "grid|" else "main|") +
          axis.fields
            .filterKeys { it !in common && it != "grid" }
            .entries
            .joinToString(",") { "${it.key}=${VegaJson.write(it.value)}" }
      }
  }

  /** The reported shape: a theme for every **measured** axis, which Vega cannot apply itself. */
  @Test
  fun `a scale-named family is written onto the axis`() {
    assertEquals(
      listOf(
        """grid|gridColor="seagreen",tickOffset=10""",
        """main|domainColor="orange",labelFont="Comic Sans MS",labelOffset=10,tickOffset=10""",
      ),
      axes(
        """{"axisQuantitative":{"domainColor":"orange","gridColor":"seagreen",
           "labelFont":"Comic Sans MS","labelOffset":10,"tickOffset":10,"grid":true}}"""
      ),
    )
  }

  /** The same for the family named after the other kind of scale. */
  @Test
  fun `a temporal family is written onto a temporal axis`() {
    assertEquals(
      listOf("grid|", """main|domainColor="brown",labelColor="purple""""),
      axes(
        """{"axisTemporal":{"domainColor":"brown","labelColor":"purple","grid":true}}""",
        type = "temporal",
      ),
    )
  }

  /** A family Vega knows is left to Vega, which applies it from the configuration itself. */
  @Test
  fun `config axis is left for Vega to apply`() {
    assertEquals(
      listOf("grid|", "main|"),
      axes(
        """{"axis":{"domainColor":"orange","gridColor":"seagreen","tickOffset":10,"grid":true}}"""
      ),
    )
  }

  @Test
  fun `config axisX is left for Vega to apply`() {
    assertEquals(
      listOf("grid|", "main|"),
      axes("""{"axisX":{"domainColor":"orange","tickOffset":10,"grid":true}}"""),
    )
  }
}
