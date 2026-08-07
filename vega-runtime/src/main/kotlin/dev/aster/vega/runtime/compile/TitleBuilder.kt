package dev.aster.vega.runtime.compile

import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.spec.Anchor
import dev.aster.vega.model.spec.Orient
import dev.aster.vega.model.spec.TitleSpec
import dev.aster.vega.scene.AccessibilityDescriptor
import dev.aster.vega.scene.Fill
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.NodeMetadata
import dev.aster.vega.scene.RectD
import dev.aster.vega.scene.SceneColor
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.SceneNodeIdAllocator
import dev.aster.vega.scene.TextAlign
import dev.aster.vega.scene.TextBaseline
import dev.aster.vega.scene.TextEngine
import dev.aster.vega.scene.TextNode
import dev.aster.vega.scene.TextRun
import dev.aster.vega.scene.TextStyle
import dev.aster.vega.scene.Transform2D
import dev.aster.vega.scene.transformedBounds

/** Vega's chart-title defaults, read out of `config.title` and the `group-title` styles. */
public object TitleDefaults {
  /** Gap between the drawing and the title. */
  public const val OFFSET: Double = 4.0

  public const val SUBTITLE_PADDING: Double = 3.0
  public const val FONT_SIZE: Double = 13.0
  public const val SUBTITLE_FONT_SIZE: Double = 12.0
  public const val FONT_WEIGHT: Int = 700

  /** The subtitle is not bold, which is the only thing distinguishing it besides size. */
  public const val SUBTITLE_FONT_WEIGHT: Int = 400

  public const val FONT_FAMILY: String = "sans-serif"

  public val color: SceneColor = SceneColor.parse("#000")!!

  /**
   * `frame: "group"` measures against the plotting area; anything else against the whole drawing.
   */
  public const val FRAME_GROUP: String = "group"
}

/**
 * Generates the chart title and subtitle.
 *
 * Placement is against the *content* — the marks, axes and legends already built — not against the
 * plotting area, unless `frame: "group"` says otherwise. That is why this runs last: a title
 * centres over the chart including its axes, so a chart with wide y-axis labels has its title
 * visibly off the plot's centre, and reproducing that means knowing how far everything else
 * reached.
 *
 * A left or right title turns a quarter turn. The rotation is applied to each text node about its
 * own anchor rather than to the group, which is upstream's arrangement and not an equivalent one:
 * the subtitle's anchor stays beside the title's in unrotated space and only the glyphs turn.
 */
