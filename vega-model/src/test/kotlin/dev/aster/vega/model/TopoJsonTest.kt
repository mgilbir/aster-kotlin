package dev.aster.vega.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The TopoJSON decoder, against vectors taken from `topojson-client` itself.
 *
 * The topology below is small and deliberately awkward: it is quantized, so every coordinate is a
 * running sum rather than a value; two of its polygons **share** an arc, and the second walks it
 * backwards, which is the whole point of the format and the thing a naive decoder gets wrong; one
 * arc has only two points, so a ring built from it has to be padded; and there are bare points and
 * a line beside the polygons, which contribute nothing to a mesh. Every expected string here came
 * out of `topojson-client` running on exactly this input.
 */
class TopoJsonTest {

  private val topology =
    VegaJson.parse(
      """
      {
        "type": "Topology",
        "transform": {"scale": [0.5, 0.25], "translate": [10, 20]},
        "objects": {
          "shapes": {
            "type": "GeometryCollection",
            "geometries": [
              {"type": "Polygon", "id": "a", "properties": {"n": 1}, "arcs": [[0, 1]]},
              {"type": "Polygon", "id": "b", "arcs": [[2, -2]]},
              {"type": "LineString", "arcs": [3]},
              {"type": "Point", "coordinates": [4, 6]},
              {"type": "MultiPoint", "coordinates": [[0, 0], [2, 2]]}
            ]
          }
        },
        "arcs": [
          [[0, 0], [4, 0], [0, 4]],
          [[4, 4], [-4, 0], [0, -4]],
          [[0, 0], [0, 4], [4, 0]],
          [[8, 8], [2, 0]]
        ]
      }
      """
    )

  /**
   * Key-sorted so a comparison is about the values and not about the order a decoder happened to
   * write them in. Whole numbers lose their decimal point, which is the only thing separating this
   * from Kotlin's own rendering and the only thing these vectors need.
   */
  private fun asJson(value: VegaValue): String =
    when (value) {
      is VegaValue.Null -> "null"
      // `eval-probe.js` writes the literal `undefined` where `JSON.stringify` gives nothing.
      is VegaValue.Undefined -> "undefined"
      is VegaValue.Bool -> value.value.toString()
      is VegaValue.Num -> number(value.value)
      is VegaValue.Timestamp -> number(value.epochMillis)
      is VegaValue.Str -> "\"${value.value}\""
      is VegaValue.Arr -> value.values.joinToString(",", "[", "]") { asJson(it) }
      is VegaValue.Obj ->
        value.fields.entries
          .sortedBy { it.key }
          .joinToString(",", "{", "}") { "\"${it.key}\":${asJson(it.value)}" }
      // `JSON.stringify(/a/)` is `{}` — a RegExp has no enumerable properties, and none of these
      // vectors can produce one anyway.
      is VegaValue.Pattern -> "{}"
    }

  private fun number(value: Double): String =
    when {
      !value.isFinite() -> "null"
      value == kotlin.math.floor(value) -> value.toLong().toString()
      else -> value.toString()
    }

  private fun render(rows: List<VegaValue>?): String =
    rows.orEmpty().joinToString(",", "[", "]") { asJson(it) }

  @Test
  fun `a geometry collection becomes one feature per geometry`() {
    assertEquals(
      """[{"geometry":{"coordinates":[[[10,20],[12,20],[12,21],[10,21],[10,20]]],""" +
        """"type":"Polygon"},"id":"a","properties":{"n":1},"type":"Feature"},""" +
        """{"geometry":{"coordinates":[[[10,20],[10,21],[10,20],[10,21],[12,21]]],""" +
        """"type":"Polygon"},"id":"b","properties":{},"type":"Feature"},""" +
        """{"geometry":{"coordinates":[[14,22],[15,22]],"type":"LineString"},""" +
        """"properties":{},"type":"Feature"},""" +
        """{"geometry":{"coordinates":[12,21.5],"type":"Point"},""" +
        """"properties":{},"type":"Feature"},""" +
        """{"geometry":{"coordinates":[[10,20],[11,20.5]],"type":"MultiPoint"},""" +
        """"properties":{},"type":"Feature"}]""",
      render(TopoJson.feature(topology, "shapes")),
    )
  }

  @Test
  fun `a mesh draws each arc once, stitched into runs`() {
    assertEquals(
      """[{"coordinates":[[[10,20],[12,20],[12,21],[10,21],[10,20],[10,21],[12,21]],""" +
        """[[14,22],[15,22]]],"type":"MultiLineString"}]""",
      render(TopoJson.mesh(topology, "shapes", TopoJson.MeshFilter.ALL)),
    )
  }

  @Test
  fun `the interior filter keeps only the arc two shapes share`() {
    assertEquals(
      """[{"coordinates":[[[12,21],[10,21],[10,20]]],"type":"MultiLineString"}]""",
      render(TopoJson.mesh(topology, "shapes", TopoJson.MeshFilter.INTERIOR)),
    )
  }

  @Test
  fun `the exterior filter keeps everything the shapes do not share`() {
    assertEquals(
      """[{"coordinates":[[[10,20],[10,21],[12,21]],[[14,22],[15,22]],""" +
        """[[10,20],[12,20],[12,21]]],"type":"MultiLineString"}]""",
      render(TopoJson.mesh(topology, "shapes", TopoJson.MeshFilter.EXTERIOR)),
    )
  }

  @Test
  fun `an object the file does not contain is reported as absent`() {
    assertEquals(null, TopoJson.feature(topology, "nowhere"))
    assertEquals(null, TopoJson.mesh(topology, "nowhere", TopoJson.MeshFilter.ALL))
  }
}
