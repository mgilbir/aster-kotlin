package dev.aster.vega.compose.mp

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.runtime.compile.SpecCompiler
import dev.aster.vega.runtime.load.DenyLoader
import dev.aster.vega.scene.MetricTextEngine
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.Image

/**
 * The Compose target, checked in pixels.
 *
 * [SceneWalkTest] asserts the *calls* a scene produces, which is where the renderer's logic lives —
 * but it never executes a line of Compose, so a target that built its paths or brushes wrong would
 * pass every one of those tests. That gap is not hypothetical: the Swift renderer had exactly it,
 * and two real bugs were hiding in it until pixels were sampled. These close it here.
 *
 * `ImageComposeScene` rasterises a composable with no window, no display and no device, so this
 * runs wherever `scripts/check.sh` does. What it exercises is Compose's own Skia backend — the same
 * one behind the Android, iOS and desktop targets — so a bug in the translation to `DrawScope`
 * shows up here rather than on a phone.
 *
 * No golden images. Rasterisation and antialiasing belong to Skia and change between its versions;
 * a byte-exact golden would fail on an upgrade that broke nothing, and the pressure would then be
 * to loosen the comparison. Sampling says what is actually being claimed.
 */
class DrawScopeTargetTest {

  private val width = 100
  private val height = 50

  private fun raster(json: String): Raster {
    val compiled =
      SpecCompiler(textEngine = MetricTextEngine(), loader = DenyLoader).compileJson(json)
    val complaints =
      compiled.diagnostics.filter {
        it.severity == DiagnosticSeverity.ERROR || it.severity == DiagnosticSeverity.FATAL
      }
    assertTrue(complaints.isEmpty(), "compiled with errors: ${complaints.map { m -> m.message }}")
    val scene = requireNotNull(compiled.scene) { "no scene" }

    // Density 1, so a pixel is a scene unit and the assertions below can be written in the
    // coordinates the specification uses.
    //
    // No size modifier is passed, deliberately: the chart takes the scene's own size, and these
    // tests
    // are what caught the consequence of it not doing so. A `Canvas` with no intrinsic size still
    // drew
    // solid fills while turning every gradient black, so the gradient case below is the guard.
    val composed = ImageComposeScene(width, height, density = Density(1f))
    try {
      composed.setContent { VegaChart(scene, fit = SceneFit.None) }
      val bytes = composed.render().encodeToData()!!.bytes
      return Raster(Image.makeFromEncoded(bytes).toComposeImageBitmap().toPixelMap())
    } finally {
      composed.close()
    }
  }

  private class Raster(private val pixels: androidx.compose.ui.graphics.PixelMap) {
    fun at(x: Int, y: Int): Triple<Int, Int, Int> {
      val colour = pixels[x, y]
      return Triple(
        (colour.red * 255f).toInt(),
        (colour.green * 255f).toInt(),
        (colour.blue * 255f).toInt(),
      )
    }

    fun isNear(x: Int, y: Int, r: Int, g: Int, b: Int, tolerance: Int = 3): Boolean {
      val (gr, gg, gb) = at(x, y)
      return abs(gr - r) <= tolerance && abs(gg - g) <= tolerance && abs(gb - b) <= tolerance
    }

    fun describe(x: Int, y: Int): String {
      val (r, g, b) = at(x, y)
      return "($x,$y) = rgb($r,$g,$b)"
    }
  }

