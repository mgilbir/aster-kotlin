package dev.aster.vega.compose.mp

import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.runtime.compile.SpecCompiler
import dev.aster.vega.runtime.load.DenyLoader
import dev.aster.vega.scene.MetricTextEngine
import dev.aster.vega.scene.Scene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Compose renderer's walk, checked against scenes the **engine actually compiled**.
 *
 * These are not hand-built trees: each specification goes through the same compiler an application
 * would use, so what is asserted is the whole path from JSON to draw call.
 *
 * They live in `commonTest`, so they are **compiled** for every target this module claims —
 * Android, both iOS targets and the JVM — and a Kotlin/Native restriction that the JVM does not
 * have fails here rather than in a release. They are **run** on the JVM by `scripts/check.sh`;
 * running them on iOS needs a simulator runtime this machine does not have, which is the same gap
 * `check.sh` already records for the core's own native suites.
 *
 * What is asserted is the sequence of calls, because that is what a renderer can get wrong: which
 * primitives, with which geometry, in which order. No pixels and no composition — a `DrawScope`
 * needs a surface, and none of the logic under test lives there.
 */
class SceneWalkTest {

  private fun scene(json: String): Scene {
    // The compiler's own defaults for the seed and the clock, which are pinned — a specification
    // mentioning `now()` is the same chart on every run.
    val compiled =
      SpecCompiler(textEngine = MetricTextEngine(), loader = DenyLoader).compileJson(json)
    val complaints =
      compiled.diagnostics.filter {
        it.severity == DiagnosticSeverity.ERROR || it.severity == DiagnosticSeverity.FATAL
      }
    assertTrue(complaints.isEmpty(), "compiled with errors: ${complaints.map { it.message }}")
    return requireNotNull(compiled.scene) { "no scene" }
  }

  private fun record(json: String): List<String> {
    val target = RecordingTarget()
    SceneWalk().draw(scene(json), target)
    return target.calls
  }

  @Test
  fun `bars are drawn as rectangles in datum order where the scales put them`() {
    val drawn =
      record(
        """
        {"${'$'}schema": "https://vega.github.io/schema/vega/v6.json",
         "width": 100, "height": 50, "padding": 0,
         "background": "white",
         "data": [{"name": "t", "values": [{"c": "a", "v": 1}, {"c": "b", "v": 2}]}],
         "scales": [
           {"name": "x", "type": "band", "domain": {"data": "t", "field": "c"}, "range": "width"},
           {"name": "y", "domain": {"data": "t", "field": "v"}, "range": "height"}],
         "marks": [{"type": "rect", "from": {"data": "t"}, "encode": {"enter": {
           "x": {"scale": "x", "field": "c"}, "width": {"scale": "x", "band": 1},
           "y": {"scale": "y", "field": "v"}, "y2": {"scale": "y", "value": 0},
           "fill": {"value": "steelblue"}}}}]}
        """
      )

    // The background comes first, and is not a node — a walk that only visited the tree would miss
    // it and leave the chart transparent.
    assertEquals("rect (0,0 100x50) fill #ffffff", drawn.first())

    val bars = drawn.filter { it.contains("#4682b4") }
    assertEquals(2, bars.size, "one rectangle per datum:\n${drawn.joinToString("\n")}")
    assertTrue(
      bars[0].contains("(0,25") && bars[1].contains("(50,0"),
      "in datum order, positioned by the scales:\n${bars.joinToString("\n")}",
    )
  }

  @Test
  fun `an axis contributes a group and its ticks and labels`() {
    val drawn =
      record(
        """
        {"${'$'}schema": "https://vega.github.io/schema/vega/v6.json",
         "width": 120, "height": 60, "padding": 0,
         "scales": [{"name": "y", "domain": [0, 10], "range": "height"}],
         "axes": [{"orient": "left", "scale": "y", "tickCount": 2}],
         "marks": []}
        """
      )
    val all = drawn.joinToString("\n")
    assertTrue(drawn.any { it.trimStart().startsWith("group") }, "an axis is a group:\n$all")
    assertTrue(drawn.any { it.contains("line ") }, "it draws its ticks:\n$all")
    assertTrue(drawn.any { it.contains("text ") }, "and its labels:\n$all")
    // Indentation is nesting: the ticks are inside the axis group, not siblings of it.
    assertTrue(drawn.any { it.startsWith("  ") }, "and they are inside it:\n$all")
  }

