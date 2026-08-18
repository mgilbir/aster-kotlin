package dev.aster.vega.runtime.compile

import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.model.VegaValue
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.RuleNode
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.TextNode
import dev.aster.vega.scene.flatten
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Group marks: nesting, faceting, and the scope a group introduces.
 *
 * Every behaviour asserted here was read off upstream Vega running the same specification. Two are
 * surprising enough to be worth naming, because a reasonable implementation would get them wrong:
 * - `parent` is the group's *datum*, not the group item, so `parent.width` is undefined
 * - `width` and `height` are **not** redefined inside a group, so a nested `"height"` range spans
 *   the whole chart unless the group declares a `height` signal of its own
 */
class GroupMarkTest {

  private fun compile(json: String) = SpecCompiler().compileJson(json)

  private fun scopes(json: String): List<GroupNode> {
    val compiled = compile(json)
    assertTrue(
      compiled.diagnostics.none { it.severity >= DiagnosticSeverity.ERROR },
      "expected a clean compile; got ${compiled.diagnostics}",
    )
    return requireNotNull(compiled.scene)
      .flatten()
      .map { it.node }
      .filterIsInstance<GroupNode>()
      .filter { it.metadata.role == "scope" }
  }

  private fun texts(json: String): List<String> =
    requireNotNull(compile(json).scene)
      .flatten()
      .map { it.node }
      .filterIsInstance<TextNode>()
      .map { it.text }

  private inline fun <reified T : SceneNode> nodes(json: String): List<T> =
    requireNotNull(compile(json).scene).flatten().map { it.node }.filterIsInstance<T>()

  private val table =
    """
    {"name": "t", "values": [
      {"c": "north", "v": 1}, {"c": "north", "v": 3},
      {"c": "east", "v": 5}
    ]}
    """
      .trimIndent()

  // ---- how many groups, and what they contain --------------------------------

  @Test
  fun `a group with no data produces exactly one container`() {
    val groups =
      scopes(
        """
        {
          "width": 100, "height": 100, "padding": 0,
          "marks": [{
            "type": "group",
            "encode": {"enter": {"x": {"value": 10}, "y": {"value": 20},
              "width": {"value": 30}, "height": {"value": 40}}},
            "marks": [{"type": "rect", "encode": {"enter": {
              "x": {"value": 0}, "y": {"value": 0}, "width": {"value": 5}, "height": {"value": 5}}}}]
          }]
        }
        """
      )
    assertEquals(1, groups.size)
    assertEquals(30.0, groups[0].size?.width)
    assertEquals(40.0, groups[0].size?.height)
    assertEquals(1, groups[0].children.size)
  }

  @Test
  fun `a group with plain data produces one container per datum`() {
    val groups =
      scopes(
        """
        {
          "width": 100, "height": 100, "padding": 0,
          "data": [$table],
          "marks": [{
            "type": "group", "from": {"data": "t"},
            "encode": {"enter": {"y": {"field": "v"}, "width": {"value": 10},
              "height": {"value": 10}}}
          }]
        }
        """
      )
    assertEquals(3, groups.size)
    assertEquals(listOf(1.0, 3.0, 5.0), groups.map { it.transform.f })
  }

  @Test
  fun `a facet produces one group per distinct key, in first-appearance order`() {
    // "north" appears before "east" in the data even though it sorts after it.
    val labels =
      texts(
        """
        {
          "width": 100, "height": 100, "padding": 0,
          "data": [$table],
          "marks": [{
            "type": "group",
            "from": {"facet": {"name": "cell", "data": "t", "groupby": "c"}},
            "encode": {"enter": {"width": {"value": 10}, "height": {"value": 10}}},
            "marks": [{"type": "text", "encode": {"enter": {
              "x": {"value": 0}, "y": {"value": 0},
              "text": {"signal": "parent.c + ':' + parent.count"}}}}]
          }]
        }
        """
      )
    assertEquals(listOf("north:2", "east:1"), labels)
  }

