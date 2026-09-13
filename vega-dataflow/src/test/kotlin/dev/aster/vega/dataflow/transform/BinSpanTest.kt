package dev.aster.vega.dataflow.transform

import dev.aster.vega.expression.CachingExpressionCompiler
import dev.aster.vega.expression.ExpressionCompiler
import dev.aster.vega.expression.ExpressionScope
import dev.aster.vega.expression.VegaExpressionCompiler
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.field
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `bin`'s `span`: the width the step is chosen from, whatever the data covers.
 *
 * ```js
 * span = _.span || (max - min) || Math.abs(min) || 1
 * ```
 *
 * It is the first term of that chain and nothing read it, so the step always came from the extent.
 * A specification states a span to keep its bins a fixed width while something else narrows the
 * rows under them — a brush, a filter, a facet — and without it the chart re-binned itself every
 * time the selection moved.
 *
 * The `||` is transcribed rather than paraphrased: a span of **zero** is no span, and falls through
 * to the extent. Every expectation is upstream's own binning of 1, 3, 7 and 9 over `[0, 10]`.
 */
class BinSpanTest {

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

  private val rows =
    (VegaJson.parse("""[{"v": 1}, {"v": 3}, {"v": 7}, {"v": 9}]""") as VegaValue.Arr).values

  /** Each row's `[bin0, bin1]`. */
  private fun bins(params: String): List<Pair<Double, Double>> {
    val context = Context()
    val out = BinTransform.apply(rows, VegaJson.parse(params) as VegaValue.Obj, context)
    assertEquals(emptyList<String>(), context.diagnostics.diagnostics.map { it.message })
    return out.map {
      (it.field("bin0") as VegaValue.Num).value to (it.field("bin1") as VegaValue.Num).value
    }
  }

  @Test
  fun `a stated span chooses the step`() {
    // A span of 100 over an extent of 10: bins ten times wider than the data would have asked for.
    assertEquals(
      listOf(0.0 to 5.0, 0.0 to 5.0, 5.0 to 10.0, 5.0 to 10.0),
      bins("""{"field": "v", "extent": [0, 10], "span": 100}"""),
    )
  }

  @Test
  fun `a span narrower than the extent bins more finely`() {
    assertEquals(
      listOf(
        1.0 to 1.2000000000000002,
        3.0 to 3.2,
        7.0 to 7.2,
        9.0 to 9.200000000000001,
      ),
      bins("""{"field": "v", "extent": [0, 10], "span": 4}"""),
    )
  }

  @Test
  fun `no span leaves the extent to decide`() {
    assertEquals(
      listOf(1.0 to 1.5, 3.0 to 3.5, 7.0 to 7.5, 9.0 to 9.5),
      bins("""{"field": "v", "extent": [0, 10]}"""),
    )
  }

  @Test
  fun `a span of zero is no span`() {
    assertEquals(
      bins("""{"field": "v", "extent": [0, 10]}"""),
      bins("""{"field": "v", "extent": [0, 10], "span": 0}"""),
    )
  }
}
