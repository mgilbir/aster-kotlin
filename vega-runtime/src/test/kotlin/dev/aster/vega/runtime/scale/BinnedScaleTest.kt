package dev.aster.vega.runtime.scale

import dev.aster.vega.model.VegaValue
import dev.aster.vega.runtime.compile.SpecCompiler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The four scales that map a continuous input onto a discrete output, against upstream.
 *
 * They differ only in where the cut points come from, and every expectation here was produced by
 * putting the same values through Vega. All four bisect **right**, so a value sitting exactly on a
 * cut point falls into the bucket above it — which is the one thing about them that is easy to get
 * backwards and invisible until a boundary value shows up in the data.
 */
class BinnedScaleTest {

  /** Runs the values through a scale in a real specification, as text, and joins the results. */
  private fun through(scale: String, values: List<Double>): String {
    val rows = values.joinToString(", ") { """{"v": $it}""" }
    val json =
      """
      {
        "width": 100, "height": 100,
        "data": [{"name": "t", "values": [$rows]}],
        "scales": [$scale],
        "marks": [{
          "type": "text", "from": {"data": "t"},
          "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                               "text": {"scale": "s", "field": "v"}}}
        }]
      }
      """
        .trimIndent()
    val compiled = SpecCompiler().compileJson(json)
    val texts = mutableListOf<String>()
    fun walk(node: dev.aster.vega.scene.SceneNode) {
      when (node) {
        is dev.aster.vega.scene.TextNode -> texts += node.layout.lines.joinToString("") { it.text }
        is dev.aster.vega.scene.GroupNode -> node.children.forEach { walk(it) }
        else -> Unit
      }
    }
    walk(compiled.scene!!.root)
    return texts.joinToString(" ")
  }

  /** Equal-width intervals: three cut points at 25, 50 and 75 for a four-value range. */
  @Test
  fun `quantize cuts the domain into equal intervals`() {
    assertEquals(
      "0 0 0 1 1 2 2 3 3 3 3",
      through(
        """{"name": "s", "type": "quantize", "domain": [0, 100], "range": [0, 1, 2, 3]}""",
        listOf(-5.0, 0.0, 24.0, 25.0, 49.0, 50.0, 74.0, 75.0, 99.0, 100.0, 120.0),
      ),
    )
  }

  /** Out of domain does not fall off either end: below the first cut is the first bucket. */
  @Test
  fun `quantize maps a colour range the same way`() {
    assertEquals(
      "#ff0000 #ff0000 #00ff00 #00ff00 #0000ff #0000ff",
      through(
        """{"name": "s", "type": "quantize", "domain": [0, 10],
            "range": ["#ff0000", "#00ff00", "#0000ff"]}""",
        listOf(0.0, 3.0, 3.4, 6.6, 7.0, 10.0),
      ),
    )
  }

  /**
   * Equal *counts*, not equal widths. The cut points are the quartiles of the domain itself, which
   * is why a quantile scale's domain is the whole column rather than its extent — here 2.5, 5 and
   * 10.5, which no equal-width scheme would produce.
   */
  @Test
  fun `quantile cuts the domain into equal counts`() {
    assertEquals(
      "0 0 0 1 2 2 3 3 3",
      through(
        """{"name": "s", "type": "quantile", "domain": [1, 2, 3, 5, 8, 13, 21],
            "range": [0, 1, 2, 3]}""",
        listOf(0.0, 1.0, 2.0, 3.0, 5.0, 8.0, 13.0, 21.0, 30.0),
      ),
    )
  }

  /** The domain *is* the cut points, so there is one more range value than domain value. */
  @Test
  fun `threshold takes its cut points literally`() {
    assertEquals(
      "a a b b c c",
      through(
        """{"name": "s", "type": "threshold", "domain": [10, 20], "range": ["a", "b", "c"]}""",
        listOf(0.0, 9.0, 10.0, 15.0, 20.0, 25.0),
      ),
    )
  }

  /**
   * The domain is bin *edges*, and the range **wraps**. A fourth bin reuses the first colour rather
   * than running out — which is ordinal behaviour, and the reason this is not just a threshold
   * scale with a different name.
   */
  @Test
  fun `bin-ordinal indexes an ordinal range and wraps`() {
    assertEquals(
      "x x y y z z x x",
      through(
        """{"name": "s", "type": "bin-ordinal", "domain": [0, 10, 20, 30],
            "range": ["x", "y", "z"]}""",
        listOf(0.0, 5.0, 10.0, 15.0, 20.0, 25.0, 30.0, 35.0),
      ),
    )
  }

  /** A scheme is sampled to the number of buckets rather than interpolated across them. */
  @Test
  fun `a scheme range gives one colour per bucket`() {
    val scale =
      QuantizeScale(
        "s",
        listOf(0.0, 1.0),
        listOf("a", "b", "c").map { VegaValue.Str(it) },
      )
    assertEquals(2, scale.thresholds.size)
    assertTrue(kotlin.math.abs(scale.thresholds[0] - 1.0 / 3) < 1e-12, scale.thresholds.toString())
    assertTrue(kotlin.math.abs(scale.thresholds[1] - 2.0 / 3) < 1e-12, scale.thresholds.toString())
    assertEquals(VegaValue.Str("a"), scale.scale(VegaValue.Num(0.3)))
    assertEquals(VegaValue.Str("b"), scale.scale(VegaValue.Num(0.4)))
    assertEquals(VegaValue.Str("c"), scale.scale(VegaValue.Num(0.9)))
  }

  /** A value below the first bin edge belongs to no bin at all, unlike the other three. */
  @Test
  fun `bin-ordinal maps a value below its first edge to nothing`() {
    val scale =
      BinOrdinalScale(
        "s",
        listOf(0.0, 10.0, 20.0),
        listOf("x", "y").map { VegaValue.Str(it) },
      )
    assertEquals(VegaValue.Null, scale.scale(VegaValue.Num(-1.0)))
    assertEquals(VegaValue.Str("x"), scale.scale(VegaValue.Num(0.0)))
  }

  /**
   * A threshold scale with the wrong number of range values is reported, not silently truncated.
   */
  @Test
  fun `a mismatched threshold range is reported`() {
    val compiled =
      SpecCompiler()
        .compileJson(
          """
          {
            "width": 100, "height": 100,
            "data": [{"name": "t", "values": [{"v": 1}]}],
            "scales": [{"name": "s", "type": "threshold",
                        "domain": [10, 20], "range": ["a", "b"]}],
            "marks": [{"type": "text", "from": {"data": "t"},
                       "encode": {"enter": {"text": {"scale": "s", "field": "v"}}}}]
          }
          """
            .trimIndent()
        )
    assertTrue(
      compiled.diagnostics.any { it.message.contains("one more range value") },
      compiled.diagnostics.toString(),
    )
  }
}