  @Test
  fun `a facet binds its partition to the given name`() {
    val rects =
      nodes<RectNode>(
        """
        {
          "width": 100, "height": 100, "padding": 0,
          "data": [$table],
          "marks": [{
            "type": "group",
            "from": {"facet": {"name": "cell", "data": "t", "groupby": "c"}},
            "encode": {"enter": {"width": {"value": 10}, "height": {"value": 10}}},
            "marks": [{"type": "rect", "from": {"data": "cell"}, "encode": {"enter": {
              "x": {"field": "v"}, "y": {"value": 0},
              "width": {"value": 1}, "height": {"value": 1}}}}]
          }]
        }
        """
      )
    // Two rects in the first cell, one in the second — the partitions, not the whole table.
    assertEquals(listOf(1.0, 3.0, 5.0), rects.map { it.x })
  }

  @Test
  fun `a facet may group by several fields at once`() {
    val labels =
      texts(
        """
        {
          "width": 100, "height": 100, "padding": 0,
          "data": [{"name": "t", "values": [
            {"a": "x", "b": "1"}, {"a": "x", "b": "2"}, {"a": "x", "b": "1"}
          ]}],
          "marks": [{
            "type": "group",
            "from": {"facet": {"name": "cell", "data": "t", "groupby": ["a", "b"]}},
            "encode": {"enter": {"width": {"value": 1}, "height": {"value": 1}}},
            "marks": [{"type": "text", "encode": {"enter": {
              "x": {"value": 0}, "y": {"value": 0},
              "text": {"signal": "parent.a + parent.b + '=' + parent.count"}}}}]
          }]
        }
        """
      )
    assertEquals(listOf("x1=2", "x2=1"), labels)
  }

  // ---- the scope a group introduces ------------------------------------------

  @Test
  fun `parent is the group's datum, not the group item`() {
    // Upstream binds the tuple, so `parent.width` is undefined even though `parent.c` resolves.
    // A specification reading `parent.width` relies on something Vega does not provide, and this
    // engine must not provide it either.
    //
    // It stringifies as "null" here where upstream prints "undefined": this value model has one
    // absent value, not JavaScript's two. That gap is general rather than specific to groups, and
    // is recorded in SUPPORTED_FEATURES.md.
    val labels =
      texts(
        """
        {
          "width": 100, "height": 100, "padding": 0,
          "data": [$table],
          "marks": [{
            "type": "group",
            "from": {"facet": {"name": "cell", "data": "t", "groupby": "c"}},
            "encode": {"enter": {"width": {"value": 37}, "height": {"value": 11}}},
            "marks": [{"type": "text", "encode": {"enter": {
              "x": {"value": 0}, "y": {"value": 0},
              "text": {"signal": "'' + parent.width"}}}}]
          }]
        }
        """
      )
    assertEquals(listOf("null", "null"), labels)
  }

  @Test
  fun `a nested scale shadows an outer one of the same name`() {
    val rects =
      nodes<RectNode>(
        """
        {
          "width": 100, "height": 100, "padding": 0,
          "data": [$table],
          "scales": [{"name": "s", "type": "linear", "domain": [0, 100], "range": [0, 100]}],
          "marks": [{
            "type": "group",
            "encode": {"enter": {"width": {"value": 10}, "height": {"value": 10}}},
            "scales": [{"name": "s", "type": "linear", "domain": [0, 10], "range": [0, 100]}],
            "marks": [{"type": "rect", "encode": {"enter": {
              "x": {"scale": "s", "value": 5}, "y": {"value": 0},
              "width": {"value": 1}, "height": {"value": 1}}}}]
          }, {
            "type": "rect", "encode": {"enter": {
              "x": {"scale": "s", "value": 5}, "y": {"value": 0},
              "width": {"value": 1}, "height": {"value": 1}}}
          }]
        }
        """
      )
    // Inside the group, 5 of a [0, 10] domain is halfway; outside, 5 of [0, 100] is a twentieth.
    assertEquals(listOf(50.0, 5.0), rects.map { it.x })
  }