  @Test
  fun `a symbol arrives as a path of cubics rather than a shape to reinvent`() {
    val drawn =
      record(
        """
        {"${'$'}schema": "https://vega.github.io/schema/vega/v6.json",
         "width": 40, "height": 40, "padding": 0,
         "data": [{"name": "t", "values": [{"x": 20, "y": 20}]}],
         "marks": [{"type": "symbol", "from": {"data": "t"}, "encode": {"enter": {
           "x": {"field": "x"}, "y": {"field": "y"}, "size": {"value": 400},
           "shape": {"value": "circle"}, "fill": {"value": "red"}}}}]}
        """
      )
    val paths = drawn.filter { it.startsWith("path ") || it.contains(" path ") }
    assertEquals(1, paths.size, "one symbol, one path:\n${drawn.joinToString("\n")}")
    assertTrue(paths[0].contains("#ff0000"), paths[0])
    // A circle arrives as four cubics: the engine reduces every curve before publishing a scene, so
    // a renderer never needs to know how to draw an ellipse.
    assertTrue(paths[0].contains("4 cubic"), "a circle is four cubics: ${paths[0]}")
    // Centred on the datum, at a radius set by an *area* of 400 — so about 11.3, not 400.
    assertTrue(paths[0].contains("from (20,"), "centred on its datum: ${paths[0]}")
  }

  @Test
  fun `a group's opacity paints its own panel and is not inherited`() {
    val drawn =
      record(
        """
        {"${'$'}schema": "https://vega.github.io/schema/vega/v6.json",
         "width": 100, "height": 50, "padding": 0,
         "marks": [{"type": "group", "encode": {"enter": {
            "x": {"value": 0}, "y": {"value": 0},
            "width": {"value": 100}, "height": {"value": 50},
            "fill": {"value": "black"}, "opacity": {"value": 0.5}}},
          "marks": [{"type": "rect", "encode": {"enter": {
            "x": {"value": 10}, "y": {"value": 10},
            "width": {"value": 30}, "height": {"value": 30},
            "fill": {"value": "red"}}}}]}]}
        """
      )
    val all = drawn.joinToString("\n")
    // The panel is half-opaque; the recording writes an alpha only when there is one.
    assertTrue(drawn.any { it.contains("#000000@0.5") }, "the panel is faded:\n$all")
    // The child is not. Inheriting would have written `#ff0000@0.5` here, which is the bug the
    // Android renderer had and the Swift renderer had before its pixel tests found it.
    assertTrue(
      drawn.any { it.contains("#ff0000") && !it.contains("@") },
      "the child is opaque:\n$all",
    )
  }

  @Test
  fun `a fully transparent group still draws its children`() {
    val drawn =
      record(
        """
        {"${'$'}schema": "https://vega.github.io/schema/vega/v6.json",
         "width": 100, "height": 50, "padding": 0,
         "marks": [{"type": "group", "encode": {"enter": {
            "x": {"value": 0}, "y": {"value": 0},
            "width": {"value": 100}, "height": {"value": 50},
            "fill": {"value": "black"}, "opacity": {"value": 0}}},
          "marks": [{"type": "rect", "encode": {"enter": {
            "x": {"value": 10}, "y": {"value": 10},
            "width": {"value": 30}, "height": {"value": 30},
            "fill": {"value": "red"}}}}]}]}
        """
      )
    val all = drawn.joinToString("\n")
    // Upstream renders the child and drops only the group's own panel: a transparent group is a
    // group with no background, not an invisible subtree.
    assertTrue(drawn.any { it.contains("#ff0000") }, "the child survives:\n$all")
    assertTrue(drawn.none { it.contains("#000000") }, "the panel does not:\n$all")
  }

