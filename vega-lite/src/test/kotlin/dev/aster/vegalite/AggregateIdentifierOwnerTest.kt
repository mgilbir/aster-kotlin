package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The identifier below an aggregate belongs to the model that **wrote** the aggregate.
 *
 * ```js
 * } else if (isAggregate(t)) {
 *   transformNode = head = AggregateNode.makeFromTransform(head, t);
 *   derivedType = 'number';
 *   if (requiresSelectionId(model)) {
 *     head = new IdentifierNode(head);
 *   }
 * ```
 *
 * `parseTransformArray` runs once per model, and `requiresSelectionId(model)` is `forEachSelection`
 * over that model — for a layer, its own components and its members'. A transform written on a
 * layer is that layer's however many members carry a copy of it, so the aggregate is one node and
 * the identifier below it is one node too.
 *
 * This compiler asked each **member** instead. In a layer that aggregates once and whose first
 * member declares a selection, the member that declared none got a chain of its own — identical to
 * its neighbour's but for the missing identifier, and so unable to fold with it. The table was
 * computed twice, and a domain sorted by an aggregate read the union of both copies rather than the
 * one table upstream reads.
 *
 * The identifier after an aggregate an **encoding** asks for is still the view's own: that
 * aggregate is the unit's, and a layer of two bars with one of them hovered over is two chains.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class AggregateIdentifierOwnerTest {

  private fun compiled(spec: String) =
    VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
      as VegaValue.Obj

  /** Every dataset with the steps it runs, then which dataset each mark reads. */
  private fun flow(spec: String): String {
    val chart = compiled(spec)
    val data =
      (chart.fields["data"] as VegaValue.Arr).values.joinToString(" ") {
        it as VegaValue.Obj
        val steps =
          it.array("transform").orEmpty().joinToString(",") { step ->
            (step as VegaValue.Obj).string("type").orEmpty()
          }
        "${it.string("name")}<-${it.string("source")}[$steps]"
      }
    fun drawn(of: List<VegaValue>): List<String> = of.flatMap {
      it as VegaValue.Obj
      if (it.string("type") == "group")
        drawn((it.fields["marks"] as? VegaValue.Arr)?.values.orEmpty())
      else listOf("${it.string("name")}<-${it.obj("from")?.string("data")}")
    }
    return "$data | ${drawn((chart.fields["marks"] as VegaValue.Arr).values).joinToString(" ")}"
  }

  /** What the sorted domain reads. */
  private fun domain(spec: String): String =
    (compiled(spec).fields["scales"] as VegaValue.Arr)
      .values
      .map { it as VegaValue.Obj }
      .filter { it.string("name")?.endsWith("y") == true }
      .joinToString(" ") { VegaJson.write(it.fields["domain"]!!).replace(Regex("\\s+"), "") }

  private val rows = """"data":{"values":[{"c":"a","v":1}]}"""
  private val aggregate =
    """{"aggregate":[{"op":"sum","field":"v","as":"total"}],"groupby":["c"]}"""
  private val sorted =
    """"y":{"field":"c","type":"ordinal","sort":{"op":"sum","field":"total","order":"descending"}}"""
  private val summed = """"x":{"aggregate":"sum","field":"total","type":"quantitative"}"""
  private val label =
    """{"mark":"text","encoding":{"text":{"field":"total","type":"quantitative"}}}"""

  private fun layered(picks: Boolean, data: String = rows): String {
    val params = if (picks) ""","params":[{"name":"pick","select":{"type":"point"}}]""" else ""
    val head = if (data.isEmpty()) "" else "$data,"
    return """{$head"transform":[$aggregate],"encoding":{$sorted},
        "layer":[{"mark":"bar"$params,"encoding":{$summed}},$label]}"""
  }

  /**
   * The reported shape: the layer aggregates once and its first member picks, so the identifier
   * below that aggregate is the layer's and both members read one table.
   */
  @Test
  fun `a layer that aggregates once writes one identifier below it`() {
    assertEquals(
      "pick_store<-null[collect] source_0<-null[] " +
        "data_0<-source_0[identifier,aggregate,identifier] " +
        "data_1<-data_0[aggregate,identifier,filter] | " +
        "layer_0_marks<-data_1 layer_1_marks<-data_0",
      flow(layered(picks = true)),
    )
  }

  /** So the domain sorted by an aggregate reads that one table rather than a union of copies. */
  @Test
  fun `a sorted domain reads the one table`() {
    assertEquals(
      """{"data":"data_0","field":"c","sort":{"op":"sum","field":"total","order":"descending"}}""",
      domain(layered(picks = true)),
    )
  }

  /**
   * The reported shape itself: the layer is a **plot** of a concatenation, so the model that wrote
   * the aggregate has a name of its own and the selection is named for a member below it.
   */
  @Test
  fun `a plot's layer that aggregates once writes one identifier below it`() {
    assertEquals(
      "pick_store<-null[collect] source_0<-null[] " +
        "data_0<-source_0[identifier,aggregate,identifier] " +
        "data_1<-data_0[aggregate,identifier,filter] | " +
        "concat_0_layer_0_marks<-data_1 concat_0_layer_1_marks<-data_0",
      flow("""{$rows,"hconcat":[${layered(picks = true, data = "")}]}"""),
    )
  }

  /** With nothing picking anywhere there is no identifier at all, which is the shape to keep. */
  @Test
  fun `a layer with no selection writes no identifier`() {
    assertEquals(
      "source_0<-null[] data_0<-source_0[aggregate] data_1<-data_0[aggregate,filter] | " +
        "layer_0_marks<-data_1 layer_1_marks<-data_0",
      flow(layered(picks = false)),
    )
  }

  /**
   * An aggregate an **encoding** asks for is the unit's own, so the identifier below it is too: a
   * layer of two bars with one of them picked is two chains, and that is what must not change.
   */
  @Test
  fun `an aggregate the encoding asked for keeps the member's own answer`() {
    assertEquals(
      "pick_store<-null[collect] source_0<-null[] data_0<-source_0[identifier,aggregate] " +
        "data_1<-data_0[filter] data_2<-data_0[identifier,filter] | " +
        "layer_0_marks<-data_2 layer_1_marks<-data_1",
      flow(
        """{$rows,"encoding":{"y":{"field":"c","type":"ordinal"}},
            "layer":[{"mark":"bar","params":[{"name":"pick","select":{"type":"point"}}],
                      "encoding":{$summed}},
                     {"mark":"tick","encoding":{$summed}}]}"""
      ),
    )
  }
}
