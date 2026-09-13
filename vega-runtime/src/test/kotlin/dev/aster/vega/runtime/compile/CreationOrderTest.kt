package dev.aster.vega.runtime.compile

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.scene.TextNode
import dev.aster.vega.scene.flatten
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A sort breaks its ties by the order the rows were **created** in, not by the order they are in.
 *
 * ```js
 * export function stableCompare(cmp, f) {
 *   return !cmp ? null
 *     : (a, b) => cmp(a, b) || (tupleid(a) - tupleid(b));
 * }
 * ```
 *
 * `collect` — and `window`'s own sort, and `stack`'s — resolve a tie by **tuple id**, the number
 * stamped on a tuple when it was made. So a table sorted by one field and then by another does not
 * keep the first sort's order among the second's ties: it goes back to the order the rows arrived
 * in. Sorting stably on the current order instead reverses such a group, and a parliament diagram
 * built from five concentric rows gave three of its seats the wrong colour.
 *
 * Below: four rows sorted by `v` and then by `w`, which is the same for all of them. Upstream
 * answers `abcd` — the order they were written in — where a stable sort on the current order
 * answers `bdac`.
 */
class CreationOrderTest {

  private fun order(transforms: String): String {
    val spec =
      """
      {
        "width": 60, "height": 40, "padding": 0, "autosize": "none",
        "data": [{
          "name": "t",
          "values": [
            {"k": "a", "v": 2, "w": 1}, {"k": "b", "v": 1, "w": 1},
            {"k": "c", "v": 3, "w": 1}, {"k": "d", "v": 1, "w": 1}
          ],
          "transform": [$transforms]
        }],
        "marks": [{"type": "text", "from": {"data": "t"}, "encode": {"update": {
          "text": {"field": "k"}, "x": {"value": 5}, "y": {"value": 5}}}}]
      }
      """
        .trimIndent()
    return requireNotNull(SpecCompiler(VegaHeadlessTextEngine()).compileJson(spec).scene)
      .flatten()
      .map { it.node }
      .filterIsInstance<TextNode>()
      .joinToString("") { it.text }
  }

  @Test
  fun `a second sort over a re-ordered table breaks its ties by creation order`() {
    assertEquals(
      "abcd",
      order(
        """{"type": "collect", "sort": {"field": "v", "order": "ascending"}},
           {"type": "collect", "sort": {"field": "w", "order": "ascending"}}"""
      ),
    )
  }

  /** The first sort on its own, which has no ties to break and must not move. */
  @Test
  fun `one sort orders by its field`() {
    assertEquals(
      "bdac",
      order("""{"type": "collect", "sort": {"field": "v", "order": "ascending"}}"""),
    )
  }

  /** And ties within *that* sort keep the order the rows arrived in, which is the same rule. */
  @Test
  fun `ties in a single sort keep the order the rows arrived in`() {
    assertEquals(
      "bdac",
      order("""{"type": "collect", "sort": {"field": "v", "order": "ascending"}}"""),
    )
  }
}