  /**
   * A run's `align` and `baseline` are resolved by the walk, into a pen position.
   *
   * The bug this pins down was visible in the iOS demo and identical here: a right-aligned axis
   * label drawn *rightwards* from its anchor sits on top of the axis line instead of ending at it.
   * A target draws from a pen position and knows nothing about alignment, so this is where it has
   * to be right.
   */
  @Test
  fun `alignment is resolved into the pen position`() {
    val drawn =
      record(
        """
        {"${'$'}schema": "https://vega.github.io/schema/vega/v6.json",
         "width": 200, "height": 60, "padding": 0,
         "marks": [
           {"type": "text", "encode": {"enter": {
             "x": {"value": 100}, "y": {"value": 20},
             "text": {"value": "MMMM"}, "align": {"value": "left"},
             "fill": {"value": "black"}}}},
           {"type": "text", "encode": {"enter": {
             "x": {"value": 100}, "y": {"value": 40},
             "text": {"value": "MMMM"}, "align": {"value": "right"},
             "fill": {"value": "black"}}}},
           {"type": "text", "encode": {"enter": {
             "x": {"value": 100}, "y": {"value": 55},
             "text": {"value": "MMMM"}, "align": {"value": "center"},
             "fill": {"value": "black"}}}}]}
        """
      )
    val labels = drawn.filter { it.contains("text ") }
    assertEquals(3, labels.size, drawn.joinToString("\n"))

    // `text "MMMM" at (x,y) …`
    fun penX(line: String): Double = line.substringAfter(" at (").substringBefore(",").toDouble()

    val left = penX(labels[0])
    val right = penX(labels[1])
    val centre = penX(labels[2])

    assertEquals(100.0, left, 0.01, "left-aligned starts at its anchor")
    assertTrue(right < left, "right-aligned starts before it: $right vs $left")
    assertEquals((left + right) / 2, centre, 0.01, "centred sits halfway between the two")
  }

  /** Multi-line text is one call per line, stacked by the line height. */
  @Test
  fun `each line of a text run is drawn separately`() {
    val drawn =
      record(
        """
        {"${'$'}schema": "https://vega.github.io/schema/vega/v6.json",
         "width": 120, "height": 80, "padding": 0,
         "marks": [{"type": "text", "encode": {"enter": {
           "x": {"value": 10}, "y": {"value": 20},
           "text": {"value": "first\nsecond"},
           "fill": {"value": "black"}}}}]}
        """
      )
    val labels = drawn.filter { it.contains("text ") }
    // A walk that drew `layout.run` once would emit one call, and the second line would never
    // appear.
    assertEquals(2, labels.size, drawn.joinToString("\n"))
    assertTrue(labels[0].contains("\"first\""), labels[0])
    assertTrue(labels[1].contains("\"second\""), labels[1])
  }

  @Test
  fun `a gradient reaches the target as absolute points rather than fractions`() {
    val drawn =
      record(
        """
        {"${'$'}schema": "https://vega.github.io/schema/vega/v6.json",
         "width": 100, "height": 50, "padding": 0,
         "marks": [{"type": "rect", "encode": {"enter": {
           "x": {"value": 20}, "y": {"value": 10},
           "width": {"value": 60}, "height": {"value": 30},
           "fill": {"value": {"gradient": "linear", "stops": [
             {"offset": 0, "color": "red"}, {"offset": 1, "color": "blue"}]}}}}}]}
        """
      )
    val gradient = drawn.firstOrNull { it.contains("linear") }
    assertTrue(gradient != null, "the fill is a gradient:\n${drawn.joinToString("\n")}")
    // A specification writes `x1: 0, x2: 1` — edge to edge of the mark. The rectangle spans x 20 to
    // 80, so those fractions have to arrive as 20 and 80 or every renderer would resolve them
    // again.
    assertTrue(
      gradient.contains("(20,") && gradient.contains("(80,"),
      "resolved against the mark it fills: $gradient",
    )
    assertTrue(gradient.contains("2 stops"), gradient)
  }

  @Test
  fun `a nested group's transform is composed rather than re-applied`() {
    val drawn =
      record(
        """
        {"${'$'}schema": "https://vega.github.io/schema/vega/v6.json",
         "width": 200, "height": 100, "padding": 0,
         "marks": [{"type": "group", "encode": {"enter": {
            "x": {"value": 50}, "y": {"value": 20},
            "width": {"value": 100}, "height": {"value": 50}}},
          "marks": [{"type": "rect", "encode": {"enter": {
            "x": {"value": 10}, "y": {"value": 5},
            "width": {"value": 20}, "height": {"value": 10},
            "fill": {"value": "green"}}}}]}]}
        """
      )
    // The child sits at 10,5 inside a group at 50,20, so it lands at 60,25 on the surface. A target
    // never receives a coordinate it has to transform itself.
    assertTrue(
      drawn.any { it.contains("(60,25 20x10)") },
      "the child's coordinates are absolute:\n${drawn.joinToString("\n")}",
    )
  }
}
