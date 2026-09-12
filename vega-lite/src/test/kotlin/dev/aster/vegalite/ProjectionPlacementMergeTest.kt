package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Two members share a projection when they agree on what it **is**, not on where it was put.
 *
 * ```js
 * export const PROJECTION_PROPERTIES = [
 *   'type', 'clipAngle', 'clipExtent', 'center', 'rotate', 'precision', 'reflectX', 'reflectY',
 *   'coefficient', 'distance', 'fraction', 'lobes', 'parallel', 'radius', 'ratio', 'spacing',
 *   'tilt',
 * ];
 * ```
 *
 * `mergeIfNoConflict` walks that list and no other, so `scale` and `translate` — where the map was
 * placed on the page — say nothing about whether two members are drawing the same projection. Two
 * layers of one map that state the same kind and place it differently are one projection, and the
 * first of them settles the placing.
 *
 * Compared over the whole specification instead, such layers were two projections: each was written
 * out, each mark read its own, and the outlines drawn over a map were placed by a projection the
 * map underneath knew nothing about — two readings of one country at two sizes, one on top of the
 * other.
 *
 * Two specifications in the wild corpus layer a map that way.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class ProjectionPlacementMergeTest {

  /** Every projection with the kind and placing it settled on, and what each mark reads. */
  private fun projections(spec: String): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    val declared =
      (compiled.fields["projections"] as? VegaValue.Arr)?.values.orEmpty().joinToString(",") {
        it as VegaValue.Obj
        "${it.string("name")}(${it.string("type")}," +
          "${it.fields["scale"]?.let { s -> VegaJson.write(s) }}," +
          "${it.fields["translate"]?.let { t -> VegaJson.write(t).replace(Regex("""\s+"""), "") }})"
      }
    fun walk(marks: List<VegaValue>): List<String> = marks.flatMap {
      it as VegaValue.Obj
      (it.fields["transform"] as? VegaValue.Arr)
        ?.values
        .orEmpty()
        .mapNotNull { step -> (step as VegaValue.Obj).string("projection") }
        .map { name -> "${it.string("name")}->$name" } +
        walk((it.fields["marks"] as? VegaValue.Arr)?.values.orEmpty())
    }
    return "[$declared] " +
      walk((compiled.fields["marks"] as VegaValue.Arr).values).joinToString(",")
  }

  private fun outline(projection: String?) =
    """{${if (projection == null) "" else "\"projection\":$projection,"}
       "data":{"url":"map.json","format":{"type":"topojson","feature":"a"}},
       "mark":"geoshape"}"""

  /** The reported shape: one kind of map placed twice, which is one projection. */
  @Test
  fun `layers that place one kind differently share it`() {
    assertEquals(
      "[projection(naturalEarth1,480,[-630,220])] " +
        "layer_0_marks->projection,layer_1_marks->projection",
      projections(
        """{"layer":[${outline("""{"type":"naturalEarth1","scale":480,"translate":[-630,220]}""")},
             ${outline("""{"type":"naturalEarth1","scale":300,"translate":[275,175]}""")}]}"""
      ),
    )
  }

  /** Two **kinds** of map are two projections, which is what the comparison is for. */
  @Test
  fun `layers of different kinds keep their own`() {
    assertEquals(
      """[layer_0_projection(naturalEarth1,480,{"signal":"[width/2,height/2]"}),""" +
        """layer_1_projection(mercator,300,{"signal":"[width/2,height/2]"})] """ +
        "layer_0_marks->layer_0_projection,layer_1_marks->layer_1_projection",
      projections(
        """{"layer":[${outline("""{"type":"naturalEarth1","scale":480}""")},
             ${outline("""{"type":"mercator","scale":300}""")}]}"""
      ),
    )
  }

  /**
   * A member that says **nothing** is fitted to the plotting area, and one that states a `scale` is
   * not: their sizes differ, which `mergeIfNoConflict` asks before anything else.
   */
  @Test
  fun `a fitted member does not merge with a placed one`() {
    assertEquals(
      """[layer_0_projection(mercator,480,{"signal":"[width/2,height/2]"}),""" +
        "layer_1_projection(equalEarth,null,null)] " +
        "layer_0_marks->layer_0_projection,layer_1_marks->layer_1_projection",
      projections(
        """{"layer":[${outline("""{"type":"mercator","scale":480}""")},${outline(null)}]}"""
      ),
    )
  }

  /** The same rule a level up: two **plots** of one kind share one projection. */
  @Test
  fun `plots that place one kind differently share it`() {
    assertEquals(
      "[projection(naturalEarth1,480,[-630,220])] " +
        "concat_0_marks->projection,concat_1_marks->projection",
      projections(
        """{"hconcat":[
             ${outline("""{"type":"naturalEarth1","scale":480,"translate":[-630,220]}""")},
             ${outline("""{"type":"naturalEarth1","scale":300,"translate":[275,175]}""")}]}"""
      ),
    )
  }
}
