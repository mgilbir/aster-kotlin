package dev.aster.vega.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.aster.vega.runtime.compile.SpecCompiler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An `image` mark drawn with real pixels.
 *
 * The differential harness verifies the geometry — where the mark is anchored, how `align` and
 * `baseline` move it — but it cannot decode an image, so it cannot tell a drawn image from a mark
 * that was silently skipped. That needs a device, and this is the only test in the suite that
 * checks an image actually lands on the canvas.
 */
@RunWith(AndroidJUnit4::class)
class ImageMarkInstrumentedTest {

  /** A 2x2 bitmap of one flat colour, so any sampled pixel inside the mark is unambiguous. */
  private fun swatch(color: Int): Bitmap =
    Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

  private val resolver = AndroidImageResolver { url ->
    when (url) {
      "red" -> swatch(Color.RED)
      "green" -> swatch(Color.GREEN)
      else -> null
    }
  }

  private val spec =
    """
    {
      "width": 100, "height": 100, "padding": 0,
      "data": [{"name": "t", "values": [{"i": 0}]}],
      "marks": [
        {"type": "image", "from": {"data": "t"},
         "encode": {"enter": {
           "url": {"value": "red"}, "x": {"value": 0}, "y": {"value": 0},
           "width": {"value": 40}, "height": {"value": 40}, "aspect": {"value": false}}}},
        {"type": "image", "from": {"data": "t"},
         "encode": {"enter": {
           "url": {"value": "green"}, "x": {"value": 100}, "y": {"value": 100},
           "width": {"value": 40}, "height": {"value": 40},
           "align": {"value": "right"}, "baseline": {"value": "bottom"},
           "aspect": {"value": false}}}}
      ]
    }
    """
      .trimIndent()

  private fun render(json: String, resolver: AndroidImageResolver): Pair<Bitmap, List<String>> {
    val compiled = SpecCompiler(AndroidTextEngine()).compileJson(json)
    val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.WHITE)
    val renderer = AndroidCanvasSceneRenderer(imageResolver = resolver)
    renderer.render(compiled.scene!!, Canvas(bitmap), RectF(0f, 0f, 100f, 100f), 1f)
    return bitmap to renderer.lastDiagnostics.map { it.message }
  }

  @Test
  fun anImageIsDrawnWhereItsAnchorSaysAndNotElsewhere() {
    val (bitmap, diagnostics) = render(spec, resolver)
    assertEquals(emptyList<String>(), diagnostics)

    // Anchored top-left at (0,0): the first 40x40 is red.
    assertEquals(Color.RED, bitmap.getPixel(20, 20))
    // Anchored bottom-right at (100,100): the last 40x40 is green.
    assertEquals(Color.GREEN, bitmap.getPixel(80, 80))
    // Between them, nothing was drawn.
    assertEquals(Color.WHITE, bitmap.getPixel(50, 50))
  }

  /**
   * An image that cannot be resolved leaves a gap **and says so**. A chart that quietly drops a
   * mark is the failure mode PROJECT_BRIEF.md 13.3 exists to prevent, and it is invisible on screen
   * by definition.
   */
  @Test
  fun anUnresolvedImageIsReportedRatherThanSilentlyMissing() {
    val (bitmap, diagnostics) = render(spec, AndroidImageResolver.None)
    assertEquals(Color.WHITE, bitmap.getPixel(20, 20))
    assertEquals(2, diagnostics.size)
    assertTrue(diagnostics.toString(), diagnostics.all { it.contains("Could not resolve image") })
  }

  /** `aspect` letterboxes rather than stretching, which is the default and the safer one. */
  @Test
  fun aspectFitsTheImageInsideTheBoxInsteadOfStretchingIt() {
    val wide =
      spec.replace(
        """"width": {"value": 40}, "height": {"value": 40}, "aspect": {"value": false}}}},""",
        """"width": {"value": 80}, "height": {"value": 40}}}},""",
      )
    val (bitmap, _) = render(wide, resolver)
    // A square image in an 80x40 box, fitted: 40 wide, centred, so x from 20 to 60.
    assertEquals(Color.RED, bitmap.getPixel(40, 20))
    assertEquals("the letterbox is left empty", Color.WHITE, bitmap.getPixel(5, 20))
  }
}
