package dev.aster.vega.dataflow.transform

import java.io.File
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * **vega-time's** own `timeFloor(units, step)` and `utcFloor` tests, replayed against the floor the
 * `timeunit` transform buckets with.
 *
 * That floor is the transform: every bucket a chart draws is its answer. `UpstreamTimeVectorsTest`
 * cannot reach it from `vega-model`, and so for as long as that was the only replay these 90
 * vectors were counted as "a function result" and skipped — including every `isoweek` floor Vega
 * 6.4.0 added, and the stepped `["year", "week"]` floors this engine had been answering unstepped.
 */
class UpstreamTimeFloorVectorsTest {

  private val json = Json { ignoreUnknownKeys = true }

  private val vectors: List<JsonObject> by lazy {
    val file =
      File(
        File(System.getProperty("user.dir")).parentFile,
        "test-fixtures/upstream-vectors/vega-time.json",
      )
    assumeTrue(
      file.isFile,
      "no upstream vectors at ${file.path} — run scripts/record-upstream-vectors.sh to replay them",
    )
    json.parseToJsonElement(file.readText()).jsonObject["calls"]!!.jsonArray.map { it.jsonObject }
  }

  private fun millis(value: kotlinx.serialization.json.JsonElement?): Double? =
    (value as? JsonObject)?.get("epochMillis")?.jsonPrimitive?.doubleOrNull

  @Test
  fun `upstream's own unit floors replay against the timeunit transform`() {
    var replayed = 0
    val failures = mutableListOf<String>()
    for (vector in vectors) {
      val fn = vector["fn"]!!.jsonPrimitive.content
      if (fn != "timeFloor()" && fn != "utcFloor()") continue
      val built = vector["constructedWith"] as? JsonArray ?: continue
      val units = (built[0] as JsonArray).map { it.jsonPrimitive.content }.toSet()
      val step = built.getOrNull(1)?.jsonPrimitive?.intOrNull ?: 1
      val from = millis(vector["args"]!!.jsonArray.firstOrNull()) ?: continue
      val expected = millis(vector["result"]) ?: continue
      val zone = if (fn == "utcFloor()") TimeZone.UTC else TimeZone.of(TEST_ZONE)
      val actual = TimeUnitTransform.floor(from, units, zone, step)
      replayed++
      if (actual != expected) {
        failures.add("$fn($units, $step)($from): expected $expected, got $actual")
      }
    }
    println("replayed $replayed vega-time unit floors")
    assertEquals(emptyList<String>(), failures, "upstream disagrees with this implementation")
    // 90 as recorded from Vega 6.4.0. Never lowered to make a change green.
    assertTrue(replayed >= 90, "only $replayed floors replayed; the harness must not shrink")
  }

  private companion object {
    /** The zone `build.gradle.kts` pins for every test, and the one the recorder pins for Node. */
    const val TEST_ZONE = "Europe/Amsterdam"
  }
}
