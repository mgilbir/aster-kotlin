package dev.aster.vega.model

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
 * **d3-dsv's** own tests, replayed against [DelimitedText].
 *
 * `vega-loader` reads every CSV and TSV through `dsvFormat(delimiter).parse`, so this is the code
 * path between a `"url"` and the first datum — and its failures are the quiet kind. A comma inside
 * an unquoted label does not drop a row; it shifts every later column of that row, and the chart
 * draws from data that is misaligned rather than missing.
 *
 * d3's corpus is almost entirely the awkward cases: a quote in the middle of a field, a doubled
 * `""` standing for one quote, a field containing the delimiter, a row shorter than the header, and
 * the three line endings.
 *
 * `autoType` is counted rather than replayed, and not because it is hard: **`vega-loader` does not
 * use it.** Vega infers types with its own `inferTypes`, driven by `format.parse`, so d3's
 * inference rules are not this engine's to match.
 */
class UpstreamD3DsvVectorsTest {

  private val json = Json { ignoreUnknownKeys = true }

  private val vectors: List<JsonObject> by lazy {
    val file =
      File(
        File(System.getProperty("user.dir")).parentFile,
        "test-fixtures/upstream-vectors/d3-dsv.json",
      )
    assumeTrue(
      file.isFile,
      "no upstream vectors at ${file.path} — run scripts/record-upstream-vectors.sh to replay them",
    )
    json.parseToJsonElement(file.readText()).jsonObject["calls"]!!.jsonArray.map { it.jsonObject }
  }

  private fun text(element: kotlinx.serialization.json.JsonElement?): String? =
    (element as? JsonPrimitive)?.takeIf { it.isString }?.content

  /** A recorded cell, as text: a string, a number the way JavaScript writes it, or a boolean. */
  private fun cell(element: kotlinx.serialization.json.JsonElement?): String? =
    when (element) {
      null,
      is JsonNull -> ""
      is JsonPrimitive ->
        when {
          element.isString -> element.content
          element.doubleOrNull != null -> Decimals.jsString(element.doubleOrNull!!)
          else -> element.content
        }
      else -> null
    }

  @Test
  fun `d3-dsv's own vectors replay against the delimited reader`() {
    var replayed = 0
    val unmapped = mutableMapOf<String, Int>()
    val failures = mutableListOf<String>()
    val tab = '\t'

    for (vector in vectors) {
      val fn = vector["fn"]?.jsonPrimitive?.content ?: continue
      val args = vector["args"] as? JsonArray
      if (args == null) {
        unmapped.merge(fn, 1, Int::plus)
        continue
      }
      // A row-conversion function — `tsvParse(text, row => ...)` — decides what each row becomes,
      // and a vector records the function's name rather than its body. There is nothing to replay.
      if (args.size > 1 && (fn == "tsvParse" || fn == "tsvParseRows")) {
        unmapped.merge("$fn (a row-conversion function)", 1, Int::plus)
        continue
      }
      val expected: String
      val actual: String
      when (fn) {
        "tsvParse" -> {
          val source = text(args.getOrNull(0)) ?: continue
          val rows = (vector["result"] as? JsonArray) ?: continue
          // Compared as text so a missing key and an empty one cannot look alike.
          expected =
            rows.joinToString(" / ") { row ->
              (row as? JsonObject)?.entries?.joinToString(",") { "${it.key}=${cell(it.value)}" }
                ?: "?"
            }
          actual =
            DelimitedText.parse(source, tab).joinToString(" / ") { row ->
              (row as VegaValue.Obj).fields.entries.joinToString(",") {
                "${it.key}=${(it.value as VegaValue.Str).value}"
              }
            }
        }
        "tsvParseRows" -> {
          val source = text(args.getOrNull(0)) ?: continue
          val rows = (vector["result"] as? JsonArray) ?: continue
          if (rows.any { it !is JsonArray }) {
            // A row-conversion function was passed, whose body a vector cannot record.
            unmapped.merge("$fn (rows came back through a conversion function)", 1, Int::plus)
            continue
          }
          expected =
            rows.joinToString(" / ") { row ->
              (row as JsonArray).joinToString(",") { "${cell(it)}" }
            }
          actual = DelimitedText.parseRows(source, tab).joinToString(" / ") { it.joinToString(",") }
        }
        "tsvFormatRow" -> {
          val cells = (args.getOrNull(0) as? JsonArray)?.map { cell(it) } ?: continue
          if (cells.any { it == null }) {
            unmapped.merge("$fn (a cell is not a plain value)", 1, Int::plus)
            continue
          }
          expected = text(vector["result"]) ?: continue
          actual = DelimitedText.formatRow(cells.filterNotNull(), tab)
        }
        "tsvFormatRows" -> {
          val rows =
            (args.getOrNull(0) as? JsonArray)?.map { row -> (row as? JsonArray)?.map { cell(it) } }
              ?: continue
          if (rows.any { it == null || it.any { c -> c == null } }) {
            unmapped.merge("$fn (a cell is not a plain value)", 1, Int::plus)
            continue
          }
          expected = text(vector["result"]) ?: continue
          actual = DelimitedText.formatRows(rows.map { it!!.filterNotNull() }, tab)
        }
        "tsvFormat" -> {
          val columnNames =
            (args.getOrNull(1) as? JsonArray)
              ?.map { text(it) }
              ?.also {
                if (it.any { name -> name == null }) {
                  unmapped.merge("$fn (a column name is not a string)", 1, Int::plus)
                  return@also
                }
              }
          if (args.size > 1 && columnNames == null) {
            unmapped.merge("$fn (the second argument is not a column list)", 1, Int::plus)
            continue
          }
          val rows = (args.getOrNull(0) as? JsonArray)?.map { it as? JsonObject } ?: continue
          if (rows.any { it == null }) {
            unmapped.merge("$fn (a row is not an object)", 1, Int::plus)
            continue
          }
          val objects = rows.map { row ->
            VegaValue.Obj(
              LinkedHashMap<String, VegaValue>().apply {
                row!!.forEach { (key, value) -> put(key, VegaValue.Str(cell(value) ?: "")) }
              }
            )
          }
          expected = text(vector["result"]) ?: continue
          actual = DelimitedText.format(objects, tab, columnNames?.filterNotNull())
        }
        else -> {
          unmapped.merge(
            if (fn == "autoType") "autoType (vega-loader infers types itself)" else fn,
            1,
            Int::plus,
          )
          continue
        }
      }
      replayed++
      if (expected != actual)
        failures.add("$fn(${args.getOrNull(0)}): upstream <$expected>, ours <$actual>")
    }

    val ledger = StringBuilder("replayed $replayed of ${vectors.size} d3-dsv vectors\n")
    unmapped.entries
      .sortedByDescending { it.value }
      .forEach { ledger.append("  ${it.key}: ${it.value}\n") }
    failures.forEach { ledger.append("MISMATCH $it\n") }
    File(File(System.getProperty("user.dir")).parentFile, "build/upstream-d3-dsv-ledger.txt")
      .apply {
        parentFile.mkdirs()
        writeText(ledger.toString())
      }

    assertEquals(emptyList<String>(), failures.take(12), "d3-dsv disagrees with this engine")
    assertTrue(replayed >= 40, "only $replayed vectors replayed; the harness must not shrink")
  }
}
