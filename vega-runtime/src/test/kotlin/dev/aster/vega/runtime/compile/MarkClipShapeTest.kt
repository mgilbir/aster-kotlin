package dev.aster.vega.runtime.compile

import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.RectD
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.SymbolNode
import dev.aster.vega.scene.transformedBounds
import dev.aster.vega.svg.SvgRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A mark's `clip` is not a boolean: it is a **shape**.
 *
 * ```js
 * if (isObject(clip)) {
 *   if (clip.signal)      expr = clip.signal;
 *   else if (clip.path)   expr = 'pathShape(' + param(clip.path) + ')';
 *   else if (clip.sphere) expr = 'geoShape(' + param(clip.sphere) + ', {type: "Sphere"})';
 * }
 * return expr ? scope.signalRef(expr) : !!clip;
 * ```
 *
 * Every object form was read here for its **truthiness**, so all four of these clipped to the
 * enclosing group's rectangle: a `{"signal"}` that says `false`, a `{"path"}` naming an outline, a
 * `{"sphere"}` naming a projection, and a globe whose projection does not exist. Upstream draws
 * four different pictures, and the rectangle is the right answer for none of them.
 *
 * Each expectation below was read off a live upstream view over the same three symbols at x = −20,
 * 50 and 160 in a 100 × 60 plotting area:
 *
 * | `clip`                               | upstream's mark bounds                                  |
 * |--------------------------------------|---------------------------------------------------------|
 * | `true`                               | `0 … 100` — the group's rectangle                       |
 * | `{"signal": "false"}`                | `−27 … 167` — not clipped at all                        |
 * | `{"signal": "p"}`, `p` a path string | `0 … 100` — a string is not a function                  |
 * | `{"path": "M10,10L60,10L60,50Z"}`    | `10 … 60` — the path's own box                          |
 * | `{"path": ""}`                       | `0 … 100` — a falsy sub-value never reaches `pathShape` |
 * | `{"sphere": "proj"}`                 | `25 … 75` — the globe's outline                         |
 * | `{"sphere": "nope"}`                 | empty — a clip that encloses nothing                    |
 */
class MarkClipShapeTest {

  private fun spec(clip: String, projection: Boolean = false): String =
    """
    {
      "width": 100, "height": 60, "padding": 0,
      "autosize": {"type": "none"},
      "signals": [{"name": "p", "value": "M10,10L60,10L60,50Z"}],
      ${if (projection) """"projections": [
        {"name": "proj", "type": "orthographic", "scale": 25, "translate": [50, 30]}
      ],""" else ""}
      "data": [{"name": "t", "values": [
        {"x": -20, "y": 30}, {"x": 50, "y": 30}, {"x": 160, "y": 30}
      ]}],
      "marks": [
        {"type": "symbol", "clip": $clip, "from": {"data": "t"}, "encode": {"enter": {
          "x": {"field": "x"}, "y": {"field": "y"}, "size": {"value": 200}
        }}}
      ]
    }
    """
      .trimIndent()

  private fun scene(clip: String, projection: Boolean = false) =
    requireNotNull(SpecCompiler().compileJson(spec(clip, projection)).scene) { "no scene" }

  private fun descendants(node: SceneNode): List<SceneNode> =
    listOf(node) + ((node as? GroupNode)?.children?.flatMap { descendants(it) } ?: emptyList())

  /** The container the clipped mark was wrapped in, or null where nothing clips. */
  private fun clipContainer(clip: String, projection: Boolean = false): GroupNode? =
    descendants(scene(clip, projection).root)
      .filterIsInstance<GroupNode>()
      .filter { it.clip != null && it.children.any { child -> child is SymbolNode } }
      .singleOrNull()

  /** What the clipped mark covers, which is what upstream's `boundClip` reports. */
  private fun reach(clip: String, projection: Boolean = false): RectD? =
    clipContainer(clip, projection)?.let { container ->
      container.children
        .map { it.transformedBounds }
        .reduce { a, b -> a.union(b) }
        .let { bounds ->
          val window = container.clip!!
          RectD(
            maxOf(bounds.left, window.left),
            maxOf(bounds.top, window.top),
            minOf(bounds.right, window.right),
            minOf(bounds.bottom, window.bottom),
          )
        }
    }

