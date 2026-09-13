package dev.aster.vega.dataflow.transform

import dev.aster.vega.expression.CachingExpressionCompiler
import dev.aster.vega.expression.ExpressionCompiler
import dev.aster.vega.expression.ExpressionScope
import dev.aster.vega.expression.VegaExpressionCompiler
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `key`: which cell a row falls into, when that is not "the group-by values".
 *
 * ```js
 * this.cellkey = _.key ? _.key : groupkey(this._dims);
 * ```
 *
 * `aggregate`, `joinaggregate` and `pivot` all declare it, and all three were ignoring it — every
 * one of them grouped by `groupby` alone, so a specification naming a key got a different number of
 * cells than upstream, each holding different rows. The parameter is in upstream's transform
 * definitions, which is what the published schema is generated from.
 *
 * The consequence worth stating is the one that makes `key` more than a renaming: a cell still
 * **reports** group-by values, and it takes them from the first row that reached it. Rows whose
 * group-by values differ therefore share a cell and the cell reports one of them — and for
 * `joinaggregate`, which writes the cell's whole tuple back onto every row, those values are
 * written over the rows' own.
 *
 * Every expectation is upstream's output for the same three rows.
 */
class CellKeyTest {

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
    (VegaJson.parse(
        """[{"a": "x", "b": "p", "c": "u", "v": 1},
            {"a": "y", "b": "p", "c": "w", "v": 2},
            {"a": "z", "b": "q", "c": "u", "v": 4}]"""
      ) as VegaValue.Arr)
      .values

  private fun run(transform: Transform, params: String): String {
    val context = Context()
    val out = transform.apply(rows, VegaJson.parse(params) as VegaValue.Obj, context)
    assertEquals(emptyList<String>(), context.diagnostics.diagnostics.map { it.message })
    // Compared as JSON, on one line: the cells' field **order** is part of what upstream produces.
    return VegaJson.write(VegaValue.Arr(out)).replace(Regex("\\s+"), "")
  }

  @Test
  fun `an aggregate cell is one per key value, reporting the first row's group-by values`() {
    assertEquals(
      """[{"a":"x","sum_v":3},{"a":"z","sum_v":4}]""",
      run(
        AggregateTransform,
        """{"groupby": ["a"], "key": "b", "fields": ["v"], "ops": ["sum"]}""",
      ),
    )
  }

  @Test
  fun `a key splits the rows even with no groupby`() {
    // The "one group over everything" shortcut is only a shortcut when nothing else decides the
    // cell.
    assertEquals(
      """[{"sum_v":3},{"sum_v":4}]""",
      run(AggregateTransform, """{"key": "b", "fields": ["v"], "ops": ["sum"]}"""),
    )
  }

  @Test
  fun `a joinaggregate writes the cell's group-by values back too`() {
    // The middle row arrived with `a: "y"` and leaves with `a: "x"`, because `extend(t,
    // cell.tuple)`
    // copies every property the cell carries and the cell's came from the first row in it.
    assertEquals(
      """[{"a":"x","b":"p","c":"u","v":1,"s":3},""" +
        """{"a":"x","b":"p","c":"w","v":2,"s":3},""" +
        """{"a":"z","b":"q","c":"u","v":4,"s":4}]""",
      run(
        JoinAggregateTransform,
        """{"groupby": ["a"], "key": "b", "fields": ["v"], "ops": ["sum"], "as": ["s"]}""",
      ),
    )
  }

  @Test
  fun `a pivot groups into the same cells`() {
    // A pivot *is* an aggregate upstream: `Pivot` builds one and forwards `key: _.key` to it.
    assertEquals(
      """[{"a":"x","u":1,"w":2},{"a":"z","u":4}]""",
      run(PivotTransform, """{"groupby": ["a"], "key": "b", "field": "c", "value": "v"}"""),
    )
  }

  @Test
  fun `without a key the group-by values still decide the cell`() {
    assertEquals(
      """[{"a":"x","sum_v":1},{"a":"y","sum_v":2},{"a":"z","sum_v":4}]""",
      run(AggregateTransform, """{"groupby": ["a"], "fields": ["v"], "ops": ["sum"]}"""),
    )
  }

  @Test
  fun `cross fills in the combinations no row reached`() {
    // Unchanged by the key work, and pinned here because the cross-product now compares against the
    // values each cell *reports* rather than against the cell keys themselves.
    assertEquals(
      """[{"a":"x","b":"p","sum_v":1},{"a":"y","b":"p","sum_v":2},{"a":"z","b":"q","sum_v":4},""" +
        """{"a":"x","b":"q"},{"a":"y","b":"q"},{"a":"z","b":"p"}]""",
      run(
        AggregateTransform,
        """{"groupby": ["a", "b"], "cross": true, "fields": ["v"], "ops": ["sum"]}""",
      ),
    )
  }
}
