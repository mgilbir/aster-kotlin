package dev.aster.vega.runtime.compile

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.scene.TextNode
import dev.aster.vega.scene.flatten
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A mark's `sort` orders the **items** it draws, whatever kind of mark it is.
 *
 * ```js
 * if (mod) pulse.source.sort(stableCompare(_.sort));
 * ```
 *
 * Upstream gives any mark that declares a `sort` a `SortItems` operator, and its fields are paths
 * into the scene **item**: `x` and `y` name where the item ended up, another channel names what the
 * encoding resolved for it, and anything under `datum` reaches through to the row — `datum.year`
 * and `datum["year"]` being the same reach.
 *
 * This engine applied a sort to a line and an area, where the vertices are the only thing an order
 * can mean, and ignored it on every other mark; and it understood only `x` and `y`, so a line
 * sorted by `datum["date"]` was drawn in the order its rows arrived in. Neither said anything.
 *
 * The order below is upstream's own for this specification: four rows sorted **descending by y** by
 * a `collect`, then drawn by a text mark sorted ascending by `datum.x`. Upstream answers `cadb` —
 * the two rows sharing an x keeping the order the mark was handed them in, which is what its tuple
 * ids amount to.
 */
class MarkSortTest {

  private fun drawn(sort: String, type: String = "text"): String {
    val spec =
      """
      {
        "width": 100, "height": 50, "padding": 0, "autosize": "none",
        "data": [{
          "name": "t",
          "values": [
            {"k": "a", "x": 1, "y": 10}, {"k": "b", "x": 2, "y": 20},
            {"k": "c", "x": 1, "y": 30}, {"k": "d", "x": 2, "y": 40}
          ],
          "transform": [{"type": "collect", "sort": {"field": "y", "order": "descending"}}]
        }],
        "marks": [{"type": "$type", "from": {"data": "t"}, $sort
          "encode": {"update": {
            "text": {"field": "k"}, "x": {"field": "x"}, "y": {"field": "y"}}}}]
      }
      """
        .trimIndent()
    return requireNotNull(SpecCompiler(VegaHeadlessTextEngine()).compileJson(spec).scene)
      .flatten()
      .map { it.node }
      .filterIsInstance<TextNode>()
      .joinToString("") { it.text }
  }

  /** A text mark, which had no sort at all before: the rows as the `collect` left them. */
  @Test
  fun `a mark with no sort keeps the order it was handed`() {
    assertEquals("dcba", drawn(""))
  }

  /** The same mark sorted by a column of its data. */
  @Test
  fun `a sort by a datum path orders any mark's items`() {
    assertEquals("cadb", drawn(""""sort": {"field": "datum.x"},"""))
  }

  /** And spelled the way Vega-Lite spells it, which is the same reach. */
  @Test
  fun `a bracketed datum path is the same path`() {
    assertEquals("cadb", drawn("""  "sort": {"field": "datum[\"x\"]"},"""))
  }

  /** A channel names what the encoding resolved, which for `x` is where the item ended up. */
  @Test
  fun `a sort by a channel orders by where the items landed`() {
    assertEquals("cadb", drawn(""""sort": {"field": "x"},"""))
  }

  /** Descending, which is the one order word upstream tests for. */
  @Test
  fun `a descending sort reverses it`() {
    assertEquals(
      "dbca",
      drawn(""""sort": {"field": "datum.x", "order": "descending"},"""),
    )
  }

  /**
   * A path that is neither is **reported**, because a tie looks exactly like a sort that worked.
   */
  @Test
  fun `a sort naming nothing the mark has is reported`() {
    val compiled =
      SpecCompiler(VegaHeadlessTextEngine())
        .compileJson(
          """
          {
            "width": 100, "height": 50, "padding": 0, "autosize": "none",
            "data": [{"name": "t", "values": [{"k": "a"}, {"k": "b"}]}],
            "marks": [{"type": "text", "from": {"data": "t"}, "sort": {"field": "nowhere"},
              "encode": {"update": {"text": {"field": "k"}, "x": {"value": 1},
                                    "y": {"value": 1}}}}]
          }
          """
            .trimIndent()
        )
    assertTrue(
      compiled.diagnostics.any {
        it.severity == DiagnosticSeverity.WARNING && "sort names 'nowhere'" in it.message
      },
      "expected the unreadable sort path to be reported; got ${compiled.diagnostics.map { it.message }}",
    )
  }
}
