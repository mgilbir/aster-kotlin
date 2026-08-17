package dev.aster.vega.dataflow.transform

import dev.aster.vega.expression.RandomStream
import dev.aster.vega.expression.Statistics
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * **vega-statistics'** own `bin` and `quantiles` tests, replayed against this engine.
 *
 * Bin edges are visible in every histogram — a step chosen one power of ten out puts every bar in
 * the wrong place — and upstream's corpus is the awkward part of the algorithm: an extent of no
 * width, a `maxbins` of one, a `minstep` that fights the span, and the floating-point cases where a
 * bound lands a hair off a boundary (`[99.2258064516129, 2307.451612903226]`).
 *
 * `quantiles` is the other half: Vega's `q1`, `median` and `q3` aggregates and its box plots all
 * rest on the same interpolation rule, and upstream's vectors pin what it does between two samples.
 */
class UpstreamStatisticsVectorsTest {

  private val json = Json { ignoreUnknownKeys = true }

  private val vectors: List<JsonObject> by lazy {
    val file =
      File(
        File(System.getProperty("user.dir")).parentFile,
        "test-fixtures/upstream-vectors/vega-statistics.json",
      )
    assumeTrue(
      file.isFile,
      "no upstream vectors at ${file.path} — run scripts/record-upstream-vectors.sh to replay them",
    )
    json.parseToJsonElement(file.readText()).jsonObject["calls"]!!.jsonArray.map { it.jsonObject }
  }

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

