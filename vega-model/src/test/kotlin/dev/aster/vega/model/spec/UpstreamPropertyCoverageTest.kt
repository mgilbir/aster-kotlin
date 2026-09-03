package dev.aster.vega.model.spec

import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Which of upstream's own properties this engine consumes, asked of the engine rather than of a
 * list.
 *
 * `SUPPORTED_FEATURES.md` used to answer this in prose, and the prose went stale in the direction
 * nobody checks: it said the axis had "forty-odd properties this engine does not honour" when the
 * real number is **zero**, and it called `timeParse`, the colour constructors and the date
 * functions "not implemented" while every one of them evaluates. Each note cited a reason that had
 * since stopped being true — "needs time scales first", "a scale registry the evaluator cannot
 * reach yet".
 *
 * So the inventory is derived, and derived from *behaviour* rather than from the `*_CONSUMED` sets.
 * Reading those sets is what produced the wrong number in the first place: `AXIS_CONSUMED` is
 * `setOf(…) + guideStyleKeys("label", "tick", "grid", "domain", "title")`, sixty names generated
 * programmatically, and a reader — human or script — who stops at the first closing bracket
 * undercounts by sixty. Asking the parser is immune to that: it either warns about a property or it
 * does not.
 *
 * The oracle is the engine's own contract, ADR 0011: nothing a specification asks for is dropped
 * without saying so. A property that draws no `PARSE_UNKNOWN_PROPERTY` is one the parser consumed.
 *
 * Writes `build/upstream-coverage.json`, which `scripts/capabilities.py` renders into
 * `docs/upstream-coverage.md`. The file is an output of this test for the same reason the feature
 * table's status column is an output of a run: a coverage number nobody re-derives is a coverage
 * number that drifts.
 */
class UpstreamPropertyCoverageTest {

  /** Upstream's own schema, from the pinned install. Absent in a checkout with no `npm ci`. */
  private val schema = File("../oracle-js/node_modules/vega/build/vega-schema.json")

  /**
   * The kinds worth asking about: those the parser has a consumed-set for and the schema describes.
   *
   * Each entry says how to wrap one property so it reaches the parser at all — an axis needs a
   * scale to point at, a legend needs an encoding channel, and a mark needs a type.
   */
  private val kinds =
    listOf(
      Kind("axis", "axes") { """{"scale": "x", "orient": "bottom", $it}""" },
      Kind("legend", "legends") { """{"fill": "c", $it}""" },
      Kind("projection", "projections") { """{"name": "p", "type": "mercator", $it}""" },
      // `mark` is the schema's own container: type, from, encode and the group-level keys.
      Kind("mark", "marks") { """{"type": "rect", $it}""" },
      // The visual channels, which is the largest surface upstream documents and the one a
      // specification actually writes. Nested inside a mark's `enter`, which is where they live.
      Kind("encodeEntry", "marks") { """{"type": "rect", "encode": {"enter": {$it}}}""" },
    )

  private class Kind(val definition: String, val key: String, val wrap: (String) -> String)

  @Test
  fun `every upstream property is consumed or reported`() {
    assumeTrue(
      schema.exists(),
      "no pinned vega schema; run scripts/check.sh or npm ci in oracle-js",
    )
    val root = VegaJson.parse(schema.readText()) as VegaValue.Obj

    val report = StringBuilder("{\n  \"kinds\": [\n")
    var first = true
    for (kind in kinds) {
      val properties = propertyNames(root, kind.definition)
      if (properties.isEmpty()) continue
      val unhandled = properties.filter { property -> reportsUnhandled(kind, property) }.sorted()
      if (!first) report.append(",\n")
      first = false
      report.append(
        """    {"kind": "${kind.definition}", "upstream": ${properties.size}, """ +
          """"consumed": ${properties.size - unhandled.size}, """ +
          """"unconsumed": [${unhandled.joinToString(", ") { "\"$it\"" }}]}"""
      )
      // A regression guard as well as an inventory: a property that stops being consumed shows up
      // here rather than in a chart that quietly drew the default.
      assertTrue(
        unhandled.size <= FLOORS.getValue(kind.definition),
        "${kind.definition}: ${unhandled.size} unconsumed properties, expected at most " +
          "${FLOORS.getValue(kind.definition)} — $unhandled",
      )
    }
    report.append("\n  ]\n}\n")
    File("build/upstream-coverage.json").apply { parentFile.mkdirs() }.writeText(report.toString())
  }

