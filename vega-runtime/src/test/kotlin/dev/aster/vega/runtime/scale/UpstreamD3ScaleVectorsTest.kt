package dev.aster.vega.runtime.scale

import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
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
 * **d3-scale's** own linear-scale tests, replayed against [LinearScale].
 *
 * A scale is the arithmetic under every mark's position, and d3's corpus for it is a *configuration
 * chain* rather than a call: `scaleLinear().domain([1, 2]).range([10, 20]).clamp(true)` and only
 * then a question. Those vectors exist here because the recorder learned to follow a chain — each
 * chainable call is remembered, and a call that answers something records the configuration that
 * produced it. `d3-scale` went from 55 recorded vectors to **1,123** with that one change.
 *
 * What is replayed is the part this engine models the same way: evaluating the scale, inverting it,
 * and its ticks. A getter (`domain()` with no arguments) is upstream's own reflection API and has
 * no equivalent here, so it is counted rather than compared.
 */
class UpstreamD3ScaleVectorsTest {

  private val json = Json { ignoreUnknownKeys = true }

  private val vectors: List<JsonObject> by lazy {
    val file =
      File(
        File(System.getProperty("user.dir")).parentFile,
        "test-fixtures/upstream-vectors/d3-scale.json",
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

  private fun numbers(element: kotlinx.serialization.json.JsonElement?): List<Double>? =
    (element as? JsonArray)?.map { number(it) ?: return null }

  /** The scale a chain describes, or null when it configures something this engine models apart. */
  private fun build(chain: JsonArray?, constructedWith: JsonArray?): LinearScale? {
    var domain = listOf(0.0, 1.0)
    var range = listOf(0.0, 1.0)
    // `scaleLinear(range)` and `scaleLinear(domain, range)` — d3 takes the configuration as
    // constructor arguments as well as chained calls, and ignoring them made a scale over [1, 2]
    // look like the default one, so `scale(0.5)` read 0.5 where upstream said 1.5.
    when (constructedWith?.size) {
      1 -> range = numbers(constructedWith[0]) ?: return null
      2 -> {
        domain = numbers(constructedWith[0]) ?: return null
        range = numbers(constructedWith[1]) ?: return null
      }
      else -> Unit
    }
    var clamp = false
    var nice: Int? = null
    for (step in chain.orEmpty()) {
      val parts = step as? JsonArray ?: return null
      val method = (parts.firstOrNull() as? JsonPrimitive)?.content ?: return null
      val args = parts.getOrNull(1) as? JsonArray
      when (method) {
        "domain" -> domain = numbers(args?.getOrNull(0)) ?: return null
        "range" -> range = numbers(args?.getOrNull(0)) ?: return null
        "clamp" -> clamp = (args?.getOrNull(0) as? JsonPrimitive)?.booleanOrNull ?: return null
        "nice" -> nice = number(args?.getOrNull(0))?.toInt() ?: DEFAULT_NICE
        // `interpolate`, `rangeRound`, `unknown` and the rest are configured differently here — a
        // Vega scale takes them as specification properties rather than as chained calls.
        else -> return null
      }
    }
    if (domain.size != 2 || range.size != 2) return null
    val niced = nice?.let { Ticks.nice(domain, it) } ?: domain
    return LinearScale(name = "replay", domain = niced, range = range, clamp = clamp)
  }

  @Test
  fun `d3-scale's own linear vectors replay against LinearScale`() {
    var replayed = 0
    val unmapped = mutableMapOf<String, Int>()
    val failures = mutableListOf<String>()
    for (vector in vectors) {
      if (vector["fn"]?.jsonPrimitive?.content != "scaleLinear()") {
        unmapped.merge(vector["fn"]?.jsonPrimitive?.content ?: "?", 1, Int::plus)
        continue
      }
      val method = vector["method"]?.jsonPrimitive?.content ?: "(call)"
      if (method !in REPLAYED) {
        unmapped.merge("$method (upstream's reflection API, not modelled here)", 1, Int::plus)
        continue
      }
      val scale = build(vector["chain"] as? JsonArray, vector["constructedWith"] as? JsonArray)
      if (scale == null) {
        unmapped.merge("a chain step this engine takes as a specification property", 1, Int::plus)
        continue
      }
      val args = vector["args"] as? JsonArray ?: continue
      val expected: String
      val actual: String
      when (method) {
        "(call)" -> {
          val at = number(args.getOrNull(0)) ?: continue
          expected = show(number(vector["result"]))
          actual = show(scale.scale(VegaValue.Num(at)).asDouble())
        }
        "invert" -> {
          val at = number(args.getOrNull(0)) ?: continue
          expected = show(number(vector["result"]))
          actual = show(scale.invert(at))
        }
        "ticks" -> {
          // A tick *count* is an `Int` on this side, so `ticks(Infinity)` — which d3's tests ask
          // for
          // — has no expression here: `toInt()` makes it `Int.MAX_VALUE` and the scale sets about
          // building two billion ticks. Counted rather than converted, the same conclusion the
          // d3-array replay reached about the same input.
          val requested = number(args.getOrNull(0))
          if (requested != null && (!requested.isFinite() || requested > MAX_TICKS)) {
            unmapped.merge("ticks (a count this engine's Int API cannot express)", 1, Int::plus)
            continue
          }
          val count = requested?.toInt() ?: DEFAULT_TICKS
          expected = numbers(vector["result"])?.joinToString(",") { show(it) } ?: continue
          actual = scale.ticks(count).joinToString(",") { show(it) }
        }
        else -> continue
      }
      replayed++
      if (expected != actual) {
        failures.add(
          "scaleLinear${describe(vector["chain"] as? JsonArray)}.$method(${args.joinToString(",") { show(number(it)) }}): " +
            "upstream $expected, ours $actual"
        )
      }
    }

    val ledger = StringBuilder("replayed $replayed of ${vectors.size} d3-scale vectors\n")
    unmapped.entries
      .sortedByDescending { it.value }
      .forEach { ledger.append("  ${it.key}: ${it.value}\n") }
    failures.forEach { ledger.append("MISMATCH $it\n") }
    File(File(System.getProperty("user.dir")).parentFile, "build/upstream-d3-scale-ledger.txt")
      .apply {
        parentFile.mkdirs()
        writeText(ledger.toString())
      }

    assertEquals(emptyList<String>(), failures.take(10), "d3-scale disagrees with LinearScale")
    assertTrue(replayed >= 40, "only $replayed vectors replayed; the harness must not shrink")
  }

  private fun describe(chain: JsonArray?): String =
    chain.orEmpty().joinToString("") { step ->
      val parts = step as? JsonArray ?: return@joinToString ""
      ".${(parts.firstOrNull() as? JsonPrimitive)?.content}(${(parts.getOrNull(1) as? JsonArray)?.joinToString(",") ?: ""})"
    }

  private fun show(value: Double?): String =
    when {
      value == null -> "null"
      value.isNaN() -> "NaN"
      !value.isFinite() -> value.toString()
      else -> {
        // Six decimals: upstream's own numbers carry the same floating-point dust this engine's do,
        // and comparing them exactly would compare the order of two multiplications.
        val rounded = kotlin.math.round(value * 1_000_000.0) / 1_000_000.0
        if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString()
        else rounded.toString()
      }
    }

  private companion object {
    val REPLAYED = setOf("(call)", "invert", "ticks")
    const val DEFAULT_TICKS = 10
    const val DEFAULT_NICE = 10

    /** No chart asks for more, and comparing lists longer than this measures nothing. */
    const val MAX_TICKS = 10_000
  }
}
