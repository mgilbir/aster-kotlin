@file:OptIn(dev.aster.vega.model.InternalAsterVegaApi::class)

package dev.aster.vega.dataflow.transform

import dev.aster.vega.expression.CachingExpressionCompiler
import dev.aster.vega.expression.ExpressionCompiler
import dev.aster.vega.expression.ExpressionScope
import dev.aster.vega.expression.VegaExpressionCompiler
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The one thing `label` does differently from upstream, and the warning that says so.
 *
 * `SUPPORTED_FEATURES.md` calls this row *not verified against upstream*, and that claim needs
 * something holding it up or it rots the way every unpinned claim in that document has.
 *
 * The difference is narrow and stated: upstream builds its occupancy bitmap by drawing the avoided
 * marks into a `<canvas>` and reading the alpha back. There is no canvas under Node — upstream's
 * own transform throws in the same place — so there is no reference to compare against, and the
 * occupancy is computed from the marks' geometry instead. The two agree except on pixels a shape
 * barely grazes, and one pixel is enough to move a label to a different anchor or drop one a
 * crowded chart would have fitted.
 *
 * So **every use of the transform reports it**. This is the only place in the engine that warns
 * about its own fidelity rather than about the specification, and the warning is the row's
 * evidence: the day somebody finds a way to verify the occupancy against upstream, the warning goes
 * and this test goes red, which is when the row has to be rewritten.
 */
class LabelOccupancyLimitTest {

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

  /** Four labelled points, far enough apart that the placement itself is uncontroversial. */
  private val rows =
    """
    [{"text": "alpha", "x": 20, "y": 20, "datum": {"x": 20, "y": 20}},
     {"text": "beta",  "x": 80, "y": 40, "datum": {"x": 80, "y": 40}},
     {"text": "gamma", "x": 40, "y": 80, "datum": {"x": 40, "y": 80}},
     {"text": "delta", "x": 90, "y": 90, "datum": {"x": 90, "y": 90}}]
    """
      .trimIndent()

  private fun run(): Pair<List<VegaValue>, Context> {
    val context = Context()
    val params =
      VegaJson.parse("""{"type": "label", "size": [120, 120], "anchor": ["top", "bottom"]}""")
        as VegaValue.Obj
    val input = (VegaJson.parse(rows) as VegaValue.Arr).values
    return LabelTransform.apply(input, params, context) to context
  }

  @Test
  fun `every use reports that the occupancy is geometric rather than rasterised`() {
    val (_, context) = run()
    val reported = context.diagnostics.diagnostics.map { it.message }
    assertTrue(
      reported.any { "rasterised" in it && "canvas" in it },
      "the label transform ran without saying its occupancy is not upstream's: $reported",
    )
  }

  /**
   * The warning is on **every** run, not only the first.
   *
   * A once-per-process warning would be worse than none: the second chart in an app would place its
   * labels by an unverified rule and say nothing, and which chart got the warning would depend on
   * the order they were drawn in.
   */
  @Test
  fun `the warning is not suppressed after the first run`() {
    repeat(3) {
      val (_, context) = run()
      assertTrue(
        context.diagnostics.diagnostics.any { d -> "rasterised" in d.message },
        "a later run of the transform placed labels silently",
      )
    }
  }

  /**
   * It is a warning about *fidelity*, so the transform still does its job.
   *
   * The guard that keeps the two above from passing for a transform that reports and then refuses:
   * a row must come back for every row that went in, carrying the placement.
   */
  @Test
  fun `labels are still placed`() {
    val (out, _) = run()
    assertEquals(4, out.size, "the transform reported its limitation and then dropped every row")
    val placed = out.count { row -> (row as VegaValue.Obj).fields["opacity"] != VegaValue.Num(0.0) }
    assertTrue(placed > 0, "every label was dropped, so nothing was placed at all")
  }
}
