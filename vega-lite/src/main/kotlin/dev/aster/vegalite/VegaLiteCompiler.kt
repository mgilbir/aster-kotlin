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

  /** `config.facet.spacing` and the gap a header title keeps from its cells. */
  private val FACET_SPACING = 20.0
  private val HEADER_OFFSET = 10.0

  /** The `row` and `column` this chart is gridded by, if either. */
  private var facet: FacetGrid? = null

  fun run(): VegaLiteCompilation {
    reportUnsupportedTopLevel()

    val parsed = views() ?: return VegaLiteCompilation(null, diagnostics.diagnostics)
    if (parsed.isEmpty()) return VegaLiteCompilation(null, diagnostics.diagnostics)

    // A facet channel does not encode anything *within* a cell, so it is lifted out of the encoding
    // before the scales are built — and everything inside then measures a cell rather than the
    // surface.
    val views = liftFacet(parsed)

    val scales = mergeScales(views)
    val scaleTypes = scales.mapValues { it.value.type }
    views.forEach {
      it.scaleTypes = scaleTypes
      it.scaleComponents = scales
    }

    val data = assembleData(views).toMutableList()
    fillScaleDomains(views, scales)
    // The facets' own values, which the layout counts and the headers title themselves from.
    facet?.let { data += it.domainDatasets(views.first().mainData) }

    val axes = assembleAxes(views, scales)
    val legends = assembleLegends(views, scales)
    val layout =
      LayoutSize(views, scales, config, spec, prefix = if (facet != null) "child_" else "")

    val vega = obj {
      put("\$schema", "https://vega.github.io/schema/vega/v6.json")
      put("description", spec.string("description"))
      put("background", config.background)
      put("padding", config.padding)
      autosize()?.let { put("autosize", it) }
      put("width", layout.width)
      put("height", layout.height)
      // `cell` is the bordered plotting area; a chart with no Cartesian position — a pie — has no
      // plotting area to border, and upstream styles it as a plain `view` instead. A faceted chart
      // has no plotting area of its own at all: each of its cells carries the style.
      if (facet == null) {
        put(
          "style",
          if (views.any { it.spec.encoding["x"] != null || it.spec.encoding["y"] != null }) "cell"
          else "view",
        )
      }
      title()?.let { put("title", it) }
      put("data", arr(data))
      if (layout.signals.isNotEmpty()) put("signals", arr(layout.signals))
      facet?.let { put("layout", it.layout(FACET_SPACING, HEADER_OFFSET, facetTitles())) }
      put("marks", arr(marks(views, axes)))
      if (scales.isNotEmpty()) put("scales", arr(scales.values.map { assembleScale(it) }))
      // A faceted chart has no axes of its own: the gridlines live in every cell and the labelled
      // axis in a header drawn once for the whole grid.
      if (facet == null && axes.isNotEmpty()) put("axes", arr(axes))
      if (legends.isNotEmpty()) put("legends", arr(legends))
      // The theme, as Vega takes it. Without this a chart's guides are drawn in the engine's own
      // colours however carefully the specification restyled them.
      config.forVega()?.let { put("config", it) }
    }

    return VegaLiteCompilation(vega, diagnostics.diagnostics)
  }

  // -----------------------------------------------------------------------------------------
  // Views
  // -----------------------------------------------------------------------------------------

  private fun views(): List<UnitView>? {
    val normalize = Normalize(config, diagnostics)
    val composite = Composite(config, diagnostics)
    // A composite mark stands for a layer of ordinary ones and names its parts relative to itself —
    // a box plot's whiskers are `layer_0_layer_1_layer_0`, being a layer inside a layer. A path
    // overlay may then apply to each part and numbers its own results beneath that name.
    fun expand(unit: VegaValue.Obj, prefix: String): List<Pair<String, VegaValue.Obj>> {
      val parts = composite.normalize(unit) ?: listOf("" to unit)
      return parts.flatMap { (name, part) ->
        val here = listOf(prefix, name).filter { it.isNotEmpty() }.joinToString("_")
        val overlaid = normalize.pathOverlay(part)
        if (overlaid == null) {
          listOf(here to part)
        } else {
          overlaid.mapIndexed { index, view ->
            listOf(here, "layer_$index").filter { it.isNotEmpty() }.joinToString("_") to view
          }
        }
      }
    }
    val layers = spec.array("layer")
    if (layers != null) {
      // Each declared layer may itself normalize into more than one — a line that draws its own
      // points is two marks — so the list is expanded first and only then numbered. The numbering
      // is what names `layer_0_marks`, so it has to count the views that actually exist.
      val units = mutableListOf<Pair<Pair<String, VegaValue.Obj>, String>>()
      layers.forEachIndexed { index, layer ->
        val child = layer as? VegaValue.Obj ?: return@forEachIndexed
        if (child.fields.containsKey("layer")) {
          diagnostics.error(
            VegaLiteDiagnostics.UNSUPPORTED_COMPOSITION,
            "A layer inside a layer is not implemented; flatten the layers into one list.",
            jsonPath = "$.layer[$index]",
          )
          return@forEachIndexed
        }
        val merged = inherited(child)
        expand(merged, "layer_$index").forEach { units += it to "$.layer[$index]" }
      }
      return units.mapNotNull { (named, path) ->
        val (name, unit) = named
        parser.unit(unit, path)?.let { UnitView(it, config, name) }
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

    // A single view that normalizes into several becomes a layer of them, which is exactly what
    // upstream does: the normalizer hands its result back to the compiler as a layer spec. One
    // that normalizes into exactly one stays a single view, and keeps its unprefixed names.
    if (composite.normalize(spec) != null || normalize.pathOverlay(spec) != null) {
      return expand(spec, "").mapNotNull { (name, unit) ->
        parser.unit(unit, "$")?.let { UnitView(it, config, name) }
      }
    }

    val unit = parser.unit(spec, "$") ?: return null
    return listOf(UnitView(unit, config, ""))
  }

  /**
   * A layer's own definition over the chart's.
   *
   * A layer inherits the chart's data, size, transforms and *encoding* unless it states its own.
   * The encoding matters most: writing the shared channels once above the layers and the
   * differences inside them is the ordinary way to author a layered chart, and a layer that did not
   * inherit them would draw its mark with no position at all.
   */
  private fun inherited(child: VegaValue.Obj): VegaValue.Obj = obj {
    put("data", spec.fields["data"])
    put("width", spec.fields["width"])
    put("height", spec.fields["height"])
    put("transform", spec.fields["transform"])
    putAll(child)
    val shared = spec.obj("encoding")
    if (shared != null) {
      // Channel by channel, so a layer overriding `y` keeps the shared `x`.
      put(
        "encoding",
        obj {
          putAll(shared)
          putAll(child.obj("encoding"))
        },
      )
    }
  }

  /**
   * Takes the facet channel out of every view's encoding, and makes the views children of a cell.
   *
   * Their marks are then named `child_marks` and their sizes `child_width`/`child_height`, which is
   * upstream's naming and is load-bearing: `width` still exists and means the whole grid.
   */
  private fun liftFacet(views: List<UnitView>): List<UnitView> {
    fun channel(name: String) = views.firstNotNullOfOrNull { view ->
      view.spec.encoding[name]?.takeIf { it.isFieldDef }?.let { Facet(name, it) }
    }
    val row = channel("row")
    val column = channel("column")
    if (row == null && column == null) return views
    val found = FacetGrid(row, column)
    facet = found

    return views.map { view ->
      val withoutFacet = view.spec.encoding.filterKeys { it !in Channels.FACET_CHANNELS }
      UnitView(
          UnitSpec(
            markDef = view.spec.markDef,
            encoding = withoutFacet,
            data = view.spec.data,
            transforms = view.spec.transforms,
            width = view.spec.width,
            height = view.spec.height,
          ),
          config,
          if (view.name.isEmpty()) "child" else "child_${view.name}",
        )
        .also {
          it.sizePrefix = "child_"
          it.facetFields = found.fields
          // The cell's marks read the partition Vega facets out for them, named `facet`; the
          // scales still read the whole table, so every cell is scaled alike.
          it.markData = "facet"
        }
    }
  }

  /**
   * The mark list: the views' own marks, or the cell and its headers when the chart is faceted.
   *
   * The axes split here. Gridlines belong in the cell, beside the data they measure; the labelled
   * axis belongs to a footer or header drawn once, or a trellis repeats its tick labels under every
   * cell.
   */
  private fun marks(views: List<UnitView>, axes: List<VegaValue>): List<VegaValue> {
    val childMarks = views.flatMap { Marks.marks(it) }
    val current = facet ?: return childMarks

    val gridAxes = axes.filter { (it["grid"] as? VegaValue.Bool)?.value == true }
    val mainAxes = axes.filter { (it["grid"] as? VegaValue.Bool)?.value != true }
    val horizontal = mainAxes.filter {
      it.string("orient") == "bottom" || it.string("orient") == "top"
    }
    val vertical = mainAxes - horizontal.toSet()

    val out = mutableListOf<VegaValue>()
    // Both headings first, rows before columns, then the four bands of labels around the grid.
    for (facetChannel in listOfNotNull(current.row, current.column)) {
      val title = Fields.title(facetChannel.def, config) as? VegaValue.Str ?: continue
      out += facetChannel.titleGroup(title.value, HEADER_OFFSET)
    }
    out += current.headerGroups(vertical, horizontal, HEADER_OFFSET)

    out +=
      current.cellGroup(views.first().mainData, childMarks, gridAxes, "child_width", "child_height")
    return out
  }

  /**
   * Which facet channels name themselves.
   *
   * The `layout` keeps a heading clear of the labels beneath it with `offset.rowTitle` or
   * `offset.columnTitle`, and upstream writes the offset only where there is a heading to keep
   * clear — `if (layoutHeaderComponent.title)` in `getHeaderLayoutMixins`.
   */
  private fun facetTitles(): Set<String> {
    val current = facet ?: return emptySet()
    return listOfNotNull(current.row, current.column)
      .filter { Fields.title(it.def, config) is VegaValue.Str }
      .mapTo(mutableSetOf()) { it.channel }
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
    // A title is anchored to the *group* rather than to the whole surface, which is what keeps it
    // over the plotting area when an axis widens the drawing to its left.
    return if (declared is VegaValue.Str) {
      obj {
        put("text", declared)
        put("frame", "group")
      }
    } else {
      declared
    }
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
    // Every view built its own chain onto the one source, so the tree forks there; the shared parse
    // is hoisted above the fork before the tree is named and flattened.
    source.moveParseUp()
    source.mergeParse()
    source.mergeIdentical()
    source.mergeAggregates()
    source.mergeOutputs()
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
          // The *more capable* type wins rather than the first-declared one. Upstream ranks them
          // (`SCALE_PRECEDENCE_INDEX`) and puts `band` above `point` above everything continuous,
          // "as they support more types of data" and band "is better for interaction" — which is
          // how a box plot, whose parts are a bar, a rule and two ticks, ends up on one band.
          if (Scales.precedence(type) > Scales.precedence(existing.type)) {
            scales[channel] = ScaleComponent(channel, type)
          }
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
    if (domains.size == 1) {
      val only = domains.first() as? VegaValue.Obj ?: return domains.first()
      val sort = simplifySort(only["sort"], only.string("field")) ?: return only
      return obj {
        only.fields.forEach { (key, value) -> if (key != "sort") put(key, value) }
        put("sort", sort.takeUnless { it == VegaValue.Bool(true) && only["sort"] == null })
      }
    }

    // A sort every entry agrees on belongs to the union rather than to each of its parts: sorting
    // the pieces separately and concatenating them is a different answer from sorting the whole.
    val sorts = domains.map { simplifySort(it["sort"], null) ?: it["sort"] }.distinct()
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

  /**
   * The three ways a domain sort says less than it was built with — `assembleDomain` in
   * `compile/scale/domain.ts`.
   *
   * Each removes something that is either implied or meaningless: a `count` has no field to count
   * *of*, `ascending` is the default order, and a sort on the domain's own field is the natural
   * order with at most a direction to it. They matter because the output is compared property by
   * property, and a sort saying the same thing twice is a different specification.
   *
   * @param domainField the field this domain is *of*, or null where several are being merged and no
   *   single one is.
   * @return the simplified sort, or null when there was nothing to simplify.
   */
  private fun simplifySort(sort: VegaValue?, domainField: String?): VegaValue? {
    val obj = sort as? VegaValue.Obj ?: return null
    var simplified = obj
    if (obj.string("op") == "count" && obj.has("field")) {
      simplified = obj { simplified.fields.forEach { (k, v) -> if (k != "field") put(k, v) } }
    }
    if (simplified.string("order") == "ascending") {
      simplified = obj { simplified.fields.forEach { (k, v) -> if (k != "order") put(k, v) } }
    }
    if (domainField != null && simplified.string("field") == domainField) {
      val order = simplified.string("order")
      simplified = if (order == null) return VegaValue.Bool(true) else obj { put("order", order) }
    }
    return if (simplified.fields == obj.fields) null else simplified
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
