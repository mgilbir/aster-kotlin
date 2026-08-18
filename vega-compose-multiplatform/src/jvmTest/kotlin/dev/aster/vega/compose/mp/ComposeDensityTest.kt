package dev.aster.vega.compose.mp

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.runtime.compile.SpecCompiler
import dev.aster.vega.runtime.load.DenyLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.Image

/**
 * What a chart looks like at a density other than 1 — where every existing test pinned it.
 *
 * Two arithmetic slips lived in that blind spot, and they pulled in opposite directions. Glyphs
 * were sized in `sp`, which is pixels times the density times the font scale, and then drawn inside
 * a scope the density had **already** scaled: at 3x every label came out three times the size of
 * the box the layout had reserved for it. And `SceneFit.None` drew at a scale of 1 where a scene
 * unit is a `dp`, so the geometry came out at a third of its size while the text did not.
 *
 * Neither is visible at density 1, which is why this file measures **ink** at 1x and at 3x and
 * compares the two. A chart is a drawing whose parts have to agree with each other; the assertion
 * is that the whole of it — bars, axes and labels alike — scales by exactly the density and by
 * nothing else.
 *
 * No golden images, for the reason `DrawScopeTargetTest` gives: rasterisation belongs to Skia and
 * changes between its versions. A ratio between two rasters produced by the same Skia is stable in
 * a way a byte comparison is not.
 */
class ComposeDensityTest {

  /** A chart with a long axis label on it, so the text's own extent is part of what is measured. */
  private val chart =
    """
    {"${'$'}schema": "https://vega.github.io/schema/vega/v6.json",
     "width": 120, "height": 60, "padding": 5,
     "background": "white",
     "data": [{"name": "t", "values": [{"c": "Measurement", "v": 2}, {"c": "Total", "v": 1}]}],
     "scales": [
       {"name": "x", "type": "band", "domain": {"data": "t", "field": "c"}, "range": "width"},
       {"name": "y", "domain": {"data": "t", "field": "v"}, "range": "height"}],
     "axes": [{"orient": "bottom", "scale": "x"}],
     "marks": [{"type": "rect", "from": {"data": "t"}, "encode": {"enter": {
       "x": {"scale": "x", "field": "c"}, "width": {"scale": "x", "band": 1},
       "y": {"scale": "y", "field": "v"}, "y2": {"scale": "y", "value": 0},
       "fill": {"value": "black"}}}}]}
    """

  /** The ink in a raster: the bounding box of every pixel that is not the white background. */
  private data class Ink(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val count: Int,
    /** The canvas it was drawn on, which is the scene's own size at this density. */
    val width: Int,
    val height: Int,
  )

  private fun ink(density: Float, fontScale: Float = 1f, fit: SceneFit = SceneFit.Contain): Ink {
    val densities = Density(density, fontScale)
    val measurer =
      TextMeasurer(
        defaultFontFamilyResolver = createFontFamilyResolver(),
        defaultDensity = densities,
        defaultLayoutDirection = LayoutDirection.Ltr,
      )
    val engine = ComposeTextEngine(measurer, densities)

    val compiled = SpecCompiler(textEngine = engine, loader = DenyLoader).compileJson(chart)
    val complaints =
      compiled.diagnostics.filter {
        it.severity == DiagnosticSeverity.ERROR || it.severity == DiagnosticSeverity.FATAL
      }
    assertTrue(complaints.isEmpty(), "compiled with errors: ${complaints.map { it.message }}")
    val scene = requireNotNull(compiled.scene) { "no scene" }

    // The canvas is the scene's own size in **pixels**, which is what a host gives it: the
    // composable
    // asks for `scene.width.dp` and the platform turns that into pixels with the density.
    val width = (scene.width * density).toInt()
    val height = (scene.height * density).toInt()
    val composed = ImageComposeScene(width, height, density = densities)
    try {
      composed.setContent { VegaChart(scene, fit = fit, textEngine = engine) }
      val bytes = composed.render().encodeToData()!!.bytes
      val pixels = Image.makeFromEncoded(bytes).toComposeImageBitmap().toPixelMap()

      var left = width
      var top = height
      var right = -1
      var bottom = -1
      var count = 0
      for (y in 0 until height) {
        for (x in 0 until width) {
          val colour = pixels[x, y]
          val dark = colour.red < 0.75f || colour.green < 0.75f || colour.blue < 0.75f
          if (!dark) continue
          count++
          if (x < left) left = x
          if (x > right) right = x
          if (y < top) top = y
          if (y > bottom) bottom = y
        }
      }
      return Ink(left, top, right, bottom, count, width, height)
    } finally {
      composed.close()
    }
  }

