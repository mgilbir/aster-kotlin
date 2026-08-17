package dev.aster.vega.model.time

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
 * **d3-time-format's** own tests, replayed against [TimeFormat] and [TimeParse].
 *
 * Every label on a time axis is one of these patterns, and the corpus is the whole directive table
 * — `%a %A %b %B %c %d %e %f %g %G %H %I %j %L %m %M %p %q %Q %s %S %u %U %V %w %W %x %X %y %Y %Z
 * %%` — applied to dates chosen to break them: the first of January, a leap day, a week that
 * straddles a year boundary, midnight and noon for the twelve-hour forms, and (because d3 runs its
 * suite in America/Los_Angeles) instants either side of a daylight-saving change.
 *
 * The zone comes from the vector file rather than the machine, so the same run means the same thing
 * anywhere — and it is a real check on this side as well, since a formatter that quietly used the
 * platform zone would pass at home and fail here.
 */
class UpstreamD3TimeFormatVectorsTest {

  private val json = Json { ignoreUnknownKeys = true }

  private val document: JsonObject by lazy {
    val file =
      File(
        File(System.getProperty("user.dir")).parentFile,
        "test-fixtures/upstream-vectors/d3-time-format.json",
      )
    assumeTrue(
      file.isFile,
      "no upstream vectors at ${file.path} — run scripts/record-upstream-vectors.sh to replay them",
    )
    json.parseToJsonElement(file.readText()).jsonObject
  }

  private fun instant(element: kotlinx.serialization.json.JsonElement?): Double? =
    when (element) {
      is JsonPrimitive -> element.doubleOrNull
      is JsonObject ->
        if (element["\$"]?.jsonPrimitive?.content == "date") {
          element["epochMillis"]?.jsonPrimitive?.doubleOrNull
        } else {
          null
        }
      else -> null
    }

  @Test
  fun `d3-time-format's own vectors replay against this engine`() {
    var replayed = 0
    val unmapped = mutableMapOf<String, Int>()
    val failures = mutableListOf<String>()
    val local = TimeZone.of(document["timeZone"]!!.jsonPrimitive.content)

    for (vector in document["calls"]!!.jsonArray.map { it.jsonObject }) {
      val fn = vector["fn"]?.jsonPrimitive?.content ?: continue
      if (fn !in REPLAYED) {
        unmapped.merge(fn, 1, Int::plus)
        continue
      }
      val pattern =
        (vector["constructedWith"] as? JsonArray)?.getOrNull(0)?.let {
          (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
        }
      val args = vector["args"] as? JsonArray
      if (pattern == null || args == null) {
        unmapped.merge("$fn (built from something other than a pattern)", 1, Int::plus)
        continue
      }
      val utc = fn.startsWith("utc")
      val zone = if (utc) TimeZone.UTC else local

      val expected: String
      val actual: String
      if (fn.endsWith("Parse()")) {
        val text =
          (args.getOrNull(0) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
        // Upstream answers `null` for a string the pattern cannot read, which is the half of
        // parsing
        // that matters: a date that silently becomes something else is worse than one that fails.
        expected = instant(vector["result"])?.toLong()?.toString() ?: "null"
        actual =
          runCatching { TimeParse.parse(text, pattern, zone, utc) }
            .getOrNull()
            ?.toLong()
            ?.toString() ?: "null"
      } else {
        val at = instant(args.getOrNull(0)) ?: continue
        expected =
          (vector["result"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: run {
              unmapped.merge("$fn (the answer is not a string)", 1, Int::plus)
              continue
            }
        actual = runCatching { TimeFormat.format(at, pattern, zone) }.getOrElse { "threw" }
      }
      replayed++
      if (expected != actual)
        failures.add("$fn(\"$pattern\") on ${args.firstOrNull()}: upstream $expected, ours $actual")
    }

    val ledger =
      StringBuilder(
        "replayed $replayed of ${document["calls"]!!.jsonArray.size} d3-time-format vectors\n"
      )
    unmapped.entries
      .sortedByDescending { it.value }
      .forEach { ledger.append("  ${it.key}: ${it.value}\n") }
    failures.forEach { ledger.append("MISMATCH $it\n") }
    File(
        File(System.getProperty("user.dir")).parentFile,
        "build/upstream-d3-time-format-ledger.txt",
      )
      .apply {
        parentFile.mkdirs()
        writeText(ledger.toString())
      }

    assertEquals(
      emptyList<String>(),
      failures.take(12),
      "d3-time-format disagrees with this engine",
    )
    assertTrue(replayed >= 150, "only $replayed vectors replayed; the harness must not shrink")
  }

  private companion object {
    val REPLAYED = setOf("timeFormat()", "utcFormat()", "utcParse()", "timeParse()")
  }
}
