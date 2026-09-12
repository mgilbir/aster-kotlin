package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A parse climbs in the order `MoveParseUp` climbs it, and the branches are numbered in that order.
 *
 * ```js
 * public optimize(node: DataFlowNode): boolean {
 *   const depths = this.getNodeDepths(node, 0, new Map());
 *   const topologicalSort = [...depths.entries()].sort((a, b) => b[1] - a[1]);
 *   for (const tuple of topologicalSort) {
 *     this.run(tuple[0]);
 *   }
 * ```
 * ```js
 * for (const child of node.children) {
 *   if (child instanceof ParseNode) {
 *     …
 *     child.swapWithParent();
 *   }
 * }
 * ```
 *
 * Two things decide the shape it leaves behind, and neither is obvious:
 * - the depths are measured **once**, before anything moves, so a node is visited at the depth it
 *   had then — a parse that has already climbed is visited again from wherever it now is;
 * - the children are walked **as they stand**. A swap empties that list and fills it with the
 *   parse's own children, and the iterator carries on at the next index — into what the swap just
 *   put there. So a parse among them climbs in the same pass, and the branch below *it* is appended
 *   after every other branch.
 *
 * It reads like an accident and it is one, but the dataset numbering follows the order the branches
 * end up in, and every mark and scale domain names a numbered dataset. Written as a recursion that
 * settles each level before the one above it, this compiler numbered such a chart's tables in an
 * order no mark expected: the box plot read the density's table and the density the box plot's.
 *
 * One specification in the wild corpus lays a box plot of instants beside one of numbers that way.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class ParseClimbOrderTest {

  /** Every dataset with the steps it runs, a formula named by the column it writes. */
  private fun flow(spec: String): String =
    ((VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
          as VegaValue.Obj)
        .fields["data"]
        as VegaValue.Arr)
      .values
      .joinToString("\n") {
        it as VegaValue.Obj
        val steps =
          it.array("transform").orEmpty().joinToString(" ") { step ->
            step as VegaValue.Obj
            step.string("type").orEmpty() +
              if (step.string("type") == "formula") "(${step.string("as")})" else ""
          }
        "${it.string("name")}<-${it.string("source")} $steps".trim()
      }

  private val rows = """"data":{"values":[{"t":"2020-01-01","c":1,"g":"a"}]}"""
  private val area =
    """{"mark":"area","transform":[{"timeUnit":"yearmonth","field":"t","as":"e"},
        {"density":"t","groupby":["g"],"as":["t","density"]}],
        "encoding":{"x":{"field":"t","type":"temporal"},
                    "y":{"field":"density","type":"quantitative"}}}"""

  private fun box(field: String, type: String) =
    """{"mark":"boxplot","encoding":{"x":{"field":"$field","type":"$type"},
        "y":{"field":"g","type":"nominal"}}}"""

  /**
   * The reported shape: a box plot of **instants** keeps a parse of its own below the summary, and
   * that parse climbing is what sends its branch to the end — behind the box plot written after it.
   */
  @Test
  fun `a branch whose parse climbs is numbered last`() {
    assertEquals(
      """source_0<-null
data_0<-source_0 formula(t) filter
data_1<-data_0 timeunit kde impute stack
data_2<-data_0 joinaggregate
data_3<-data_2 filter filter
data_4<-data_2 filter aggregate
data_5<-data_4 filter
data_6<-data_4 filter
data_7<-data_0 aggregate formula(lower_box_t) formula(upper_box_t) formula(max_t) formula(mid_box_t) formula(min_t)
data_8<-data_7 filter
data_9<-data_7 filter
data_10<-data_7 filter
data_11<-data_7 filter
data_12<-data_0 joinaggregate
data_13<-data_12 filter filter
data_14<-data_12 filter aggregate formula(lower_whisker_t) formula(lower_box_t) formula(upper_whisker_t) formula(upper_box_t)
data_15<-data_14 filter
data_16<-data_14 filter""",
      flow(
        """{$rows,"transform":[{"filter":"datum.c > 0"}],
            "vconcat":[$area,${box("t", "temporal")},${box("c", "quantitative")}]}"""
      ),
    )
  }

  /**
   * With nothing left to climb — two box plots of **numbers**, which are the same question asked
   * twice — the branches fold into one and stay where they were written.
   */
  @Test
  fun `branches with no parse to climb stay as they were written`() {
    assertEquals(
      """source_0<-null
data_0<-source_0 formula(t) filter
data_1<-data_0 timeunit kde impute stack
data_2<-data_0 joinaggregate
data_3<-data_2 filter filter
data_4<-data_2 filter aggregate
data_5<-data_4 filter
data_6<-data_4 filter
data_7<-data_0 aggregate
data_8<-data_7 filter
data_9<-data_7 filter""",
      flow(
        """{$rows,"transform":[{"filter":"datum.c > 0"}],
            "vconcat":[$area,${box("c", "quantitative")},${box("c", "quantitative")}]}"""
      ),
    )
  }
}
