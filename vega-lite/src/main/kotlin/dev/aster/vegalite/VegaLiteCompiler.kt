package dev.aster.vegalite

import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.model.VegaDiagnostic
import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.locale.VegaLocale
import dev.aster.vega.model.spec.mergeConfig
import kotlinx.datetime.TimeZone

/** A compiled Vega-Lite specification: the Vega it became, and everything it could not honour. */
public data class VegaLiteCompilation(
  /**
   * The Vega specification, ready to hand to the runtime. Null only if nothing could be built.
   *
   * **An ERROR does not imply null**, and the audit was right that this needed saying: a document
   * can compile to a usable chart and still report that one construct in it was not honoured — an
   * encoding channel dropped, a transform not implemented, a layer member that would not parse.
   * That is the whole shape of this engine's diagnostic model, and refusing to draw the other
   * ninety per cent would serve nobody.
   *
   * Null means the compiler produced no specification at all: the input was not an object, it
   * exceeded a limit in `Limits`, or a defect was caught by the guard. A host following the
   * README's stop-on-null pattern is therefore checking the right thing, and should *also* read
   * [diagnostics] and show what they say — a chart that drew is not a chart that drew everything.
   */
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
public class VegaLiteCompiler(
  /**
   * A `config` block the **host** supplies, which the specification's own beats key by key.
   *
   * This is how an app themes a chart it did not write. A specification arriving from a server
   * carries the colours that server chose — a `tableau10` scheme picked for a white page, a white
   * point overlay — and an app drawing it on a dark surface has to be able to say otherwise without
   * rewriting the payload. `Config` is internal and built from the specification alone, so before
   * this there was no way in at all.
   *
   * Merged by [mergeConfig], which is `vega-util`'s own `mergeConfig`: a block is merged property
   * by property, an object inside a block overwrites, and `legend.layout` and each `style` entry
   * recurse one level further. The specification is the **later** source, so it wins wherever both
   * name the same property — a theme is a default and a stated value is an override of it.
   *
   * Two things it cannot do, and both are properties of Vega-Lite rather than of this seam. A
   * mark's own encoded property beats every configuration block, so a specification writing
   * `mark.point.fill` keeps its white point whatever a host says; and `Normalize.pointOverlay` uses
   * that `point` object verbatim as the overlay mark's definition. A host that has to change one of
   * those is rewriting the specification, and can inject its `config` in the same pass.
   */
  private val hostConfig: VegaValue? = null,
  /**
   * What **local** time means, or null for the device's own zone.
   *
   * Vega-Lite settles almost nothing about time itself — a `timeUnit` becomes a `timeunit`
   * transform and a temporal channel becomes a `time` scale, both of which the runtime resolves
   * with the zone it was given, so the seam that matters is `SpecCompiler.timeZone` and this one
   * only has to agree with it. The exception is a **selection store**: an `init` written as
   * `{"year": …}` is turned into a millisecond here, at compile time, and a store on a different
   * clock from the axis is a brush that starts in the wrong place.
   */
  private val timeZone: TimeZone? = null,
  /**
   * The host's language, which decides one thing this compiler **emits**.
   *
   * Almost nothing about a locale belongs here: a month name is resolved by the runtime from the
   * pattern written into the specification, so `%b` has always been enough and a Dutch axis has
   * always said `mei`. The exception is the *pattern itself* — `Fields.timeUnitSpecifier` writes an
   * override table into a bucketed axis's format, `%b %d, %Y`, and the order of those fields and
   * what separates them are properties of a language rather than of a chart. Before this, a host
   * that supplied a locale to get the day before the month had no lever for it anywhere.
   *
   * d3's `en-US` by default, which is what upstream produces, so the emitted specification is
   * byte-for-byte what it was. It should be the **same** locale the runtime is given: a chart whose
   * axis pattern comes from one language and whose month names come from another is worse than
   * either.
   */
  private val locale: VegaLocale = VegaLocale.EnglishUS,
) {

  public fun compileJson(json: String): VegaLiteCompilation = guarded {
    val diagnostics = DiagnosticCollector()
    val parsed =
      VegaJson.parseOrNull(json, diagnostics)
        ?: return@guarded VegaLiteCompilation(null, diagnostics.diagnostics)
    compileUnguarded(parsed)
  }

  public fun compile(spec: VegaValue): VegaLiteCompilation = guarded { compileUnguarded(spec) }

  private fun compileUnguarded(spec: VegaValue): VegaLiteCompilation {
    val diagnostics = DiagnosticCollector()
    if (spec !is VegaValue.Obj) {
      diagnostics.fatal(
        VegaLiteDiagnostics.NOT_VEGA_LITE,
        "A Vega-Lite specification must be a JSON object.",
      )
      return VegaLiteCompilation(null, diagnostics.diagnostics)
    }
    // Before anything walks it: a document too deep or too long to recurse over is refused with a
    // diagnostic, rather than proving the point with a `StackOverflowError`. See `Limits`.
    if (!Limits.check(spec, diagnostics)) {
      return VegaLiteCompilation(null, diagnostics.diagnostics)
    }
    return Compilation(withDefaultData(withHostConfig(spec)), diagnostics, timeZone, locale).run()
  }

  /**
   * A specification that names no `data` reads an **empty named table**, which is what upstream
   * gives it: `"data": [{"name": "source"}]` and marks drawn from `source`.
   *
   * This used to be an ERROR *and* a non-null result whose marks read a dataset called `""` — so a
   * host following the README's stop-on-null pattern passed a broken specification straight to the
   * runtime. It is not an error at all: a chart with no data is how a host supplies rows itself,
   * which is `VegaChartController.hostData` on this side and `view.data(name, rows)` on upstream's,
   * and the name it is supplied under is the one written here.
   *
   * Injected at the root because that is where upstream creates it — a child with no `data` of its
   * own inherits the parent's, and one that has its own goes on using it, so a specification whose
   * every view brings its own table is unchanged: the injected root is unread and never emitted.
   */
  private fun withDefaultData(spec: VegaValue.Obj): VegaValue.Obj {
    if (spec.fields.containsKey("data")) return spec
    return VegaValue.Obj(
      LinkedHashMap(spec.fields).apply {
        put("data", VegaValue.Obj(linkedMapOf("name" to VegaValue.Str(DEFAULT_DATA_NAME))))
      }
    )
  }

  /**
   * The catch-all behind both entry points.
   *
   * This module takes **pasted text** and had no `try` in it anywhere, so a defect reached on a
   * hostile or merely unusual document was a crash in the host rather than a diagnostic. `Limits`
   * closes the two ways a document could reach one deliberately; this is what the *next* one looks
   * like. Cancellation is rethrown, because swallowing it would leave a cancelled coroutine
   * running, and so is `OutOfMemoryError`, after which nothing this process does is trustworthy.
   *
   * **`Exception`, not `Throwable`**, which is both the portable spelling and the right rule. An
   * `Error` is not a failed compile: `OutOfMemoryError` leaves nothing this process does
   * trustworthy, and `StackOverflowError` is not catchable at all on Kotlin/Native, so catching
   * either would turn "the host dies" into "the host dies later, holding a diagnostic". That is
   * exactly why `Limits` refuses an over-deep document *before* walking it rather than relying on a
   * catch. (`OutOfMemoryError` is also not in the common standard library, so a `Throwable` catch
   * with an explicit rethrow of it does not compile for the native targets at all — which the
   * gradle gate did not notice, because it does not compile the common metadata.)
   */
  private inline fun guarded(compile: () -> VegaLiteCompilation): VegaLiteCompilation =
    try {
      compile()
    } catch (cancellation: kotlin.coroutines.cancellation.CancellationException) {
      throw cancellation
    } catch (failure: Exception) {
      VegaLiteCompilation(
        vega = null,
        diagnostics =
          listOf(
            VegaDiagnostic(
              severity = DiagnosticSeverity.FATAL,
              code = VegaLiteDiagnostics.COMPILE_FAILED,
              message =
                "The Vega-Lite compiler failed on this specification with " +
                  "${failure::class.simpleName}: ${failure.message}. That is a defect in this " +
                  "engine rather than in the document — please report it.",
              cause = failure,
            )
          ),
      )
    }

  /** The specification with the host's configuration merged **under** its own. */
  private fun withHostConfig(spec: VegaValue.Obj): VegaValue.Obj {
    val host = hostConfig ?: return spec
    val merged = mergeConfig(host, spec.fields["config"]) ?: return spec
    return VegaValue.Obj(LinkedHashMap(spec.fields).apply { put("config", merged) })
  }
}

/**
 * What an unnamed, un-valued table is called: upstream's `DataSourceType.Raw` with no counter.
 *
 * A host supplying rows for a Vega-Lite chart with no `data` writes them under this name.
 */
internal const val DEFAULT_DATA_NAME: String = "source"

/**
 * Keys whose contents are the **user's data** rather than anything this compiler names.
 *
 * A dataset's inline rows, and a `datum` written into an encoding. See `renamedValue`.
 */
private val LITERAL_DATA_KEYS = setOf("values", "datum")

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
  /** What a selection store's written date is read in; null is the device's own zone. */
  private val timeZone: TimeZone? = null,
  /** The host's language; see `VegaLiteCompiler.locale`. Reaches the guides through [config]. */
  private val locale: VegaLocale = VegaLocale.EnglishUS,
) {

  private val config = Config(spec.obj("config") ?: VegaValue.EmptyObject, locale)

  /**
   * The selections this chart declares, which the data, the signals and the marks all read.
   *
   * Read **after** a repetition has been rewritten, because a repetition's copies each declare the
   * selection its template declared: a scatter-plot matrix with one brush in the template has a
   * brush in every cell, each with its own drag signals and its own rectangle, all writing into one
   * store. Read from the template instead, the chart had a single brush that only the first cell
   * could be dragged in.
   */
  private var selections: List<Selection> = emptyList()

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
      //
      // Every interval selection on the same view, whatever it is bound to. Upstream asks only its
      // type:
      //
      //     vals(model.component.selection ?? {})
      //       .reduce((acc, cmpt) => cmpt.type === 'interval' ? acc.concat(cmpt.name + BRUSH) :
      // acc, [])
      //
      // A **scale-bound** interval was excluded here on the reasoning that it draws no brush, so
      // there is no rectangle to click — but `indexof` on a name nothing carries is simply always
      // less than zero, and the guard costs nothing. Upstream writes it, so a chart that pans its
      // axes while picking points disagreed on the one signal that does the picking.
      val brushes =
        selections
          .filter { it.type == "interval" && it.owner === selection.owner }
          .map { "${it.name}_brush" }
      return selection.signals(
        unit = selection.unitName(views.firstOrNull().takeIf { facet != null }, facet),
        brushes = brushes,
        view = selection.owner ?: views.firstOrNull(),
      )
    }
    val view = selection.owner ?: views.firstOrNull() ?: return emptyList()
    val unit = selection.unitName(views.firstOrNull().takeIf { facet != null }, facet)
    return selection.intervalSignals(
      view,
      selection.initial,
      pushesOutward = pushesOutward(view),
    ) + selection.intervalTail(view, unit = unit, initial = selection.initial)
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
  // Built lazily, because the selections it resolves conditions against are only known once a
  // repetition has been rewritten into the concatenation its copies each declare one in.
  private val parser by lazy { Parse(config, diagnostics, selections) }

  /** `config.facet.spacing` and the gap a header title keeps from its cells. */
  private val FACET_SPACING = 20.0
  private val HEADER_OFFSET = 10.0

  /** The grid this chart's cells are laid out in, if it is faceted at all. */
  private var facet: FacetLayout? = null

  /**
   * The facet levels **inside** the outermost one, outermost first — a grid whose cells are grids.
   *
   * Empty for every ordinary chart, which is why the one-level path below is untouched: the levels
   * are lifted in turn, each one wrapping what the level under it produced.
   */
  private var nestedFacets: List<VegaValue.Obj> = emptyList()

  /**
   * The facet levels wrapping a **concatenation**, outermost first — a grid whose cell holds plots.
   *
   * Empty for every other chart, and the inverse of [facetLevels]: there a grid lives inside a plot
   * and its cell holds that plot's views, where here the grid is above the whole composition and
   * its cell holds the plots themselves. Upstream has a `ConcatModel` under the `FacetModel`; this
   * is that shape, reached by building the concatenation as the chart and wrapping it.
   */
  private var cellLevels: List<VegaValue.Obj> = emptyList()

  /**
   * Where the **cell's** own transforms begin in the specification's array.
   *
   * Everything before it was written by a grid and stands above the partition for good; everything
   * from it on is the cell's, which `moveFacetDown` walks the partition past — adding the facet's
   * own fields to every grouping it passes. See [FacetOperator.Peeled.gridTransforms].
   *
   * The default is **every** transform, because a facet written in the *encoding* keeps them:
   * ```ts
   * const {mark, width, projection, height, view, params, encoding: _, ...outerSpec} = spec;
   * return this.mapFacet({...outerSpec, ...layout, facet: facetMapping, spec: {…, mark, encoding}});
   * ```
   *
   * `mapFacetedUnit` moves the mark and the encoding into the cell and leaves everything else on
   * the grid — a `transform` among it. Only the operator form has a cell that wrote its own, and
   * the peel says how many of the array came from the grids above it.
   */
  private var gridTransforms: Int = Int.MAX_VALUE

  /** The grids [cellLevels] describes, made once the selections are known. */
  private var cellGrids: List<FacetLayout> = emptyList()

  /** Where the flow splits for a cell holding plots: one partition, shared by every plot in it. */
  private var cellSplit: FacetNode? = null

  /** The datasets that cell computes for itself, and the table its partition read. */
  private var cellGroupData: List<VegaValue> = emptyList()

  private var cellReads: String? = null

  private var cellDomainsAt: Int = -1

  /**
   * The chart's own facet levels, outermost first — [facet] is the innermost of them.
   *
   * The two are read in different places and mean different things: the **outermost** level
   * arranges the chart, so the top-level `layout` is its; the **innermost** owns a cell, so
   * everything about a plotting area is [facet]'s.
   */
  private var facetLevels: List<FacetLayout> = emptyList()

  /** The concatenation this chart is, if it is one. */
  private var concat: Concat? = null

  fun run(): VegaLiteCompilation {
    reportSchemaVersion()
    reportUnsupportedTopLevel()

    // **Vega-Lite 4's `selection` first**, because upstream runs its compatibility normalizer
    // before
    // the core one — `coreNormalizer.map(selectionCompatNormalizer.map(spec))` — and everything
    // below here is written against `params`. See `SelectionCompat`.
    if (SelectionCompat.applies(spec)) spec = SelectionCompat.normalize(spec)
    // A repetition is rewritten into a concatenation before anything is compiled, exactly as
    // upstream normalizes it, so there is nothing further down that knows what `repeat` is.
    if (spec.has("repeat")) spec = Repeat.normalize(spec, diagnostics) ?: return failed()
    // A `row`/`column` facet operator is the same chart as the same two channels written in the
    // encoding, so it becomes one before anything else looks at it.
    if (spec.has("facet")) {
      // Only the outermost level lands in the encoding; a grid whose cells are grids hands the rest
      // back, and each is lifted in turn below.
      val peeled = FacetOperator.normalize(spec, diagnostics) ?: return failed()
      spec = peeled.spec
      nestedFacets = peeled.inner
      gridTransforms = peeled.gridTransforms
      // A cell that is a **concatenation** is compiled the other way about. There is no encoding to
      // lift a facet channel out of, so the concatenation is built as the chart — under the name
      // the
      // grid gives its cell, which is what makes its plots `child_concat_0` and `child_concat_1` —
      // and the grids are made straight from the levels and wrap the whole of it.
      if (peeled.cellIsComposition) cellLevels = peeled.levels
    }
    // A **cell that is a repetition** is a concatenation of its copies, exactly as one at the top
    // of
    // a chart is — and it has to be normalised *after* the grids are peeled off, because until then
    // it is not the thing being compiled.
    if (spec.has("repeat")) spec = Repeat.normalize(spec, diagnostics) ?: return failed()
    selections = Selection.of(spec, diagnostics)
    // The grids are built straight from the levels rather than lifted out of an encoding: there is
    // no encoding here to lift them from. Everything they publish runs through the name the level
    // above gave its cell — `child`, then `child_child` — exactly as a lifted one does.
    cellGrids = cellLevels.mapIndexedNotNull { depth, level ->
      gridFor(
        level,
        Fields.varName("child_".repeat(depth).removeSuffix("_")).takeIf { depth > 0 } ?: "",
      )
    }
    if (cellLevels.isNotEmpty() && cellGrids.size != cellLevels.size) return failed()
    val plots = plots() ?: return failed()
    allPlots = plots
    concat = (plotTree as? Node.Nest)?.concat
    if (plots.any { it.views.isEmpty() }) return failed()

    // A facet channel does not encode anything *within* a cell, so it is lifted out of the encoding
    // before the scales are built — and everything inside then measures a cell rather than the
    // surface.
    // Each plot's own grid, where it has one: a concatenation may hold a faceted plot beside a
    // plain one, and the cells then belong inside that plot's group rather than to the chart.
    for (plot in plots) {
      var lifted = liftFacet(plot.views, plot.name)
      plot.views = lifted.first
      lifted.second?.let { plot.facets += it }
      // A grid whose cells are grids: each further level is lifted in turn, and each lift is the
      // same operation one name deeper. `liftFacet` renames its views `<owner>_child`, so the owner
      // of the next lift is the name the last one gave them — which is how the unit ends up
      // `child_child` and its size `child_child_width`, as upstream names them.
      for ((depth, level) in plot.nestedFacets.withIndex()) {
        if (lifted.second == null) break
        val owner =
          Fields.varName(
            listOf(plot.name, "child").filter { it.isNotEmpty() }.joinToString("_") +
              "_child".repeat(depth)
          )
        // The level's channels never reached any encoding, so they are put back into one for the
        // lift to read and take out again — the same path the outermost level took.
        val channels = Parse(config, diagnostics, selections).facetChannels(level, "$.facet")
        lifted = liftFacet(plot.views.map { it.withEncoding(it.spec.encoding + channels) }, owner)
        plot.views = lifted.first
        lifted.second?.let { plot.facets += it }
      }
      // Lifting a facet builds the cell's views anew, so the plot each belongs to has to be
      // recorded again: a scale resolved per plot is named for the plot that owns it, and a view
      // nothing knows the plot of is named as though it stood alone.
      plot.views.forEach { plotNames[it] = plot.name }
      // The **chart's** grid is the one it lays out itself. A faceted plot inside a concatenation
      // lays out its own cells within its group, and everything the chart does about a facet —
      // the split in the data flow, the cell's scales, the machinery in its signals — belongs to
      // that plot rather than to the chart.
      if (concat == null) {
        facetLevels = plot.facets
        facet = plot.facets.lastOrNull()
      }
    }
    // What a grid's cell can hold is a **flat** concatenation of plain plots. A plot in it that is
    // itself a grid, or a concatenation inside the concatenation, is a level this does not build:
    // the cell holds one composition and arranges what that composition drew, and a third level
    // would need its own layout inside a cell that has none. Reported rather than approximated —
    // both came out as plausible charts measuring the wrong rows before this check.
    if (cellGrids.isNotEmpty()) {
      val deeper =
        when {
          plots.any { it.facets.isNotEmpty() } -> "facet"
          nests().size > 1 -> "concatenation"
          else -> null
        }
      if (deeper != null) {
        diagnostics.fatal(
          VegaLiteDiagnostics.UNSUPPORTED_COMPOSITION,
          "A `$deeper` inside the concatenation in a `facet` is not implemented; a grid's cell " +
            "holds a concatenation of single views or layers of them.",
          jsonPath = "$.spec",
        )
        return failed()
      }
    }
    // A **projection** belongs to the unit whose places it puts on the page, and it is the
    // *encoding* that says so:
    //
    //     function parseUnitProjection(model: UnitModel): ProjectionComponent {
    //       if (model.hasProjection) { … }
    //       return undefined;
    //     }
    //
    // `hasProjection` is a `geoshape` mark or a geographic position channel — nothing else. A
    // projection stated at the top of a chart does not make a plot that draws in `x` and `y`
    // projected, and treating it as if it did put that plot's table into the `fit`: a map layered
    // under a scatter of ordinary positions was scaled to cover both, so the map came out the
    // wrong size.
    for (plot in plots) {
      for (view in plot.views) {
        val stated = view.spec.projection ?: plot.spec.obj("projection") ?: spec.obj("projection")
        val geographic =
          view.spec.encoding.keys.any { it in Channels.GEO_POSITION_CHANNELS } ||
            view.spec.mark == "geoshape"
        if (!geographic) continue
        view.projection = obj {
          config.raw.obj("projection")?.fields?.forEach { (key, value) -> put(key, value) }
          stated?.fields?.forEach { (key, value) -> put(key, value) }
        }
        // The order `gatherFitData` walks the pairs in, which is the order the signals were named.
        view.geoJsonSignals =
          listOf(listOf("longitude", "latitude"), listOf("longitude2", "latitude2"))
            .mapIndexedNotNull { index, pair ->
              if (pair.none { view.spec.encoding[it] != null }) null
              else view.prefixed("geojson_$index")
            }
        // A **shape** column of outlines is gathered too, after the coordinate pairs.
        if (
          view.spec.encoding["shape"]?.let { it.isFieldDef && it.type == MeasureType.GEOJSON } ==
            true
        ) {
          view.geoJsonSignals =
            view.geoJsonSignals + view.prefixed("geojson_${view.geoJsonSignals.size}")
        }
        // With nothing to fit to, the projection measures itself against the view's own table:
        // "main source is geojson, so we can just use that".
        if (view.geoJsonSignals.isEmpty() && view.projectionFits) view.fitsTable = true
      }
      // `parseNonUnitProjections`: a layer whose members agree about the projection has one, named
      // for the layer. It is fitted to everything they all draw — a map of states under a map of
      // routes is one map, and fitting each layer on its own would draw two of different sizes.
      val geographic = plot.views.filter { it.hasProjection }
      // `parseNonUnitProjections` runs for any composition, a **facet** as much as a layer: a grid
      // whose cells are maps has one projection, named for the grid and not for the cell, so every
      // cell is drawn at the same scale.
      // A member with **no** projection does not stop the merge — `every` returns true for it:
      //
      //     const mergable = every(model.children, (child) => {
      //       const projection = child.component.projection;
      //       if (!projection) return true;          // child layer does not use a projection
      //       …
      //     });
      //
      // so one map under a scatter of ordinary positions is still a *layer's* projection, named for
      // the layer. Requiring two geographic members left it named for the member, and the mark that
      // reads it named the member's too.
      val agreed = if (geographic.isEmpty()) null else agreedProjection(geographic)
      if ((plot.views.size > 1 || plot.facet != null) && agreed != null) {
        val (spoke, merged) = agreed
        val name =
          Fields.varName(
            listOf(plot.name, "projection").filter { it.isNotEmpty() }.joinToString("_")
          )
        geographic.forEachIndexed { index, view ->
          view.projectionName = name
          if (index == 0) {
            // The merged component is built from what the children **agreed**, which may be what
            // one of them said and the others left alone: `new ProjectionComponent(name,
            // nonUnitProjection.specifiedProjection, …, duplicate(nonUnitProjection.data))`.
            //
            // That copy is why the **fold's** own outlines come first in the fit and the children's
            // after it: the merged component starts with the data of whichever child the fold ended
            // on, and then every fitted child is appended in order.
            view.projection = merged
            view.projectionFitViews = listOf(spoke) + geographic
          } else {
            view.projectionMerged = true
          }
        }
      }
    }
    // `parseProjection` **recurses**: every level that is not a unit merges what the level below it
    // agreed on, so a concatenation merges its plots the same way a layer merges its members. Three
    // copies of one map are three plots, and merged they are one projection named for the chart —
    // fitted to everything all three draw, so the three are drawn at a single scale. Left per plot,
    // each copy fitted itself to its own outlines and the row came out three maps of three sizes.
    elevateProjection(plotTree)

    val views = plots.flatMap { it.views }
    views.forEach { it.selections = selections }
    // A plot inside a **cell** carries no facet channel of its own — the grid was never lifted out
    // of its encoding — but the copy of its chain that stands beside the grid for the scales still
    // has to group by the facet's columns, as `cloneSubtree` adds them. So the fields are handed to
    // it directly.
    cellGrids.lastOrNull()?.let { grid -> views.forEach { it.facetFields = grid.fields } }
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
          // A scale still called by its own channel — under the chart's name, where the chart has
          // one — is a **shared** one; anything else is named for the child that owns it.
          if (scale.name() == prefixed(scale.channel)) null
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
        // `parseSelectionDomain` asks two places: the scale's own `domain`, and the **bin's**
        // extent. A bucketing whose width a brush sets is measuring the same thing the domain is,
        // so the scale follows the brush too — the second plot of a chart with an overview above it
        // shows what the brush has picked, cut into buckets as wide as the brush makes them.
        val stated =
          def.scale?.obj("domain")?.takeIf { it.has("param") }
            ?: (def.bin as? Binning.Bin)?.params?.obj("extent")?.takeIf { it.has("param") }
            ?: continue
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
      }
    }
    // ```js
    // export function scaleClip(model: UnitModel) {
    //   const xScale = model.getScaleComponent('x');
    //   const yScale = model.getScaleComponent('y');
    //   return xScale?.get('selectionExtent') || yScale?.get('selectionExtent') ? true : undefined;
    // }
    // ```
    //
    // A mark whose position scale is driven by a selection is **clipped**, or a pan that moves the
    // domain past the data draws the rows that fell outside the plot. The question is asked of the
    // *scale* — which is why two plots sharing the panned scale are both clipped, and what makes a
    // pair of linked plots move together without either spilling over its neighbour — and it is
    // asked of the scale that was actually driven. Both loops above have already answered it: a
    // `domainRaw` is what a driven scale carries, and it is written only where a selection can
    // drive one. Asking the selection instead clipped a chart whose brush was **refused**: a
    // categorical position has no halfway between two of its values, so binding one to the scales
    // moves nothing, and upstream warns and passes over the channel.
    // `getScaleComponent` walks **up** the model tree — a member that encodes no `y` of its own is
    // still measured by its layer's — so the question is asked of the plot's position scales rather
    // than of the view's own: a text label beside a panned scatter is clipped along with it.
    for (plot in plots) {
      val driven =
        plot.scales.values.any {
          // `x` and `y` alone, which is what `scaleClip` asks for: a polar position is not panned.
          it.channel in setOf("x", "y") && it.properties.containsKey("domainRaw")
        }
      if (driven) plot.views.forEach { it.clippedByScale = true }
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
    val legendPlot = mutableMapOf<String, String>()
    /** The channel each key explains, which is what its **resolution** is asked about. */
    val legendChannel = mutableMapOf<String, String>()
    // Every model in upstream's hierarchy carries its own `resolve`, and a legend is settled by the
    // composition it belongs to: a `resolve` written on one plot of a concatenation governs the
    // layers inside *that* plot and nothing else.
    val resolveOf =
      plots.flatMap { plot -> plot.views.map { it to Resolve(plot.spec.obj("resolve")) } }.toMap()
    val allLegends = assembleLegends(views, legendScale, legendPlot, legendChannel, resolveOf)
    // A legend the specification resolves **independently** belongs to the plot that raised it even
    // where the scale is shared: `resolve: {"legend": {"color": "independent"}}` is how a
    // concatenation puts a key inside the plot it explains rather than beside the whole chart.
    fun ownedBy(key: String, plot: Plot): Boolean =
      legendPlot[key]?.let { it == plot.name } ?: (owner[legendScale[key]] === plot)
    for (plot in plots) {
      plot.legends =
        if (concat == null) emptyList()
        else allLegends.filterKeys { ownedBy(it, plot) }.values.toList()
    }
    // A key explaining a scale the **cell** owns stands in the cell, as the scale does — see
    // [cellOwnsLegend].
    cellLegends = allLegends.filterKeys { cellOwnsLegend(legendChannel[it]) }.values.toList()
    val legends =
      allLegends
        .filterKeys { key ->
          !cellOwnsLegend(legendChannel[key]) &&
            (concat == null || (legendPlot[key] == null && owner[legendScale[key]] == null))
        }
        .values
        .toList()
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
        ) +
          // `interval.topLevelSignals`: one tick for the chart, however many brushes over maps open
          // already drawn. It is written only where one does — a brush the reader has yet to drag
          // has nothing to intersect with and nothing to wait for.
          if (
            selections.any {
              it.throughProjection() && it.initial != null
            }
          ) {
            listOf(Selection.geoInitTick())
          } else emptyList()
    // Upstream's order: the layout's own sizes, then `unit`, then what each selection *resolves*
    // to — because a variable parameter may read one — then the variables, then the machinery that
    // writes the stores.
    val sizeSignals =
      sizeSignalsFor(plotTree).distinctBy { it.string("name") } +
        selectionSignals +
        // A selection's **controls** are part of the page rather than of the drawing, so they sit
        // at
        // the top with `unit` and before the signal the tests read.
        // In **reverse** order of declaration, each control being *unshifted* onto the list as the
        // selections are walked: `signals.unshift({name: sgname, …})` in `inputBindings` and in
        // `bindLegend` alike. So a chart with a control per parameter writes the last one's first,
        // which is not a detail of the output — a reader looking down a column of drop-downs sees
        // them in the order Vega lists them.
        selections
          .distinctBy { it.name }
          .reversed()
          .flatMap {
            it.inputSignals(it.owner ?: views.firstOrNull()) +
              it.legendSignals(it.owner ?: views.firstOrNull())
          } +
        selections
          .distinctBy { it.name }
          .flatMap { selection ->
            // A selection **bound to the scales** in a chart of several views is resolved from the
            // signals its plot pushes outward rather than from its store: `vlSelectionResolve`
            // knows
            // nothing about bound scales, so upstream reassembles the state by hand from the
            // per-channel signals — and those are declared here, empty, for the plot to push into.
            // One selection at a time, its own signal and then what it pushes: upstream calls each
            // compiler's `topLevelSignals` while it is on that selection, so the two stay together.
            val bound = boundOutward(selection, views)
            val resolved =
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
            listOf(resolved) +
              bound.map { (_, signal) -> obj { put("name", signal) } } +
              // The clock a `timer` selection is advanced by, which ticks for the whole chart.
              selection.clockSignals()
          } +
        Params.signals(spec, diagnostics) +
        // A concatenation writes each selection's machinery inside the plot that declared it,
        // where the marks it reacts to are; everything else is one plot, so it stays here.
        // Inside a facet the machinery belongs to the **cell**: every signal in it reads the
        // pointer against one cell's scales, and there is one of each per cell rather than one for
        // the grid. What stays here is what a cell cannot own — the store, the signal the store
        // resolves into, and whatever a binding writes from outside the chart.
        selections
          .filter { facet == null && (concat == null || it.owner == null) }
          .flatMap { machinery(it, views) } +
        // A control's own signals stand at the top even where everything else about the selection
        // is written inside a cell: one widget for the chart, not one per cell.
        selections
          .distinctBy { it.name }
          .filter { facet != null }
          .flatMap { selection ->
            val outside = boundInward(selection, views).toSet()
            machinery(selection, views).filter {
              (it as? VegaValue.Obj)?.string("name") in outside
            }
          }
    val root = plots.first().size!!
    // The facets' own values, which the layout counts and the headers title themselves from — and,
    // for a wrapped facet, only along the directions a shared axis was actually drawn in.
    // A faceted plot inside a **concatenation** names its own values the same way; there is no
    // split below it and no cell scale to place them among, so they simply follow the tables the
    // chart derived.
    for (plot in plots.filter { it.facet != null && concat != null }) {
      // The **outermost** level's values stand beside the plot's table; a level inside one breaks
      // that level's partition down further, so its values are computed inside that level's cell.
      val current = plot.facets.first()
      val bands =
        plot.axes.filter { (it["grid"] as? VegaValue.Bool)?.value != true && !cellOwnsAxis(it) }
      val across = bands.filter { it.string("orient") == "bottom" || it.string("orient") == "top" }
      val reads = plot.reads ?: plot.views.first().mainData
      val domains =
        current.domainDatasets(
          counted = emptyMap(),
          source = reads,
          vertical = (bands - across.toSet()).isNotEmpty(),
          horizontal = across.isNotEmpty(),
        )
      // Beside the table it reads, not at the end of the chart's: a plot's own data is assembled
      // when that plot is, so a grid's values stand between its table and the next plot's.
      val at = data.indexOfFirst { it.string("name") == reads }
      if (at >= 0) data.addAll(at + 1, domains) else data += domains
    }
    // A grid **wrapping a concatenation** writes its own values beside the chart's table too: the
    // cells' values are the whole table's, whatever the cell turns out to hold.
    cellGrids.firstOrNull()?.let { current ->
      val domains =
        current.domainDatasets(
          counted = emptyMap(),
          source = cellReads ?: views.first().mainData,
          vertical = false,
          horizontal = false,
        )
      if (cellDomainsAt >= 0) data.addAll(cellDomainsAt, domains) else data += domains
    }
    // The **outermost** level's values stand beside the chart's table; a level inside one breaks
    // that level's partition down further, so its values are computed inside that level's cell and
    // are written there instead.
    facetLevels.firstOrNull()?.let { current ->
      val main =
        plots.single().axes.filter {
          (it["grid"] as? VegaValue.Bool)?.value != true && !cellOwnsAxis(it)
        }
      val horizontal = main.filter {
        it.string("orient") == "bottom" || it.string("orient") == "top"
      }
      val domains =
        current.domainDatasets(
          counted = cellCardinality,
          // The table the **facet** reads, which is the one above the split where there is one:
          // the cells' values are the whole table's values, not one cell's.
          source = plots.single().reads ?: views.first().mainData,
          vertical = (main - horizontal.toSet()).isNotEmpty(),
          horizontal = horizontal.isNotEmpty(),
        )
      // At the point the facet stands in the flow, where the flow splits there; at the end
      // otherwise, nothing else having been derived after it.
      val at = plots.single().domainsAt
      if (at >= 0) data.addAll(at, domains) else data += domains
    }

    val vega = obj {
      put("\$schema", "https://vega.github.io/schema/vega/v6.json")
      // An empty description is no description: `isEmpty` drops it rather than announcing a chart
      // whose spoken summary is the empty string.
      put("description", spec.string("description")?.takeIf { it.isNotEmpty() })
      // The chart's own `background` beats the configured one: `config.background` is a theme's
      // default and a specification that states one is overriding the theme, not being overridden
      // by it.
      // ```js
      // const outputConfig: Config<SignalRef> = omit(mergedConfig, configPropsWithExpr);
      //
      // for (const prop of ['background', 'lineBreak', 'padding'] as const) {
      //   if (mergedConfig[prop]) {
      //     (outputConfig as any)[prop] = signalRefOrValue(mergedConfig[prop]);
      //   }
      // }
      // ```
      //
      // The theme's is taken only where it is **truthy**, as its padding is: `initConfig` takes all
      // three of these off the configuration and puts back only the ones that are. A theme saying
      // `{"background": null}` — a document whose charts are drawn on whatever is behind them — is
      // a theme with no background at all, and this wrote the null out as the chart's own. A
      // background the **chart** states is written as it stands, null included: that one is not the
      // theme's to drop.
      put("background", spec.fields["background"] ?: config.background.takeIf { it.isTruthy() })
      // A chart's own padding beats the theme's, as its background does: a specification stating
      // one is overriding what the configuration settled, not the other way about.
      //
      // The theme's is taken only where it is **truthy**, which is the one place the two differ: a
      // `config: {"padding": 0}` reaches Vega as no padding at all, where a `"padding": 0` written
      // on the chart itself reaches it as a zero. Upstream drops the falsy one while merging the
      // configuration and takes the specification's as written, and this wrote a `padding: 0` that
      // upstream does not.
      put(
        "padding",
        spec.fields["padding"] ?: config.padding.takeIf { it.isTruthy() },
      )
      autosize(views, root)?.let { put("autosize", it) }
      // A merged size that is a **`"container"`** is a signal rather than a property: there is no
      // number to write, the page having to be measured first. `assembleTopLevelModel` moves only
      // the signals that carry a `value`.
      put("width", hoisted(mergedSize("width") ?: if (concat == null) root.width else null))
      put("height", hoisted(mergedSize("height") ?: if (concat == null) root.height else null))
      // `cell` is the bordered plotting area; a chart with no Cartesian position — a pie — has no
      // plotting area to border, and upstream styles it as a plain `view` instead. A faceted chart
      // has no plotting area of its own at all: each of its cells carries the style, and neither
      // does a concatenation, whose plots are each their own cell.
      if (facet == null && concat == null) put("style", style(views))
      title()?.let { put("title", it) }
      // A top-level `view` block paints the *plotting area* rather than the surface around it, so
      // it becomes an `encode` on the chart's own group — `background` is the surface, `view.fill`
      // is the paper the marks sit on, and the two are different colours in the same chart.
      // In a trellis the `view` block describes a **cell**, and the cell group carries it.
      if (facet == null) viewEncode()?.let { put("encode", it) }
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
            .map {
              it.storeData(it.owner ?: views.firstOrNull(), it.initial, timeZone, facet)
            } + data
        ),
      )
      if (sizeSignals.isNotEmpty()) put("signals", arr(sizeSignals))
      // A stated `spacing` is the gap between cells, and it beats the configured twenty.
      // The **outermost** level arranges the chart; a level inside one arranges its cells within
      // that level's cell group, and its `layout` is written there rather than here.
      facetLevels.firstOrNull()?.let {
        // `spacing` is a number or a `{row, column}` pair, and a pair states only the side it
        // means: a trellis of rows an inch apart still wants the configured gap between its
        // columns, so the side left out is filled in rather than dropped.
        val spacing = facetSpacing(spec)
        val independent =
          setOf("x", "y").filter { channel ->
            resolve.scaleIsIndependent(channel, defaultIndependent = false)
          }
        put(
          "layout",
          it.layout(
            spacing,
            HEADER_OFFSET,
            config,
            independent.toSet(),
            headings = if (facetLevels.size > 1) headingsPerLevel(facetLevels).first() else null,
            childHasSize = facetLevels.size == 1,
          ),
        )
      }
      // A concatenation **wrapped in grids** arranges its plots inside the innermost cell, so its
      // own layout is written there and what stands here is the outermost grid's.
      if (cellGrids.isNotEmpty()) {
        put(
          "layout",
          cellGrids
            .first()
            .layout(
              facetSpacing(spec),
              HEADER_OFFSET,
              config,
              emptySet(),
              headings = if (cellGrids.size > 1) headingsPerLevel(cellGrids).first() else null,
              // What this band names is a whole composition, which has no one cell to be as tall
              // as.
              childHasSize = false,
            ),
        )
      } else concat?.let { put("layout", it.layout()) }
      // The cells' own scales are assembled before the marks that read them, the cell group being
      // where they are written.
      val grid = facet
      if (grid != null && concat == null) {
        // `assembleAxisSignals` on the **cell**: an axis inside it that draws its grid across no
        // other scale falls back to `width` or `height` by name, and inside the cell those names
        // mean the whole chart until the cell aliases them to its own.
        val own = selections.distinctBy { it.name }
        cellSignals =
          // `assembleFacetSignals`: a cell whose child declares a selection carries the datum of
          // the cell the pointer is in, so that a pick made anywhere in the grid is attributed to
          // the right one. A grid nothing is selected in needs no such signal.
          (if (own.isEmpty()) emptyList()
          else
            listOf(
              obj {
                put("name", "facet")
                put("value", VegaValue.EmptyObject)
                put(
                  "on",
                  arr(
                    listOf(
                      obj {
                        put(
                          "events",
                          arr(
                            listOf(
                              obj {
                                put("source", "scope")
                                put("type", "pointermove")
                              }
                            )
                          ),
                        )
                        put(
                          "update",
                          "isTuple(facet) ? facet : group(${quoted(grid.named("cell"))}).datum",
                        )
                      }
                    )
                  ),
                )
              }
            )) +
            localSizeSignals(plots.single()) +
            own.flatMap { selection ->
              // A signal the top level declares is *written* here and read there — `push: "outer"`
              // is how Vega says which of the two directions this one goes — and one a **control**
              // writes is not written here at all: it belongs beside the widget, outside the grid.
              val pushed = boundOutward(selection, views).map { it.second }.toSet()
              val outside = boundInward(selection, views).toSet()
              machinery(selection, views).mapNotNull { signal ->
                val named = (signal as? VegaValue.Obj)?.string("name")
                when {
                  named in outside -> null
                  named !in pushed -> signal
                  else ->
                    obj {
                      (signal as VegaValue.Obj).fields.forEach { (key, value) -> put(key, value) }
                      put("push", "outer")
                    }
                }
              }
            }
        cellScales =
          allScales.values
            .filter { it.name() != prefixed(it.channel) }
            .map { withinCell(assembleScale(it)) }
      }
      // The projections a chart's places are put on the page by, which stand before the marks that
      // read them — `assembleProjections`, walking the model tree.
      projections(views).takeIf { it.isNotEmpty() }?.let { put("projections", arr(it)) }
      // Shared scales first, then each plot's own, which is the order upstream's assembly walks the
      // model tree in: the composition's own components before it recurses into its children.
      val scales =
        (allScales.values.filter { owner[it.name()] == null } +
            plots.flatMap { plot -> allScales.values.filter { owner[it.name()] === plot } })
          // A facet's independently resolved scales are built inside its cells, where the rows
          // they measure are, so they are not written beside the grid as well.
          .filterNot { facet != null && concat == null && it.name() != prefixed(it.channel) }
      // A cell holding **plots** keeps those plots' own scales: each is measured over the rows the
      // partition handed one cell, so it is built there and not once beside the grid. What a
      // composition *shares* — a colour key covering every plot — is still the chart's. Settled
      // before the marks because the cell is one of them and carries them.
      val ownedByPlot =
        if (cellGrids.isEmpty()) emptyList()
        else scales.filter { scale -> plots.any { owner[scale.name()] === it } }
      // Measured over the rows the partition handed **this** cell, so each domain is turned to the
      // counterpart computed inside it — the same rewrite a facet's own cell scales take. Left
      // pointing at the copy beside the grid, every cell would build the same scale from every row.
      cellOwnScales = ownedByPlot.map { withinCell(assembleScale(it)) }
      val outside = scales - ownedByPlot.toSet()
      // A brush is drawn in **two** parts around the marks: its background under them so the data
      // stays legible through it, and its outline over them so it can be grabbed.
      put(
        "marks",
        arr(
          if (cellGrids.isNotEmpty()) cellWrapped(groups(plotTree))
          else if (concat != null) groups(plotTree)
          // A facet's marks are its cell and its headers, and the brush is already inside the cell.
          else if (facet != null) marks(views, plots.single().axes, facet, facetLevels.dropLast(1))
          else brushed(views, marks(views, plots.single().axes))
        ),
      )
      if (outside.isNotEmpty()) put("scales", arr(outside.map { assembleScale(it) }))
      // A faceted chart has no axes of its own: the gridlines live in every cell and the labelled
      // axis in a header drawn once for the whole grid. A concatenation's axes live in its plots.
      if (facet == null && concat == null && plots.single().axes.isNotEmpty()) {
        put("axes", arr(plots.single().axes))
      }
      if (legends.isNotEmpty()) put("legends", arr(legends))
      // The theme, as Vega takes it. Without this a chart's guides are drawn in the engine's own
      // colours however carefully the specification restyled them.
      config.forVega()?.let { put("config", it) }
      // `usermeta` is carried through to the Vega specification, last, which is where upstream's
      // `assemble` puts it — verified against the pinned compiler rather than read off its
      // documentation. It is the one top-level property whose whole purpose is to survive
      // compilation: a host writes what it needs into it and reads it back off the output.
      spec.fields["usermeta"]?.let { put("usermeta", it) }
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
      val owner = independenceOwner(view)
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
    val owner = independenceOwner(view)
    return if (owner.isEmpty()) channel else "${owner}_$channel"
  }

  /**
   * Which child of the composition a scale or a guide resolved **independently** belongs to.
   *
   * Independence is settled between the children of the composition, and those are a
   * concatenation's plots or a facet's single cell — never the layers *inside* one. A trellis of
   * layers that measures its `x` per cell has one `child_x`, not one scale per layer: the layers
   * are one model to the facet, and two scales there would be two axes over the same picture.
   */
  private fun independenceOwner(view: UnitView): String =
    when {
      concat != null -> plotOf(view)
      facet != null -> prefixed("child")
      else -> view.childName
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
  /**
   * `model.parent && !isTopLevelLayer(model)`: whether a view's bound-scale state is pushed
   * outward.
   *
   * ```js
   * function isTopLevelLayer(model: Model): boolean {
   *   return model.parent && isLayerModel(model.parent) && (!model.parent.parent || isTopLevelLayer(model.parent.parent));
   * }
   * ```
   *
   * A view drawn by itself has nothing above it, and a member of a **single** layer at the root of
   * the chart is drawn in the chart's own group — so in neither case is there an outer signal to
   * push into. Everything else has one: a plot of a concatenation, a cell of a trellis, and a
   * member of a *nested* layer, which is what a composite mark and a layer of layers both are.
   *
   * The nesting is read off the view's name, this compiler having no model tree to walk:
   * `layer_0_layer_1` is a member of a layer inside a layer, where `layer_0` is a member of the
   * chart's own.
   */
  private fun pushesOutward(view: UnitView): Boolean {
    if (concat != null || facet != null) return true
    return Regex("(^|_)layer_\\d+").findAll(view.name).count() >= 2
  }

  private fun boundOutward(
    selection: Selection,
    views: List<UnitView>,
  ): List<Pair<String, String>> {
    if (!selection.bindsScales) return emptyList()
    // Every view that declares it, not just the one that owns the component. `topLevelSignals` is
    // called once per unit and **appends** the mappings it does not already have — "no single
    // selCmpt has a global view" — so a repeated plot's bound signal names every field any of its
    // copies is scaled by, in the order the copies were written. The diagonal of a scatter-plot
    // matrix projects one field where the others project two, and taking a single copy's answer
    // left a whole column of the grid unbound.
    val declaring = views.filter { view ->
      Selection.from(view.spec.params).any { it.name == selection.name }
    }
    val over = (listOfNotNull(selection.owner) + declaring).distinct().ifEmpty { views.take(1) }
    // `if (!model.parent || isTopLevelLayer(model) || bound.length === 0) return signals` — a chart
    // whose views push nothing outward has nothing to push *into*, and the state is read from the
    // one view's own signals.
    if (over.none { pushesOutward(it) }) return emptyList()
    val out = LinkedHashMap<String, String>()
    for (view in over) {
      selection.intervalChannels(view).forEach { (_, field) ->
        out.getOrPut(field) { Fields.varName("${selection.name}_$field") }
      }
    }
    return out.toList()
  }

  /**
   * The signals a selection **bound to a control** publishes, which stand at the top of the chart.
   *
   * `inputs.ts`'s `topLevelSignals`: one per projection, carrying the binding itself. The widget is
   * outside the drawing, so the signal it writes cannot live inside a cell that is drawn once per
   * value of the facet — there would be one control per cell.
   */
  private fun boundInward(selection: Selection, views: List<UnitView>): List<String> {
    if (selection.inputs == null) return emptyList()
    val view = selection.owner ?: views.firstOrNull() ?: return emptyList()
    return selection.projections(view).map { (_, field) ->
      Fields.varName("${selection.name}_$field")
    }
  }

  /**
   * `assembleProjectionForModel`: the projections the chart writes.
   *
   * A **fitted** projection is given the plotting area to fill and the features to fill it with, so
   * that the map is as large as it can be and centred without anything being said about where it
   * sits. One that states its own `scale` or `translate` has been placed by hand, and takes the
   * middle of the plotting area unless it says otherwise.
   */
  private fun projections(views: List<UnitView>): List<VegaValue> =
    views
      .filter { it.hasProjection && !it.projectionMerged }
      .distinctBy { it.projectionName }
      .map { view ->
        obj {
          put("name", view.projectionName)
          if (view.projectionFits) {
            put("size", signalRef("[${view.widthSignal}, ${view.heightSignal}]"))
            // What each view the projection was merged over contributes, in order — its feature
            // collections, or its own table where it gathered none.
            val over = view.projectionFitViews.ifEmpty { listOf(view) }
            val fits =
              over
                .flatMap { it.geoJsonSignals.ifEmpty { listOf("data('${it.mainData}')") } }
                .distinct()
            put(
              "fit",
              signalRef(if (fits.size > 1) "[${fits.joinToString(", ")}]" else fits.first()),
            )
          } else {
            put("translate", signalRef("[width / 2, height / 2]"))
          }
          // `replaceExprRef`: a property written as an **expression** is a signal to Vega, which
          // has no notion of `expr` — a projection whose type a parameter chooses says
          // `{"signal": "projection"}`, not `{"expr": "projection"}`.
          view.projection?.fields?.forEach { (key, value) ->
            val expression = (value as? VegaValue.Obj)?.takeIf { it.has("expr") }?.string("expr")
            put(key, if (expression != null) signalRef(expression) else value)
          }
          // `projComp.set('type', 'equalEarth', false)`: the projection a **unit** falls back to.
          // A merged one is built from what its members *specified* and carries no default with it,
          // so a layer that says nothing about the kind of map it wants writes nothing.
          if (
            view.projection?.fields?.containsKey("type") != true &&
              view.projectionFitViews.isEmpty()
          ) {
            put("type", "equalEarth")
          }
        }
      }

  /**
   * `parseNonUnitProjections` above the plots: the views a node draws geography with, merged.
   *
   * Answers with the views a level contributes upward, which is the ones it merged — and with
   * **nothing** where its children disagreed, exactly as upstream returns no component for a level
   * it could not merge. A disagreement is therefore not an obstacle to the level above: those
   * children keep the projections they had, and whatever else agreed is still merged around them.
   *
   * Bottom-up, so an outer concatenation renames over an inner one's merge and the outermost name
   * is the one that survives — `renameProjection` walking down, seen from the other end.
   */
  private fun elevateProjection(node: Node): List<UnitView> {
    val geographic =
      when (node) {
        is Node.Leaf -> node.plot.views.filter { it.hasProjection }
        is Node.Nest -> node.children.flatMap { elevateProjection(it) }
      }
    if (geographic.isEmpty()) return emptyList()
    val (spoke, merged) = agreedProjection(geographic) ?: return emptyList()
    // A leaf has merged already, in the pass over the plots: this is the level *above* it.
    if (node !is Node.Nest) return geographic
    val name =
      Fields.varName(listOf(node.name, "projection").filter { it.isNotEmpty() }.joinToString("_"))
    geographic.forEachIndexed { index, view ->
      view.projectionName = name
      view.projectionMerged = index != 0
      if (index == 0) view.projection = merged
      // The merge carries the fit, and only the one that carries it: a view that was the first of
      // its own plot's merge is not the first of this one, and would otherwise write a second
      // projection fitted to a subset of what the chart draws.
      view.projectionFitViews = if (index == 0) listOf(spoke) + geographic else emptyList()
    }
    return geographic
  }

  /**
   * `mergeIfNoConflict`: the one projection a level's children agree on, or null where they do not.
   *
   * ```js
   * const allPropertiesShared = every(PROJECTION_PROPERTIES, (prop) => {
   *   if (!hasOwnProperty(first.explicit, prop) && !hasOwnProperty(second.explicit, prop)) return true;
   *   if (hasOwnProperty(first.explicit, prop) && hasOwnProperty(second.explicit, prop) &&
   *       deepEqual(first.get(prop), second.get(prop))) return true;
   *   return false;
   * });
   *
   * const size = deepEqual(first.size, second.size);
   * if (size) {
   *   if (allPropertiesShared) return first;
   *   else if (deepEqual(first.explicit, {})) return second;
   *   else if (deepEqual(second.explicit, {})) return first;
   * }
   * return null;
   * ```
   *
   * A member that **said nothing** agrees with one that did: the merge takes the one that spoke.
   * Comparing the specifications for equality instead left a map layered under another map, where
   * only the upper one names its kind, with a projection each — and two projections fitted to two
   * different sets of outlines draw the same country at two sizes.
   *
   * The sizes have to agree too, which here is whether each is **fitted**: a projection that states
   * its own `scale` or `translate` has been placed by hand and has no size to compare.
   */
  private fun agreedProjection(views: List<UnitView>): Pair<UnitView, VegaValue.Obj>? {
    fun fitted(spec: VegaValue.Obj) =
      spec.fields["scale"] == null && spec.fields["translate"] == null
    var winner = views.first()
    var merged = winner.projection ?: return null
    for (view in views.drop(1)) {
      val own = view.projection ?: return null
      if (fitted(merged) != fitted(own)) return null
      when {
        merged.fields == own.fields -> Unit
        merged.fields.isEmpty() -> {
          winner = view
          merged = own
        }
        own.fields.isEmpty() -> Unit
        else -> return null
      }
    }
    return winner to merged
  }

  /** The plot a view belongs to, where the compiler has been told about it. */
  /**
   * The views whose chains hang **below** [plot]'s grid, which is every one reading no table of its
   * own.
   *
   * `parseRoot` hands a child the partition only where the child states no `data` of its own; one
   * that states its own starts a root instead, so it is no child of the facet — it cannot be the
   * fork that stops `moveFacetDown`, the facet's fields group nothing of its, and its marks read
   * its own chain in every cell alike.
   *
   * Where a cell holds nothing else — every layer in it reading a table of its own — the grid still
   * has to partition something, and it partitions the first of them as it always did. Upstream
   * partitions the chain the *facet model* computes for itself, which nothing here builds: no view
   * stands for it.
   */
  private fun viewsBelow(plot: Plot): List<UnitView> =
    plot.views.filter { !it.ownsSource }.ifEmpty { plot.views.take(1) }

  private fun plotOfView(view: UnitView): Plot? =
    plotNames[view]?.let { name -> allPlots.firstOrNull { it.name == name } }

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
          VegaValue.Obj(
            node.fields.entries.associate { (key, own) ->
              // **Not into the data.** The rename is a substring replace over every string in the
              // finished specification, which is right for the places a signal name is read as text
              // and wrong for a dataset's rows: those are the user's values, and a row holding a
              // string that happened to contain a generated name — they are long and specific, but
              // they are not reserved — would have been quietly rewritten. Nothing under `values`
              // is a reference to anything.
              key to if (key in LITERAL_DATA_KEYS) own else walk(own)
            }
          )
        else -> node
      }
    return walk(value)
  }

  /** The chart's own name in front of a shared name, where the specification gave it one. */
  /**
   * A name inside this chart, prefixed by the chart's own `name` where it has one.
   *
   * Run through [Fields.varName], which is upstream's `Model.getName`: a Vega name is read as an
   * identifier in expressions and signal references, so a chart called `score-total` becomes
   * `score_total` and its x scale is `score_total_x`. Without the substitution the emitted
   * specification referred to `score-total_x` — which Vega resolves as a name but a **signal
   * expression** reading it parses as a subtraction, and which disagrees with upstream everywhere
   * the name appears. Found by the `usermeta` fixture, whose chart carries a hyphenated `name`.
   */
  private fun prefixed(name: String): String =
    Fields.varName(
      listOf(spec.string("name").orEmpty(), name).filter { it.isNotEmpty() }.joinToString("_")
    )

  private fun plotOf(view: UnitView): String = plotNames[view] ?: ""

  private val plotNames = mutableMapOf<UnitView, String>()

  /** Every plot the chart was built from, so a view can be asked which grid it belongs to. */
  private var allPlots: List<Plot> = emptyList()

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
      // `assembleGroupStyle` asks the view's own `view` block first: a chart that names its styles
      // is drawn with **those**, and the `cell` a plotting area gets by default is a default like
      // any other. A layer unions its members' answers, so a member naming one style and a member
      // naming none come out as that style beside `cell`.
      when (val stated = view.spec.viewBackground?.fields?.get("style")) {
        null ->
          styles +=
            if (view.spec.encoding["x"] != null || view.spec.encoding["y"] != null) "cell"
            else "view"
        is VegaValue.Str -> styles += stated.value
        is VegaValue.Arr -> styles += stated.values.mapNotNull { (it as? VegaValue.Str)?.value }
        // A `"style": null` names no style and asks for no default either: `style !== undefined`
        // is what the question is, and Vega is handed nothing.
        else -> Unit
      }
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

    /**
     * The grids this plot's cells are laid out in, outermost first.
     *
     * One entry for an ordinary trellis and none for a plain plot. A grid whose every cell is
     * itself a grid has two, and they are two levels of cell group rather than one crossed grid.
     */
    var facets: List<FacetLayout> = emptyList()

    /**
     * The facet levels inside this plot's outermost one, outermost first — its grids to lift.
     *
     * Per plot rather than per chart: a concatenation may hold a nested grid beside a plain plot,
     * and the levels belong to the plot whose specification wrote them.
     */
    var nestedFacets: List<VegaValue.Obj> = emptyList()

    /**
     * Where this plot's flow splits at its innermost partition, and the partitions above it.
     *
     * Per plot for the same reason the grids are: a concatenation may hold a nest beside a plain
     * plot, and only the nest's own chain is computed inside a cell.
     */
    var split: FacetNode? = null

    var splitAbove: List<FacetNode> = emptyList()

    /**
     * The partition where the flow does **not** split at it, which is the other of the two shapes.
     *
     * A node in the flow either way, and a node in the flow takes a name and stands somewhere: the
     * grid's own value lists are written at the point it stands, so the node has to be kept to be
     * asked where the assembler passed it.
     */
    var tail: FacetNode? = null

    /** The datasets this plot's innermost cell computes for itself, where the flow splits. */
    var groupData: List<VegaValue> = emptyList()

    /** The table the outermost partition reads, and where in the list it stood. */
    var reads: String? = null

    var domainsAt: Int = -1

    /**
     * The **innermost** grid, which is the one that owns a cell.
     *
     * Everything about a cell belongs to the level closest to the marks: its style and size, the
     * scales resolved within it, the split in the data flow, the gridlines. The levels above only
     * caption and arrange what that level produced.
     */
    val facet: FacetLayout?
      get() = facets.lastOrNull()

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
        out.getOrPut(channel) { scale }
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

  /**
   * A grid made straight from a facet definition, for a cell that holds plots rather than views.
   *
   * The same three shapes `liftFacet` builds — a crossed grid, a wrapped one, or nothing — read
   * from the definition as written instead of from an encoding a level was lifted out of.
   */
  private fun gridFor(level: VegaValue.Obj, owner: String): FacetLayout? {
    val channels = Parse(config, diagnostics, selections).facetChannels(level, "$.facet")
    channels["facet"]?.let {
      return FacetWrap(
        it,
        (spec.number("columns") ?: it.raw.number("columns"))?.toInt(),
        owner,
        config,
        wrappedFacetLayout(level, it),
      )
    }
    val row = channels["row"]?.let { Facet("row", it, owner, config) }
    val column = channels["column"]?.let { Facet("column", it, owner, config) }
    if (row == null && column == null) {
      diagnostics.fatal(
        VegaLiteDiagnostics.UNSUPPORTED_COMPOSITION,
        "A `facet` names a `row`, a `column`, or one field to wrap; this one names none.",
        jsonPath = "$.facet",
      )
      return null
    }
    return FacetGrid(
      row,
      column,
      owner,
      crossedFacetLayout(level, channels["row"], channels["column"]),
    )
  }

  private fun plots(): List<Plot>? {
    val leaves = mutableListOf<Plot>()

    fun build(
      name: String,
      child: VegaValue.Obj,
      above: List<String>,
      /**
       * The facet levels inside this plot's outermost one, peeled where its spec was normalised.
       */
      nestedFacets: List<VegaValue.Obj> = emptyList(),
    ): Node? {
      val nested = Concat.of(child, diagnostics, config)
      if (nested == null) {
        val plot = Plot(name, child)
        plot.nestedFacets = nestedFacets
        plot.views = views(plot.spec, plot.name, above) ?: return null
        plot.views.forEach { plotNames[it] = plot.name }
        leaves += plot
        return Node.Leaf(plot)
      }
      val children =
        nested.children.mapIndexed { index, entry ->
          // A plot that names itself is compiled under that name, which is how a *repetition*'s
          // copies come out `child__b` rather than `concat_0`: upstream's model takes `spec.name`
          // over the name its parent offered it. Below the first level the names compose.
          val here =
            entry.name
              ?: Fields.varName(
                listOf(name, "concat_$index").filter { it.isNotEmpty() }.joinToString("_")
              )
          // A concatenation's own transforms belong to *it*, not to each plot below it: they are
          // one chain in upstream's tree, forking at the plots, and naming them for each plot in
          // turn would leave three copies of one chain with nothing to fold them by.
          build(here, entry.spec, owning(entry.spec, above, here), entry.nestedFacets)
            ?: return null
        }
      return Node.Nest(name, nested, children, child)
    }

    // A chart that **names itself** is compiled under that name, the way a concatenation's children
    // are: `getName` prefixes every scale, mark and step signal with the model's own name, so a
    // specification with `"name": "plotname"` reads `plotname_x` throughout. The sizes are the
    // exception — `width` and `height` are the chart's, not this level's.
    // A concatenation wrapped in grids is compiled under the name the innermost grid gives its
    // cell,
    // which is one `child` per level: that is what makes its plots `child_concat_0` rather than
    // `concat_0`, and their scales and sizes follow the name.
    val root =
      if (cellLevels.isEmpty()) spec.string("name").orEmpty()
      else
        Fields.varName(
          listOf(spec.string("name").orEmpty())
            .plus(List(cellLevels.size) { "child" })
            .filter { it.isNotEmpty() }
            .joinToString("_")
        )
    plotTree =
      build(
        root,
        spec,
        List(spec.array("transform").orEmpty().size) { spec.string("name").orEmpty() },
        nestedFacets,
      ) ?: return null
    return leaves
  }

  /** [above] extended so that everything [spec] adds of its own is owned by [name]. */
  private fun owning(spec: VegaValue.Obj, above: List<String>, name: String): List<String> {
    val own = spec.array("transform").orEmpty().size
    // A level that reads its own table stands on nothing above it, so every transform it carries is
    // its own.
    if (own < above.size) return List(own) { name }
    return above + List(own - above.size) { name }
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
      // A cell's size is named through the model, the whole grid's is not: `width` is always the
      // chart's, and `child_width` belongs to the facet that owns the cell. **One `child` per
      // level**: a grid whose cells are grids names its innermost cell `child_child_width`, because
      // the name belongs to the model that owns it and each level puts a child under itself.
      val prefix = "child_".repeat(plot.facets.size)
      plot.sizeNames =
        if (facet == null) mapOf("x" to "width", "y" to "height")
        else mapOf("x" to prefixed("${prefix}width"), "y" to prefixed("${prefix}height"))
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
      // A **faceted** plot contributes nothing to a merge: `parseConcatLayoutSize` reads each
      // child's own layout size and a grid has none — the size it knows is one *cell's*, and two
      // plots agreeing about a cell are not two plots agreeing about themselves. Offering the
      // cell's size instead merged a trellis with the plain plot beside it whenever the two
      // happened to be the same width, and then wrote that shared name into the plain plot's marks.
      is Node.Leaf ->
        if (node.plot.facet != null) null
        else
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
        // A **faceted** plot's size is its *cell's*: the grid itself is a place its cells are
        // arranged in and has no extent of its own to name, so the signal is `child_width` under
        // the plot's name and no level above may rename it into a size the plot does not have.
        // One `child` per **level**: a plot that is a grid of grids names its innermost cell
        // `concat_0_child_child_width`, the name belonging to the model that owns the cell.
        val depth = (child as? Node.Leaf)?.plot?.facets?.size ?: 0
        val own =
          if (depth > 0) "${nameOf(child)}_${"child_".repeat(depth)}$plain"
          else name ?: "${nameOf(child)}_$plain"
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
      if (plot.facet != null) continue
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
          .mapNotNull { name -> merged[name]?.let { mergedSizeSignal(name, it) } } +
          node.children.flatMap { sizeSignalsFor(it) }
    }

  private fun mergedSizeSignals(): List<VegaValue> =
    merged.entries.mapNotNull { (name, value) -> mergedSizeSignal(name, value) }

  /**
   * The signal a merged size writes, or null where it is a top-level property instead.
   *
   * ```js
   * layoutSignals = layoutSignals.filter((signal) => {
   *   if ((signal.name === 'width' || signal.name === 'height') && signal.value !== undefined) {
   *     topLevelProperties[signal.name] = +signal.value;
   *     return false;
   *   }
   *   return true;
   * });
   * ```
   *
   * The hoist upstream does at the end is for a signal **carrying a value**: a plain number named
   * `width` is the chart's width and is written as one. A `"container"` size has no number to hoist
   * — the page has to be measured first — so it stays a signal, and this compiler wrote the view's
   * default out as a property instead. Four specifications in the wild corpus are a column of plots
   * each asking the page for its width.
   */
  /**
   * The chart's own size, from the merged one — `topLevelProperties[signal.name] = +signal.value`.
   *
   * A **number** either way: the hoist coerces what it moves, so a chart written `"width": "1024"`
   * is 1024 wide at the top even though the signal a *plot* of a concatenation keeps carries the
   * string as it was written. Coerced as JavaScript's unary plus does it — a string of digits is
   * its number, the empty string is zero — and a string that is no number at all is left where it
   * was rather than written out as a `NaN` Vega cannot read.
   */
  private fun hoisted(merged: VegaValue?): VegaValue? =
    when (merged) {
      is VegaValue.Num -> merged
      is VegaValue.Str ->
        (if (merged.value.isBlank()) 0.0 else merged.value.trim().toDoubleOrNull())?.let {
          VegaValue.Num(it)
        }
      else -> null
    }

  private fun mergedSizeSignal(name: String, value: VegaValue): VegaValue? {
    val channel = if (name.endsWith("width", ignoreCase = true)) "x" else "y"
    if (value == VegaValue.Str("container")) {
      return LayoutSize.containerSignal(name, channel, config)
    }
    if (name == "width" || name == "height") return null
    return obj {
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

  /**
   * The gap between a grid's cells: what the specification stated, then what the theme did.
   *
   * `spacing` is a number or a `{row, column}` pair, and a pair states only the side it means: a
   * trellis of rows an inch apart still wants the configured gap between its columns, so the side
   * left out is filled in rather than dropped.
   */
  /**
   * `getFacetMappingAndLayout`: the composition-layout properties a **wrapped** facet carries.
   *
   * `align` and `center` are stated beside the facet in the operator form and on the channel in the
   * encoding form, and upstream's normaliser lifts the one onto the other before either is read —
   * the same two places `columns` is looked for.
   */
  private fun wrappedFacetLayout(owner: VegaValue.Obj, def: ChannelDef): VegaValue.Obj = obj {
    // `bounds` is never lifted — a facet definition has no such property — so it is the chart's own
    // and nothing else's.
    owner.fields["bounds"]?.let { put("bounds", it) }
    // `{...outerSpec, ...layout}`: the **lifted** properties are spread after the chart's own, so a
    // `center` written on the facet channel outranks one written beside the chart. This asked the
    // chart first and had the precedence the other way round.
    for (key in listOf("align", "center")) {
      (def.raw.fields[key] ?: owner.fields[key])?.let { put(key, it) }
    }
  }

  /**
   * `extractCompositionLayout` and the crossed half of `getFacetMappingAndLayout`, together.
   *
   * A **crossed** grid lifts its layout properties per channel:
   * ```js
   * for (const prop of ['align', 'center', 'spacing'] as const) {
   *   if (def[prop] !== undefined) {
   *     layout[prop] ??= {};
   *     layout[prop][channel] = def[prop];
   *   }
   * }
   * ```
   *
   * so a trellis whose *rows* state an alignment gets `{"align": {"row": …}}` — an object, which
   * **replaces** whatever the chart itself said rather than filling in the other side. Row before
   * column, the loop's own order.
   *
   * `bounds` is not lifted and comes from the chart alone. `spacing` is [statedFacetSpacing]'s, the
   * layout's `padding` being a different key.
   */
  private fun crossedFacetLayout(
    owner: VegaValue.Obj,
    row: ChannelDef?,
    column: ChannelDef?,
  ): VegaValue.Obj = obj {
    owner.fields["bounds"]?.let { put("bounds", it) }
    for (key in listOf("align", "center")) {
      val perChannel =
        listOfNotNull("row" to row, "column" to column).mapNotNull { (channel, def) ->
          def?.raw?.fields?.get(key)?.let { channel to it }
        }
      when {
        perChannel.isNotEmpty() -> put(key, obj { perChannel.forEach { (c, v) -> put(c, v) } })
        else -> owner.fields[key]?.let { put(key, it) }
      }
    }
  }

  /**
   * `getFacetMappingAndLayout`: a facet **channel** carries its own `spacing`.
   *
   * The operator form writes it beside the facet and the encoding form writes it on the channel,
   * and upstream's normaliser lifts the one onto the other before `assembleLayout` turns it into
   * the layout's `padding` — the same two places `columns` and `align` are looked for.
   *
   * A wrapped facet's is a number, lifted whole. A crossed one's is written **per channel**,
   * `layout[prop][channel] = def[prop]`, so a trellis whose rows state a gap and whose columns do
   * not is a `{row, column}` pair with one side still to fill in.
   */
  private fun statedFacetSpacing(owner: VegaValue.Obj): VegaValue? {
    owner.fields["spacing"]?.let {
      return it
    }
    val encoding = owner.obj("encoding") ?: return null
    encoding.obj("facet")?.fields?.get("spacing")?.let {
      return it
    }
    val sides = LinkedHashMap<String, VegaValue>()
    for (channel in listOf("row", "column")) {
      encoding.obj(channel)?.fields?.get("spacing")?.let { sides[channel] = it }
    }
    return if (sides.isEmpty()) null else VegaValue.Obj(sides)
  }

  private fun facetSpacing(owner: VegaValue.Obj): VegaValue {
    val stated = statedFacetSpacing(owner) ?: config.raw.obj("facet")?.fields?.get("spacing")
    val configured = config.raw.obj("facet")?.number("spacing") ?: FACET_SPACING
    return if (stated is VegaValue.Obj)
      obj {
        put("row", stated.number("row") ?: configured)
        put("column", stated.number("column") ?: configured)
      }
    else num((stated as? VegaValue.Num)?.value ?: configured)
  }

  private fun leafGroup(plot: Plot): VegaValue = run {
    obj {
      put("type", "group")
      put("name", "${plot.name}_group")
      // A plot inside a composition is still a unit or a layer, so its own title frames the group
      // — unless it is a **grid**, which is a composition itself and anchors its title to the
      // start rather than framing a plotting area it does not have.
      //
      // And a plot that is a **layer** promotes a title from one of its members, exactly as the
      // chart does: `LayerModel.assembleTitle` is the same function whether the layer is the whole
      // chart or one plot of a concatenation. Reading only the plot's own title left a
      // concatenation of layers untitled, cell by cell, with the captions written on the layers
      // that carry the text marks.
      val title =
        plot.spec.fields["title"]?.let { titleFor(it, composed = plot.facet != null) }
          ?: layerTitle(plot.spec)
      title?.let { put("title", it) }
      if (plot.facet == null) put("style", style(plot.views))
      // A **grid** has no plotting area to size: its layout places the cells, and the size the
      // plot's name carries is one cell's.
      if (plot.facet == null) {
        put(
          "encode",
          obj {
            put(
              "update",
              obj {
                put("width", signalRef(plot.sizeNames.getValue("x")))
                put("height", signalRef(plot.sizeNames.getValue("y")))
                // A plot's own `view` block paints its plotting area, the same as the chart's
                // does: a middle column drawn without a border says so on itself rather than on
                // the chart.
                (viewEncode(plot.spec) as? VegaValue.Obj)?.obj("update")?.fields?.forEach {
                  (key, value) ->
                  put(key, value)
                }
              },
            )
          },
        )
      }
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
      // A **faceted** plot lays its own cells out inside its group: the grid is this plot's, not
      // the chart's, so its headers and its cell stand here and the axes are already inside them.
      val grid = plot.facet
      if (grid != null) {
        put(
          "layout",
          // The **outermost** level arranges the plot's group; a level inside one arranges its
          // cells
          // within that level's cell, and its layout is written there.
          plot.facets
            .first()
            .layout(
              facetSpacing(plot.spec),
              HEADER_OFFSET,
              config,
              setOf("x", "y")
                .filter { resolve.scaleIsIndependent(it, defaultIndependent = false) }
                .toSet(),
              headings = if (plot.facets.size > 1) headingsPerLevel(plot.facets).first() else null,
              childHasSize = plot.facets.size == 1,
            ),
        )
        put("marks", arr(marks(plot.views, plot.axes, grid, plot.facets.dropLast(1))))
      } else {
        put("marks", arr(brushed(plot.views, plot.views.flatMap { Marks.marks(it) })))
      }
      if (grid == null && plot.axes.isNotEmpty()) put("axes", arr(plot.axes))
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

  private fun views(
    spec: VegaValue.Obj,
    namePrefix: String,
    /** The model each of the chart's own transforms belongs to, from the levels above. */
    above: List<String> = emptyList(),
    /**
     * Whether this specification itself reads a table of its own — see [UnitView.ownsSource].
     *
     * False for a plot, which is either the chart or a cell whose `data` the levels above it wrote:
     * a **cell** that states its own table is the one shape this does not carry, its grid then
     * partitioning a chain no view here represents.
     */
    ownsSource: Boolean = false,
  ): List<UnitView>? {
    val mineHere = owning(spec, above, namePrefix)
    fun named(suffix: String) =
      listOf(namePrefix, suffix).filter { it.isNotEmpty() }.joinToString("_")

    val normalize = Normalize(config, diagnostics)
    val composite = Composite(config, diagnostics)
    // A composite mark stands for a layer of ordinary ones and names its parts relative to itself —
    // a box plot's whiskers are `layer_0_layer_1_layer_0`, being a layer inside a layer. A path
    // overlay may then apply to each part and numbers its own results beneath that name.
    fun expand(
      unit: VegaValue.Obj,
      prefix: String,
      /** The model each of [unit]'s own transforms belongs to. */
      above: List<String>,
    ): List<Triple<String, VegaValue.Obj, List<String>>> {
      val parts = composite.normalize(unit) ?: listOf("" to unit)
      // Whatever the expansion **added** belongs to the model being expanded, not to the parts it
      // expanded into: a box plot's aggregate is written once, above its five views, so all five
      // carry the same node rather than one apiece.
      fun owners(part: VegaValue.Obj): List<String> =
        above +
          List((part.array("transform").orEmpty().size - above.size).coerceAtLeast(0)) { prefix }
      return parts.flatMap { (name, part) ->
        val here = Fields.varName(listOf(prefix, name).filter { it.isNotEmpty() }.joinToString("_"))
        val overlaid = normalize.pathOverlay(part)
        if (overlaid == null) {
          listOf(Triple(here, part, owners(part)))
        } else {
          overlaid.mapIndexed { index, view ->
            Triple(
              Fields.varName(
                listOf(here, "layer_$index").filter { it.isNotEmpty() }.joinToString("_")
              ),
              view,
              owners(view),
            )
          }
        }
      }
    }
    if (spec.has("layer")) {
      // Each declared layer may itself normalize into more than one — a line that draws its own
      // points is two marks — so the list is expanded first and only then numbered. The numbering
      // is what names `layer_0_marks`, so it has to count the views that actually exist.
      val units = mutableListOf<Member>()
      /** The model each collected view's transforms was written on, in the same order. */
      val owners = mutableMapOf<VegaValue.Obj, List<String>>()

      /**
       * A layer's members, and the members of any layer among them.
       *
       * A layer inside a layer needs nothing new: its names simply run deeper —
       * `layer_1_layer_0_marks` — which is exactly what a composite mark inside a layer already
       * produces, so the naming was already carrying it. What the recursion has to keep hold of is
       * the name of the **outermost** member, because that is the child a top-level `resolve`
       * speaks about; the nesting below it is not a level anything resolves against.
       */
      fun collect(
        parent: VegaValue.Obj,
        prefix: String,
        owner: String?,
        path: String,
        /** The model each of the parent's own transforms belongs to. */
        above: List<String>,
        /** Whether a level above these members already read a table of its own. */
        ownsAbove: Boolean,
      ) {
        parent.array("layer").orEmpty().forEachIndexed { index, layer ->
          val child = layer as? VegaValue.Obj ?: return@forEachIndexed
          // A layer's member is a view or a layer of views — never a composition that arranges
          // plots. This is the one refusal here that is not a gap to be closed: upstream rejects
          // the
          // same four with `Invalid specification`, the grammar saying a layer's members are units
          // or layers, so there is no chart to reach later and the wording says so. It used to be
          // reported as a **missing mark** — a `facet` has none of its own, so the innermost check
          // failed first — which sent the reader looking for a mark in a specification whose
          // trouble
          // was the composition around it.
          for (composition in listOf("facet", "repeat", "hconcat", "vconcat", "concat")) {
            if (!child.has(composition)) continue
            diagnostics.fatal(
              VegaLiteDiagnostics.UNSUPPORTED_COMPOSITION,
              "A `$composition` inside a `layer` is not a chart Vega-Lite describes: a layer draws " +
                "its members over one another, so each is a single view or a layer of views. " +
                "Upstream rejects it as an invalid specification rather than compiling it. A " +
                "`layer` inside a `layer` is allowed and does compile, as does a `repeat` whose " +
                "copies are layered — `{\"repeat\": {\"layer\": […]}}`.",
              jsonPath = "$path.layer[$index].$composition",
            )
            return
          }
          val merged = inherited(parent, child)
          // A layer that names itself is compiled under that name, which is what a `repeat` over
          // `layer` relies on: its copies are `child__layer_b`, not `layer_0`.
          val here =
            child.string("name")
              ?: Fields.varName(
                listOf(prefix, "layer_$index").filter { it.isNotEmpty() }.joinToString("_")
              )
          val here2 = "$path.layer[$index]"
          // A transform belongs to the model it was **written on**, and that model's name is what
          // names the signals it publishes: a `bin` above a layer is the layer's, so its bounds are
          // `bin_maxbins_10_x_bins` and both members read the same ones. Named for the member
          // instead, one bucketing became two that no optimizer could fold, and the chart was
          // drawn twice over two sets of buckets.
          val mine =
            if (child.has("data")) List(child.array("transform").orEmpty().size) { here }
            else above + List(child.array("transform").orEmpty().size) { here }
          // A member that states its own `data` reads a table of its own, and so does every member
          // of a layer that states one: `parseRoot` starts a root for it rather than descending
          // from the level above, and the whole chain below it hangs there. See
          // [UnitView.ownsSource].
          val ownsHere = ownsAbove || child.has("data")
          if (child.has("layer")) {
            collect(merged, here, owner ?: here, here2, mine, ownsHere)
          } else {
            expand(merged, here, mine).forEach {
              owners[it.second] = it.third
              units += Member(it.first, it.second, owner ?: here, here2, ownsHere)
            }
          }
        }
      }
      collect(spec, namePrefix, null, "$", mineHere, ownsSource)

      // A member the parser could not read is **dropped**, and the rest of the layer is drawn.
      // Upstream throws on the same document, which takes the whole chart with it; keeping the
      // members that parsed is this engine's rule, and the parser has already said what was wrong
      // with the one that did not — `parser.unit` reports before it answers null. What was missing
      // is the fact that a layer came back smaller than it was written, which a reader counting
      // marks would otherwise have to work out.
      return units.mapNotNull { member ->
        val parsed = parser.unit(member.unit, member.path)
        if (parsed == null) {
          diagnostics.error(
            VegaLiteDiagnostics.UNSUPPORTED_COMPOSITION,
            "This layer member could not be read, so the layer is drawn without it. The " +
              "diagnostic above says what was wrong with it.",
            jsonPath = member.path,
          )
          return@mapNotNull null
        }
        UnitView(parsed, config, member.name, member.child, parentIsLayer = true).also { view ->
          view.transformOwners = owners[member.unit].orEmpty()
          view.ownsSource = member.ownsSource
        }
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
      return expand(spec, namePrefix, mineHere).mapNotNull { (name, unit, owned) ->
        parser.unit(unit, "$")?.let {
          UnitView(it, config, name, name, parentIsLayer = true).also { view ->
            view.transformOwners = owned
            view.ownsSource = ownsSource
          }
        }
      }
    }

    val unit = parser.unit(spec, "$") ?: return null
    return listOf(
      UnitView(unit, config, namePrefix).also {
        it.transformOwners = mineHere
        it.ownsSource = ownsSource
      }
    )
  }

  /** One member a `layer` collected, with everything the view built from it needs to know. */
  private class Member(
    val name: String,
    val unit: VegaValue.Obj,
    /** The outermost member it belongs to, which is what a top-level `resolve` speaks about. */
    val child: String,
    val path: String,
    /** Whether it reads a table of its own — see [UnitView.ownsSource]. */
    val ownsSource: Boolean,
  )

  /**
   * `isFieldOrDatumDef`: whether a channel definition **names** something to measure.
   *
   * ```js
   * export function isFieldDef(channelDef) {
   *   return hasProperty(channelDef, 'field') || channelDef?.aggregate === 'count';
   * }
   * ```
   *
   * A count is the one aggregate that names no column of its own — it counts rows — so it is asked
   * for by name here rather than through a `field`. A `datum` is a literal standing where a column
   * would, and it is a definition of the same kind for this purpose.
   */
  private fun namesAColumn(def: VegaValue.Obj): Boolean =
    def.has("field") || def.has("datum") || def.string("aggregate") == "count"

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
    // A projection is handed down as the data is: a chart that states one draws every member
    // through it, and a member that states its own overrides it.
    put("projection", spec.fields["projection"])
    putAll(child)
    // A child's transforms come **after** its parent's rather than instead of them: the parent's
    // belong to the parent's own data chain and the child's hang below. Letting the child's replace
    // them ran a filter over a column the parent's formula had not yet written.
    //
    // Unless the child states its own `data`, in which case it inherits none of them. `parseData`
    // starts a new source for such a child rather than descending from its parent's main output,
    // so the parent's chain is not above it at all — and a formula written for the chart's own
    // table has no business running over a second table that has no such column.
    val inheritedTransforms =
      if (child.has("data")) child.array("transform").orEmpty()
      else spec.array("transform").orEmpty() + child.array("transform").orEmpty()
    put("transform", if (inheritedTransforms.isEmpty()) null else arr(inheritedTransforms))
    val shared = spec.obj("encoding")
    if (shared != null) {
      // Channel by channel, and **property by property within a channel** — but only where the two
      // are definitions of the same kind. `mergeEncoding` spreads the parent's channel def under
      // the child's when the child's *names a column*, so a shared `x` stating the type and a
      // layer's `x` naming only the field come out as one definition with both; replacing the whole
      // channel there would lose the type, and a quantitative measure would be spoken as a
      // category. Anything else replaces it outright.
      put(
        "encoding",
        obj {
          val own = child.obj("encoding")
          val channels = shared.fields.keys + own?.fields?.keys.orEmpty()
          for (channel in channels) {
            val parent = shared.fields[channel] as? VegaValue.Obj
            val mine = own?.fields?.get(channel)
            // `hasConditionalFieldOrDatumDef`: a single condition that names a column, which is the
            // half of such a channel the parent's definition belongs under.
            val condition = (mine as? VegaValue.Obj)?.obj("condition")
            put(
              channel,
              when {
                parent == null || mine !is VegaValue.Obj -> mine ?: shared.fields[channel]
                // "Field/Datum Def can inherit properties from its parent."
                namesAColumn(mine) ->
                  obj {
                    putAll(parent)
                    putAll(mine)
                  }
                condition != null && namesAColumn(condition) ->
                  obj {
                    putAll(mine)
                    put(
                      "condition",
                      obj {
                        putAll(parent)
                        putAll(condition)
                      },
                    )
                  }
                // `} else if (channelDef || channelDef === null) { merged[channel] = channelDef; }`
                // — a definition that names no column **replaces** the parent's rather than
                // inheriting from it. A member drawing its label at the corner of the plot writes
                // `{"value": "width"}` for its `x`, and spreading the chart's own `x` under it left
                // that member still measuring a column: placed against a scale it had said it did
                // not want, filtered for rows that column had no value in, described by a field it
                // does not show, and contributing to a colour domain it takes no part in. An empty
                // `{}` is such a definition too, and is how a member says it has no `x` at all.
                else -> mine
              },
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
  private fun liftFacet(views: List<UnitView>, owner: String): Pair<List<UnitView>, FacetLayout?> {
    // The model the grid belongs to: the chart itself, or — inside a concatenation — the plot that
    // holds it. Everything the grid names runs through it, so a faceted plot beside a plain one
    // reads `concat_0_cell` rather than `cell`.
    val named = if (owner.isEmpty()) spec.string("name").orEmpty() else owner
    fun through(suffix: String) =
      Fields.varName(if (named.isEmpty()) suffix else "${named}_$suffix")
    fun channel(name: String) = views.firstNotNullOfOrNull { view ->
      view.spec.encoding[name]?.takeIf { it.isFieldDef }?.let { Facet(name, it, named, config) }
    }
    val row = channel("row")
    val column = channel("column")
    val wrapped = views.firstNotNullOfOrNull { view ->
      view.spec.encoding["facet"]?.takeIf { it.isFieldDef }
    }
    if (row == null && column == null && wrapped == null) return views to null
    val crossed = row != null && column != null
    listOfNotNull(row, column).forEach { it.reportUnsupportedSort(diagnostics, crossed) }
    val found: FacetLayout =
      // A wrapped facet written as a **channel** carries its `columns` on the channel itself, where
      // the operator form carries it beside the facet: `mapFacetedUnit` lifts the one to the other,
      // and reading only the outer place left a grid that never wrapped.
      if (wrapped != null)
        FacetWrap(
          wrapped,
          (spec.number("columns") ?: wrapped.raw.number("columns"))?.toInt(),
          named,
          config,
          wrappedFacetLayout(spec, wrapped),
        )
      else FacetGrid(row, column, named, crossedFacetLayout(spec, row?.def, column?.def))

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
            // A cell has a plotting area of its own, so what the view block says about styling it
            // is the cell's — `assembleGroupStyle` is asked of the child model, which is this one.
            viewBackground = view.spec.viewBackground,
          ),
          config,
          // `child` under the chart's own name and above the layer's: a named trellis of layers
          // reads `trellis_child_layer_0`, because the name belongs to the model the cell hangs
          // from and the layer's index to the view inside it.
          Fields.varName(
            listOf(named, "child", view.name.removePrefix(named).trimStart('_'))
              .filter { it.isNotEmpty() }
              .joinToString("_")
          ),
          parentIsLayer = view.parentIsLayer,
        )
        .also {
          // A transform still belongs to the model it was written on. The facet's own are the
          // *facet model's*, whatever the cell is called, and that is what says they stand above
          // the partition rather than being rebuilt inside every cell.
          it.transformOwners = view.transformOwners
          it.widthSignal = through("child_width")
          it.heightSignal = through("child_height")
          it.ownsSource = view.ownsSource
          // A view that reads a table of its own is **not** below the partition: its chain hangs
          // beside the grid, off its own root, so the facet's fields group nothing of its and the
          // facet's own columns are none of its business. Its marks read that chain's output and
          // draw the same rows in every cell, which is what such a layer is written for — a grid
          // of outlines over a map, say, drawn the same over each. See [UnitView.ownsSource].
          if (!view.ownsSource) {
            it.facetFields = found.fields
            it.gridTransforms = gridTransforms
            it.facetDefs = found.defs
            it.facetDeclared =
              view.spec.encoding.entries
                .filter { entry -> entry.key in Channels.FACET_CHANNELS }
                .map { entry -> entry.value }
            // The cell's marks read the partition Vega facets out for them, named `facet`; the
            // scales still read the whole table, so every cell is scaled alike.
            it.markData = found.named("facet")
          }
        }
    } to found
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

  /** The keys a facet's cells own, which stand inside the cell as their scales do. */
  private var cellLegends: List<VegaValue> = emptyList()

  /**
   * Whether the key explaining [scale] belongs **in the cell** rather than beside the grid.
   *
   * ```js
   * resolve.legend[channel] = parseGuideResolve(model.component.resolve, channel);
   *
   * if (resolve.legend[channel] === 'shared') {
   *   legends[channel] = mergeLegendComponent(legends[channel], child.component.legends[channel]);
   * ```
   *
   * `parseNonUnitLegend` merges a child's key up into the composition only where the resolve says
   * **shared**, and `parseGuideResolve` answers `independent` for any channel whose *scale* is
   * independent. A key is a reading of one scale — its swatches are that scale's colours — so a
   * trellis whose cells colour themselves has a key per cell, and it stands in the cell group where
   * the scale it reads does.
   *
   * Asked of the **channel**, not of the scale: `{"legend": {"color": "independent"}}` is a key per
   * cell for a scale every cell shares, which is a reader's answer to a grid too crowded to carry
   * one key beside it. Reading the scale's own name instead would have missed exactly that.
   *
   * This engine placed a key by the composition alone — inside a plot of a concatenation, and
   * otherwise beside the chart — so a trellis's own key was written beside the grid, one key for a
   * scale there is one of per cell, drawn from a scale that does not exist at the level it was
   * written on.
   */
  private fun cellOwnsLegend(channel: String?): Boolean =
    facet != null &&
      concat == null &&
      channel != null &&
      resolve.guideIsIndependent(
        channel,
        resolve.scaleIsIndependent(channel, defaultIndependent = false),
      )

  /** The sizes a cell's own axes fall back to by name, aliased to the cell's own. */
  private var cellSignals: List<VegaValue> = emptyList()

  /** Where the flow splits at the facet: each outer dataset's counterpart inside the cell. */
  private val cellDataFor = mutableMapOf<String, String>()

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
    fun inside(name: VegaValue) = cellDataFor[(name as? VegaValue.Str)?.value] ?: "facet"
    // A domain measured over **several** datasets — two layers of one cell — names each of them,
    // and where the flow splits at the facet each already has a counterpart computed inside the
    // cell. Those are the tables to measure; the partition itself is what a chain that could not
    // split has instead.
    domain.array("fields")?.let { entries ->
      return obj {
        block.fields.forEach { (key, value) ->
          if (key != "domain") put(key, value)
          else
            put(
              "domain",
              obj {
                domain.fields.forEach { (name, own) ->
                  // A stacked domain names its table once and its two ends in `fields`, so the
                  // table is still rewritten here; a domain over several tables names one in each
                  // entry and has none of its own.
                  if (name == "data") put(name, VegaValue.Str(inside(own)))
                  else if (name != "fields") put(name, own)
                  else
                    put(
                      "fields",
                      arr(
                        entries.map { entry ->
                          val part = entry as? VegaValue.Obj ?: return@map entry
                          obj {
                            part.fields.forEach { (key, own) ->
                              put(
                                key,
                                if (key == "data") VegaValue.Str(inside(own)) else own,
                              )
                            }
                          }
                        }
                      ),
                    )
                }
              },
            )
        }
      }
    }
    if (!domain.fields.containsKey("data")) return scale
    return obj {
      block.fields.forEach { (key, value) ->
        if (key != "domain") put(key, value)
        else
          put(
            "domain",
            obj {
              domain.fields.forEach { (name, own) ->
                put(name, if (name == "data") VegaValue.Str(inside(own)) else own)
              }
            },
          )
      }
    }
  }

  /**
   * `distinct_<field>` — the column a grid counts its own cells by, or null where it has no
   * columns.
   *
   * Rows stack however many of them there are; columns have to be counted, and inside another grid
   * counted *per cell*. Both halves of that read this: the outer partition counts it, and the cell
   * the count lands on reads it back as a field.
   */
  private fun columnsOf(grid: FacetLayout): String? =
    grid.byChannel.firstOrNull { it.first == "column" }?.let { "distinct_${it.second}" }

  /**
   * The heading each level of a nest actually writes, by channel — `"Origin / Cylinders"`.
   *
   * `parseFacetHeader` folds a child's heading into its parent's and nulls the child's, and it does
   * so **per channel, and only where the parent facets on that channel too**. Two row grids one
   * inside the other caption the rows once, at the top. A column grid holding row grids captions
   * its columns at the top and its rows inside each cell — there is no column heading below it to
   * absorb, and no row heading above to be absorbed into. Levels arrive outermost first, which is
   * the order the words read in.
   */
  private fun headingsPerLevel(levels: List<FacetLayout>): List<Map<String, String>> {
    val own = levels.map { LinkedHashMap(it.headings(config)) }
    for (index in own.indices.reversed()) {
      if (index == 0) break
      val parent = own[index - 1]
      val child = own[index]
      val above = levels[index - 1].byChannel.map { it.first }.toSet()
      for (channel in child.keys.toList()) {
        if (channel !in above) continue
        val text = child.getValue(channel)
        parent[channel] = parent[channel]?.let { "$it / $text" } ?: text
        child.remove(channel)
      }
    }
    return own
  }

  private fun marks(
    views: List<UnitView>,
    axes: List<VegaValue>,
    grid: FacetLayout? = facet,
    /**
     * The levels **above** [grid], outermost first, each of which wraps what the one below made.
     *
     * `parseFacetHeaders` and `assembleFacetMarks` recurse: a grid whose cells are grids is two
     * cell groups, the outer one arranging what the inner one drew. Everything about a plotting
     * area belongs to the innermost level, which is [grid]; these only caption and arrange.
     */
    above: List<FacetLayout> = emptyList(),
  ): List<VegaValue> {
    val drawn = views.flatMap { Marks.marks(it) }
    val current = grid ?: return drawn
    // The split belongs to the **plot** whose grid this is: a concatenation may hold a nest beside
    // a
    // plain plot, and only the nest's cells compute their own rows.
    val owner = plotOfView(views.first())
    val reads = owner?.reads
    val groupData = owner?.groupData.orEmpty()
    // A brush is drawn **in the cell**, where the marks it reacts to are. The rectangle belongs to
    // one cell's plotting area — it is dragged across those rows and no others — and the signals
    // that follow the pointer read that cell's own scales, so both go inside the group.
    val childMarks = brushed(views, drawn)

    // An axis is drawn **once for the grid** only where its scale is the grid's: a channel each
    // cell scales for itself has an axis per cell, since one band of labels cannot stand for
    // several different extents. `parseGuideResolve` says the same thing about the guide.
    val independent =
      setOf("x", "y").filter { channel ->
        resolve.scaleIsIndependent(channel, defaultIndependent = false)
      }
    fun cellsOwn(axis: VegaValue): Boolean = cellOwnsAxis(axis, ofFacet = true)
    val gridAxes = axes.filter { (it["grid"] as? VegaValue.Bool)?.value == true || cellsOwn(it) }
    val mainAxes = axes.filter { (it["grid"] as? VegaValue.Bool)?.value != true && !cellsOwn(it) }
    val horizontal = mainAxes.filter {
      it.string("orient") == "bottom" || it.string("orient") == "top"
    }
    val vertical = mainAxes - horizontal.toSet()

    // The innermost level's own groups and cell: the headers carrying the shared axes, and the cell
    // the marks are actually drawn in.
    val inner =
      current.groups(
        vertical,
        horizontal,
        HEADER_OFFSET,
        config,
        views.first().widthSignal,
        views.first().heightSignal,
        // What is left of this level's headings once the levels above absorbed what they could.
        headings = if (above.isEmpty()) null else headingsPerLevel(above + current).last(),
      ) +
        current.cellGroup(
          // The table the **facet** reads: the partition the level above it cut where there is one,
          // and otherwise the table above the split — or the view's own main output where nothing
          // splits at all.
          above.lastOrNull()?.named("facet") ?: reads ?: views.first().mainData,
          childMarks,
          gridAxes,
          views.first().widthSignal,
          views.first().heightSignal,
          HEADER_OFFSET,
          // A cell is styled by the same rule the chart's own group is: `cell` where it has a
          // Cartesian position to border, `view` where it has none. A trellis of pies has no
          // plotting area in any of its cells.
          style(views) ?: VegaValue.Str("cell"),
          cellCardinality,
          cellScales,
          viewEncode(),
          groupData,
          // `assembleAxisSignals` on the **cell**: an axis inside it that draws its grid across no
          // other scale falls back to `width` or `height` by name, and inside the cell those names
          // mean the whole chart until the cell aliases them to its own.
          cellSignals,
          cellLegends,
        )
    if (above.isEmpty()) return inner

    // Outwards, one level at a time. Each level wraps what the level below produced in a cell of
    // its own, and hands that level the facet values it breaks *this* partition down by — which is
    // why the inner grid's own domain dataset lives inside the outer cell rather than beside the
    // chart. The heading and header bands are then this level's, drawn around the whole of it.
    var wrapped = inner
    var below = current
    for ((depth, level) in above.withIndex().reversed()) {
      wrapped =
        // No axes at this level: they were all consumed by the innermost one, which is
        // `mergeChildAxis` moving a child's axes into its parent's bands only one step. So what a
        // level above draws is its heading and its bands of captions, and nothing else.
        level.groups(
          emptyList(),
          emptyList(),
          HEADER_OFFSET,
          config,
          // No size to state: the grid below sizes its own cells, so this band is only keeping
          // room for a caption — `makeHeaderComponent` asks the child for one and gets none.
          "",
          "",
          headings = headingsPerLevel(above + current)[depth],
        ) +
          level.nestedCellGroup(
            // The table this level partitions: the partition the level above it cut, and for the
            // outermost the table standing above the split — which is the whole of the chart's,
            // since the cells' values are the whole table's values and not one cell's.
            above.getOrNull(depth - 1)?.named("facet") ?: reads ?: views.first().mainData,
            // `getCardinalityAggregateForChild` asks the child first: where the child is another
            // grid, what this partition counts is that grid's **columns** and nothing else — the
            // per-cell scale cardinality belongs to the level that owns a cell.
            listOfNotNull(columnsOf(below)).associateWith { it },
            below.layout(
              facetSpacing(spec),
              HEADER_OFFSET,
              config,
              independent.toSet(),
              // The level below keeps room for whatever heading it still writes of its own.
              headings = headingsPerLevel(above + current)[depth + 1],
              childHasSize = below === current,
              insideFacet = true,
            ),
            below.domainDatasets(
              level.named("facet"),
              vertical.isNotEmpty(),
              horizontal.isNotEmpty(),
            ),
            wrapped,
            emptyList(),
            columnsOf(below),
          )
      below = level
    }
    return wrapped
  }

  /**
   * A concatenation's groups wrapped in the grids above them, innermost cell first.
   *
   * The inverse of [marks]'s wrapping, and the same shape: each level partitions the table, hands
   * the level below the facet values it breaks that partition down by, and arranges what came out.
   * What differs is what the innermost cell holds — **plots**, not views — so the concatenation's
   * own layout goes in it, the chains its plots compute per cell go in it, and so do their scales,
   * which are measured over the rows the partition handed the cell and can only be built where
   * those rows are visible.
   */
  private fun cellWrapped(plotGroups: List<VegaValue>): List<VegaValue> {
    var wrapped = plotGroups
    var innerLayout = concat?.layout() ?: VegaValue.EmptyObject
    var innerData = cellGroupData
    var innerScales = cellOwnScales
    var below: FacetLayout? = null
    for ((depth, grid) in cellGrids.withIndex().reversed()) {
      wrapped =
        // No axes at any level: they belong to the plots, which keep their own.
        grid.groups(
          emptyList(),
          emptyList(),
          HEADER_OFFSET,
          config,
          "",
          "",
          headings = headingsPerLevel(cellGrids)[depth],
        ) +
          grid.nestedCellGroup(
            cellGrids.getOrNull(depth - 1)?.named("facet") ?: cellReads ?: "",
            emptyMap(),
            innerLayout,
            innerData,
            wrapped,
            innerScales,
            below?.let { columnsOf(it) },
          )
      // Above the innermost level there is one cell holding one grid: its layout, and no data or
      // scales of its own.
      innerLayout =
        grid.layout(
          facetSpacing(spec),
          HEADER_OFFSET,
          config,
          emptySet(),
          headings = headingsPerLevel(cellGrids)[depth],
          childHasSize = false,
          insideFacet = depth > 0,
        )
      innerData = grid.domainDatasets(grid.named("facet"), vertical = false, horizontal = false)
      innerScales = emptyList()
      below = grid
    }
    return wrapped
  }

  /** The scales a cell holding plots builds inside itself, which is every plot's own. */
  private var cellOwnScales: List<VegaValue> = emptyList()

  /**
   * Says so when the `$schema` names a Vega-Lite version these rules are not.
   *
   * `VegaLiteInput.isVegaLite` accepts any URL with "vega-lite" in it, which is what makes a
   * specification with no `$schema` at all work — most captured payloads have none — but it also
   * meant a version 7 payload would be compiled with version 6 rules without a word about it. The
   * rules here are ported from a **pinned** upstream, `vega-lite@6.4.3`, and every one of the
   * Vega-Lite fixtures is checked against that compiler; a specification declaring another major
   * version is outside what has been verified, and saying so is cheaper for whoever reads the chart
   * than a silent difference in one default.
   *
   * A warning and not a refusal. Across a major version most of a specification still means what it
   * meant, and a host is better served by a chart and a note than by nothing at all — which is the
   * same reasoning as [reportUnsupportedTopLevel]'s.
   */
  private fun reportSchemaVersion() {
    val schema = spec.string("\$schema") ?: return
    val declared = SCHEMA_VERSION_PATTERN.find(schema)?.groupValues?.get(1)?.toIntOrNull() ?: return
    if (declared == VEGA_LITE_MAJOR_VERSION) return
    val direction = if (declared < VEGA_LITE_MAJOR_VERSION) "an older" else "a newer"
    diagnostics.warn(
      VegaLiteDiagnostics.SCHEMA_VERSION,
      "The specification declares Vega-Lite $declared, which is $direction major version than the " +
        "$VEGA_LITE_MAJOR_VERSION these rules implement and are verified against. It is compiled " +
        "with Vega-Lite $VEGA_LITE_MAJOR_VERSION rules, so a default that moved between the two " +
        "will be this version's.",
      jsonPath = "$.\$schema",
    )
  }

  /**
   * Says so when the specification's top level holds a key nothing reads.
   *
   * This was a `= Unit` stub while `Diagnostics.kt` claimed nothing is silently ignored, so an
   * unknown top-level property — a typo, a `selection` block from Vega-Lite 4, a property a later
   * version adds — was dropped without a word. That is the one failure mode this engine is supposed
   * not to have: a chart that draws confidently having quietly discarded part of what it was asked
   * for.
   *
   * A **warning** rather than an error, because the chart that comes out is still the chart the
   * rest of the specification describes, and a host showing diagnostics beside it can say what was
   * left out. The set below is every key something in this module reads; `$schema` is routing
   * metadata `VegaLiteInput` consumes before the compiler sees the specification.
   */
  private fun reportUnsupportedTopLevel() {
    for (key in spec.fields.keys) {
      if (key in TOP_LEVEL_PROPERTIES) continue
      diagnostics.warn(
        VegaLiteDiagnostics.UNSUPPORTED_TOP_LEVEL_PROPERTY,
        "Nothing reads the top-level `$key`, so it has no effect on the chart. Vega-Lite 6's " +
          "top-level properties are: " +
          TOP_LEVEL_PROPERTIES.sorted().joinToString(", ") { "`$it`" } +
          ".",
        jsonPath = "$.$key",
      )
    }
  }

  /**
   * `normalizeAutoSize` and `getTopLevelProperties`, which settle the same property in two places.
   *
   * A chart says nothing about sizing and gets `pad`, which is Vega's own default and so is written
   * as nothing at all. Four things change that.
   *
   * A size of **`"container"`** asks the page for it, so the chart is *fitted* along that direction
   * — and `contains: "padding"` with it, because the element's width includes the padding the chart
   * would otherwise add outside it. A theme may state the sizing its document's charts share, which
   * the chart's own then overrides property by property. An axis whose orientation is driven by a
   * **parameter** needs `resize`, since the drawing is re-laid out when the axis moves from one
   * side to the other and a padded surface would keep the old extent — and that one is added only
   * where nothing else settled the sizing, `getTopLevelProperties` reaching it under `autosize ===
   * undefined`.
   *
   * And a fit is given up where there is nothing to fit. Only a single view or a layer *has* one
   * plotting area to stretch; and a plotting area derived from a **step** per category has a size
   * of its own, so stretching it to the surface would contradict the step. Where one direction is
   * stepped and the other is not, the fit survives along the other — a bar chart as wide as its
   * bars is still fitted vertically.
   */
  private fun autosize(views: List<UnitView>, root: LayoutSize): VegaValue? {
    // `isFitCompatible`: a grid or a row of plots is laid out from its parts, and none of them is
    // the thing a fit would stretch.
    val single = facet == null && concat == null
    val declared = spec.fields["autosize"]
    val stated = normalizedAutoSize(declared)
    // A `"container"` size on a composition is discarded rather than fitted.
    val responsive =
      listOf("width" to "fit-x", "height" to "fit-y").filter {
        single && spec.fields[it.first] == VegaValue.Str("container")
      }
    val fitted =
      when {
        responsive.size == 2 -> "fit"
        responsive.size == 1 -> responsive.single().second
        else -> null
      }
    val merged = obj {
      put("type", "pad")
      if (fitted != null) {
        put("type", fitted)
        put("contains", "padding")
      }
      normalizedAutoSize(config.autosize)?.fields?.forEach { (key, value) -> put(key, value) }
      stated?.fields?.forEach { (key, value) -> put(key, value) }
    }
    // A `"fit"` asks for the whole surface, which only a single view or a layer has one of.
    val normalized =
      if (merged.string("type") == "fit" && !single) {
        obj {
          merged.fields.forEach { (key, value) -> put(key, value) }
          put("type", "pad")
        }
      } else {
        merged
      }
    // Where all of that settles on Vega's own default, `normalize` has nothing to say and leaves
    // the property the specification wrote **unnormalized** — it spreads its answer over the
    // specification only when it has one. So a `"fit"` on a facet reaches the assembly as it was
    // written, having been turned into a `pad` and then forgotten: the warning is the whole of what
    // upstream does about it.
    val settled = if (normalized.fields == PADDED) declared else normalized
    val resize = views.any { view -> Guides.hasSignalOrient(view) }
    val top =
      when {
        settled == null ->
          obj {
            put("type", "pad")
            if (resize) put("resize", VegaValue.Bool(true))
          }
        // Anything but a name or a block is passed through as it stands: `keys` of a number is
        // empty, so there is no type to read and nothing to settle.
        else -> normalizedAutoSize(settled) ?: return settled
      }
    val fitting = droppedFit(top.string("type"), single, root)
    val emitted =
      if (fitting == null) top
      else
        obj {
          top.fields.forEach { (key, value) -> put(key, value) }
          put("type", fitting)
        }
    // Vega's own default is written as nothing; a type on its own is written as the bare string.
    val type = emitted.string("type")
    if (emitted.fields.keys == setOf("type") && !type.isNullOrEmpty()) {
      return if (type == "pad") null else VegaValue.Str(type)
    }
    return emitted
  }

  /** `_normalizeAutoSize`: a name is the block that states only that name. */
  private fun normalizedAutoSize(autosize: VegaValue?): VegaValue.Obj? =
    when (autosize) {
      null -> null
      is VegaValue.Str -> obj { put("type", autosize.value) }
      is VegaValue.Obj -> autosize
      else -> null
    }

  /**
   * The fit a **stepped** plotting area leaves, or null where the stated one stands.
   *
   * A size derived from a step per category is a size the data settles, so a fit along that
   * direction is a contradiction: upstream drops it, and where only one direction is stepped it
   * drops that half of the fit rather than the whole — `getFitType(inverseSizeType)`.
   */
  private fun droppedFit(type: String?, single: Boolean, root: LayoutSize): String? {
    if (type !in setOf("fit", "fit-x", "fit-y")) return null
    // A composition's own layout size is never settled — `parseChildrenLayoutSize` sizes the
    // children and leaves the parent's alone — and the rule asks for both.
    if (!single) return null
    val stepped =
      listOf("x" to "width", "y" to "height").filter { (channel, size) ->
        root.values[channel] == null && spec.fields[size] != VegaValue.Str("container")
      }
    if (stepped.size == 2) return "pad"
    val step = stepped.singleOrNull() ?: return null
    return if (step.first == "x") "fit-y" else "fit-x"
  }

  /**
   * Whether this specification makes a **composition** rather than a unit or a layer.
   *
   * `assembleTitle` reads the two kinds of model differently. A unit or layer anchors its title to
   * the *group* rather than to the whole surface, which keeps it over the plotting area when an
   * axis widens the drawing to its left. A composition cannot: its groups are laid out and there is
   * no one plotting area to sit over, so it takes `anchor: "start"` instead — upstream's note is
   * that a centred title "does not look nice" over a grid.
   */
  private fun isComposition(from: VegaValue.Obj): Boolean {
    val encoding = from.obj("encoding")
    return from.has("facet") ||
      from.has("concat") ||
      from.has("hconcat") ||
      from.has("vconcat") ||
      from.has("repeat") ||
      // A facet channel is a facet written in the encoding, and the model it makes is a facet
      // model — so its title is a composition's, laid out above a grid. All three channels:
      // `facet` wraps one field's values, and a chart that wraps is as composed as one that
      // crosses. Leaving it out framed such a title to a plotting area the chart does not have.
      encoding?.has("row") == true ||
      encoding?.has("column") == true ||
      encoding?.has("facet") == true
  }

  private fun title(): VegaValue? {
    spec.fields["title"]?.let { declared ->
      titleFor(declared, isComposition(spec))?.let {
        return it
      }
    }
    return layerTitle(spec)
  }

  /**
   * `LayerModel.assembleTitle`: "if title does not provide layer, look into children".
   *
   * A layer's members are drawn in one group, so there is no child group for a title written on one
   * of them to sit over — and rather than lose it, upstream promotes it to the chart's own. The
   * first member that has one wins, depth first, and a title on the layer itself outranks all of
   * them. A **concatenation** does not do this: its children have groups of their own, and a title
   * written on one stays there.
   */
  private fun layerTitle(from: VegaValue.Obj): VegaValue? {
    val layers = (from.fields["layer"] as? VegaValue.Arr)?.values ?: return null
    for (member in layers) {
      val child = member as? VegaValue.Obj ?: continue
      child.fields["title"]?.let { declared ->
        titleFor(declared, isComposition(child))?.let {
          return it
        }
      }
      layerTitle(child)?.let {
        return it
      }
    }
    return null
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

  /**
   * `extractTitleConfig(…).nonMarkTitleProperties`: the six a theme writes on the title *directive*
   * rather than into a style block.
   *
   * Every other `config.title` property is paint and becomes the `group-title` style, which is why
   * [Config] keeps these six out of it — and until now nothing put them back, so a theme whose
   * `config.title.anchor` is `"start"` produced a centred title with a group frame instead.
   *
   * `angle` and `limit` come through wherever they are stated, the other four only where they are
   * truthy: upstream spreads them as `...(anchor ? {anchor} : {})` against `...(angle !== undefined
   * ? {angle} : {})`, and an `anchor: ""` is no anchor at all.
   */
  private fun nonMarkTitleProperties(): Map<String, VegaValue> {
    val block = config.raw.obj("title") ?: return emptyMap()
    val properties = LinkedHashMap<String, VegaValue>()
    for (key in listOf("anchor", "frame", "offset", "orient", "angle", "limit")) {
      val value = block.fields[key] ?: continue
      if (key == "angle" || key == "limit" || value.isTruthy()) properties[key] = value
    }
    return properties
  }

  /**
   * `assembleTitle`, for a title on any model: the group frame, or the composition's anchor.
   *
   * Nothing at all where there is no `text`, which is upstream's `if (title.text) { … } return
   * undefined` — a block of title properties with nothing to say is not a title. And `isText`
   * accepts an **array** of strings as readily as one string, a title written over several lines
   * being a list of them.
   */
  private fun titleFor(declared: VegaValue, composed: Boolean): VegaValue? {
    val fields = (declared as? VegaValue.Obj)?.fields
    val text =
      fields?.get("text")
        ?: declared.takeIf {
          it is VegaValue.Str || (it as? VegaValue.Arr)?.values?.firstOrNull() is VegaValue.Str
        }
    // `if (title.text)`, and the empty string is falsy: a title of no words is no title, and
    // writing one out reserved the space above the chart for it. `""` is what a specification
    // written by a tool that always emits the key leaves behind.
    if (text == null || !text.isTruthy()) return null
    // `{...nonMarkTitleProperties, ...titleNoEncoding, ...(encoding ? {encode: …} : {})}`, and in
    // that order: what the title itself states outranks what the theme did.
    val title = LinkedHashMap<String, VegaValue>(nonMarkTitleProperties())
    if (fields == null) title["text"] = text
    // A title's `encoding` is a Vega `encode` block rather than a title property, which is the one
    // key upstream destructures out before it spreads the rest.
    else fields.forEach { (key, value) -> if (key != "encoding") title[key] = value }
    fields?.get("encoding")?.let { title["encode"] = obj { put("update", it) } }
    // The two defaults are applied to the assembled title, after everything it was spread from —
    // so they read a theme's anchor as being as explicit as the title's own, and a `frame` lands
    // last of all, which is where upstream's `??=` puts it.
    if (composed) {
      if (!title.containsKey("anchor")) title["anchor"] = VegaValue.Str("start")
    } else {
      val anchor = (title["anchor"] as? VegaValue.Str)?.value
      if ((anchor == null || anchor == "middle") && !title.containsKey("frame")) {
        title["frame"] = VegaValue.Str("group")
      }
    }
    return VegaValue.Obj(title)
  }

  // -----------------------------------------------------------------------------------------
  // Data

  /**
   * How many **models** name each table, counted over the specification as written.
   *
   * `parseRoot` runs for the root and for every model that states its own `data`, and nowhere else:
   * a layer member with no `data` block of its own reads its parent's flow rather than looking for
   * a source. So this counts the `data` blocks in the tree, and a table two of them name is one
   * `findSource` finds already standing — which [SourceNode.shared] explains is observable.
   *
   * A **`lookup`**'s joined table is not one of them: `LookupNode.make` calls the same `findSource`
   * and only reuses what it finds, so a `transform` is not walked into.
   */
  private fun countStatedTables(node: VegaValue, into: MutableMap<VegaValue, Int>) {
    when (node) {
      is VegaValue.Obj -> {
        node.fields["data"]?.let { into[it] = (into[it] ?: 0) + 1 }
        for ((key, value) in node.fields) {
          if (key == "data" || key == "datasets" || key == "transform") continue
          countStatedTables(value, into)
        }
      }
      is VegaValue.Arr -> node.values.forEach { countStatedTables(it, into) }
      else -> {}
    }
  }

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
    // The **chart's own** table is the first source, whatever the first view reads. `parseData`
    // walks the model tree from the top, and the root model creates its source before any child
    // creates one of its own: a chart whose first layer brings its own rows still numbers the
    // chart's table `source_0`. It is left out where nothing hangs off it, as an unused subtree is.
    spec.fields["data"]?.let { own -> if (views.any { it.spec.data == own }) order += own }
    val roots = LinkedHashMap<VegaValue, SourceNode>()
    // How many **models** name each table, which is how many times `parseRoot` runs on it and
    // therefore how many times it can be *found* already standing. See [SourceNode.shared].
    val statedBy = LinkedHashMap<VegaValue, Int>()
    countStatedTables(spec, statedBy)
    // Which selections are read as a **table** rather than as a test. `materializeSelections`
    // builds one for every selection upstream and lets its ref counting drop the unread ones; the
    // same answer is reached here by asking first, since an output nobody reads still costs a
    // dataset name.
    val materialized =
      views
        .flatMap { it.spec.transforms }
        .mapNotNull { it.obj("from")?.string("param")?.takeIf { _ -> it.has("lookup") } }
        .toSet()
    val lookupOutputs = mutableMapOf<String, OutputNode>()
    // One facet, however many layers are drawn in each of its cells: `facetRoot` is a single node
    // with every child's chain hanging under it, so the layers share the partition rather than
    // each cutting one of their own.
    // `moveFacetDown` walks the facet down past every step that takes it and stops at the first
    // **fork or named output** below it. A cell of several layers is that fork, and the
    // pre-aggregation table a sorted domain asks for is that output; either way the chain stays
    // below and is computed per cell.
    // A grid whose cells are grids always splits: what stands under the outer partition is the
    // inner grid's own named point, which is exactly the "fork or named output" the walk stops at.
    // Per plot: a concatenation may hold a grid beside a plain plot, and each grid decides for
    // itself whether its cells compute their own rows.
    for (plot in allPlots) {
      val grid = plot.facet ?: continue
      // The views that hang **below** the partition, which are the ones the walk counts: a layer
      // that reads a table of its own is a root of its own, so it is no child of the facet at all
      // and cannot be the fork that stops the walk. A cell of three layers, two of them with their
      // own tables, is therefore one child and hoists its chain like a single mark would.
      val below = viewsBelow(plot)
      plot.split =
        grid
          .takeIf {
            below.size > 1 ||
              plot.facets.size > 1 ||
              below.any { view -> DataPipeline.needsRawTable(view) }
          }
          ?.let { FacetNode(it.named("facet")) }
      // The levels above the split, whose partitions the chain passes through on the way down.
      plot.splitAbove =
        if (plot.split == null) emptyList()
        else plot.facets.dropLast(1).map { FacetNode(it.named("facet")) }
      // One node for the grid either way, kept rather than made where it is used: the assembler
      // fills in what it read and where it stood, and the grid's value lists are written there.
      plot.tail = if (plot.split != null) null else FacetNode(grid.named("facet"))
    }
    val split = if (concat == null) allPlots.singleOrNull()?.split else null
    // One partition for the whole cell, however many plots stand in it: they share the cell, so
    // they
    // share the rows it was handed, and each hangs its own chain below the one node.
    cellSplit = cellGrids.lastOrNull()?.let { FacetNode(it.named("facet")) }
    val cellSplitAbove =
      if (cellSplit == null) emptyList()
      else cellGrids.dropLast(1).map { FacetNode(it.named("facet")) }
    // `LookupNode.make`: the joined table is given a **named point** of its own on the source it
    // comes from, and the join names that point rather than the table. Two things follow, and both
    // are the reason it is done unconditionally rather than only where the chart also draws from
    // the table.
    //
    // The point makes the source a fork — the table written out bare and the drawing's own steps
    // deriving from it — so a chart that both joins against a table and draws from it does not join
    // against the drawing's own steps.
    //
    // And the point's *name* carries the model the join was written on, which is what tells two
    // copies of one join apart from two joins. Named for the table it reads instead, three copies
    // of one plot folded into a single node above the fork where upstream keeps one per copy, and
    // the whole chart came out a dataset short.
    val register: (VegaValue, String) -> String = { table, key ->
      if (table !in order) order += table
      lookupOutputs.getOrPut(key) {
        OutputNode(key).also { roots.getOrPut(table) { SourceNode(table) }.then(it) }
      }
      key
    }
    val outputs = views.map { view ->
      val data = view.spec.data!!
      if (data !in order) order += data
      val partitioned = plotOfView(view)?.let { view in viewsBelow(it) } ?: !view.ownsSource
      // `requiresSelectionId(model)` asks the **unit**, not the chart: the identity column is
      // written where a selection that remembers rows by identity was declared, and nowhere else. A
      // layer of two bars, one of them hovered over, is the case — the hovered one needs a
      // `_vgsid_` after its aggregate and the other does not, which is what makes the two of them
      // two datasets rather than one chain with a stray identifier in it.
      DataPipeline(
          view,
          diagnostics,
          register,
          Selection.needsIdentity(selections),
          Selection.needsIdentity(selections.filter { it.owner === view }),
          // `moveFacetDown` hoists a cell's chain above the facet until it meets a named point the
          // scales read. The pre-aggregation table a sorted domain asks for is such a point, and
          // where there is one the chain stays below the facet and a copy of it — with the facet's
          // own fields added to every grouping — is hung beside it for the scales to measure.
          // Only for a view that hangs below the grid: one reading a table of its own has a chain
          // beside the grid rather than under it, so no partition stands anywhere in it. See
          // [viewsBelow].
          facetSplit = if (!partitioned) null else plotOfView(view)?.split ?: cellSplit,
          facetAbove =
            if (!partitioned) emptyList()
            else
              plotOfView(view)
                ?.split
                ?.let { plotOfView(view)?.splitAbove }
                .orEmpty()
                .ifEmpty {
                  if (plotOfView(view)?.split == null) cellSplitAbove else emptyList()
                },
          // Where the flow does not split, the grid is still a node in it, and a node takes a name.
          facetTail = if (!partitioned) null else plotOfView(view)?.tail,
          // Whether the partition is a fork, which is what stops the hoisting — see
          // [DataPipeline.facetForked].
          facetForked = plotOfView(view)?.let { viewsBelow(it).size > 1 } ?: false,
          materialized = materialized,
          lookupOutputs = lookupOutputs,
        )
        // `parseRoot`: a source already standing is **found** rather than made, and finding one is
        // observable — see [SourceNode.shared].
        .build(
          roots
            .getOrPut(data) { SourceNode(data) }
            .also {
              if ((statedBy[data] ?: 0) >= 2) it.sharedAgain()
            }
        )
    }
    // Every view built its own chain onto its source, so a shared tree forks there; the shared
    // parse is hoisted above the fork before the tree is named and flattened.
    // `optimizeDataflow` runs its whole sequence again until nothing moves, at most five times,
    // and that is not belt and braces: one optimizer's fold makes the next one's siblings. Two
    // layers each bucketing an instant and then aggregating are not sibling aggregates until the
    // time units have been folded together, so a single pass leaves the aggregates apart.
    for (root in roots.values) {
      // Before anything moves: the copies of an ancestor's transforms that every branch carries are
      // one node upstream, and an optimizer that lifts one of them past its branch leaves the rest
      // unable to fold at all.
      root.foldAncestorCopies()
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
      // Last, because it asks what each identifier's **parent** is and the folds above are what
      // decide that: `RemoveUnnecessaryIdentifierNodes` is the one optimizer upstream runs after
      // the loop rather than inside it.
      root.pruneIdentifiers()
    }
    val datasets =
      DataAssembler()
        .also { it.named = spec.obj("datasets")?.fields.orEmpty() }
        .assemble(order.map { roots[it] ?: it })
    // The facet's own values stand where the facet does: after the table it reads and before what
    // the scales derive beside it. `assembleFacetData` then gives each cell the chain below.
    cellSplit?.let { own ->
      cellGroupData = DataAssembler().assembleFacetData(own)
      val read = cellSplitAbove.firstOrNull() ?: own
      cellReads = read.data
      cellDomainsAt = read.at
    }
    for (plot in allPlots) {
      val own = plot.split ?: continue
      plot.groupData = DataAssembler().assembleFacetData(own)
      // The **outermost** partition is the one the chart's own walk reached, so it is the one that
      // knows which table it read and where in the list it stood. The inner partitions are inside a
      // cell group, where the chart's numbering does not reach.
      val read = plot.splitAbove.firstOrNull() ?: own
      plot.reads = read.data
      plot.domainsAt = read.at
    }
    // Where nothing splits, the grid's value lists still stand at the point the partition does —
    // `data.push(...node.assemble())` the moment the walk reaches it, and then the walk turns back.
    // Written at the end instead they came after a table a *later* root derived, which is the shape
    // a layer reading a table of its own makes: its chain is walked after the grid's.
    for (plot in allPlots) {
      val tail = plot.tail?.takeIf { it.at >= 0 } ?: continue
      plot.reads = tail.data
      plot.domainsAt = tail.at
    }
    // "now fix the from references in lookup transforms": a join names the *output node* while the
    // flow is being built, because the dataset that node ends up being is not known until the tree
    // has been walked and named.
    val joined =
      if (lookupOutputs.isEmpty()) datasets
      else
        datasets.map { dataset ->
          val steps = dataset.array("transform") ?: return@map dataset
          if (steps.none { lookupOutputs.containsKey(it.string("from").orEmpty()) })
            return@map dataset
          obj {
            (dataset as VegaValue.Obj).fields.forEach { (key, value) ->
              if (key != "transform") put(key, value)
              else
                put(
                  "transform",
                  arr(
                    steps.map { step ->
                      val output =
                        step
                          .takeIf { it.string("type") == "lookup" }
                          ?.let { lookupOutputs[it.string("from")] }
                      if (output == null) step
                      else
                        obj {
                          (step as VegaValue.Obj).fields.forEach { (key, own) ->
                            put(key, if (key == "from") VegaValue.Str(output.source ?: "") else own)
                          }
                        }
                    }
                  ),
                )
            }
          }
        }
    // A chart advanced by a **clock** draws one frame at a time, and the frame is a table of its
    // own: `assembleUnitSelectionData` lifts the selection's filter out of the view's main table
    // and hangs it on a `<name>_curr` beside it. The scales still measure the whole column — an
    // axis that moved with the frame would be a different chart every tick — and only the marks
    // read the frame.
    val animated = selections.firstOrNull { it.isTimer }
    var frames = joined
    if (animated != null) {
      val test = animated.test()
      val source = views.firstOrNull()?.let { it.spec.data }?.let { order.indexOf(it) } ?: 0
      val holds = frames.firstOrNull { dataset ->
        dataset.array("transform").orEmpty().any { it.string("expr") == test }
      }
      if (holds != null) {
        val name = holds.string("name") ?: "source_$source"
        val kept = holds.array("transform").orEmpty().filter { it.string("expr") != test }
        frames =
          frames.map { dataset ->
            if (dataset !== holds) dataset
            else
              obj {
                (dataset as VegaValue.Obj).fields.forEach { (key, value) ->
                  if (key != "transform") put(key, value) else put("transform", arr(kept))
                }
              }
          } +
            obj {
              put("name", "${name}_curr")
              put("source", name)
              put(
                "transform",
                arr(
                  listOf(
                    obj {
                      put("type", "filter")
                      put("expr", test)
                    }
                  )
                ),
              )
            }
        views.forEach { it.markData = "${name}_curr" }
      }
    }
    views.forEachIndexed { index, view ->
      // The **scales** read one named point and the marks another, where the specification asked
      // for a path that breaks at a gap the domain does not want. They are the same node
      // otherwise, and `markData` then stays whatever it already was — inside a facet it is the
      // cell's own partition, which is not this question.
      val scales = outputs[index].scales
      val main = outputs[index].main
      view.mainData = scales.source ?: ""
      if (scales !== main) view.markData = main.source ?: ""
      // Inside a facet the marks read the cell's own chain, which is below the split — and so does
      // a scale the *cell* owns, whose extent is measured over the rows that cell was handed.
      outputs[index].cell?.source?.let {
        view.markData = it
        cellDataFor[view.mainData] = it
      }
      view.rawData = outputs[index].raw?.source ?: view.mainData
    }
    return frames
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
            // Reported **here** and not from `findIncompatibleScales`, which asks the same question
            // earlier to see whether two views can share a scale: a refused type is one fact about
            // the specification, and saying it twice would be a report of two.
            diagnostics = diagnostics,
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
        // A domain the specification **states** is explicit, and an explicit value settles a
        // merged property outright — `mergeValuesWithExplicit`. A layer whose colours are listed
        // once, on one of its members, is that list: unioned with the other member's derived domain
        // it became the list *and* whatever the data happened to hold.
        val explicit = def.scale?.fields?.get("domain") is VegaValue.Arr
        if (explicit && !component.explicitDomain) {
          component.explicitDomain = true
          component.domains.clear()
        }
        if (explicit || !component.explicitDomain) {
          for (domain in domains) if (domain !in component.domains) component.domains += domain
        }
        // `parseNonUnitScaleProperty` merges a shared scale **property by property**, not layer by
        // layer: the first layer to settle a property settles it, and the ones that say nothing
        // about it are passed over rather than ending the search. A candlestick's rules come first
        // and have no width to speak of, so the bar's `padding` is the only one anybody states.
        val contributed = ScaleComponent(channel, component.type, component.name())
        Scales.range(view, channel, def, component.type)?.let { contributed.set("range", it) }
        Scales.properties(view, channel, def, component.type, contributed)
        // Which of them this view **stated**. A range is explicit where any of the four words that
        // settle one was written: `parseScaleRange` records the whole property as explicit for
        // `scheme` and the two ends as much as for `range` itself.
        val statedKeys = def.scale?.fields?.keys.orEmpty()
        val stated =
          statedKeys +
            if (statedKeys.any { it in setOf("range", "scheme", "rangeMin", "rangeMax") })
              setOf("range")
            else emptySet()
        contributed.properties.forEach { (key, value) ->
          val explicitHere = key in stated
          // `mergeValuesWithExplicit`: an explicit value beats a derived one whichever layer it
          // arrives on, and between two of the same kind the first still wins.
          if (explicitHere && key !in component.explicitProperties) {
            component.properties[key] = value
            component.explicitProperties += key
          } else if (key !in component.properties) component.properties[key] = value
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

  /**
   * `mergeDomains` in `compile/scale/domain.ts`: one domain passes through, several become a
   * `fields` union, and the *sort* is settled here rather than by each contributor.
   *
   * Settling it here is the point. Every view a scale is shared by contributes a domain carrying
   * the sort its own encoding asked for, and Vega takes one sort for the whole scale: sorting the
   * parts separately and concatenating them is a different answer from sorting the union. So the
   * contributors' sorts are collected, the ones a union cannot express are given up, and what
   * survives is written once — never inside a part.
   */
  private fun domainValue(component: ScaleComponent): VegaValue? {
    val domains = component.domains
    // Two views may name the same column and disagree only about the order: that is one domain
    // with two sorts, not two domains, and the sort is what the disagreement is settled over.
    val uniqueDomains = domains.map { withoutSort(it) }.distinct()
    val sorts = domains.mapNotNull { normalizedSort(it) }.distinct()
    if (uniqueDomains.isEmpty()) return null

    if (uniqueDomains.size == 1) {
      val only = domains.first()
      if (!isDataRef(only) || sorts.isEmpty()) return only
      return obj {
        (only as VegaValue.Obj).fields.forEach { (key, value) ->
          if (key != "sort") put(key, value)
        }
        put("sort", settledSort(sorts, only.string("field")))
      }
    }

    // A union sorts by an aggregate Vega can compute *across* datasets, which is only the three
    // that need no more than one pass over each: a `sum` would have to be re-totalled over the
    // whole union and there is nothing to re-total it from, so the request is given up and the
    // domain sorts naturally.
    val unionSorts =
      sorts
        .map { sort ->
          if (sort !is VegaValue.Obj || !sort.has("op")) sort
          else if (sort.string("op") in UNION_DOMAIN_SORT_OPS) sort else VegaValue.Bool(true)
        }
        .distinct()
    // Sorts that disagree cannot be reconciled either, and the natural order is the answer that
    // does not privilege one of them.
    val sort =
      when {
        unionSorts.size == 1 -> unionSorts.single()
        unionSorts.size > 1 -> VegaValue.Bool(true)
        else -> null
      }

    // Several fields of one dataset collapse further, into one reference with a field list.
    val data = domains.map { if (isDataRef(it)) it.string("data") else null }.distinct()
    return if (data.size == 1 && data.single() != null) {
      obj {
        put("data", data.single())
        put("fields", strings(uniqueDomains.map { it.string("field")!! }))
        put("sort", sort)
      }
    } else {
      obj {
        put("fields", arr(uniqueDomains))
        put("sort", sort)
      }
    }
  }

  /**
   * The sort a single domain settles on, of the [sorts] its contributors asked for.
   *
   * One sort is taken as it stands, but for two the op is what tells them apart: a `min` is the
   * default a plain `"descending"` expands into, so a single non-`min` request among them is a
   * choice somebody made and the others are defaults it outranks. Anything less clear-cut than that
   * is left to the natural order.
   */
  private fun settledSort(sorts: List<VegaValue>, domainField: String?): VegaValue {
    if (sorts.size > 1) {
      val stated = sorts.filter { it is VegaValue.Obj && it.has("op") && it.string("op") != "min" }
      val allAggregate = sorts.all { it is VegaValue.Obj && it.has("op") }
      return if (allAggregate && stated.size == 1) stated.single() else VegaValue.Bool(true)
    }
    val sort = sorts.single()
    // Sorting a domain by its own column is the natural order with at most a direction to it: the
    // aggregate picks one value of a column out of the rows that all carry the same one.
    if (sort is VegaValue.Obj && sort.has("field") && sort.string("field") == domainField) {
      val order = sort.string("order") ?: return VegaValue.Bool(true)
      return obj { put("order", order) }
    }
    return sort
  }

  /**
   * A contributor's sort as it counts towards the merge, or null where it asked for none.
   *
   * A `count` counts rows rather than values, so the field it was written beside says nothing, and
   * `ascending` is the order a sort has anyway. Both are dropped before the sorts are compared, so
   * that two contributors spelling the same request differently are seen to agree.
   */
  private fun normalizedSort(domain: VegaValue): VegaValue? {
    val sort = (domain as? VegaValue.Obj)?.get("sort") ?: return null
    val stated = sort as? VegaValue.Obj ?: return sort
    val dropped =
      setOfNotNull(
        "field".takeIf { stated.string("op") == "count" },
        "order".takeIf { stated.string("order") == "ascending" },
      )
    if (dropped.isEmpty()) return stated
    return obj { stated.fields.forEach { (key, value) -> if (key !in dropped) put(key, value) } }
  }

  /** The domain without its sort, which is what makes two contributors the same domain. */
  private fun withoutSort(domain: VegaValue): VegaValue {
    if (!isDataRef(domain)) return domain
    return obj {
      (domain as VegaValue.Obj).fields.forEach { (key, value) ->
        if (key != "sort") put(key, value)
      }
    }
  }

  /** `isDataRefDomain`: a reference to a column of a dataset, rather than a list or a signal. */
  private fun isDataRef(domain: VegaValue): Boolean =
    domain is VegaValue.Obj && domain.has("field") && domain.has("data")

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
          if (concat == null && guideIsIndependent(channel)) "${independenceOwner(view)}|$channel"
          else component.name()
        val existing = components[key]
        if (existing == null) {
          parsed.explicitProperties += def.axis?.fields?.keys.orEmpty()
          components[key] = channel to parsed
        } else {
          val merged = existing.second
          // An axis one layer switched off is switched off for the scale, whichever side of the
          // merge it arrives on: `mergeValuesWithExplicit` keeps the explicit `disable` and there
          // is no tie-breaker that could put it back.
          if (parsed.disabled) merged.disabled = true
          if (parsed.explicitGrid) merged.explicitGrid = true
          // A layer stating that the axis has no caption says so for the axis, whichever side of
          // the merge it arrives on — `mergeTitleComponent` answers `null` for either side being
          // it, whatever the other side says.
          if (parsed.nulledTitle) merged.nulledTitle = true
          when {
            // An explicit title wins outright rather than joining: a layer that names its axis has
            // said what the axis measures, and the other layer's derived name adds nothing.
            parsed.explicitTitle && !merged.explicitTitle -> {
              merged.titles.clear()
              merged.titleKeys.clear()
              merged.titles += parsed.titles
              merged.titleKeys += parsed.titleKeys
              merged.explicitTitle = true
            }
            merged.explicitTitle && !parsed.explicitTitle -> Unit
            else ->
              parsed.titleKeys.forEachIndexed { at, key ->
                merged.addTitle(key, parsed.titles[at])
              }
          }
          // `mergeValuesWithExplicit`: a property one layer settled and the other left alone is
          // the merged axis's. A layer of an error band over a line is where it tells — the band
          // lifts its bucketing out into a transform, so its own axis says nothing about buckets,
          // while the line's still asks for a `%Y` format and a tick step a year wide. Taking the
          // first layer's answer for everything dropped both.
          //
          // And a property the specification **stated** beats one this compiler derived, whichever
          // layer states it. Filling only the gaps meant a layer writing `"axis": {"grid": false}`
          // lost to an earlier layer that never mentioned gridlines — a quantitative position has
          // them by default, so the earlier layer's silence became a decision.
          val statedHere = def.axis?.fields?.keys.orEmpty()
          parsed.properties.forEach { (name, value) ->
            if (name in statedHere && name !in merged.explicitProperties) {
              merged.override(name, value)
              merged.explicitProperties += name
            } else merged.set(name, value)
          }
        }
      }
    }
    // `if (disable) return axisComponent`, read on the way out: the component took part in the
    // merge and has nothing to assemble.
    components.entries.removeAll { it.value.second.disabled }
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

  /**
   * Whether an axis is drawn **inside** each cell rather than once in a band beside the grid.
   *
   * A band can only label a scale every cell shares, so a channel resolved independently keeps its
   * axis in the cell — and `parseGuideResolve` settles that for the *guide*, which an independent
   * scale forces but an `axis: "independent"` asks for on its own.
   */
  private fun cellOwnsAxis(axis: VegaValue, ofFacet: Boolean = concat == null): Boolean =
    setOf("x", "y").any { channel ->
      guideIsIndependent(channel, ofFacet) && axis.string("scale")?.endsWith(channel) == true
    }

  private fun guideIsIndependent(channel: String, ofFacet: Boolean = concat == null): Boolean =
    resolve.guideIsIndependent(
      channel,
      resolve.scaleIsIndependent(
        channel,
        // `defaultScaleResolve` answers for the composition being asked about. A facet **inside** a
        // concatenation is asked about its own cells, which share everything but `theta`; the
        // concatenation's plots are what measure their positions separately.
        defaultIndependent =
          if (!ofFacet && concat != null)
            channel in Channels.POSITION_SCALE_CHANNELS || channel == "theta"
          else channel == "theta",
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
      // `if (index > 0 && !!axisCmpt.get('grid') && !axisCmpt.explicit.grid)` — only a **derived**
      // grid is taken away. Two layers that each write `"axis": {"grid": true}` have each asked for
      // gridlines and get them, however busy that reads; two that merely happen to have them,
      // because a quantitative position has them by default or a theme turned them on, get one set
      // between them. Taking them off regardless left a dual-axis chart with one layer's gridlines
      // where its specification had asked for three.
      for ((index, axis) in onChannel.withIndex()) {
        if (
          index > 0 &&
            (axis.properties["grid"] as? VegaValue.Bool)?.value == true &&
            !axis.explicitGrid
        ) {
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
    /** Which plot a legend belongs to, where the composition resolves that legend per plot. */
    plotOf: MutableMap<String, String> = mutableMapOf(),
    /** Which channel each legend explains, which is what a guide's resolution is asked about. */
    channelOf: MutableMap<String, String> = mutableMapOf(),
    /** The `resolve` of the composition each view sits in, which may not be the chart's own. */
    resolveOf: Map<UnitView, Resolve> = emptyMap(),
  ): LinkedHashMap<String, VegaValue> {
    val legends = LinkedHashMap<String, LinkedHashMap<String, VegaValue>>()
    val explicitlyTitled = mutableSetOf<String>()
    /** Whether each key's merged legend is switched off, and whether that was stated. */
    val disableOf = LinkedHashMap<String, Pair<Boolean, Boolean>>()
    for (view in views) {
      for (channel in Channels.LEGEND_CHANNELS) {
        // The same definition the *scale* was built from, which for a channel written entirely as
        // a condition is the condition's own: a colour that only exists once a row is picked still
        // needs a key saying what its colours mean.
        val def = view.spec.encoding[channel]?.let { view.scaledDef(it) } ?: continue
        val component = view.scaleComponents[channel] ?: continue
        // Keyed by the **field**, not by the scale — `assembleLegends` groups by
        // `field:<name>`. One field encoded twice, as a colour *and* as a size, is one key to the
        // reader and one legend whose swatches carry both; keying by the scale gave it two, side by
        // side, saying the same thing. The scale's own prefix stays in the key so that a
        // composition resolving its legends independently still gets one per plot, and the
        // discreteness with it, since a ramp and a set of swatches cannot be the same legend.
        val prefix = component.name().removeSuffix(channel)
        val discrete = if (Scales.hasDiscreteDomain(component.type)) "d" else "c"
        // `getFieldKeyForChannel` asks the **children**: where they all name one column the key is
        // that column, and where they disagree it is the *channel*. Two layers colouring by
        // different columns on one shared scale are one key to the reader — a heat lane's two bars
        // both explain "count" — and keyed by their own fields they came out as two keys side by
        // side saying the same thing.
        val agreed =
          views
            .filter { it.scaleComponents[channel]?.name() == component.name() }
            .mapNotNull { it.scaledDef(it.spec.encoding[channel] ?: return@mapNotNull null)?.field }
            .distinct()
        val fieldKey = if (agreed.size == 1) agreed.single() else "channel:$channel"
        // A legend the composition resolves per child is keyed by that child as well, since the
        // whole point of resolving it independently is that there is one of it per plot.
        // A legend the composition resolves per child is keyed by that child as well, since the
        // whole point of resolving it independently is that there is one of it per child. Which
        // child depends on the composition: a concatenation's are its plots, and each keeps its
        // key inside its own group; a **layer**'s are its members, and they have no group to keep
        // it in, so two keys stand side by side beside the chart saying two different things.
        // Two levels can each ask for a key of their own, and they mean different children. The
        // chart's `resolve` speaks about the *concatenation* — one key per plot — and a `resolve`
        // written on a plot speaks about the layers inside it. Both put the key in the plot's own
        // group; only the second splits one plot's layers into two keys.
        val byPlot =
          concat != null && resolve.guideIsIndependent(channel, scaleIsIndependent = false)
        val byLayer =
          resolveOf[view]?.guideIsIndependent(channel, scaleIsIndependent = false) == true
        val plotName = plotNames[view].orEmpty()
        val ownPlot = plotName.takeIf { (byPlot || byLayer) && concat != null }
        val ownChild =
          when {
            byLayer -> "$plotName|${view.childName}"
            byPlot -> plotName
            else -> ""
          }
        val key = "$prefix|${def.field?.let { fieldKey } ?: channel}|$discrete|$ownChild"
        // A **disabled** legend is still a component, and its disable is folded into the merged
        // one: a layer writing `"legend": null` takes the whole key away rather than only its own
        // share of it. See [Guides.legendDisable] for which way round the explicitness goes.
        val (disabled, explicitDisable) = Guides.legendDisable(view, def)
        val standing = disableOf[key]
        if (standing == null) {
          disableOf[key] = disabled to explicitDisable
        } else if (!standing.second && explicitDisable) {
          disableOf[key] = disabled to true
        }
        // A disabled legend is still a **component**, and a component carries its scale from the
        // moment it is made: `new LegendComponent({}, getLegendDefWithScale(model, channel))` runs
        // before the disable is read. So a channel switched off still tells the legend it merges
        // into which scale draws its swatches — a chart telling its lines apart by colour and by
        // dash pattern keeps one key, and that key shows both dashes and colours however the
        // dashes' own legend was refused.
        if (disabled) {
          val entry = legends.getOrPut(key) { LinkedHashMap() }
          if (key !in scaleOf) {
            scaleOf[key] = component.name()
            channelOf[key] = channel
            ownPlot?.let { plotOf[key] = it }
          }
          // `putIfAbsent` is a JVM extension, and this file is compiled for five targets.
          val scaled = Guides.legendScaleChannel(view, channel)
          if (scaled !in entry) entry[scaled] = str(component.name())
          continue
        }
        val built = Guides.legend(view, channel, def, component.type) as? VegaValue.Obj ?: continue
        // `mergeValuesWithExplicit` settles a property before any tie-breaker runs: a value the
        // specification stated beats one this compiler derived. A field encoded as both a colour
        // and a size, with a title written on only one of them, is titled by the one that was
        // written — not by the two joined with a comma.
        val titled = def.legend?.fields?.containsKey("title") == true || def.explicitTitle != null
        val existing = legends[key]
        if (existing == null) {
          legends[key] = LinkedHashMap(built.fields)
          scaleOf[key] = component.name()
          channelOf[key] = channel
          ownPlot?.let { plotOf[key] = it }
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
      // `if (disable) return undefined` — `assembleLegend` answers nothing for a disabled
      // component, and the merge is what decided it: a key another layer supplied every property of
      // still goes where one layer said it was not to be drawn.
      if (disableOf[name]?.first == true) return@forEach
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
    // `if (!legend.title) delete legend.title` — "title schema doesn't include null, ''". Any
    // **falsy** caption, so `"legend": {"title": ""}` takes a key's caption off exactly as
    // `null` does; a key captioned with the empty string is a key with no caption, and writing one
    // out reserved the space for it.
    //
    // It has to happen *here*, after the merge, and not where a title is read. The caption is what
    // `mergeValuesWithExplicit` settled between the layers, and a stated `null` is what wins that:
    // one layer naming its colour `null` and another leaving it derived is one uncaptioned key.
    // Dropping it earlier takes the key away and the merge then fills it in from the other layer.
    if (!fields["title"].isTruthy()) fields.remove("title")
    // `const {disable, labelExpr, selections, ...legend} = legendCmpt.combine()`, and then:
    //
    //     if (labelExpr !== undefined) {
    //       let expr = labelExpr;
    //       if (legend.encode?.labels?.update && isSignalRef(legend.encode.labels.update.text)) {
    //         expr = util.replaceAll(labelExpr, 'datum.label',
    // legend.encode.labels.update.text.signal);
    //       }
    //       …
    //     }
    //
    // After the merge, so every layer's encode is in hand: the expression composes with a text the
    // encode already states rather than replacing whatever was there.
    (fields.remove("labelExpr") as? VegaValue.Str)?.let { expression ->
      fields["encode"] = Guides.withLabelText(fields["encode"], expression.value)
    }
    val symbols = fields["encode"]?.get("symbols")?.get("update") as? VegaValue.Obj ?: return
    val remaining =
      symbols.fields.filterKeys { it !in Channels.LEGEND_SCALE_CHANNELS || !fields.containsKey(it) }
    if (remaining.size == symbols.fields.size) return
    // Upstream deletes from the swatch's own update **in place** — `delete out[property]` — so
    // every other part of the encode is untouched. Rebuilding the whole `encode` from the swatch
    // discarded the rest of it: a legend whose labels carry an expression lost them the moment a
    // scale channel was dropped from its swatch.
    val whole = fields["encode"] as? VegaValue.Obj
    val swatches = (whole?.fields?.get("symbols") as? VegaValue.Obj)?.fields.orEmpty()
    fields["encode"] = obj {
      whole?.fields?.forEach { (key, value) -> if (key != "symbols") put(key, value) }
      put(
        "symbols",
        obj {
          swatches.forEach { (key, value) -> if (key != "update") put(key, value) }
          put("update", obj { remaining.forEach { (key, value) -> put(key, value) } })
        },
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

  private companion object {
    /**
     * Vega-Lite 6's top-level properties, and the one metadata key that is not one.
     *
     * Every name here is read by something in this module — the grep is the point of the list,
     * since a property nobody reads belongs in a diagnostic rather than in a set of known ones.
     * `$schema` is the exception and is not read at all: `VegaLiteInput` routes on it before the
     * compiler is handed the specification.
     */
    /** The Vega-Lite major version these rules implement, and the fixtures are checked against. */
    const val VEGA_LITE_MAJOR_VERSION = 6

    /**
     * `{type: 'pad'}` — the sizing Vega does anyway, which upstream compares against by value.
     *
     * `deepEqual(autosize, {type: 'pad'})` is how `normalizeAutoSize` decides it has nothing to
     * say: a chart that asks to be padded and nothing else has asked for the default.
     */
    val PADDED = mapOf("type" to VegaValue.Str("pad"))

    /** `https://vega.github.io/schema/vega-lite/v6.json` — the major version out of the URL. */
    val SCHEMA_VERSION_PATTERN = Regex("""vega-lite/v(\d+)""")

    /**
     * `MULTIDOMAIN_SORT_OP_INDEX` — the aggregates a **unioned** domain can be sorted by.
     *
     * Each of the three is a running answer: a union's count is the counts added up, its minimum
     * the smallest of the minima. An op that has to see all the rows at once — a `sum` per
     * category, a `mean` — has no such answer once the categories come from several datasets.
     */
    val UNION_DOMAIN_SORT_OPS = setOf("count", "min", "max")

    val TOP_LEVEL_PROPERTIES =
      setOf(
        "\$schema",
        "align",
        "autosize",
        "background",
        "bounds",
        "center",
        "columns",
        "concat",
        "config",
        "data",
        "datasets",
        "description",
        "encoding",
        "facet",
        "hconcat",
        "height",
        "layer",
        "mark",
        "name",
        "padding",
        "params",
        "projection",
        "repeat",
        "resolve",
        "spacing",
        "spec",
        "title",
        "transform",
        "usermeta",
        "vconcat",
        "view",
        "width",
      )
  }
}
