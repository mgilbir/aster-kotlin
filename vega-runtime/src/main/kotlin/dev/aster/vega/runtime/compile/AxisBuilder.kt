package dev.aster.vega.runtime.compile

import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.spec.Anchor
import dev.aster.vega.model.spec.AxisSpec
import dev.aster.vega.model.spec.Orient
import dev.aster.vega.runtime.scale.BandScale
import dev.aster.vega.runtime.scale.LinearScale
import dev.aster.vega.runtime.scale.PointScale
import dev.aster.vega.runtime.scale.PositionScale
import dev.aster.vega.runtime.scale.TimeScale
import dev.aster.vega.runtime.scale.TransformedScale
import dev.aster.vega.runtime.scale.VegaScale
import dev.aster.vega.scene.Fill
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.NodeMetadata
import dev.aster.vega.scene.RectD
import dev.aster.vega.scene.RuleNode
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

/**
 * Generates axis scene nodes: ticks, labels, gridlines and the domain line.
 *
 * Geometry follows what upstream Vega actually emits, established by inspecting its scenegraph:
 * - the axis group is translated by half a pixel so 1-pixel lines land on pixel centres
 * - a tick's coordinate is its scale position rounded to a whole pixel in that translated space
 * - a band scale's positions are the band centres, shifted back half a pixel, so labels centre on
 *   the band while ticks stay crisp
 *
 * Three different sizes govern an axis, which is invisible at the top level because they coincide
 * there and only diverges inside a group mark. Established by reading upstream's own axis layout:
 * - the axis group is *placed* at the enclosing group's `width`/`height` — its encoded extent
 * - a gridline is as long as the `width`/`height` **signals**, which a group inherits from the
 *   chart unless it declares its own
 * - the domain line spans the scale's own range, not the plotting area
 *
 * The title is placed against a fourth quantity: the reach of the ticks and labels only. Gridlines
 * and the domain line are excluded, so turning on a grid does not push the title away from the
 * axis.
 */
