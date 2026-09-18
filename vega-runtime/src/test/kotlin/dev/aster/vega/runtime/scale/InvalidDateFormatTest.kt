package dev.aster.vega.runtime.scale

import dev.aster.vega.model.time.TimeFormat
import java.io.File
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * What d3 prints for a value that is **not an instant**, replayed against [TimeFormat].
 *
 * Unlike `UpstreamD3TimeFormatVectorsTest` this replays no upstream *test*, because d3's suite
 * formats dates and an Invalid Date is not one. It is still observable, still reachable from a
 * specification, and it decides a chart's size: `formatType: "time"` over a column of words is
 * enough, since Vega-Lite writes `toDate(datum[...])` over it and `Date.parse` answers `NaN` rather
 * than nothing. The label that comes out is garbage in every case, and being *upstream's* garbage
 * is the whole point — it is one character wider than what this engine wrote, the label is turned
 * on its side, and that was the eight pixels of unexplained chart height.
 *
 * The vectors are recorded by `oracle-js/src/record-invalid-date-formats.mjs`: every directive in
 * d3's table crossed with every pad modifier, in both zones, plus a handful of whole patterns and
 * the **multi-format** a guide with no specifier uses. Nothing here states what the answer should
 * be; the answers are d3's, and the three that look like mistakes are its own —
 *
 * ```
 * %I  12    pad(d.getHours() % 12 || 12, p, 2), and NaN is falsy
 * %p  AM    locale_periods[+(d.getHours() >= 12)], and NaN >= 12 is false
 * %q  1     1 + ~~(d.getMonth() / 3), and ~~NaN is 0
 * ```
 *
 * — while `%B` is the empty string rather than `undefined`, because d3 joins an array and
 * `[undefined].join('')` is `''`.
 */
class InvalidDateFormatTest {

  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun `d3's answer for a value that is not an instant is this engine's`() {
    val file =
      File(
        File(System.getProperty("user.dir")).parentFile,
        "test-fixtures/upstream-vectors/d3-invalid-date.json",
      )
    assumeTrue(
      file.isFile,
      "no vectors at ${file.path} — run oracle-js/src/record-invalid-date-formats.mjs",
    )

    val failures = mutableListOf<String>()
    var replayed = 0
    var multi = 0
    val calls = json.parseToJsonElement(file.readText()).jsonObject["calls"]!!.jsonArray
    for (vector in calls) {
      val row = vector.jsonObject
      val function = row["fn"]!!.jsonPrimitive.content
      val expected = row["result"]!!.jsonPrimitive.content
      val pattern = row["constructedWith"]!!.jsonArray.firstOrNull()?.jsonPrimitive?.content
      // The zone cannot matter — there is no instant to place in one — and asserting that is worth
      // a line, because a formatter that reached for the platform's zone would be reading a field
      // of a date it does not have.
      val zone = if (function.startsWith("utc")) TimeZone.UTC else TimeZone.of("America/Havana")
      val actual =
        when (function) {
          "timeFormat",
          "utcFormat" -> TimeFormat.format(Double.NaN, pattern!!, zone)
          // No specifier at all, which `vega-format` routes to its multi-format: every
          // `interval(d) < d` test is false for an Invalid Date, so it falls through to the year.
          "timeMultiFormat",
          "utcMultiFormat" -> {
            multi++
            TimeTicks.label(Double.NaN, zone)
          }
          else -> {
            failures += "unknown function $function"
            continue
          }
        }
      replayed++
      if (actual != expected) {
        failures += "$function($pattern): upstream \"$expected\", here \"$actual\""
      }
    }

    assertEquals(emptyList<String>(), failures, "$replayed vectors replayed")
    // Every recorded call, not a number written down here: the recorder is meant to grow, and a
    // count in the test would have to be edited in step or else quietly stop covering the tail.
    assertEquals(calls.size, replayed, "the whole recorded corpus was replayed")
    assertTrue(replayed > 200, "the corpus is the directive table crossed with the modifiers")
    assertEquals(2, multi, "both multi-formats were replayed")
  }

  /**
   * The engine's `NaN` is every `NaN`.
   *
   * `Double.NaN` is one bit pattern of many and a formatter that tested `== Double.NaN` would
   * answer for none of them, so the two other ways a chart reaches this are named here: an infinity
   * — which `new Date(Infinity)` is also invalid for — and the quiet `NaN` arithmetic produces.
   */
  @Test
  fun `an infinity is not an instant either`() {
    assertEquals("0NaN", TimeFormat.format(Double.POSITIVE_INFINITY, "%Y", TimeZone.UTC))
    assertEquals("0NaN", TimeFormat.format(Double.NEGATIVE_INFINITY, "%Y", TimeZone.UTC))
    // Arithmetic's own NaN rather than the constant, which is a different bit pattern.
    assertEquals(
      "0NaN",
      TimeFormat.format(Double.POSITIVE_INFINITY - Double.POSITIVE_INFINITY, "%Y", TimeZone.UTC),
    )
  }
}
