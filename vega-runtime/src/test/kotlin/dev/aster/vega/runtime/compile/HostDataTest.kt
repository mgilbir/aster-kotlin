package dev.aster.vega.runtime.compile

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.model.VegaValue
import dev.aster.vega.runtime.ForeignData
import dev.aster.vega.runtime.VegaChartController
import dev.aster.vega.runtime.load.DataLoader
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.SymbolNode
import dev.aster.vega.scene.flatten
import dev.aster.vegalite.VegaLiteInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A table the **host** supplies, which is the other half of "the engine takes data".
 *
 * Until this, a chart could only be drawn from data a *payload* carried: values inlined in the
 * specification, or a `url` for the engine to fetch. An app's own data is neither. A diary lives in
 * a local store and was plotted offline by the apps this engine is replacing; a measurement list
 * arrives over a channel the chart knows nothing about; rows get assembled from a sensor. The
 * adopting team named it: *"the engine must accept a data table that the app builds, not only a
 * specification with its data inlined."*
 *
 * Upstream's shape is `view.data(name, rows)`, and it works because a Vega dataset with no
 * `values`, no `url` and no `source` is an **input**: something outside the specification fills it.
 * Vega-Lite writes `{"data": {"name": "diary"}}` and passes that name through to the compiled
 * specification unchanged — verified against `vega-lite@6.4.3`, and pinned below — so a host uses
 * the name it wrote and never has to guess at a `source_0`.
 *
 * The rows go in **where inline values would**, which is the design decision worth stating: a host
 * does not reimplement a parse rule or a transform to get its own table drawn. `format.parse` still
 * parses, `timeunit` still buckets, a `filter` still filters, and what comes out is the chart the
 * same rows would have drawn inlined.
 */
class HostDataTest {

  /** A chart whose data it does not carry: `{"name": "diary"}` and nothing else. */
  private val awaitingData =
    """
    {
      "width": 200, "height": 100, "padding": 5,
      "data": [{"name": "diary", "format": {"parse": {"t": "date"}}}],
      "scales": [
        {"name": "x", "type": "band", "domain": {"data": "diary", "field": "bucket"},
         "range": "width"},
        {"name": "y", "type": "linear", "domain": {"data": "diary", "field": "v"},
         "range": "height"}
      ],
      "marks": [{"type": "rect", "from": {"data": "diary"}, "encode": {"enter": {
        "x": {"scale": "x", "field": "bucket"}, "width": {"scale": "x", "band": 1},
        "y": {"scale": "y", "field": "v"}, "y2": {"scale": "y", "value": 0}}}}]
    }
    """
      .trimIndent()

  private fun rows(vararg pairs: Pair<String, Double>): List<VegaValue> =
    pairs.map { (bucket, value) ->
      ForeignData.row(mapOf("bucket" to VegaValue.Str(bucket), "v" to VegaValue.Num(value)))
    }

  private fun compile(json: String, data: Map<String, List<VegaValue>>?) =
    SpecCompiler(VegaHeadlessTextEngine(), hostData = data).compileJson(json)

  @Test
  fun `a chart with no data of its own is drawn from the host's table`() {
    val compiled = compile(awaitingData, mapOf("diary" to rows("morning" to 3.0, "evening" to 7.0)))
    val scene = requireNotNull(compiled.scene) { "no scene: ${compiled.diagnostics}" }

    val bars = scene.flatten().map { it.node }.filterIsInstance<RectNode>()
    assertEquals(2, bars.size, "one bar per row the host supplied")
    // And the values reached the scale rather than merely the row count: the bars are in the order
    // supplied, and the one for 7 is taller than the one for 3. The heights themselves are a
    // question
    // about a Vega linear scale over `[3, 7]` and not about this seam, so they are not pinned here.
    assertTrue(
      bars[1].height > bars[0].height,
      "the second row is the larger value: ${bars.map { it.height }}",
    )
    assertTrue(
      compiled.diagnostics.none { it.severity.ordinal >= 2 },
      "nothing to report: ${compiled.diagnostics}",
    )
  }

  @Test
  fun `an empty table is a chart with no rows, not a chart that was never filled`() {
    val compiled = compile(awaitingData, mapOf("diary" to emptyList()))
    val scene = requireNotNull(compiled.scene) { "no scene" }
    assertTrue(scene.flatten().map { it.node }.filterIsInstance<RectNode>().isEmpty())
  }

