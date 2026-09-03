@file:OptIn(dev.aster.vega.model.InternalAsterVegaApi::class)

package dev.aster.vega.dataflow.transform

import dev.aster.vega.expression.CachingExpressionCompiler
import dev.aster.vega.expression.ExpressionCompiler
import dev.aster.vega.expression.ExpressionScope
import dev.aster.vega.expression.VegaExpressionCompiler
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `contour`, against numbers read off upstream running the same input.
 *
 * The fixture `contour-legacy` is the wider check — 450 marks compared against upstream through the
 * whole compiler — and this is the narrower one, where a disagreement says which *step* moved. Both
 * are needed for the same reason the repository keeps both everywhere else: a fixture says the
 * picture is wrong, a unit test says why.
 *
 * The expected values here were produced by running upstream's own `contour` on exactly this grid,
 * so they are reference vectors and not a restatement of the implementation.
 */
class ContourTest {

  private class Context : TransformContext {
    override var tree: TreeSource? = null
    override val diagnostics = DiagnosticCollector()
    override val expressions: ExpressionCompiler =
      CachingExpressionCompiler(VegaExpressionCompiler())
    override val scope: ExpressionScope = scopeFor(VegaValue.Null)

    override fun setSignal(name: String, value: VegaValue) = Unit

    override fun scopeFor(datum: VegaValue): ExpressionScope =
      object : ExpressionScope {
        override val datum: VegaValue = datum

        override fun signal(name: String): VegaValue = VegaValue.Null

        override fun dataset(name: String): List<VegaValue> = emptyList()
      }
  }

  /**
   * A symmetric hill on an 8 x 8 grid: a plateau of 9 at the centre, falling to 0 at every edge.
   *
   * Symmetry is what makes the expected numbers readable — every contour is a square ring centred
   * on (4, 4) — and it is also what would hide a transposed index, so the asymmetric case is the
   * fixture's job rather than this one's.
   */
  private val hill =
    listOf(
      0,
      0,
      0,
      0,
      0,
      0,
      0,
      0,
      0,
      1,
      2,
      3,
      3,
      2,
      1,
      0,
      0,
      2,
      4,
      6,
      6,
      4,
      2,
      0,
      0,
      3,
      6,
      9,
      9,
      6,
      3,
      0,
      0,
      3,
      6,
      9,
      9,
      6,
      3,
      0,
      0,
      2,
      4,
      6,
      6,
      4,
      2,
      0,
      0,
      1,
      2,
      3,
      3,
      2,
      1,
      0,
      0,
      0,
      0,
      0,
      0,
      0,
      0,
      0,
    )

  private fun run(
    json: String,
    input: List<VegaValue> = emptyList(),
  ): Pair<List<VegaValue>, Context> {
    val context = Context()
    val params = VegaJson.parse(json) as VegaValue.Obj
    return ContourTransform.apply(input, params, context) to context
  }

  private fun points(row: VegaValue): List<Pair<Double, Double>> =
    ((row as VegaValue.Obj).fields["coordinates"] as VegaValue.Arr)
      .values
      .flatMap { (it as VegaValue.Arr).values }
      .flatMap { (it as VegaValue.Arr).values }
      .map { (it as VegaValue.Arr).values.let { p -> p[0].asDouble() to p[1].asDouble() } }

  @Test
  fun `a given grid is cut at the levels upstream cuts it at`() {
    val (rows, context) =
      run("""{"size": [8, 8], "values": [${hill.joinToString(",")}], "count": 3}""")
    assertEquals(emptyList<String>(), context.diagnostics.diagnostics.map { it.message })
    // `count` of 3 over a span of 0..9 with zero folded in: step 9/4, levels at 2.25, 4.5 and 6.75.
    // Three *interior* contours rather than three boundaries — `start + step` up to but not
    // including `stop`, which is where a plausible off-by-one would show.
    assertEquals(
      listOf(2.25, 4.5, 6.75),
      rows.map { (it as VegaValue.Obj).fields["value"]!!.asDouble() },
    )
    assertEquals(
      List(3) { "MultiPolygon" },
      rows.map { ((it as VegaValue.Obj).fields["type"] as VegaValue.Str).value },
    )
  }

