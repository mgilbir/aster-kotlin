package dev.aster.vega.scene

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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

  /** The primitives that are geometry rather than rasterisation; `intersectPath` is not. */
  private val INTERSECTS = setOf("intersectBoxLine", "intersectRule", "intersectPoint")

  /** A recorded box, which upstream writes as `{x1, y1, x2, y2}`. */
  private fun box(element: kotlinx.serialization.json.JsonElement?): RectD? {
    val o = element as? JsonObject ?: return null
    fun side(key: String) = (o[key] as? JsonPrimitive)?.doubleOrNull
    val x1 = side("x1") ?: return null
    val y1 = side("y1") ?: return null
    val x2 = side("x2") ?: return null
    val y2 = side("y2") ?: return null
    return RectD(x1, y1, x2, y2)
  }

  /** One `intersect*` vector, or null when it is not a shape this engine measures. */
  private fun intersects(fn: String, args: JsonArray?): Boolean? {
    if (args == null) return null
    fun field(of: JsonObject?, key: String) = (of?.get(key) as? JsonPrimitive)?.doubleOrNull
    return when (fn) {
      "intersectBoxLine" -> {
        val b = box(args.getOrNull(0)) ?: return null
        val numbers = (1..4).map { (args.getOrNull(it) as? JsonPrimitive)?.doubleOrNull }
        if (numbers.any { it == null }) return null
        MarkIntersect.boxLine(b, numbers[0]!!, numbers[1]!!, numbers[2]!!, numbers[3]!!)
      }
      "intersectRule" -> {
        val item = args.getOrNull(0) as? JsonObject ?: return null
        val b = box(args.getOrNull(1)) ?: return null
        val x = field(item, "x") ?: 0.0
        val y = field(item, "y") ?: 0.0
        MarkIntersect.rule(x, y, field(item, "x2") ?: x, field(item, "y2") ?: y, b)
      }
      "intersectPoint" -> {
        val item = args.getOrNull(0) as? JsonObject ?: return null
        val b = box(args.getOrNull(1)) ?: return null
        MarkIntersect.point(field(item, "x") ?: 0.0, field(item, "y") ?: 0.0, b)
      }
      else -> null
    }
  }

  @Test
  fun `vega-scenegraph's own path vectors replay against SvgPath`() {
    var replayed = 0
    val unmapped = mutableMapOf<String, Int>()
    val failures = mutableListOf<String>()

    for (vector in vectors) {
      // The `intersect*` primitives: whether a mark meets a rectangle, which is what a brush
      // selection asks of every mark it drags over.
      val intersecting = vector["fn"]?.jsonPrimitive?.content
      if (intersecting in INTERSECTS) {
        val args = vector["args"] as? JsonArray
        val expected = (vector["result"] as? JsonPrimitive)?.booleanOrNull
        val ours = intersects(intersecting!!, args)
        if (expected == null || ours == null) {
          unmapped.merge("$intersecting (not a box and an item)", 1, Int::plus)
          continue
        }
        replayed++
        if (expected != ours) failures.add("$intersecting($args): upstream $expected, ours $ours")
        continue
      }
      // `boundStroke` decides how far a stroke reaches past its geometry, which is what a mark's
      // bounds are made of — and bounds drive layout, autosize and clipping, so being short here
      // crops a chart rather than merely mismeasuring one.
      if (vector["fn"]?.jsonPrimitive?.content == "boundStroke") {
        val args = vector["args"] as? JsonArray
        val box = args?.getOrNull(0) as? JsonObject
        val item = args?.getOrNull(1) as? JsonObject
        val answer = vector["result"] as? JsonObject
        if (box == null || item == null || answer == null) {
          unmapped.merge("boundStroke (not a box and an item)", 1, Int::plus)
          continue
        }
        fun side(of: JsonObject, key: String) = (of[key] as? JsonPrimitive)?.doubleOrNull
        val x1 = side(box, "x1")
        val y1 = side(box, "y1")
        val x2 = side(box, "x2")
        val y2 = side(box, "y2")
        if (x1 == null || y1 == null || x2 == null || y2 == null) continue
        val stroke =
          (item["stroke"] as? JsonPrimitive)?.let {
            Stroke(
              paint = ScenePaint.Solid(SceneColor.parse("red")!!),
              width = side(item, "strokeWidth") ?: 1.0,
              cap =
                when ((item["strokeCap"] as? JsonPrimitive)?.content) {
                  "square" -> StrokeCap.SQUARE
                  "round" -> StrokeCap.ROUND
                  else -> StrokeCap.BUTT
                },
              join =
                when ((item["strokeJoin"] as? JsonPrimitive)?.content) {
                  "round" -> StrokeJoin.ROUND
                  "bevel" -> StrokeJoin.BEVEL
                  else -> StrokeJoin.MITER
                },
              miterLimit = side(item, "strokeMiterLimit") ?: 4.0,
              opacity = side(item, "strokeOpacity") ?: 1.0,
            )
          }
        // Upstream bounds a group, a rect and a rule *without* the join allowance and the
        // path-like marks with it, and the vector does not record which of the two callers it came
        // from. The recorded growth says which: anything wider than the cap allowance alone had the
        // join allowance applied.
        val opacity = side(item, "opacity") ?: 1.0
        val widened = stroke.wideningAt(opacity)
        val expected =
          listOf(side(answer, "x1"), side(answer, "y1"), side(answer, "x2"), side(answer, "y2"))
        val grew = x1 - (expected[0] ?: x1)
        val miter = widened != null && grew > widened.boundsExpansion() + 1e-9
        val e = widened?.boundsExpansion(miter) ?: 0.0
        replayed++
        val ours = listOf(x1 - e, y1 - e, x2 + e, y2 + e)
        if (expected.indices.any { kotlin.math.abs((expected[it] ?: 0.0) - ours[it]) > 1e-9 })
          failures.add("boundStroke($item): upstream $expected, ours $ours")
        continue
      }
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
    assertTrue(replayed >= 70, "only $replayed vectors replayed; the harness must not shrink")
  }

  private fun show(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}