  @Test
  fun `vega-statistics' own bin and quantile vectors replay against this engine`() {
    var replayed = 0
    // One generator per seed, so a sequence of draws is replayed as a sequence.
    val lcgStreams = mutableMapOf<Double, RandomStream>()
    val unmapped = mutableMapOf<String, Int>()
    val failures = mutableListOf<String>()

    for (vector in vectors) {
      val fn = vector["fn"]?.jsonPrimitive?.content ?: continue
      val args = vector["args"] as? JsonArray
      if (args == null) {
        unmapped.merge("$fn (too large to record)", 1, Int::plus)
        continue
      }
      when (fn) {
        // The seeded generator, replayed as **one sequence**: all 9,708 draws share the seed
        // 123456789 and the file holds them in call order, so the nth vector is the nth draw. The
        // arithmetic is the point — `1103515245 * seed` reaches past 2^53, so JavaScript loses low
        // bits and the whole sequence is a property of that loss. Computing it exactly would give a
        // better generator and the wrong one, and this is what makes a `sample` transform
        // reproduce.
        "randomLCG()" -> {
          val seed = (vector["constructedWith"] as? JsonArray)?.getOrNull(0)?.let { number(it) }
          if (seed == null) {
            unmapped.merge("randomLCG() (no seed recorded)", 1, Int::plus)
            continue
          }
          val stream = lcgStreams.getOrPut(seed) { RandomStream(seed.toLong()) }
          val drawn = stream.next()
          val answer = number(vector["result"]) ?: continue
          replayed++
          if (show(drawn) != show(answer))
            failures.add("randomLCG($seed) draw ${replayed}: upstream $answer, ours $drawn")
        }
        "densityNormal",
        "cumulativeNormal",
        "quantileNormal",
        "densityLogNormal",
        "cumulativeLogNormal",
        "quantileLogNormal",
        "densityUniform",
        "cumulativeUniform",
        "quantileUniform" -> {
          val x = number(args.getOrNull(0))
          if (x == null) {
            unmapped.merge("$fn (the argument is not a number)", 1, Int::plus)
            continue
          }
          // Upstream's defaults are 0 and 1 for the two normals and 0 and 1 for the uniform's
          // bounds, and the tests mostly leave them alone.
          val a = number(args.getOrNull(1))
          val b = number(args.getOrNull(2))
          val answer = number(vector["result"]) ?: continue
          val ours =
            when (fn) {
              "densityNormal" -> Statistics.densityNormal(x, a ?: 0.0, b ?: 1.0)
              "cumulativeNormal" -> Statistics.cumulativeNormal(x, a ?: 0.0, b ?: 1.0)
              "quantileNormal" -> Statistics.quantileNormal(x, a ?: 0.0, b ?: 1.0)
              "densityLogNormal" -> Statistics.densityLogNormal(x, a ?: 0.0, b ?: 1.0)
              "cumulativeLogNormal" -> Statistics.cumulativeLogNormal(x, a ?: 0.0, b ?: 1.0)
              "quantileLogNormal" -> Statistics.quantileLogNormal(x, a ?: 0.0, b ?: 1.0)
              "densityUniform" -> Statistics.densityUniform(x, a ?: 0.0, b ?: 1.0)
              "cumulativeUniform" -> Statistics.cumulativeUniform(x, a ?: 0.0, b ?: 1.0)
              else -> Statistics.quantileUniform(x, a ?: 0.0, b ?: 1.0)
            }
          replayed++
          if (show(answer) != show(ours))
            failures.add("$fn($x, $a, $b): upstream $answer, ours $ours")
        }
        "bin" -> {
          val params = args.getOrNull(0) as? JsonObject ?: continue
          val extent = params["extent"] as? JsonArray
          val min = number(extent?.getOrNull(0))
          val max = number(extent?.getOrNull(1))
          if (min == null || max == null) {
            unmapped.merge("bin (no extent)", 1, Int::plus)
            continue
          }
          // `span` overrides the extent's own width upstream, which this engine folds into the
          // extent it is given; a vector that uses it is measuring a different entry point.
          if (params["span"] != null) {
            unmapped.merge(
              "bin (a `span` override, which this engine takes as an extent)",
              1,
              Int::plus,
            )
            continue
          }
          val result = vector["result"] as? JsonObject ?: continue
          val settings =
            BinTransform.binSettings(
              min = min,
              max = max,
              maxbins = number(params["maxbins"])?.toInt() ?: DEFAULT_MAXBINS,
              base = number(params["base"]) ?: 10.0,
              step = number(params["step"]),
              divide =
                (params["divide"] as? JsonArray)?.mapNotNull { number(it) } ?: listOf(5.0, 2.0),
              minstep = number(params["minstep"]) ?: 0.0,
              nice = (params["nice"] as? JsonPrimitive)?.booleanOrNull ?: true,
            )
          replayed++
          // `binSettings` here is `vega-statistics`' `bin` **plus** the realignment upstream's
          // `Bin`
          // *transform* applies on top of it — `start + ceil((stop - start) / step) * step` —
          // because
          // both belong to the settings a histogram is drawn from. The vector records the bare
          // function, so upstream's own realignment is applied to its result before comparing,
          // which
          // keeps the comparison on the substance: the chosen step and start.
          val upstreamStart = number(result["start"]) ?: continue
          val upstreamStep = number(result["step"]) ?: continue
          val upstreamStop = number(result["stop"]) ?: continue
          val realigned =
            upstreamStart +
              kotlin.math.ceil((upstreamStop - upstreamStart) / upstreamStep) * upstreamStep
          val expected = "${show(upstreamStart)},${show(realigned)},${show(upstreamStep)}"
          val actual = "${show(settings.start)},${show(settings.stop)},${show(settings.step)}"
          if (expected != actual) failures.add("bin(${params}): upstream $expected, ours $actual")
        }
        "quantiles" -> {
          val raw = (args.getOrNull(0) as? JsonArray) ?: continue
          // One of upstream's two calls passes **objects** and an accessor. Reading numbers out of
          // that with `mapNotNull` silently produced an empty list and a NaN answer, which looked
          // like a bug in `quantiles` and was a bug in this adapter.
          if (raw.any { it !is JsonPrimitive }) {
            unmapped.merge("quantiles (values are objects behind an accessor)", 1, Int::plus)
            continue
          }
          val values = raw.mapNotNull { number(it) }
          val probabilities =
            (args.getOrNull(1) as? JsonArray)?.mapNotNull { number(it) } ?: continue
          val expected =
            (vector["result"] as? JsonArray)?.joinToString(",") { show(number(it)) } ?: continue
          replayed++
          val actual = Distributions.quantiles(values, probabilities).joinToString(",") { show(it) }
          if (expected != actual) {
            failures.add("quantiles($values, $probabilities): upstream $expected, ours $actual")
          }
        }
        else -> unmapped.merge(fn, 1, Int::plus)
      }
    }

    val ledger = StringBuilder("replayed $replayed of ${vectors.size} vega-statistics vectors\n")
    unmapped.entries
      .sortedByDescending { it.value }
      .forEach { ledger.append("  ${it.key}: ${it.value}\n") }
    failures.forEach { ledger.append("MISMATCH $it\n") }
    File(File(System.getProperty("user.dir")).parentFile, "build/upstream-statistics-ledger.txt")
      .apply {
        parentFile.mkdirs()
        writeText(ledger.toString())
      }

    assertEquals(emptyList<String>(), failures.take(10), "vega-statistics disagrees")
    assertTrue(replayed >= 9750, "only $replayed vectors replayed; the harness must not shrink")
  }

  private fun show(value: Double?): String =
    when {
      value == null -> "null"
      value.isNaN() -> "NaN"
      !value.isFinite() -> value.toString()
      value == value.toLong().toDouble() -> value.toLong().toString()
      else -> value.toString()
    }

  private companion object {
    /** Upstream's default when a `bin` names no `maxbins`. */
    const val DEFAULT_MAXBINS = 20
  }
}
