package dev.aster.vega.runtime.compile

import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.spec.Anchor
import dev.aster.vega.model.spec.Orient
import dev.aster.vega.model.spec.TitleSpec
import dev.aster.vega.scene.AccessibilityDescriptor
import dev.aster.vega.scene.Fill
import dev.aster.vega.scene.FontStyle
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.NodeMetadata
import dev.aster.vega.scene.RectD
import dev.aster.vega.scene.SceneColor
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.SceneNodeIdAllocator
import dev.aster.vega.scene.ScenePaint
import dev.aster.vega.scene.Stroke
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
  /**
   * Resolves a title's own `encode` channels, which have no property behind them.
   *
   * A heading may be coloured or sized from a signal, or have a panel painted behind it, and none
   * of those can be said with a title property. Optional for the same reason the axis builder's is:
   * a title built without one simply has no encode to resolve.
   */
  private val channels: MarkEncoder? = null,
) {

  /**
   * One channel of one part of a title's `encode`, as a number or as text.
   *
   * `update` beats `enter`, which is upstream's effective set for a single render. The datum is
   * empty: a title's data source is upstream's one-element `Collect(null, [{}])`, so an expression
   * here reads signals and nothing else.
   */
  private fun number(spec: TitleSpec, part: String, name: String): Double? {
    val encoder = channels ?: return null
    val entry = spec.encode[part]?.effective?.get(name) ?: return null
    return encoder.channelNumber(entry, EMPTY_DATUM)?.takeIf { it.isFinite() }
  }

  private fun text(spec: TitleSpec, part: String, name: String): String? {
    val encoder = channels ?: return null
    val entry = spec.encode[part]?.effective?.get(name) ?: return null
    return encoder.channelText(entry, EMPTY_DATUM)?.takeIf { it.isNotEmpty() }
  }

  private fun colour(spec: TitleSpec, part: String, name: String): SceneColor? =
    text(spec, part, name)?.let { SceneColor.parse(it) }

  /**
   * @param content the bounds of everything else in this scope, in its coordinate space.
   * @param extent the plotting area, used when the title is framed to the group rather than the
   *   drawing.
   */
  private companion object {
    /** A title has one datum and it is empty, so an encode expression sees only signals. */
    val EMPTY_DATUM: VegaValue = VegaValue.Obj(emptyMap())
  }

  fun build(spec: TitleSpec, content: RectD, extent: PlotSize): SceneNode {
    val offset = numbers.resolve(spec.offset, "title") ?: TitleDefaults.OFFSET
    val padding = numbers.resolve(spec.subtitlePadding, "title") ?: TitleDefaults.SUBTITLE_PADDING
    // A title's `encode` overrides the property for the same channel, which is upstream's order:
    // `extendEncode` lets the specification's entry win over the one the guide wrote.
    val fontSize =
      number(spec, "title", "fontSize")
        ?: numbers.resolve(spec.fontSize, "title")
        ?: TitleDefaults.FONT_SIZE
    val subtitleFontSize =
      number(spec, "subtitle", "fontSize")
        ?: numbers.resolve(spec.subtitleFontSize, "title")
        ?: TitleDefaults.SUBTITLE_FONT_SIZE

    // The derived angle and alignment, which an explicit `angle` or `align` overrides: upstream
    // writes these into the title's `enter` block and the explicit ones into `update`.
    val angle =
      number(spec, "title", "angle")
        ?: numbers.resolve(spec.angle, "title")
        ?: when (spec.orient) {
          Orient.LEFT -> -90.0
          Orient.RIGHT -> 90.0
          else -> 0.0
        }
    val align =
      alignOf(text(spec, "title", "align"))
        ?: alignOf(spec.align)
        ?: when (spec.anchor) {
          Anchor.START -> TextAlign.LEFT
          Anchor.END -> TextAlign.RIGHT
          Anchor.MIDDLE -> TextAlign.CENTER
        }
    val baseline =
      baselineOf(text(spec, "title", "baseline")) ?: baselineOf(spec.baseline) ?: TextBaseline.TOP
    val limit = number(spec, "title", "limit") ?: numbers.resolve(spec.limit, "title") ?: 0.0
    val colour =
      colour(spec, "title", "fill")
        ?: spec.color?.let { SceneColor.parse(it) }
        ?: TitleDefaults.color
    val subtitleColour =
      colour(spec, "subtitle", "fill")
        ?: spec.subtitleColor?.let { SceneColor.parse(it) }
        ?: TitleDefaults.color

    // A trellis header takes its words from the row it labels, so the text may be a signal.
    val text =
      text(spec, "title", "text")
        ?: spec.textExpression?.let { numbers.resolveText(it, "title") }
        ?: spec.text
    // `dx`/`dy` shift the title after the anchor has placed it, and they move the surface with it:
    // a heading nudged one unit left to line up with an axis makes the whole drawing one unit
    // wider.
    val nudgeX = number(spec, "title", "dx") ?: numbers.resolve(spec.dx, "title") ?: 0.0
    val nudgeY = number(spec, "title", "dy") ?: numbers.resolve(spec.dy, "title") ?: 0.0
    val title =
      TextNode(
        id = ids.allocate(),
        x = nudgeX,
        y = nudgeY,
        layout =
          textEngine.layout(
            run(
              text,
              fontSize,
              weightOf(text(spec, "title", "fontWeight")) ?: titleWeight(spec),
              align,
              styleOf(text(spec, "title", "fontStyle") ?: spec.fontStyle),
              baseline,
              text(spec, "title", "font") ?: spec.font,
              number(spec, "title", "lineHeight") ?: numbers.resolve(spec.lineHeight, "title"),
              limit,
            )
          ),
        angleDegrees = angle,
        fill = Fill(ScenePaint.Solid(colour), opacityOf(spec, "title")),
        metadata =
          NodeMetadata(
            role = "title-text",
            markName = spec.name,
            interactive = spec.interactive,
            // A title is a guide like any other, and a screen reader is told which kind it is
            // rather than just being read the words. `aria: false` takes it out of the tree
            // altogether, which is what a decorative heading wants and the only way to say so.
            accessibility =
              if (!spec.aria) {
                null
              } else {
                AccessibilityDescriptor(
                  // Upstream's caption is `array(text).join(' ')`, so a two-line heading is read
                  // out as one sentence rather than with the break in it.
                  label = "Title text '${text.replace('\n', ' ')}'",
                  role = "graphics-symbol",
                  roleDescription = "title",
                  focusable = true,
                )
              },
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
                run(
                  text,
                  subtitleFontSize,
                  weightOf(text(spec, "subtitle", "fontWeight"))
                    ?: weightOf(spec.subtitleFontWeight)
                    ?: TitleDefaults.SUBTITLE_FONT_WEIGHT,
                  alignOf(text(spec, "subtitle", "align")) ?: align,
                  styleOf(text(spec, "subtitle", "fontStyle") ?: spec.subtitleFontStyle),
                  baselineOf(text(spec, "subtitle", "baseline")) ?: baseline,
                  // **Not** `?: spec.font`. A subtitle never inherits the title's font family,
                  // from an explicit `title.font`, from `config.title.font`, or from a `style`
                  // block — verified against all three. Falling back to it put a `style` that set
                  // `font: "serif"` on the heading into the subtitle as well, where upstream leaves
                  // it sans-serif. The other subtitle properties already knew this; only `font`
                  // reached across.
                  text(spec, "subtitle", "font") ?: spec.subtitleFont,
                  number(spec, "subtitle", "lineHeight")
                    ?: numbers.resolve(spec.subtitleLineHeight, "title"),
                  number(spec, "subtitle", "limit") ?: limit,
                )
              ),
            angleDegrees = number(spec, "subtitle", "angle") ?: angle,
            fill = Fill(ScenePaint.Solid(subtitleColour), opacityOf(spec, "subtitle")),
            metadata =
              NodeMetadata(
                role = "title-subtitle",
                markName = spec.name,
                interactive = spec.interactive,
                accessibility =
                  if (!spec.aria) {
                    null
                  } else {
                    AccessibilityDescriptor(
                      label = "Subtitle text '${text.replace('\n', ' ')}'",
                      role = "graphics-symbol",
                      roleDescription = "subtitle",
                      focusable = true,
                    )
                  },
              ),
          )
      }

    val box = children.fold(RectD.Empty) { acc, node -> acc.union(node.transformedBounds) }
    // `frame` decides what the title is *anchored along* — the plotting area under `"group"`, the
    // whole drawing otherwise. It does **not** decide how far out the title sits: upstream's
    // `titleLayout` reads `frame` only for the anchor and always measures the gap from
    // `viewBounds`, so a title over a chart whose marks overflow their plot clears the marks
    // whichever frame it names.
    val anchorFrame =
      if (spec.frame == TitleDefaults.FRAME_GROUP) RectD(0.0, 0.0, extent.width, extent.height)
      else content

    val position = position(spec, anchorFrame, content, box, offset, extent)
    return GroupNode(
      id = ids.allocate(),
      children = children,
      transform = Transform2D.translate(position.first, position.second),
      // A `group` encode block paints the panel the heading sits in. Nothing else can: a title has
      // no `fillColor` the way a legend does, so this block is the only way to put a band of colour
      // behind a chart's name.
      fill =
        colour(spec, "group", "fill")?.let {
          Fill(ScenePaint.Solid(it), opacityOf(spec, "group"))
        },
      stroke = colour(spec, "group", "stroke")?.let { Stroke(paint = ScenePaint.Solid(it)) },
      cornerRadius = number(spec, "group", "cornerRadius") ?: 0.0,
      // The heading measures as its words, whatever is painted behind them: upstream's
      // `titleLayout`
      // overwrites the group's bounds with the union of its texts', so the outline it drew round a
      // rectangle of no size does not push the surface half a unit wider.
      boundsFromChildren = true,
      metadata = NodeMetadata(role = "title"),
    )
  }

  /**
   * A part's opacity, from `fillOpacity` or from the item's own `opacity`.
   *
   * Upstream keeps them apart — `opacity` fades the whole item, `fillOpacity` only what is inside —
   * but a title's text has no outline, so for these marks the two land in the same place.
   */
  private fun opacityOf(spec: TitleSpec, part: String): Double =
    number(spec, part, "fillOpacity") ?: number(spec, part, "opacity") ?: 1.0

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
    /** What the title is anchored along; `frame` chooses it. */
    frame: RectD,
    /** How far the drawing reaches, which always decides the gap. */
    content: RectD,
    box: RectD,
    offset: Double,
    extent: PlotSize,
  ): Pair<Double, Double> {
    val plot = RectD(0.0, 0.0, extent.width, extent.height)
    val bounds = if (frame.isEmpty) plot else frame
    val reach = if (content.isEmpty) plot else content
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
      Orient.TOP -> along to reach.top - box.height - offset
      Orient.BOTTOM -> along to reach.bottom + offset
      Orient.LEFT -> reach.left - box.width - offset to along
      Orient.RIGHT -> reach.right + box.width + offset to along
    }
  }

  /** The title's own `fontWeight`, or a theme's, falling back to Vega's bold default. */
  private fun titleWeight(spec: TitleSpec): Int =
    weightOf(spec.fontWeight) ?: TitleDefaults.FONT_WEIGHT

  /** A CSS font weight, named or numeric. */
  private fun weightOf(named: String?): Int? = named?.let {
    when (it.lowercase()) {
      "normal" -> 400
      "bold" -> 700
      "lighter" -> 300
      "bolder" -> 800
      else -> it.toIntOrNull()
    }
  }

  /** `"italic"` slants the face; anything else, including nothing, leaves it upright. */
  private fun styleOf(name: String?): FontStyle =
    if (name.equals("italic", ignoreCase = true)) FontStyle.ITALIC else FontStyle.NORMAL

  private fun alignOf(name: String?): TextAlign? =
    when (name?.lowercase()) {
      "left" -> TextAlign.LEFT
      "center" -> TextAlign.CENTER
      "right" -> TextAlign.RIGHT
      else -> null
    }

  private fun baselineOf(name: String?): TextBaseline? =
    when (name?.lowercase()) {
      "top" -> TextBaseline.TOP
      "middle" -> TextBaseline.MIDDLE
      "bottom" -> TextBaseline.BOTTOM
      "alphabetic" -> TextBaseline.ALPHABETIC
      "line-top" -> TextBaseline.LINE_TOP
      "line-bottom" -> TextBaseline.LINE_BOTTOM
      else -> null
    }

  private fun run(
    text: String,
    fontSize: Double,
    weight: Int,
    align: TextAlign,
    fontStyle: FontStyle = FontStyle.NORMAL,
    baseline: TextBaseline = TextBaseline.TOP,
    fontFamily: String? = null,
    lineHeight: Double? = null,
    limit: Double = 0.0,
  ) =
    TextRun(
      text = text,
      style =
        TextStyle(
          fontFamily = fontFamily ?: TitleDefaults.FONT_FAMILY,
          fontSize = fontSize,
          fontWeight = weight,
          fontStyle = fontStyle,
          lineHeight = lineHeight,
        ),
      align = align,
      baseline = baseline,
      limit = limit,
    )
}