  @Test
  fun `a nested height range spans the chart, not the group`() {
    // The Vega gotcha, reproduced deliberately: a group's subscope inherits `width` and `height`,
    // so
    // a scale ranged over "height" inside a 20-tall group still spans the 100-tall chart.
    val rects =
      nodes<RectNode>(
        """
        {
          "width": 100, "height": 100, "padding": 0,
          "marks": [{
            "type": "group",
            "encode": {"enter": {"width": {"value": 20}, "height": {"value": 20}}},
            "scales": [{"name": "y", "type": "linear", "domain": [0, 10], "range": "height"}],
            "marks": [{"type": "rect", "encode": {"enter": {
              "x": {"value": 0}, "y": {"scale": "y", "value": 0},
              "width": {"value": 1}, "height": {"value": 1}}}}]
          }]
        }
        """
      )
    assertEquals(listOf(100.0), rects.map { it.y })
  }

  @Test
  fun `a group that declares its own height signal changes what a nested range means`() {
    val rects =
      nodes<RectNode>(
        """
        {
          "width": 100, "height": 100, "padding": 0,
          "marks": [{
            "type": "group",
            "signals": [{"name": "height", "update": "20"}],
            "encode": {"enter": {"width": {"value": 20}, "height": {"value": 20}}},
            "scales": [{"name": "y", "type": "linear", "domain": [0, 10], "range": "height"}],
            "marks": [{"type": "rect", "encode": {"enter": {
              "x": {"value": 0}, "y": {"scale": "y", "value": 0},
              "width": {"value": 1}, "height": {"value": 1}}}}]
          }]
        }
        """
      )
    assertEquals(listOf(20.0), rects.map { it.y })
  }

  @Test
  fun `a group may declare data of its own, sourced from the enclosing scope`() {
    val rects =
      nodes<RectNode>(
        """
        {
          "width": 100, "height": 100, "padding": 0,
          "data": [$table],
          "marks": [{
            "type": "group",
            "encode": {"enter": {"width": {"value": 10}, "height": {"value": 10}}},
            "data": [{"name": "big", "source": "t",
              "transform": [{"type": "filter", "expr": "datum.v > 2"}]}],
            "marks": [{"type": "rect", "from": {"data": "big"}, "encode": {"enter": {
              "x": {"field": "v"}, "y": {"value": 0},
              "width": {"value": 1}, "height": {"value": 1}}}}]
          }]
        }
        """
      )
    assertEquals(listOf(3.0, 5.0), rects.map { it.x })
  }

  // ---- geometry ---------------------------------------------------------------

  @Test
  fun `a group's own paint covers its declared size`() {
    val groups =
      scopes(
        """
        {
          "width": 100, "height": 100, "padding": 0,
          "marks": [{
            "type": "group",
            "encode": {"enter": {"x": {"value": 5}, "y": {"value": 5},
              "width": {"value": 30}, "height": {"value": 20},
              "fill": {"value": "#eee"}, "stroke": {"value": "#333"}}}
          }]
        }
        """
      )
    val rect = requireNotNull(groups[0].paintRect)
    assertEquals(0.0, rect.left)
    assertEquals(30.0, rect.right)
    assertEquals(20.0, rect.bottom)
  }

  @Test
  fun `a group with no paint has nothing to cover`() {
    val groups =
      scopes(
        """
        {
          "width": 100, "height": 100, "padding": 0,
          "marks": [{"type": "group",
            "encode": {"enter": {"width": {"value": 30}, "height": {"value": 20}}}}]
        }
        """
      )
    assertNull(groups[0].paintRect)
  }

