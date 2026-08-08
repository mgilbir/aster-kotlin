package dev.aster.vegalite

import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaDiagnostic
import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue

/** A compiled Vega-Lite specification: the Vega it became, and everything it could not honour. */
public data class VegaLiteCompilation(
  /** The Vega specification, ready to hand to the runtime. Null only if nothing could be built. */
  val vega: VegaValue.Obj?,
  val diagnostics: List<VegaDiagnostic>,
) {
  public val isUsable: Boolean
    get() = vega != null

  /** The specification as JSON text, for writing to a file or comparing against upstream's. */
  public fun toJson(): String? = vega?.let { VegaJson.write(it) }
}

/**
 * Compiles Vega-Lite to Vega.
 *
 * Vega-Lite is a much smaller grammar than Vega, and everything it leaves out it supplies by rule:
 * a bar chart is a rect mark on a band scale with a stack transform, an axis with gridlines, a
 * title that reads `Mean of b`, and a plot exactly as wide as its categories need. Those rules are
 * the whole of this compiler, and they are ported from upstream's own source rather than inferred
 * from its documentation — see `CONTRIBUTING.md`, where probing upstream first is the one rule.
 *
 * What comes out is a Vega specification in the same value model the runtime parses, so a Vega-Lite
 * chart takes exactly the path a Vega one does from that point on. The differential fixtures
 * compare this output against upstream's compiler property by property, so a rule that drifts is
 * caught where it happens rather than as a wrong picture several layers later.
 *
 * The implemented subset is a single view or a layer of them. Faceting, concatenation, repetition,
 * selection parameters and the composite marks (`boxplot`, `errorbar`, `errorband`) are reported by
 * name and not approximated.
 */
public class VegaLiteCompiler {

  public fun compileJson(json: String): VegaLiteCompilation {
    val diagnostics = DiagnosticCollector()
    val parsed =
      VegaJson.parseOrNull(json, diagnostics)
        ?: return VegaLiteCompilation(null, diagnostics.diagnostics)
    return compile(parsed)
  }

  public fun compile(spec: VegaValue): VegaLiteCompilation {
    val diagnostics = DiagnosticCollector()
    if (spec !is VegaValue.Obj) {
      diagnostics.fatal(
        VegaLiteDiagnostics.NOT_VEGA_LITE,
        "A Vega-Lite specification must be a JSON object.",
      )
      return VegaLiteCompilation(null, diagnostics.diagnostics)
    }
    return Compilation(spec, diagnostics).run()
  }
}

