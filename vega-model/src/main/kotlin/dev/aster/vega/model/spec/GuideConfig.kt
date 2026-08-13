package dev.aster.vega.model.spec

import dev.aster.vega.model.VegaValue

/**
 * A specification's `config` block, resolved into the defaults a guide reads behind its own
 * properties.
 *
 * This is where a Vega-Lite-compiled specification puts everything it does not say inline, so a
 * chart that ignores `config` is not a chart with a few missing options — it is a chart drawn in
 * somebody else's theme.
 *
 * The precedence runs, later winning, and was read out of upstream's `axis-config.js` and then
 * confirmed by setting the same property at every level and seeing which one drew:
 * ```
 * style["guide-label"] / style["guide-title"]   the weakest, and where Vega's own defaults live
 * config.axis
 * config.axisX  |  config.axisY                 by orientation's dimension
 * config.axisTop | axisBottom | axisLeft | axisRight
 * config.axisBand                               band scales only
 * the axis's own properties                     the strongest
 * ```
 *
 * A `style` block names its properties the way a *mark* does — `fill`, `font`, `fontSize` — and a
 * guide names them the way a *guide* does — `labelColor`, `labelFont`, `labelFontSize`. Upstream
 * translates between the two, and so does this: without it, `style: {"guide-label": {"fill": ...}}`
 * would set nothing, which is the form every Vega-Lite theme uses.
 */
public class GuideConfig(private val blocks: Map<String, VegaValue.Obj>) {

  /** The named `config` block, or an empty one. */
  public fun block(name: String): VegaValue.Obj = blocks[name] ?: EMPTY

  /**
   * A `config.style` block, over the two blocks Vega's own default configuration already fills in.
   *
   * `cell` and `view` are those two, and they exist for Vega-Lite: every chart it compiles carries
   * `"style": "cell"` on its root group and gets the plotting area's thin grey border from here,
   * without the specification mentioning a colour anywhere. A specification's own `config.style`
   * still wins, property by property.
   */
  private fun style(name: String): VegaValue.Obj {
    val declared = (blocks["style"]?.fields?.get(name) as? VegaValue.Obj)?.fields.orEmpty()
    val builtIn = BUILT_IN_STYLES[name]?.fields.orEmpty()
    if (builtIn.isEmpty()) return VegaValue.Obj(declared)
    return VegaValue.Obj(LinkedHashMap(builtIn).apply { putAll(declared) })
  }

  /**
   * The defaults behind one axis, weakest first.
   *
   * @param orient the axis's own orientation, which decides both which dimension block applies and
   *   which side block does.
   * @param band whether the axis's scale is a band scale, which is the only thing `axisBand` keys
   *   off — upstream's own correction for a band axis's centring bias lives there.
   */
  public fun axisDefaults(orient: Orient, band: Boolean): List<VegaValue.Obj> = buildList {
    add(guideStyleDefaults())
    add(block("axis"))
    add(block(if (orient.isVertical) "axisY" else "axisX"))
    add(block("axis" + orient.name.lowercase().replaceFirstChar { it.uppercase() }))
    if (band) add(block("axisBand"))
  }

  /**
   * A mark's defaults, split either side of the engine's own built-in per-type block.
   *
   * Upstream resolves them as `extend({}, config.mark, config[type])` and then the mark's `style`
   * names in order — but its *default* configuration already fills `config[type]` in, with a rect's
   * blue and a symbol's size of 64. So `config.mark` sits **below** those built-ins and everything
   * else sits above, which is why setting `config.mark.fill` does not recolour a rect and setting
   * `config.rect.fill` does.
   *
   * @return the block that loses to the built-ins, then the one that beats them.
   */
  public fun markDefaults(type: String, styles: List<String>): Pair<VegaValue.Obj, VegaValue.Obj> {
    val above = LinkedHashMap<String, VegaValue>()
    above.putAll(block(type).fields)
    for (name in styles) above.putAll(styleBlock(name).fields)
    return block("mark") to VegaValue.Obj(above)
  }