internal class TitleBuilder(
  private val ids: SceneNodeIdAllocator,
  private val textEngine: TextEngine,
  @Suppress("unused") private val diagnostics: DiagnosticCollector,
  private val numbers: NumberResolver,
) {

  /**
   * @param content the bounds of everything else in this scope, in its coordinate space.
   * @param extent the plotting area, used when the title is framed to the group rather than the
   *   drawing.
   */
  fun build(spec: TitleSpec, content: RectD, extent: PlotSize): SceneNode {
    val offset = numbers.resolve(spec.offset, "title") ?: TitleDefaults.OFFSET
    val padding = numbers.resolve(spec.subtitlePadding, "title") ?: TitleDefaults.SUBTITLE_PADDING
    val fontSize = numbers.resolve(spec.fontSize, "title") ?: TitleDefaults.FONT_SIZE
    val subtitleFontSize =
      numbers.resolve(spec.subtitleFontSize, "title") ?: TitleDefaults.SUBTITLE_FONT_SIZE

    val angle =
      when (spec.orient) {
        Orient.LEFT -> -90.0
        Orient.RIGHT -> 90.0
        else -> 0.0
      }
    val align =
      when (spec.anchor) {
        Anchor.START -> TextAlign.LEFT
        Anchor.END -> TextAlign.RIGHT
        Anchor.MIDDLE -> TextAlign.CENTER
      }

    // A trellis header takes its words from the row it labels, so the text may be a signal.
    val text = spec.textExpression?.let { numbers.resolveText(it, "title") } ?: spec.text
    val title =
      TextNode(
        id = ids.allocate(),
        x = 0.0,
        y = 0.0,
        layout = textEngine.layout(run(text, fontSize, TitleDefaults.FONT_WEIGHT, align)),
        angleDegrees = angle,
        fill = Fill.of(TitleDefaults.color),
        metadata =
          NodeMetadata(
            role = "title-text",
            // A title is a guide like any other, and a screen reader is told which kind it is
            // rather than just being read the words.
            accessibility =
              AccessibilityDescriptor(
                label = "Title text '$text'",
                role = "graphics-symbol",
                focusable = true,
              ),
          ),
      )

    val children = mutableListOf<SceneNode>(title)
    spec.subtitle
      ?.takeIf { it.isNotEmpty() }
      ?.let { text ->
        // The subtitle is offset along whichever direction the title's own box grew in, which after
        // a
        // quarter turn is horizontal rather than vertical.
        val titleBounds = title.bounds
        val (sx, sy) =
          when (spec.orient) {
            Orient.LEFT -> titleBounds.width + padding to 0.0
            Orient.RIGHT -> -titleBounds.width - padding to 0.0
            else -> 0.0 to titleBounds.height + padding
          }
        children +=
          TextNode(
            id = ids.allocate(),
            x = sx,
            y = sy,
            layout =
              textEngine.layout(
                run(text, subtitleFontSize, TitleDefaults.SUBTITLE_FONT_WEIGHT, align)
              ),
            angleDegrees = angle,
            fill = Fill.of(TitleDefaults.color),
            metadata =
              NodeMetadata(
                role = "title-subtitle",
                accessibility =
                  AccessibilityDescriptor(
                    label = "Subtitle text '$text'",
                    role = "graphics-symbol",
                    focusable = true,
                  ),
              ),
          )
      }

    val box = children.fold(RectD.Empty) { acc, node -> acc.union(node.transformedBounds) }
    val frame =
      if (spec.frame == TitleDefaults.FRAME_GROUP) RectD(0.0, 0.0, extent.width, extent.height)
      else content

    val position = position(spec, frame, box, offset, extent)
    return GroupNode(
      id = ids.allocate(),
      children = children,
      transform = Transform2D.translate(position.first, position.second),
      metadata = NodeMetadata(role = "title"),
    )
  }

  /**
   * Where the title group sits.
   *
   * Two things are easy to get backwards. A left-oriented title's anchor runs *bottom to top*, so
   * `start` is the drawing's lower edge — the title reads upwards, and its start is where the
   * reader begins. And a right-oriented title is placed at `x2 + width`, not `x2`, because after
   * the quarter turn its own box extends in negative x from the anchor.
   */
  private fun position(
    spec: TitleSpec,
    frame: RectD,
    box: RectD,
    offset: Double,
    extent: PlotSize,
  ): Pair<Double, Double> {
    val bounds = if (frame.isEmpty) RectD(0.0, 0.0, extent.width, extent.height) else frame
    val (start, end) =
      when (spec.orient) {
        Orient.LEFT -> bounds.bottom to bounds.top
        Orient.RIGHT -> bounds.top to bounds.bottom
        else -> bounds.left to bounds.right
      }
    val along =
      when (spec.anchor) {
        Anchor.START -> start
        Anchor.END -> end
        Anchor.MIDDLE -> (start + end) / 2.0
      }
    return when (spec.orient) {
      Orient.TOP -> along to bounds.top - box.height - offset
      Orient.BOTTOM -> along to bounds.bottom + offset
      Orient.LEFT -> bounds.left - box.width - offset to along
      Orient.RIGHT -> bounds.right + box.width + offset to along
    }
  }

  private fun run(text: String, fontSize: Double, weight: Int, align: TextAlign) =
    TextRun(
      text = text,
      style =
        TextStyle(
          fontFamily = TitleDefaults.FONT_FAMILY,
          fontSize = fontSize,
          fontWeight = weight,
        ),
      align = align,
      baseline = TextBaseline.TOP,
    )
}
