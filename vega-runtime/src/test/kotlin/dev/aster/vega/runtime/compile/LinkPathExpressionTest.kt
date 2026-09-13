package dev.aster.vega.runtime.compile

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.scene.PathNode
import dev.aster.vega.scene.flatten
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A `field` parameter may be an **expression**, and `linkpath`'s four endpoints are field
 * parameters.
 *
 * ```js
 * { 'name': 'sourceX', 'type': 'field' }
 * ```
 *
 * A field-typed parameter is a tuple accessor, and upstream builds one from a column name, from
 * `{"field": …}` or from `{"expr": …}` — so a coordinate that is a scale lookup is as ordinary as a
 * column. A sankey writes exactly that, its links running between band positions rather than
 * between numbers in the data. Read as a string alone, the parameter stringified to
 * `expr:scale(…)`, no row had a field by that name, and every link came out `MNaN,NaN…` — twelve
 * paths with no geometry at all, and the whole diagram blank.
 *
 * The path is upstream's own for this specification.
 */
class LinkPathExpressionTest {

  private fun compiled() =
    SpecCompiler(VegaHeadlessTextEngine())
      .compileJson(
        """
        {
          "width": 100, "height": 50, "padding": 0, "autosize": "none",
          "scales": [{"name": "x", "type": "band", "domain": ["p", "q"], "range": "width"}],
          "data": [{"name": "t", "values": [{"a": 1}], "transform": [
            {"type": "linkpath", "shape": "diagonal", "orient": "horizontal",
             "sourceX": {"expr": "scale('x','p') + bandwidth('x')"}, "sourceY": {"expr": "10"},
             "targetX": {"expr": "scale('x','q')"}, "targetY": {"expr": "40"}}]}],
          "marks": [{"type": "path", "from": {"data": "t"}, "encode": {"update": {
            "path": {"field": "path"}, "fill": {"value": "black"}}}}]
        }
        """
          .trimIndent()
      )

  @Test
  fun `a link drawn between scale lookups has the outline upstream draws`() {
    val path =
      requireNotNull(compiled().scene)
        .flatten()
        .map { it.node }
        .filterIsInstance<PathNode>()
        .single()
    // `M50,10C50,10 50,40 50,40`: both bands are 50 wide, so the link leaves the first at 50 and
    // arrives at the second at 50, curving from y 10 to y 40. Filled rather than stroked, so the
    // bounds are the outline's own and not the outline plus a stroke's allowance.
    assertEquals(50.0, path.bounds.left, 1e-9)
    assertEquals(50.0, path.bounds.right, 1e-9)
    assertEquals(10.0, path.bounds.top, 1e-9)
    assertEquals(40.0, path.bounds.bottom, 1e-9)
  }

  /** And it says nothing about it, where an unreadable accessor is still reported. */
  @Test
  fun `an expression endpoint is not reported as a missing field`() {
    assertEquals(
      emptyList<String>(),
      compiled()
        .diagnostics
        .filter { it.severity >= DiagnosticSeverity.WARNING }
        .map { it.message },
    )
  }
}
