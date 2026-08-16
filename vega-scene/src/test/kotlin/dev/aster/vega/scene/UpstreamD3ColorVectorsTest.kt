package dev.aster.vega.scene

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

  /**
   * One `hsl`/`lab`/`hcl` vector: the empty string when it agrees, a description when it does not,
   * and null when it is not a conversion of a single colour.
   *
   * Only the **one-argument** form is a conversion — `hcl("#abc")`. Given three numbers, d3 is
   * building a colour in that space rather than reading one, which this engine spells as `fromHcl`
   * and answers in RGB, so there is nothing component-wise to compare.
   *
   * An achromatic colour has **no hue**: d3 answers NaN for the hue of black or grey, because the
   * angle is undefined when the chroma is zero, and a ramp through it has to hold the other
   * endpoint's hue rather than swing through red.
   */
  private fun replayConversion(fn: String, vector: JsonObject): String? {
    val args = vector["args"] as? JsonArray ?: return null
    if (args.size != 1) return null
    val text = (args[0] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val result = vector["result"] as? JsonObject ?: return null
    val colour = SceneColor.parse(text) ?: return null

    val keys =
      if (fn == "hsl") listOf("h", "s", "l")
      else if (fn == "lab") listOf("l", "a", "b") else listOf("h", "c", "l")
    val ours =
      when (fn) {
        "hsl" -> ColorSpaces.toHsl(colour).let { listOf(it.hue, it.saturation, it.lightness) }
        "lab" -> ColorSpaces.toLab(colour).let { listOf(it.lightness, it.a, it.b) }
        else -> ColorSpaces.toHcl(colour).let { listOf(it.hue, it.chroma, it.lightness) }
      }
    // A component the recording could not render is not comparable; a recorded **NaN** is, and is
    // the interesting case — `number` decodes that marker.
    val expected = keys.map { number(result[it]) ?: return null }
    val near =
      expected.indices.all {
        val e = expected[it]
        val a = ours[it]
        (e.isNaN() && a.isNaN()) ||
          kotlin.math.abs(e - a) <= 1e-9 * kotlin.math.max(1.0, kotlin.math.abs(e))
      }
    return if (near) ""
    else
      "$fn(\"$text\"): upstream ${expected.joinToString(",") { show(it) }}, " +
        "ours ${ours.joinToString(",") { show(it) }}"
  }

  @Test
  fun `d3-color's own parse vectors replay against SceneColor`() {
    var replayed = 0
    val unmapped = mutableMapOf<String, Int>()
    val failures = mutableListOf<String>()

    for (vector in vectors) {
      val fn = vector["fn"]?.jsonPrimitive?.content ?: continue
      // `hsl`, `lab` and `hcl` convert a colour *into* another space, which this engine has had all
      // along in `ColorSpaces` — it is the arithmetic under every non-RGB ramp, and it was reaching
      // the corpus only indirectly, through the interpolators. The label here said the spaces were
      // not modelled; what was not modelled was the adapter.
      if (fn == "hsl" || fn == "lab" || fn == "hcl") {
        val outcome = replayConversion(fn, vector)
        if (outcome == null) unmapped.merge("$fn (not a conversion of one colour)", 1, Int::plus)
        else {
          replayed++
          if (outcome.isNotEmpty()) failures.add(outcome)
        }
        continue
      }
      if (fn != "color") {
        unmapped.merge("$fn (not a colour this engine keeps)", 1, Int::plus)
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
      // Compared against the **strict** acceptance, because this corpus is d3's parser and that is
      // this engine's equivalent of it. [SceneColor.parse] deliberately takes a superset for the
      // renderer's sake, and testing it here was comparing two functions that were never meant to
      // agree — thirteen pinned "divergences" that were really a mismatched comparison.
      val parsed = if (SceneColor.acceptedByD3(text)) SceneColor.parse(text) else null
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
        // Both subjects are this file's: the parser and the conversions read the same channels,
        // and a transparent colour differs in both for one reason.
        .filter {
          it["subject"]?.jsonPrimitive?.content in setOf("SceneColor.parse", "ColorSpaces.toHsl")
        }
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
