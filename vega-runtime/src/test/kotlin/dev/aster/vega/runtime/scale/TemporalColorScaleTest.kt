package dev.aster.vega.runtime.scale

import dev.aster.vega.runtime.compile.SpecCompiler
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.ScenePaint
import dev.aster.vega.scene.SymbolNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A colour scale over **instants**: `{"type": "time", "range": {"scheme": …}}`.
 *
 * `SUPPORTED_FEATURES.md` said the Vega-Lite side compiled this exactly as upstream does while "the
 * **runtime** cannot yet build a time scale whose range is a colour scheme". It can, and there is
 * no sign of when it started: `ScaleResolver` routes `TIME` and `UTC` to `buildSequentialColor` the
 * moment the range holds colours, exactly as it routes `LINEAR`. The claim was left behind by the
 * code and nothing was checking it — a limitation that no longer exists reads to an adopter as a
 * reason to work around something that works.
 *
 * A time scale is a linear scale over epoch milliseconds, so this is less a feature than an absence
 * of a special case; what the test pins is that the absence holds, end to end, through the
 * compiler.
 */
class TemporalColorScaleTest {

  private val json =
    """
    {
      "width": 120, "height": 60, "padding": 0, "autosize": "none",
      "data": [{"name": "t",
                "values": [{"d": "2026-01-01", "i": 0},
                           {"d": "2026-07-02", "i": 1},
                           {"d": "2026-12-31", "i": 2}],
                "format": {"parse": {"d": "date"}}}],
      "scales": [{"name": "c", "type": "time",
                  "domain": {"data": "t", "field": "d"},
                  "range": {"scheme": "viridis"}}],
      "marks": [{
        "type": "symbol", "from": {"data": "t"},
        "encode": {"enter": {"x": {"field": "i"}, "y": {"value": 30},
                             "size": {"value": 100},
                             "fill": {"scale": "c", "field": "d"}}}
      }]
    }
    """
      .trimIndent()

  private fun fills(root: SceneNode): List<String> {
    val out = mutableListOf<String>()
    fun walk(node: SceneNode) {
      if (node is SymbolNode) {
        out += ((node.fill?.paint as? ScenePaint.Solid)?.color?.toCssHex() ?: "none")
      }
      if (node is dev.aster.vega.scene.GroupNode) node.children.forEach { walk(it) }
    }
    walk(root)
    return out
  }

  @Test
  fun `a time scale with a scheme range resolves to a colour ramp`() {
    val compiled = SpecCompiler().compileJson(json)
    assertEquals(
      emptyList<String>(),
      compiled.diagnostics.map { it.message },
      "a temporal colour scale was reported as something this engine could not do",
    )
    val scale = compiled.scales["c"]
    assertTrue(
      scale is SequentialColorScale,
      "a time scale with a scheme range resolved to ${scale?.let { it::class.simpleName }}",
    )
  }

  /**
   * The colours are viridis's own, at the ends and in the middle.
   *
   * Read off the scheme rather than off this implementation: viridis runs `#440154` to `#fde725`,
   * and the middle of a year lands in its middle. Asserting the *endpoints alone* would pass for a
   * scale that mapped everything to one end and happened to be asked at both.
   */
  @Test
  fun `each instant takes its place along the scheme`() {
    val compiled = SpecCompiler().compileJson(json)
    val drawn = fills(requireNotNull(compiled.scene).root)
    assertEquals(3, drawn.size, "expected one symbol per row")
    assertEquals("#440154", drawn.first(), "the earliest instant is not at the start of the ramp")
    assertEquals("#fde725", drawn.last(), "the latest instant is not at the end of the ramp")
    assertTrue(
      drawn[1] != drawn.first() && drawn[1] != drawn.last(),
      "the middle instant took an endpoint colour, so the ramp is not being walked: $drawn",
    )
  }

  /** `utc` is the same scale in a fixed zone, and takes the same path through the resolver. */
  @Test
  fun `a utc scale with a scheme range resolves the same way`() {
    val compiled =
      SpecCompiler().compileJson(json.replace("\"type\": \"time\"", "\"type\": \"utc\""))
    assertTrue(compiled.scales["c"] is SequentialColorScale)
    assertEquals(emptyList<String>(), compiled.diagnostics.map { it.message })
  }

  /**
   * A time scale with a **numeric** range is still positional, which is what says the routing reads
   * the range rather than the type.
   *
   * Without this the two above would pass for an engine that turned every time scale into a colour
   * ramp, which would place nothing.
   */
  @Test
  fun `a time scale with a numeric range is unaffected`() {
    val compiled =
      SpecCompiler().compileJson(json.replace("""{"scheme": "viridis"}""", """[0, 100]"""))
    val scale = compiled.scales["c"]
    assertTrue(
      scale is TimeScale,
      "a numeric range gave ${scale?.let { it::class.simpleName }} rather than a time scale",
    )
  }
}
