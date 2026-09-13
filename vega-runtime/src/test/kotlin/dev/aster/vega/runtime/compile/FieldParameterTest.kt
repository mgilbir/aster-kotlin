package dev.aster.vega.runtime.compile

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.scene.TextNode
import dev.aster.vega.scene.flatten
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A **field parameter** may be written as an object, in every transform that takes one.
 *
 * ```js
 * const expr = def.expr || isField(type);
 * return expr && outerExpr(value) ? scope.exprRef(value.expr, value.as)
 *      : expr && outerField(value) ? fieldRef(value.field, value.as)
 *      : …
 * ```
 *
 * Upstream accepts three spellings for every field-typed parameter: a name, an `{"expr": …}` and a
 * `{"field": …}`. The third is the first written longhand, and reading it as a string turned it
 * into the name `field:amount`, which no row has — an aggregate over nothing, an extent of nothing,
 * and not a word about it.
 *
 * The expectations are upstream's own for this specification: an extent of `[1, 5]` and two groups
 * summing to 4 and 5.
 */
class FieldParameterTest {

  private fun compiled() =
    SpecCompiler(VegaHeadlessTextEngine())
      .compileJson(
        """
        {
          "width": 60, "height": 30, "padding": 0, "autosize": "none",
          "data": [{"name": "t",
            "values": [{"a": 3, "g": "x"}, {"a": 1, "g": "x"}, {"a": 5, "g": "y"}],
            "transform": [
              {"type": "extent", "field": {"field": "a"}, "signal": "ex"},
              {"type": "aggregate", "groupby": [{"field": "g"}],
               "fields": [{"field": "a"}], "ops": ["sum"], "as": ["total"]}
            ]}],
          "marks": [{"type": "text", "from": {"data": "t"}, "encode": {"update": {
            "text": {"signal": "datum.g + ':' + datum.total"},
            "x": {"value": 1}, "y": {"value": 1}}}}]
        }
        """
          .trimIndent()
      )

  @Test
  fun `a groupby and a field written as objects name their columns`() {
    assertEquals(
      listOf("x:4", "y:5"),
      requireNotNull(compiled().scene)
        .flatten()
        .map { it.node }
        .filterIsInstance<TextNode>()
        .map { it.text },
    )
  }

  /** And the extent beside them, which publishes a signal rather than changing the rows. */
  @Test
  fun `an extent written as an object measures its column`() {
    val extent = compiled().signals.values["ex"] as dev.aster.vega.model.VegaValue.Arr
    assertEquals(
      listOf(1.0, 5.0),
      extent.values.map { (it as dev.aster.vega.model.VegaValue.Num).value },
    )
  }

  /**
   * An `{"expr": …}` in a transform that reads a column name is **reported**.
   *
   * The transforms that meet one in the wild evaluate it — `voronoi`, `linkpath`, `kde2d`,
   * `contour`, `force`. Anywhere else it would be stringified into a name no row has, which is a
   * silently empty answer; this says so instead.
   */
  @Test
  fun `an expression where a column name is expected is reported`() {
    val compiled =
      SpecCompiler(VegaHeadlessTextEngine())
        .compileJson(
          """
          {
            "width": 60, "height": 30, "padding": 0, "autosize": "none",
            "data": [{"name": "t", "values": [{"a": 1}],
              "transform": [{"type": "extent", "field": {"expr": "datum.a * 2"},
                             "signal": "ex"}]}]
          }
          """
            .trimIndent()
        )
    assertTrue(
      compiled.diagnostics.any {
        it.severity == DiagnosticSeverity.WARNING && "written as the expression" in it.message
      },
      "expected the unevaluated expression to be reported; got ${compiled.diagnostics.map { it.message }}",
    )
  }
}
