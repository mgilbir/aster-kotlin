package dev.aster.vega.runtime.scale

import dev.aster.vega.model.VegaValue
import dev.aster.vega.scene.ColorSpaces
import dev.aster.vega.scene.SceneColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Colour scales and schemes.
 *
 * Expected values come from upstream Vega evaluating the same scale definition, including the exact
 * palette entries read out of `vega.scheme(name)`.
 */
class ColorScaleTest {

  private fun hex(text: String): SceneColor = requireNotNull(SceneColor.parse(text))

  // ---- interpolation --------------------------------------------------------

  @Test
  fun `rgb interpolation matches upstream componentwise`() {
    // Upstream: linear scale [0,1] with range ["red","blue"] gives rgb(191, 0, 64) at 0.25.
    val scale = SequentialColorScale("s", listOf(0.0, 1.0), listOf(hex("red"), hex("blue")))
    assertEquals("#ff0000", scale.colorAt(0.0)?.toCssHex())
    assertEquals("#bf0040", scale.colorAt(0.25)?.toCssHex())
    assertEquals("#800080", scale.colorAt(0.5)?.toCssHex())
    assertEquals("#0000ff", scale.colorAt(1.0)?.toCssHex())
  }

  @Test
  fun `lab interpolation avoids the muddy rgb midpoint`() {
    // Upstream with interpolate: "lab" gives rgb(193, 0, 136) halfway from red to blue, where RGB
    // interpolation would give rgb(128, 0, 128).
    val lab =
      SequentialColorScale(
        "s",
        listOf(0.0, 1.0),
        listOf(hex("red"), hex("blue")),
        space = ColorSpaces.Interpolation.LAB,
      )
    val midpoint = requireNotNull(lab.colorAt(0.5))
    // Allow a channel of rounding: the conversion chain is lossy and upstream rounds at the end.
    assertChannelsNear(hex("#c10088"), midpoint, tolerance = 2)
  }

  @Test
  fun `lab round-trips a colour`() {
    for (name in listOf("red", "steelblue", "white", "black", "#123456")) {
      val original = hex(name)
      val roundTripped = ColorSpaces.fromLab(ColorSpaces.toLab(original))
      assertChannelsNear(original, roundTripped, tolerance = 1)
    }
  }

  @Test
  fun `a multi-stop ramp interpolates between neighbouring stops`() {
    val scale =
      SequentialColorScale(
        "s",
        listOf(0.0, 1.0),
        listOf(hex("black"), hex("red"), hex("white")),
      )
    assertEquals("#000000", scale.colorAt(0.0)?.toCssHex())
    assertEquals("#ff0000", scale.colorAt(0.5)?.toCssHex())
    assertEquals("#ffffff", scale.colorAt(1.0)?.toCssHex())
    // Quarter of the way is halfway along the first segment.
    assertEquals("#800000", scale.colorAt(0.25)?.toCssHex())
  }

  // ---- domain behaviour -----------------------------------------------------

  @Test
  fun `a sequential colour scale clamps by default`() {
    val scale = SequentialColorScale("s", listOf(0.0, 1.0), listOf(hex("red"), hex("blue")))
    assertEquals("#ff0000", scale.colorAt(-5.0)?.toCssHex())
    assertEquals("#0000ff", scale.colorAt(5.0)?.toCssHex())
  }

  @Test
  fun `without clamping an out-of-domain value has no colour`() {
    val scale =
      SequentialColorScale("s", listOf(0.0, 1.0), listOf(hex("red"), hex("blue")), clamp = false)
    assertNull(scale.colorAt(-1.0))
    assertNull(scale.colorAt(2.0))
    assertNotNull(scale.colorAt(0.5))
  }

  @Test
  fun `a zero-extent domain yields the ramp's last colour`() {
    val scale = SequentialColorScale("s", listOf(5.0, 5.0), listOf(hex("red"), hex("blue")))
    assertEquals("#0000ff", scale.colorAt(5.0)?.toCssHex())
  }

  @Test
  fun `a non-numeric input has no colour`() {
    val scale = SequentialColorScale("s", listOf(0.0, 1.0), listOf(hex("red"), hex("blue")))
    assertEquals(VegaValue.Null, scale.scale(VegaValue.Str("not a number")))
  }