  @Test
  fun `bars land where the scales say and the background fills the rest`() {
    val image =
      raster(
        """
        {"${'$'}schema": "https://vega.github.io/schema/vega/v6.json",
         "width": 100, "height": 50, "padding": 0,
         "background": "white",
         "data": [{"name": "t", "values": [{"c": "a", "v": 1}, {"c": "b", "v": 2}]}],
         "scales": [
           {"name": "x", "type": "band", "domain": {"data": "t", "field": "c"}, "range": "width"},
           {"name": "y", "domain": {"data": "t", "field": "v"}, "range": "height"}],
         "marks": [{"type": "rect", "from": {"data": "t"}, "encode": {"enter": {
           "x": {"scale": "x", "field": "c"}, "width": {"scale": "x", "band": 1},
           "y": {"scale": "y", "field": "v"}, "y2": {"scale": "y", "value": 0},
           "fill": {"value": "steelblue"}}}}]}
        """
      )
    // steelblue is rgb(70,130,180). The shorter bar fills the lower half of the left column.
    assertTrue(image.isNear(10, 40, 70, 130, 180), "left bar: ${image.describe(10, 40)}")
    assertTrue(image.isNear(10, 10, 255, 255, 255), "above it: ${image.describe(10, 10)}")
    // Twice the value, so the right column is painted to the top.
    assertTrue(image.isNear(60, 10, 70, 130, 180), "right bar: ${image.describe(60, 10)}")
    assertTrue(image.isNear(60, 40, 70, 130, 180), "its foot: ${image.describe(60, 40)}")
  }

  @Test
  fun `a circle is round rather than its bounding box`() {
    val image =
      raster(
        """
        {"${'$'}schema": "https://vega.github.io/schema/vega/v6.json",
         "width": 100, "height": 50, "padding": 0,
         "background": "white",
         "data": [{"name": "t", "values": [{"x": 50, "y": 25}]}],
         "marks": [{"type": "symbol", "from": {"data": "t"}, "encode": {"enter": {
           "x": {"field": "x"}, "y": {"field": "y"}, "size": {"value": 1600},
           "shape": {"value": "circle"}, "fill": {"value": "black"}}}}]}
        """
      )
    assertTrue(image.isNear(50, 25, 0, 0, 0), "centre filled: ${image.describe(50, 25)}")
    // A symbol's size is an *area*, so 1600 is a radius near 22.6. The corner of that circle's
    // bounding box is 22.6 out along both axes, so 21 is outside the circle and inside the box —
    // and it would be filled if the engine's cubics had been read as a rectangle or closed wrongly.
    assertTrue(
      image.isNear(29, 4, 255, 255, 255),
      "bounding-box corner is background: ${image.describe(29, 4)}",
    )
  }

  @Test
  fun `a group's opacity paints its panel and not its children`() {
    val image =
      raster(
        """
        {"${'$'}schema": "https://vega.github.io/schema/vega/v6.json",
         "width": 100, "height": 50, "padding": 0,
         "background": "white",
         "marks": [{"type": "group", "encode": {"enter": {
            "x": {"value": 0}, "y": {"value": 0},
            "width": {"value": 100}, "height": {"value": 50},
            "fill": {"value": "black"}, "opacity": {"value": 0.5}}},
          "marks": [{"type": "rect", "encode": {"enter": {
            "x": {"value": 10}, "y": {"value": 10},
            "width": {"value": 30}, "height": {"value": 30},
            "fill": {"value": "red"}}}}]}]}
        """
      )
    // Black at half opacity over white is mid grey.
    val (r, g, _) = image.at(80, 40)
    assertTrue(
      abs(r - 128) <= 4 && abs(g - 128) <= 4,
      "the group's own panel is half-opaque: ${image.describe(80, 40)}",
    )
    // Its child is not. This is the assertion the Android and Swift renderers would have failed.
    assertTrue(image.isNear(25, 25, 255, 0, 0), "the child is opaque: ${image.describe(25, 25)}")
  }

  @Test
  fun `a gradient runs across the mark it fills`() {
    val image =
      raster(
        """
        {"${'$'}schema": "https://vega.github.io/schema/vega/v6.json",
         "width": 100, "height": 50, "padding": 0,
         "background": "white",
         "marks": [{"type": "rect", "encode": {"enter": {
           "x": {"value": 20}, "y": {"value": 10},
           "width": {"value": 60}, "height": {"value": 30},
           "fill": {"value": {"gradient": "linear", "stops": [
             {"offset": 0, "color": "red"}, {"offset": 1, "color": "blue"}]}}}}}]}
        """
      )
    // The stops are at the mark's own edges, not the chart's: red at x=20, blue at x=80.
    val (leftRed, _, leftBlue) = image.at(22, 25)
    val (rightRed, _, rightBlue) = image.at(78, 25)
    assertTrue(leftRed > 200 && leftBlue < 60, "red end: ${image.describe(22, 25)}")
    assertTrue(rightBlue > 200 && rightRed < 60, "blue end: ${image.describe(78, 25)}")
    // And it is a gradient, not two halves: the middle is a mix of both.
    val (midRed, _, midBlue) = image.at(50, 25)
    assertTrue(
      midRed in 60..220 && midBlue in 60..220,
      "the middle is mixed: ${image.describe(50, 25)}",
    )
  }