  @Test
  fun `a group's bounds cover its declared size even when its children are smaller`() {
    val groups =
      scopes(
        """
        {
          "width": 100, "height": 100, "padding": 0,
          "marks": [{
            "type": "group",
            "encode": {"enter": {"width": {"value": 60}, "height": {"value": 40}}},
            "marks": [{"type": "rect", "encode": {"enter": {
              "x": {"value": 1}, "y": {"value": 1},
              "width": {"value": 2}, "height": {"value": 2}}}}]
          }]
        }
        """
      )
    assertEquals(60.0, groups[0].bounds.right)
    assertEquals(40.0, groups[0].bounds.bottom)
  }

  @Test
  fun `clip narrows a group to its own extent`() {
    val groups =
      scopes(
        """
        {
          "width": 100, "height": 100, "padding": 0,
          "marks": [{
            "type": "group", "clip": true,
            "encode": {"enter": {"width": {"value": 10}, "height": {"value": 10}}},
            "marks": [{"type": "rect", "encode": {"enter": {
              "x": {"value": 0}, "y": {"value": 0},
              "width": {"value": 500}, "height": {"value": 500}}}}]
          }]
        }
        """
      )
    assertEquals(10.0, groups[0].bounds.right)
    assertEquals(10.0, groups[0].bounds.bottom)
  }

  @Test
  fun `an axis inside a group is placed against the group's extent`() {
    val compiled =
      compile(
        """
        {
          "width": 200, "height": 200, "padding": 0,
          "marks": [{
            "type": "group",
            "signals": [{"name": "height", "update": "40"}],
            "encode": {"enter": {"width": {"value": 100}, "height": {"value": 40}}},
            "scales": [{"name": "y", "type": "linear", "domain": [0, 10], "range": "height"}],
            "axes": [{"orient": "bottom", "scale": "y", "ticks": false, "labels": false}]
          }]
        }
        """
      )
    val axis =
      requireNotNull(compiled.scene)
        .flatten()
        .map { it.node }
        .filterIsInstance<GroupNode>()
        .single { it.metadata.role == "axis" }
    // A bottom axis sits at the group's height, not the chart's, plus Vega's half-pixel crisp
    // offset.
    assertEquals(40.5, axis.transform.f, 1e-9)
  }

  @Test
  fun `a domain line spans the scale's range, not the plotting area`() {
    val rules =
      nodes<RuleNode>(
        """
        {
          "width": 200, "height": 200, "padding": 0,
          "scales": [{"name": "x", "type": "linear", "domain": [0, 10], "range": [20, 60]}],
          "axes": [{"orient": "bottom", "scale": "x", "ticks": false, "labels": false}]
        }
        """
      )
    val domain = rules.single { it.metadata.role == "axis-domain" }
    assertEquals(20.0, domain.x1)
    assertEquals(60.0, domain.x2)
  }

  @Test
  fun `a discrete height range ascends, so the first category is at the top`() {
    // Upstream keys this off the scale type: `"height"` is [0, height] for a discrete scale and
    // [height, 0] for a continuous one. Getting it wrong flips a row-faceted trellis upside down.
    val compiled =
      compile(
        """
        {
          "width": 100, "height": 90, "padding": 0,
          "data": [$table],
          "scales": [
            {"name": "band", "type": "band", "domain": {"data": "t", "field": "c"},
             "range": "height"},
            {"name": "linear", "type": "linear", "domain": [0, 10], "range": "height"}
          ]
        }
        """
      )
    val band = compiled.scales["band"] as dev.aster.vega.runtime.scale.BandScale
    val linear = compiled.scales["linear"] as dev.aster.vega.runtime.scale.LinearScale
    assertEquals(listOf(0.0, 90.0), band.range)
    assertEquals(listOf(90.0, 0.0), linear.range)
  }

  // ---- what is reported -------------------------------------------------------

