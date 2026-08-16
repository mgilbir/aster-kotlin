package dev.aster.vega.dataflow.geo

import dev.aster.vega.model.toVegaValue
import java.io.File
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * **d3-geo's** spherical measures, replayed against [SphericalMeasure].
 *
 * These are what `geoArea(null, feature)` and `geoCentroid(null, feature)` mean in an expression.
 * Upstream's `geoMethod` branches on the projection, and with none it calls d3's *spherical*
 * function rather than a path generator — a distinction this engine did not make, so it measured
 * longitude and latitude as if they were page coordinates. A one-degree box is `1` square degree
 * that way and `0.000304` steradians the right way.
 *
 * d3's corpus is mostly the cases where a sphere stops behaving like a plane: a polygon enclosing a
 * pole, a ring wound the other way, a feature crossing the antimeridian, and antipodal points whose
 * centroid is every direction at once.
 *
 * The `geoContains`, `geoDistance` and `geoInterpolate` vectors are counted rather than replayed:
 * Vega calls none of them, so there is nothing in this engine for them to be a test of.
 */
class UpstreamD3GeoVectorsTest {

  private val json = Json { ignoreUnknownKeys = true }

  private val vectors: List<JsonObject> by lazy {
    val file =
      File(
        File(System.getProperty("user.dir")).parentFile,
        "test-fixtures/upstream-vectors/d3-geo.json",
      )
    assumeTrue(
      file.isFile,
      "no upstream vectors at ${file.path} — run scripts/record-upstream-vectors.sh to replay them",
    )
    json.parseToJsonElement(file.readText()).jsonObject["calls"]!!.jsonArray.map { it.jsonObject }
  }

  /** Upstream's number, including the ones a vector records as a marker. */
  private fun number(element: kotlinx.serialization.json.JsonElement?): Double? =
    when (element) {
      is JsonPrimitive -> element.doubleOrNull
      is JsonObject ->
        when (element["\$"]?.jsonPrimitive?.content) {
          "NaN" -> Double.NaN
          "Infinity" -> Double.POSITIVE_INFINITY
          "-Infinity" -> Double.NEGATIVE_INFINITY
          else -> null
        }
      else -> null
    }

  /**
   * Angles compare to a tolerance, and the tolerance is the point: these are long sums of
   * trigonometric terms, so agreeing to the last bit would be luck rather than correctness.
   * Agreeing to a nanodegree is the real claim — a millimetre on the ground.
   */
  private fun near(a: Double, b: Double): Boolean =
    (a.isNaN() && b.isNaN()) || abs(a - b) <= 1e-9 * kotlin.math.max(1.0, abs(b))

  @Test
  fun `d3-geo's spherical measures replay against this engine`() {
    var replayed = 0
    val unmapped = mutableMapOf<String, Int>()
    val failures = mutableListOf<String>()

    for (vector in vectors) {
      val fn = vector["fn"]?.jsonPrimitive?.content ?: continue
      if (fn !in REPLAYED) {
        unmapped.merge(
          if (fn in NOT_VEGA_S) "$fn (Vega does not call it)" else fn,
          1,
          Int::plus,
        )
        continue
      }
      val argument = (vector["args"] as? JsonArray)?.getOrNull(0)
      if (argument !is JsonObject) {
        unmapped.merge("$fn (the argument is not a geometry)", 1, Int::plus)
        continue
      }
      val geojson = argument.toVegaValue()

      when (fn) {
        "geoArea",
        "geoLength" -> {
          val expected = number(vector["result"]) ?: continue
          val actual =
            if (fn == "geoArea") SphericalMeasure.area(geojson)
            else SphericalMeasure.length(geojson)
          replayed++
          if (!near(actual, expected)) failures.add("$fn: upstream $expected, ours $actual")
        }
        "geoBounds" -> {
          val corners = vector["result"] as? JsonArray ?: continue
          val expected = corners.flatMap {
            (it as? JsonArray)?.mapNotNull { v -> number(v) } ?: emptyList()
          }
          if (expected.size != 4) {
            unmapped.merge("$fn (the answer is not two corners)", 1, Int::plus)
            continue
          }
          // Recorded as `[[west, south], [east, north]]`; flattened in that order.
          val actual = SphericalMeasure.bounds(geojson)
          replayed++
          val ours = listOf(actual[0], actual[1], actual[2], actual[3])
          if (expected.indices.any { !near(ours[it], expected[it]) }) {
            failures.add("$fn: upstream $expected, ours $ours")
          }
        }
        else -> {
          val pair = vector["result"] as? JsonArray ?: continue
          val expected = pair.mapNotNull { number(it) }
          if (expected.size != 2) {
            unmapped.merge("$fn (the answer is not a pair)", 1, Int::plus)
            continue
          }
          val actual = SphericalMeasure.centroid(geojson)
          replayed++
          if (!near(actual[0], expected[0]) || !near(actual[1], expected[1])) {
            failures.add(
              "$fn: upstream ${expected[0]}, ${expected[1]}; ours ${actual[0]}, ${actual[1]}"
            )
          }
        }
      }
    }

    val ledger = StringBuilder("replayed $replayed of ${vectors.size} d3-geo vectors\n")
    unmapped.entries
      .sortedByDescending { it.value }
      .forEach { ledger.append("  ${it.key}: ${it.value}\n") }
    failures.forEach { ledger.append("MISMATCH $it\n") }
    File(File(System.getProperty("user.dir")).parentFile, "build/upstream-d3-geo-ledger.txt")
      .apply {
        parentFile.mkdirs()
        writeText(ledger.toString())
      }

    assertEquals(emptyList<String>(), failures.take(12), "d3-geo disagrees with this engine")
    assertTrue(replayed >= 110, "only $replayed vectors replayed; the harness must not shrink")
  }

  private companion object {
    val REPLAYED = setOf("geoArea", "geoBounds", "geoCentroid", "geoLength")

    /** Recorded because they are in the package, but nothing in Vega reaches them. */
    val NOT_VEGA_S = setOf("geoContains", "geoDistance", "geoInterpolate", "geoInterpolate()")
  }
}