public class AxisBuilder(
  private val scales: Map<String, VegaScale>,
  private val ids: SceneNodeIdAllocator,
  private val textEngine: TextEngine,
  private val diagnostics: DiagnosticCollector,
  /** Resolves axis properties that a specification supplied as signals. */
  private val numbers: NumberResolver,
) {

  /** One tick's label text and its position along the axis. */
  private data class Tick(val label: String, val position: Double)

  /**
   * An axis, and the rectangle everything else measures it by.
   *
   * The two are not the same, and the difference is upstream's rather than an approximation.
   * [guideBounds] covers the axis's whole *extent* — the full length of the scale range, and the
   * depth its ticks and labels reach — where the node's own bounds are the union of the items
   * actually drawn. An axis whose ticks stop short of its domain therefore still measures its full
   * length, so a chart's size does not wobble as tick values come and go. The half-pixel crisp
   * offset the node carries is excluded too, because anything that rounds these bounds outwards
   * turns half a pixel into a whole unit of displacement.
   */
  public data class BuiltAxis(val node: SceneNode, val guideBounds: RectD)

  /**
   * @param extent the enclosing group's encoded size, which positions a bottom or right axis.
   * @param gridSize the `width`/`height` signals in scope, which set how long a gridline is.
   */
  public fun build(spec: AxisSpec, extent: PlotSize, gridSize: PlotSize = extent): BuiltAxis? {
    val scale = scales[spec.scale]
    if (scale == null) {
      diagnostics.error(
        DiagnosticCodes.SCALE_UNSUPPORTED_TYPE,
        "Axis refers to scale '${spec.scale}', which was not built; the axis was skipped",
        operator = spec.scale,
      )
      return null
    }

    val ticks = ticksFor(scale, spec)
    if (ticks == null) {
      diagnostics.error(
        DiagnosticCodes.SCALE_UNSUPPORTED_TYPE,
        "Cannot generate ticks for scale '${spec.scale}'; the axis was skipped",
        operator = spec.scale,
      )
      return null
    }

    val tickSize = numbers.resolve(spec.tickSize, spec.scale) ?: AxisDefaults.TICK_SIZE
    val labelPadding = numbers.resolve(spec.labelPadding, spec.scale) ?: AxisDefaults.LABEL_PADDING
    // An axis with its ticks switched off pulls its labels in by the tick size: there is nothing
    // for
    // them to clear. Upstream passes 0 in place of the tick size rather than keeping the gap.
    val labelOffset = (if (spec.ticks) tickSize else 0.0) + labelPadding
    val fontSize = numbers.resolve(spec.labelFontSize, spec.scale) ?: AxisDefaults.LABEL_FONT_SIZE
    val labelStyle = TextStyle(fontFamily = AxisDefaults.LABEL_FONT_FAMILY, fontSize = fontSize)

    val children = mutableListOf<SceneNode>()
    // Ticks and labels only. Upstream measures an axis by these two and skips the gridlines, so
    // switching a grid on does not widen the chart or push the axis title away.
    val measured = mutableListOf<SceneNode>()
    val tickStroke =
      Stroke(paint = ScenePaint.Solid(AxisDefaults.tickColor), width = AxisDefaults.TICK_WIDTH)
    val gridStroke =
      Stroke(paint = ScenePaint.Solid(AxisDefaults.gridColor), width = AxisDefaults.TICK_WIDTH)

    // A gridline runs back across the plot, away from the side its axis is on: up from a bottom
    // axis, down from a top one, right from a left one, left from a right one.
    val gridSign = if (spec.orient == Orient.TOP || spec.orient == Orient.LEFT) 1.0 else -1.0

    // Gridlines first so they sit under the ticks, matching Vega's ordering.
    if (spec.grid) {
      val gridMeta = NodeMetadata(role = "axis-grid")
      for (tick in ticks) {
        val at = AxisDefaults.crispRound(tick.position)
        children +=
          when (spec.orient) {
            Orient.BOTTOM,
            Orient.TOP ->
              RuleNode(
                ids.allocate(),
                at,
                0.0,
                at,
                gridSign * gridSize.height,
                gridStroke,
                metadata = gridMeta,
              )
            Orient.LEFT,
            Orient.RIGHT ->
              RuleNode(
                ids.allocate(),
                0.0,
                at,
                gridSign * gridSize.width,
                at,
                gridStroke,
                metadata = gridMeta,
              )
          }
      }
    }

    if (spec.ticks) {
      val tickMeta = NodeMetadata(role = "axis-tick")
      for (tick in ticks) {
        val at = AxisDefaults.crispRound(tick.position)
        measured +=
          when (spec.orient) {
            Orient.BOTTOM ->
              RuleNode(ids.allocate(), at, 0.0, at, tickSize, tickStroke, metadata = tickMeta)
            Orient.TOP ->
              RuleNode(ids.allocate(), at, 0.0, at, -tickSize, tickStroke, metadata = tickMeta)
            Orient.LEFT ->
              RuleNode(ids.allocate(), 0.0, at, -tickSize, at, tickStroke, metadata = tickMeta)
            Orient.RIGHT ->
              RuleNode(ids.allocate(), 0.0, at, tickSize, at, tickStroke, metadata = tickMeta)
          }
      }
    }

    if (spec.labels) {
      for (tick in ticks) {
        val run =
          TextRun(
            text = tick.label,
            style = labelStyle,
            align = labelAlign(spec.orient),
            baseline = labelBaseline(spec.orient),
          )
        val layout = textEngine.layout(run)
        val (x, y) =
          when (spec.orient) {
            Orient.BOTTOM -> tick.position to labelOffset
            Orient.TOP -> tick.position to -labelOffset
            Orient.LEFT -> -labelOffset to tick.position
            Orient.RIGHT -> labelOffset to tick.position
          }
        measured +=
          TextNode(
            id = ids.allocate(),
            x = x,
            y = y,
            layout = layout,
            fill = Fill.of(AxisDefaults.labelColor),
            metadata = NodeMetadata(role = "axis-label"),
          )
      }
    }

    val tickAndLabelReach =
      measured.fold(RectD.Empty) { acc, node -> acc.union(node.transformedBounds) }
    children += measured

    if (spec.domainLine) {
      val domainStroke =
        Stroke(paint = ScenePaint.Solid(AxisDefaults.domainColor), width = AxisDefaults.TICK_WIDTH)
      val domainMeta = NodeMetadata(role = "axis-domain")
      // Upstream encodes the domain line's endpoints as range positions 0 and 1 of the axis scale,
      // so a scale that does not span the whole plotting area gets a correspondingly short line.
      val span = (scale as? PositionScale)?.range
      val from = span?.firstOrNull() ?: 0.0
      val to = span?.lastOrNull() ?: if (spec.orient.isVertical) extent.height else extent.width
      children +=
        when (spec.orient) {
          Orient.BOTTOM,
          Orient.TOP ->
            RuleNode(ids.allocate(), from, 0.0, to, 0.0, domainStroke, metadata = domainMeta)
          Orient.LEFT,
          Orient.RIGHT ->
            RuleNode(ids.allocate(), 0.0, from, 0.0, to, domainStroke, metadata = domainMeta)
        }
    }

    val titleNode = spec.title?.let { title(spec, it, scale, tickAndLabelReach) }
    titleNode?.let { children += it }

    val placement = groupTransform(spec, extent)
    val node =
      GroupNode(
        id = ids.allocate(),
        children = children,
        transform = placement,
        metadata = NodeMetadata(role = "axis", markName = spec.scale),
      )

    val guide =
      extentRect(spec, scale, tickAndLabelReach)
        .union(tickAndLabelReach)
        .union(titleNode?.bounds ?: RectD.Empty)
        .translate(
          placement.e - AxisDefaults.CRISP_OFFSET,
          placement.f - AxisDefaults.CRISP_OFFSET,
        )
    return BuiltAxis(node, guide)
  }

  /**
   * The rectangle an axis occupies by definition: its full length, and how deep its ticks and
   * labels reach on its own side.
   *
   * Upstream adds this to an axis's bounds unconditionally, which is what makes an axis measure the
   * whole scale range even when its outermost tick falls short of the domain's end.
   */
  private fun extentRect(spec: AxisSpec, scale: VegaScale, reach: RectD): RectD {
    val range = (scale as? PositionScale)?.range
    val length =
      if (range == null || range.size < 2) 0.0 else kotlin.math.abs(range.last() - range.first())
    val depth = depth(spec, reach)
    return when (spec.orient) {
      Orient.BOTTOM -> RectD(0.0, 0.0, length, depth)
      Orient.TOP -> RectD(0.0, -depth, length, 0.0)
      Orient.LEFT -> RectD(-depth, 0.0, 0.0, length)
      Orient.RIGHT -> RectD(0.0, 0.0, depth, length)
    }
  }

  /**
   * How far the ticks and labels stick out on the axis's own side, clamped as upstream clamps it.
   */
  private fun depth(spec: AxisSpec, reach: RectD): Double =
    when (spec.orient) {
      Orient.BOTTOM -> reach.bottom
      Orient.TOP -> -reach.top
      Orient.LEFT -> -reach.left
      Orient.RIGHT -> reach.right
    }.coerceIn(AxisDefaults.TITLE_MIN_EXTENT, AxisDefaults.TITLE_MAX_EXTENT)

  /**
   * The axis title.
   *
   * Placed a fixed padding beyond however far the ticks and labels reach, so it never collides with
   * a long label. A vertical axis rotates its title a quarter turn, and — this is the part that
   * looks arbitrary until you see it drawn — baselines it at the *bottom* in both directions,
   * because after the rotation the baseline runs along the axis rather than across it.
   */
  private fun title(
    spec: AxisSpec,
    text: String,
    scale: VegaScale,
    reach: RectD,
  ): TextNode {
    val padding = numbers.resolve(spec.titlePadding, spec.scale) ?: AxisDefaults.TITLE_PADDING
    val fontSize = numbers.resolve(spec.titleFontSize, spec.scale) ?: AxisDefaults.TITLE_FONT_SIZE
    val anchor = spec.titleAnchor ?: Anchor.MIDDLE

    val depth = depth(spec, reach)
    val away =
      if (spec.orient == Orient.TOP || spec.orient == Orient.LEFT) -(depth + padding)
      else depth + padding

    // Along the axis, the title sits wherever the anchor says on the *scale's range*, not on the
    // plotting area — the two differ inside a group.
    val range = (scale as? PositionScale)?.range
    val from = range?.firstOrNull() ?: 0.0
    val to = range?.lastOrNull() ?: 0.0
    val along =
      when (anchor) {
        Anchor.START -> from
        Anchor.END -> to
        Anchor.MIDDLE -> (from + to) / 2.0
      }

    val run =
      TextRun(
        text = text,
        style =
          TextStyle(
            fontFamily = AxisDefaults.LABEL_FONT_FAMILY,
            fontSize = fontSize,
            fontWeight = AxisDefaults.TITLE_FONT_WEIGHT,
          ),
        align =
          when (anchor) {
            Anchor.START -> TextAlign.LEFT
            Anchor.END -> TextAlign.RIGHT
            Anchor.MIDDLE -> TextAlign.CENTER
          },
        baseline = if (spec.orient == Orient.BOTTOM) TextBaseline.TOP else TextBaseline.BOTTOM,
      )
    return TextNode(
      id = ids.allocate(),
      x = if (spec.orient.isVertical) away else along,
      y = if (spec.orient.isVertical) along else away,
      layout = textEngine.layout(run),
      angleDegrees =
        when (spec.orient) {
          Orient.LEFT -> -90.0
          Orient.RIGHT -> 90.0
          else -> 0.0
        },
      fill = Fill.of(AxisDefaults.titleColor),
      metadata = NodeMetadata(role = "axis-title", markName = spec.scale),
    )
  }

  /**
   * Where the axis group sits.
   *
   * The half-pixel offset is Vega's, not a rounding artefact of ours: it makes 1-pixel ticks land
   * on pixel centres instead of straddling two pixels.
   */
  private fun groupTransform(spec: AxisSpec, extent: PlotSize): Transform2D {
    val offset = numbers.resolve(spec.offset, spec.scale) ?: 0.0
    return when (spec.orient) {
      Orient.BOTTOM ->
        Transform2D.translate(
          AxisDefaults.CRISP_OFFSET,
          extent.height + AxisDefaults.CRISP_OFFSET + offset,
        )
      Orient.TOP ->
        Transform2D.translate(AxisDefaults.CRISP_OFFSET, AxisDefaults.CRISP_OFFSET - offset)
      Orient.LEFT ->
        Transform2D.translate(AxisDefaults.CRISP_OFFSET - offset, AxisDefaults.CRISP_OFFSET)
      Orient.RIGHT ->
        Transform2D.translate(
          extent.width + AxisDefaults.CRISP_OFFSET + offset,
          AxisDefaults.CRISP_OFFSET,
        )
    }
  }

  private fun labelAlign(orient: Orient): TextAlign =
    when (orient) {
      Orient.BOTTOM,
      Orient.TOP -> TextAlign.CENTER
      Orient.LEFT -> TextAlign.RIGHT
      Orient.RIGHT -> TextAlign.LEFT
    }

  private fun labelBaseline(orient: Orient): TextBaseline =
    when (orient) {
      Orient.BOTTOM -> TextBaseline.TOP
      Orient.TOP -> TextBaseline.BOTTOM
      Orient.LEFT,
      Orient.RIGHT -> TextBaseline.MIDDLE
    }

  /**
   * Tick values and positions for a scale.
   *
   * A band or point scale ticks at every domain entry; a linear scale ticks at d3's chosen round
   * values. Band positions are the band centres shifted back half a pixel, which is what upstream
   * emits — verified by comparing against Vega's own axis items for band, point and linear scales.
   */
  private fun ticksFor(scale: VegaScale, spec: AxisSpec): List<Tick>? =
    when (scale) {
      is BandScale ->
        scale.domain.zip(scale.centers()).map { (label, centre) ->
          Tick(label, centre - AxisDefaults.CRISP_OFFSET)
        }
      is PointScale ->
        scale.domain.map { label ->
          Tick(label, scale.position(VegaValue.Str(label)))
        }
      is LinearScale -> {
        val count =
          numbers.resolveInt(spec.tickCount, spec.scale) ?: AxisDefaults.DEFAULT_TICK_COUNT
        // Labels come from the scale rather than being formatted here, because a log scale blanks
        // the
        // crowded ones and only it knows which.
        scale.ticks(count).zip(scale.tickLabels(count)).map { (value, label) ->
          Tick(label, scale.apply(value))
        }
      }
      is TransformedScale -> {
        val count =
          numbers.resolveInt(spec.tickCount, spec.scale) ?: AxisDefaults.DEFAULT_TICK_COUNT
        scale.ticks(count).zip(scale.tickLabels(count)).map { (value, label) ->
          Tick(label, scale.apply(value))
        }
      }
      is TimeScale -> {
        val count =
          numbers.resolveInt(spec.tickCount, spec.scale) ?: AxisDefaults.DEFAULT_TICK_COUNT
        scale.ticks(count).zip(scale.tickLabels(count)).map { (value, label) ->
          Tick(label, scale.apply(value))
        }
      }
      else -> null
    }
}