  @Test
  fun `each ring is where upstream traces it`() {
    val (rows, _) = run("""{"size": [8, 8], "values": [${hill.joinToString(",")}], "count": 3}""")
    // Read off upstream: the extent of each ring, and how many points it is drawn with. The point
    // count is what catches a smoothing difference — an unsmoothed ring has the same extent and a
    // different number of vertices.
    val expected = listOf(Triple(1.25, 6.75, 25), Triple(2.0, 6.0, 17), Triple(2.75, 5.25, 9))
    for ((row, want) in rows.zip(expected)) {
      val (low, high, count) = want
      val pts = points(row)
      assertEquals(count, pts.size, "vertex count at ${(row as VegaValue.Obj).fields["value"]}")
      assertEquals(low, pts.minOf { it.first }, 1e-9)
      assertEquals(high, pts.maxOf { it.first }, 1e-9)
      // Symmetric, so y must agree with x; a transposed index would pass the x assertions alone.
      assertEquals(low, pts.minOf { it.second }, 1e-9)
      assertEquals(high, pts.maxOf { it.second }, 1e-9)
    }
  }

  /**
   * `smooth: false` keeps the ring on the cell boundaries, which is a different ring.
   *
   * Asserted because the default is true and a parameter that is read but never acted on looks
   * exactly like one that works.
   */
  @Test
  fun `smoothing is a parameter rather than always on`() {
    val smoothed = run("""{"size": [8, 8], "values": [${hill.joinToString(",")}], "count": 1}""")
    val stepped =
      run(
        """{"size": [8, 8], "values": [${hill.joinToString(",")}], "count": 1, "smooth": false}"""
      )
    val a = points(smoothed.first.single())
    val b = points(stepped.first.single())
    assertEquals(a.size, b.size, "smoothing moves vertices, it does not add or drop them")
    assertTrue(a != b, "smooth: false traced the same ring as smooth: true")
    // Unsmoothed vertices sit on half-cell boundaries, which is what "not interpolated" means here.
    assertTrue(
      b.all { (x, y) -> (x * 2) % 1.0 == 0.0 && (y * 2) % 1.0 == 0.0 },
      "an unsmoothed ring left a cell boundary: $b",
    )
  }

  /** Written-down thresholds are used as given, `count` and `nice` ignored — upstream's rule. */
  @Test
  fun `stated thresholds beat a count`() {
    val (rows, _) =
      run(
        """{"size": [8, 8], "values": [${hill.joinToString(",")}],
            "thresholds": [1, 8], "count": 99, "nice": true}"""
      )
    assertEquals(
      listOf(1.0, 8.0),
      rows.map { (it as VegaValue.Obj).fields["value"]!!.asDouble() },
    )
  }

  /**
   * A grid shorter than its stated size is refused rather than read past its end.
   *
   * The one input that can be *wrong* rather than merely absent, and reading it optimistically
   * would index off the end of the array — which in Kotlin throws where in JavaScript it quietly
   * yields `undefined` and contours a grid of NaN.
   */
  @Test
  fun `a values array too short for its size is reported`() {
    val (rows, context) = run("""{"size": [8, 8], "values": [1, 2, 3], "count": 3}""")
    val reported = context.diagnostics.diagnostics.map { it.message }
    assertTrue(
      reported.any { "3 numbers" in it && "64" in it },
      "expected a report naming both counts, got $reported",
    )
    // The rows are handed back untouched, which is what every transform here does when it cannot
    // run: a pipeline that stops says so rather than emitting an empty dataset that reads as "no
    // contours".
    assertEquals(0, rows.size)
  }

