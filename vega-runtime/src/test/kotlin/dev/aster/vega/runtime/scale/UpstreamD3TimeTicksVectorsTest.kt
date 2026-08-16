package dev.aster.vega.runtime.scale

import java.io.File
import kotlinx.datetime.TimeZone
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
 * **d3-time's** `timeTicks` and `utcTicks`, replayed against [TimeTicks].
 *
 * These choose the calendar unit a time axis is labelled in — seconds, or five minutes, or months —
 * and the choice is not a division: d3 picks from a fixed table of tick intervals by which one
 * comes closest to the requested count, so an axis over 90 minutes ticks every 15 minutes rather
 * than every 9. Getting it wrong does not shift a label, it relabels the whole axis.
 *
 * They live in a different module from the rest of d3-time's corpus — `TimeTicks` is a runtime
 * concern, `TimeStepper` a model one — which is the only reason this is a second file.
 *
 * Four vectors pass an **interval** as the third argument rather than a count. A vector records a
 * function by name, so which interval it was is not in the file, and they are counted rather than
 * guessed at.
 */
class UpstreamD3TimeTicksVectorsTest {

  private val json = Json { ignoreUnknownKeys = true }

  private val document: JsonObject by lazy {
    val file =
      File(
        File(System.getProperty("user.dir")).parentFile,
        "test-fixtures/upstream-vectors/d3-time.json",
      )
    assumeTrue(
      file.isFile,
      "no upstream vectors at ${file.path} — run scripts/record-upstream-vectors.sh to replay them",
    )
    json.parseToJsonElement(file.readText()).jsonObject
  }

  /** An instant, whether recorded as a `Date` marker or as a bare number. */
  private fun instant(element: kotlinx.serialization.json.JsonElement?): Double? =
    when (element) {
      is JsonPrimitive -> element.doubleOrNull
      is JsonObject ->
        if (element["\$"]?.jsonPrimitive?.content == "date") {
          element["epochMillis"]?.jsonPrimitive?.doubleOrNull
        } else if (element["\$"]?.jsonPrimitive?.content == "NaN") {
          Double.NaN
        } else {
          null
        }
      else -> null
    }

  @Test
  fun `d3's time tick generator replays against this engine`() {
    var replayed = 0
    val unmapped = mutableMapOf<String, Int>()
    val failures = mutableListOf<String>()
    val local = TimeZone.of(document["timeZone"]!!.jsonPrimitive.content)

    for (vector in document["calls"]!!.jsonArray.map { it.jsonObject }) {
      val fn = vector["fn"]?.jsonPrimitive?.content ?: continue
      if (fn != "timeTicks" && fn != "utcTicks") continue
      val args = vector["args"] as? JsonArray ?: continue
      val start = instant(args.getOrNull(0))
      val stop = instant(args.getOrNull(1))
      val count = (args.getOrNull(2) as? JsonPrimitive)?.doubleOrNull
      if (start == null || stop == null || count == null) {
        unmapped.merge("$fn (an interval rather than a count)", 1, Int::plus)
        continue
      }
      val expected = (vector["result"] as? JsonArray)?.mapNotNull { instant(it) } ?: continue
      val zone = if (fn == "utcTicks") TimeZone.UTC else local
      replayed++
      val actual = TimeTicks.ticks(start, stop, count.toInt(), zone)
      if (expected != actual) {
        failures.add(
          "$fn($start, $stop, ${count.toInt()}): upstream ${expected.size} ticks " +
            "${expected.take(4)}, ours ${actual.size} ${actual.take(4)}"
        )
      }
    }

    val ledger = StringBuilder("replayed $replayed d3-time tick vectors\n")
    unmapped.entries
      .sortedByDescending { it.value }
      .forEach { ledger.append("  ${it.key}: ${it.value}\n") }
    failures.forEach { ledger.append("MISMATCH $it\n") }
    File(File(System.getProperty("user.dir")).parentFile, "build/upstream-d3-timeticks-ledger.txt")
      .apply {
        parentFile.mkdirs()
        writeText(ledger.toString())
      }

    assertEquals(emptyList<String>(), failures.take(12), "d3's tick intervals disagree")
    assertTrue(replayed >= 45, "only $replayed vectors replayed; the harness must not shrink")
  }
}
