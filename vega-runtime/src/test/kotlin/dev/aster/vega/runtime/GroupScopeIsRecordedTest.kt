@file:OptIn(InternalAsterVegaApi::class)

package dev.aster.vega.runtime

import dev.aster.vega.model.InternalAsterVegaApi
import dev.aster.vega.model.VegaValue
import dev.aster.vega.runtime.compile.SpecCompiler
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a group mark's scope resolved to is kept, instead of being resolved and dropped.
 *
 * A group's signals have always been resolved — `ScopeCompiler.nest` does it and the cell is drawn
 * from them — and then discarded, because nothing above could name them. That is the first half of
 * why a signal handler declared inside a group never fires: there was no scope to evaluate it in,
 * and nowhere for its result to go. Dispatch is the second half and comes next; this is the value
 * being there at all.
 *
 * Nothing about the picture changes here, which the last test says.
 */
class GroupScopeIsRecordedTest {

  private fun compile(json: String) = SpecCompiler().compileJson(json)

  private fun fixture(name: String) = compile(File("../test-fixtures/specs/$name").readText())

  /**
   * Vega's own `overview-plus-detail`, which is the specification this exists for.
   *
   * Its `overview` group declares five signals with handlers — `brush`, `anchor`, `xdown`, `delta`
   * and a `detailDomain` that pushes outward — and an `xOverview` scale that one of them inverts
   * against. Every one of those was resolved and thrown away.
   */
  @Test
  fun `the overview group's own signals are recorded under its name`() {
    val compiled = fixture("overview-plus-detail.vg.json")
    val overview = compiled.groupScopes["overview"]
    assertNotNull(
      overview,
      "the overview group's scope was not recorded: ${compiled.groupScopes.keys}",
    )
    for (name in listOf("brush", "anchor", "xdown", "delta", "detailDomain")) {
      assertTrue(
        name in overview!!.values,
        "'$name' is declared in the overview group and is not in its scope: " +
          overview.values.keys.sorted(),
      )
    }
    // The declared initial values, so this is the group's own resolution rather than an empty map
    // with the right keys in it.
    assertEquals(VegaValue.Num(0.0), overview!!.values["brush"])
    assertEquals(VegaValue.Num(0.0), overview.values["xdown"])
    assertEquals(VegaValue.Null, overview.values["anchor"])
  }

  /**
   * And the group's **scales**, because a handler needs both in one expression.
   *
   * `detailDomain`'s update is `invert('xOverview', brush)` — a scale lookup and a signal read
   * together — and `xOverview` is declared inside the group. A scope holding only the signals would
   * evaluate half of it.
   */
  @Test
  fun `the group's own scales come with it`() {
    val overview = fixture("overview-plus-detail.vg.json").groupScopes["overview"]!!
    // Asked the way an expression asks, since the scales are private to the scope.
    val inverted = overview.let { scope ->
      dev.aster.vega.expression
        .VegaExpressionCompiler()
        .compile("invert('xOverview', 20)")
        .let { it as dev.aster.vega.expression.ExpressionResult.Compiled }
        .expression
        .evaluate(scope)
    }
    assertTrue(
      inverted != VegaValue.Null,
      "inverting the group's own xOverview scale gave null, so the scale did not come with the " +
        "scope and half of detailDomain's update could not be evaluated",
    )
  }

  /**
   * A group that declares nothing of its own records nothing, so this is not noise on every spec.
   */
  @Test
  fun `a group with no declarations of its own still records its scope, and a plain chart records none`() {
    val plain =
      compile(
        """
        {"width": 60, "height": 60, "padding": 0, "autosize": "none",
         "data": [{"name": "t", "values": [{"v": 1}]}],
         "marks": [{"type": "rect", "from": {"data": "t"},
                    "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                                         "width": {"value": 5}, "height": {"value": 5}}}}]}
        """
          .trimIndent()
      )
    assertEquals(
      emptyMap<String, Any>(),
      plain.groupScopes,
      "a chart with no group marks recorded a scope",
    )
  }

  /**
   * A faceted group records one scope **per cell**, because it genuinely resolves one per cell.
   *
   * The case a single map keyed by name would silently collapse — and collapsing it is what would
   * make a later brush in cell three write cell one's signal.
   */
  @Test
  fun `a faceted group records a scope for each cell`() {
    val compiled =
      compile(
        """
        {"width": 300, "height": 60, "padding": 0, "autosize": "none",
         "data": [{"name": "t",
                   "values": [{"c": "a", "v": 1}, {"c": "b", "v": 2}, {"c": "c", "v": 3}]}],
         "marks": [{
           "type": "group", "name": "cell",
           "from": {"facet": {"name": "rows", "data": "t", "groupby": "c"}},
           "signals": [{"name": "local", "update": "length(data('rows'))"}],
           "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                                "width": {"value": 80}, "height": {"value": 50}}},
           "marks": [{"type": "rect", "from": {"data": "rows"},
                      "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                                           "width": {"value": 5}, "height": {"value": 5}}}}]}]}
        """
          .trimIndent()
      )
    val cells = compiled.groupScopes.keys.filter { it.startsWith("cell") }
    assertEquals(
      listOf("cell/cells[0]", "cell/cells[1]", "cell/cells[2]"),
      cells,
      "three facet cells did not record three scopes: ${compiled.groupScopes.keys}",
    )
    // Each resolved its own `local` against its own rows, which is what says these are three
    // scopes and not one recorded three times.
    for (key in cells) {
      assertEquals(VegaValue.Num(1.0), compiled.groupScopes[key]!!.values["local"], key)
    }
  }

  /** An unnamed group is keyed by its index, since a specification has no other handle on it. */
  @Test
  fun `an unnamed group is recorded under its index`() {
    val compiled =
      compile(
        """
        {"width": 60, "height": 60, "padding": 0, "autosize": "none",
         "data": [{"name": "t", "values": [{"v": 1}]}],
         "marks": [{"type": "group",
                    "signals": [{"name": "inner", "value": 7}],
                    "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                                         "width": {"value": 50}, "height": {"value": 50}}},
                    "marks": []}]}
        """
          .trimIndent()
      )
    assertEquals(
      VegaValue.Num(7.0),
      compiled.groupScopes["[0]"]?.values?.get("inner"),
      "an unnamed group was not recorded under its index: ${compiled.groupScopes.keys}",
    )
  }

  /**
   * Recording changes nothing that is drawn.
   *
   * The guard on all of the above: this is a pure addition, and the mark comparison against
   * upstream is what would catch it if it were not. Asserted here too so that a regression points
   * at this change rather than at a fixture.
   */
  @Test
  fun `the scene and the diagnostics are unchanged`() {
    val compiled = fixture("overview-plus-detail.vg.json")
    assertNotNull(compiled.scene, "recording a group scope stopped the chart drawing")
    assertNull(
      compiled.diagnostics.firstOrNull { it.severity.name == "FATAL" }?.message,
      "recording a group scope made the compile fail",
    )
  }
}
