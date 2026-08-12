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

  /** Which of a composition's scales and guides its children share, and which they do not. */
  private val resolve = Resolve(spec.obj("resolve"))
  private val parser = Parse(config, diagnostics)

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
    // A concatenation scales each of its plots separately along the axes and shares everything
    // else — `defaultScaleResolve` — so the position scales are merged within a plot and the rest
    // across the whole chart. That is why two plots side by side have their own `y` but one colour
    // legend between them.
    // `resolve` governs the *outermost* composition and nothing below it, as it does upstream where
    // every model carries its own: a top-level resolve settles a concatenation's plots against each
    // other, or, with no concatenation, a layer's views against each other.
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

    // The sizes are named before anything reads them, because what a concatenation calls them
    // depends on whether its plots agree: a row of equally wide plots shares one `childWidth`,
    // and a row of unequal ones keeps `concat_0_width` beside `concat_1_width`.
    nameSizes(plots)

    val data = assembleData(views).toMutableList()
    fillScaleDomains(views)
    for (plot in plots) {
      plot.axes = assembleAxes(plot)
      plot.size =
        LayoutSize(plot.views, plot.byChannel(), config, plot.spec, plot.sizeNames, plot.prefix)
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
    val sizeSignals =
      sizeSignalsFor(plotTree).distinctBy { it.string("name") } + Params.signals(spec, diagnostics)
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
          views.first().mainData,
          vertical = (main - horizontal.toSet()).isNotEmpty(),
          horizontal = horizontal.isNotEmpty(),
        )
    }

    val vega = obj {
      put("\$schema", "https://vega.github.io/schema/vega/v6.json")
      put("description", spec.string("description"))
      put("background", config.background)
      put("padding", config.padding)
      autosize()?.let { put("autosize", it) }
      put("width", mergedSize("width") ?: if (concat == null) root.width else null)
      put("height", mergedSize("height") ?: if (concat == null) root.height else null)
      // `cell` is the bordered plotting area; a chart with no Cartesian position — a pie — has no
      // plotting area to border, and upstream styles it as a plain `view` instead. A faceted chart
      // has no plotting area of its own at all: each of its cells carries the style, and neither
      // does a concatenation, whose plots are each their own cell.
      if (facet == null && concat == null) put("style", style(views))
      title()?.let { put("title", it) }
      put("data", arr(data))
      if (sizeSignals.isNotEmpty()) put("signals", arr(sizeSignals))
      facet?.let { put("layout", it.layout(FACET_SPACING, HEADER_OFFSET, config)) }
      concat?.let { put("layout", it.layout()) }
      put("marks", arr(if (concat == null) marks(views, plots.single().axes) else groups(plotTree)))
      // Shared scales first, then each plot's own, which is the order upstream's assembly walks the
      // model tree in: the composition's own components before it recurses into its children.
      val scales =
        allScales.values.filter { owner[it.name()] == null } +
          plots.flatMap { plot -> allScales.values.filter { owner[it.name()] === plot } }
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

    return VegaLiteCompilation(vega, diagnostics.diagnostics)
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
    val independent =
      resolve.scaleIsIndependent(
        channel,
        defaultIndependent = concat != null && channel in Channels.POSITION_SCALE_CHANNELS,
      )
    if (!independent) return channel
    val owner = if (concat != null) plotOf(view) else view.childName
    return if (owner.isEmpty()) channel else "${owner}_$channel"
  }

  private fun plotOf(view: UnitView): String = plotNames[view] ?: ""

  private val plotNames = mutableMapOf<UnitView, String>()

  private fun failed() = VegaLiteCompilation(null, diagnostics.diagnostics)

  private fun style(views: List<UnitView>): String =
    if (views.any { it.spec.encoding["x"] != null || it.spec.encoding["y"] != null }) "cell"
    else "view"

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
      val nested = Concat.of(child, diagnostics)
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

    plotTree = build("", spec) ?: return null
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
      is Node.Nest -> nestSizes[node]?.get(channel)
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
            },
          )
        },
      )
      val local = localSizeSignals(plot)
      if (local.isNotEmpty()) put("signals", arr(local))
      put("marks", arr(plot.views.flatMap { Marks.marks(it) }))
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
    for (channel in Channels.POSITION_CHANNELS) {
      val other = if (channel == "x") "y" else "x"
      val byChannel = plot.byChannel()
      if (byChannel[channel] == null || byChannel[other] != null) continue
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
    put("transform", spec.fields["transform"])
    putAll(child)
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
      if (wrapped != null) FacetWrap(wrapped, spec.number("columns")?.toInt())
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

    return current.groups(vertical, horizontal, HEADER_OFFSET, config) +
      current.cellGroup(
        views.first().mainData,
        childMarks,
        gridAxes,
        "child_width",
        "child_height",
        HEADER_OFFSET,
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

  private fun autosize(): VegaValue? {
    val declared = spec.fields["autosize"] ?: return null
    val type = (declared as? VegaValue.Str)?.value ?: declared.string("type")
    // `pad` is Vega's own default, so upstream writes nothing for it.
    if (declared is VegaValue.Str) return if (type == "pad") null else declared
    return declared
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
      DataPipeline(view, diagnostics, register).build(roots.getOrPut(data) { SourceNode(data) })
    }
    // Every view built its own chain onto its source, so a shared tree forks there; the shared
    // parse is hoisted above the fork before the tree is named and flattened.
    for (root in roots.values) {
      root.moveParseUp()
      root.mergeParse()
      root.mergeIdentical()
      root.mergeAggregates()
      root.mergeOutputs()
    }
    val datasets = DataAssembler().assemble(order.map { roots[it] ?: it })
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
        val type = Scales.scaleType(channel, def, view.spec.mark)
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
        val domains = Scales.domain(view, channel, def, component.type, view.mainData)
        for (domain in domains) if (domain !in component.domains) component.domains += domain
        if (component.properties.isEmpty()) {
          Scales.range(view, channel, def, component.type)?.let { component.set("range", it) }
          Scales.properties(view, channel, def, component.type, component)
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
        val parsed = Guides.parseAxis(view, channel, def, component.type, hasOther) ?: continue
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
        defaultIndependent = concat != null && channel in Channels.POSITION_SCALE_CHANNELS,
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
        val def = view.spec.encoding[channel] ?: continue
        if (!def.isFieldDef && def.datum == null) continue
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
