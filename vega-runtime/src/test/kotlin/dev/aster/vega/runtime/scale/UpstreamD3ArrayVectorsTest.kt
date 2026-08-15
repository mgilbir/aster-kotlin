package dev.aster.vega.runtime.scale

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
 * **d3-array's** own tick tests, replayed against [Ticks].
 *
 * Where an axis puts its labels is d3's algorithm, and this is d3's own corpus for it: 253 recorded
 * calls to `ticks`, `tickIncrement`, `tickStep` and `nice`, including the awkward inputs a chart
 * reaches by accident — a NaN bound, an empty span, a count of zero, spans that cross a decade
 * boundary, and the fractional steps where the increment is returned as a negative divisor.
 *
 * `TicksTest` already compares a hundred domains against upstream. This is the same idea with
 * somebody else's choice of inputs, which is the point: it covers what we would not have thought to
 * try.
 */
class UpstreamD3ArrayVectorsTest {

  private val json = Json { ignoreUnknownKeys = true }

  private val vectors: List<JsonObject> by lazy {
    val file =
      File(
        File(System.getProperty("user.dir")).parentFile,
        "test-fixtures/upstream-vectors/d3-array.json",
      )
    assumeTrue(
      file.isFile,
      "no upstream vectors at ${file.path} — run scripts/record-upstream-vectors.sh to replay them",
    )
    json.parseToJsonElement(file.readText()).jsonObject["calls"]!!.jsonArray.map { it.jsonObject }
  }

  /** A recorded number, including the ones JSON cannot hold. */
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
  fun `d3-array's own tick vectors replay against Ticks`() {
    var replayed = 0
    val unmapped = mutableMapOf<String, Int>()
    val failures = mutableListOf<String>()

    for (vector in vectors) {
      val fn = vector["fn"]?.jsonPrimitive?.content ?: continue
      if (fn !in REPLAYED) {
        unmapped.merge(fn, 1, Int::plus)
        continue
      }
      // A vector too large to record keeps its name and a byte count instead of its arguments —
      // `d3-array` operates on million-element arrays in places. Counted, not dereferenced.
      val args = (vector["args"] as? JsonArray)
      if (args == null) {
        unmapped.merge("$fn (too large to record)", 1, Int::plus)
        continue
      }
      val start = number(args.getOrNull(0))
      val stop = number(args.getOrNull(1))
      val count = number(args.getOrNull(2))
      if (start == null || stop == null || count == null) {
        unmapped.merge("$fn (an argument is not a number)", 1, Int::plus)
        continue
      }
      // A count arrives here as a `Double` and this engine's tick API takes an `Int`, so the
      // conversion is the same one `NumberValues.resolveInt` makes: a non-finite count is *none*,
      // which is upstream's answer too — `ticks(0, 1, Infinity)` is `[]`. Taking `Int.MAX_VALUE`
      // instead is what exhausted the heap the first time this test ran.
      val result = vector["result"]
      val expected: String
      val actual: String
      when (fn) {
        "ticks" -> {
          expected = (result as? JsonArray)?.joinToString(",") { show(number(it)) } ?: continue
          actual = Ticks.ticks(start, stop, count).joinToString(",") { show(it) }
        }
        "tickIncrement" -> {
          expected = show(number(result))
          actual = show(Ticks.tickIncrement(start, stop, count))
        }
        "tickStep" -> {
          // d3's own `tickStep`, which this engine now has: the increment as a signed magnitude.
          expected = show(number(result))
          actual = show(Ticks.step(start, stop, count))
        }
        "nice" -> {
          expected = (result as? JsonArray)?.joinToString(",") { show(number(it)) } ?: continue
          actual = Ticks.nice(listOf(start, stop), count.toInt()).joinToString(",") { show(it) }
        }
        else -> continue
      }
      replayed++
      if (expected != actual) {
        failures.add("$fn($start, $stop, $count): upstream [$expected], ours [$actual]")
      }
    }

    val ledger = StringBuilder("replayed $replayed of ${vectors.size} d3-array vectors\n")
    unmapped.entries
      .sortedByDescending { it.value }
      .forEach { ledger.append("  not mapped: ${it.key}: ${it.value}\n") }
    failures.forEach { ledger.append("MISMATCH $it\n") }
    File(File(System.getProperty("user.dir")).parentFile, "build/upstream-d3-array-ledger.txt")
      .apply {
        parentFile.mkdirs()
        writeText(ledger.toString())
      }

    assertEquals(emptyList<String>(), failures.take(10), "d3-array disagrees with Ticks")
    assertTrue(replayed >= 240, "only $replayed vectors replayed; the harness must not shrink")
  }

  /** NaN and the infinities compare as text, so a mismatch reads as itself rather than as false. */
  private fun show(value: Double?): String =
    when {
      value == null -> "null"
      value.isNaN() -> "NaN"
      !value.isFinite() -> value.toString()
      value == value.toLong().toDouble() -> value.toLong().toString()
      else -> value.toString()
    }

  private companion object {
    /**
     * The tick family. The rest of `d3-array` — `sum`, `median`, `quantile`, `variance`, the
     * bisectors, the grouping helpers — is implemented here as **aggregate operations** over rows
     * rather than as functions over an array, so it wants an adapter of its own rather than a
     * pretence that the shapes match.
     */
    val REPLAYED = setOf("ticks", "tickIncrement", "tickStep", "nice")
  }
}
