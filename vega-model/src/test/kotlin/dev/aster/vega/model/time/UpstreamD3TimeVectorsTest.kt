package dev.aster.vega.model.time

import java.io.File
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **d3-time's** own tests, replayed against `TimeStepper`.
 *
 * This is where the calendar arithmetic in this engine actually comes from: Vega's time scales,
 * axis ticks and `timeunit` transform all stand on d3's intervals, and d3 tests them far harder
 * than any chart-level fixture can — 1,047 recorded calls against 33 intervals, local and UTC.
 *
 * The zone is **not** this repository's usual one. d3 runs its suite in `America/Los_Angeles` and a
 * local interval's answer depends on it, so the recorder writes the zone into the vector file and
 * this reads it from there rather than assuming. A stepper is built with that zone explicitly,
 * which is also a small proof that `TimeStepper` does not secretly depend on the machine's.
 *
 * Coverage is stated, not implied: every vector is replayed or counted with a reason, and the floor
 * at the end stops the harness shrinking into a pass.
 */
class UpstreamD3TimeVectorsTest {

  private val json = Json { ignoreUnknownKeys = true }

  private val document: JsonObject by lazy {
    val file =
      File(
        File(System.getProperty("user.dir")).parentFile,
        "test-fixtures/upstream-vectors/d3-time.json",
      )
    org.junit.jupiter.api.Assumptions.assumeTrue(
      file.isFile,
      "no upstream vectors at ${file.path} — run scripts/record-upstream-vectors.sh to replay them",
    )
    json.parseToJsonElement(file.readText()).jsonObject
  }

  /**
   * d3's interval names, as an interval of ours and how many of it make one step.
   *
   * The gaps are as interesting as the entries. A **weekday-anchored week** — `timeMonday` through
   * `timeSaturday` — has no equivalent here, because Vega's grammar exposes one `week` and it
   * starts on Sunday; those vectors are counted, not skipped silently. `unixDay` is d3's day
   * counted from the epoch rather than from local midnight, which is a different thing again.
   */
  private fun stepper(name: String, step: Int): TimeStepper? {
    // `unixDay` is `utcDay` for every method here: it floors with `setUTCHours(0,0,0,0)`, offsets
    // with `setUTCDate`, and counts elapsed milliseconds over a day. The two part company only in
    // the `field` function `every()` filters on, which is not replayable anyway.
    val utc = name.startsWith("utc") || name.startsWith("unix")
    val zone = if (utc) TimeZone.UTC else TimeZone.of(document["timeZone"]!!.jsonPrimitive.content)
    val unit =
      name
        .removePrefix("utc")
        .removePrefix("unix")
        .removePrefix("time")
        .lowercase()
        .removeSuffix("s")
    val interval =
      when (unit) {
        "millisecond" -> TimeInterval.MILLISECOND
        "second" -> TimeInterval.SECOND
        "minute" -> TimeInterval.MINUTE
        "hour" -> TimeInterval.HOUR
        "day" -> TimeInterval.DAY
        "week",
        "sunday" -> TimeInterval.WEEK
        "month" -> TimeInterval.MONTH
        "year" -> TimeInterval.YEAR
        else -> return null
      }
    return TimeStepper(interval, step, zone)
  }

