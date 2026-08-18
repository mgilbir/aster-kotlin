package dev.aster.vegalite

import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue

/**
 * Several plots standing beside one another, and the grid that places them.
 *
 * `hconcat` is a row, `vconcat` a column, and `concat` is whichever `columns` says — the three are
 * one construct in upstream's compiler (`ConcatModel`), which is why they are one here. What makes
 * a concatenation different from a chart with more marks in it is that each plot keeps its own
 * position scales and its own axes: two plots side by side measure separate things, and sharing a
 * `y` between them would say they measure the same one. Everything a legend stands for *is* shared,
 * so one colour key covers the whole chart.
 *
 * The children inherit the chart's data and transforms, as every model in upstream's hierarchy
 * does, so the ordinary case — one dataset drawn several ways — needs saying only once.
 */
internal class Concat
private constructor(
  /** Each plot and the name it is compiled under, which a *repetition* supplies for its copies. */
  val children: List<Child>,
  /**
   * How many plots to a row: 1 for a column, null for a row, and whatever `columns` says otherwise.
   *
   * This is also what decides which sizes can merge — `parseConcatLayoutSize` — because a column of
   * plots shares a width and a row shares a height.
   */
  val columns: Int?,
  private val spacing: Double,
  private val declared: VegaValue.Obj,
) {

  /**
   * `assembleDefaultLayout` in `compile/concat.ts`, under the spacing `assembleLayout` supplies.
   *
   * `align: "each"` rather than the grid's usual `all`, in upstream's own words "so it can work
   * with multiple plots with different size" — a row of plots of unequal height is the normal case,
   * and aligning their rows would leave gaps between them.
   */
  fun layout(): VegaValue = obj {
    put("padding", spacing)
    columns?.let { put("columns", it) }
    put("bounds", "full")
    put("align", "each")
    for (key in listOf("align", "bounds", "center", "spacing")) {
      declared.fields[key]?.let { if (key != "spacing") put(key, it) }
    }
  }

  /**
   * One plot of the concatenation.
   *
   * [nestedFacets] is why this is a class rather than a pair: a plot may be a grid whose cells are
   * grids, and the levels inside the outermost one have to reach the plot that owns them. They are
   * peeled here, where the plot's specification is normalised, and lifted per plot later.
   */
  class Child(
    /** The name the plot is compiled under, where it states one — a repetition names its copies. */
    val name: String?,
    val spec: VegaValue.Obj,
    val nestedFacets: List<VegaValue.Obj> = emptyList(),
  )

  companion object {
    /** `config.concat.spacing`. */
    private const val DEFAULT_SPACING = 20.0

    fun of(spec: VegaValue.Obj, diagnostics: DiagnosticCollector, config: Config? = null): Concat? {
      val kind = listOf("hconcat", "vconcat", "concat").firstOrNull { spec.has(it) } ?: return null
      val entries = spec.array(kind).orEmpty()
      if (entries.isEmpty()) {
        diagnostics.fatal(
          VegaLiteDiagnostics.UNSUPPORTED_COMPOSITION,
          "`$kind` is empty, so there is nothing to draw.",
          jsonPath = "$.$kind",
        )
        return null
      }
      val children = mutableListOf<Child>()
      entries.forEachIndexed { index, entry ->
        val declared = entry as? VegaValue.Obj ?: return@forEachIndexed
        // A **repetition** inside a concatenation is a concatenation of its copies, exactly as one
        // at the top of a chart is: normalising it here rather than refusing it lets the ordinary
        // nesting take it, a concatenation's plot being allowed to be a concatenation.
        val repeated =
          if (!declared.has("repeat")) declared
          else Repeat.normalize(declared, diagnostics) ?: return null
        // A **facet** operator is the same chart as its two channels written in the encoding, here
        // as much as at the top: the grid it lays out is then this plot's rather than the chart's.
        // A grid whose cells are grids peels into a level per plot, lifted where that plot is.
        val peeled =
          if (!repeated.has("facet")) FacetOperator.Peeled(repeated, emptyList())
          else FacetOperator.normalize(repeated, diagnostics) ?: return null
        val child = peeled.spec
        for (nested in listOf("repeat", "facet")) {
          if (child.has(nested)) {
            diagnostics.fatal(
              VegaLiteDiagnostics.UNSUPPORTED_COMPOSITION,
              "A `$nested` inside a `$kind` is not implemented; a concatenation's plots are " +
                "single views, layers of them, or concatenations.",
              jsonPath = "$.$kind[$index].$nested",
            )
            return null
          }
        }
        children +=
          Child(
            child.string("name"),
            obj {
              put("data", spec.fields["data"])
              putAll(child)
              // A plot's own transforms come **after** the concatenation's rather than instead of
              // them: the concatenation's belong to its own data chain and each plot's hangs below.
              // Letting a plot's replace them ran its filter over a column the shared formula had
              // not yet written.
              // A plot that reads its own table inherits none of the concatenation's transforms:
              // `parseData` starts a new source for it rather than descending from the level above,
              // so that chain is not over it at all.
              val inherited =
                if (child.has("data")) child.array("transform").orEmpty()
                else spec.array("transform").orEmpty() + child.array("transform").orEmpty()
              put("transform", if (inherited.isEmpty()) null else arr(inherited))
            },
            peeled.inner,
          )
      }
      val columns =
        when (kind) {
          "vconcat" -> 1
          "hconcat" -> null
          else -> spec.number("columns")?.toInt()
        }
      return Concat(
        children,
        columns,
        // `config.concat.spacing` settles it for every concatenation in the chart, which is how a
        // mosaic pulls its two bands together without saying so on each of them.
        spec.number("spacing") ?: config?.raw?.obj("concat")?.number("spacing") ?: DEFAULT_SPACING,
        spec,
      )
    }
  }
}
