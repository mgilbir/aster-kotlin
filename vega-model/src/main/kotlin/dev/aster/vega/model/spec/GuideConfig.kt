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

  private fun style(name: String): VegaValue.Obj =
    (blocks["style"]?.fields?.get(name) as? VegaValue.Obj) ?: EMPTY

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

  /** A legend has one block, over the same guide styles. */
  public fun legendDefaults(): List<VegaValue.Obj> = listOf(guideStyleDefaults(), block("legend"))

  /** `config.title` — the chart's own heading, which has no guide-style layer beneath it. */
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
