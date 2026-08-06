package dev.aster.vega.runtime.compile

import dev.aster.vega.scene.SceneColor

/**
 * Vega's built-in axis appearance defaults.
 *
 * Read off upstream's own scenegraph for `test-fixtures/specs/bar.vg.json` rather than from
 * documentation, so they are what Vega actually draws. `config` overrides are not implemented,
 * which the parser reports.
 */
public object AxisDefaults {
  public const val TICK_SIZE: Double = 5.0
  public const val LABEL_PADDING: Double = 2.0
  public const val LABEL_FONT_SIZE: Double = 10.0
  public const val LABEL_FONT_FAMILY: String = "sans-serif"
  public const val TICK_WIDTH: Double = 1.0
  public const val DEFAULT_TICK_COUNT: Int = 10

  public val tickColor: SceneColor = SceneColor.parse("#888")!!
  public val domainColor: SceneColor = SceneColor.parse("#888")!!
  public val gridColor: SceneColor = SceneColor.parse("#ddd")!!
  public val labelColor: SceneColor = SceneColor.parse("#000")!!

  /**
   * Vega translates an axis group by half a pixel so its 1-pixel lines land on pixel centres, then
   * rounds each tick's position to a whole pixel in that translated space. Both are reproduced,
   * because axis coordinates are part of what differential tests compare.
   */
  public const val CRISP_OFFSET: Double = 0.5

  /**
   * Rounds a tick coordinate the way Vega does.
   *
   * JavaScript's `Math.round` rounds half away from zero for positives and towards zero for
   * negative halves; `kotlin.math.round` rounds half away from zero in both directions. Axis
   * coordinates are occasionally negative (a left axis's ticks), so the JavaScript behaviour is
   * spelled out here rather than assumed.
   */
  public fun crispRound(value: Double): Double = kotlin.math.floor(value + 0.5)

  /** Gap between the far edge of the ticks and labels and the axis title. */
  public const val TITLE_PADDING: Double = 4.0

  public const val TITLE_FONT_SIZE: Double = 11.0

  /** Axis and legend titles are bold; their labels are not. */
  public const val TITLE_FONT_WEIGHT: Int = 700

  /**
   * How far a title may be pushed away from its axis.
   *
   * Upstream clamps to `[0, 200]`, so an axis with pathologically long labels stops pushing its
   * title outwards rather than dragging the whole surface with it.
   */
  public const val TITLE_MIN_EXTENT: Double = 0.0
  public const val TITLE_MAX_EXTENT: Double = 200.0

  public val titleColor: SceneColor = SceneColor.parse("#000")!!
}