  /**
   * A right-aligned label ends at its anchor; a left-aligned one starts there.
   *
   * In pixels, because that is the only place the bug showed: the recording tests assert the pen
   * position and would pass whatever the target then did with it. Two labels at the same anchor,
   * one aligned each way, must put their ink on opposite sides of it.
   */
  @Test
  fun `alignment puts the ink on the right side of the anchor`() {
    val image =
      raster(
        """
        {"${'$'}schema": "https://vega.github.io/schema/vega/v6.json",
         "width": 100, "height": 50, "padding": 0,
         "background": "white",
         "marks": [
           {"type": "text", "encode": {"enter": {
             "x": {"value": 50}, "y": {"value": 15},
             "text": {"value": "IIII"}, "align": {"value": "right"},
             "fontSize": {"value": 14}, "fill": {"value": "black"}}}},
           {"type": "text", "encode": {"enter": {
             "x": {"value": 50}, "y": {"value": 40},
             "text": {"value": "IIII"}, "align": {"value": "left"},
             "fontSize": {"value": 14}, "fill": {"value": "black"}}}}]}
        """
      )

    fun inkInColumn(from: Int, until: Int, rows: IntRange): Int {
      var count = 0
      for (y in rows) for (x in from until until) if (image.at(x, y).first < 128) count++
      return count
    }

    // The right-aligned label is on rows around y=15, and its ink must be left of x=50.
    val rightAlignedLeftOfAnchor = inkInColumn(0, 50, 2..20)
    val rightAlignedRightOfAnchor = inkInColumn(51, 100, 2..20)
    assertTrue(
      rightAlignedLeftOfAnchor > 0 && rightAlignedRightOfAnchor == 0,
      "right-aligned ink sits before the anchor: " +
        "$rightAlignedLeftOfAnchor before, $rightAlignedRightOfAnchor after",
    )

    // The left-aligned one, around y=40, must be right of it.
    val leftAlignedLeftOfAnchor = inkInColumn(0, 50, 28..46)
    val leftAlignedRightOfAnchor = inkInColumn(51, 100, 28..46)
    assertTrue(
      leftAlignedRightOfAnchor > 0 && leftAlignedLeftOfAnchor == 0,
      "left-aligned ink sits after the anchor: " +
        "$leftAlignedLeftOfAnchor before, $leftAlignedRightOfAnchor after",
    )
  }

  @Test
  fun `a group's clip keeps its children inside it`() {
    val image =
      raster(
        """
        {"${'$'}schema": "https://vega.github.io/schema/vega/v6.json",
         "width": 100, "height": 50, "padding": 0,
         "background": "white",
         "marks": [{"type": "group", "clip": true, "encode": {"enter": {
            "x": {"value": 0}, "y": {"value": 0},
            "width": {"value": 40}, "height": {"value": 50}}},
          "marks": [{"type": "rect", "encode": {"enter": {
            "x": {"value": 0}, "y": {"value": 0},
            "width": {"value": 100}, "height": {"value": 50},
            "fill": {"value": "black"}}}}]}]}
        """
      )
    // The child is the full width; the group clips it to 40.
    assertTrue(image.isNear(20, 25, 0, 0, 0), "inside the clip: ${image.describe(20, 25)}")
    assertTrue(image.isNear(60, 25, 255, 255, 255), "outside it: ${image.describe(60, 25)}")
  }
}
