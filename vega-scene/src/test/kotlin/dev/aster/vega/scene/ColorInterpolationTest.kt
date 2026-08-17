package dev.aster.vega.scene

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Every colour space Vega can interpolate a scale in, against `d3-interpolate`'s own output.
 *
 * Pinned to the byte rather than to the eye. A wrong space produces a ramp that still runs from the
 * right colour to the right colour — both ends are exact by construction — and takes a different
 * path between them, which is the only thing a reader of the chart actually sees. Two of the paths
 * differ only in **which way round the hue circle** they go, and nothing about the endpoints
 * reveals that.
 *
 * The vectors come from `d3-interpolate` driven directly:
 * ```
 * node -e "const $=require('d3-interpolate');
 *          console.log($.interpolateHcl('#1f77b4','#ff7f0e')(0.5))"
 * ```
 */
class ColorInterpolationTest {

  private fun ramp(space: ColorSpaces.Interpolation, from: String, to: String): String {
    val a = requireNotNull(SceneColor.parse(from))
    val b = requireNotNull(SceneColor.parse(to))
    return listOf(0.0, 0.25, 0.5, 0.75, 1.0).joinToString(" | ") { t ->
      val c = ColorSpaces.interpolate(a, b, t, space)
      "rgb(${byteOf(c.red)}, ${byteOf(c.green)}, ${byteOf(c.blue)})"
    }
  }

  /** d3 rounds each channel as it writes it out, which is where its halves land. */
  private fun byteOf(channel: Double): Int = kotlin.math.round(channel * 255.0).toInt()

  @Test
  fun `rgb and lab`() {
    assertEquals(
      "rgb(31, 119, 180) | rgb(87, 121, 139) | rgb(143, 123, 97) | rgb(199, 125, 56) | " +
        "rgb(255, 127, 14)",
      ramp(ColorSpaces.Interpolation.RGB, "#1f77b4", "#ff7f0e"),
    )
    assertEquals(
      "rgb(31, 119, 180) | rgb(126, 123, 146) | rgb(176, 126, 111) | rgb(217, 127, 73) | " +
        "rgb(255, 127, 14)",
      ramp(ColorSpaces.Interpolation.LAB, "#1f77b4", "#ff7f0e"),
    )
  }

  @Test
  fun `hcl goes the short way round and hcl-long the long way`() {
    assertEquals(
      "rgb(31, 119, 180) | rgb(132, 112, 206) | rgb(217, 89, 182) | rgb(255, 80, 116) | " +
        "rgb(255, 127, 14)",
      ramp(ColorSpaces.Interpolation.HCL, "#1f77b4", "#ff7f0e"),
    )
    assertEquals(
      "rgb(31, 119, 180) | rgb(0, 146, 165) | rgb(0, 162, 92) | rgb(136, 161, 0) | " +
        "rgb(255, 127, 14)",
      ramp(ColorSpaces.Interpolation.HCL_LONG, "#1f77b4", "#ff7f0e"),
    )
    // Viridis's own ends, where the two paths part company completely: through magenta one way and
    // through blue the other.
    assertEquals(
      "rgb(68, 1, 84) | rgb(154, 3, 93) | rgb(222, 69, 79) | rgb(255, 147, 51) | rgb(253, 231, 37)",
      ramp(ColorSpaces.Interpolation.HCL, "#440154", "#fde725"),
    )
    assertEquals(
      "rgb(68, 1, 84) | rgb(0, 87, 174) | rgb(0, 152, 177) | rgb(0, 205, 101) | rgb(253, 231, 37)",
      ramp(ColorSpaces.Interpolation.HCL_LONG, "#440154", "#fde725"),
    )
  }

  @Test
  fun `hsl and hsl-long`() {
    assertEquals(
      "rgb(31, 119, 180) | rgb(25, 201, 143) | rgb(30, 222, 18) | rgb(197, 245, 9) | " +
        "rgb(255, 127, 14)",
      ramp(ColorSpaces.Interpolation.HSL, "#1f77b4", "#ff7f0e"),
    )
    assertEquals(
      "rgb(68, 1, 84) | rgb(135, 2, 91) | rgb(186, 2, 29) | rgb(236, 90, 2) | rgb(253, 231, 37)",
      ramp(ColorSpaces.Interpolation.HSL, "#440154", "#fde725"),
    )
    assertEquals(
      "rgb(68, 1, 84) | rgb(2, 24, 135) | rgb(2, 186, 158) | rgb(31, 236, 2) | rgb(253, 231, 37)",
      ramp(ColorSpaces.Interpolation.HSL_LONG, "#440154", "#fde725"),
    )
  }

  @Test
  fun `cubehelix and cubehelix-long`() {
    assertEquals(
      "rgb(31, 119, 180) | rgb(0, 169, 125) | rgb(30, 192, 36) | rgb(132, 173, 0) | " +
        "rgb(255, 127, 14)",
      ramp(ColorSpaces.Interpolation.CUBEHELIX, "#1f77b4", "#ff7f0e"),
    )
    assertEquals(
      "rgb(68, 1, 84) | rgb(230, 0, 128) | rgb(255, 19, 50) | rgb(255, 118, 0) | rgb(253, 231, 37)",
      ramp(ColorSpaces.Interpolation.CUBEHELIX, "#440154", "#fde725"),
    )
    assertEquals(
      "rgb(68, 1, 84) | rgb(17, 75, 250) | rgb(0, 227, 196) | rgb(0, 255, 0) | rgb(253, 231, 37)",
      ramp(ColorSpaces.Interpolation.CUBEHELIX_LONG, "#440154", "#fde725"),
    )
    assertEquals(
      "rgb(255, 0, 0) | rgb(197, 0, 191) | rgb(41, 64, 235) | rgb(0, 129, 124) | rgb(0, 128, 0)",
      ramp(ColorSpaces.Interpolation.CUBEHELIX_LONG, "red", "green"),
    )
  }

  /**
   * Black to white, where every space agrees — and where three of them have a `NaN` hue at both
   * ends.
   *
   * The agreement is the point: a grey has no hue and no saturation, and d3 holds such a channel
   * constant rather than averaging it. Getting that wrong shows up here and nowhere else, because
   * it is the one ramp where the answer is obvious.
   */
  @Test
  fun `a grey ramp is the same in every space`() {
    val expected =
      "rgb(0, 0, 0) | rgb(64, 64, 64) | rgb(128, 128, 128) | rgb(191, 191, 191) | " +
        "rgb(255, 255, 255)"
    for (space in
      listOf(
        ColorSpaces.Interpolation.RGB,
        ColorSpaces.Interpolation.HSL,
        ColorSpaces.Interpolation.HSL_LONG,
        ColorSpaces.Interpolation.CUBEHELIX,
        ColorSpaces.Interpolation.CUBEHELIX_LONG,
      )) {
      assertEquals(expected, ramp(space, "#000000", "#ffffff"), space.name)
    }
    // Lab and HCL take the *perceptual* midpoint, which is darker than the arithmetic one — a fact
    // about human vision rather than a discrepancy.
    val perceptual =
      "rgb(0, 0, 0) | rgb(59, 59, 59) | rgb(119, 119, 119) | rgb(185, 185, 185) | " +
        "rgb(255, 255, 255)"
    assertEquals(perceptual, ramp(ColorSpaces.Interpolation.LAB, "#000000", "#ffffff"))
    assertEquals(perceptual, ramp(ColorSpaces.Interpolation.HCL, "#000000", "#ffffff"))
    assertEquals(perceptual, ramp(ColorSpaces.Interpolation.HCL_LONG, "#000000", "#ffffff"))
  }
}
