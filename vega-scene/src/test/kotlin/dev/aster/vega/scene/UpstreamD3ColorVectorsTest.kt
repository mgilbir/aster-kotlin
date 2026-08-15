package dev.aster.vega.scene

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * **d3-color's** own `color()` tests, replayed against [SceneColor.parse].
 *
 * Every `fill` and `stroke` in a specification is a CSS colour string, so this parser sits under
 * the whole renderer — and d3's corpus is mostly the cases nobody writes deliberately: a four-digit
 * hex with alpha, percentages, `rgb(12,34,56,)` with a trailing comma, `#abcdef3` at seven digits,
 * names that do not exist. **Nineteen of the thirty-eight expect `null`**, which is the half a
 * hand-written test tends to skip: rejecting a malformed colour matters as much as parsing a good
 * one, because the alternative is a mark painted an arbitrary colour rather than left alone.
 */
class UpstreamD3ColorVectorsTest {

  private val json = Json { ignoreUnknownKeys = true }

  private val vectors: List<JsonObject> by lazy {
    val file =
      File(
        File(System.getProperty("user.dir")).parentFile,
        "test-fixtures/upstream-vectors/d3-color.json",
      )
    assumeTrue(
      file.isFile,
      "no upstream vectors at ${file.path} — run scripts/record-upstream-vectors.sh to replay them",
    )
    json.parseToJsonElement(file.readText()).jsonObject["calls"]!!.jsonArray.map { it.jsonObject }
  }

  /** A recorded number, including a NaN channel — d3 uses one for a component it could not read. */
  private fun number(element: kotlinx.serialization.json.JsonElement?): Double? =
    when (element) {
      is kotlinx.serialization.json.JsonPrimitive -> element.doubleOrNull
      is JsonObject -> if (element["\$"]?.jsonPrimitive?.content == "NaN") Double.NaN else null
      else -> null
    }

  @Test
  fun `d3-color's own parse vectors replay against SceneColor`() {
    var replayed = 0
    val unmapped = mutableMapOf<String, Int>()
    val failures = mutableListOf<String>()

    for (vector in vectors) {
      val fn = vector["fn"]?.jsonPrimitive?.content ?: continue
      if (fn != "color") {
        unmapped.merge("$fn (a colour space this engine does not model)", 1, Int::plus)
        continue
      }
      val argument = vector["args"]?.jsonArray?.getOrNull(0)
      val text =
        (argument as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
      if (text == null) {
        unmapped.merge("color (argument is not a string)", 1, Int::plus)
        continue
      }
      val result = vector["result"]
      val expected: String =
        when {
          result == null || result is JsonNull -> "null"
          result is JsonObject && result["r"] != null ->
            listOf("r", "g", "b", "opacity").joinToString(",") { show(number(result[it])) }
          else -> {
            // An `hsl(...)` string comes back as an Hsl object upstream; this engine keeps one
            // representation, so there is nothing to compare component by component.
            unmapped.merge("color (result is not RGB)", 1, Int::plus)
            continue
          }
        }
      // `SceneColor` keeps its channels in **0..1** and d3 keeps them in **0..255**, so the
      // comparison scales rather than pretending the two agree. Rounded to six decimals because a
      // percentage colour is fractional on both sides — `rgb(12%,...)` is 30.6, and 0.12 * 255 is
      // 30.599999999999998.
      val parsed = SceneColor.parse(text)
      val actual =
        parsed?.let {
          listOf(it.red * 255.0, it.green * 255.0, it.blue * 255.0, it.alpha).joinToString(",") { c
            ->
            show(c)
          }
        } ?: "null"
      replayed++
      if (expected != actual) failures.add("color(\"$text\"): upstream $expected, ours $actual")
    }

    val ledger = StringBuilder("replayed $replayed of ${vectors.size} d3-color vectors\n")
    unmapped.entries
      .sortedByDescending { it.value }
      .forEach { ledger.append("  ${it.key}: ${it.value}\n") }
    failures.forEach { ledger.append("MISMATCH $it\n") }
    File(File(System.getProperty("user.dir")).parentFile, "build/upstream-d3-color-ledger.txt")
      .apply {
        parentFile.mkdirs()
        writeText(ledger.toString())
      }

    // Pinned exactly, like the transform divergences: this parser accepts a superset of d3's, which
    // is right for rendering and wrong for `luminance()`. A new disagreement fails, and so does
    // fixing one without deleting its entry from `known-divergences.json`.
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
        .filter { it["transform"]?.jsonPrimitive?.content == "SceneColor.parse" }
        .map { it["signature"]!!.jsonPrimitive.content }
    assertEquals(
      known.sorted(),
      failures.map { it.substringBefore(':') }.sorted(),
      "the set of colour divergences changed; update known-divergences.json",
    )
    assertTrue(replayed >= 30, "only $replayed vectors replayed; the harness must not shrink")
  }

  private fun show(value: Double?): String =
    when {
      value == null -> "null"
      value.isNaN() -> "NaN"
      else -> {
        val rounded = kotlin.math.round(value * 1_000_000.0) / 1_000_000.0
        if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString()
        else rounded.toString()
      }
    }
}
