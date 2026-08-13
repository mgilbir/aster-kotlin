package dev.aster.vegalite

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

  init {
    val emitted = mutableListOf<VegaValue>()
    val sizes = LinkedHashMap<String, VegaValue?>()
    var widthValue: VegaValue? = null
    var heightValue: VegaValue? = null

    for (channel in listOf("x", "y")) {
      val sizeName = names.getValue(channel)
      val declared =
        spec.fields[if (channel == "x") "width" else "height"]
          ?: views.firstOrNull()?.spec?.let { if (channel == "x") it.width else it.height }
      val scale = scales[channel]
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

      val value: VegaValue? =
        when {
          !discrete || declared is VegaValue.Num -> value(views, scales, config, spec, channel)
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
            val offset = scales[if (channel == "x") "xOffset" else "yOffset"]
            emitted +=
              if (offset == null || stepForPosition) {
                obj {
                  put("name", "$scalePrefix${channel}_step")
                  put("value", step ?: config.step)
                }
              } else {
                val nestedInner =
                  (offset.properties["paddingInner"] as? VegaValue.Num)?.value ?: 0.0
                val nestedOuter =
                  (offset.properties["paddingOuter"] as? VegaValue.Num)?.value ?: 0.0
                obj {
                  put("name", "$scalePrefix${channel}_step")
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
            emitted += obj {
              put("name", sizeName)
              put(
                "update",
                "bandspace(domain('$scalePrefix$channel').length, ${number(paddingInner)}, " +
                  "${number(paddingOuter)}) * $scalePrefix${channel}_step",
              )
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
     * The plain number a channel's size comes out as, or null where it is derived from a step.
     *
     * A concatenation has to know this *before* the sizes are named, because what it names them
     * depends on whether its plots agree; and the answer needs nothing but the declared size and
     * the kind of scale, both of which are settled long before a padding is.
     */
    fun value(
      views: List<UnitView>,
      scales: Map<String, ScaleComponent>,
      config: Config,
      spec: VegaValue.Obj,
      channel: String,
    ): VegaValue? {
      val declared =
        spec.fields[if (channel == "x") "width" else "height"]
          ?: views.firstOrNull()?.spec?.let { if (channel == "x") it.width else it.height }
      if (declared is VegaValue.Num) return declared
      val scale = scales[channel]
      if (scale != null && (scale.type == "band" || scale.type == "point")) return null
      // A channel with **no scale at all** is not a continuous one: `defaultUnitSize` falls to the
      // *discrete* size for it, which is a step. That is what makes a one-dimensional chart — a
      // strip of ticks, a bar chart of one measure — twenty units deep rather than three hundred,
      // and it is the single most common way a gallery example came out the wrong size.
      if (scale == null && views.none { it.spec.mark == "arc" }) {
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
   */
  private fun number(value: Double): String =
    if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e21) value.toLong().toString()
    else value.toString()
}