  @Test
  fun `the dataset's own parse and transforms run over the host's rows`() {
    // The point of injecting where inline values go. `format.parse` turns the column into instants
    // and
    // `timeunit` buckets them, so the host hands over strings and gets a chart of days.
    val spec =
      """
      {
        "width": 200, "height": 100, "padding": 5,
        "signals": [
          {"name": "first", "update": "data('readings')[0].unit0"},
          {"name": "rows", "update": "length(data('readings'))"}
        ],
        "data": [{
          "name": "readings",
          "format": {"parse": {"t": "date"}},
          "transform": [
            {"type": "filter", "expr": "datum.v > 2"},
            {"type": "timeunit", "field": "t", "units": ["year", "month", "date"],
             "timezone": "utc"}
          ]
        }],
        "marks": []
      }
      """
        .trimIndent()

    val supplied =
      listOf(
        ForeignData.row(
          mapOf("t" to VegaValue.Str("2026-05-20T12:00:00Z"), "v" to VegaValue.Num(9.0))
        ),
        ForeignData.row(
          mapOf("t" to VegaValue.Str("2026-05-21T12:00:00Z"), "v" to VegaValue.Num(1.0))
        ),
      )
    val compiled = compile(spec, mapOf("readings" to supplied))

    assertEquals(VegaValue.Num(1.0), compiled.signals.signal("rows"), "the filter ran")
    assertEquals(
      1779235200000.0,
      (compiled.signals.signal("first") as VegaValue.Num).value,
      "2026-05-20T00:00Z: the string was parsed as a date and bucketed to the day",
    )
  }

  @Test
  fun `an instant can be handed over as one, with no parse rule at all`() {
    // A host holding a `Date` should not have to format it to a string for the engine to parse
    // back:
    // that goes through a zone twice, and twice is where a day goes missing.
    val spec =
      """
      {
        "width": 200, "height": 100,
        "signals": [{"name": "isDate", "update": "isDate(data('t')[0].at)"}],
        "data": [{"name": "t"}],
        "marks": []
      }
      """
        .trimIndent()
    val compiled =
      compile(spec, mapOf("t" to listOf(ForeignData.row(mapOf("at" to ForeignData.instant(0.0))))))

    assertEquals(VegaValue.Bool(true), compiled.signals.signal("isDate"))
  }

  @Test
  fun `a name no dataset carries is reported rather than ignored`() {
    val compiled = compile(awaitingData, mapOf("dairy" to rows("morning" to 1.0)))

    val complaint = compiled.diagnostics.singleOrNull { it.message.contains("'dairy'") }
    assertTrue(complaint != null, "a typo has to be said out loud: ${compiled.diagnostics}")
    // And the chart still draws, from the nothing it was given, rather than failing outright.
    assertTrue(compiled.scene != null)
  }

  @Test
  fun `a derived dataset is refused, because filling it would discard its transforms`() {
    val spec =
      """
      {
        "width": 200, "height": 100,
        "signals": [{"name": "rows", "update": "length(data('kept'))"}],
        "data": [
          {"name": "raw", "values": [{"v": 1}, {"v": 5}]},
          {"name": "kept", "source": "raw", "transform": [{"type": "filter", "expr": "datum.v > 2"}]}
        ],
        "marks": []
      }
      """
        .trimIndent()
    val compiled =
      compile(spec, mapOf("kept" to listOf(ForeignData.row(mapOf("v" to VegaValue.Num(99.0))))))

    assertTrue(
      compiled.diagnostics.any { it.message.contains("derives from raw") },
      "the refusal has to name itself: ${compiled.diagnostics}",
    )
    // The transform still decided the contents: one row of the source survives the filter.
    assertEquals(VegaValue.Num(1.0), compiled.signals.signal("rows"))
  }

  @Test
  fun `a supplied table means the url is not fetched`() {
    // Security as much as speed: a `url` in a specification is a request that this process open an
    // address the specification chose. A host that has the data already has no reason to make it.
    var asked = false
    val loader =
      object : DataLoader {
        override fun sanitize(uri: String): String = uri

        override fun load(uri: String): String {
          asked = true
          return "[]"
        }
      }
    val spec =
      """
      {
        "width": 200, "height": 100,
        "signals": [{"name": "rows", "update": "length(data('t'))"}],
        "data": [{"name": "t", "url": "https://example.invalid/rows.json"}],
        "marks": []
      }
      """
        .trimIndent()
    val compiled =
      SpecCompiler(
          VegaHeadlessTextEngine(),
          loader = loader,
          hostData = mapOf("t" to listOf(ForeignData.row(mapOf("v" to VegaValue.Num(1.0))))),
        )
        .compileJson(spec)

    assertFalse(asked, "the loader was called even though the host supplied the table")
    assertEquals(VegaValue.Num(1.0), compiled.signals.signal("rows"))
    assertTrue(
      compiled.diagnostics.any { it.message.contains("was not fetched") },
      "a request that did not happen is worth saying: ${compiled.diagnostics}",
    )
  }

