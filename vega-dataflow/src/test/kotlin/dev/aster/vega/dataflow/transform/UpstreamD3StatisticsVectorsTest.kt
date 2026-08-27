package dev.aster.vega.dataflow.transform

import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.field
import dev.aster.vega.model.isMissing
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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
 * **d3-array's** summary statistics, replayed against this engine's aggregate operations.
 *
 * `sum`, `mean`, `median`, `variance`, `deviation`, `extent`, `min`, `max` and `quantile` are the
 * arithmetic behind every `aggregate` transform, every `joinaggregate`, and the sorted domains a
 * discrete scale derives — and upstream's corpus is mostly the awkward inputs: an empty array, a
 * single value, arrays holding `null`, `NaN` and strings that look like numbers, and the
 * floating-point cases where summing in a different order gives a different last digit.
 *
 * Upstream takes an array; this engine takes rows and a field, which is the same question asked of
 * a different shape — so each array becomes one row per value and the operation reads that field.
 */
class UpstreamD3StatisticsVectorsTest {

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

  private fun scalar(element: kotlinx.serialization.json.JsonElement?): VegaValue? =
    when (element) {
      is JsonNull -> VegaValue.Null
      is JsonPrimitive ->
        when {
          element.isString -> VegaValue.Str(element.content)
          element.doubleOrNull != null -> VegaValue.Num(element.doubleOrNull!!)
          element.content == "true" -> VegaValue.Bool(true)
          element.content == "false" -> VegaValue.Bool(false)
          else -> null
        }
      is JsonObject ->
        when (element["\$"]?.jsonPrimitive?.content) {
          "NaN" -> VegaValue.Num(Double.NaN)
          "Infinity" -> VegaValue.Num(Double.POSITIVE_INFINITY)
          "-Infinity" -> VegaValue.Num(Double.NEGATIVE_INFINITY)
          "undefined" -> VegaValue.Null
          else -> null
        }
      else -> null
    }

  private fun show(value: Double?): String =
    when {
      value == null -> "null"
      value.isNaN() -> "NaN"
      !value.isFinite() -> value.toString()
      else -> {
        val rounded = kotlin.math.round(value * 1_000_000_000.0) / 1_000_000_000.0
        if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString()
        else rounded.toString()
      }
    }

