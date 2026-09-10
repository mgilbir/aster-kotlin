package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * An `identifier` survives only where new rows were **made**.
 *
 * ```js
 * if (node instanceof IdentifierNode) {
 *   // Only preserve IdentifierNodes if we have default discrete selections
 *   // in our model tree, and if the nodes come after tuple producing nodes.
 *   if (!(this.requiresSelectionId &&
 *         (isDataSourceNode(node.parent) || node.parent instanceof AggregateNode || node.parent instanceof ParseNode))) {
 *     node.remove();
 *   }
 * }
 * ```
 *
 * Upstream writes one at the head of every flow and takes it out again here, so the only question
 * is *what is above it*: a table, an aggregate or a parse — the three steps after which a row is a
 * new row with no identity of its own. Anywhere else the rows already carry one.
 *
 * It tells where a **fork** has moved. A chart that joins against the table it also draws from gets
 * a named point on that table, and `MergeOutputs` then hangs the drawing's own steps *below* that
 * point rather than beside it: the identifier at their head no longer sits on the table, so
 * upstream drops it and the rows are identified by the copy on the table's own branch. This engine
 * kept it, and the extra transform was the only difference in two specifications of the wild
 * corpus.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class IdentifierPruningTest {

  /** The flow as `«dataset»[transform,…]`, which is where an identifier shows. */
  private fun flow(spec: String): List<String> {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    return (compiled.fields["data"] as VegaValue.Arr)
      .values
      .mapNotNull { it as? VegaValue.Obj }
      .map { dataset ->
        val transforms =
          (dataset.fields["transform"] as? VegaValue.Arr)?.values.orEmpty().mapNotNull {
            (it as? VegaValue.Obj)?.string("type")
          }
        "${dataset.string("name")}[${transforms.joinToString(",")}]"
      }
  }

  private val rows = """{"values":[{"a":1,"b":2,"k":"x","z":9}]}"""
  private val picked = """"params":[{"name":"p","select":"point"}]"""
  private val position = """"x":{"field":"a","type":"quantitative"}"""

  /** A selection that remembers rows by identity puts the column on the table. */
  @Test
  fun `a point selection identifies the rows below the table`() {
    assertEquals(
      listOf("p_store[collect]", "source_0[]", "data_0[identifier,filter]"),
      flow("""{"data":$rows,$picked,"mark":"point","encoding":{$position}}"""),
    )
  }

  /** And again after an **aggregate**, whose output rows are not the rows that went in. */
  @Test
  fun `an aggregate needs an identifier of its own`() {
    assertEquals(
      listOf("p_store[collect]", "source_0[]", "data_0[identifier,aggregate,identifier,filter]"),
      flow(
        """
        {"data":$rows,$picked,"mark":"bar",
         "encoding":{"x":{"field":"k","type":"nominal"},
                     "y":{"aggregate":"sum","field":"a","type":"quantitative"}}}
        """
      ),
    )
  }

  /**
   * The reported shape: a chart that **joins against its own table**. The join's named point moves
   * the drawing's steps below it, so the identifier at their head is no longer on the table and
   * goes.
   */
  @Test
  fun `a join against the same table takes the identifier away`() {
    assertEquals(
      listOf("p_store[collect]", "source_0[]", "data_0[lookup,filter]"),
      flow(
        """
        {"data":$rows,$picked,
         "transform":[{"lookup":"a","from":{"data":$rows,"key":"a","fields":["z"]}}],
         "mark":"point","encoding":{$position}}
        """
      ),
    )
  }

  /** A layer whose sibling has the selection keeps one identifier, above the fork. */
  @Test
  fun `a layered chart identifies once above the fork`() {
    assertEquals(
      listOf(
        "p_store[collect]",
        "source_0[]",
        "data_0[identifier]",
        "data_1[filter]",
        "data_2[filter]",
      ),
      flow(
        """
        {"data":$rows,
         "layer":[{"mark":"point",$picked,"encoding":{$position}},
                  {"mark":"line","transform":[{"filter":"datum.a>0"}],"encoding":{$position}}]}
        """
      ),
    )
  }

  /** With no selection there is nothing to identify. */
  @Test
  fun `a chart with no selection has no identifier`() {
    assertEquals(
      listOf("source_0[]", "data_0[filter]"),
      flow("""{"data":$rows,"mark":"point","encoding":{$position}}"""),
    )
  }
}
