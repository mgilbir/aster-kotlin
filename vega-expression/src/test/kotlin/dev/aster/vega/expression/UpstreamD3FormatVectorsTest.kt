package dev.aster.vega.expression

import java.io.File
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
 * **d3-format's** own tests, replayed against the `format` expression function.
 *
 * Every number a chart writes goes through this: an axis label, a legend entry, a tooltip. d3's
 * corpus is 406 applications of a built formatter — `format(".2f")(42)` — over the whole grammar,
 * including the parts that look like details and are not: where the sign goes relative to a
 * currency symbol, what `,` does to a number with no integer part, how `%` interacts with
 * precision, and the rounding ties this engine had wrong until `Decimals` stopped going through
 * Java's `%e`.
 *
 * These vectors exist because the recorder learned a **third** test runner. Vega uses `tape`, most
 * of d3 uses `mocha`, and the packages d3 has migrated use `vitest` — `d3-format` recorded
 * *nothing* at all until the shim for it existed, and 24 of 24 files were being counted as skipped.
 */
class UpstreamD3FormatVectorsTest {

  private val json = Json { ignoreUnknownKeys = true }

  private val vectors: List<JsonObject> by lazy {
    val file =
      File(
        File(System.getProperty("user.dir")).parentFile,
        "test-fixtures/upstream-vectors/d3-format.json",
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
          // `JSON.stringify(-0)` is "0", so the recorder tags a negative zero; d3 signs by
          // `1 / value < 0`, and the two zeros format differently under `+f`.
          "-0" -> -0.0
          "NaN" -> Double.NaN
          "Infinity" -> Double.POSITIVE_INFINITY
          "-Infinity" -> Double.NEGATIVE_INFINITY
          else -> null
        }
      else -> null
    }

  @Test
  fun `d3-format's own vectors replay against the format function`() {
    var replayed = 0
    val unmapped = mutableMapOf<String, Int>()
    val failures = mutableListOf<String>()

    for (vector in vectors) {
      val fn = vector["fn"]?.jsonPrimitive?.content
      // The precision helpers and `formatPrefix` are what `vega-format`'s `formatSpan` calls to
      // decide how many decimals an axis label needs, so they are replayed here rather than
      // counted as unmapped.
      if (fn in PRECISION || fn == "formatPrefix()") {
        val outcome = replayHelper(fn!!, vector)
        if (outcome == null) unmapped.merge("$fn (not comparable)", 1, Int::plus)
        else {
          replayed++
          if (outcome.isNotEmpty()) failures.add(outcome)
        }
        continue
      }
      if (fn != "format()") {
        unmapped.merge(fn ?: "?", 1, Int::plus)
        continue
      }
      val specifier =
        (vector["constructedWith"] as? JsonArray)?.getOrNull(0)?.let {
          (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
        }
      if (specifier == null) {
        unmapped.merge("format (built from something other than a specifier string)", 1, Int::plus)
        continue
      }
      // A specifier this engine's grammar does not accept is *reported* rather than silently
      // approximated — `s`, `r`, the radix types, the fill/align/width slots — so it is counted
      // here
      // by the type it asked for rather than compared against a fallback.
      val parsed = NumberFormat.parse(specifier)
      if (parsed == null) {
        val type = specifier.lastOrNull()?.takeIf { !it.isDigit() }?.toString() ?: "(no type)"
        unmapped.merge("a specifier this grammar does not accept: '$type'", 1, Int::plus)
        continue
      }
      val value = number((vector["args"] as? JsonArray)?.getOrNull(0))
      val expected = (vector["result"] as? JsonPrimitive)?.takeIf { it.isString }?.content
      if (value == null || expected == null) {
        unmapped.merge("format (the value or the answer is not comparable)", 1, Int::plus)
        continue
      }
      replayed++
      val actual = NumberFormat.format(value, specifier)
      if (expected != actual)
        failures.add("format(\"$specifier\")($value): upstream $expected, ours $actual")
    }

    val ledger = StringBuilder("replayed $replayed of ${vectors.size} d3-format vectors\n")
    unmapped.entries
      .sortedByDescending { it.value }
      .forEach { ledger.append("  ${it.key}: ${it.value}\n") }
    failures.forEach { ledger.append("MISMATCH $it\n") }
    File(File(System.getProperty("user.dir")).parentFile, "build/upstream-d3-format-ledger.txt")
      .apply {
        parentFile.mkdirs()
        writeText(ledger.toString())
      }

    val known =
      json
        .parseToJsonElement(
          File(
              File(System.getProperty("user.dir")).parentFile,
              "test-fixtures/upstream-vectors/known-divergences.json",
            )
            .readText()
        )
        .jsonObject["divergences"]!!
        .jsonArray
        .map { it.jsonObject }
        .filter { it["kind"]?.jsonPrimitive?.content == "format" }
        .map { it["signature"]!!.jsonPrimitive.content }
    assertEquals(
      known.sorted(),
      failures.map { it.substringBefore(": upstream") }.sorted(),
      "the set of format divergences changed; update known-divergences.json",
    )
    assertTrue(replayed >= 548, "only $replayed vectors replayed; the harness must not shrink")
  }

  /**
   * One `precisionFixed`/`precisionRound`/`precisionPrefix`/`formatPrefix()` vector: the empty
   * string when it agrees, a description when it does not, and null when it is not comparable.
   */
  private fun replayHelper(fn: String, vector: JsonObject): String? {
    if (fn == "formatPrefix()") {
      val built = vector["constructedWith"] as? JsonArray ?: return null
      val specifier =
        (built.getOrNull(0) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
      val reference = number(built.getOrNull(1)) ?: return null
      val spec = NumberFormat.parse(specifier) ?: return null
      val value = number((vector["args"] as? JsonArray)?.getOrNull(0)) ?: return null
      val expected =
        (vector["result"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
      val actual = NumberFormat.prefixed(spec, reference)(value)
      return if (expected == actual) ""
      else "formatPrefix(\"$specifier\", $reference)($value): upstream $expected, ours $actual"
    }
    val args = vector["args"] as? JsonArray ?: return null
    val step = number(args.getOrNull(0)) ?: return null
    val expected = (vector["result"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: return null
    val actual =
      when (fn) {
        "precisionFixed" -> NumberFormat.precisionFixed(step)
        "precisionRound" ->
          NumberFormat.precisionRound(step, number(args.getOrNull(1)) ?: return null)
        else -> NumberFormat.precisionPrefix(step, number(args.getOrNull(1)) ?: return null)
      }
    return if (expected == actual) ""
    else "$fn(${args.joinToString(", ")}): upstream $expected, ours $actual"
  }

  private companion object {
    val PRECISION = setOf("precisionFixed", "precisionRound", "precisionPrefix")
  }
}
