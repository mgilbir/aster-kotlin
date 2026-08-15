package dev.aster.vega.scene

import java.io.File
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
 * **vega-scenegraph's** `pathParse` tests, replayed against [SvgPath].
 *
 * Every `shape` mark, every custom `symbol` and every `path` in a specification arrives as an SVG
 * path string, so this parser decides whether those draw at all — and upstream's corpus is
 * precisely the grammar's traps: implicit repeats (`M0,0 1,1 2,2` is a move and two lines), a
 * relative run with no separators (`l.5.5.3.3`), `H`/`V` shorthands, a `z` in the middle of a path,
 * two `Z`s in a row, and numbers glued together by their signs (`M-1-1H1V1`).
 *
 * The two representations differ on purpose, so the comparison is on a **normalised** form.
 * Upstream's parser returns the raw segments with their letters intact; this one resolves them —
 * relative to absolute, `H`/`V` to lines — because everything downstream wants points rather than
 * grammar. The adapter therefore applies upstream's own resolution rules to its segments and
 * compares the result, which is the behaviour under test rather than the notation.
 */
class UpstreamPathParseVectorsTest {

  private val json = Json { ignoreUnknownKeys = true }

  private val vectors: List<JsonObject> by lazy {
    val file =
      File(
        File(System.getProperty("user.dir")).parentFile,
        "test-fixtures/upstream-vectors/vega-scenegraph.json",
      )
    assumeTrue(
      file.isFile,
      "no upstream vectors at ${file.path} — run scripts/record-upstream-vectors.sh to replay them",
    )
    json.parseToJsonElement(file.readText()).jsonObject["calls"]!!.jsonArray.map { it.jsonObject }
  }

  /**
   * Upstream's segments as absolute moves, lines and closes — or null when the path curves.
   *
   * A cubic, a quadratic or an arc is normalised differently here (arcs and quadratics become
   * cubics), so comparing those would compare two deliberate choices rather than one behaviour.
   */
  private fun normalise(segments: JsonArray): List<String>? {
    var cx = 0.0
    var cy = 0.0
    var startX = 0.0
    var startY = 0.0
    val out = mutableListOf<String>()
    for (segment in segments) {
      val parts = (segment as? JsonArray) ?: return null
      val command = (parts.firstOrNull() as? JsonPrimitive)?.content ?: return null
      val numbers = parts.drop(1).map { (it as? JsonPrimitive)?.doubleOrNull ?: return null }
      when (command) {
        "M",
        "m" -> {
          cx = if (command == "M") numbers[0] else cx + numbers[0]
          cy = if (command == "M") numbers[1] else cy + numbers[1]
          startX = cx
          startY = cy
          out += "M ${show(cx)} ${show(cy)}"
        }
        "L",
        "l" -> {
          cx = if (command == "L") numbers[0] else cx + numbers[0]
          cy = if (command == "L") numbers[1] else cy + numbers[1]
          out += "L ${show(cx)} ${show(cy)}"
        }
        "H",
        "h" -> {
          cx = if (command == "H") numbers[0] else cx + numbers[0]
          out += "L ${show(cx)} ${show(cy)}"
        }
        "V",
        "v" -> {
          cy = if (command == "V") numbers[0] else cy + numbers[0]
          out += "L ${show(cx)} ${show(cy)}"
        }
        "Z",
        "z" -> {
          cx = startX
          cy = startY
          out += "Z"
        }
        else -> return null
      }
    }
    return out
  }

  private fun ours(path: PathData): List<String>? =
    path.commands.map { command ->
      when (command) {
        is PathCommand.MoveTo -> "M ${show(command.x)} ${show(command.y)}"
        is PathCommand.LineTo -> "L ${show(command.x)} ${show(command.y)}"
        is PathCommand.Close -> "Z"
        else -> return null
      }
    }

  @Test
  fun `vega-scenegraph's own path vectors replay against SvgPath`() {
    var replayed = 0
    val unmapped = mutableMapOf<String, Int>()
    val failures = mutableListOf<String>()

    for (vector in vectors) {
      if (vector["fn"]?.jsonPrimitive?.content != "pathParse") {
        unmapped.merge(vector["fn"]?.jsonPrimitive?.content ?: "?", 1, Int::plus)
        continue
      }
      val source = (vector["args"]?.jsonArray?.getOrNull(0) as? JsonPrimitive)?.content ?: continue
      val segments = vector["result"] as? JsonArray
      if (segments == null) {
        unmapped.merge("pathParse (upstream refused the string)", 1, Int::plus)
        continue
      }
      val expected = normalise(segments)
      if (expected == null) {
        unmapped.merge("pathParse (curves and arcs are normalised differently here)", 1, Int::plus)
        continue
      }
      val actual = ours(SvgPath.parse(source).path)
      if (actual == null) {
        unmapped.merge("pathParse (this engine produced a curve)", 1, Int::plus)
        continue
      }
      replayed++
      if (expected != actual) {
        failures.add("pathParse(\"$source\"): upstream $expected, ours $actual")
      }
    }

    val ledger = StringBuilder("replayed $replayed of ${vectors.size} vega-scenegraph vectors\n")
    unmapped.entries
      .sortedByDescending { it.value }
      .forEach { ledger.append("  ${it.key}: ${it.value}\n") }
    failures.forEach { ledger.append("MISMATCH $it\n") }
    File(File(System.getProperty("user.dir")).parentFile, "build/upstream-pathparse-ledger.txt")
      .apply {
        parentFile.mkdirs()
        writeText(ledger.toString())
      }

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
        .filter { it["subject"]?.jsonPrimitive?.content == "SvgPath.parse" }
        .map { it["signature"]!!.jsonPrimitive.content }
    assertEquals(
      known.sorted(),
      failures.map { it.substringBefore(':') }.sorted(),
      "the set of path-parser divergences changed; update known-divergences.json",
    )
    assertTrue(replayed >= 18, "only $replayed vectors replayed; the harness must not shrink")
  }

  private fun show(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}