/** One compilation. Holds the counters and the components the whole chart shares. */
private class Compilation(
  private val spec: VegaValue.Obj,
  private val diagnostics: DiagnosticCollector,
) {

  private val config = Config(spec.obj("config") ?: VegaValue.EmptyObject)
  private val parser = Parse(config, diagnostics)

  fun run(): VegaLiteCompilation {
    reportUnsupportedTopLevel()

    val views = views() ?: return VegaLiteCompilation(null, diagnostics.diagnostics)
    if (views.isEmpty()) return VegaLiteCompilation(null, diagnostics.diagnostics)

    val scales = mergeScales(views)
    val scaleTypes = scales.mapValues { it.value.type }
    views.forEach {
      it.scaleTypes = scaleTypes
      it.scaleComponents = scales
    }

    val data = assembleData(views)
    fillScaleDomains(views, scales)

    val axes = assembleAxes(views, scales)
    val legends = assembleLegends(views, scales)
    val layout = LayoutSize(views, scales, config, spec)

    val vega = obj {
      put("\$schema", "https://vega.github.io/schema/vega/v6.json")
      put("description", spec.string("description"))
      put("background", config.background)
      put("padding", config.padding)
      autosize()?.let { put("autosize", it) }
      put("width", layout.width)
      put("height", layout.height)
      put("style", "cell")
      title()?.let { put("title", it) }
      put("data", arr(data))
      if (layout.signals.isNotEmpty()) put("signals", arr(layout.signals))
      put("marks", arr(views.flatMap { Marks.marks(it) }))
      if (scales.isNotEmpty()) put("scales", arr(scales.values.map { assembleScale(it) }))
      if (axes.isNotEmpty()) put("axes", arr(axes))
      if (legends.isNotEmpty()) put("legends", arr(legends))
    }

    return VegaLiteCompilation(vega, diagnostics.diagnostics)
  }

  // -----------------------------------------------------------------------------------------
  // Views
  // -----------------------------------------------------------------------------------------

  private fun views(): List<UnitView>? {
    val layers = spec.array("layer")
    if (layers != null) {
      return layers.mapIndexedNotNull { index, layer ->
        val child = layer as? VegaValue.Obj ?: return@mapIndexedNotNull null
        if (child.fields.containsKey("layer")) {
          diagnostics.error(
            VegaLiteDiagnostics.UNSUPPORTED_COMPOSITION,
            "A layer inside a layer is not implemented; flatten the layers into one list.",
            jsonPath = "$.layer[$index]",
          )
          return@mapIndexedNotNull null
        }
        // A layer inherits the chart's data, size, transforms and *encoding* unless it states its
        // own. The encoding matters most: writing the shared channels once above the layers and the
        // differences inside them is the ordinary way to author a layered chart, and a layer that
        // did not inherit them would draw its mark with no position at all.
        val merged = obj {
          put("data", spec.fields["data"])
          put("width", spec.fields["width"])
          put("height", spec.fields["height"])
          put("transform", spec.fields["transform"])
          putAll(child)
          val inherited = spec.obj("encoding")
          if (inherited != null) {
            // Channel by channel, so a layer overriding `y` keeps the shared `x`.
            put(
              "encoding",
              obj {
                putAll(inherited)
                putAll(child.obj("encoding"))
              },
            )
          }
        }
        parser.unit(merged, "$.layer[$index]")?.let { UnitView(it, config, "layer_$index") }
      }
    }

    for (composition in listOf("facet", "hconcat", "vconcat", "concat", "repeat")) {
      if (spec.fields.containsKey(composition)) {
        diagnostics.fatal(
          VegaLiteDiagnostics.UNSUPPORTED_COMPOSITION,
          "`$composition` is not implemented. A single view or a `layer` of views compiles; a " +
            "composition of several plots does not, and would need its own layout.",
          jsonPath = "$.$composition",
        )
        return null
      }
    }

    val unit = parser.unit(spec, "$") ?: return null
    return listOf(UnitView(unit, config, ""))
  }

  private fun reportUnsupportedTopLevel() {
    if (spec.fields.containsKey("params")) {
      diagnostics.error(
        VegaLiteDiagnostics.UNSUPPORTED_PARAMETER,
        "`params` is not implemented, so selections and bound inputs are dropped. The chart still " +
          "compiles, without the interaction.",
        jsonPath = "$.params",
      )
    }
    if (spec.fields.containsKey("projection")) {
      diagnostics.error(
        VegaLiteDiagnostics.UNSUPPORTED_TOP_LEVEL_PROPERTY,
        "`projection` is not implemented; a geographic chart needs projection support in the " +
          "runtime first.",
        jsonPath = "$.projection",
      )
    }
    if (spec.fields.containsKey("resolve")) {
      diagnostics.error(
        VegaLiteDiagnostics.UNSUPPORTED_TOP_LEVEL_PROPERTY,
        "`resolve` is not implemented; scales, axes and legends are always shared between layers.",
        jsonPath = "$.resolve",
      )
    }
  }

  private fun autosize(): VegaValue? {
    val declared = spec.fields["autosize"] ?: return null
    val type = (declared as? VegaValue.Str)?.value ?: declared.string("type")
    // `pad` is Vega's own default, so upstream writes nothing for it.
    if (declared is VegaValue.Str) return if (type == "pad") null else declared
    return declared
  }

  private fun title(): VegaValue? {
    val declared = spec.fields["title"] ?: return null
    return if (declared is VegaValue.Str) obj { put("text", declared) } else declared
  }

  // -----------------------------------------------------------------------------------------
  // Data
  // -----------------------------------------------------------------------------------------

  private fun assembleData(views: List<UnitView>): List<VegaValue> {
    val data = views.first().spec.data
    if (data == null) {
      diagnostics.error(
        VegaLiteDiagnostics.UNSUPPORTED_TOP_LEVEL_PROPERTY,
        "The specification names no `data`, so there is nothing to draw.",
        jsonPath = "$.data",
      )
      return emptyList()
    }

    val source = SourceNode(data)
    val outputs = views.map { view -> DataPipeline(view, diagnostics).build(source) }
    val datasets = DataAssembler().assemble(source)
    views.forEachIndexed { index, view ->
      view.mainData = outputs[index].main.source ?: ""
      view.rawData = outputs[index].raw?.source ?: view.mainData
    }
    return datasets
  }

  // -----------------------------------------------------------------------------------------
  // Scales
  // -----------------------------------------------------------------------------------------

  private fun mergeScales(views: List<UnitView>): LinkedHashMap<String, ScaleComponent> {
    val scales = LinkedHashMap<String, ScaleComponent>()
    for (view in views) {
      for ((channel, def) in view.scaledChannels()) {
        val type = Scales.scaleType(channel, def, view.spec.mark)
        val existing = scales[channel]
        if (existing == null) {
          scales[channel] = ScaleComponent(channel, type)
        } else if (existing.type != type) {
          diagnostics.warn(
            VegaLiteDiagnostics.UNSUPPORTED_COMPOSITION,
            "Two layers want different scale types on `$channel` (${existing.type} and $type); " +
              "the first one wins, which is what a shared scale can do.",
          )
        }
      }
    }
    return scales
  }

  private fun fillScaleDomains(views: List<UnitView>, scales: Map<String, ScaleComponent>) {
    for (view in views) {
      for ((channel, def) in view.scaledChannels()) {
        val component = scales[channel] ?: continue
        val domains = Scales.domain(view, channel, def, component.type, view.mainData)
        for (domain in domains) if (domain !in component.domains) component.domains += domain
        if (component.properties.isEmpty()) {
          Scales.range(view, channel, def, component.type)?.let { component.set("range", it) }
          Scales.properties(view, channel, def, component.type, component)
        }
        if ((component.properties["zero"] as? VegaValue.Bool)?.value == true)
          component.domainHasZero = true
      }
    }
  }

  private fun assembleScale(component: ScaleComponent): VegaValue = obj {
    put("name", component.name())
    put("type", component.type)
    put("domain", domainValue(component))
    put("range", component.properties["range"])
    component.properties.forEach { (key, value) -> if (key != "range") put(key, value) }
  }

  /** One domain passes through; several become a `fields` union, which is what a layer needs. */
  private fun domainValue(component: ScaleComponent): VegaValue? {
    val domains = component.domains
    if (domains.isEmpty()) return null
    if (domains.size == 1) return domains.first()

    // A sort every entry agrees on belongs to the union rather than to each of its parts: sorting
    // the pieces separately and concatenating them is a different answer from sorting the whole.
    val sorts = domains.map { it["sort"] }.distinct()
    val sharedSort = if (sorts.size == 1) sorts.single() else null
    val entries =
      if (sharedSort == null) {
        domains
      } else {
        domains.map { entry ->
          obj { (entry as VegaValue.Obj).fields.forEach { (k, v) -> if (k != "sort") put(k, v) } }
        }
      }

    // Several fields of one dataset collapse further, into one reference with a field list.
    val sameData = entries.all {
      it is VegaValue.Obj && it.string("data") == entries.first().string("data") && it.has("field")
    }
    return if (sameData) {
      obj {
        put("data", entries.first().string("data"))
        put("fields", strings(entries.map { it.string("field")!! }))
        put("sort", sharedSort)
      }
    } else {
      obj {
        put("fields", arr(entries))
        put("sort", sharedSort)
      }
    }
  }

  // -----------------------------------------------------------------------------------------
  // Guides
  // -----------------------------------------------------------------------------------------

  private fun assembleAxes(
    views: List<UnitView>,
    scales: Map<String, ScaleComponent>,
  ): List<VegaValue> {
    val components = LinkedHashMap<String, Guides.AxisComponent>()
    for (view in views) {
      for (channel in Channels.POSITION_CHANNELS) {
        val def = view.spec.encoding[channel] ?: continue
        if (!def.isFieldDef && def.datum == null) continue
        val component = scales[channel] ?: continue
        val hasOther = scales.containsKey(if (channel == "x") "y" else "x")
        val parsed = Guides.parseAxis(view, channel, def, component.type, hasOther) ?: continue
        val existing = components[channel]
        if (existing == null) {
          components[channel] = parsed
        } else {
          parsed.titles.forEach { if (it !in existing.titles) existing.titles += it }
        }
      }
    }
    // Gridlines first, so they are painted behind every mark, then the axes themselves.
    return components.values.mapNotNull { Guides.assembleAxis(it, "grid") } +
      components.values.mapNotNull { Guides.assembleAxis(it, "main") }
  }

  private fun assembleLegends(
    views: List<UnitView>,
    scales: Map<String, ScaleComponent>,
  ): List<VegaValue> {
    val legends = LinkedHashMap<String, VegaValue>()
    for (view in views) {
      for (channel in Channels.LEGEND_CHANNELS) {
        val def = view.spec.encoding[channel] ?: continue
        if (!def.isFieldDef && def.datum == null) continue
        val component = scales[channel] ?: continue
        if (legends.containsKey(channel)) continue
        Guides.legend(view, channel, def, component.type)?.let { legends[channel] = it }
      }
    }
    return legends.values.toList()
  }
}
