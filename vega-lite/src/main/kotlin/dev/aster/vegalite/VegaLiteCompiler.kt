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

/** The side an axis moves to when two land on one channel — upstream's `OPPOSITE_ORIENT`. */
private val OPPOSITE_ORIENT =
  mapOf("bottom" to "top", "top" to "bottom", "left" to "right", "right" to "left")

/** One compilation. Holds the counters and the components the whole chart shares. */
private class Compilation(
  /**
   * The specification being compiled, which a `repeat` *replaces* with the concatenation it
   * normalizes into before anything else looks at it.
   */
  private var spec: VegaValue.Obj,
  private val diagnostics: DiagnosticCollector,
) {

  private val config = Config(spec.obj("config") ?: VegaValue.EmptyObject)

  /** The selections this chart declares, which the data, the signals and the marks all read. */
  private val selections: List<Selection> = Selection.of(spec)

  /**
   * The signals a selection is worked by: the tuple it writes, and for an interval the drag itself.
   *
   * These live beside the marks they react to — at the top of a chart that is one plot, and inside
   * the plot's group in a concatenation — because the events they listen for are scoped to a group.
   */
  private fun machinery(selection: Selection, views: List<UnitView>): List<VegaValue> {
    if (selection.type != "interval") {
      // A click on another selection's **brush** is not a pick: the rectangle belongs to the brush
      // that owns it, and a click on it would otherwise pick whatever row lies under the drag.
      val brushes =
        selections
          .filter { it.type == "interval" && !it.bindsScales && it.owner === selection.owner }
          .map { "${it.name}_brush" }
      return selection.signals(
        unit = selection.unitName(),
        brushes = brushes,
        view = selection.owner ?: views.firstOrNull(),
      )
    }
    val view = selection.owner ?: views.firstOrNull() ?: return emptyList()
    return selection.intervalSignals(view, selection.initial) +
      selection.intervalTail(view, unit = selection.unitName(), initial = selection.initial)
  }

  /**
   * The voronoi overlay a `nearest` selection picks through, laid over the marks it was built from.
   *
   * Directly **after** the mark it covers, which is where upstream splices it: the cells have to be
   * over the points to catch the pointer, and under anything drawn later.
   */
  private fun withVoronoi(views: List<UnitView>, marks: List<VegaValue>): List<VegaValue> {
    val overlays = views.mapNotNull { view ->
      selections
        .firstOrNull { it.nearest && (it.owner == null || it.owner === view) }
        ?.voronoiMark(view)
        ?.let { view.prefixed("marks") to it }
    }
    if (overlays.isEmpty()) return marks
    return marks.flatMap { mark ->
      val after = overlays.filter { it.first == mark.string("name") }.map { it.second }
      listOf(mark) + after
    }
  }

  /** The brush a selection is dragged as, drawn around the marks of the plot that declared it. */
  private fun brushed(views: List<UnitView>, marks: List<VegaValue>): List<VegaValue> {
    val own = selections.filter { it.owner == null || it.owner in views }
    val view = views.firstOrNull() ?: return marks
    // Each brush **wraps** the list rather than joining it: upstream's `marks` hook returns
    // `[background, ...marks, brush]`, so a second selection's background lands outside the first's
    // and its outline outside that one's. Two brushes over one plot are drawn in opposite orders
    // above and below the marks, and that is why.
    return own.fold(withVoronoi(views, marks)) { inner, selection ->
      val where = selection.owner ?: view
      selection.brushMarks(where, selection.unitName(), background = true) +
        inner +
        selection.brushMarks(where, selection.unitName(), background = false)
    }
  }

  /** Which of a composition's scales and guides its children share, and which they do not. */
  private val resolve = Resolve(spec.obj("resolve"))
  private val parser = Parse(config, diagnostics, selections)

  /** `config.facet.spacing` and the gap a header title keeps from its cells. */
  private val FACET_SPACING = 20.0
  private val HEADER_OFFSET = 10.0

  /** The grid this chart's cells are laid out in, if it is faceted at all. */
  private var facet: FacetLayout? = null

  /** The concatenation this chart is, if it is one. */
  private var concat: Concat? = null

  fun run(): VegaLiteCompilation {
    reportUnsupportedTopLevel()

    // A repetition is rewritten into a concatenation before anything is compiled, exactly as
    // upstream normalizes it, so there is nothing further down that knows what `repeat` is.
    if (spec.has("repeat")) spec = Repeat.normalize(spec, diagnostics) ?: return failed()
    // A `row`/`column` facet operator is the same chart as the same two channels written in the
    // encoding, so it becomes one before anything else looks at it.
    if (spec.has("facet")) spec = FacetOperator.normalize(spec, diagnostics) ?: return failed()
    val plots = plots() ?: return failed()
    concat = (plotTree as? Node.Nest)?.concat
    if (plots.any { it.views.isEmpty() }) return failed()

    // A facet channel does not encode anything *within* a cell, so it is lifted out of the encoding
    // before the scales are built — and everything inside then measures a cell rather than the
    // surface.
    if (concat == null) plots.single().views = liftFacet(plots.single().views)

    val views = plots.flatMap { it.views }
    views.forEach { it.selections = selections }
    // A selection belongs to the view that declared it, which is only known now that the views are
    // named: `brush` in the second plot of a concatenation records `"concat_1"` in every tuple, and
    // draws its brush in that plot rather than across the chart.
    for (view in views) {
      // One per **name**: two plots that each declare a `hover` contribute one selection each, and
      // the first unclaimed one of that name is this view's. Claiming every unclaimed match would
      // give the first plot both and leave the second with nothing to react to.
      for (name in Selection.from(view.spec.params).map { it.name }) {
        selections.firstOrNull { it.name == name && it.owner == null }?.owner = view
      }
    }
    // A concatenation scales each of its plots separately along the axes and shares everything
    // else — `defaultScaleResolve` — so the position scales are merged within a plot and the rest
    // across the whole chart. That is why two plots side by side have their own `y` but one colour
    // legend between them.
    // `resolve` governs the *outermost* composition and nothing below it, as it does upstream where
    // every model carries its own: a top-level resolve settles a concatenation's plots against each
    // other, or, with no concatenation, a layer's views against each other.
    findIncompatibleScales(views)
    val allScales = mergeScales(views) { view, channel -> scaleName(view, channel) }
    // Which plot each scale belongs to, or none where several share it. That is the whole of what
    // decides where a scale and its legend are written: a shared one at the top, an independent one
    // inside the plot that owns it.
    // Ownership follows the **resolve**, not usage: a colour scale is shared between a
    // concatenation's plots because that is what `defaultScaleResolve` says, whether or not more
    // than one plot happens to draw with it. An independently resolved scale is named for the child
    // that owns it, so a name that is still its plain channel is a shared one.
    val owner =
      allScales.values.associate { scale ->
        scale.name() to
          if (scale.name() == scale.channel) null
          else plots.firstOrNull { plot -> plot.views.any { scale.name() in it.scaleNames.values } }
      }
    for (plot in plots) {
      plot.scales =
        LinkedHashMap(
          allScales.filterKeys { name -> plot.views.any { name in it.scaleNames.values } }
        )
    }

    // A selection **bound to the scales** is read back by the scales it moves: `domainRaw` is the
    // domain a pan or a zoom has arrived at, and Vega prefers it over the computed one whenever the
    // signal is not null — which is what makes the plot itself the thing being dragged.
    for (selection in selections.filter { it.bindsScales }) {
      val view = selection.owner ?: views.firstOrNull() ?: continue
      for ((channel, field) in selection.intervalChannels(view)) {
        val scale = allScales.values.firstOrNull { it.name() == view.scale(channel) } ?: continue
        // Only a continuous scale can be panned: there is no halfway between two categories.
        if (!Selection.isContinuous(scale.type)) continue
        scale.properties["domainRaw"] = signalRef("${selection.name}[${quoted(field)}]")
      }
    }
    // A scale domain may also name a selection outright — `{"domain": {"param": "brush"}}` — which
    // is one plot deciding another's extent rather than its rows. `parseSelectionExtent`: with no
    // `field` or `encoding` stated it is the selection's first projection, so a brush on one
    // channel needs nothing said about which.
    for (view in views) {
      for ((channel, def) in view.scaledChannels()) {
        val stated = def.scale?.obj("domain")?.takeIf { it.has("param") } ?: continue
        val named = stated.string("param") ?: continue
        val selection = selections.firstOrNull { it.name == named } ?: continue
        val projected = selection.intervalChannels(selection.owner ?: view)
        val field =
          stated.string("field")
            ?: stated.string("encoding")?.let { wanted ->
              projected.firstOrNull { it.first == wanted }?.second
            }
            ?: projected.firstOrNull()?.second
            ?: selection.fields.firstOrNull()
            ?: continue
        val scale = view.scaleComponents[channel] ?: continue
        scale.properties["domainRaw"] = signalRef("$named[${quoted(field)}]")
        view.clippedByScale = true
      }
    }
    // `scaleClip`: a mark whose position scale is driven by a selection is **clipped**, or a pan
    // that moves the domain past the data draws the rows that fell outside the plot.
    for (selection in selections.filter { it.bindsScales }) {
      (selection.owner ?: views.firstOrNull())?.clippedByScale = true
    }

    // The sizes are named before anything reads them, because what a concatenation calls them
    // depends on whether its plots agree: a row of equally wide plots shares one `childWidth`,
    // and a row of unequal ones keeps `concat_0_width` beside `concat_1_width`.
    nameSizes(plots)

    val data = assembleData(views).toMutableList()
    fillScaleDomains(views)
    // A **facet** that resolves a position independently gives each cell its own scale, and a
    // discrete one measured in steps then makes each cell its own width: there is no size for the
    // grid to share, so the cells count their own categories — `getCardinalityAggregateForChild`.
    cellCardinality =
      if (facet == null || concat != null) emptyMap()
      else {
        val view = views.first()
        setOf("x", "y")
          .filter { channel ->
            resolve.scaleIsIndependent(channel, defaultIndependent = false) &&
              view.scaleType(channel)?.let { Scales.hasDiscreteDomain(it) } == true &&
              // Keyed by **channel**, which is what `LayoutSize` reads: the merged map is keyed by
              // scale name, and an independent facet scale is called `child_x` rather than `x`.
              LayoutSize.value(views, plots.first().byChannel(), config, spec, channel) == null
          }
          .mapNotNull { channel ->
            view.spec.fieldDef(channel)?.let { channel to "distinct_${Fields.vgField(it)}" }
          }
          .toMap()
      }
    for (plot in plots) {
      plot.axes = assembleAxes(plot)
      plot.size =
        LayoutSize(
          plot.views,
          plot.byChannel(),
          config,
          plot.spec,
          plot.sizeNames,
          plot.prefix,
          cellCardinality,
        )
      // Where a cell sizes itself, the *expression* takes the place of the signal's name: it is
      // read in a `{"signal": …}` everywhere a size is read, so nothing else has to know.
      plot.size!!.expressions.forEach { (channel, expression) ->
        plot.sizeNames = plot.sizeNames + (channel to expression)
        plot.views.forEach {
          if (channel == "x") it.widthSignal = expression else it.heightSignal = expression
        }
      }
    }
    // A legend belongs where its scale does. A concatenation whose plots share a colour scale draws
    // one key beside the whole chart; one that resolves colour independently draws a key inside
    // each plot, because two keys standing for different scales cannot be one.
    val legendScale = mutableMapOf<String, String>()
    val allLegends = assembleLegends(views, legendScale)
    for (plot in plots) {
      plot.legends =
        if (concat == null) emptyList()
        else allLegends.filterKeys { owner[legendScale[it]] === plot }.values.toList()
    }
    val legends =
      allLegends.filterKeys { concat == null || owner[legendScale[it]] == null }.values.toList()
    // In the order upstream's `assembleLayoutSignals` walks the model tree: each level's own sizes
    // before it recurses, and within a level `width`, `height`, `childWidth`, `childHeight`. Then
    // the parameters, which is `assembleTopLevelModel`'s order — a parameter may read a size and
    // not the other way about.
    // One signal per *name*: a level of the tree contributes its own merged sizes and its children
    // contribute theirs, and a size two levels agree on is named once by each. Vega reads the
    // first and warns about the rest, so the duplicates are not harmless — they are a chart that
    // logs on every render.
    // `assembleTopLevelSignals`: a chart with any selection in it gets `unit`, which every tuple
    // records so a picked row can be traced back to the plot it was picked in. It goes **first**,
    // ahead of the sizes, because upstream unshifts it.
    val selectionSignals =
      if (selections.isEmpty()) emptyList()
      else
        listOf(
          obj {
            put("name", "unit")
            put("value", VegaValue.EmptyObject)
            put(
              "on",
              arr(
                listOf(
                  obj {
                    put("events", "pointermove")
                    put("update", "isTuple(group()) ? group() : unit")
                  }
                )
              ),
            )
          }
        )
    // Upstream's order: the layout's own sizes, then `unit`, then what each selection *resolves*
    // to — because a variable parameter may read one — then the variables, then the machinery that
    // writes the stores.
    val sizeSignals =
      sizeSignalsFor(plotTree).distinctBy { it.string("name") } +
        selectionSignals +
        // A selection's **controls** are part of the page rather than of the drawing, so they sit
        // at
        // the top with `unit` and before the signal the tests read.
        selections
          .distinctBy { it.name }
          .flatMap {
            it.inputSignals(it.owner ?: views.firstOrNull())
          } +
        selections
          .distinctBy { it.name }
          .map { selection ->
            // A selection **bound to the scales** in a chart of several views is resolved from the
            // signals its plot pushes outward rather than from its store: `vlSelectionResolve`
            // knows
            // nothing about bound scales, so upstream reassembles the state by hand from the
            // per-channel signals — and those are declared here, empty, for the plot to push into.
            val bound = boundOutward(selection, views)
            if (bound.isEmpty()) selection.resolveSignal()
            else
              obj {
                put("name", selection.name)
                put(
                  "update",
                  "{" +
                    bound.joinToString(", ") { (field, signal) -> "${quoted(field)}: $signal" } +
                    "}",
                )
              }
          } +
        selections
          .distinctBy { it.name }
          .flatMap { selection ->
            boundOutward(selection, views).map { (_, signal) -> obj { put("name", signal) } }
          } +
        Params.signals(spec, diagnostics) +
        // A concatenation writes each selection's machinery inside the plot that declared it,
        // where the marks it reacts to are; everything else is one plot, so it stays here.
        selections.filter { concat == null || it.owner == null }.flatMap { machinery(it, views) }
    val root = plots.first().size!!
    // The facets' own values, which the layout counts and the headers title themselves from — and,
    // for a wrapped facet, only along the directions a shared axis was actually drawn in.
    facet?.let { current ->
      val main = plots.single().axes.filter { (it["grid"] as? VegaValue.Bool)?.value != true }
      val horizontal = main.filter {
        it.string("orient") == "bottom" || it.string("orient") == "top"
      }
      data +=
        current.domainDatasets(
          counted = cellCardinality,
          source = views.first().mainData,
          vertical = (main - horizontal.toSet()).isNotEmpty(),
          horizontal = horizontal.isNotEmpty(),
        )
    }

    val vega = obj {
      put("\$schema", "https://vega.github.io/schema/vega/v6.json")
      // An empty description is no description: `isEmpty` drops it rather than announcing a chart
      // whose spoken summary is the empty string.
      put("description", spec.string("description")?.takeIf { it.isNotEmpty() })
      // The chart's own `background` beats the configured one: `config.background` is a theme's
      // default and a specification that states one is overriding the theme, not being overridden
      // by it.
      put("background", spec.fields["background"] ?: config.background)
      // A chart's own padding beats the theme's, as its background does: a specification stating
      // one is overriding what the configuration settled, not the other way about.
      put("padding", spec.fields["padding"] ?: config.padding)
      autosize(views)?.let { put("autosize", it) }
      put("width", mergedSize("width") ?: if (concat == null) root.width else null)
      put("height", mergedSize("height") ?: if (concat == null) root.height else null)
      // `cell` is the bordered plotting area; a chart with no Cartesian position — a pie — has no
      // plotting area to border, and upstream styles it as a plain `view` instead. A faceted chart
      // has no plotting area of its own at all: each of its cells carries the style, and neither
      // does a concatenation, whose plots are each their own cell.
      if (facet == null && concat == null) put("style", style(views))
      title()?.let { put("title", it) }
      // A top-level `view` block paints the *plotting area* rather than the surface around it, so
      // it becomes an `encode` on the chart's own group — `background` is the surface, `view.fill`
      // is the paper the marks sit on, and the two are different colours in the same chart.
      viewEncode()?.let { put("encode", it) }
      // A selection's **store** comes first, ahead of every table: nothing derives from it and
      // everything reads it, and upstream's assembly puts the selection data at the head.
      put(
        "data",
        arr(
          // The **last** child's stores come first: `assembleSelectionData` folds the children in
          // order and each prepends its own, so a chart whose second plot declares a selection has
          // that selection's store at the head. Within one view the order is the declaration's.
          selections
            .distinctBy { it.name }
            .sortedByDescending { views.indexOf(it.owner) }
            .map { it.storeData(it.owner ?: views.firstOrNull(), it.initial) } + data
        ),
      )
      if (sizeSignals.isNotEmpty()) put("signals", arr(sizeSignals))
      // A stated `spacing` is the gap between cells, and it beats the configured twenty.
      facet?.let {
        val spacing =
          spec.number("spacing") ?: config.raw.obj("facet")?.number("spacing") ?: FACET_SPACING
        val independent =
          setOf("x", "y").filter { channel ->
            resolve.scaleIsIndependent(channel, defaultIndependent = false)
          }
        put("layout", it.layout(spacing, HEADER_OFFSET, config, independent.toSet()))
      }
      concat?.let { put("layout", it.layout()) }
      // The cells' own scales are assembled before the marks that read them, the cell group being
      // where they are written.
      if (facet != null && concat == null) {
        cellScales =
          allScales.values.filter { it.name() != it.channel }.map { withinCell(assembleScale(it)) }
      }
      // A brush is drawn in **two** parts around the marks: its background under them so the data
      // stays legible through it, and its outline over them so it can be grabbed.
      put(
        "marks",
        arr(
          if (concat != null) groups(plotTree)
          else brushed(views, marks(views, plots.single().axes))
        ),
      )
      // Shared scales first, then each plot's own, which is the order upstream's assembly walks the
      // model tree in: the composition's own components before it recurses into its children.
      val scales =
        (allScales.values.filter { owner[it.name()] == null } +
            plots.flatMap { plot -> allScales.values.filter { owner[it.name()] === plot } })
          // A facet's independently resolved scales are built inside its cells, where the rows
          // they measure are, so they are not written beside the grid as well.
          .filterNot { facet != null && concat == null && it.name() != it.channel }
      if (scales.isNotEmpty()) put("scales", arr(scales.map { assembleScale(it) }))
      // A faceted chart has no axes of its own: the gridlines live in every cell and the labelled
      // axis in a header drawn once for the whole grid. A concatenation's axes live in its plots.
      if (facet == null && concat == null && plots.single().axes.isNotEmpty()) {
        put("axes", arr(plots.single().axes))
      }
      if (legends.isNotEmpty()) put("legends", arr(legends))
      // The theme, as Vega takes it. Without this a chart's guides are drawn in the engine's own
      // colours however carefully the specification restyled them.
      config.forVega()?.let { put("config", it) }
    }

    // The signals two merged bins agreed to share, applied everywhere they are read. Upstream keeps
    // a rename map and consults it at every reference; here the references are already written, so
    // the map is applied to the finished specification.
    return VegaLiteCompilation(renamed(vega, signalRenames), diagnostics.diagnostics)
  }

  /**
   * What one view's scale on a channel is called.
   *
   * A shared channel keeps its plain name; an independent one takes the name of the child that owns
   * it, which is the plot inside a concatenation and the view inside a layer. A concatenation's
   * positions are independent unless the specification says otherwise — `defaultScaleResolve` — and
   * everything else, at either level, is shared unless it does.
   */
  private fun scaleName(view: UnitView, channel: String): String {
    // `defaultScaleResolve`: a concatenation's plots measure their own positions and their own
    // polar extents; a **facet's** cells share everything but `theta`, which is the one channel
    // whose extent is a cell's own — a trellis of pies compares slices within each pie, not
    // across the grid.
    // A channel whose children disagree about the *kind* of scale it is resolves independently
    // whatever the resolve says, because there is no one scale for them to share.
    if (channel in incompatibleChannels) {
      val owner = if (concat != null) plotOf(view) else view.childName
      return if (owner.isEmpty()) channel else "${owner}_$channel"
    }
    val independent =
      resolve.scaleIsIndependent(
        channel,
        defaultIndependent =
          if (concat != null) channel in Channels.POSITION_SCALE_CHANNELS || channel == "theta"
          else facet != null && channel == "theta",
      )
    if (!independent) return prefixed(channel)
    val owner = if (concat != null) plotOf(view) else view.childName
    return if (owner.isEmpty()) channel else "${owner}_$channel"
  }

  /** Per channel, the column a cell counts its own categories in — empty for every other chart. */
  private var cellCardinality: Map<String, String> = emptyMap()

  /** Channels whose views disagree about the scale type, and so cannot share one. */
  private val incompatibleChannels = mutableSetOf<String>()

  /**
   * `parseNonUnitScaleCore`: a shared channel is forced independent when the types cannot merge.
   *
   * The check is per **name**, not per channel outright: a concatenation that already resolves `x`
   * per plot has nothing to disagree about, and two layers that would share a colour scale — one a
   * ramp over counts, one a pair of named colours — have everything.
   */
  private fun findIncompatibleScales(views: List<UnitView>) {
    val byName = mutableMapOf<String, MutableList<Pair<String, String>>>()
    for (view in views) {
      for ((channel, def) in view.scaledChannels()) {
        val type =
          Scales.scaleType(
            channel,
            def,
            view.spec.mark,
            hasOffset = offsetChannelFor(channel)?.let { view.spec.encoding[it] != null } == true,
          )
        byName.getOrPut(scaleName(view, channel)) { mutableListOf() } += channel to type
      }
    }
    for ((_, entries) in byName) {
      val types = entries.map { it.second }
      if (types.any { one -> types.any { !Scales.compatible(one, it) } }) {
        incompatibleChannels += entries.first().first
      }
    }
  }

  /**
   * The per-channel signals a scale-bound selection pushes out of its plot, where it is in one.
   *
   * `scales.ts`'s `topLevelSignals`: a chart of several views cannot resolve a bound selection from
   * its store, because every unit writes a tuple and the state is whichever unit moved last. The
   * signals themselves carry it instead, declared at the top and written from inside.
   */
  private fun boundOutward(
    selection: Selection,
    views: List<UnitView>,
  ): List<Pair<String, String>> {
    if (!selection.bindsScales || concat == null) return emptyList()
    val view = selection.owner ?: views.firstOrNull() ?: return emptyList()
    return selection.intervalChannels(view).map { (_, field) ->
      field to Fields.varName("${selection.name}_$field")
    }
  }

  /** Signals that changed their name when two nodes were folded together. */
  private val signalRenames = mutableMapOf<String, String>()

  /**
   * The specification with every renamed signal read under its new name.
   *
   * Textual, because a signal is read as *text*: `datum["…"]` in an expression, a `signal` field, a
   * scale's `bins`. The names are the compiler's own — `child__layer_US_Gross_bin_maxbins_10_…` —
   * so there is nothing else they could match.
   */
  private fun renamed(value: VegaValue, renames: Map<String, String>): VegaValue.Obj =
    renamedValue(value, renames) as VegaValue.Obj

  private fun renamedValue(value: VegaValue, renames: Map<String, String>): VegaValue {
    if (renames.isEmpty()) return value
    fun walk(node: VegaValue): VegaValue =
      when (node) {
        is VegaValue.Str -> {
          var text = node.value
          for ((from, to) in renames) text = text.replace(from, to)
          VegaValue.Str(text)
        }
        is VegaValue.Arr -> VegaValue.Arr(node.values.map { walk(it) })
        is VegaValue.Obj ->
          VegaValue.Obj(node.fields.entries.associate { (key, own) -> key to walk(own) })
        else -> node
      }
    return walk(value)
  }

  /** The chart's own name in front of a shared name, where the specification gave it one. */
  private fun prefixed(name: String): String =
    listOf(spec.string("name").orEmpty(), name).filter { it.isNotEmpty() }.joinToString("_")

  private fun plotOf(view: UnitView): String = plotNames[view] ?: ""

  private val plotNames = mutableMapOf<UnitView, String>()

  private fun failed() = VegaLiteCompilation(null, diagnostics.diagnostics)

  /**
   * The style a group is drawn in, from `assembleGroupStyle` in `unit.ts` and `layer.ts`.
   *
   * Each view answers for itself — `cell` if it has a Cartesian position, `view` if it does not —
   * and a layer takes the union of its children's answers rather than one verdict for the lot. So a
   * scatter plot with a caption pinned to its corner is styled `["cell", "view"]`: the points want
   * a bordered plotting area and the caption, which has no position at all, does not.
   */
  private fun style(views: List<UnitView>): VegaValue? {
    val styles = LinkedHashSet<String>()
    for (view in views) {
      styles +=
        if (view.spec.encoding["x"] != null || view.spec.encoding["y"] != null) "cell" else "view"
    }
    return when (styles.size) {
      0 -> null
      1 -> VegaValue.Str(styles.first())
      else -> strings(styles.toList())
    }
  }

  // -----------------------------------------------------------------------------------------
  // Plots
  // -----------------------------------------------------------------------------------------

  /**
   * One plot being compiled: a whole chart, or one cell of a concatenation.
   *
   * A concatenation is not a chart with more marks in it — each of its plots has its own scales,
   * its own axes and its own size, and only the data and the legends are shared. So everything from
   * the scales onwards is held per plot rather than once for the specification.
   */
  private class Plot(val name: String, val spec: VegaValue.Obj) {
    val prefix: String = if (name.isEmpty()) "" else "${name}_"
    var views: List<UnitView> = emptyList()
    var scales: LinkedHashMap<String, ScaleComponent> = LinkedHashMap()
    var axes: List<VegaValue> = emptyList()

    /**
     * The channels this plot actually draws an axis on.
     *
     * `assembleAxisSignals` walks the axes rather than the scales, and that is the whole
     * difference: a plot whose axis is switched off has no grid to draw across anything, so it
     * needs no alias for the size it would have drawn it over.
     */
    var gridlessAxes: List<String> = emptyList()
    var legends: List<VegaValue> = emptyList()
    var size: LayoutSize? = null
    var sizeNames: Map<String, String> = mapOf("x" to "width", "y" to "height")

    /**
     * One scale per channel, the first view that owns one winning.
     *
     * The plotting area is measured against *a* scale on each position channel, and where two views
     * resolve one independently the first is the one whose step the plot is derived from — which is
     * upstream's own merge, taking the first child that answers.
     */
    fun byChannel(): Map<String, ScaleComponent> {
      val out = LinkedHashMap<String, ScaleComponent>()
      for (view in views) view.scaleComponents.forEach { (channel, scale) ->
        out.putIfAbsent(channel, scale)
      }
      return out
    }
  }

  /**
   * How the plots nest, which is separate from what each plot holds.
   *
   * A concatenation may hold another, and then the names compose — `concat_0_concat_1_x` — and the
   * inner one carries a `layout` of its own inside the outer one's group. Everything that a *plot*
   * has (views, scales, axes, a size) still belongs to the leaves; this is only the shape they are
   * arranged in, so the leaf list stays flat and nothing downstream has to know about the tree.
   */
  private sealed interface Node {
    class Leaf(val plot: Plot) : Node

    class Nest(
      val name: String,
      val concat: Concat,
      val children: List<Node>,
      /** The specification this level was built from, which may carry a title of its own. */
      val spec: VegaValue.Obj = VegaValue.EmptyObject,
    ) : Node {
      /** The size signal this level merged its children into, per channel, where it merged one. */
      val owns: MutableMap<String, String> = mutableMapOf()
    }
  }

  private lateinit var plotTree: Node

  private fun plots(): List<Plot>? {
    val leaves = mutableListOf<Plot>()

    fun build(name: String, child: VegaValue.Obj): Node? {
      val nested = Concat.of(child, diagnostics, config)
      if (nested == null) {
        val plot = Plot(name, child)
        plot.views = views(plot.spec, plot.name) ?: return null
        plot.views.forEach { plotNames[it] = plot.name }
        leaves += plot
        return Node.Leaf(plot)
      }
      val children =
        nested.children.mapIndexed { index, (declared, entry) ->
          // A plot that names itself is compiled under that name, which is how a *repetition*'s
          // copies come out `child__b` rather than `concat_0`: upstream's model takes `spec.name`
          // over the name its parent offered it. Below the first level the names compose.
          val here =
            declared ?: listOf(name, "concat_$index").filter { it.isNotEmpty() }.joinToString("_")
          build(here, entry) ?: return null
        }
      return Node.Nest(name, nested, children, child)
    }

    // A chart that **names itself** is compiled under that name, the way a concatenation's children
    // are: `getName` prefixes every scale, mark and step signal with the model's own name, so a
    // specification with `"name": "plotname"` reads `plotname_x` throughout. The sizes are the
    // exception — `width` and `height` are the chart's, not this level's.
    plotTree = build(spec.string("name").orEmpty(), spec) ?: return null
    return leaves
  }

  /** Every concatenation in the tree, outermost first, which is the order the sizes merge in. */
  private fun nests(node: Node = plotTree): List<Node.Nest> =
    when (node) {
      is Node.Leaf -> emptyList()
      is Node.Nest -> listOf(node) + node.children.flatMap { nests(it) }
    }

  /** The leaves under a node, which are the plots whose sizes it merges. */
  private fun leavesOf(node: Node): List<Plot> =
    when (node) {
      is Node.Leaf -> listOf(node.plot)
      is Node.Nest -> node.children.flatMap { leavesOf(it) }
    }

  // -----------------------------------------------------------------------------------------
  // Sizes
  // -----------------------------------------------------------------------------------------

  /** What each plot's two size signals are called, and what the merged ones are called. */
  private val merged = LinkedHashMap<String, VegaValue>()

  private fun nameSizes(plots: List<Plot>) {
    if (concat == null) {
      val plot = plots.single()
      val prefix = if (facet != null) "child_" else ""
      plot.sizeNames = mapOf("x" to "${prefix}width", "y" to "${prefix}height")
      plot.views.forEach {
        it.widthSignal = plot.sizeNames.getValue("x")
        it.heightSignal = plot.sizeNames.getValue("y")
      }
      return
    }

    // Every concatenation merges its *own* children, innermost first, because a nested one settles
    // its size before the level above can ask what that size is.
    for (nest in nests().reversed()) mergeSizes(nest)
  }

  /**
   * The size a node contributes to the level above, or null where it has none to contribute.
   *
   * A leaf's is what its own scales and declared size make of it. A **concatenation's** is whatever
   * its children merged into — and nothing where they did not agree, which is what stops a column
   * holding a row of plots from claiming a width of its own.
   */
  private fun sizeOf(node: Node, channel: String): VegaValue? =
    when (node) {
      is Node.Leaf ->
        LayoutSize.value(
          node.plot.views,
          // Keyed by *channel*: `plot.scales` is keyed by scale name, and inside a concatenation
          // those are `concat_0_x`, so looking a channel up in it finds nothing and every plot
          // looks scale-less — which merged three plots of different depths into one signal.
          node.plot.byChannel(),
          config,
          node.plot.spec,
          channel,
        )
      // A nested concatenation contributes upward only the dimension it shares **as a whole**. A
      // column of plots is one width and a stack of heights; its `childHeight` is one cell's, not
      // the column's, so a row of columns must not merge on it. The plain name is what says which
      // of the two a level settled.
      is Node.Nest ->
        nestSizes[node]?.get(channel)?.takeIf {
          val plain = if (channel == "x") "width" else "height"
          node.owns[channel]?.removePrefix(node.prefix()) == plain
        }
    }

  /** What each concatenation merged its children's sizes into, by channel. */
  private val nestSizes = mutableMapOf<Node.Nest, MutableMap<String, VegaValue?>>()

  private fun mergeSizes(nest: Node.Nest) {
    // `parseConcatLayoutSize`: a row of plots shares one height and a column shares one width, so
    // the merged size keeps the plain name there and becomes a `child` size where it does not.
    val mergedName =
      mapOf(
        "x" to if (nest.concat.columns == 1) "width" else "childWidth",
        "y" to if (nest.concat.columns == null) "height" else "childHeight",
      )
    val settled = nestSizes.getOrPut(nest) { mutableMapOf() }
    for (channel in listOf("x", "y")) {
      // Merge only where every child agrees and none of them is sized by a step, which is what
      // `parseNonUnitLayoutSizeForChannel` abandons the merge on.
      val values = nest.children.map { sizeOf(it, channel) }
      val agreed = values.first()
      val mergeable = agreed != null && values.all { it == agreed }
      val plain = if (channel == "x") "width" else "height"
      val name = if (mergeable) "${nest.prefix()}${mergedName.getValue(channel)}" else null
      // The name a *leaf* measures against. A leaf under a merged level takes the merged name; one
      // under an unmerged level keeps its own, which is what puts `concat_0_height` beside
      // `concat_1_height` where two plots in a column are different heights.
      for (child in nest.children) {
        if (name == null && child is Node.Nest) continue
        val own = name ?: "${nameOf(child)}_$plain"
        when (child) {
          is Node.Leaf -> {
            child.plot.sizeNames = child.plot.sizeNames + (channel to own)
            child.plot.views.forEach {
              if (channel == "x") it.widthSignal = own else it.heightSignal = own
            }
          }
          // A merged level renames its children's already-settled signals rather than adding one.
          is Node.Nest -> renameSize(child, channel, own)
        }
      }
      // What this level contributes upwards, and the signal it writes if nothing above takes it.
      settled[channel] = if (mergeable) agreed else null
      if (mergeable) {
        nest.owns[channel] = name!!
        merged[name] = agreed
        // A level that merges its children takes the name over from them, so the signal is written
        // once and at the level that settled it.
        for (child in nest.children) {
          if (child is Node.Nest) child.owns.remove(channel)?.let { merged.remove(it) }
        }
      }
    }
  }

  private fun nameOf(node: Node): String =
    when (node) {
      is Node.Leaf -> node.plot.name
      is Node.Nest -> node.name
    }

  private fun Node.Nest.prefix(): String = if (name.isEmpty()) "" else "${name}_"

  /** A level above renamed what this one merged, so everything under it follows. */
  private fun renameSize(nest: Node.Nest, channel: String, name: String) {
    for (plot in leavesOf(nest)) {
      if (plot.sizeNames[channel]?.endsWith(if (channel == "x") "width" else "height") != true)
        continue
      plot.sizeNames = plot.sizeNames + (channel to name)
      plot.views.forEach { if (channel == "x") it.widthSignal = name else it.heightSignal = name }
    }
  }

  /** A merged size that is a plain number and is called `width` or `height` goes to the top. */
  private fun mergedSize(name: String): VegaValue? = merged[name]

  private fun sizeSignalsFor(node: Node): List<VegaValue> =
    when (node) {
      is Node.Leaf -> node.plot.size!!.signals.filter { it.string("name") !in merged }
      is Node.Nest ->
        listOf("x" to "width", "y" to "height", "x" to "childWidth", "y" to "childHeight")
          .mapNotNull { (channel, kind) ->
            node.owns[channel]?.takeIf { it == "${node.prefix()}$kind" }
          }
          // A merged size called plainly `width` or `height` is a top-level *property*, not a
          // signal, which is upstream's own last step in `assembleTopLevelModel`.
          .filter { it != "width" && it != "height" }
          .mapNotNull { name ->
            merged[name]?.let { value ->
              obj {
                put("name", name)
                put("value", value)
              }
            }
          } + node.children.flatMap { sizeSignalsFor(it) }
    }

  private fun mergedSizeSignals(): List<VegaValue> =
    merged.entries
      .filter { it.key != "width" && it.key != "height" }
      .map { (name, value) ->
        obj {
          put("name", name)
          put("value", value)
        }
      }

  // -----------------------------------------------------------------------------------------
  // Concatenated plots
  // -----------------------------------------------------------------------------------------

  /**
   * A group per plot: its marks, its axes, and the size it is drawn at.
   *
   * `ConcatModel.assembleMarks` — a concatenation has no marks of its own, and its layout places
   * the groups rather than the marks inside them.
   */
  /**
   * A group per plot, and a group per concatenation holding them.
   *
   * A nested concatenation is a group with a `layout` and no size of its own: it is not a plotting
   * area, only a place its plots are arranged in, so it carries neither a style nor an `encode`.
   */
  private fun groups(node: Node): List<VegaValue> =
    when (node) {
      is Node.Nest ->
        node.children.flatMap { child ->
          if (child !is Node.Nest) groups(child)
          else
            listOf(
              obj {
                put("type", "group")
                put("name", "${child.name}_group")
                // A nested concatenation may still be titled, and being a composition it anchors
                // to the start rather than framing a plotting area it does not have.
                child.spec.fields["title"]?.let { put("title", titleFor(it, composed = true)) }
                put("layout", child.concat.layout())
                put("marks", arr(groups(child)))
              }
            )
        }
      is Node.Leaf -> listOf(leafGroup(node.plot))
    }

  private fun leafGroup(plot: Plot): VegaValue = run {
    obj {
      put("type", "group")
      put("name", "${plot.name}_group")
      // A plot inside a composition is still a unit or a layer, so its own title frames the group.
      plot.spec.fields["title"]?.let { put("title", titleFor(it, composed = false)) }
      put("style", style(plot.views))
      put(
        "encode",
        obj {
          put(
            "update",
            obj {
              put("width", signalRef(plot.sizeNames.getValue("x")))
              put("height", signalRef(plot.sizeNames.getValue("y")))
              // A plot's own `view` block paints its plotting area, the same as the chart's does:
              // a middle column drawn without a border says so on itself rather than on the chart.
              (viewEncode(plot.spec) as? VegaValue.Obj)?.obj("update")?.fields?.forEach {
                (key, value) ->
                put(key, value)
              }
            },
          )
        },
      )
      val local =
        localSizeSignals(plot) +
          selections
            .filter { it.owner in plot.views }
            .flatMap { selection ->
              val pushed = boundOutward(selection, plot.views).map { it.second }.toSet()
              machinery(selection, plot.views).map { signal ->
                // A signal the top level declares is *written* here and read there — `push:
                // "outer"`
                // is how Vega says which of the two directions this one goes.
                if ((signal as? VegaValue.Obj)?.string("name") !in pushed) signal
                else
                  obj {
                    (signal as VegaValue.Obj).fields.forEach { (key, value) -> put(key, value) }
                    put("push", "outer")
                  }
              }
            }
      if (local.isNotEmpty()) put("signals", arr(local))
      put("marks", arr(brushed(plot.views, plot.views.flatMap { Marks.marks(it) })))
      if (plot.axes.isNotEmpty()) put("axes", arr(plot.axes))
      if (plot.legends.isNotEmpty()) put("legends", arr(plot.legends))
    }
  }

  /**
   * The size a plot's own axes read, where the plot is not called what they say.
   *
   * `assembleAxisSignals`: an axis draws its gridlines across the *other* scale's extent, and where
   * there is no other scale it falls back to `width` or `height` by name. Inside a concatenation
   * that name means the whole chart, so the plot aliases it to its own — without which a plot with
   * one encoded axis draws its grid the height of everything beside it.
   */
  private fun localSizeSignals(plot: Plot): List<VegaValue> {
    val out = LinkedHashMap<String, String>()
    // The alias belongs to an **axis**, not to a scale: `assembleAxisSignals` walks the assembled
    // axes and asks each one without a `gridScale` for the size it will fall back to. A plot whose
    // axis is switched off has nothing to draw a grid across and needs no alias at all.
    for (channel in plot.gridlessAxes) {
      val other = if (channel == "x") "y" else "x"
      // Only where there is no other scale to draw the grid across: with one, the axis names it in
      // `gridScale` and needs no size at all.
      if (plot.byChannel()[other] != null) continue
      val name = if (other == "x") "width" else "height"
      val update = plot.views.first().sizeSignal(other)
      if (update != name) out[name] = update
    }
    return out.map { (name, update) ->
      obj {
        put("name", name)
        put("update", update)
      }
    }
  }

  // -----------------------------------------------------------------------------------------
  // Views
  // -----------------------------------------------------------------------------------------

  private fun views(spec: VegaValue.Obj, namePrefix: String): List<UnitView>? {
    fun named(suffix: String) =
      listOf(namePrefix, suffix).filter { it.isNotEmpty() }.joinToString("_")

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
    if (spec.has("layer")) {
      // Each declared layer may itself normalize into more than one — a line that draws its own
      // points is two marks — so the list is expanded first and only then numbered. The numbering
      // is what names `layer_0_marks`, so it has to count the views that actually exist.
      val units = mutableListOf<Pair<Triple<String, VegaValue.Obj, String>, String>>()

      /**
       * A layer's members, and the members of any layer among them.
       *
       * A layer inside a layer needs nothing new: its names simply run deeper —
       * `layer_1_layer_0_marks` — which is exactly what a composite mark inside a layer already
       * produces, so the naming was already carrying it. What the recursion has to keep hold of is
       * the name of the **outermost** member, because that is the child a top-level `resolve`
       * speaks about; the nesting below it is not a level anything resolves against.
       */
      fun collect(parent: VegaValue.Obj, prefix: String, owner: String?, path: String) {
        parent.array("layer").orEmpty().forEachIndexed { index, layer ->
          val child = layer as? VegaValue.Obj ?: return@forEachIndexed
          val merged = inherited(parent, child)
          // A layer that names itself is compiled under that name, which is what a `repeat` over
          // `layer` relies on: its copies are `child__layer_b`, not `layer_0`.
          val here =
            child.string("name")
              ?: listOf(prefix, "layer_$index").filter { it.isNotEmpty() }.joinToString("_")
          val here2 = "$path.layer[$index]"
          if (child.has("layer")) {
            collect(merged, here, owner ?: here, here2)
          } else {
            expand(merged, here).forEach {
              units += Triple(it.first, it.second, owner ?: here) to here2
            }
          }
        }
      }
      collect(spec, namePrefix, null, "$")

      return units.mapNotNull { (named, path) ->
        val (name, unit, child) = named
        parser.unit(unit, path)?.let { UnitView(it, config, name, child) }
      }
    }

    for (composition in listOf("facet", "repeat")) {
      if (spec.fields.containsKey(composition)) {
        diagnostics.fatal(
          VegaLiteDiagnostics.UNSUPPORTED_COMPOSITION,
          "`$composition` is not implemented. A single view, a `layer` of views and a " +
            "concatenation of either compile; this composition does not, and would need its own " +
            "layout.",
          jsonPath = "$.$composition",
        )
        return null
      }
    }

    // A single view that normalizes into several becomes a layer of them, which is exactly what
    // upstream does: the normalizer hands its result back to the compiler as a layer spec. One
    // that normalizes into exactly one stays a single view, and keeps its unprefixed names.
    if (composite.normalize(spec) != null || normalize.pathOverlay(spec) != null) {
      return expand(spec, namePrefix).mapNotNull { (name, unit) ->
        parser.unit(unit, "$")?.let { UnitView(it, config, name) }
      }
    }

    val unit = parser.unit(spec, "$") ?: return null
    return listOf(UnitView(unit, config, namePrefix))
  }

  /**
   * A layer's own definition over the chart's.
   *
   * A layer inherits the chart's data, size, transforms and *encoding* unless it states its own.
   * The encoding matters most: writing the shared channels once above the layers and the
   * differences inside them is the ordinary way to author a layered chart, and a layer that did not
   * inherit them would draw its mark with no position at all.
   */
  private fun inherited(spec: VegaValue.Obj, child: VegaValue.Obj): VegaValue.Obj = obj {
    put("data", spec.fields["data"])
    put("width", spec.fields["width"])
    put("height", spec.fields["height"])
    putAll(child)
    // A child's transforms come **after** its parent's rather than instead of them: the parent's
    // belong to the parent's own data chain and the child's hang below. Letting the child's replace
    // them ran a filter over a column the parent's formula had not yet written.
    val inheritedTransforms = spec.array("transform").orEmpty() + child.array("transform").orEmpty()
    put("transform", if (inheritedTransforms.isEmpty()) null else arr(inheritedTransforms))
    val shared = spec.obj("encoding")
    if (shared != null) {
      // Channel by channel, and **property by property within a channel**: `mergeEncoding` spreads
      // the parent's channel def under the child's, so a shared `x` stating the type and a layer's
      // `x` naming only the field come out as one definition with both. Replacing the whole channel
      // instead loses the type, and a quantitative measure is then spoken as a category.
      put(
        "encoding",
        obj {
          val own = child.obj("encoding")
          val channels = shared.fields.keys + own?.fields?.keys.orEmpty()
          for (channel in channels) {
            val parent = shared.fields[channel] as? VegaValue.Obj
            val mine = own?.fields?.get(channel)
            put(
              channel,
              if (parent != null && mine is VegaValue.Obj) {
                obj {
                  putAll(parent)
                  putAll(mine)
                }
              } else mine ?: shared.fields[channel],
            )
          }
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
    val wrapped = views.firstNotNullOfOrNull { view ->
      view.spec.encoding["facet"]?.takeIf { it.isFieldDef }
    }
    if (row == null && column == null && wrapped == null) return views
    val crossed = row != null && column != null
    listOfNotNull(row, column).forEach { it.reportUnsupportedSort(diagnostics, crossed) }
    val found: FacetLayout =
      // A wrapped facet written as a **channel** carries its `columns` on the channel itself, where
      // the operator form carries it beside the facet: `mapFacetedUnit` lifts the one to the other,
      // and reading only the outer place left a grid that never wrapped.
      if (wrapped != null)
        FacetWrap(wrapped, (spec.number("columns") ?: wrapped.raw.number("columns"))?.toInt())
      else FacetGrid(row, column)
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
          it.widthSignal = "child_width"
          it.heightSignal = "child_height"
          it.facetFields = found.fields
          it.facetDefs = found.defs
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
  /**
   * The scales a facet's cells own, which are built inside the cell rather than beside the grid.
   */
  private var cellScales: List<VegaValue> = emptyList()

  /**
   * A cell-owned scale, reading the cell's own rows.
   *
   * The whole point of resolving a scale per cell is that its extent is measured over the rows the
   * facet handed *that* cell, and inside the group those rows are the partition Vega named `facet`.
   * Left pointing at the shared dataset the scale would be built per cell and identical in each.
   */
  private fun withinCell(scale: VegaValue): VegaValue {
    val block = scale as? VegaValue.Obj ?: return scale
    val domain = block.obj("domain") ?: return scale
    if (!domain.fields.containsKey("data")) return scale
    return obj {
      block.fields.forEach { (key, value) ->
        if (key != "domain") put(key, value)
        else
          put(
            "domain",
            obj {
              domain.fields.forEach { (name, own) ->
                put(name, if (name == "data") VegaValue.Str("facet") else own)
              }
            },
          )
      }
    }
  }

  private fun marks(views: List<UnitView>, axes: List<VegaValue>): List<VegaValue> {
    val childMarks = views.flatMap { Marks.marks(it) }
    val current = facet ?: return childMarks

    // An axis is drawn **once for the grid** only where its scale is the grid's: a channel each
    // cell scales for itself has an axis per cell, since one band of labels cannot stand for
    // several different extents. `parseGuideResolve` says the same thing about the guide.
    val independent =
      setOf("x", "y").filter { channel ->
        resolve.scaleIsIndependent(channel, defaultIndependent = false)
      }
    fun cellsOwn(axis: VegaValue): Boolean = independent.any { channel ->
      axis.string("scale")?.endsWith(channel) == true
    }
    val gridAxes = axes.filter { (it["grid"] as? VegaValue.Bool)?.value == true || cellsOwn(it) }
    val mainAxes = axes.filter { (it["grid"] as? VegaValue.Bool)?.value != true && !cellsOwn(it) }
    val horizontal = mainAxes.filter {
      it.string("orient") == "bottom" || it.string("orient") == "top"
    }
    val vertical = mainAxes - horizontal.toSet()

    return current.groups(
      vertical,
      horizontal,
      HEADER_OFFSET,
      config,
      views.first().widthSignal,
      views.first().heightSignal,
    ) +
      current.cellGroup(
        views.first().mainData,
        childMarks,
        gridAxes,
        views.first().widthSignal,
        views.first().heightSignal,
        HEADER_OFFSET,
        // A cell is styled by the same rule the chart's own group is: `cell` where it has a
        // Cartesian position to border, `view` where it has none. A trellis of pies has no
        // plotting area in any of its cells.
        (style(views) as? VegaValue.Str)?.value ?: "cell",
        cellCardinality,
        cellScales,
      )
  }

  private fun reportUnsupportedTopLevel() {
    if (spec.fields.containsKey("projection")) {
      diagnostics.error(
        VegaLiteDiagnostics.UNSUPPORTED_TOP_LEVEL_PROPERTY,
        "`projection` is not implemented; a geographic chart needs projection support in the " +
          "runtime first.",
        jsonPath = "$.projection",
      )
    }
  }

  /**
   * `normalizeAutoSize` and `getTopLevelProperties`, which settle the same property in two places.
   *
   * A chart says nothing about sizing and gets `pad`, which is Vega's own default and so is written
   * as nothing at all. Two things change that. A size of **`"container"`** asks the page for it, so
   * the chart is *fitted* along that direction — and `contains: "padding"` with it, because the
   * element's width includes the padding the chart would otherwise add outside it. And an axis
   * whose orientation is driven by a **parameter** needs `resize`, since the drawing is re-laid out
   * when the axis moves from one side to the other and a padded surface would keep the old extent.
   */
  private fun autosize(views: List<UnitView>): VegaValue? {
    val declared = spec.fields["autosize"]
    val stated = (declared as? VegaValue.Str)?.let { obj { put("type", it.value) } } ?: declared
    val responsive =
      listOf("width" to "fit-x", "height" to "fit-y").filter {
        spec.fields[it.first] == VegaValue.Str("container")
      }
    val fitted =
      when {
        responsive.size == 2 -> "fit"
        responsive.size == 1 -> responsive.single().second
        else -> null
      }
    val resize = views.any { view -> Guides.hasSignalOrient(view) }
    val merged = obj {
      put("type", "pad")
      if (fitted != null) {
        put("type", fitted)
        put("contains", "padding")
      }
      if (resize) put("resize", VegaValue.Bool(true))
      (stated as? VegaValue.Obj)?.fields?.forEach { (key, value) -> put(key, value) }
    }
    // Vega's own default is written as nothing; a type on its own is written as the bare string.
    if (merged.fields.keys == setOf("type")) {
      val type = merged.string("type")
      return if (type == "pad") null else VegaValue.Str(type.orEmpty())
    }
    return merged
  }

  private fun title(): VegaValue? {
    val declared = spec.fields["title"] ?: return null
    // `assembleTitle` reads the two kinds of model differently. A **unit or layer** anchors its
    // title to the *group* rather than to the whole surface, which keeps it over the plotting area
    // when an axis widens the drawing to its left. A **composition** cannot: its groups are laid
    // out and there is no one plotting area to sit over, so it takes `anchor: "start"` instead —
    // upstream's note is that a centred title "does not look nice" over a grid.
    val encoding = spec.obj("encoding")
    val composed =
      spec.has("facet") ||
        spec.has("concat") ||
        spec.has("hconcat") ||
        spec.has("vconcat") ||
        spec.has("repeat") ||
        // A `row`/`column` channel is a facet written in the encoding, and the model it makes is a
        // facet model — so its title is a composition's, laid out above a grid.
        encoding?.has("row") == true ||
        encoding?.has("column") == true
    return titleFor(declared, composed)
  }

  /** The chart group's own `encode`, which is where a top-level `view` block's paint lands. */
  private fun viewEncode(from: VegaValue.Obj = spec): VegaValue? {
    val view = from.obj("view") ?: return null
    // `assembleEncodeFromView` writes **everything** the block holds but its `style`, each as a
    // value ref: a `cursor` on the plotting area is how a chart says what the pointer looks like
    // over it, and reading only the paint left it out.
    val applied = view.fields.filterKeys { it != "style" }
    if (applied.isEmpty()) return null
    return obj {
      put(
        "update",
        obj {
          applied.forEach { (key, value) ->
            val expression =
              (value as? VegaValue.Obj)?.let { it.string("expr") ?: it.string("signal") }
            put(key, if (expression != null) signalRef(expression) else obj { put("value", value) })
          }
        },
      )
    }
  }

  /** `assembleTitle`, for a title on any model: the group frame, or the composition's anchor. */
  private fun titleFor(declared: VegaValue, composed: Boolean): VegaValue {
    val fields = (declared as? VegaValue.Obj)?.fields
    val text = fields?.get("text") ?: declared.takeIf { it is VegaValue.Str }
    if (text == null) return declared
    return obj {
      if (fields == null) put("text", text) else fields.forEach { (key, value) -> put(key, value) }
      if (composed) {
        if (fields?.containsKey("anchor") != true) put("anchor", "start")
      } else {
        val anchor = (fields?.get("anchor") as? VegaValue.Str)?.value
        if ((anchor == null || anchor == "middle") && fields?.containsKey("frame") != true) {
          put("frame", "group")
        }
      }
    }
  }

  // -----------------------------------------------------------------------------------------
  // Data
  // -----------------------------------------------------------------------------------------

  private fun assembleData(views: List<UnitView>): List<VegaValue> {
    if (views.any { it.spec.data == null }) {
      diagnostics.error(
        VegaLiteDiagnostics.UNSUPPORTED_TOP_LEVEL_PROPERTY,
        "The specification names no `data`, so there is nothing to draw.",
        jsonPath = "$.data",
      )
      return emptyList()
    }

    // Every table the chart reads, in the order it was first asked for, because that order *is*
    // the numbering: `source_0`, `source_1`. A layer or a plot with its own `data` gets a root of
    // its own — without which its marks are drawn against the first view's rows, which is a wrong
    // chart rather than a missing one — and a `lookup`'s joined table is a root here too, since a
    // join reads a second table rather than deriving from the first.
    val order = mutableListOf<VegaValue>()
    val roots = LinkedHashMap<VegaValue, SourceNode>()
    val register: (VegaValue) -> String = { table ->
      val existing = order.indexOf(table)
      "source_${if (existing >= 0) existing else order.size.also { order += table }}"
    }
    val outputs = views.map { view ->
      val data = view.spec.data!!
      if (data !in order) order += data
      DataPipeline(view, diagnostics, register, Selection.needsIdentity(selections))
        .build(roots.getOrPut(data) { SourceNode(data) })
    }
    // Every view built its own chain onto its source, so a shared tree forks there; the shared
    // parse is hoisted above the fork before the tree is named and flattened.
    // `optimizeDataflow` runs its whole sequence again until nothing moves, at most five times,
    // and that is not belt and braces: one optimizer's fold makes the next one's siblings. Two
    // layers each bucketing an instant and then aggregating are not sibling aggregates until the
    // time units have been folded together, so a single pass leaves the aggregates apart.
    for (root in roots.values) {
      var previous = ""
      repeat(5) {
        root.moveParseUp()
        root.removeDuplicateTimeUnits()
        root.mergeBins(signalRenames)
        root.mergeParse()
        root.mergeAggregates()
        root.mergeTimeUnits()
        root.mergeIdentical()
        root.mergeOutputs()
        val settled = root.signature()
        if (settled == previous) return@repeat
        previous = settled
      }
    }
    val datasets =
      DataAssembler()
        .also { it.named = spec.obj("datasets")?.fields.orEmpty() }
        .assemble(order.map { roots[it] ?: it })
    views.forEachIndexed { index, view ->
      view.mainData = outputs[index].main.source ?: ""
      view.rawData = outputs[index].raw?.source ?: view.mainData
    }
    return datasets
  }

  // -----------------------------------------------------------------------------------------
  // Scales
  // -----------------------------------------------------------------------------------------

  /**
   * Every scale a plot needs, keyed by the name it will carry, and each view's own map by channel.
   *
   * The name is the whole of what `resolve` decides. A shared channel keeps its plain name and one
   * component takes every view's domain; an independent one is named for the child that owns it —
   * the plot inside a concatenation, the view inside a layer — and each child gets a component of
   * its own. Two views on one shared channel merge by *type* first, and the more capable type wins
   * rather than the first-declared one: upstream ranks them (`SCALE_PRECEDENCE_INDEX`) and puts
   * `band` above `point` above everything continuous, "as they support more types of data", which
   * is how a box plot — a bar, a rule and two ticks — ends up on one band.
   */
  private fun mergeScales(
    views: List<UnitView>,
    name: (UnitView, String) -> String,
  ): LinkedHashMap<String, ScaleComponent> {
    val scales = LinkedHashMap<String, ScaleComponent>()
    for (view in views) {
      for ((channel, def) in view.scaledChannels()) {
        val type =
          Scales.scaleType(
            channel,
            def,
            view.spec.mark,
            hasOffset = offsetChannelFor(channel)?.let { view.spec.encoding[it] != null } == true,
          )
        val key = name(view, channel)
        val existing = scales[key]
        if (existing == null || Scales.precedence(type) > Scales.precedence(existing.type)) {
          scales[key] = ScaleComponent(channel, type, key)
        }
      }
    }
    for (view in views) {
      view.scaleNames =
        view.scaledChannels().associate { (channel, _) -> channel to name(view, channel) }
      view.scaleComponents =
        view.scaleNames.mapNotNull { (channel, key) -> scales[key]?.let { channel to it } }.toMap()
      view.scaleTypes = view.scaleComponents.mapValues { it.value.type }
    }
    return scales
  }

  private fun fillScaleDomains(views: List<UnitView>) {
    for (view in views) {
      for ((channel, def) in view.scaledChannels()) {
        val component = view.scaleComponents[channel] ?: continue
        // Renamed **before** the dedupe, not only in the finished specification: two layers that
        // bucket one column now read one bin signal, and two domains that have become the same
        // domain are one domain rather than a union of a thing with itself.
        val domains =
          Scales.domain(view, channel, def, component.type, view.mainData).map {
            renamedValue(it, signalRenames)
          }
        for (domain in domains) if (domain !in component.domains) component.domains += domain
        // `parseNonUnitScaleProperty` merges a shared scale **property by property**, not layer by
        // layer: the first layer to settle a property settles it, and the ones that say nothing
        // about it are passed over rather than ending the search. A candlestick's rules come first
        // and have no width to speak of, so the bar's `padding` is the only one anybody states.
        val contributed = ScaleComponent(channel, component.type, component.name())
        Scales.range(view, channel, def, component.type)?.let { contributed.set("range", it) }
        Scales.properties(view, channel, def, component.type, contributed)
        contributed.properties.forEach { (key, value) ->
          if (key !in component.properties) component.properties[key] = value
        }
        component.domainHasZero = Scales.domainHasZero(component)
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

  private fun assembleAxes(plot: Plot): List<VegaValue> {
    // Keyed by whatever makes two axes *two*: a shared scale merges its views' axes into one, and
    // an independently resolved guide keeps one per view. That is the whole of a dual-axis chart.
    val components = LinkedHashMap<String, Pair<String, Guides.AxisComponent>>()
    for (view in plot.views) {
      for (channel in Channels.POSITION_CHANNELS) {
        val def = view.spec.encoding[channel] ?: continue
        if (!def.isFieldDef && def.datum == null) continue
        val component = view.scaleComponents[channel] ?: continue
        val hasOther = view.scaleComponents.containsKey(if (channel == "x") "y" else "x")
        val parsed =
          Guides.parseAxis(view, channel, def, component.type, hasOther, diagnostics) ?: continue
        // Independence is resolved **between the children of the composition**, and a
        // concatenation's children are its plots — so the layers *inside* one plot still share an
        // axis. Keying per view there gave a layered plot two of every axis.
        val key =
          if (concat == null && guideIsIndependent(channel)) "${view.childName}|$channel"
          else component.name()
        val existing = components[key]
        if (existing == null) {
          components[key] = channel to parsed
        } else {
          val merged = existing.second
          when {
            // An explicit title wins outright rather than joining: a layer that names its axis has
            // said what the axis measures, and the other layer's derived name adds nothing.
            parsed.explicitTitle && !merged.explicitTitle -> {
              merged.titles.clear()
              merged.titles += parsed.titles
              merged.explicitTitle = true
            }
            merged.explicitTitle && !parsed.explicitTitle -> Unit
            else -> parsed.titles.forEach { if (it !in merged.titles) merged.titles += it }
          }
        }
      }
    }
    faceOff(components.values.toList())
    // Which axes will fall back to a *name* for the extent they draw their grid across:
    // `assembleAxisSignals` asks each component without a `gridScale`, and a plot inside a
    // composition then aliases that name to its own size.
    plot.gridlessAxes = components.values.map { it.first }
    val ordered = components.values.map { it.second }
    // Gridlines first, so they are painted behind every mark, then the axes themselves.
    return ordered.mapNotNull { Guides.assembleAxis(it, "grid") } +
      ordered.mapNotNull { Guides.assembleAxis(it, "main") }
  }

  private fun guideIsIndependent(channel: String): Boolean =
    resolve.guideIsIndependent(
      channel,
      resolve.scaleIsIndependent(
        channel,
        defaultIndependent =
          if (concat != null) channel in Channels.POSITION_SCALE_CHANNELS || channel == "theta"
          else facet != null && channel == "theta",
      ),
    )

  /**
   * Puts the second axis of a channel on the other side, and stops it ruling its own gridlines.
   *
   * `parseLayerAxes`: two independent axes on one channel would otherwise stack on the left, so
   * upstream counts how many have landed on each side and moves one across when they do not match —
   * counting the side it *asked* for rather than the side it ended on. And "show gridlines for the
   * first axis only for dual-axis chart": two sets of gridlines across one plot measure different
   * things and say neither.
   */
  private fun faceOff(components: List<Pair<String, Guides.AxisComponent>>) {
    for (channel in Channels.POSITION_CHANNELS) {
      val onChannel = components.filter { it.first == channel }.map { it.second }
      if (onChannel.size < 2) continue
      val counts = mutableMapOf<String, Int>()
      for (axis in onChannel) {
        val orient = (axis.properties["orient"] as? VegaValue.Str)?.value ?: continue
        val opposite = OPPOSITE_ORIENT[orient] ?: continue
        if ((counts[orient] ?: 0) > 0 && !axis.explicitOrient) {
          if ((counts[orient] ?: 0) > (counts[opposite] ?: 0))
            axis.override("orient", str(opposite))
        }
        counts[orient] = (counts[orient] ?: 0) + 1
      }
      for ((index, axis) in onChannel.withIndex()) {
        if (index > 0 && (axis.properties["grid"] as? VegaValue.Bool)?.value == true) {
          axis.override("grid", bool(false))
        }
      }
    }
  }

  private fun assembleLegends(
    views: List<UnitView>,
    /**
     * Which scale each assembled legend came from, filled in as they are grouped.
     *
     * The legends are grouped by *field* and a concatenation places them by *scale*, so the two
     * keys are not the same and the second has to be recorded on the way past.
     */
    scaleOf: MutableMap<String, String> = mutableMapOf(),
  ): LinkedHashMap<String, VegaValue> {
    val legends = LinkedHashMap<String, LinkedHashMap<String, VegaValue>>()
    val explicitlyTitled = mutableSetOf<String>()
    for (view in views) {
      for (channel in Channels.LEGEND_CHANNELS) {
        // The same definition the *scale* was built from, which for a channel written entirely as
        // a condition is the condition's own: a colour that only exists once a row is picked still
        // needs a key saying what its colours mean.
        val def = view.spec.encoding[channel]?.let { view.scaledDef(it) } ?: continue
        val component = view.scaleComponents[channel] ?: continue
        val built = Guides.legend(view, channel, def, component.type) as? VegaValue.Obj ?: continue
        // Keyed by the **field**, not by the scale — `assembleLegends` groups by
        // `field:<name>`. One field encoded twice, as a colour *and* as a size, is one key to the
        // reader and one legend whose swatches carry both; keying by the scale gave it two, side by
        // side, saying the same thing. The scale's own prefix stays in the key so that a
        // composition resolving its legends independently still gets one per plot, and the
        // discreteness with it, since a ramp and a set of swatches cannot be the same legend.
        val prefix = component.name().removeSuffix(channel)
        val discrete = if (Scales.hasDiscreteDomain(component.type)) "d" else "c"
        val key = "$prefix|${def.field ?: channel}|$discrete"
        // `mergeValuesWithExplicit` settles a property before any tie-breaker runs: a value the
        // specification stated beats one this compiler derived. A field encoded as both a colour
        // and a size, with a title written on only one of them, is titled by the one that was
        // written — not by the two joined with a comma.
        val titled = def.legend?.fields?.containsKey("title") == true || def.explicitTitle != null
        val existing = legends[key]
        if (existing == null) {
          legends[key] = LinkedHashMap(built.fields)
          scaleOf[key] = component.name()
          if (titled) explicitlyTitled += key
        } else {
          merge(
            existing,
            built,
            titleWins =
              when {
                titled && key !in explicitlyTitled -> true
                !titled && key in explicitlyTitled -> false
                else -> null
              },
          )
          if (titled) explicitlyTitled += key
        }
      }
    }
    val out = LinkedHashMap<String, VegaValue>()
    legends.forEach { (name, fields) ->
      settle(fields)
      out[name] = obj { fields.forEach { (key, value) -> put(key, value) } }
    }
    return out
  }

  /**
   * "Remove properties that the legend is encoding" — `assembleLegend` in `legend/assemble.ts`.
   *
   * A swatch painted by a scale must not also be painted by a value: a point's legend carries
   * `fill: transparent` because a hollow point is hollow, but the moment a bar merges into the same
   * legend and brings a scaled `fill` with it, that transparent fill would blank every swatch. This
   * runs after the merge, because it is the *merged* legend's own channels that decide it.
   */
  private fun settle(fields: LinkedHashMap<String, VegaValue>) {
    // "title schema doesn't include null" — `assembleLegend` drops the property rather than
    // writing an empty one, which is how `"legend": {"title": null}` takes a key's caption off.
    if (fields["title"].let { it == null || it == VegaValue.Null }) fields.remove("title")
    val symbols = fields["encode"]?.get("symbols")?.get("update") as? VegaValue.Obj ?: return
    val remaining =
      symbols.fields.filterKeys { it !in Channels.LEGEND_SCALE_CHANNELS || !fields.containsKey(it) }
    if (remaining.size == symbols.fields.size) return
    fields["encode"] = obj {
      put(
        "symbols",
        obj { put("update", obj { remaining.forEach { (key, value) -> put(key, value) } }) },
      )
    }
  }

  /**
   * One legend where several views encode the same channel — `mergeLegendComponent`.
   *
   * A bar and a point coloured by the same column get **one** key between them, and it has to say
   * both things: the bar fills its swatch and the point strokes one, so the merged legend carries
   * `fill` and `stroke` alike. Where the two disagree the first view's answer stands, with two
   * exceptions upstream names — a circle wins over any other glyph, being the plainer symbol, and
   * two different titles are joined rather than one being dropped.
   */
  private fun merge(
    into: LinkedHashMap<String, VegaValue>,
    from: VegaValue.Obj,
    /**
     * Which of the two titles the specification stated, or null where neither or both did.
     *
     * True takes the incoming one, false keeps the standing one, and null joins them.
     */
    titleWins: Boolean? = null,
  ) {
    for ((key, value) in from.fields) {
      val existing = into[key]
      if (existing == null) {
        into[key] = value
        continue
      }
      if (existing == value) continue
      when (key) {
        "symbolType" -> if ((value as? VegaValue.Str)?.value == "circle") into[key] = value
        "title" ->
          when (titleWins) {
            true -> into[key] = value
            false -> Unit
            null -> {
              val titles = (existing.strings() + value.strings()).distinct()
              into[key] = if (titles.size == 1) existing else str(titles.joinToString(", "))
            }
          }
      }
    }
  }

  private fun VegaValue.strings(): List<String> =
    when (this) {
      is VegaValue.Str -> listOf(value)
      is VegaValue.Arr -> values.mapNotNull { (it as? VegaValue.Str)?.value }
      else -> emptyList()
    }
}
