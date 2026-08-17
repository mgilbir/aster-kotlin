package dev.aster.vega.scene

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Every interpolation method Vega offers, against the path string d3-shape actually produces.
 *
 * The vectors below were taken from d3-shape 3.x driven through `vega-scenegraph`'s own `curves()`
 * name table — so the family, the orientation and the meaning of `tension` are resolved exactly as
 * they are when Vega draws a line — and printed with d3's path formatter. Comparing the emitted
 * commands rather than the picture is deliberate: two curves can look alike at chart scale and put
 * their control points in visibly different places once anyone zooms, and a spline's first and last
 * segments differ from its middle ones in ways no reading of the picture would predict.
 *
 * The short series matter as much as the long ones. Below three points the *open* families draw
 * nothing at all — not a straight line — and at exactly three they emit a single position and a
 * `Z`.
 */
class CurvesTest {

  private val points = listOf(0.0 to 0.0, 50.0 to 80.0, 100.0 to 20.0, 150.0 to 90.0)

  private fun path(kind: String, n: Int, tension: Double? = null): String =
    PathData.build {
        curve(
          points.take(n).map { PointD(it.first, it.second) },
          CurveKind.fromName(kind) ?: error("unknown curve $kind"),
          tension = tension,
        )
      }
      .let(::svg)

  /** d3's own path text: three decimals, trailing zeros stripped, no spaces between numbers. */
  private fun svg(path: PathData): String =
    path.commands.joinToString("") { command ->
      when (command) {
        is PathCommand.MoveTo -> "M${n(command.x)},${n(command.y)}"
        is PathCommand.LineTo -> "L${n(command.x)},${n(command.y)}"
        is PathCommand.CubicTo ->
          "C${n(command.x1)},${n(command.y1)},${n(command.x2)},${n(command.y2)}," +
            "${n(command.x)},${n(command.y)}"
        PathCommand.Close -> "Z"
      }
    }

  private fun n(value: Double): String {
    val rounded = kotlin.math.round(value * 1000.0) / 1000.0
    return if (rounded == kotlin.math.truncate(rounded)) rounded.toLong().toString()
    else rounded.toString()
  }

  /** d3 prints nothing for a line that never began; ours prints the empty string. */
  private fun assertCurve(expected: String, kind: String, n: Int, tension: Double? = null) {
    assertEquals(expected, path(kind, n, tension), "$kind with $n point(s)")
  }

  @Test
  fun `one point closes its subpath, except where nothing was drawn`() {
    for (kind in
      listOf(
        "linear",
        "step",
        "step-before",
        "step-after",
        "basis",
        "basis-closed",
        "cardinal",
        "cardinal-closed",
        "catmull-rom",
        "catmull-rom-closed",
        "monotone",
        "natural",
        "linear-closed",
      )) {
      assertCurve("M0,0Z", kind, 1)
    }
    for (kind in listOf("basis-open", "cardinal-open", "catmull-rom-open", "bundle")) {
      assertCurve("", kind, 1)
    }
  }

  @Test
  fun `two points`() {
    assertCurve("M0,0L50,80", "linear", 2)
    assertCurve("M0,0L25,0L25,80L50,80", "step", 2)
    assertCurve("M0,0L0,80L50,80", "step-before", 2)
    assertCurve("M0,0L50,0L50,80", "step-after", 2)
    assertCurve("M0,0L50,80", "basis", 2)
    assertCurve("M33.333,53.333L16.667,26.667Z", "basis-closed", 2)
    assertCurve("M0,0L50,80", "bundle", 2)
    assertCurve("M0,0L50,80", "cardinal", 2)
    assertCurve("M50,80L0,0Z", "cardinal-closed", 2)
    assertCurve("M0,0L50,80", "catmull-rom", 2)
    assertCurve("M50,80L0,0Z", "catmull-rom-closed", 2)
    assertCurve("M0,0L50,80", "monotone", 2)
    assertCurve("M0,0L50,80", "natural", 2)
    assertCurve("M0,0L50,80Z", "linear-closed", 2)
    for (kind in listOf("basis-open", "cardinal-open", "catmull-rom-open")) {
      assertCurve("", kind, 2)
    }
  }

  @Test
  fun `three points, where the open families collapse to a position`() {
    assertCurve("M0,0L50,80L100,20", "linear", 3)
    assertCurve("M0,0L25,0L25,80L75,80L75,20L100,20", "step", 3)
    assertCurve(
      "M0,0L8.333,13.333C16.667,26.667,33.333,53.333,50,56.667C66.667,60,83.333,40,91.667,30L100,20",
      "basis",
      3,
    )
    assertCurve("M50,56.667Z", "basis-open", 3)
    assertCurve(
      "M50,56.667C66.667,60,83.333,40,75,26.667C66.667,13.333,33.333,6.667,25,16.667" +
        "C16.667,26.667,33.333,53.333,50,56.667",
      "basis-closed",
      3,
    )
    assertCurve(
      "M0,0L8.333,11.583C16.667,23.167,33.333,46.333,50,49.667C66.667,53,83.333,36.5,91.667,28.25" +
        "L100,20",
      "bundle",
      3,
    )
    assertCurve("M0,0C0,0,33.333,76.667,50,80C66.667,83.333,100,20,100,20", "cardinal", 3)
    assertCurve("M50,80Z", "cardinal-open", 3)
    assertCurve(
      "M50,80C66.667,83.333,108.333,33.333,100,20C91.667,6.667,8.333,-10,0,0" +
        "C-8.333,10,33.333,76.667,50,80",
      "cardinal-closed",
      3,
    )
    assertCurve("M0,0C0,0,32.469,78.805,50,80C65.951,81.087,100,20,100,20", "catmull-rom", 3)
    assertCurve("M50,80Z", "catmull-rom-open", 3)
    assertCurve(
      "M50,80C65.951,81.087,104.726,33.389,100,20C94.6,4.701,7.509,-10.864,0,0" +
        "C-7.223,10.449,32.469,78.805,50,80",
      "catmull-rom-closed",
      3,
    )
    assertCurve("M0,0C16.667,40,33.333,80,50,80C66.667,80,83.333,50,100,20", "monotone", 3)
    assertCurve(
      "M0,0C16.667,38.333,33.333,76.667,50,80C66.667,83.333,83.333,51.667,100,20",
      "natural",
      3,
    )
  }