  @Test
  fun `a missing size is reported`() {
    val (_, context) = run("""{"values": [1, 2, 3, 4], "count": 1}""")
    assertTrue(
      context.diagnostics.diagnostics.any { "size" in it.message },
      context.diagnostics.diagnostics.map { it.message }.toString(),
    )
  }

  /**
   * With no `values`, the grid is estimated from the rows — against upstream's own numbers.
   *
   * Forty points on an ellipse, which is a surface with real structure rather than a single blob,
   * and every number below was read off upstream running this exact input.
   *
   * This is the test that earned its keep. The estimate is built with **`counts: true`** because
   * upstream's `contour` calls `density2D()(values, true)`, where `kde2d` defaults the same flag to
   * false — and the flag scales the whole grid by a constant, `2^(-2k)` against `1 / sum`. Since
   * the thresholds are derived from that same grid's extent, every contour lands in exactly the
   * same place with the flag either way: the geometry, the vertex counts and the colours a scale
   * over `value` produces are all identical. Only the `value` each contour carries moves, here by a
   * factor of 2.5. The 450-mark `contour-legacy` fixture passes with it wrong.
   */
  @Test
  fun `an estimated grid is upstream's, values and all`() {
    val rows =
      (0 until 40).map {
        val angle = it / 40.0 * 2 * kotlin.math.PI
        VegaValue.Obj(
          linkedMapOf(
            "u" to VegaValue.Num(60 + 25 * kotlin.math.cos(angle)),
            "v" to VegaValue.Num(60 + 18 * kotlin.math.sin(angle)),
          )
        )
      }
    val (rowsOut, context) =
      run(
        """{"size": [120, 120], "x": "u", "y": "v", "cellSize": 4, "count": 4}""",
        rows,
      )
    assertEquals(emptyList<String>(), context.diagnostics.diagnostics.map { it.message })

    // value, vertex count, and the ring's extent in x and y. Read off upstream.
    val expected =
      listOf(
        Expected(
          0.0036148149520158768,
          82,
          18.692537157707584,
          101.30746284229241,
          31.28379462872732,
          88.71620537127268,
        ),
        Expected(
          0.0072296299040317535,
          82,
          23.951444940850166,
          96.04855505914983,
          34.23400017866026,
          85.76599982133975,
        ),
        Expected(
          0.01084444485604763,
          86,
          28.80526365941588,
          91.19473634058411,
          36.18600016457866,
          83.81399983542134,
        ),
        Expected(
          0.014459259808063507,
          52,
          39.62353285733938,
          81.15675699186639,
          38.2641151033296,
          81.7358848966704,
        ),
      )
    assertEquals(expected.size, rowsOut.size, "contour count")
    for ((row, want) in rowsOut.zip(expected)) {
      val value = (row as VegaValue.Obj).fields["value"]!!.asDouble()
      // A float32 grid accumulated in a different order agrees to about a part in a billion, which
      // is nine orders of magnitude tighter than the 2.5x the `counts` flag was worth. The same
      // slack covers the vertex positions, where the grid's error reaches a coordinate through the
      // linear interpolation that places it between two cells.
      assertEquals(want.value, value, want.value * 1e-6, "level")
      val pts = points(row)
      assertEquals(want.vertices, pts.size, "vertex count at $value")
      assertEquals(want.x1, pts.minOf { it.first }, 2e-5, "left at $value")
      assertEquals(want.x2, pts.maxOf { it.first }, 2e-5, "right at $value")
      assertEquals(want.y1, pts.minOf { it.second }, 2e-5, "top at $value")
      assertEquals(want.y2, pts.maxOf { it.second }, 2e-5, "bottom at $value")
    }
  }

  private class Expected(
    val value: Double,
    val vertices: Int,
    val x1: Double,
    val x2: Double,
    val y1: Double,
    val y2: Double,
  )
}
