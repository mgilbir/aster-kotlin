package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Lifting a facet out of a view takes the facet **channels** and nothing else.
 *
 * ```ts
 * const {mark, width, projection, height, view, params, encoding: _, ...outerSpec} = spec;
 * return this.mapFacet({...outerSpec, ...layout, facet: facetMapping, spec: {…, mark, encoding}});
 * ```
 *
 * `mapFacetedUnit` moves the unit down into the cell as the unit it was. This compiler rebuilt the
 * cell's specification property by property and left two of them behind:
 * - its **parameters**, so a selection declared inside a grid belonged to no view.
 *   `interactiveFlag` asks each unit whether the selection is its own, and a unit that owns none is
 *   written `interactive: false` so a click there falls through to the one that does. Owned by
 *   nobody, every mark in the cell claimed it and swallowed the click.
 * - its **projection**, so a map drawn in a cell was put on the page by whatever the chart above it
 *   said rather than by what it said itself.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class LiftedCellKeepsItsOwnTest {

  private fun compiled(spec: String) =
    VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
      as VegaValue.Obj

  /** Every drawing mark, marked `!on` or `!off` by the flag, or bare where none was written. */
  private fun marks(spec: String): String {
    fun walk(marks: List<VegaValue>): List<String> = marks.flatMap {
      it as VegaValue.Obj
      val own =
        when ((it.fields["interactive"] as? VegaValue.Bool)?.value) {
          true -> "!on"
          false -> "!off"
          null -> ""
        }
      (if (it.string("type") == "group") emptyList() else listOf("${it.string("name")}$own")) +
        walk((it.fields["marks"] as? VegaValue.Arr)?.values.orEmpty())
    }
    return walk((compiled(spec).fields["marks"] as VegaValue.Arr).values).joinToString(" ")
  }

  /** The kind of every projection, or `-` where none was named. */
  private fun projections(spec: String): String =
    (compiled(spec).fields["projections"] as? VegaValue.Arr)?.values.orEmpty().joinToString(" ") {
      (it as VegaValue.Obj).string("type") ?: "-"
    }

  private val x = """{"field":"a","type":"quantitative"}"""
  private val pick = """"params":[{"name":"pick","select":"point"}]"""
  private val cell =
    """{"layer":[{"mark":"point","encoding":{"x":$x},$pick},{"mark":"line","encoding":{"x":$x}}]}"""
  private val row = """{"row":{"field":"r","type":"nominal"}}"""

  /** The reported shape: the cell's second member declares nothing, so it is not reachable. */
  @Test
  fun `a cell's parameters still belong to the view that declared them`() {
    assertEquals(
      "child_layer_0_marks!on child_layer_1_marks!off",
      marks("""{"facet":$row,"spec":$cell}"""),
    )
  }

  /** Which is what the same layer does with no grid around it, and always did. */
  @Test
  fun `a layer outside a grid is unchanged`() {
    assertEquals("layer_0_marks!on layer_1_marks!off", marks(cell))
  }

  /** With no selection anywhere the flag is left off entirely rather than written false. */
  @Test
  fun `a cell with no selection is written no flag at all`() {
    assertEquals(
      "child_layer_0_marks child_layer_1_marks",
      marks(
        """{"facet":$row,"spec":{"layer":[{"mark":"point","encoding":{"x":$x}},
             {"mark":"line","encoding":{"x":$x}}]}}"""
      ),
    )
  }

  /** A grid of grids lifts twice, and the second lift has to keep what the first one kept. */
  @Test
  fun `a cell inside a cell keeps them too`() {
    assertEquals(
      "child_child_layer_0_marks!on child_child_layer_1_marks!off",
      marks(
        """{"facet":$row,"spec":{"facet":{"column":{"field":"c","type":"nominal"}},
               "spec":$cell}}"""
      ),
    )
  }

  /** A facet written as a **channel** is lifted by the same path, from a view that is the chart. */
  @Test
  fun `a facet channel lifts the view's own parameters`() {
    assertEquals(
      "child_marks!on",
      marks("""{"mark":"point","encoding":{"x":$x,"row":{"field":"r","type":"nominal"}},$pick}"""),
    )
  }

  private val outline =
    """"data":{"url":"map.json","format":{"type":"topojson","feature":"a"}},"mark":"geoshape""""

  /** The cell's own projection is the one its places are put on the page by. */
  @Test
  fun `a cell keeps the projection it named`() {
    assertEquals(
      "mercator",
      projections("""{"facet":$row,"spec":{$outline,"projection":{"type":"mercator"}}}"""),
    )
  }

  /** And a member of the cell's layer keeps it, the layer merging what its members agreed on. */
  @Test
  fun `a member of a cell's layer keeps the projection it named`() {
    assertEquals(
      "mercator",
      projections(
        """{"facet":$row,"spec":{"layer":[{$outline,"projection":{"type":"mercator"}},
             {$outline,"projection":{"type":"mercator"}}]}}"""
      ),
    )
  }

  /**
   * A cell that names none is fitted with no kind invented for it, which is what already worked.
   */
  @Test
  fun `a cell that names no projection is given none`() {
    assertEquals("-", projections("""{"facet":$row,"spec":{$outline}}"""))
  }
}
