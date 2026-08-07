package dev.aster.vega.runtime.compile

import dev.aster.vega.scene.SceneColor

/**
 * Vega's built-in legend appearance and layout constants.
 *
 * Read out of upstream's `config.legend` and the `guide-label` / `guide-title` styles rather than
 * from documentation, and cross-checked against the scenegraph a legend actually produces. They are
 * not cosmetic: legend layout is entirely arithmetic on these numbers, so one wrong constant moves
 * every entry.
 *
 * `config.legend` overrides are not implemented; the parser reports a `config` block.
 */
public object LegendDefaults {

  /** Gap between the plotting area and a legend placed against one of its edges. */
  public const val OFFSET: Double = 18.0

  /** Space inside the legend, around its content. Zero by default, which surprises people. */
  public const val PADDING: Double = 0.0

  /** Gap between two legends that share an orientation. Upstream calls this the layout margin. */
  public const val MARGIN: Double = 8.0

  public const val TITLE_PADDING: Double = 5.0
  public const val TITLE_FONT_SIZE: Double = 11.0

  /** Legend and axis titles are bold; their labels are not. */
  public const val TITLE_FONT_WEIGHT: Int = 700

  public const val LABEL_FONT_SIZE: Double = 10.0

  /** As for an axis, but 160 rather than 180 — upstream's `config.legend` carries its own. */
  public const val LABEL_LIMIT: Double = 160.0
  public const val LABEL_OFFSET: Double = 4.0
  public const val FONT_FAMILY: String = "sans-serif"

  public const val ROW_PADDING: Double = 2.0
  public const val COLUMN_PADDING: Double = 10.0

  public const val SYMBOL_SIZE: Double = 100.0
  public const val SYMBOL_STROKE_WIDTH: Double = 1.5
  public const val SYMBOL_OFFSET: Double = 0.0

  public const val GRADIENT_LENGTH: Double = 200.0
  public const val GRADIENT_THICKNESS: Double = 16.0
  public const val GRADIENT_LABEL_OFFSET: Double = 2.0
  public const val GRADIENT_STROKE_WIDTH: Double = 0.0

  /** How many stops a gradient swatch is sampled at: upstream's `scale.ticks(15)` plus the ends. */
  public const val GRADIENT_STOP_COUNT: Int = 15

  /** Entries a symbol legend generates for a continuous scale when the count is unstated. */
  public const val SYMBOL_TICK_COUNT: Int = 5

  public val labelColor: SceneColor = SceneColor.parse("#000")!!
  public val titleColor: SceneColor = SceneColor.parse("#000")!!
  public val gradientStrokeColor: SceneColor = SceneColor.parse("#ddd")!!

  /**
   * A legend symbol's fill and stroke when the legend does not encode them.
   *
   * A legend for a `size` or `shape` scale has to draw something, and upstream draws an unfilled
   * grey outline rather than a solid swatch that would imply a colour the scale never assigned.
   */
  public val symbolBaseStrokeColor: SceneColor = SceneColor.parse("#888")!!
}
