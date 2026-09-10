package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The **encoding** says whether a plot is projected, and one projected member makes it the layer's.
 *
 * ```js
 * function parseUnitProjection(model: UnitModel): ProjectionComponent {
 *   if (model.hasProjection) { … }
 *   return undefined;
 * }
 * ```
 *
 * `hasProjection` is a `geoshape` mark or a geographic position channel — nothing else. A
 * projection stated at the top of a chart does not make a plot drawing in `x` and `y` projected,
 * and treating it as though it did put that plot's table into the `fit`: a map layered under a
 * scatter of ordinary positions was scaled to cover both, so the map came out the wrong size.
 *
 * And a member with **no** projection does not stop the merge — `every` returns true for it — so
 * one map under such a scatter still has a *layer's* projection, named for the layer. Requiring two
 * projected members named it for the member instead, and the mark that reads it named the member's
 * too.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class ProjectionScopeTest {

  private fun projections(spec: String): List<Pair<String?, String?>> {
    val compiled =
      VegaJson.parse(
        requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "did not compile" }
      ) as VegaValue.Obj
    return (compiled.fields["projections"] as? VegaValue.Arr)
      ?.values
      ?.mapNotNull { it as? VegaValue.Obj }
      ?.map { p ->
        (p.fields["name"] as? VegaValue.Str)?.value to
          ((p.fields["fit"] as? VegaValue.Obj)?.fields?.get("signal") as? VegaValue.Str)?.value
      }
      .orEmpty()
  }

  private val cartesianPoint =
    """{"mark":"point","encoding":{"x":{"field":"a","type":"quantitative"},
       "y":{"field":"b","type":"quantitative"}}}"""

  /** A stated projection over ordinary positions projects nothing at all. */
  @Test
  fun `a stated projection over cartesian positions makes no projection`() {
    assertEquals(
      emptyList<Pair<String?, String?>>(),
      projections(
        """
        {"data":{"values":[{"a":1,"b":2}]},"projection":{"type":"albersUsa"},"mark":"point",
         "encoding":{"x":{"field":"a","type":"quantitative"},
                     "y":{"field":"b","type":"quantitative"}}}
        """
      ),
    )
  }

  /** A geoshape on its own is fitted to its own table, and named for the chart. */
  @Test
  fun `a geoshape is fitted to its own table`() {
    assertEquals(
      listOf("projection" to "data('source_0')"),
      projections(
        """{"data":{"values":[{"a":1,"b":2}]},"projection":{"type":"albersUsa"},"mark":"geoshape"}"""
      ),
    )
  }

  /** The reported shape: a map under a scatter that is not projected. */
  @Test
  fun `a map under a cartesian layer keeps the layer's projection and its own fit`() {
    assertEquals(
      listOf("projection" to "data('source_0')"),
      projections(
        """
        {"data":{"values":[{"a":1,"b":2}]},"projection":{"type":"albersUsa"},
         "layer":[{"mark":"geoshape"},$cartesianPoint]}
        """
      ),
      "the scatter's table is not something to scale a map to",
    )
  }

  /** Two projected members are fitted to both, which is what the merge is for. */
  @Test
  fun `two projected members are fitted to both`() {
    assertEquals(
      listOf("projection" to "[data('source_0'), layer_1_geojson_0]"),
      projections(
        """
        {"data":{"values":[{"a":1,"b":2}]},"projection":{"type":"albersUsa"},
         "layer":[{"mark":"geoshape"},
                  {"mark":"point","encoding":{
                     "longitude":{"field":"a","type":"quantitative"},
                     "latitude":{"field":"b","type":"quantitative"}}}]}
        """
      ),
    )
  }
}
