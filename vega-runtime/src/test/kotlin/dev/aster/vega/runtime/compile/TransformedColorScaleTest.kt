package dev.aster.vega.runtime.compile

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.runtime.scale.SequentialColorScale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A `pow`, `sqrt`, `log` or `symlog` scale whose range is **colours**.
 *
 * A continuous scale interpolates whatever its range holds, and d3 interpolates colours as readily
 * as numbers — so a `sqrt` scale over `[0, 100]` paints 25 the exact midpoint colour, the square
 * roots making it the midpoint. This engine read a colour range only on `linear`, `time`, `utc` and
 * `sequential`; the four transformed types demanded numbers and refused. A choropleth shaded by a
 * `pow` scale therefore had no colour scale at all: three `scale()` calls reported as naming a
 * scale the specification does not define, and a map drawn in the default fill.
 *
 * Every colour here was read off a live upstream view of the same scale.
 */
class TransformedColorScaleTest {

  private fun scaled(scale: String, values: List<Double>): String {
    val compiled =
      SpecCompiler(VegaHeadlessTextEngine())
        .compileJson(
          """
          {
            "width": 100, "height": 50, "padding": 0, "autosize": "none",
            "scales": [$scale]
          }
          """
            .trimIndent()
        )
    assertEquals(
      emptyList<String>(),
      compiled.diagnostics.filter { it.severity >= DiagnosticSeverity.ERROR }.map { it.message },
    )
    val built = compiled.scales.values.single() as SequentialColorScale
    return values.joinToString(" ") { "$it->${built.colorAt(it)?.toCssHex()}" }
  }

  /** `sqrt`: the halfway colour lands at a quarter of the domain, not at its half. */
  @Test
  fun `a sqrt scale walks its ramp in root space`() {
    assertEquals(
      "0.0->#ff0000 25.0->#800080 50.0->#4b00b4 75.0->#2200dd 100.0->#0000ff",
      scaled(
        """{"name": "c", "type": "sqrt", "domain": [0, 100],
            "range": ["#ff0000", "#0000ff"]}""",
        listOf(0.0, 25.0, 50.0, 75.0, 100.0),
      ),
    )
  }

  /** `pow` with a small exponent, which is the shape a choropleth uses to spread a long tail. */
  @Test
  fun `a pow scale walks its ramp in its own exponent`() {
    assertEquals(
      "0.0->#ff0000 25.0->#2100de 50.0->#1100ee 75.0->#0700f8 100.0->#0000ff",
      scaled(
        """{"name": "c", "type": "pow", "exponent": 0.1, "domain": [0, 100],
            "range": ["#ff0000", "#0000ff"]}""",
        listOf(0.0, 25.0, 50.0, 75.0, 100.0),
      ),
    )
  }

  /**
   * `log`, whose domain stays on one side of zero.
   *
   * Sampled either side of the middle rather than on it. Upstream's own ramp answers one unit of
   * blue lower at exactly 10 — `rgb(128, 0, 127)` where d3's `interpolateRgb` at a half answers
   * `rgb(128, 0, 128)` — because its transform lands a hair under the midpoint there. Every other
   * sample agrees to the unit, and pinning a single floating-point crumb would be pinning noise.
   */
  @Test
  fun `a log scale walks its ramp in log space`() {
    assertEquals(
      "1.0->#ff0000 2.0->#d90026 5.0->#a60059 50.0->#2600d9 100.0->#0000ff",
      scaled(
        """{"name": "c", "type": "log", "domain": [1, 100],
            "range": ["#ff0000", "#0000ff"]}""",
        listOf(1.0, 2.0, 5.0, 50.0, 100.0),
      ),
    )
  }

  /** `symlog`, which does reach zero and both signs, and puts zero at the middle. */
  @Test
  fun `a symlog scale walks its ramp through zero`() {
    assertEquals(
      "-100.0->#ff0000 -1.0->#93006c 0.0->#800080 1.0->#6c0093 100.0->#0000ff",
      scaled(
        """{"name": "c", "type": "symlog", "domain": [-100, 100],
            "range": ["#ff0000", "#0000ff"]}""",
        listOf(-100.0, -1.0, 0.0, 1.0, 100.0),
      ),
    )
  }

  /** And a `linear` one is unchanged, which is the arm the other four used to take. */
  @Test
  fun `a linear colour scale is unchanged`() {
    assertEquals(
      "0.0->#ff0000 50.0->#800080 100.0->#0000ff",
      scaled(
        """{"name": "c", "type": "linear", "domain": [0, 100],
            "range": ["#ff0000", "#0000ff"]}""",
        listOf(0.0, 50.0, 100.0),
      ),
    )
  }
}