  /** A named `config.style` block, which a mark opts into through its own `style` property. */
  public fun styleBlock(name: String): VegaValue.Obj = style(name)

  /**
   * The named style blocks alone, merged in order, with nothing from `config.mark` beneath them.
   *
   * This is what the chart's own group takes. A group mark reads `config.mark` as well, but the
   * chart's frame does not: it is not a mark anybody wrote, so a `config.mark.fill` meant for the
   * bars would otherwise paint the whole plotting area with it.
   */
  public fun styleDefaults(styles: List<String>): VegaValue.Obj {
    if (styles.isEmpty()) return EMPTY
    val fields = LinkedHashMap<String, VegaValue>()
    for (name in styles) fields.putAll(styleBlock(name).fields)
    return VegaValue.Obj(fields)
  }

  /**
   * A `config.range` entry, or null when the configuration does not name that range.
   *
   * The value is whatever the theme wrote — a scheme object, a literal array, a step — because
   * upstream substitutes it for the name and reads the result as an ordinary `range`. That is why
   * `config.range.category` may be a `{"scheme": ...}` where the built-in default is a list of
   * symbol names: the two are the same property, not two kinds of thing.
   */
  public fun rangeDefault(name: String): VegaValue? = block("range").fields[name]

  /** A legend has one block, over the same guide styles. */
  public fun legendDefaults(): List<VegaValue.Obj> = listOf(guideStyleDefaults(), block("legend"))

  /**
   * `config.title`, over the style block Vega keeps its own heading defaults in.
   *
   * The style names its properties the way a *mark* does — `fill`, not `color` — so it is
   * translated on the way through, exactly as the guide styles are. This is where a Vega-Lite
   * theme's title colour arrives: its compiler redirects `config.title.color` into
   * `style["group-title"].fill`, and reading only `config.title` leaves a themed title black.
   *
   * @param style the title's own `style`, which *replaces* `group-title` rather than adding to it —
   *   upstream's `guideMark` assigns `mark.style = extras.style || mark.style`, so a trellis header
   *   asking for `guide-label` is set at a label's ten points and not a heading's thirteen.
   */
  /**
   * A title's own configuration block.
   *
   * The `group-title` style beneath it is *not* here: `titleStyleLayers` supplies whichever style
   * blocks the title names, `group-title` among them, and layering one here as well outranked a
   * title that had named a narrower style — which is how every trellis caption came out at a
   * heading's thirteen points rather than a label's ten.
   */
  public fun titleDefaults(): List<VegaValue.Obj> = listOf(block("title"))

  /**
   * `style["guide-label"]` and `style["guide-title"]` rewritten in guide property names.
   *
   * These carry Vega's own label and title defaults — black, sans-serif, 10 and 11 point, the title
   * bold — so a theme that replaces them replaces them for every axis and legend at once.
   */
  private fun guideStyleDefaults(): VegaValue.Obj {
    val fields = LinkedHashMap<String, VegaValue>()
    fields += prefixed(style("guide-label"), "label")
    fields += prefixed(style("guide-title"), "title")
    return VegaValue.Obj(fields)
  }

