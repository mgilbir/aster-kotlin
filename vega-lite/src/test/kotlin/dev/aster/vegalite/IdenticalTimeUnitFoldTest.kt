package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which of two identical sibling **time units** survives depends on when they became siblings.
 *
 * ```js
 * // MergeTimeUnits, run before MergeIdenticalNodes in each round
 * const parentTimeUnit = timeUnitChildren.pop();
 * ```
 * ```js
 * // MergeIdenticalNodes
 * const mergedNode = nodes.shift();
 * ```
 *
 * The same pair of optimizers that folds two aggregates folds two bucketings of an instant, and
 * they keep opposite ends. `MergeTimeUnits` sees only what is *already* sibling when it runs and
 * keeps the **last**, so the branches below it come out in reverse; `MergeIdenticalNodes` hashes
 * the node — `TimeUnit ${hash(this.timeUnits)}` — keeps the **first**, and reaches a pair that only
 * becomes sibling because the steps above them folded, in that same pass.
 *
 * This compiler gave a time unit no identity at all and left every such fold to its own
 * `MergeTimeUnits` a round later, so a chart whose layers all bucket one column had its branches
 * numbered backwards and every mark read its neighbour's dataset.
 *
 * One specification in the wild corpus buckets an instant in sixteen layers that way.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 *
 * @see IdenticalAggregateFoldTest, the same rule for the aggregate.
 */
class IdenticalTimeUnitFoldTest {

  /** Every dataset with the steps it runs, then which dataset each mark reads. */
  private fun flow(spec: String): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    val data =
      (compiled.fields["data"] as VegaValue.Arr).values.joinToString(" ") {
        it as VegaValue.Obj
        val steps =
          it.array("transform").orEmpty().joinToString(",") { step ->
            (step as VegaValue.Obj).string("type").orEmpty()
          }
        "${it.string("name")}($steps)"
      }
    val marks =
      (compiled.fields["marks"] as VegaValue.Arr).values.joinToString(" ") {
        it as VegaValue.Obj
        "${it.string("name")}<-${it.obj("from")?.string("data")}"
      }
    return "$data | $marks"
  }

  private val rows = """"data":{"values":[{"t":"2020-01-01","a":1}]}"""
  private val bucket = """{"as":"d","field":"t","timeUnit":"yearmonthdate"}"""
  private val rank = """{"sort":[{"field":"d"}],"window":[{"as":"r","field":"d","op":"rank"}]}"""
  private val keep = """{"filter":"(datum.a > 0)"}"""
  private val encoding =
    """"encoding":{"x":{"field":"d","type":"temporal"},"y":{"field":"a","type":"quantitative"}}"""

  private fun member(mark: String, vararg steps: String) =
    """{"mark":"$mark","transform":[${steps.joinToString(",")}],$encoding}"""

  /**
   * Siblings from the start, so `MergeTimeUnits` folds them and the **last** survives: the third
   * member's branch is numbered first and the second member reads the later dataset.
   */
  @Test
  fun `time units that were already siblings fold into the last`() {
    assertEquals(
      "source_0() data_0(formula,timeunit) data_1(filter) data_2(window,filter) | " +
        "layer_0_marks<-data_0 layer_1_marks<-data_2 layer_2_marks<-data_1",
      flow(
        """{$rows,"layer":[${member("line", bucket)},${member("circle", bucket, rank)},
            ${member("square", bucket)}]}"""
      ),
    )
  }

  /**
   * The reported shape: a step of each member's own stands above the bucketings, so they are not
   * siblings until `MergeIdenticalNodes` folds *that* step — and it then folds them into the
   * **first**, leaving the branches in the order the members were written.
   */
  @Test
  fun `time units that became siblings during the fold fold into the first`() {
    assertEquals(
      "source_0() data_0(formula,filter,timeunit) data_1(window,filter) data_2(filter) | " +
        "layer_0_marks<-data_0 layer_1_marks<-data_1 layer_2_marks<-data_2",
      flow(
        """{$rows,"layer":[${member("line", keep, bucket)},${member("circle", keep, bucket, rank)},
            ${member("square", keep, bucket)}]}"""
      ),
    )
  }
}
