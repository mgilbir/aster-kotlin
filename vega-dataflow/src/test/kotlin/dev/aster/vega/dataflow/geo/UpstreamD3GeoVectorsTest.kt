package dev.aster.vega.dataflow.geo

import dev.aster.vega.model.VegaValue
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
          when (fn) {
            in NOT_VEGA_S -> "$fn (Vega does not call it)"
            // The recorder captures a builder's *constructor* arguments, not the chain of `.step()`
            // and `.extent()` calls that configure it, so two vectors that differ only by
            // configuration are indistinguishable here. Replaying them would mean guessing.
            in BUILDERS -> "$fn (no circle generator here; Vega does not call one)"
            else -> fn
          },
          1,
          Int::plus,
        )
        continue
      }
      // A graticule is *configured before it is asked*, and the configuration is the recorded
      // `chain` — `[["extent", [[[-90,-45],[90,45]]]], ["step", [[45,45]]]]`. Replaying it means
      // replaying those calls in order and only then asking the question in `method`.
      if (fn == "geoGraticule()") {
        val graticule = Graticule()
        var understood = true
        for (step in (vector["chain"] as? JsonArray).orEmpty()) {
          val parts = step as? JsonArray ?: continue
          val call = (parts.firstOrNull() as? JsonPrimitive)?.content
          val given = (parts.getOrNull(1) as? JsonArray)?.getOrNull(0)
          val box = corners(given)
          val pair = (given as? JsonArray)?.mapNotNull { number(it) }?.takeIf { it.size == 2 }
          when (call) {
            "extent" ->
              box?.let {
                graticule.extentMajor(it[0], it[1], it[2], it[3])
                graticule.extentMinor(it[0], it[1], it[2], it[3])
              } ?: run { understood = false }
            "extentMajor" ->
              box?.let { graticule.extentMajor(it[0], it[1], it[2], it[3]) }
                ?: run { understood = false }
            "extentMinor" ->
              box?.let { graticule.extentMinor(it[0], it[1], it[2], it[3]) }
                ?: run { understood = false }
            "step" ->
              pair?.let {
                graticule.stepMajor(it[0], it[1])
                graticule.stepMinor(it[0], it[1])
              } ?: run { understood = false }
            "stepMajor" ->
              pair?.let { graticule.stepMajor(it[0], it[1]) } ?: run { understood = false }
            "stepMinor" ->
              pair?.let { graticule.stepMinor(it[0], it[1]) } ?: run { understood = false }
            "precision" ->
              number(given)?.let { graticule.precision(it) } ?: run { understood = false }
            else -> understood = false
          }
        }
        if (!understood) {
          unmapped.merge("$fn (a configuring call this adapter does not know)", 1, Int::plus)
          continue
        }
        val asked = vector["method"]?.jsonPrimitive?.content
        val ours =
          when (asked) {
            null -> graticule.multiLineString()
            "lines" -> graticule.lineStrings()
            "outline" -> graticule.outline()
            "precision" -> VegaValue.Num(graticule.precisionValue())
            "extentMajor" -> box(graticule.extentMajorValue())
            "extentMinor" -> box(graticule.extentMinorValue())
            "stepMajor" -> pair(graticule.stepMajorValue())
            "stepMinor" -> pair(graticule.stepMinorValue())
            else -> null
          }
        if (ours == null) {
          unmapped.merge("$fn.$asked", 1, Int::plus)
          continue
        }
        replayed++
        val expected = vector["result"]?.toVegaValue() ?: VegaValue.Null
        if (!alike(expected, ours))
          failures.add("$fn.${asked ?: "(call)"}: upstream $expected, ours $ours")
        continue
      }

      // A rotation compares numbers rather than measuring a geometry, so it is answered first.
      if (fn == "geoRotation()") {
        val angles =
          (vector["constructedWith"] as? JsonArray)
            ?.getOrNull(0)
            ?.let { it as? JsonArray }
            ?.mapNotNull {
              number(it)
            }
        val point =
          ((vector["args"] as? JsonArray)?.getOrNull(0) as? JsonArray)?.mapNotNull { number(it) }
        val expected = (vector["result"] as? JsonArray)?.mapNotNull { number(it) }
        if (angles == null || angles.size < 2 || point?.size != 2 || expected?.size != 2) {
          unmapped.merge("$fn (not a rotation of a point)", 1, Int::plus)
          continue
        }
        val rotation =
          Rotation(
            angles[0] * GeoMath.RADIANS,
            angles[1] * GeoMath.RADIANS,
            (angles.getOrNull(2) ?: 0.0) * GeoMath.RADIANS,
          )
        // `rotation.invert(p)` is recorded under the same `fn` as `rotation(p)`, distinguished
        // only by the `method` field. Ignoring it compares an inverse against a forward, which
        // looks exactly like an engine that has its rotation backwards.
        val method = vector["method"]?.jsonPrimitive?.content
        if (method != null && method != "invert") {
          unmapped.merge("geoRotation().$method", 1, Int::plus)
          continue
        }
        val turned =
          if (method == "invert")
            rotation.invert(point[0] * GeoMath.RADIANS, point[1] * GeoMath.RADIANS)
          else rotation.forward(point[0] * GeoMath.RADIANS, point[1] * GeoMath.RADIANS)
        val ours = listOf(turned[0] * GeoMath.DEGREES, turned[1] * GeoMath.DEGREES)
        replayed++
        if (expected.indices.any { !near(ours[it], expected[it]) })
          failures.add("$fn$angles($point): upstream $expected, ours $ours")
        continue
      }

      val argument = (vector["args"] as? JsonArray)?.getOrNull(0)

      // `geoStream` is a corpus of *shapes rather than answers*: an unknown geometry type, a null
      // geometry, empty coordinate arrays, and points carrying an elevation. Upstream walks all of
      // them and reports nothing, and the sink it was given is recorded as an empty object — so
      // what is replayable is the part that matters, that none of them is an error.
      if (fn == "geoStream") {
        val walked = runCatching {
          val counting = CountingSink()
          GeoJsonStream.stream(
            argument?.toVegaValue() ?: dev.aster.vega.model.VegaValue.Null,
            counting,
          )
        }
          .exceptionOrNull()
        replayed++
        if (walked != null) failures.add("$fn on $argument threw ${walked::class.simpleName}")
        continue
      }

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
    assertTrue(replayed >= 155, "only $replayed vectors replayed; the harness must not shrink")
  }

  /** `[[x0, y0], [x1, y1]]` as a flat box, which is how this engine passes an extent. */
  private fun corners(element: kotlinx.serialization.json.JsonElement?): DoubleArray? {
    val outer = element as? JsonArray ?: return null
    val low = (outer.getOrNull(0) as? JsonArray)?.mapNotNull { number(it) } ?: return null
    val high = (outer.getOrNull(1) as? JsonArray)?.mapNotNull { number(it) } ?: return null
    if (low.size != 2 || high.size != 2) return null
    return doubleArrayOf(low[0], low[1], high[0], high[1])
  }

  private fun box(values: DoubleArray): VegaValue =
    VegaValue.Arr(
      listOf(
        VegaValue.Arr(listOf(VegaValue.Num(values[0]), VegaValue.Num(values[1]))),
        VegaValue.Arr(listOf(VegaValue.Num(values[2]), VegaValue.Num(values[3]))),
      )
    )

  private fun pair(values: DoubleArray): VegaValue =
    VegaValue.Arr(listOf(VegaValue.Num(values[0]), VegaValue.Num(values[1])))

  /**
   * Two values equal to within the same tolerance the scalars use, structure included.
   *
   * A graticule answer is thousands of coordinates deep, so comparing the rendered text would fail
   * on the last digit of one point in a grid that is otherwise identical.
   */
  private fun alike(expected: VegaValue, ours: VegaValue): Boolean =
    when {
      expected is VegaValue.Num && ours is VegaValue.Num -> near(ours.value, expected.value)
      expected is VegaValue.Str && ours is VegaValue.Str -> expected.value == ours.value
      expected is VegaValue.Arr && ours is VegaValue.Arr ->
        expected.values.size == ours.values.size &&
          expected.values.indices.all { alike(expected.values[it], ours.values[it]) }
      expected is VegaValue.Obj && ours is VegaValue.Obj ->
        expected.fields.keys == ours.fields.keys &&
          expected.fields.all { (key, value) -> alike(value, ours.fields.getValue(key)) }
      else -> expected == ours
    }

  private companion object {
    val REPLAYED =
      setOf(
        "geoArea",
        "geoBounds",
        "geoCentroid",
        "geoGraticule()",
        "geoLength",
        "geoRotation()",
        "geoStream",
      )

    /** Counts what a sink is asked to draw, so a walk can be checked for having happened. */
    private class CountingSink : GeoStream() {
      var points = 0

      override fun point(x: Double, y: Double) {
        points++
      }
    }

    /** Recorded because they are in the package, but nothing in Vega reaches them. */
    val NOT_VEGA_S = setOf("geoContains", "geoDistance", "geoInterpolate", "geoInterpolate()")

    val BUILDERS = setOf("geoCircle()", "geoCircle")
  }
}