  @Test
  fun `a Vega-Lite named dataset is filled by the name the specification wrote`() {
    // Upstream keeps `data: {"name": "diary"}` as a Vega dataset called `diary`, with the derived
    // ones
    // sourcing from it — checked against `vega-lite@6.4.3` — so a host never has to know about
    // `source_0` or `data_0`.
    val vegaLite =
      """
      {
        "${'$'}schema": "https://vega.github.io/schema/vega-lite/v6.json",
        "data": {"name": "diary"},
        "mark": "point",
        "encoding": {
          "x": {"field": "bucket", "type": "ordinal"},
          "y": {"field": "v", "type": "quantitative"}
        }
      }
      """
        .trimIndent()
    val vega = requireNotNull(VegaLiteInput.toVega(vegaLite).vegaJson) { "no Vega" }

    val compiled = compile(vega, mapOf("diary" to rows("morning" to 3.0, "evening" to 7.0)))
    val scene = requireNotNull(compiled.scene) { "no scene: ${compiled.diagnostics}" }
    assertEquals(
      2,
      scene.flatten().map { it.node }.filterIsInstance<SymbolNode>().size,
      "one point per supplied row",
    )
    assertTrue(
      compiled.diagnostics.none { it.message.contains("no dataset") },
      "the name went through the Vega-Lite compiler unchanged: ${compiled.diagnostics}",
    )
  }

  @Test
  fun `rows can be read from JSON a host already holds`() {
    val supplied =
      requireNotNull(ForeignData.rowsFromJson("""[{"bucket": "morning", "v": 4}]""")) { "not read" }
    val compiled = compile(awaitingData, mapOf("diary" to supplied))

    assertEquals(1, requireNotNull(compiled.scene).flatten().count { it.node is RectNode })
    // Not an array: null, and null rather than an empty table, because an empty table is a chart.
    assertEquals(null, ForeignData.rowsFromJson("""{"bucket": "morning"}"""))
    assertEquals(null, ForeignData.rowsFromJson("not json"))
    assertEquals(emptyList<VegaValue>(), ForeignData.rowsFromJson("[]"))
  }

  @Test
  fun `a dataset declared inside a group mark is filled too, and not reported as unclaimed`() {
    // Group scopes are resolved during the *scene* compile, through the same resolver, which is why
    // an
    // unclaimed name can only be reported once everything has run. Without that ordering this table
    // would be filled correctly and complained about in the same breath.
    val spec =
      """
      {
        "width": 200, "height": 100, "padding": 5,
        "data": [{"name": "outer", "values": [{"g": 1}]}],
        "marks": [{
          "type": "group", "from": {"data": "outer"},
          "encode": {"enter": {"width": {"value": 200}, "height": {"value": 100}}},
          "data": [{"name": "inner"}],
          "marks": [{"type": "symbol", "from": {"data": "inner"}, "encode": {"enter": {
            "x": {"field": "x"}, "y": {"value": 50}}}}]
        }]
      }
      """
        .trimIndent()

    val compiled =
      compile(
        spec,
        mapOf(
          "inner" to
            listOf(
              ForeignData.row(mapOf("x" to VegaValue.Num(20.0))),
              ForeignData.row(mapOf("x" to VegaValue.Num(60.0))),
            )
        ),
      )
    val scene = requireNotNull(compiled.scene) { "no scene: ${compiled.diagnostics}" }

    assertEquals(
      2,
      scene.flatten().map { it.node }.filterIsInstance<SymbolNode>().size,
      "the group's own dataset was filled from the host's table",
    )
    assertTrue(
      compiled.diagnostics.none { it.message.contains("no dataset in this specification") },
      "filled and complained about in the same compile: ${compiled.diagnostics}",
    )
  }

  @Test
  fun `a controller redraws when the host's data changes`() {
    // The diary case: rows arrive from a store, and again when the store changes. A recompile is
    // how
    // this engine answers any change of a compile input, and it is inside a frame.
    val controller = VegaChartController(textEngine = VegaHeadlessTextEngine())
    controller.setSpec(awaitingData)
    assertEquals(
      0,
      requireNotNull(controller.snapshot.scene).flatten().count { it.node is RectNode },
      "nothing supplied yet",
    )

    controller.setData("diary", rows("morning" to 3.0, "evening" to 7.0))
    assertEquals(
      2,
      requireNotNull(controller.snapshot.scene).flatten().count { it.node is RectNode },
    )

    controller.setData("diary", rows("morning" to 3.0))
    assertEquals(
      1,
      requireNotNull(controller.snapshot.scene).flatten().count { it.node is RectNode },
    )

    // And a table supplied *before* the specification is not lost by loading it.
    val second = VegaChartController(textEngine = VegaHeadlessTextEngine())
    second.setData("diary", rows("morning" to 3.0))
    second.setSpec(awaitingData)
    assertEquals(1, requireNotNull(second.snapshot.scene).flatten().count { it.node is RectNode })
  }
}