  /**
   * Whether the parser reports [property] as one it did not consume, **whatever value it is
   * given**.
   *
   * The value matters, which is the trap this method exists to avoid. Consumption is
   * type-sensitive: a parser branch that reads `labelColor` as a string does not recognise
   * `"labelColor": 1`, so a probe that passes one value per property reports nineteen axis
   * properties as unconsumed that a chart consumes perfectly well. The first draft of this test did
   * exactly that and was believed until a hand-written specification with `"labelColor": "red"`
   * produced no diagnostic at all.
   *
   * So a property counts as unconsumed only when *every* candidate value is reported. The
   * candidates cover the shapes a guide property takes — a colour word, a number, a flag, a dash
   * array, and a signal object — which is cheaper and steadier than interpreting the schema's own
   * type union, where half the entries are `$ref`s into definitions that are themselves unions.
   *
   * **What this therefore does and does not measure**, stated because the difference is easy to
   * overclaim. It measures whether a property *name* is recognised somewhere for some plausible
   * value. It does **not** measure whether the property is honoured correctly — that is the
   * differential corpus's job, which compares whole scenes against upstream.
   *
   * A consequence worth knowing: a name read by more than one path stays recognised when one of
   * them stops reading it. Deleting `labelOffset` from `AXIS_CONSUMED` leaves a numeric
   * `labelOffset` consumed elsewhere and this test still passes. So it is a floor on the *surface*,
   * not a proof of behaviour, and [`a property nothing can consume is counted`] is what keeps the
   * floor from being vacuous.
   */
  private fun reportsUnhandled(kind: Kind, property: String): Boolean =
    CANDIDATE_VALUES.all { value ->
      reportsUnhandled(kind, property, value)
    }

  private fun reportsUnhandled(kind: Kind, property: String, value: String): Boolean {
    // **One `marks` key, not two.** The template used to carry a trailing `"marks": []` whatever
    // the
    // kind was, so a mark probe emitted the object twice and the empty one won — every mark
    // property
    // was measured against a specification with no marks in it, and reported perfect coverage of
    // nothing. `a property nothing can consume is counted` is what caught that.
    val entry = kind.wrap(field(property, value))
    val body = if (kind.key == "title") entry else "[$entry]"
    val json =
      """
      {"width": 10, "height": 10, "padding": 0,
       "data": [{"name": "t", "values": []}],
       "scales": [{"name": "x", "type": "linear", "domain": [0, 1], "range": "width"},
                  {"name": "c", "type": "ordinal", "domain": ["a"], "range": "category"}],
       "${kind.key}": $body${if (kind.key == "marks") "" else ""","marks": []"""}}
      """
        .trimIndent()
    return SpecParser().parseJson(json).diagnostics.any {
      it.code == DiagnosticCodes.PARSE_UNKNOWN_PROPERTY && it.message.contains("'$property'")
    }
  }

  /**
   * The counter counts, checked against a name no parser can possibly consume.
   *
   * Without this the coverage numbers could read 100 per cent because the probe never detects
   * anything — a gate that passes by observing nothing, which is the failure this repository keeps
   * meeting. A guard on the guard.
   */
  @Test
  fun `a property nothing can consume is counted`() {
    val invented = "aPropertyUpstreamHasNeverHeardOf"
    for (kind in kinds) {
      assertTrue(
        reportsUnhandled(kind, invented),
        "${kind.definition}: an invented property was not reported, so the probe detects nothing",
      )
    }
  }

  private fun field(property: String, value: String) = "\"$property\": $value"

  /**
   * The property names upstream's schema gives [definition].
   *
   * Some definitions state their properties directly and some compose them through `allOf`, so both
   * shapes are read: taking only the direct ones silently answers "no properties" for the composed
   * kinds, which would report perfect coverage of nothing.
   */
  private fun propertyNames(root: VegaValue.Obj, definition: String): List<String> {
    val definitions = root.fields["definitions"] as? VegaValue.Obj ?: return emptyList()
    val entry = definitions.fields[definition] as? VegaValue.Obj ?: return emptyList()
    val direct = (entry.fields["properties"] as? VegaValue.Obj)?.fields?.keys.orEmpty()
    val composed =
      (entry.fields["allOf"] as? VegaValue.Arr)
        ?.values
        ?.filterIsInstance<VegaValue.Obj>()
        ?.flatMap { (it.fields["properties"] as? VegaValue.Obj)?.fields?.keys.orEmpty() }
        .orEmpty()
    return (direct + composed).distinct()
  }

  private companion object {
    /**
     * How many properties of each kind may go unconsumed, as measured when this was written.
     *
     * Zero everywhere it is zero, which is the point: these are ceilings a regression trips, not
     * targets. Raising one is a decision to stop honouring something upstream documents.
     */
    /** The shapes a guide property takes, tried in turn. */
    val CANDIDATE_VALUES = listOf("\"red\"", "1", "true", "[2, 2]", """{"signal": "s"}""")

    val FLOORS =
      mapOf("axis" to 0, "legend" to 0, "projection" to 0, "mark" to 0, "encodeEntry" to 0)
  }
}
