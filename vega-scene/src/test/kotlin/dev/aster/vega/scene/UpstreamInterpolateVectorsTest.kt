package dev.aster.vega.scene

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
 * **d3-interpolate's** colour interpolators, replayed against [ColorSpaces].
 *
 * Every continuous colour scale in a chart is one of these ramps, and which space it interpolates
 * *through* is visible in the middle of the ramp rather than at its ends: `rgb` from steelblue to
 * brown passes through a muddy grey, `hcl` keeps the chroma up, `cubehelix` spirals. A comparison
 * of endpoints would pass on all of them, so these vectors sample the middle — t = 0, 0.25, 0.5,
 * 0.75, 1 — which is where a wrong space shows.
 *
 * These vectors only exist because the recorder learned to wrap a **returned function**: d3's
 * interpolators are built and then called, so recording only `interpolateRgb(a, b)` recorded `{$:
 * 'function'}` and nothing else. Wrapping what comes back took this package from 253 vectors to
 * 533, and the whole corpus from 4,898 to 15,220.
 */
class UpstreamInterpolateVectorsTest {

  private val json = Json { ignoreUnknownKeys = true }

  private val vectors: List<JsonObject> by lazy {
    val file =
      File(
        File(System.getProperty("user.dir")).parentFile,
        "test-fixtures/upstream-vectors/d3-interpolate.json",
      )
    assumeTrue(
      file.isFile,
      "no upstream vectors at ${file.path} — run scripts/record-upstream-vectors.sh to replay them",
    )
    json.parseToJsonElement(file.readText()).jsonObject["calls"]!!.jsonArray.map { it.jsonObject }
  }

  private fun colour(element: kotlinx.serialization.json.JsonElement?): SceneColor? {
    val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    return SceneColor.parse(text)
  }

  @Test
  fun `d3-interpolate's colour ramps replay against ColorSpaces`() {
    var replayed = 0
    val unmapped = mutableMapOf<String, Int>()
    val failures = mutableListOf<String>()

    for (vector in vectors) {
      val fn = vector["fn"]?.jsonPrimitive?.content ?: continue
      val built = vector["constructedWith"] as? JsonArray
      if (built == null) {
        unmapped.merge("$fn (the construction, not a sample of the ramp)", 1, Int::plus)
        continue
      }
      val interpolate: ((SceneColor, SceneColor, Double) -> SceneColor)? =
        when (fn) {
          "interpolateRgb()" -> { a, b, t ->
            ColorSpaces.interpolateRgb(a, b, t)
          }
          "interpolateLab()" -> { a, b, t ->
            ColorSpaces.interpolateLab(a, b, t)
          }
          "interpolateHcl()" -> { a, b, t ->
            ColorSpaces.interpolateHcl(a, b, t)
          }
          "interpolateHsl()" -> { a, b, t ->
            ColorSpaces.interpolateHsl(a, b, t)
          }
          "interpolateCubehelix()" -> { a, b, t ->
            ColorSpaces.interpolateCubehelix(a, b, t)
          }
          // The "long" variants take the *long way round* the hue circle instead of the short one,
          // which is a different ramp through the same ends — d3 spells it as a separate function
          // and this engine as a flag.
          "interpolateHslLong()" -> { a, b, t ->
            ColorSpaces.interpolateHsl(a, b, t, long = true)
          }
          "interpolateHclLong()" -> { a, b, t ->
            ColorSpaces.interpolateHcl(a, b, t, long = true)
          }
          "interpolateCubehelixLong()" -> { a, b, t ->
            ColorSpaces.interpolateCubehelix(a, b, t, long = true)
          }
          // d3's generic `interpolate` dispatches on the type of its ends; for two colour strings
          // that is `interpolateRgb`, which is the case a scale's `range` reaches by default.
          "interpolate()" -> { a, b, t ->
            ColorSpaces.interpolateRgb(a, b, t)
          }
          else -> null
        }
      if (interpolate == null) {
        unmapped.merge(fn, 1, Int::plus)
        continue
      }
      val from = colour(built.getOrNull(0))
      val to = colour(built.getOrNull(1))
      val t =
        (vector["args"] as? JsonArray)?.getOrNull(0)?.let { (it as? JsonPrimitive)?.doubleOrNull }
      val expected = colour(vector["result"])
      if (from == null || to == null || t == null || expected == null) {
        // A ramp built from an object rather than a colour string, or one whose answer is not a
        // colour: `interpolateHue` returns a number, and a `null` end is upstream's own edge case.
        unmapped.merge("$fn (an end or the answer is not a colour string)", 1, Int::plus)
        continue
      }
      replayed++
      val actual = interpolate(from, to, t)
      // Compared channel by channel with a rounding tolerance rather than as text: upstream prints
      // `rgb(94, 108, 146)` with its channels already rounded to whole numbers, so the comparison
      // has to allow the half-unit that rounding hides.
      val close =
        abs(actual.red * 255.0 - expected.red * 255.0) <= 0.5001 &&
          abs(actual.green * 255.0 - expected.green * 255.0) <= 0.5001 &&
          abs(actual.blue * 255.0 - expected.blue * 255.0) <= 0.5001 &&
          abs(actual.alpha - expected.alpha) <= 0.002
      if (!close) {
        failures.add(
          "$fn(${show(from)} → ${show(to)}, t=$t): upstream ${show(expected)}, ours ${show(actual)}"
        )
      }
    }

    val ledger = StringBuilder("replayed $replayed of ${vectors.size} d3-interpolate vectors\n")
    unmapped.entries
      .sortedByDescending { it.value }
      .forEach { ledger.append("  ${it.key}: ${it.value}\n") }
    failures.forEach { ledger.append("MISMATCH $it\n") }
    File(File(System.getProperty("user.dir")).parentFile, "build/upstream-interpolate-ledger.txt")
      .apply {
        parentFile.mkdirs()
        writeText(ledger.toString())
      }

    assertEquals(
      emptyList<String>(),
      failures.take(10),
      "d3-interpolate disagrees with ColorSpaces",
    )
    assertTrue(replayed >= 60, "only $replayed vectors replayed; the harness must not shrink")
  }

  private fun show(colour: SceneColor): String =
    "rgb(${(colour.red * 255).toInt()}, ${(colour.green * 255).toInt()}, ${(colour.blue * 255).toInt()})"
}