  @Test
  fun `d3-array's summary statistics replay against the aggregate operations`() {
    var replayed = 0
    val unmapped = mutableMapOf<String, Int>()
    val failures = mutableListOf<String>()

    for (vector in vectors) {
      val fn = vector["fn"]?.jsonPrimitive?.content ?: continue
      val op = OPERATIONS[fn]
      if (op == null && fn != "extent") {
        unmapped.merge(fn, 1, Int::plus)
        continue
      }
      val args = vector["args"] as? JsonArray
      val values = (args?.getOrNull(0) as? JsonArray)?.map { scalar(it) }
      if (args == null || values == null || values.any { it == null }) {
        // An accessor, an iterator, or a value shape a row cannot hold: upstream's `sum(data, d =>
        // d.amount)` reads a field through a function, which a vector records without its body.
        unmapped.merge("$fn (an argument is not a plain array of values)", 1, Int::plus)
        continue
      }
      if (args.size > 1 && fn != "quantile") {
        unmapped.merge("$fn (an accessor or a second argument)", 1, Int::plus)
        continue
      }
      // d3's `min`, `max` and `extent` compare with JavaScript's `<`, which is lexicographic for
      // strings — `min(["20", "3"])` is `"20"` — where this engine's aggregates read numbers. A
      // string column is a different question, not a divergence, so it is counted.
      if (values.any { it is VegaValue.Str }) {
        unmapped.merge(
          "$fn (values are strings, compared lexicographically upstream)",
          1,
          Int::plus,
        )
        continue
      }
      val rows = values.filterNotNull().map { VegaValue.Obj(linkedMapOf("v" to it)) }

      val expected: String
      val actual: String
      if (fn == "extent") {
        val result = vector["result"] as? JsonArray
        // An extent of nothing is `[undefined, undefined]` upstream, which is "no answer" rather
        // than a pair of NaNs.
        val undefined =
          result?.all { (it as? JsonObject)?.get("\$")?.jsonPrimitive?.content == "undefined" }
            ?: true
        expected =
          if (undefined) "null"
          else result.joinToString(",") { show((scalar(it) ?: VegaValue.Null).asDouble()) }
        val low = aggregateOver(AggregateOp.MIN, "v", rows).asDouble()
        val high = aggregateOver(AggregateOp.MAX, "v", rows).asDouble()
        // An extent over rows that hold no *readable* number — a lone null, a lone NaN — has no
        // answer either, which upstream writes as `[undefined, undefined]` and this renders as null
        // rather than as a pair of NaNs.
        actual =
          if (rows.isEmpty() || (low.isNaN() && high.isNaN())) "null"
          else "${show(low)},${show(high)}"
      } else if (fn == "quantile") {
        val p = (args.getOrNull(1) as? JsonPrimitive)?.doubleOrNull
        if (p == null) {
          unmapped.merge("quantile (the probability is not a number)", 1, Int::plus)
          continue
        }
        expected = show(scalar(vector["result"])?.asDouble())
        actual =
          show(
            Distributions.quantiles(rows.map { it.field("v").asDouble() }, listOf(p)).firstOrNull()
          )
      } else if (op == AggregateOp.SUM && rows.all { it.field("v").isMissing }) {
        // d3's `sum` and Vega's aggregate `sum` are **not** the same function over nothing.
        // `d3.sum` starts an accumulator at 0 and skips what it cannot read, so a column of nulls
        // sums to 0; Vega's cell guards every numeric operation with `m.valid ? … : undefined`, so
        // that group has no `sum` property at all. Probed both ways. This engine implements the
        // aggregate, so what is asserted here is that it answers *nothing* — a 0 would pass an
        // `isValid` filter that upstream's answer does not.
        expected = "no answer"
        actual =
          if (aggregateOver(op, "v", rows) == VegaValue.Null) "no answer"
          else show(aggregateOver(op, "v", rows).asDouble())
      } else {
        expected = show(scalar(vector["result"])?.asDouble())
        actual = show(aggregateOver(op!!, "v", rows).asDouble())
      }
      replayed++
      if (expected != actual)
        failures.add("$fn(${values.size} values): upstream $expected, ours $actual")
    }

    val ledger =
      StringBuilder("replayed $replayed of ${vectors.size} d3-array statistics vectors\n")
    unmapped.entries
      .sortedByDescending { it.value }
      .forEach { ledger.append("  ${it.key}: ${it.value}\n") }
    failures.forEach { ledger.append("MISMATCH $it\n") }
    File(File(System.getProperty("user.dir")).parentFile, "build/upstream-d3-statistics-ledger.txt")
      .apply {
        parentFile.mkdirs()
        writeText(ledger.toString())
      }

    assertEquals(emptyList<String>(), failures.take(12), "d3-array disagrees with the aggregates")
    assertTrue(replayed >= 100, "only $replayed vectors replayed; the harness must not shrink")
  }

  private companion object {
    /**
     * d3's name for a summary, and this engine's operation for it.
     *
     * `deviation` is a *sample* standard deviation and `variance` a sample variance — the `n - 1`
     * denominators — which are `stdev` and `variance` here; the population forms are separate
     * operations in both.
     */
    val OPERATIONS =
      mapOf(
        "sum" to AggregateOp.SUM,
        "mean" to AggregateOp.MEAN,
        "median" to AggregateOp.MEDIAN,
        "variance" to AggregateOp.VARIANCE,
        "deviation" to AggregateOp.STDEV,
        "min" to AggregateOp.MIN,
        "max" to AggregateOp.MAX,
      )
  }
}