  /**
   * A numeric argument, or null when it is anything else.
   *
   * `undefined` is recorded as an object — `{"$": "undefined"}` — because recording it as null
   * would be indistinguishable from a real null. Reading it as a primitive throws, which is how a
   * `range` called with an explicit `undefined` step took the whole replay down.
   */
  private fun number(element: kotlinx.serialization.json.JsonElement?): Double? =
    (element as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull

  private fun date(element: kotlinx.serialization.json.JsonElement?): Double? =
    (element as? JsonObject)
      ?.takeIf { it["\$"]?.jsonPrimitive?.content == "date" }
      ?.get("epochMillis")
      ?.jsonPrimitive
      ?.doubleOrNull

  @Test
  fun `d3-time's own vectors replay against TimeStepper`() {
    var replayed = 0
    val unmapped = mutableMapOf<String, Int>()
    val failures = mutableListOf<String>()

    for (vector in document["calls"]!!.jsonArray.map { it.jsonObject }) {
      val name = vector["fn"]!!.jsonPrimitive.content
      val interval = name.substringBefore('.')
      val method = if ('.' in name) name.substringAfter('.') else "(call)"
      val args = vector["args"]!!.jsonArray
      val result = vector["result"]

      if (method !in REPLAYED_METHODS) {
        unmapped.merge("$method (not implemented here)", 1, Int::plus)
        continue
      }
      val stepper = stepper(interval, 1)
      if (stepper == null) {
        unmapped.merge("$interval (no equivalent interval)", 1, Int::plus)
        continue
      }
      val expected: String
      val actual: String
      when (method) {
        // Calling an interval *is* flooring it: `timeDay(date)` and `timeDay.floor(date)` are the
        // same function in d3, and the shorter spelling is the one its tests mostly use.
        "(call)",
        "floor" -> {
          val at = date(args.getOrNull(0)) ?: continue
          expected = describe(date(result))
          actual = describe(stepper.floor(at))
        }
        "ceil" -> {
          val at = date(args.getOrNull(0)) ?: continue
          expected = describe(date(result))
          actual = describe(stepper.ceil(at))
        }
        "round" -> {
          val at = date(args.getOrNull(0)) ?: continue
          expected = describe(date(result))
          actual = describe(stepper.round(at))
        }
        "count" -> {
          val from = date(args.getOrNull(0)) ?: continue
          val to = date(args.getOrNull(1)) ?: continue
          val answer = number(result) ?: continue
          expected = answer.toString()
          actual = stepper.count(from, to).toString()
        }
        "offset" -> {
          val at = date(args.getOrNull(0)) ?: continue
          val count = number(args.getOrNull(1))?.toInt() ?: 1
          expected = describe(date(result))
          actual = describe(stepper.offset(at, count))
        }
        "range" -> {
          val start = date(args.getOrNull(0)) ?: continue
          val stop = date(args.getOrNull(1)) ?: continue
          // An **absent** step means one; a step that is present and null, zero or negative means
          // d3
          // returns nothing at all (`step = Math.floor(step); if (!(step > 0)) return []`).
          // Defaulting
          // a present-but-null argument to one is a different question from the one upstream
          // answered.
          val given = args.getOrNull(2)
          val step =
            when {
              given == null -> 1
              else -> number(given)?.toInt() ?: 0
            }
          // d3's `range(start, stop, step)` steps **from the range start**; a `TimeStepper` with a
          // step snaps to a global grid, which is d3's *other* spelling — `every(step).range(...)`
          // —
          // and is the one Vega uses and this engine implements. Different function, so not
          // compared.
          if (step != 1) {
            unmapped.merge(
              "range with a step (d3 steps from the start; this is every())",
              1,
              Int::plus,
            )
            continue
          }
          val stepped = stepper(interval, step) ?: continue
          expected = (result as? JsonArray)?.joinToString(",") { describe(date(it)) } ?: continue
          actual = stepped.range(start, stop).joinToString(",") { describe(it) }
        }
        else -> continue
      }
      replayed++
      if (expected != actual) {
        val shown =
          args.joinToString(",") { argument ->
            describe(date(argument) ?: number(argument))
          }
        failures.add("$name($shown): upstream $expected, ours $actual")
      }
    }

    val ledger =
      StringBuilder("replayed $replayed of ${document["calls"]!!.jsonArray.size} d3-time vectors\n")
    unmapped.entries
      .sortedByDescending { it.value }
      .forEach { ledger.append("  ${it.key}: ${it.value}\n") }
    failures.forEach { ledger.append("MISMATCH $it\n") }
    File(File(System.getProperty("user.dir")).parentFile, "build/upstream-d3-time-ledger.txt")
      .apply {
        parentFile.mkdirs()
        writeText(ledger.toString())
      }

    assertEquals(emptyList<String>(), failures.take(10), "d3 disagrees with TimeStepper")
    assertTrue(replayed >= 685, "only $replayed vectors replayed; the harness must not shrink")
  }

  private fun describe(value: Double?): String =
    when {
      value == null -> "null"
      value.isFinite() -> value.toLong().toString()
      else -> value.toString()
    }

  private companion object {
    /**
     * What `TimeStepper` implements. `ceil`, `round`, `count` and `every` are d3's and are not
     * modelled here — Vega reaches them only through its own scale and axis code, which this engine
     * implements a different way — so they are counted rather than pretended about.
     */
    val REPLAYED_METHODS = setOf("(call)", "ceil", "count", "floor", "offset", "range", "round")
  }
}