  @Test
  fun `a signal that says no does not clip`() {
    assertEquals(null, clipContainer("""{"signal": "false"}"""))
    assertEquals(3, descendants(scene("""{"signal": "false"}""").root).count { it is SymbolNode })
  }

  @Test
  fun `a signal that says yes clips to the rectangle`() {
    val container = clipContainer("""{"signal": "true"}""")
    assertEquals(RectD(0.0, 0.0, 100.0, 60.0), container?.clip)
    assertEquals(null, container?.clipPath, "a rectangle is not a path clip")
  }

  @Test
  fun `a signal answering a path string still clips to the rectangle`() {
    // `isFunction(clip)` upstream, and a string is not a function however much it looks like a
    // path. This is the reading the shape of the value invites and it is wrong.
    val container = clipContainer("""{"signal": "p"}""")
    assertEquals(RectD(0.0, 0.0, 100.0, 60.0), container?.clip)
    assertEquals(null, container?.clipPath)
  }

  @Test
  fun `a path clips to that path`() {
    val container = clipContainer("""{"path": "M10,10L60,10L60,50Z"}""")
    assertEquals(RectD(10.0, 10.0, 60.0, 50.0), container?.clipPath?.bounds)
    // The window everything measures against is the path's own bounding box, upstream's
    // `boundContext`.
    assertEquals(RectD(10.0, 10.0, 60.0, 50.0), container?.clip)
    assertEquals(10.0, reach("""{"path": "M10,10L60,10L60,50Z"}""")?.left ?: 0.0, 1e-9)
    assertEquals(60.0, reach("""{"path": "M10,10L60,10L60,50Z"}""")?.right ?: 0.0, 1e-9)
  }

  @Test
  fun `a path written as a signal is the same clip`() {
    val container = clipContainer("""{"path": {"signal": "p"}}""")
    assertEquals(RectD(10.0, 10.0, 60.0, 50.0), container?.clipPath?.bounds)
    assertTrue(
      SvgRenderer()
        .render(scene("""{"path": {"signal": "p"}}"""))
        .svg
        .contains("""<path d="M10,10 L60,10 L60,50 Z"/>"""),
      "the signal's path is the clip",
    )
  }

  @Test
  fun `an empty path is not a path clip at all`() {
    // `else if (clip.path)` — a falsy sub-value never reaches `pathShape`, and the object it was
    // written in is still truthy, so the mark clips to the rectangle.
    val container = clipContainer("""{"path": ""}""")
    assertEquals(RectD(0.0, 0.0, 100.0, 60.0), container?.clip)
    assertEquals(null, container?.clipPath)
  }

  @Test
  fun `a sphere clips to the globe's outline`() {
    val container = clipContainer("""{"sphere": "proj"}""", projection = true)
    val path = requireNotNull(container?.clipPath) { "the sphere's outline is the clip" }
    val bounds = path.bounds
    // A radius-25 orthographic globe at (50, 30): the outline is the circle around it.
    assertEquals(25.0, bounds.left, 0.001)
    assertEquals(75.0, bounds.right, 0.001)
    assertEquals(5.0, bounds.top, 0.001)
    assertEquals(55.0, bounds.bottom, 0.001)
    assertEquals(25.0, reach("""{"sphere": "proj"}""", projection = true)?.left ?: 0.0, 0.001)
    assertEquals(75.0, reach("""{"sphere": "proj"}""", projection = true)?.right ?: 0.0, 0.001)
  }

  @Test
  fun `a sphere naming no projection clips the mark away`() {
    // Upstream still has a clip function; it simply draws nothing, and the mark's bounds come back
    // empty. Leaving the mark unclipped would show what the specification asked to have cut off.
    val container = clipContainer("""{"sphere": "nope"}""", projection = true)
    assertTrue(container?.clip?.isEmpty == true, "a clip that encloses nothing")
    assertEquals(true, container?.clipPath?.isEmpty)
  }

  @Test
  fun `a path clip reaches the SVG output`() {
    val svg = SvgRenderer().render(scene("""{"path": "M10,10L60,10L60,50Z"}""")).svg
    assertTrue(
      svg.contains("""<clipPath id="vc0"><path d="M10,10 L60,10 L60,50 Z"/></clipPath>"""),
      "upstream writes the clip as a path in `defs`; got\n$svg",
    )
  }
}