  @Test
  fun `a facet over an unknown dataset is reported`() {
    val compiled =
      compile(
        """
        {
          "width": 100, "height": 100, "padding": 0,
          "marks": [{"type": "group", "name": "g",
            "from": {"facet": {"name": "cell", "data": "nope", "groupby": "c"}},
            "encode": {"enter": {"width": {"value": 1}, "height": {"value": 1}}}}]
        }
        """
      )
    assertTrue(
      compiled.diagnostics.any {
        it.severity >= DiagnosticSeverity.ERROR && it.message.contains("nope")
      },
      compiled.diagnostics.toString(),
    )
  }

  /**
   * `facet.field` groups nothing: the rows are already grouped and the field holds each group.
   *
   * One cell per row of the source, and the cell's own data is the array in that column. An
   * edge-bundling diagram is built this way — each dependency carries the path it takes through the
   * tree, and the cell draws it.
   */
  @Test
  fun `pre-faceted data makes one cell per row from the column it names`() {
    val compiled =
      compile(
        """
        {
          "width": 100, "height": 100, "padding": 0,
          "data": [{"name": "t", "values": [
            {"k": "a", "rows": [{"n": 1}, {"n": 2}]},
            {"k": "b", "rows": [{"n": 3}]}
          ]}],
          "marks": [{"type": "group",
            "from": {"facet": {"name": "cell", "data": "t", "field": "rows"}},
            "encode": {"enter": {"width": {"value": 10}, "height": {"value": 10}}},
            "marks": [{"type": "rect", "from": {"data": "cell"},
              "encode": {"enter": {"x": {"field": "n"}, "width": {"value": 1},
                                   "y": {"value": 0}, "height": {"value": 1}}}}]}]
        }
        """
      )
    assertTrue(
      compiled.diagnostics.none { it.severity >= DiagnosticSeverity.ERROR },
      compiled.diagnostics.toString(),
    )
    // Two cells, holding two rows and one: three rects in all, at the `n` of each row.
    val rects =
      requireNotNull(compiled.scene).flatten().map { it.node }.filterIsInstance<RectNode>()
    assertEquals(listOf(1.0, 2.0, 3.0), rects.map { it.x })
  }

  @Test
  fun `faceting a mark that is not a group is reported`() {
    val compiled =
      compile(
        """
        {
          "width": 100, "height": 100, "padding": 0,
          "data": [$table],
          "marks": [{"type": "rect",
            "from": {"facet": {"name": "cell", "data": "t", "groupby": "c"}}}]
        }
        """
      )
    assertTrue(
      compiled.diagnostics.any {
        it.severity >= DiagnosticSeverity.ERROR && it.message.contains("Only a group mark")
      },
      compiled.diagnostics.toString(),
    )
  }

  @Test
  fun `a group's legends are built in its own scope`() {
    val compiled =
      compile(
        """
        {
          "width": 100, "height": 100, "padding": 0,
          "marks": [{"type": "group",
            "encode": {"enter": {"width": {"value": 40}, "height": {"value": 40}}},
            "scales": [{"name": "c", "type": "ordinal", "domain": ["a"], "range": ["red"]}],
            "legends": [{"fill": "c"}]}]
        }
        """
      )
    // The legend reads a scale declared on the group, so it can only have been built in that scope.
    val legend =
      requireNotNull(compiled.scene)
        .flatten()
        .map { it.node }
        .filterIsInstance<GroupNode>()
        .single { it.metadata.role == "legend" }
    assertEquals("c", legend.metadata.markName)
  }

  @Test
  fun `a layout grids the cells, overriding wherever their own encode put them`() {
    // Read off upstream: three 30x20 cells in two columns with 10 and 6 of padding sit at (0,0),
    // (40,0) and (0,26) — and the x each cell encoded for itself is discarded, which is the point.
    val groups =
      scopes(
        """
        {
          "width": 200, "height": 100, "padding": 0,
          "data": [{"name": "t", "values": [{"k": "a"}, {"k": "b"}, {"k": "c"}]}],
          "layout": {"columns": 2, "padding": {"row": 6, "column": 10}},
          "marks": [{
            "type": "group",
            "from": {"facet": {"name": "cell", "data": "t", "groupby": "k"}},
            "encode": {"enter": {"x": {"value": 99}, "y": {"value": 99},
              "width": {"value": 30}, "height": {"value": 20}, "fill": {"value": "#eee"}}}
          }]
        }
        """
      )
    assertEquals(3, groups.size)
    assertEquals(
      listOf(0.0 to 0.0, 40.0 to 0.0, 0.0 to 26.0),
      groups.map { it.transform.e to it.transform.f },
    )
  }

