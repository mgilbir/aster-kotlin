package dev.aster.vegalite

import dev.aster.vega.model.Decimals
import dev.aster.vega.model.VegaValue

/**
 * How large the plotting area is, and whether that is a number or a computation.
 *
 * This is the rule that makes a Vega-Lite bar chart come out the size of its data: a discrete
 * position has no width of its own, so the chart is *derived* from a step per category, and the
 * width becomes a signal rather than a constant. A continuous position takes the configured 300.
 * `bandspace` is Vega's own count of how many steps a padded band scale needs.
 */
internal class LayoutSize(
  views: List<UnitView>,
  scales: Map<String, ScaleComponent>,
  config: Config,
  spec: VegaValue.Obj,
  /**
   * What this plot's two size signals are called.
   *
   * `width` and `height` for a plain chart, `child_width`/`child_height` inside a facet — where
   * `width` is the whole grid and this sizes one cell of it — and inside a concatenation whatever
   * the sizes merged into. A signal named `width` or `height` that holds a plain *number* is
   * hoisted to a top-level property instead of being written out, which is upstream's own last step
   * in `assembleTopLevelModel`; anything else stays a signal, because there is no number to write.
   */
  private val names: Map<String, String> = mapOf("x" to "width", "y" to "height"),
  /**
   * What this plot's own scales are called, where a concatenation has renamed them.
   *
   * A step-derived width counts the categories in its *own* band scale, so the expression has to
   * name `concat_1_x` rather than `x`, or every plot in a row comes out the width of the first.
   */
  private val scalePrefix: String = "",
  /**
   * The column each cell counts its own categories in, per channel — `distinct_age`.
   *
   * Set only where a **facet** resolves that channel independently and the cell's scale is discrete
   * with a step range: the cell's width is then not a number the whole grid shares but an
   * expression over the cell's own row, `bandspace(datum["distinct_age"], …) * child_x_step`. There
   * is no size signal at all in that case — every reader of the size reads the expression.
   */
  private val cardinality: Map<String, String> = emptyMap(),
) {
  val signals: List<VegaValue>
  val width: VegaValue?
  val height: VegaValue?

  /**
   * What each channel's size came out as, or null where it is derived from a step.
   *
   * A concatenation merges its plots' sizes into one signal only when they agree and none of them
   * is a step — `parseNonUnitLayoutSizeForChannel` abandons the merge on either count — so this is
   * what there is to compare.
   */
  val values: Map<String, VegaValue?>

  /** The expression a cell's own size is, where the grid has no one size to share. */
  val expressions: MutableMap<String, String> = mutableMapOf()

  init {
    val emitted = mutableListOf<VegaValue>()
    val sizes = LinkedHashMap<String, VegaValue?>()
    var widthValue: VegaValue? = null
    var heightValue: VegaValue? = null

    for (channel in listOf("x", "y")) {
      val sizeName = names.getValue(channel)
      val declared = declaredSize(views, spec, channel)
      val scale = scales[channel]
      // The step signal is named after the **scale**, which inside a facet that resolves the
      // channel independently is the cell's own — `child_x_step`, not `x_step`.
      val scaleName = scale?.name() ?: "$scalePrefix$channel"
      val discrete = scale != null && (scale.type == "band" || scale.type == "point")
      val step = (declared as? VegaValue.Obj)?.number("step")
      // `{"step": 50, "for": "position"}` — the step belongs to the *outer* band, not to one mark
      // inside it. `getPositionStep` reads the `for` and hands the step straight to the position,
      // so the nested arithmetic below is skipped and the offset scale divides whatever band the
      // step produced.
      val stepForPosition = (declared as? VegaValue.Obj)?.string("for") == "position"

      // `"container"` is a size the *page* settles: the signal reads the element it is drawn in and
      // follows it as the window changes, with the view's own default where there is nothing to
      // measure — a chart rendered outside a browser still has to have a width.
      if (declared == VegaValue.Str("container")) {
        val measured = if (channel == "x") "containerSize()[0]" else "containerSize()[1]"
        val fallback =
          number(if (channel == "x") config.continuousWidth else config.continuousHeight)
        val expression = "isFinite($measured) ? $measured : $fallback"
        emitted += obj {
          put("name", sizeName)
          put("init", expression)
          put(
            "on",
            arr(
              listOf(
                obj {
                  put("events", "window:resize")
                  put("update", expression)
                }
              )
            ),
          )
        }
        sizes[channel] = null
        continue
      }

      // `getViewConfigDiscreteSize` answers a **number** where the theme states one and `{step: …}`
      // only otherwise, so a themed discrete size replaces the step arithmetic entirely: every
      // strip in the document is that deep, however many categories it holds.
      val themedDiscrete = if (channel == "x") config.discreteWidth else config.discreteHeight
      val value: VegaValue? =
        when {
          !discrete || declared.isStatedSize() -> value(views, scales, config, spec, channel)
          declared == null && themedDiscrete != null -> num(themedDiscrete)
          else -> {
            val padding = (scale.properties["padding"] as? VegaValue.Num)?.value
            // Only a *band* scale has a real inner padding. A **point** scale counts as 1, because
            // n points have n−1 steps between them — upstream's `sizeExpr` says so in those words,
            // citing vega-scale's own band arithmetic. With 0 instead, a chart on a point scale
            // comes out a whole step too wide.
            val paddingInner =
              if (scale.type == "band") {
                (scale.properties["paddingInner"] as? VegaValue.Num)?.value ?: padding ?: 0.0
              } else {
                1.0
              }
            val paddingOuter =
              (scale.properties["paddingOuter"] as? VegaValue.Num)?.value ?: padding ?: 0.0
            // With marks nested inside the band, the step is no longer one mark wide: it has to
            // hold as many as the inner scale has, and is then divided by what the outer padding
            // takes away. That arithmetic is the whole width of a grouped bar chart.
            // Only a **discrete** offset divides the band into lanes; a continuous one — a jitter
            // — has no lanes to count, so the step is the band's own. `getStepFor` again.
            val offset =
              scales[if (channel == "x") "xOffset" else "yOffset"]?.takeIf {
                it.type == "band" || it.type == "point"
              }
            emitted +=
              if (offset == null || stepForPosition) {
                obj {
                  put("name", "${scaleName}_step")
                  put("value", step ?: config.step)
                }
              } else {
                val nestedInner =
                  (offset.properties["paddingInner"] as? VegaValue.Num)?.value ?: 0.0
                val nestedOuter =
                  (offset.properties["paddingOuter"] as? VegaValue.Num)?.value ?: 0.0
                obj {
                  put("name", "${scaleName}_step")
                  put(
                    "update",
                    // `bandspace` counts the *bands* a padded band scale needs; a **point** scale
                    // has no bands, only places, so the count is the domain's own length.
                    "${number(step ?: config.step)} * " +
                      (if (offset.type == "point") "domain('${offset.name()}').length"
                      else
                        "bandspace(domain('${offset.name()}').length, " +
                          "${number(nestedInner)}, ${number(nestedOuter)})") +
                      " / (1-${number(paddingInner)})",
                  )
                }
              }
            val counted = cardinality[channel]
            if (counted != null) {
              // The cells count their own categories, so there is nothing for the grid to hold: the
              // size is an expression over the cell's row and every reader of it reads that.
              expressions[channel] =
                "bandspace(datum[${quoted(counted)}], ${number(paddingInner)}, " +
                  "${number(paddingOuter)}) * ${scaleName}_step"
            } else {
              emitted += obj {
                put("name", sizeName)
                put(
                  "update",
                  "bandspace(domain('$scaleName').length, ${number(paddingInner)}, " +
                    "${number(paddingOuter)}) * ${scaleName}_step",
                )
              }
            }
            null
          }
        }

      sizes[channel] = value
      if (value == null) continue
      if (sizeName == "width" || sizeName == "height") {
        if (channel == "x") widthValue = value else heightValue = value
      } else {
        emitted += obj {
          put("name", sizeName)
          put("value", value)
        }
      }
    }

    signals = emitted
    width = widthValue
    height = heightValue
    values = sizes
  }

  companion object {

    /**
     * The signal a **`"container"`** size is: the element measured at first render and again on
     * every resize, with the view's own default where there is nothing to measure.
     *
     * A chart rendered outside a browser still has to have a width, which is what the fallback is
     * for. Written here rather than only inside a plot's own sizing because a *concatenation* may
     * merge its children onto it, and then the signal belongs to the level that settled it.
     */
    fun containerSignal(name: String, channel: String, config: Config): VegaValue {
      val measured = if (channel == "x") "containerSize()[0]" else "containerSize()[1]"
      val fallback =
        Decimals.jsString(if (channel == "x") config.continuousWidth else config.continuousHeight)
      val expression = "isFinite($measured) ? $measured : $fallback"
      return obj {
        put("name", name)
        put("init", expression)
        put(
          "on",
          arr(
            listOf(
              obj {
                put("events", "window:resize")
                put("update", expression)
              }
            )
          ),
        )
      }
    }

    /**
     * The plain number a channel's size comes out as, or null where it is derived from a step.
     *
     * A concatenation has to know this *before* the sizes are named, because what it names them
     * depends on whether its plots agree; and the answer needs nothing but the declared size and
     * the kind of scale, both of which are settled long before a padding is.
     */
    /**
     * Whether this is a size the specification **stated**, rather than one to be worked out.
     *
     * A number or a string, `{"step": …}` aside: `isStep(specifiedSize) ? 'step' : specifiedSize`
     * asks only that one question of it and takes anything else as written. `"container"` is a
     * stated size too — the page settles what it comes to — and is answered before this is asked.
     */
    fun VegaValue?.isStatedSize(): Boolean = this is VegaValue.Num || this is VegaValue.Str

    /**
     * The size a level takes: its **first member's**, and the level's own where no member has one.
     *
     * ```js
     * size: isFrameMixins(spec)
     *   ? {...parentGivenSize, ...(spec.width !== undefined ? {width: spec.width} : {}), ...}
     *   : parentGivenSize,
     * ```
     * ```js
     * mergedSize = mergeValuesWithExplicit(mergedSize, childSize, sizeType, '', defaultTieBreaker);
     * ```
     *
     * A member's own size overrides the one the level above handed it — that spread is the whole of
     * it — and `parseNonUnitLayoutSizeForChannel` then merges the members', the first of them
     * winning a disagreement with a warning. So a chart written `"width": "container"` whose layers
     * are each 600 wide is 600 wide: the members were handed the container and then said otherwise,
     * and there is nothing left for the page to settle. Read the other way round — the chart's own
     * first — the layers' width was never consulted at all, and such a chart measured the element
     * it was drawn in instead.
     *
     * A member that states nothing carries the level's own here, `inherited` having put it there,
     * so the first member answers for both cases at once.
     */
    /** [declaredSize] for both channels, which is what a level hands the views inside it. */
    fun statedSizes(views: List<UnitView>, spec: VegaValue.Obj): Map<String, VegaValue?> =
      mapOf("x" to declaredSize(views, spec, "x"), "y" to declaredSize(views, spec, "y"))

    private fun declaredSize(views: List<UnitView>, spec: VegaValue.Obj, channel: String) =
      views.firstNotNullOfOrNull { if (channel == "x") it.spec.width else it.spec.height }
        ?: spec.fields[if (channel == "x") "width" else "height"]

    fun value(
      views: List<UnitView>,
      scales: Map<String, ScaleComponent>,
      config: Config,
      spec: VegaValue.Obj,
      channel: String,
    ): VegaValue? {
      val declared = declaredSize(views, spec, channel)
      // ```js
      // component.layoutSize.set(sizeType, isStep(specifiedSize) ? 'step' : specifiedSize, true);
      // ```
      //
      // **Whatever was stated** is the layout size, a step object aside: `parseUnitLayoutSize` puts
      // the specified size into the component without asking what kind of value it is. A chart
      // written `"width": "1024"` is 1024 wide, not 300 — the string is the size, and the only
      // place it stops being one is the hoist to the top of the chart, which coerces it. Two
      // specifications in the wild corpus state a size as a string.
      if (declared.isStatedSize()) return declared
      // `component.layoutSize.set(sizeType, isStep(specifiedSize) ? 'step' : specifiedSize, true)`:
      // a **`"container"`** size is the layout size, as a number is. It is what a level above
      // compares when it merges its children — two plots asking the page for their width agree,
      // and what they agree on is to ask the page — and answering with the view's own default
      // instead merged them on a number and wrote that number out. The chart then had a width of
      // its own and never measured the element it was drawn in.
      if (declared == VegaValue.Str("container")) return declared
      val scale = scales[channel]
      if (scale != null && (scale.type == "band" || scale.type == "point")) return null
      // A channel with **no scale at all** is not a continuous one: `defaultUnitSize` falls to the
      // *discrete* size for it, which is a step. That is what makes a one-dimensional chart — a
      // strip of ticks, a bar chart of one measure — twenty units deep rather than three hundred,
      // and it is the single most common way a gallery example came out the wrong size.
      // `defaultUnitSize`'s third arm, which this had only half of:
      //
      //     } else if (model.hasProjection || model.mark === 'arc') {
      //       // arc should use continuous size by default otherwise the pie is extremely small
      //       return getViewConfigContinuousSize(config.view, sizeType);
      //
      // A **map** is as tall as a continuous plot for the same reason a pie is. `hasProjection` is
      // a `geoshape` mark or a geographic position channel, and a chart drawn that way has no
      // position scale on either channel — so falling to the discrete size made it twenty units
      // deep, which is a strip rather than a map.
      val projected = views.any { view ->
        view.spec.mark == "geoshape" ||
          view.spec.encoding.keys.any { it in Channels.GEO_POSITION_CHANNELS }
      }
      if (scale == null && !projected && views.none { it.spec.mark == "arc" }) {
        val discrete =
          if (channel == "x") config.discreteWidth ?: config.step
          else config.discreteHeight ?: config.step
        return num(discrete)
      }
      return num(if (channel == "x") config.continuousWidth else config.continuousHeight)
    }
  }

  /**
   * JavaScript's number-to-text, so `0.1` and `0` read as upstream writes them in an expression.
   *
   * `Decimals.jsString` is `String(x)` exactly, which is what this was approximating in two ways
   * that break: `toLong()` saturates at 9.2e18 while the guard let 1e21 through, so a step over
   * that turned into `9223372036854775807`; and `Double.toString` writes `1.0E-7` where JavaScript
   * writes `1e-7`.
   */
  private fun number(value: Double): String = Decimals.jsString(value)
}