  /**
   * Three times, within two per cent and two pixels.
   *
   * The tolerance is relative because the comparison is not of one drawing scaled up but of two
   * drawings: the layout is recomputed at each density, and a font hinted at 33 pixels reports very
   * slightly different advances than the same font at 11, so the label's box — and with it the
   * whole scene — comes out a fraction wider. That is correct behaviour and is exactly what
   * measuring with the platform's own metrics means. Nine times, which is the density applied
   * twice, is not within any tolerance of this.
   */
  private fun assertThreeTimes(one: Int, three: Int, what: String, context: String) {
    val expected = one * 3.0
    assertEquals(expected, three.toDouble(), expected * 0.02 + 2.0, "$what: $context")
  }

  @Test
  fun `a chart at 3x is the same chart, three times the size`() {
    val one = ink(density = 1f)
    val three = ink(density = 3f)

    assertTrue(one.count > 0 && three.count > 0, "nothing drawn: $one / $three")
    val context = "$one vs $three"
    assertThreeTimes(one.left, three.left, "left edge", context)
    assertThreeTimes(one.top, three.top, "top edge", context)
    assertThreeTimes(one.right, three.right, "right edge", context)
    assertThreeTimes(one.bottom, three.bottom, "bottom edge", context)
    assertThreeTimes(one.width, three.width, "canvas width", context)
    assertThreeTimes(one.height, three.height, "canvas height", context)
  }

  @Test
  fun `the label does not outgrow its chart at 3x`() {
    // The tell for the density being applied twice, stated as the thing a reader would see: a label
    // three times the size of the box reserved for it runs off the bottom of the chart.
    val three = ink(density = 3f)
    assertTrue(three.bottom < three.height - 1, "ink reaches the bottom edge: $three")
    assertTrue(three.right < three.width - 1, "ink reaches the right edge: $three")
  }

  @Test
  fun `drawn at its own size, a scene unit is a dp`() {
    // `SceneFit.None` means "its own size", and a scene's own size is in dp. At 3x that is three
    // times
    // as many pixels, so the ink fills the canvas the same way it does at 1x — where a scale of 1
    // left
    // the drawing in the top-left ninth of it.
    val one = ink(density = 1f, fit = SceneFit.None)
    val three = ink(density = 3f, fit = SceneFit.None)

    assertThreeTimes(one.right, three.right, "right edge", "$one vs $three")
    assertThreeTimes(one.bottom, three.bottom, "bottom edge", "$one vs $three")
  }

  @Test
  fun `a larger text setting reserves more room rather than overlapping`() {
    val plain = ink(density = 1f)
    val enlarged = ink(density = 1f, fontScale = 2f)

    // Two things, and the second is the one that matters. More ink, because the glyphs are larger.
    // And
    // a **taller chart**: the axis measured its labels through the engine, which measured them with
    // the
    // font scale, so it reserved more room and `autosize: pad` grew the scene to hold it. That is
    // the
    // difference between a legible chart at a 2x text setting and a chart whose labels overlap.
    assertTrue(
      enlarged.count > plain.count,
      "doubling the text scale drew no more ink: $plain vs $enlarged",
    )
    assertTrue(
      enlarged.height > plain.height,
      "the axis reserved no more room for larger labels: $plain vs $enlarged",
    )
    assertTrue(
      enlarged.bottom < enlarged.height,
      "the enlarged label runs off its own canvas: $enlarged",
    )
  }
}
