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

  /**
   * `view.discreteWidth`/`discreteHeight`: how deep a plot is along a channel with **no scale**.
   *
   * A plain number here replaces the step entirely — `getViewConfigDiscreteSize` takes the
   * configured size first and only then falls back to `{step}` — which is how a themed chart makes
   * every one-dimensional strip the same depth without mentioning a step.
   */
  val discreteWidth: Double? = view.number("width") ?: view.number("discreteWidth")

  val discreteHeight: Double? = view.number("height") ?: view.number("discreteHeight")

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

  /** `config.axis.<name>`, which a theme uses to settle a property for every axis at once. */
  fun axisConfig(name: String): VegaValue? = user.obj("axis")?.fields?.get(name)

  /** `config.style.<name>`, which a mark's `style` list pulls in as well as its own block. */
  fun style(name: String): VegaValue.Obj? = user.obj("style")?.obj(name)

  /**
   * The user's configuration, as *Vega* takes it — `stripAndRedirectConfig` upstream.
   *
   * Three things happen on the way through, and a chart drawn in somebody else's theme depends on
   * all of them:
   *
   * - Vega-Lite-only keys are dropped. Some of them have already been applied here (`background`
   *   became a top-level property, `countTitle` named a field) and the rest mean nothing to Vega.
   * - A per-mark-type block is **redirected into `config.style`**, because Vega-Lite's `bar` and
   *   `rect` are the same Vega mark: left in `config.rect`, a rect theme would repaint every bar.
   * - `config.title` becomes the `group-title` style, with `color` rewritten as `fill`, since a
   *   style block names its properties the way a mark does.
   *
   * Anything not recognised passes through untouched rather than being dropped: Vega has guide
   * configuration this compiler never reads, and a theme that sets it should still reach the
   * renderer.
   */
  fun forVega(): VegaValue.Obj? {
    val out = LinkedHashMap<String, VegaValue>()
    val styles = LinkedHashMap<String, VegaValue>()

    for ((key, value) in user.fields) {
      when {
        key in VEGA_LITE_ONLY -> Unit
        key == "style" -> (value as? VegaValue.Obj)?.fields?.forEach { (k, v) -> styles[k] = v }
        // `config.mark` survives, minus the properties only Vega-Lite understands — `color` and
        // `filled` are resolved into a mark's own fill and stroke long before Vega sees anything.
        key == "mark" ->
          (value as? VegaValue.Obj)
            ?.let { block ->
              VegaValue.Obj(block.fields.filterKeys { it !in VEGA_LITE_ONLY_MARK })
            }
            ?.takeIf { it.fields.isNotEmpty() }
            ?.let { out["mark"] = it }
        key in MARK_TYPES ->
          if (value is VegaValue.Obj && value.fields.isNotEmpty()) styles[key] = value
        key == "title" -> titleStyle(value)?.let { styles["group-title"] = it }
        // `config.view` becomes the **`cell`** style, not a `view` one: "View's default style is
        // `cell`" — `stripAndRedirectConfig` renames it on the way through, and a chart that told
        // its plotting area not to draw a border was otherwise still drawing one.
        key == "view" -> viewStyle(value)?.let { styles["cell"] = it }
        else -> out[key] = value
      }
    }

    if (styles.isNotEmpty()) out["style"] = VegaValue.Obj(styles)
    return if (out.isEmpty()) null else VegaValue.Obj(out)
  }

  /** `config.title` names its colour `color`; a style block names it `fill`. */
  private fun titleStyle(value: VegaValue): VegaValue.Obj? {
    val block = value as? VegaValue.Obj ?: return null
    val fields = LinkedHashMap<String, VegaValue>()
    for ((key, property) in block.fields) {
      // The non-mark title properties are written on the title directive itself, not on a style.
      if (key in setOf("anchor", "frame", "offset", "orient", "angle", "limit")) continue
      if (key.startsWith("subtitle")) continue
      fields[if (key == "color") "fill" else key] = property
    }
    return if (fields.isEmpty()) null else VegaValue.Obj(fields)
  }

  /** Only the view's own paint reaches Vega; its sizes are Vega-Lite's own arithmetic. */
  private fun viewStyle(value: VegaValue): VegaValue.Obj? {
    val block = value as? VegaValue.Obj ?: return null
    val fields = LinkedHashMap<String, VegaValue>()
    for ((key, property) in block.fields) {
      if (
        key in
          setOf("continuousWidth", "continuousHeight", "discreteWidth", "discreteHeight", "step")
      ) {
        continue
      }
      fields[key] = property
    }
    return if (fields.isEmpty()) null else VegaValue.Obj(fields)
  }

  private companion object {
    /** Keys Vega has no use for: this compiler has already applied them, or they mean nothing. */
    /** `VL_ONLY_MARK_CONFIG_PROPERTIES`: what a `config.mark` block loses on the way to Vega. */
    val VEGA_LITE_ONLY_MARK =
      setOf(
        "color",
        "filled",
        "invalid",
        "order",
        "radius2",
        "theta2",
        "timeUnitBandSize",
        "timeUnitBandPosition",
        "tooltip",
      )

    val VEGA_LITE_ONLY =
      setOf(
        "scale",
        "color",
        "fontSize",
        "background",
        "padding",
        "facet",
        "concat",
        "numberFormat",
        "numberFormatType",
        "normalizedNumberFormat",
        "normalizedNumberFormatType",
        "timeFormat",
        "timeFormatType",
        "countTitle",
        "fieldTitle",
        "header",
        "headerRow",
        "headerColumn",
        "headerFacet",
        "selection",
        "customFormatTypes",
        "axisQuantitative",
        "axisTemporal",
        "axisDiscrete",
        "axisPoint",
        "boxplot",
        "errorbar",
        "errorband",
      )

    /** The per-mark-type blocks, which are redirected into `style` rather than passed through. */
    val MARK_TYPES =
      setOf(
        "arc",
        "area",
        "bar",
        "circle",
        "geoshape",
        "image",
        "line",
        "point",
        "rect",
        "rule",
        "square",
        "text",
        "tick",
        "trail",
      )

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
        // The composite marks' own blocks, which say which of their parts are drawn. An error bar
        // is a rule with no caps unless caps are asked for; an error band is a faded band with no
        // edges. `center` is here rather than in the code because a theme may move it.
        "errorbar" to
          obj {
            put("center", "mean")
            put("rule", VegaValue.Bool(true))
            put("ticks", VegaValue.Bool(false))
          },
        "errorband" to
          obj {
            put("band", obj { put("opacity", 0.3) })
            put("borders", VegaValue.Bool(false))
          },
        // A box plot's parts, and the two numbers that decide its shape: how wide a box is, and how
        // many interquartile ranges a whisker reaches before a point is an outlier.
        "boxplot" to
          obj {
            put("size", 14)
            put("extent", 1.5)
            put("box", VegaValue.EmptyObject)
            put("median", obj { put("color", "white") })
            put("outliers", VegaValue.EmptyObject)
            put("rule", VegaValue.EmptyObject)
            put("ticks", VegaValue.Null)
          },
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