  @Test
  fun `scaling returns a hex string`() {
    val scale = SequentialColorScale("s", listOf(0.0, 1.0), listOf(hex("red"), hex("blue")))
    assertEquals(VegaValue.Str("#800080"), scale.scale(VegaValue.Num(0.5)))
  }

  @Test
  fun `an empty colour list or short domain is rejected`() {
    org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
      SequentialColorScale("s", listOf(0.0, 1.0), emptyList())
    }
    org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
      SequentialColorScale("s", listOf(0.0), listOf(hex("red")))
    }
  }

  // ---- schemes --------------------------------------------------------------

  @Test
  fun `category10 matches upstream exactly`() {
    val palette = requireNotNull(ColorSchemes.categoricalOrNull("category10"))
    assertEquals(
      listOf(
        "#1f77b4",
        "#ff7f0e",
        "#2ca02c",
        "#d62728",
        "#9467bd",
        "#8c564b",
        "#e377c2",
        "#7f7f7f",
        "#bcbd22",
        "#17becf",
      ),
      palette.map { it.toCssHex() },
    )
  }

  @Test
  fun `tableau10 matches upstream exactly`() {
    val palette = requireNotNull(ColorSchemes.categoricalOrNull("tableau10"))
    assertEquals("#4c78a8", palette[0].toCssHex())
    assertEquals("#f58518", palette[1].toCssHex())
    assertEquals(10, palette.size)
  }

  @Test
  fun `scheme lookup is case-insensitive and every palette parses`() {
    assertNotNull(ColorSchemes.categoricalOrNull("Category10"))
    for (name in ColorSchemes.categoricalNames) {
      val palette = requireNotNull(ColorSchemes.categoricalOrNull(name))
      assertTrue(palette.isNotEmpty(), name)
      assertTrue(palette.all { it.alpha == 1.0 }, name)
    }
  }

  @Test
  fun `the twenty-colour schemes have twenty entries`() {
    for (name in listOf("category20", "category20b", "category20c", "tableau20")) {
      assertEquals(20, requireNotNull(ColorSchemes.categoricalOrNull(name)).size, name)
    }
  }

  /**
   * The ramps are **Vega's own tables, not d3's**, which is the whole finding.
   *
   * `blues` starts at `#cfe1f2`, a fifth of the way into d3's `interpolateBlues`, and that looked
   * for a long time like a scale-level extent of `[0.2, 1]` applied over d3's ramp. It is not:
   * `vega-scale/src/palettes.js` simply starts its table there, and nothing at the scale level
   * narrows anything. `viridis`, by contrast, does begin at d3's own zero.
   */
  @Test
  fun `the continuous ramps are Vega's tables rather than d3's`() {
    assertEquals("#cfe1f2", ColorSchemes.rampOrNull("blues")!!.first().toCssHex())
    assertEquals("#0a4a90", ColorSchemes.rampOrNull("blues")!!.last().toCssHex())
    assertEquals("#440154", ColorSchemes.rampOrNull("viridis")!!.first().toCssHex())
    assertEquals("#fde725", ColorSchemes.rampOrNull("viridis")!!.last().toCssHex())
    // Fifty-three of them, named the way upstream stores them: lowercased, unhyphenated.
    assertEquals(53, ColorSchemes.ramps.size)
    assertNotNull(ColorSchemes.rampOrNull("redYellowBlue"))
    assertNull(ColorSchemes.rampOrNull("nosuchscheme"))
    // A ramp is not a categorical palette, and a diagnostic that confused the two would mislead.
    assertNull(ColorSchemes.categoricalOrNull("viridis"))
  }

  private fun assertChannelsNear(expected: SceneColor, actual: SceneColor, tolerance: Int) {
    fun channel(value: Double) = Math.round(value * 255.0).toInt()
    val differences =
      listOf(
        channel(expected.red) - channel(actual.red),
        channel(expected.green) - channel(actual.green),
        channel(expected.blue) - channel(actual.blue),
      )
    assertTrue(
      differences.all { kotlin.math.abs(it) <= tolerance },
      "expected ${expected.toCssHex()} within $tolerance, got ${actual.toCssHex()}",
    )
  }
}
