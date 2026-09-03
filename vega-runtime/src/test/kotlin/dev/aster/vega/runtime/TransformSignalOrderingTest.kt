package dev.aster.vega.runtime

import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.runtime.compile.SpecCompiler
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.SymbolNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A signal read through a transform's **expression** parameter is ordered before the transform.
 *
 * `SUPPORTED_FEATURES.md` said it was not: "a signal read through a transform's expression
 * parameter … is not an edge in the graph … it resolves whenever the ordering happens to put the
 * signal first, which it usually does; when it does not, the read comes back null, and null is zero
 * to arithmetic." It named Vega's radial tree example as failing that way, every node collapsed on
 * the origin.
 *
 * The row disagrees with the code's own documentation. `DataflowOrder.collectTransformExpressions`
 * is titled *"The transform parameters upstream declares as `type: 'expr'`, which are edges after
 * all"*, and it walks `filter`'s `expr`, `formula`'s `expr` and `cross`'s `filter` through the same
 * `readsOf` every `{"signal": …}` reference goes through. The ordering is not luck.
 *
 * What is left is a real **cycle** — a signal whose value depends on the dataset whose transform
 * reads the signal — and that is reported by name rather than resolved to null in silence. Both are
 * pinned here: the ordinary case works, and the impossible case says so.
 */
class TransformSignalOrderingTest {

  private fun compile(json: String) = SpecCompiler().compileJson(json)

  private fun symbolXs(root: SceneNode): List<Double> {
    val out = mutableListOf<Double>()
    fun walk(node: SceneNode) {
      if (node is SymbolNode) out += node.x
      if (node is GroupNode) node.children.forEach { walk(it) }
    }
    walk(root)
    return out
  }

  /**
   * The shape the row said was luck: a computed signal read by a `formula`'s `expr`.
   *
   * Declared **after** the dataset that reads it, so a resolver walking the document in order would
   * get it wrong. That is the whole point — the ordering comes from the dependency graph, not from
   * where the author happened to type it.
   */
  private val radialShape =
    """
    {"width": 100, "height": 100, "padding": 0, "autosize": "none",
     "data": [{"name": "t", "values": [{"r": 1}, {"r": 2}],
               "transform": [{"type": "formula", "as": "px", "expr": "originX + datum.r"}]}],
     "signals": [{"name": "originX", "update": "width / 2"}],
     "marks": [{"type": "symbol", "from": {"data": "t"},
                "encode": {"enter": {"x": {"field": "px"}, "y": {"value": 5}}}}]}
    """
      .trimIndent()

  @Test
  fun `a formula reads a computed signal whatever order they are declared in`() {
    val compiled = compile(radialShape)
    assertEquals(emptyList<String>(), compiled.diagnostics.map { it.message })
    // `width / 2` is 50, so the two rows land at 51 and 52 — not at 1 and 2, which is what reading
    // the signal as null would give, and not at the origin, which is how the radial tree failed.
    assertEquals(listOf(51.0, 52.0), symbolXs(requireNotNull(compiled.scene).root))
  }

  /**
   * `filter`'s `expr` and `cross`'s `filter` are the other two upstream declares as `type: expr`.
   */
  @Test
  fun `a filter reads a computed signal too`() {
    val compiled =
      compile(
        """
        {"width": 100, "height": 100, "padding": 0, "autosize": "none",
         "data": [{"name": "t", "values": [{"r": 1}, {"r": 9}],
                   "transform": [{"type": "filter", "expr": "datum.r > cut"}]}],
         "signals": [{"name": "cut", "update": "5"}],
         "marks": [{"type": "symbol", "from": {"data": "t"},
                    "encode": {"enter": {"x": {"field": "r"}, "y": {"value": 5}}}}]}
        """
          .trimIndent()
      )
    assertEquals(emptyList<String>(), compiled.diagnostics.map { it.message })
    // Only the 9 survives. A null `cut` would compare against zero and keep both.
    assertEquals(listOf(9.0), symbolXs(requireNotNull(compiled.scene).root))
  }

  /**
   * A genuine cycle is **named**, which is what is left of the row's warning.
   *
   * The signal's value depends on the dataset whose transform reads the signal, so no ordering can
   * satisfy both. Upstream refuses such a specification; this reports it, says which way round it
   * resolved, and draws — a chart that draws with one value stuck beats a chart that does not draw.
   *
   * This is the assertion the row now rests on: if the cycle ever went unreported, a reader would
   * get a diagram silently collapsed on the origin, which is exactly the failure the row was
   * written about.
   */
  @Test
  fun `a cycle between a signal and the dataset it reads is reported by name`() {
    val compiled =
      compile(
        """
        {"width": 100, "height": 100, "padding": 0, "autosize": "none",
         "signals": [{"name": "originX", "update": "length(data('t'))"}],
         "data": [{"name": "t", "values": [{"r": 1}],
                   "transform": [{"type": "formula", "as": "px", "expr": "originX + datum.r"}]}],
         "marks": [{"type": "symbol", "from": {"data": "t"},
                    "encode": {"enter": {"x": {"field": "px"}, "y": {"value": 5}}}}]}
        """
          .trimIndent()
      )
    val cycle = compiled.diagnostics.filter { it.code == DiagnosticCodes.SIGNAL_CYCLE }
    assertTrue(
      cycle.isNotEmpty(),
      "a signal and a dataset each waiting for the other drew in silence: " +
        compiled.diagnostics.map { it.message },
    )
    // Named on both sides, and in the order it resolved them, so a reader can act on it.
    val message = cycle.first().message
    assertTrue("originX" in message && "'t'" in message, message)
    assertTrue("resolved first" in message, message)
    // And it still drew.
    assertTrue(compiled.scene != null, "a reported cycle abandoned the chart")
  }
}
