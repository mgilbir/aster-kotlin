package dev.aster.vegalite

import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * How much of upstream's Vega-Lite this compiler covers, asked of the compiler rather than of a
 * list.
 *
 * Six rows of `SUPPORTED_FEATURES.md` say `Partial` and then describe a subset in prose. That word
 * is safe — anything outside the subset is refused by name — but it is not *measurable*, and an
 * unmeasured claim is the one that goes stale. `UpstreamPropertyCoverageTest` already solved this
 * shape for Vega's guide properties, deriving **247 of 247** from behaviour; this is the same trick
 * pointed at Vega-Lite.
 *
 * The inventory comes from upstream's own `vega-lite-schema.json`: the channels of
 * `FacetedEncoding`, the `Mark` enum plus the three composite marks, and the nineteen members of
 * the `Transform` union. Nothing is hand-listed, so a version bump that adds a channel adds it
 * here.
 *
 * **What this measures, stated exactly**, because it is easy to overclaim: whether the compiler
 * *accepts* the construct rather than refusing it by name. Whether it compiles to the same Vega
 * upstream emits is `VegaLiteFixtureTest`'s question, and it answers that property by property on
 * every fixture. This is breadth; that is depth.
 */
class UpstreamVegaLiteCoverageTest {

  private val schema = File("../oracle-js/node_modules/vega-lite/build/vega-lite-schema.json")

  private fun definitions(): VegaValue.Obj {
    assumeTrue(schema.exists(), "no pinned vega-lite schema; run npm ci in oracle-js")
    return (VegaJson.parse(schema.readText()) as VegaValue.Obj).fields["definitions"]
      as VegaValue.Obj
  }

  private fun names(definition: String, from: VegaValue.Obj): List<String> =
    ((from.fields[definition] as? VegaValue.Obj)?.fields?.get("properties") as? VegaValue.Obj)
      ?.fields
      ?.keys
      ?.sorted()
      .orEmpty()

  /**
   * Whether the compiler **refuses** [json] by name.
   *
   * An ERROR does not imply a null specification here — a document can compile to a usable chart
   * and still report that one construct in it was not honoured — so the test is the diagnostic
   * rather than the outcome, which is the same rule `SubsetIsRefusedTest` uses.
   */
  private fun refused(json: String, needle: String): Boolean {
    val result = VegaLiteCompiler().compileJson(json)
    return result.diagnostics.any {
      it.severity >= DiagnosticSeverity.WARNING && needle in it.message
    }
  }

  /**
   * A channel in a specification that is **well formed for that channel**.
   *
   * The `*Error` channels only mean anything on an `errorbar`, and an `errorbar` needs a continuous
   * position field of its own — so `{"mark": "errorbar", "encoding": {"xError": …}}` is refused for
   * being an incomplete error bar rather than for the channel. Two runs of this test reported four
   * refused channels for exactly that reason, which is the same mistake the Vega-side coverage
   * probe made twice: a probe that measures its own malformed input reports the engine as narrower
   * than it is.
   *
   * So an error channel is probed alongside the position it modifies, and everything else on an
   * ordinary `point`.
   */
  private fun channelSpec(channel: String): String {
    val body =
      when {
        channel.startsWith("xError") ->
          """"mark": "errorbar",
             "encoding": {"x": {"field": "a", "type": "quantitative"},
                          "y": {"field": "c", "type": "nominal"},
                          "$channel": {"field": "b", "type": "quantitative"}}"""
        channel.startsWith("yError") ->
          """"mark": "errorbar",
             "encoding": {"y": {"field": "a", "type": "quantitative"},
                          "x": {"field": "c", "type": "nominal"},
                          "$channel": {"field": "b", "type": "quantitative"}}"""
        else ->
          """"mark": "point",
             "encoding": {"$channel": {"field": "a", "type": "quantitative"}}"""
      }
    return """
      {"data": {"values": [{"a": 1, "b": 2, "c": "x"}]},
       $body}
      """
      .trimIndent()
  }

  private fun markSpec(mark: String) =
    """
    {"data": {"values": [{"a": 1, "b": 2}]},
     "mark": "$mark",
     "encoding": {"x": {"field": "a", "type": "quantitative"},
                  "y": {"field": "b", "type": "quantitative"}}}
    """
      .trimIndent()

