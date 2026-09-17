package dev.aster.vegalite

import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asBoolean
import dev.aster.vega.model.locale.VegaLocale

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
internal class Config(
  stated: VegaValue.Obj = VegaValue.EmptyObject,
  /**
   * The host's language, carried here because it decides one thing this compiler **emits**.
   *
   * Almost nothing about a locale belongs to Vega-Lite: a month name is resolved by the runtime
   * from the pattern this compiler writes, so `%b` is enough and always was. The exception is the
   * *pattern* — `Fields.timeUnitSpecifier` writes an override table into the axis format, `%b %d,
   * %Y`, and the order of those fields is a property of a language rather than of a chart. It is
   * built from [VegaLocale.timeUnitSpecifiers] for that reason.
   *
   * The default is d3's `en-US`, which is what upstream produces, so the emitted specification is
   * byte-for-byte what it was before locales reached this side.
   */
  val locale: VegaLocale = VegaLocale.EnglishUS,
) {

  /**
   * What the specification wrote, with every **expression** in it made a signal.
   *
   * ```js
   * export function replaceExprRef<T extends Dict<any>>(index: T, {level}: {level: number} = {level: 0}) {
   *   const props = keys(index || {});
   *   const newIndex: Dict<any> = {};
   *   for (const prop of props) {
   *     newIndex[prop] = level === 0 ? signalRefOrValue(index[prop]) : replaceExprRef(index[prop], {level: level - 1});
   *   }
   *   return newIndex as MappedExclude<T, ExprRef>;
   * }
   * ```
   *
   * `initConfig` does this **once**, as the configuration is read, and everything downstream sees
   * signals: `config.mark.font` reaches a mark's encoding as `{"signal": …}` and `config.axis
   * .labelColor` reaches an axis the same way. Left as `{"expr": …}`, each of them was written into
   * the chart as a *value* that happened to be an object — a font whose name was `[object Object]`
   * — so a document that names its typeface once, in a parameter, and reads it from the theme drew
   * every word of every chart in the fallback face.
   */
  private val user: VegaValue.Obj = withSignals(stated)

  val raw: VegaValue.Obj
    get() = user

  val background: VegaValue = user.fields["background"] ?: VegaValue.Str("white")

  val padding: VegaValue = user.fields["padding"] ?: VegaValue.Num(5.0)

  /**
   * How a themed chart is sized, which `normalizeAutoSize` reads between the two it settles.
   *
   * A theme states the sizing behaviour a document's charts share — `{"autosize": "fit-x"}` for a
   * column of charts that fill the page — and a chart of its own overrides it, property by property
   * rather than whole: a theme's `contains: "padding"` survives a chart asking to be padded instead
   * of fitted.
   */
  val autosize: VegaValue? = user.fields["autosize"]

  val timeFormat: String = user.string("timeFormat") ?: "%b %d, %Y"

  val countTitle: String = user.string("countTitle") ?: "Count of Records"

  /**
   * `config.fieldTitle`: which of the three title formatters a guide's default caption comes from.
   */
  val fieldTitle: String? = user.string("fieldTitle")

  val normalizedNumberFormat: String = user.string("normalizedNumberFormat") ?: ".0%"

  val numberFormat: String? = user.string("numberFormat")

  /**
   * `config.numberFormatType`, honoured only where `customFormatTypes` says to honour it.
   *
   * A **custom** format type is not a d3 specifier but the name of a function the embedding page
   * registered, so Vega cannot be handed it as a `format`: `guideFormat` answers nothing and the
   * label is written out as an expression calling it instead — `pow(datum.value, "1.0")`. The flag
   * is a safety catch, since an unregistered name would be a runtime error on every label.
   */
  val numberFormatType: String? =
    user.string("numberFormatType")?.takeIf { user.boolean("customFormatTypes") == true }

  /**
   * The same configuration under `config.tooltipFormat` — `{...config, ...config.tooltipFormat}`.
   *
   * A tooltip is a small table rather than a caption, and a chart may want more precision there
   * than on its axes: `{"numberFormat": "d", "tooltipFormat": {"numberFormat": ".8f"}}` rounds the
   * labels and spells the tooltip out. The block overrides key by key, so anything it leaves out is
   * still the chart's own.
   */
  val forTooltip: Config by lazy {
    val block = user.obj("tooltipFormat") ?: return@lazy this
    Config(
      obj {
        putAll(user)
        block.fields.forEach { (key, value) -> put(key, value) }
      },
      locale,
    )
  }

  /** `config.mark.invalid`: what every mark does with a null it cannot place. */
  val markInvalid: VegaValue? = user.obj("mark")?.fields?.get("invalid")

  /**
   * `config.scale.invalid[channel]`: a stated output for an invalid value on one channel.
   *
   * A channel that has one is valid by construction — the scale answers for a null — so it neither
   * breaks a path nor filters a row.
   */
  fun scaleInvalid(channel: String): VegaValue? =
    user.obj("scale")?.obj("invalid")?.fields?.get(channel)

  /**
   * `view.continuousWidth`/`continuousHeight`: the size of a plot with a continuous position.
   *
   * `view.width` and `view.height` are the same properties under the names they had before the
   * continuous and discrete sizes were told apart, and upstream still reads them **first** — "get
   * width/height for backwards compatibility". A theme written against an older Vega-Lite sizes its
   * plots that way, and this read only the newer names, so such a chart was drawn at the default.
   */
  val continuousWidth: Double = view.number("width") ?: view.number("continuousWidth") ?: 300.0

  val continuousHeight: Double = view.number("height") ?: view.number("continuousHeight") ?: 300.0

  /** One discrete step, from which a band-scaled plot's whole width is computed. */
  val step: Double = view.number("step") ?: DEFAULT_STEP

  /**
   * `view.discreteWidth`/`discreteHeight`: how deep a plot is along a channel with **no scale**.
   *
   * A plain number here replaces the step entirely — `getViewConfigDiscreteSize` takes the
   * configured size first and only then falls back to `{step}` — which is how a themed chart makes
   * every one-dimensional strip the same depth without mentioning a step.
   */
  val discreteWidth: Double? = view.number("width") ?: view.number("discreteWidth")

  val discreteHeight: Double? = view.number("height") ?: view.number("discreteHeight")

  /**
   * `getViewConfigDiscreteStep`: the step **that channel's** own themed size comes to.
   *
   * ```js
   * export function getViewConfigDiscreteStep(viewConfig, channel) {
   *   const size = getViewConfigDiscreteSize(viewConfig, channel);
   *   return isStep(size) ? size.step : DEFAULT_STEP;
   * }
   * ```
   *
   * A theme may state a step for one dimension and leave the other alone, and every reader asks the
   * dimension it is sizing: `view.discreteWidth` answers for `x` and `view.discreteHeight` for `y`.
   * `view.step` is the answer only where neither is set — it is what `getViewConfigDiscreteSize`
   * falls back to — so a theme that states one was being ignored, and a document whose bars are
   * thirty units apart drew them twenty.
   *
   * Where the themed size is a plain **number** the answer is `DEFAULT_STEP` and not `view.step`:
   * `isStep` is false and the fallback has already been passed. It is upstream's own reading and it
   * is what upstream emits. Most readers never see it — a themed depth is not a step at all, so
   * they are not asking — but the `size` scale's largest point is bounded by the *smaller* of the
   * two steps whether or not either sizes a scale, and there it shows.
   */
  fun discreteStep(size: String): Double {
    val themed =
      view.fields[size] ?: view.fields[if (size == "width") "discreteWidth" else "discreteHeight"]
    if (themed == null) return step
    return (themed as? VegaValue.Obj)?.number("step") ?: DEFAULT_STEP
  }

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

  /**
   * `config[mark.type]` and `config.mark` **unmerged**, in that order.
   *
   * [markConfig] flattens the two into one table, which is what most readers want and what most
   * properties can live with. It cannot answer `getMarkConfig`, though, because that walks the two
   * blocks looking for a *different key in each*:
   * ```js
   * getFirstDefined(cfg, cfg, config[mark.type][vgChannel], config[mark.type][channel],
   *                 vgChannel ? config.mark[vgChannel] : config.mark[channel])
   * ```
   *
   * so `config.bar.size` outranks `config.mark.width`, and a flattened table would answer with
   * whichever key happens to be present rather than with the block that owns it.
   */
  fun markBlocks(mark: String): List<VegaValue.Obj> =
    listOf(
      obj {
        putAll(MARK_DEFAULTS[mark] ?: VegaValue.EmptyObject)
        putAll(user.obj(mark))
      },
      obj {
        putAll(DEFAULT_MARK)
        putAll(user.obj("mark"))
      },
    )

  fun scaleConfig(name: String): Double? = user.obj("scale").number(name) ?: SCALE_DEFAULTS[name]

  /** A `config.scale` entry that is a flag rather than a number, such as `zero`. */
  fun scaleFlag(name: String): Boolean? =
    (user.obj("scale")?.fields?.get(name) as? VegaValue.Bool)?.value

  /** `config.axis.<name>`, which a theme uses to settle a property for every axis at once. */
  fun axisConfig(name: String): VegaValue? = user.obj("axis")?.fields?.get(name)

  /**
   * `getAxisConfigs`: the blocks an axis property may be configured in, most specific first.
   *
   * A theme can speak about every axis (`config.axis`), about one direction (`config.axisX`), about
   * one edge (`config.axisBottom`) or about a kind of scale (`config.axisTemporal`, and the same
   * again per direction). The Vega-Lite-only blocks — the ones named after a scale — outrank all
   * the rest, since Vega has never heard of them and would apply nothing.
   */
  fun axisConfigChain(channel: String, scaleType: String, orient: String): List<VegaValue.Obj> {
    val (vegaLiteOnly, vega) = axisConfigFamilies(channel, scaleType, orient)
    return vegaLiteOnly + vega
  }

  /**
   * [axisConfigChain], split into the two families `getAxisConfigs` keeps apart.
   *
   * ```js
   * const vlOnlyConfigTypes = [...typeBasedConfigTypes, ...typeBasedConfigTypes.map((c) => axisChannel + c.substr(4))];
   * const vgConfigTypes = ['axis', axisOrient, axisChannel];
   * ```
   *
   * The distinction decides what reaches the axis. A property stated in a block **Vega** knows —
   * `config.axis`, `config.axisX`, `config.axisBottom` — is left off the axis so that Vega applies
   * it from its own config block, which is the only way it can settle every axis at once. One
   * stated in a block only *Vega-Lite* knows — `config.axisQuantitative` and its per-direction
   * twins, named after a kind of scale rather than a place — has to be written onto the axis, since
   * nothing downstream would apply it.
   *
   * @return the Vega-Lite-only blocks first, then Vega's own; each most specific first.
   */
  fun axisConfigFamilies(
    channel: String,
    scaleType: String,
    orient: String,
  ): Pair<List<VegaValue.Obj>, List<VegaValue.Obj>> {
    val typeBased =
      when {
        scaleType == "band" -> listOf("Band", "Discrete")
        scaleType == "point" -> listOf("Point", "Discrete")
        scaleType == "time" || scaleType == "utc" -> listOf("Temporal")
        Scales.hasContinuousDomain(scaleType) -> listOf("Quantitative")
        else -> emptyList()
      }
    val axisChannel = if (channel == "x") "axisX" else "axisY"
    val vegaLiteOnly = typeBased.map { axisChannel + it } + typeBased.map { "axis$it" }
    val vega = listOf(axisChannel, "axis${orient.replaceFirstChar { it.uppercase() }}", "axis")
    return vegaLiteOnly.mapNotNull { user.obj(it) } to vega.mapNotNull { user.obj(it) }
  }

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
   * - A `config.legend` block loses the five words only Vega-Lite knows, the same way `config.mark`
   *   loses its own.
   * - `config.title` becomes the `group-title` style, with `color` rewritten as `fill`, since a
   *   style block names its properties the way a mark does.
   * - Every property left holding an empty block is deleted, whatever its name, which is the last
   *   thing upstream does and the only step here that is not about a particular key.
   *
   * Anything not recognised passes through untouched rather than being dropped: Vega has guide
   * configuration this compiler never reads, and a theme that sets it should still reach the
   * renderer.
   */
  fun forVega(diagnostics: DiagnosticCollector): VegaValue.Obj? {
    val out = LinkedHashMap<String, VegaValue>()
    val styles = LinkedHashMap<String, VegaValue>()

    // `initConfig` lifts `font` out of the configuration and merges a derived block in its place,
    // **under** everything the specification wrote:
    //
    //     const {color, font, fontSize, selection, ...restConfig} = specifiedConfig;
    //     const mergedConfig = mergeConfig({}, duplicate(defaultConfig),
    //       font ? fontConfig(font) : {}, …, restConfig || {});
    //
    //     export function fontConfig(font: string): Config {
    //       return {text: {font}, style: {'guide-label': {font}, 'guide-title': {font},
    //                                     'group-title': {font}, 'group-subtitle': {font}}};
    //     }
    //
    // Vega has no top-level `config.font`, so a theme that names one and nothing else reached the
    // renderer with the font in a place nothing reads: the whole chart was drawn in the default
    // face. Seeded here rather than written at the end, because the specification's own style
    // blocks merge *over* it — a `guide-label` that names a colour keeps this font beside it.
    (user.fields["font"] as? VegaValue.Str)?.let { font ->
      val block = obj { put("font", font) }
      for (name in listOf("text", "guide-label", "guide-title", "group-title", "group-subtitle")) {
        styles[name] = block
      }
    }

    for ((key, value) in user.fields) {
      when {
        key in VEGA_LITE_ONLY -> Unit
        // A `config.style` block is passed through **whole**. `stripAndRedirectConfig` deletes
        // Vega-Lite-only properties from `config.mark` and from each `config[markType]`, and from
        // nowhere else — a style block is never walked:
        //
        //     if (config.mark) { for (const prop of VL_ONLY_MARK_CONFIG_PROPERTIES) delete …; }
        //     for (const markType of MARK_STYLES) { … redirectConfigToStyleConfig(config,
        // markType); }
        //
        // and the redirection *reads into* `config.style` rather than filtering what is already
        // there, `{...propConfig, ...config.style[toProp ?? prop]}`. Filtered like a mark config,
        // a style block lost every word on that list — a theme that said how a named style treats
        // an unplaceable value, or gave one a colour, arrived at the renderer without it.
        key == "style" ->
          (value as? VegaValue.Obj)?.fields?.forEach { (k, v) ->
            // `mergeConfig` is a deep merge over the derived blocks above, so a style that names
            // one property keeps the seeded font beside it rather than replacing the block.
            //
            // A block that names *nothing* is still written. The closing sweep below is over the
            // configuration's own properties and goes no deeper — `config.style` is what it asks
            // about, not `config.style.named` — so a named style written empty survives, and
            // `{"style": {"named": {}}}` is what upstream emits for it. Dropped here, a theme that
            // declares its styles up front and fills some of them in later arrived one style short.
            styles[k] = merged(styles[k], v)
          }
        // `config.mark` survives, minus the properties only Vega-Lite understands — `color` and
        // `filled` are resolved into a mark's own fill and stroke long before Vega sees anything.
        key == "mark" ->
          (value as? VegaValue.Obj)
            ?.let { block ->
              // ```js
              // if (config.mark.tooltip && isObject(config.mark.tooltip)) {
              //   delete config.mark.tooltip;
              // }
              // ```
              //
              // A tooltip written as an **object** — `{"content": "data"}` — says *which* fields to
              // show, which is a question only Vega-Lite can answer: it is spent while compiling,
              // turning into the `tooltip` channel on the marks themselves. A bare `true` is
              // Vega's own switch and travels through. Passed on as written, Vega was handed a
              // table where it expects a flag.
              val tooltip = block.fields["tooltip"]
              val dropped =
                if (tooltip is VegaValue.Obj && tooltip.asBoolean()) VEGA_LITE_ONLY_MARK + "tooltip"
                else VEGA_LITE_ONLY_MARK
              VegaValue.Obj(block.fields.filterKeys { it !in dropped })
            }
            ?.let { out["mark"] = it }
        // ```js
        // if (config.legend) {
        //   for (const prop of VL_ONLY_LEGEND_CONFIG) { delete config.legend[prop]; }
        // }
        // ```
        //
        // The five are *inputs* to this compiler, not instructions to Vega: the four
        // `gradient*Length` bounds are the clamp a gradient legend's length is worked out from, and
        // `unselectedOpacity` is what a legend bound to a selection fades its unpicked entries to.
        // Vega has never heard of any of them, so passing them through put five unknown words in
        // the block it applies to every legend. A block left holding nothing at all is dropped by
        // the closing sweep below, as every other emptied block is.
        key == "legend" ->
          (value as? VegaValue.Obj)
            ?.let { block ->
              VegaValue.Obj(block.fields.filterKeys { it !in VEGA_LITE_ONLY_LEGEND })
            }
            ?.let { out["legend"] = it }
        key in MARK_TYPES ->
          (value as? VegaValue.Obj)
            ?.let { block ->
              val drop = VEGA_LITE_ONLY_MARK + MARK_SPECIFIC_VEGA_LITE_ONLY[key].orEmpty()
              VegaValue.Obj(block.fields.filterKeys { it !in drop })
            }
            ?.takeIf { it.fields.isNotEmpty() }
            ?.let { styles[key] = merged(styles[key], it) }
        key == "title" -> {
          titleStyle(value)?.let { styles["group-title"] = merged(styles["group-title"], it) }
          subtitleStyle(value)?.let {
            styles["group-subtitle"] = merged(styles["group-subtitle"], it)
          }
          // "subtitle part can stay in config.title since header titles do not use subtitle":
          //
          //     if (!isEmpty(subtitle)) { config.title = subtitle; } else { delete config.title; }
          //
          // So `config.title` **survives**, holding those seven properties and nothing else. This
          // consumed the whole block, and a theme that set `subtitleFont` had nowhere to say it —
          // the subtitle was drawn in the title's face.
          subtitleProperties(value)?.let { out["title"] = it }
        }
        // `config.view` becomes the **`cell`** style, not a `view` one: "View's default style is
        // `cell`" — `stripAndRedirectConfig` renames it on the way through, and a chart that told
        // its plotting area not to draw a border was otherwise still drawing one.
        key == "view" -> viewStyle(value)?.let { styles["cell"] = merged(styles["cell"], it) }
        else -> out[key] = value
      }
    }

    if (styles.isNotEmpty()) out["style"] = VegaValue.Obj(styles)
    // ```js
    // if (config.params) {
    //   config.signals = (config.signals || []).concat(assembleParameterSignals(config.params));
    //   delete config.params;
    // }
    // ```
    //
    // A theme's **parameters** are signals of the document rather than of a chart: a colour named
    // once and read by every guide in it. Vega has no `config.params`, so left under that name they
    // reached the renderer as nothing at all and every expression that read one was undefined.
    Params.signals(user, diagnostics)
      .takeIf { it.isNotEmpty() }
      ?.let { declared ->
        out.remove("params")
        out["signals"] = arr((out["signals"] as? VegaValue.Arr)?.values.orEmpty() + declared)
      }
    // ```js
    // // Remove empty config objects.
    // for (const prop in config) {
    //   if (isObject(config[prop]) && isEmpty(config[prop])) { delete config[prop]; }
    // }
    // ```
    //
    // The last thing `stripAndRedirectConfig` does, and it asks about **every** property rather
    // than about a named few. A block may arrive empty because the specification wrote it so —
    // `{"config": {"axis": {}}}` — or because everything in it was Vega-Lite's own and has just
    // been taken out, which is how `{"config": {"legend": {"unselectedOpacity": 0.3}}}` ends. Each
    // block this compiler knows by name was dropping its own, so the ones it passes through
    // untouched — an axis, a projection, a range, a header — reached Vega as empty objects nobody
    // had asked for, in a configuration upstream does not emit at all.
    //
    // `isObject` is Vega's, so an **array** answers to it too, and one property of a configuration
    // is a list: `{"params": []}` is as empty as `{"axis": {}}` and goes the same way. Upstream
    // reaches it by another road — `if (config.params)` is true of an empty list, so the block
    // above turns it into `config.signals = []` and this sweep deletes that instead — but the
    // configuration it emits is the same one, and neither word is in it. A `null` does not answer:
    // `isObject` is `_ != null && typeof _ === 'object'`, so a property stated as null survives.
    out.values.removeAll { it.isEmptyBlock() }
    return if (out.isEmpty()) null else VegaValue.Obj(out)
  }

  /** `isObject(value) && isEmpty(value)`: a block, or a list, holding nothing. */
  private fun VegaValue.isEmptyBlock(): Boolean =
    when (this) {
      is VegaValue.Obj -> fields.isEmpty()
      is VegaValue.Arr -> values.isEmpty()
      else -> false
    }

  /**
   * The `subtitle` half of `extractTitleConfig`: the seven properties that stay in `config.title`.
   *
   * A **header** title has no subtitle, which is why these are the part that does not become a
   * style — Vega's title directive reads them from the configuration itself. Each is kept only
   * where it is truthy, `...(subtitleColor ? {subtitleColor} : {})`.
   */
  private fun subtitleProperties(value: VegaValue): VegaValue.Obj? {
    val block = value as? VegaValue.Obj ?: return null
    val fields = LinkedHashMap<String, VegaValue>()
    for (key in
      listOf(
        "subtitleColor",
        "subtitleFont",
        "subtitleFontSize",
        "subtitleFontStyle",
        "subtitleFontWeight",
        "subtitleLineHeight",
        "subtitlePadding",
      )) {
      block.fields[key]?.takeIf { it.isTruthy() }?.let { fields[key] = it }
    }
    return if (fields.isEmpty()) null else VegaValue.Obj(fields)
  }

  /** `mergeConfig`, for one style block: what the specification wrote wins, key by key. */
  private fun merged(seeded: VegaValue?, stated: VegaValue): VegaValue {
    val under = (seeded as? VegaValue.Obj)?.fields ?: return stated
    val over = (stated as? VegaValue.Obj)?.fields ?: return stated
    val fields = LinkedHashMap(under)
    fields.putAll(over)
    return VegaValue.Obj(fields)
  }

  /**
   * `subtitleMarkConfig`: the five properties a chart's **subtitle** inherits from its title.
   *
   * ```js
   * const subtitleMarkConfig = pick(titleConfig, ['align', 'baseline', 'dx', 'dy', 'limit']);
   * …
   * if (!isEmpty(subtitleMarkConfig)) {
   *   config.style['group-subtitle'] = {...config.style['group-subtitle'], ...subtitleMarkConfig};
   * }
   * ```
   *
   * A subtitle sits under the title and is nudged with it, so the placement carries over while the
   * type does not — `fontSize` and `fontWeight` stay the title's alone, and the subtitle's own
   * `subtitleFontSize` and its kin are left in `config.title` for the title directive to read.
   *
   * Writing only the `group-title` style left a chart that had moved its title fifty units across
   * with a subtitle still at the origin, under nothing.
   */
  private fun subtitleStyle(value: VegaValue): VegaValue.Obj? {
    val block = value as? VegaValue.Obj ?: return null
    val fields = LinkedHashMap<String, VegaValue>()
    for (key in listOf("align", "baseline", "dx", "dy", "limit")) {
      block.fields[key]?.let { fields[key] = it }
    }
    return if (fields.isEmpty()) null else VegaValue.Obj(fields)
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
    // ```js
    // const MARK_STYLES = new Set(['view', ...PRIMITIVE_MARKS]);
    // …
    // for (const markType of MARK_STYLES) {
    //   for (const prop of VL_ONLY_MARK_CONFIG_PROPERTIES) { delete config[markType][prop]; }
    //   const vlOnlyMarkSpecificConfigs =
    // VL_ONLY_ALL_MARK_SPECIFIC_CONFIG_PROPERTY_INDEX[markType];
    //   if (vlOnlyMarkSpecificConfigs) {
    //     for (const prop of vlOnlyMarkSpecificConfigs) { delete config[markType][prop]; }
    //   }
    //   redirectConfigToStyleConfig(config, markType);
    // }
    // ```
    //
    // **`view` is one of the mark blocks**, not a shape of its own: it is the first member of
    // `MARK_STYLES`, so it loses the generic Vega-Lite-only mark properties *as well as* the five
    // sizes that are its own entry in the mark-specific table. This dropped the sizes and kept the
    // rest, so `config.view.invalid` — meaningless to Vega, which has never heard of it — was
    // written into the `cell` style and shipped.
    val drop = VEGA_LITE_ONLY_MARK + MARK_SPECIFIC_VEGA_LITE_ONLY["view"].orEmpty()
    val fields = block.fields.filterKeys { it !in drop }
    return if (fields.isEmpty()) null else VegaValue.Obj(LinkedHashMap(fields))
  }

  private companion object {
    /** `DEFAULT_STEP`: one discrete step where nothing at all says otherwise. */
    const val DEFAULT_STEP = 20.0

    /**
     * `signalRefOrValue`: `{"expr": …}` is Vega-Lite's way of writing a signal, Vega's is
     * `{"signal": …}`, and whatever else was written beside it stays.
     */
    private fun asSignal(value: VegaValue): VegaValue {
      val stated = (value as? VegaValue.Obj)?.takeIf { it.has("expr") } ?: return value
      val expression = stated.string("expr") ?: return value
      return obj {
        put("signal", expression)
        stated.fields.forEach { (key, own) -> if (key != "expr") put(key, own) }
      }
    }

    /** One block's properties, each made a signal where it is an expression. */
    private fun signalled(block: VegaValue): VegaValue {
      val fields = (block as? VegaValue.Obj)?.fields ?: return block
      return obj { fields.forEach { (key, value) -> put(key, asSignal(value)) } }
    }

    /**
     * `configPropsWithExpr`: the blocks `initConfig` reads for expressions, and nothing else.
     *
     * A key not in this list passes through as written — `config.params` above all, which is the
     * one place an expression is a *parameter's* and not a property's.
     */
    private val EXPR_BLOCKS: Set<String> by lazy {
      MARK_TYPES +
        setOf("mark") +
        setOf(
          "axis",
          "axisBand",
          "axisBottom",
          "axisDiscrete",
          "axisLeft",
          "axisPoint",
          "axisQuantitative",
          "axisRight",
          "axisTemporal",
          "axisTop",
          "axisX",
          "axisXBand",
          "axisXDiscrete",
          "axisXPoint",
          "axisXQuantitative",
          "axisXTemporal",
          "axisY",
          "axisYBand",
          "axisYDiscrete",
          "axisYPoint",
          "axisYQuantitative",
          "axisYTemporal",
        ) +
        setOf("header", "headerRow", "headerColumn", "headerFacet") +
        setOf("legend", "scale", "title", "view")
    }

    /** The three read as a property rather than as a block of them. */
    private val EXPR_VALUES = setOf("background", "lineBreak", "padding")

    private fun withSignals(stated: VegaValue.Obj): VegaValue.Obj = obj {
      stated.fields.forEach { (key, value) ->
        when {
          key in EXPR_VALUES -> put(key, asSignal(value))
          // `config.style` is a block **of** blocks — one per named style — so the properties are
          // one level further down than everywhere else.
          key == "style" ->
            put(
              key,
              obj {
                (value as? VegaValue.Obj)?.fields.orEmpty().forEach { (name, own) ->
                  put(name, signalled(own))
                }
              },
            )
          // `replaceExprRef(invalid, {level: 1})`: what a scale does about an unplaceable value is
          // stated per channel, so its properties are a level down too. The rest of the scale
          // block is read as any other.
          key == "scale" ->
            put(
              key,
              obj {
                (value as? VegaValue.Obj)?.fields.orEmpty().forEach { (name, own) ->
                  put(name, if (name == "invalid") signalled(own) else asSignal(own))
                }
              },
            )
          key in EXPR_BLOCKS -> put(key, signalled(value))
          else -> put(key, value)
        }
      }
    }

    /**
     * `VL_ONLY_MARK_SPECIFIC_CONFIG_PROPERTY_INDEX`: what each *kind* of mark loses on top.
     *
     * These are the properties a mark's own compilation has already spent: a bar's band sizes have
     * become a width by the time anything is emitted, and a line's `point` has become a second
     * mark.
     */
    private val RECT_VEGA_LITE_ONLY =
      setOf("binSpacing", "continuousBandSize", "discreteBandSize", "minBandSize")

    val MARK_SPECIFIC_VEGA_LITE_ONLY: Map<String, Set<String>> =
      mapOf(
        // `VL_ONLY_ALL_MARK_SPECIFIC_CONFIG_PROPERTY_INDEX` opens with `view`, whose five sizes are
        // how a chart states its own default extent and mean nothing to Vega. It sits in the same
        // table as the marks' own because `view` is one of `MARK_STYLES` — the sizes are its entry
        // there, and the generic mark properties reach it by the same loop.
        "view" to
          setOf("continuousWidth", "continuousHeight", "discreteWidth", "discreteHeight", "step"),
        "area" to setOf("line", "point"),
        "line" to setOf("point"),
        "bar" to RECT_VEGA_LITE_ONLY,
        "rect" to RECT_VEGA_LITE_ONLY,
        "tick" to RECT_VEGA_LITE_ONLY + setOf("bandSize", "thickness"),
      )

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
      )

    /**
     * `VL_ONLY_LEGEND_CONFIG`: what a `config.legend` block loses on the way to Vega.
     *
     * ```ts
     * export const VL_ONLY_LEGEND_CONFIG: (keyof LegendConfig<any>)[] = [
     *   'gradientHorizontalMaxLength', 'gradientHorizontalMinLength',
     *   'gradientVerticalMaxLength', 'gradientVerticalMinLength', 'unselectedOpacity',
     * ];
     * ```
     *
     * Every one of them is spent before a specification is written: the four bounds by
     * `defaultGradientLength`, which turns them into a legend's own `gradientLength`, and
     * `unselectedOpacity` by the encoding a legend binding puts on its unpicked entries.
     */
    val VEGA_LITE_ONLY_LEGEND =
      setOf(
        "gradientHorizontalMaxLength",
        "gradientHorizontalMinLength",
        "gradientVerticalMaxLength",
        "gradientVerticalMinLength",
        "unselectedOpacity",
      )

    /**
     * `VL_ONLY_CONFIG_PROPERTIES`, **as upstream lists it** — and it is a list, not a rule.
     *
     * The temptation is to read it as "whatever only Vega-Lite understands", and every key here
     * does fit that reading, but the converse does not hold and the emitted configuration is
     * decided by the list rather than by the idea behind it. `fieldTitle` names the formatter a
     * guide's default title is written by — `switch (config.fieldTitle) { case 'plain': …}` in
     * `channeldef.ts`, which is as Vega-Lite a property as there is — and it is **not** on the
     * list, so it travels to Vega, which has no use for it. `timeFormatType` is the same, and so
     * are `headerRow`, `headerColumn` and `headerFacet` while `header` beside them is struck out.
     * Going the other way, the ten per-direction type-based axis blocks — `axisXBand` and its kin,
     * which this compiler reads itself in [axisConfigFamilies] — *are* on the list, where
     * `axisBand` is not and survives.
     *
     * Derived rather than copied, this dropped each of those five and passed each of those eleven
     * through, which is what a theme reaching the renderer with the wrong words in it looks like.
     *
     * Two keys are struck here that upstream strikes elsewhere, and they are not on this list
     * upstream:
     * - `font`, which `initConfig` destructures out of the configuration before any of this runs
     *   and turns into the derived style blocks seeded in [forVega].
     * - the three composite marks, deleted by the loop after this one, `for (const m of
     *   getAllCompositeMarks()) delete config[m]`.
     */
    val VEGA_LITE_ONLY =
      setOf(
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
        "countTitle",
        "header",
        "axisQuantitative",
        "axisTemporal",
        "axisDiscrete",
        "axisPoint",
        "axisXBand",
        "axisXPoint",
        "axisXDiscrete",
        "axisXQuantitative",
        "axisXTemporal",
        "axisYBand",
        "axisYPoint",
        "axisYDiscrete",
        "axisYQuantitative",
        "axisYTemporal",
        "scale",
        "selection",
        "overlay",
        // Struck by `initConfig` and by the composite-mark loop rather than by the list above.
        "font",
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
