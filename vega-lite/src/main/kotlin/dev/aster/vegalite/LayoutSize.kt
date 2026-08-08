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
   * `child_` inside a facet, where `width` is the whole grid and this sizes one cell of it.
   *
   * A cell's size is also always a *signal*, never a top-level property: the grid's own width is
   * whatever the layout makes of the cells, so there is no number to write down.
   */
  private val prefix: String = "",
) {
  val signals: List<VegaValue>
  val width: VegaValue?
  val height: VegaValue?

  init {
    val emitted = mutableListOf<VegaValue>()
    var widthValue: VegaValue? = null
    var heightValue: VegaValue? = null

    for (channel in listOf("x", "y")) {
      val sizeName = prefix + if (channel == "x") "width" else "height"
      val declared =
        spec.fields[if (channel == "x") "width" else "height"]
          ?: views.firstOrNull()?.spec?.let { if (channel == "x") it.width else it.height }
      val scale = scales[channel]
      val discrete = scale != null && (scale.type == "band" || scale.type == "point")
      val step = (declared as? VegaValue.Obj)?.number("step")

      val value: VegaValue? =
        when {
          declared is VegaValue.Num -> declared
          discrete -> {
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
              if (offset == null) {
                obj {
                  put("name", "${channel}_step")
                  put("value", step ?: config.step)
                }
              } else {
                val nestedInner =
                  (offset.properties["paddingInner"] as? VegaValue.Num)?.value ?: 0.0
                val nestedOuter =
                  (offset.properties["paddingOuter"] as? VegaValue.Num)?.value ?: 0.0
                obj {
                  put("name", "${channel}_step")
                  put(
                    "update",
                    "${number(step ?: config.step)} * " +
                      "bandspace(domain('${offset.channel}').length, " +
                      "${number(nestedInner)}, ${number(nestedOuter)})" +
                      " / (1-${number(paddingInner)})",
                  )
                }
              }
            emitted += obj {
              put("name", sizeName)
              put(
                "update",
                "bandspace(domain('$channel').length, ${number(paddingInner)}, ${number(paddingOuter)})" +
                  " * ${channel}_step",
              )
            }
            null
          }
          else -> {
            val size = if (channel == "x") config.continuousWidth else config.continuousHeight
            if (prefix.isEmpty()) {
              num(size)
            } else {
              emitted += obj {
                put("name", sizeName)
                put("value", size)
              }
              null
            }
          }
        }

      if (channel == "x") widthValue = value else heightValue = value
    }

    signals = emitted
    width = widthValue
    height = heightValue
  }

  /**
   * JavaScript's number-to-text, so `0.1` and `0` read as upstream writes them in an expression.
   */
  private fun number(value: Double): String =
    if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e21) value.toLong().toString()
    else value.toString()
}
