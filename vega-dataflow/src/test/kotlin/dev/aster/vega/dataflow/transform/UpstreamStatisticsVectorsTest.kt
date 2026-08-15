package dev.aster.vega.dataflow.transform

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
    assertTrue(replayed >= 12, "only $replayed vectors replayed; the harness must not shrink")
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