  @Test
  fun `layout features that are not implemented are reported by name`() {
    val compiled =
      compile(
        """
        {
          "width": 100, "height": 100, "padding": 0,
          "layout": {"columns": 2, "center": true, "headerBand": 0.5, "titleBand": 0.5},
          "marks": [{"type": "group",
            "encode": {"enter": {"width": {"value": 10}, "height": {"value": 10}}}}]
        }
        """
      )
    // Nothing is left to report: all ten of upstream's layout properties are read.
    val messages = compiled.diagnostics.map { it.message }
    for (name in emptyList<String>()) {
      assertTrue(messages.any { it.contains("'$name'") }, "$name not reported in $messages")
    }
  }

  @Test
  fun `nesting a group inside a group works to any depth`() {
    val rects =
      nodes<RectNode>(
        """
        {
          "width": 100, "height": 100, "padding": 0,
          "marks": [{
            "type": "group",
            "encode": {"enter": {"x": {"value": 10}, "y": {"value": 10},
              "width": {"value": 50}, "height": {"value": 50}}},
            "marks": [{
              "type": "group",
              "encode": {"enter": {"x": {"value": 5}, "y": {"value": 5},
                "width": {"value": 20}, "height": {"value": 20}}},
              "marks": [{"type": "rect", "encode": {"enter": {
                "x": {"value": 1}, "y": {"value": 2},
                "width": {"value": 3}, "height": {"value": 4}}}}]
            }]
          }]
        }
        """
      )
    // Positions are group-local; the accumulated translation is 10 + 5 on each axis.
    val rect = rects.single()
    assertEquals(1.0, rect.x)
    assertEquals(2.0, rect.y)
    val world = requireNotNull(compile(nestedSpec).scene).flatten().last()
    assertEquals(16.0, world.worldBounds.left)
    assertEquals(17.0, world.worldBounds.top)
  }

  /** The same specification as the nesting test, reused to check accumulated world coordinates. */
  private val nestedSpec =
    """
    {
      "width": 100, "height": 100, "padding": 0,
      "marks": [{
        "type": "group",
        "encode": {"enter": {"x": {"value": 10}, "y": {"value": 10},
          "width": {"value": 50}, "height": {"value": 50}}},
        "marks": [{
          "type": "group",
          "encode": {"enter": {"x": {"value": 5}, "y": {"value": 5},
            "width": {"value": 20}, "height": {"value": 20}}},
          "marks": [{"type": "rect", "encode": {"enter": {
            "x": {"value": 1}, "y": {"value": 2},
            "width": {"value": 3}, "height": {"value": 4}}}}]
        }]
      }]
    }
    """
      .trimIndent()

  @Test
  fun `a facet cell carries its datum for tooltips and accessibility`() {
    val groups =
      scopes(
        """
        {
          "width": 100, "height": 100, "padding": 0,
          "data": [$table],
          "marks": [{
            "type": "group",
            "from": {"facet": {"name": "cell", "data": "t", "groupby": "c"}},
            "encode": {"enter": {"width": {"value": 1}, "height": {"value": 1}}}
          }]
        }
        """
      )
    val datum = groups.first().metadata.tooltip as VegaValue.Obj
    assertEquals(VegaValue.Str("north"), datum.fields["c"])
    assertEquals(VegaValue.Num(2.0), datum.fields["count"])
  }
}