  @Test
  fun `four points, where every family is drawing its middle segments`() {
    assertCurve(
      "M0,0L8.333,13.333C16.667,26.667,33.333,53.333,50,56.667C66.667,60,83.333,40,100,41.667" +
        "C116.667,43.333,133.333,66.667,141.667,78.333L150,90",
      "basis",
      4,
    )
    assertCurve("M50,56.667C66.667,60,83.333,40,100,41.667", "basis-open", 4)
    assertCurve(
      "M50,56.667C66.667,60,83.333,40,100,41.667C116.667,43.333,133.333,66.667,116.667,63.333" +
        "C100,60,50,30,33.333,28.333C16.667,26.667,33.333,53.333,50,56.667",
      "basis-closed",
      4,
    )
    assertCurve(
      "M0,0L8.333,12.083C16.667,24.167,33.333,48.333,50,52.667C66.667,57,83.333,41.5,100,44.417" +
        "C116.667,47.333,133.333,68.667,141.667,79.333L150,90",
      "bundle",
      4,
    )
    assertCurve(
      "M0,0C0,0,33.333,76.667,50,80C66.667,83.333,83.333,18.333,100,20C116.667,21.667,150,90,150,90",
      "cardinal",
      4,
    )
    assertCurve("M50,80C66.667,83.333,83.333,18.333,100,20", "cardinal-open", 4)
    assertCurve(
      "M50,80C66.667,83.333,83.333,18.333,100,20C116.667,21.667,166.667,93.333,150,90" +
        "C133.333,86.667,16.667,1.667,0,0C-16.667,-1.667,33.333,76.667,50,80",
      "cardinal-closed",
      4,
    )
    assertCurve(
      "M0,0C0,0,32.469,78.805,50,80C65.951,81.087,83.717,19.393,100,20C117.089,20.637,150,90,150,90",
      "catmull-rom",
      4,
    )
    assertCurve("M50,80C65.951,81.087,83.717,19.393,100,20", "catmull-rom-open", 4)
    assertCurve(
      "M50,80C65.951,81.087,83.717,19.393,100,20C117.089,20.637,154.656,84.956,150,90" +
        "C143.36,97.192,8.086,-8.234,0,0C-5.938,6.047,32.469,78.805,50,80",
      "catmull-rom-closed",
      4,
    )
    assertCurve(
      "M0,0C16.667,40,33.333,80,50,80C66.667,80,83.333,20,100,20C116.667,20,133.333,55,150,90",
      "monotone",
      4,
    )
    assertCurve(
      "M0,0C16.667,42,33.333,84,50,80C66.667,76,83.333,26,100,20C116.667,14,133.333,52,150,90",
      "natural",
      4,
    )
  }

  /**
   * `tension` is three different quantities sharing one channel name, and each family's neutral
   * value is its own: a cardinal stiffness (0), a Catmull-Rom exponent (0.5) and a bundle blend
   * (0.85). Reading an unspecified `tension` as 0 for all three would turn a Catmull-Rom into a
   * cardinal spline and a bundle into a straight line, which is why the defaults are asserted here
   * next to the explicit values.
   */
  @Test
  fun `tension means something different to each family`() {
    assertCurve(
      "M0,0C0,0,41.667,78.333,50,80C58.333,81.667,91.667,19.167,100,20" +
        "C108.333,20.833,150,90,150,90",
      "cardinal",
      4,
      0.5,
    )
    assertCurve("M50,80C58.333,81.667,91.667,19.167,100,20", "cardinal-open", 4, 0.5)
    // alpha 0 makes a Catmull-Rom spline a cardinal one exactly, which is the family's own claim.
    assertEquals(path("cardinal", 4, 0.0), path("catmull-rom", 4, 0.0))
    assertCurve(
      "M0,0C0,0,31.438,81.138,50,80C65.367,79.057,84.064,20.401,100,20" +
        "C117.553,19.558,150,90,150,90",
      "catmull-rom",
      4,
      1.0,
    )
    // beta 1 leaves the points where they were, so a bundle is a plain B-spline.
    assertEquals(path("basis", 4), path("bundle", 4, 1.0))
    assertCurve(
      "M0,0L8.333,9.167C16.667,18.333,33.333,36.667,50,43.333C66.667,50,83.333,45,100,50.833" +
        "C116.667,56.667,133.333,73.333,141.667,81.667L150,90",
      "bundle",
      4,
      0.5,
    )
  }
}