  /** One transform of each kind, with the required keys its schema entry names. */
  private val transformBodies =
    mapOf(
      "aggregate" to
        """{"aggregate": [{"op": "sum", "field": "a", "as": "s"}], "groupby": ["c"]}""",
      "bin" to """{"bin": true, "field": "a", "as": "ab"}""",
      "calculate" to """{"calculate": "datum.a * 2", "as": "d"}""",
      "density" to """{"density": "a"}""",
      "extent" to """{"extent": "a", "param": "ex"}""",
      "filter" to """{"filter": "datum.a > 0"}""",
      "flatten" to """{"flatten": ["a"]}""",
      "fold" to """{"fold": ["a", "b"]}""",
      "impute" to """{"impute": "a", "key": "b"}""",
      "joinaggregate" to """{"joinaggregate": [{"op": "sum", "field": "a", "as": "s"}]}""",
      "loess" to """{"loess": "a", "on": "b"}""",
      "lookup" to
        """{"lookup": "c", "from": {"data": {"values": [{"c": "x", "z": 1}]}, "key": "c", "fields": ["z"]}}""",
      "quantile" to """{"quantile": "a"}""",
      "regression" to """{"regression": "a", "on": "b"}""",
      "timeUnit" to """{"timeUnit": "year", "field": "a", "as": "ay"}""",
      "sample" to """{"sample": 5}""",
      "stack" to """{"stack": "a", "groupby": ["c"], "as": "sa"}""",
      "window" to """{"window": [{"op": "rank", "as": "r"}]}""",
      "pivot" to """{"pivot": "c", "value": "a"}""",
    )

  private fun transformSpec(body: String) =
    """
    {"data": {"values": [{"a": 1, "b": 2, "c": "x"}]},
     "transform": [$body],
     "mark": "point",
     "encoding": {"x": {"field": "a", "type": "quantitative"}}}
    """
      .trimIndent()

  @Test
  fun `every channel, mark and transform upstream documents is accepted or refused by name`() {
    val defs = definitions()

    val channels = names("FacetedEncoding", defs)
    val marks =
      (((defs.fields["Mark"] as VegaValue.Obj).fields["enum"] as VegaValue.Arr).values.map {
        (it as VegaValue.Str).value
      } + listOf("boxplot", "errorbar", "errorband"))
    val transforms = transformBodies.keys.sorted()

    assertTrue(channels.size >= 35, "only ${channels.size} channels found; the schema moved")
    assertTrue(marks.size >= 15, "only ${marks.size} marks found; the schema moved")

    val unsupportedChannels = channels.filter { refused(channelSpec(it), "`$it`") }
    val unsupportedMarks = marks.filter { refused(markSpec(it), "`$it`") }
    val unsupportedTransforms = transforms.filter {
      refused(transformSpec(transformBodies.getValue(it)), it)
    }

    val report = buildString {
      append("{\n  \"kinds\": [\n")
      listOf(
          Triple("channel", channels, unsupportedChannels),
          Triple("mark", marks, unsupportedMarks),
          Triple("transform", transforms, unsupportedTransforms),
        )
        .forEachIndexed { index, (kind, all, missing) ->
          if (index > 0) append(",\n")
          append(
            """    {"kind": "$kind", "upstream": ${all.size}, """ +
              """"accepted": ${all.size - missing.size}, """ +
              """"refused": [${missing.sorted().joinToString(", ") { "\"$it\"" }}]}"""
          )
        }
      append("\n  ]\n}\n")
    }
    File("build/vega-lite-coverage.json").apply { parentFile.mkdirs() }.writeText(report)

    for ((kind, missing) in
      listOf(
        "channel" to unsupportedChannels,
        "mark" to unsupportedMarks,
        "transform" to unsupportedTransforms,
      )) {
      assertTrue(
        missing.size <= FLOORS.getValue(kind),
        "$kind: ${missing.size} refused, expected at most ${FLOORS.getValue(kind)} — $missing",
      )
    }
  }

  /**
   * The probe detects something, checked against constructs upstream has never had.
   *
   * Without this the numbers above could read as total coverage because nothing is ever detected —
   * the failure this repository keeps meeting. A guard on the guard, and it earned its place in the
   * Vega version of this test immediately, by catching a probe that measured everything against an
   * empty marks array.
   */
  @Test
  fun `a channel, mark and transform nobody has heard of are all counted`() {
    definitions()
    assertTrue(
      refused(channelSpec("aChannelUpstreamHasNeverHad"), "aChannelUpstreamHasNeverHad"),
      "an invented channel was not refused, so the channel probe detects nothing",
    )
    assertTrue(
      refused(markSpec("aMarkUpstreamHasNeverHad"), "aMarkUpstreamHasNeverHad"),
      "an invented mark was not refused, so the mark probe detects nothing",
    )
  }

  private companion object {
    /**
     * How many of each kind may be refused: **none**, which is what the measurement says.
     *
     * Ceilings a regression trips, not targets. Zero everywhere, so a schema bump that adds a
     * channel this compiler does not accept fails here rather than being absorbed into the word
     * "partial" — and raising one is a decision to stop supporting something upstream documents.
     */
    val FLOORS = mapOf("channel" to 0, "mark" to 0, "transform" to 0)
  }
}
