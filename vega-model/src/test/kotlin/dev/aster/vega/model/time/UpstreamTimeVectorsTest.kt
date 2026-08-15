package dev.aster.vega.model.time

import java.io.File
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Vega's **own** `vega-time` tests, replayed against this implementation.
 *
 * Not a transcription. `oracle-js/src/record-upstream-tests.mjs` runs upstream's test files against
 * the installed Vega 6.3.1 with the package's exports wrapped, and writes every call it makes —
 * with upstream's actual answer — to `test-fixtures/upstream-vectors/vega-time.json`. So the inputs
 * are the ones Vega's authors chose, and the expectations are what Vega really returns rather than
 * what its assertions happen to check: several of those are deliberately loose where an exact value
 * is what a port needs.
 *
 * The point of this shape is a version upgrade. Re-run the recorder against a newer checkout and
 * the diff is *data*: a changed number in a JSON file, not a hand-edit spread across Kotlin tests.
 *
 * **Coverage is asserted, not assumed.** Every vector is either replayed or counted as unmapped
 * with its function named, and the test fails if the number replayed drops. A harness that quietly
 * skips what it cannot do would report a pass for a function nobody had implemented — which is the
 * same failure as a gate that never runs.
 */
class UpstreamTimeVectorsTest {

  private val json = Json { ignoreUnknownKeys = true }

  private val vectors: List<JsonObject> by lazy {
    val file =
      File(
        File(System.getProperty("user.dir")).parentFile,
        "test-fixtures/upstream-vectors/vega-time.json",
      )
    assertTrue(file.isFile, "missing ${file.path}; regenerate with record-upstream-tests.mjs")
    json.parseToJsonElement(file.readText()).jsonObject["calls"]!!.jsonArray.map { it.jsonObject }
  }

  /** A recorded `Date` argument, as epoch milliseconds. */
  private fun date(value: JsonElementLike): Double? =
    (value as? JsonObject)?.get("epochMillis")?.jsonPrimitive?.doubleOrNull

  private fun number(value: JsonElementLike): Double? = (value as? JsonPrimitive)?.doubleOrNull

  private fun text(value: JsonElementLike): String? = (value as? JsonPrimitive)?.contentOrNull

  private fun stepper(unit: String, step: Int, utc: Boolean): TimeStepper? {
    val (interval, unitStep) = TimeInterval.forUnit(unit) ?: return null
    return TimeStepper(
      interval,
      unitStep * step,
      if (utc) TimeZone.UTC else TimeZone.of(TEST_ZONE),
    )
  }

  @Test
  fun `upstream's own time vectors replay against this implementation`() {
    var replayed = 0
    val unmapped = mutableMapOf<String, Int>()
    val failures = mutableListOf<String>()

    for (vector in vectors) {
      val fn = vector["fn"]!!.jsonPrimitive.content
      val args = vector["args"]!!.jsonArray.map { it.asLike() }
      val result = vector["result"]?.asLike()
      // A recorded result that is a *function* — `timeFloor('year')` curries one — has nothing to
      // compare; upstream's own tests call it and those calls are recorded separately. A `date`
      // result is a number in disguise and is compared like one.
      val marker = (result as? JsonObject)?.get("\$")?.jsonPrimitive?.content
      if (marker != null && marker != "date") {
        unmapped.merge("$fn ($marker result)", 1, Int::plus)
        continue
      }
      val utc = fn.startsWith("utc")
      val actual: Any? =
        when (fn) {
          "timeUnitSpecifier" -> {
            val units = (args[0] as? JsonArray)?.map { it.jsonPrimitive.content } ?: continue
            // The second argument is real and was being dropped: upstream's tests pass a table of
            // *overrides*, which is how a chart shortens one unit without restating the rest. This
            // harness caught its own adapter before it caught anything else.
            // A null override is kept, not dropped: it *suppresses* an entry upstream rather than
            // falling back to the default, and dropping it here is what hid the difference.
            val overrides: Map<String, String?> =
              (args.getOrNull(1) as? JsonObject)?.mapValues { (_, value) ->
                value.jsonPrimitive.contentOrNull
              } ?: emptyMap()
            TimeUnits.specifier(units, overrides)
          }
          "timeOffset",
          "utcOffset" -> {
            val unit = text(args[0]) ?: continue
            val from = date(args[1]) ?: continue
            val count = args.getOrNull(2)?.let { number(it) }?.toInt() ?: 1
            stepper(unit, 1, utc)?.offset(from, count)
          }
          "timeFloor",
          "utcFloor" -> {
            val unit = text(args[0]) ?: continue
            val from = date(args[1]) ?: continue
            stepper(unit, 1, utc)?.floor(from)
          }
          "timeSequence",
          "utcSequence" -> {
            val unit = text(args[0]) ?: continue
            val start = date(args[1]) ?: number(args[1]) ?: continue
            val stop = date(args[2]) ?: number(args[2]) ?: continue
            val step = args.getOrNull(3)?.let { number(it) }?.toInt() ?: 1
            stepper(unit, step, utc)?.range(start, stop)
          }
          else -> {
            unmapped.merge(fn, 1, Int::plus)
            continue
          }
        }
      if (actual == null) {
        unmapped.merge("$fn (no interval for that unit)", 1, Int::plus)
        continue
      }
      replayed++
      val expected = if (marker == "date") describe(date(result)) else describe(result)
      val got = describe(actual)
      if (expected != got)
        failures.add(
          "$fn(${args.joinToString(", ") { describe(it) }}): expected $expected, got $got"
        )
    }

    println("replayed $replayed of ${vectors.size} vega-time vectors")
    unmapped.entries
      .sortedByDescending { it.value }
      .forEach { println("  unmapped ${it.key}: ${it.value}") }
    assertEquals(
      emptyList<String>(),
      failures.take(12),
      "upstream disagrees with this implementation",
    )
    // The floor is what stops this becoming a test that passes by skipping: 281 of upstream's 460
    // recorded calls are replayed today, and the rest are named above rather than forgotten —
    // `detectTimeUnits`, `timeBin`, `timeUnits`, `week`, `dayofyear`, and the curried forms whose
    // result is a *function*. Raise this as those are mapped; never lower it to make a change
    // green.
    assertTrue(replayed >= 281, "only $replayed vectors replayed; the harness must not shrink")
  }

  /** A stable rendering for comparison: dates and numbers both become their millisecond value. */
  private fun describe(value: Any?): String =
    when (value) {
      null -> "null"
      is Double ->
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
      is List<*> -> value.joinToString(",", "[", "]") { describe(it) }
      is JsonArray ->
        value.joinToString(",", "[", "]") { element ->
          describe(date(element.asLike()) ?: element.asLike().let { number(it) ?: text(it) })
        }
      is JsonObject -> describe(date(value) ?: value.toString())
      is JsonPrimitive -> value.contentOrNull ?: "null"
      else -> value.toString()
    }

  private companion object {
    /** The zone `build.gradle.kts` pins for every test, and the one the recorder pins for Node. */
    const val TEST_ZONE = "Europe/Amsterdam"
  }
}

private typealias JsonElementLike = kotlinx.serialization.json.JsonElement

private fun kotlinx.serialization.json.JsonElement.asLike(): JsonElementLike = this