  public companion object {
    public val Empty: GuideConfig = GuideConfig(emptyMap())

    private val EMPTY = VegaValue.Obj(emptyMap())

    /**
     * Upstream's own `config.style` blocks, which a specification can override but rarely defines.
     *
     * They are not decoration. `cell` is what makes a `style: "cell"` group *painted* — a
     * transparent fill and a light grey outline — and a group that paints nothing is not a mark at
     * all, so a chart laid out from styled cells came out two marks short of upstream's. `point`,
     * `circle` and `square` are the Vega-Lite symbol styles, and they carry a **size of 30** where
     * a bare symbol's is 100; they are reached by *name* rather than by mark type, a specification
     * saying `"style": ["point"]` and getting a symbol a third the size stroked twice as thick. The
     * rest of Vega's default configuration lives in `MarkDefaults`, next to the built-in
     * per-mark-type values it belongs with.
     *
     * The four guide styles carry the font every guide is drawn in. This engine also holds those
     * numbers as its own axis, legend and title defaults, which is one number in two places — but
     * they are needed *here* because anything may **name** a guide style: a group mark's `title`
     * does exactly that, and a trellis header drawn at a heading's thirteen points instead of a
     * label's ten is both the wrong size and, being measured, the wrong amount of chart.
     */
    private val BUILT_IN_STYLES: Map<String, VegaValue.Obj> =
      mapOf(
        // axis and legend labels
        "guide-label" to
          VegaValue.Obj(
            linkedMapOf(
              "fill" to VegaValue.Str("#000"),
              "font" to VegaValue.Str("sans-serif"),
              "fontSize" to VegaValue.Num(10.0),
            )
          ),
        // axis and legend titles
        "guide-title" to
          VegaValue.Obj(
            linkedMapOf(
              "fill" to VegaValue.Str("#000"),
              "font" to VegaValue.Str("sans-serif"),
              "fontSize" to VegaValue.Num(11.0),
              "fontWeight" to VegaValue.Str("bold"),
            )
          ),
        // headers, including the chart's own title
        "group-title" to
          VegaValue.Obj(
            linkedMapOf(
              "fill" to VegaValue.Str("#000"),
              "font" to VegaValue.Str("sans-serif"),
              "fontSize" to VegaValue.Num(13.0),
              "fontWeight" to VegaValue.Str("bold"),
            )
          ),
        "group-subtitle" to
          VegaValue.Obj(
            linkedMapOf(
              "fill" to VegaValue.Str("#000"),
              "font" to VegaValue.Str("sans-serif"),
              "fontSize" to VegaValue.Num(12.0),
            )
          ),
        "point" to
          VegaValue.Obj(
            linkedMapOf(
              "size" to VegaValue.Num(30.0),
              "strokeWidth" to VegaValue.Num(2.0),
              "shape" to VegaValue.Str("circle"),
            )
          ),
        "circle" to
          VegaValue.Obj(
            linkedMapOf("size" to VegaValue.Num(30.0), "strokeWidth" to VegaValue.Num(2.0))
          ),
        "square" to
          VegaValue.Obj(
            linkedMapOf(
              "size" to VegaValue.Num(30.0),
              "strokeWidth" to VegaValue.Num(2.0),
              "shape" to VegaValue.Str("square"),
            )
          ),
        "cell" to
          VegaValue.Obj(
            linkedMapOf(
              "fill" to VegaValue.Str("transparent"),
              // Vega's `lightGray`. The border a Vega-Lite plotting area is drawn inside.
              "stroke" to VegaValue.Str("#ddd"),
            )
          ),
        "view" to VegaValue.Obj(linkedMapOf("fill" to VegaValue.Str("transparent"))),
      )

    /** `fill` becomes `{prefix}Color`; everything else takes the prefix and keeps its own name. */
    private fun prefixed(style: VegaValue.Obj, prefix: String): Map<String, VegaValue> {
      val result = LinkedHashMap<String, VegaValue>(style.fields.size)
      for ((key, value) in style.fields) {
        val name =
          when (key) {
            "fill" -> "${prefix}Color"
            else -> prefix + key.replaceFirstChar { it.uppercase() }
          }
        result[name] = value
      }
      return result
    }

    /**
     * Merges the defaults under a guide's own properties.
     *
     * The result is read by the ordinary property readers, so a config default is indistinguishable
     * from something the specification wrote — which is the point, and also why anything the parser
     * *reports* is checked against the guide's own object rather than this one. A theme should not
     * make every axis in a chart complain about a property it does not use.
     */
    public fun merge(own: VegaValue.Obj, defaults: List<VegaValue.Obj>): VegaValue.Obj {
      if (defaults.all { it.fields.isEmpty() }) return own
      val fields = LinkedHashMap<String, VegaValue>()
      for (block in defaults) fields.putAll(block.fields)
      fields.putAll(own.fields)
      return VegaValue.Obj(fields)
    }
  }
}
