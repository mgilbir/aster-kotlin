package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which of two identical sibling aggregates survives depends on **when** they became siblings.
 *
 * Two optimizers can fold them, and they keep opposite ends:
 * ```js
 * // MergeAggregates, run first in each round
 * const mergedAggs = mergeableAggs.pop();
 * ```
 * ```js
 * // MergeIdenticalNodes, run later in the same round
 * const mergedNode = nodes.shift();
 * ```
 *
 * `MergeAggregates` sees only the aggregates that are *already* siblings when it runs, and keeps
 * the **last**. `MergeIdenticalNodes` hashes every node by what it emits, aggregates included, and
 * works top-down: folding a pair of identical steps brings their children together and it descends
 * straight into them, so a pair of aggregates that only *become* siblings during that pass is
 * folded by it, keeping the **first**. The branches below the survivor are then numbered in its
 * order, and every mark and scale domain names one of them.
 *
 * This compiler gave an aggregate no identity at all, leaving every such fold to its own
 * `MergeAggregates` in a later round — so the branches came out in the wrong order and each mark
 * read the other one's dataset. Six specifications in the wild corpus fold an aggregate that way.
 *
 * The pair being siblings from the start depends in turn on where the identifier a selection needs
 * is written: `parseData` writes one in **every model's** pipeline, so a chart's own sits above the
 * fork and the units' copies are taken out again. Written once per view here, they were one node
 * only after `MergeIdenticalNodes` had folded them — which is a round too late, and made a pair of
 * aggregates that upstream folds into the last fold into the first instead.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class IdenticalAggregateFoldTest {

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

  private val rows = """"data":{"values":[{"c":"a","v":1}]}"""
  private val aggregate = """{"aggregate":[{"op":"mean","field":"v","as":"m"}],"groupby":["c"]}"""
  private val calculate = """{"calculate":"datum.v * 2","as":"w"}"""
  private val encoding =
    """"encoding":{"x":{"field":"c","type":"nominal"},"y":{"field":"m","type":"quantitative"}}"""

  private fun member(vararg steps: String) =
    """{"mark":"bar","transform":[${steps.joinToString(",")}],$encoding}"""

  private val kept = """{"filter":"datum.m > 0"}"""

  /**
   * Siblings from the start, so `MergeAggregates` folds them and the **last** survives: the second
   * member's branch is numbered first and the first member reads the later dataset.
   */
  @Test
  fun `aggregates that were already siblings fold into the last`() {
    assertEquals(
      "source_0() data_0(aggregate) data_1(stack,filter) data_2(filter,stack,filter) | " +
        "layer_0_marks<-data_2 layer_1_marks<-data_1",
      flow("""{$rows,"layer":[${member(aggregate, kept)},${member(aggregate)}]}"""),
    )
  }

  /**
   * The reported shape: a step of each member's own stands above the aggregates, so they are not
   * siblings until `MergeIdenticalNodes` folds *that* step — and it then folds them into the
   * **first**, leaving the branches in the order the members were written.
   */
  @Test
  fun `aggregates that became siblings during the fold fold into the first`() {
    assertEquals(
      "source_0() data_0(formula,aggregate) data_1(filter,stack,filter) data_2(stack,filter) | " +
        "layer_0_marks<-data_1 layer_1_marks<-data_2",
      flow(
        """{$rows,"layer":[${member(calculate, aggregate, kept)},${member(calculate, aggregate)}]}"""
      ),
    )
  }

  /**
   * The identifier a selection needs is written once, above the fork, so it does not come between
   * the aggregates: they are siblings from the start and the last still survives.
   */
  @Test
  fun `an identifier above the fork leaves the aggregates siblings`() {
    assertEquals(
      "pick_store(collect) source_0() data_0(identifier,aggregate,identifier) " +
        "data_1(stack,filter) data_2(filter,stack,filter) | " +
        "layer_0_marks<-data_2 layer_1_marks<-data_1",
      flow(
        """{$rows,"params":[{"name":"pick","select":{"type":"point"}}],
            "layer":[${member(aggregate, kept)},${member(aggregate)}]}"""
      ),
    )
  }

  /** And a selection one plot of a concatenation declares leaves the pair siblings too. */
  @Test
  fun `a selection in one plot leaves the aggregates siblings`() {
    assertEquals(
      "pick_store(collect) source_0() data_0(identifier,aggregate) data_1(stack,filter) " +
        "data_2(identifier,stack,filter) | " +
        "concat_0_group<-null concat_1_group<-null",
      flow(
        """{$rows,"hconcat":[
            {"mark":"bar","transform":[$aggregate],$encoding,
             "params":[{"name":"pick","select":{"type":"point"}}]},
            ${member(aggregate)}]}"""
      ),
    )
  }
}
