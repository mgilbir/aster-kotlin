package dev.aster.vega.runtime.compile

import dev.aster.vega.model.spec.MarkType
import dev.aster.vega.scene.SceneColor

/**
 * Vega's built-in per-mark appearance defaults.
 *
 * Read off upstream's own scenegraph for a mark encoded with position only, rather than from
 * documentation. They matter more than they look: a specification that omits `fill` is common, and
 * without these a chart renders as invisible outlines instead of the blue Vega actually draws.
 *
 * `config.mark` overrides are not implemented; the parser reports a `config` block.
 */
public object MarkDefaults {

  /** Vega's default mark colour. */
  public val DEFAULT_FILL: SceneColor = SceneColor.parse("#4c78a8")!!

  /** Text and rules default to black instead. */
  public val TEXT_FILL: SceneColor = SceneColor.parse("#000")!!
  public val RULE_STROKE: SceneColor = SceneColor.parse("#000")!!

  public const val SYMBOL_SIZE: Double = 64.0
  public const val TEXT_FONT_FAMILY: String = "sans-serif"
  public const val TEXT_FONT_SIZE: Double = 11.0

  /** A line is stroked at 2, not the 1 that every other stroked mark uses. */
  public const val LINE_STROKE_WIDTH: Double = 2.0
  public const val STROKE_WIDTH: Double = 1.0

  /** The fill a mark of [type] gets when the specification does not set one. */
  public fun fillFor(type: MarkType): SceneColor? =
    when (type) {
      MarkType.RECT,
      MarkType.SYMBOL,
      MarkType.AREA,
      MarkType.ARC,
      MarkType.PATH,
      MarkType.SHAPE -> DEFAULT_FILL
      MarkType.TEXT -> TEXT_FILL
      // A line is stroked, not filled, and a group's fill is genuinely absent by default.
      MarkType.LINE,
      MarkType.RULE,
      MarkType.GROUP,
      MarkType.IMAGE,
      MarkType.TRAIL -> null
    }

  /** The stroke a mark of [type] gets when the specification does not set one. */
  public fun strokeFor(type: MarkType): SceneColor? =
    when (type) {
      MarkType.LINE -> DEFAULT_FILL
      MarkType.RULE -> RULE_STROKE
      else -> null
    }

  public fun strokeWidthFor(type: MarkType): Double =
    if (type == MarkType.LINE) LINE_STROKE_WIDTH else STROKE_WIDTH
}
