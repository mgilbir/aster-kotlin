package dev.aster.vegalite

import dev.aster.vega.model.VegaValue

/**
 * Vega-Lite's configuration: the defaults a specification does not state.
 *
 * These numbers decide the whole look of a chart that says nothing about it — the blue of a bar,
 * the gap between two of them, the 300 units a continuous plot is wide — so they are copied from
 * `config.ts` rather than chosen, and each block below names the upstream constant it came from.
 *
 * User configuration merges *over* these, one level deep per block, which is what `mergeConfig`
 * does: `{"bar": {"fill": "red"}}` replaces the bar's fill and keeps its `binSpacing`.
 */
internal class Config(private val user: VegaValue.Obj = VegaValue.EmptyObject) {

  val raw: VegaValue.Obj
    get() = user

  val background: VegaValue = user.fields["background"] ?: VegaValue.Str("white")

  val padding: VegaValue = user.fields["padding"] ?: VegaValue.Num(5.0)

  val timeFormat: String = user.string("timeFormat") ?: "%b %d, %Y"

  val countTitle: String = user.string("countTitle") ?: "Count of Records"

  val normalizedNumberFormat: String = user.string("normalizedNumberFormat") ?: ".0%"

  val numberFormat: String? = user.string("numberFormat")

  /** `view.continuousWidth`/`continuousHeight`: the size of a plot with a continuous position. */
  val continuousWidth: Double = view.number("continuousWidth") ?: 300.0

  val continuousHeight: Double = view.number("continuousHeight") ?: 300.0

  /** One discrete step, from which a band-scaled plot's whole width is computed. */
  val step: Double = view.number("step") ?: 20.0

  private val view: VegaValue.Obj
    get() = user.obj("view") ?: VegaValue.EmptyObject

  /** A block of mark configuration — `config.bar`, `config.point` — merged over `config.mark`. */
  fun markConfig(mark: String): VegaValue.Obj {
    val defaults = MARK_DEFAULTS[mark] ?: VegaValue.EmptyObject
    return obj {
      putAll(DEFAULT_MARK)
      putAll(user.obj("mark"))
      putAll(defaults)
      putAll(user.obj(mark))
    }
  }

  fun scaleConfig(name: String): Double? = user.obj("scale").number(name) ?: SCALE_DEFAULTS[name]

  /** `config.style.<name>`, which a mark's `style` list pulls in as well as its own block. */
  fun style(name: String): VegaValue.Obj? = user.obj("style")?.obj(name)

  private companion object {
    /** `defaultMarkConfig`. `invalid` and `timeUnitBandSize` are carried for completeness. */
    val DEFAULT_MARK: VegaValue.Obj = obj { put("color", "#4c78a8") }

    val DEFAULT_RECT: VegaValue.Obj = obj {
      put("binSpacing", 0)
      put("continuousBandSize", 5)
      put("minBandSize", 0.25)
      put("timeUnitBandPosition", 0.5)
    }

    val MARK_DEFAULTS: Map<String, VegaValue.Obj> =
      mapOf(
        // `defaultBarConfig` is the rect block with a unit of spacing between bins.
        "bar" to
          obj {
            putAll(DEFAULT_RECT)
            put("binSpacing", 1)
          },
        "rect" to DEFAULT_RECT,
        "tick" to
          obj {
            putAll(DEFAULT_RECT)
            put("thickness", 1)
          },
        // Both override the blue of the shared mark config rather than inheriting it.
        "rule" to obj { put("color", "black") },
        "text" to obj { put("color", "black") },
      )

    /** `defaultScaleConfig`. */
    val SCALE_DEFAULTS: Map<String, Double> =
      mapOf(
        "pointPadding" to 0.5,
        "barBandPaddingInner" to 0.1,
        "rectBandPaddingInner" to 0.0,
        "tickBandPaddingInner" to 0.25,
        "bandWithNestedOffsetPaddingInner" to 0.2,
        "bandWithNestedOffsetPaddingOuter" to 0.2,
        "minBandSize" to 2.0,
        "minFontSize" to 8.0,
        "maxFontSize" to 40.0,
        "minOpacity" to 0.3,
        "maxOpacity" to 0.8,
        "minSize" to 4.0,
        "minStrokeWidth" to 1.0,
        "maxStrokeWidth" to 4.0,
        "quantileCount" to 4.0,
        "quantizeCount" to 4.0,
      )
  }
}
