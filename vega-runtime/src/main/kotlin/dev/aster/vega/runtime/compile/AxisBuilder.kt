package dev.aster.vega.runtime.compile

import dev.aster.vega.expression.NumberFormatSubset
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.asString
import dev.aster.vega.model.spec.Anchor
import dev.aster.vega.model.spec.AxisSpec
import dev.aster.vega.model.spec.Orient
import dev.aster.vega.model.spec.ScaleType
import dev.aster.vega.model.time.TimeFormat
import dev.aster.vega.runtime.scale.BandScale
import dev.aster.vega.runtime.scale.LinearScale
import dev.aster.vega.runtime.scale.PointScale
import dev.aster.vega.runtime.scale.PositionScale
import dev.aster.vega.runtime.scale.Ticks
import dev.aster.vega.runtime.scale.TimeScale
import dev.aster.vega.runtime.scale.TimeTicks
import dev.aster.vega.runtime.scale.TransformedScale
import dev.aster.vega.runtime.scale.VegaScale
import dev.aster.vega.scene.AccessibilityDescriptor
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.NodeMetadata
import dev.aster.vega.scene.RectD
import dev.aster.vega.scene.RuleNode
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.SceneNodeIdAllocator
import dev.aster.vega.scene.TextAlign
import dev.aster.vega.scene.TextBaseline
import dev.aster.vega.scene.TextEngine
import dev.aster.vega.scene.TextNode
import dev.aster.vega.scene.TextRun
import dev.aster.vega.scene.Transform2D
import dev.aster.vega.scene.transformedBounds
import kotlinx.datetime.TimeZone

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
  /**
   * The declared `type` per scale, for the accessibility caption; `sqrt` and `pow` share a class.
   */
  private val scaleTypes: Map<String, ScaleType>,
  private val ids: SceneNodeIdAllocator,
  private val textEngine: TextEngine,
  private val diagnostics: DiagnosticCollector,
  /** Resolves axis properties that a specification supplied as signals. */
  private val numbers: NumberResolver,
  /**
   * Resolves a guide `encode` channel that has no property behind it — a label's own position.
   *
   * Optional because most callers have no encode to resolve, and because an axis built without one
   * still lays its labels out the way it always has.
   */
  private val channels: MarkEncoder? = null,
) {

  /** One tick's label text and its position along the axis. */
  /**
   * One tick: what it says, where it goes, and the domain value behind it.
   *
   * The value is carried because a label's `encode` block is resolved against the tick as a datum,
   * and `datum.value` is the whole point of such a block — placing the label by the scale rather
   * than by the tick's own position.
   */
  private data class Tick(
    val label: String,
    val position: Double,
    val value: VegaValue = VegaValue.Null,
    /**
     * Where the *label* goes, which is not always where the tick goes.
     *
     * Upstream places a band axis's ticks at `bandPosition` and its labels at the band's centre
     * regardless, so a chart that puts its ticks on the band edges still reads its labels from the
     * middle. Null means upstream has no number here at all — the extra tick `tickExtra` appends
     * carries no value, so its label scales an absent one and lands at `NaN`. Such a label is drawn
     * at the origin, which is what upstream's renderer paints, and is left out of the measurement,
     * which is what upstream's `NaN` gets for free.
     */
    val labelPosition: Double? = position,
  )

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

    // The label specifier, resolved once: a specification may compute it rather than write it
    // down, and a chart bound to a granularity control does exactly that.
    val specifier =
      spec.format ?: spec.formatExpression?.let { numbers.resolveText(it, spec.scale) }
    val ticks = ticksFor(scale, spec, specifier)?.let { withExtraTick(it, scale, spec) }
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
    val labelStyle = GuideStyle.text(spec.labelStyle, fontSize, defaultWeight = 400)
    val labelLimit = numbers.resolve(spec.labelLimit, spec.scale) ?: AxisDefaults.LABEL_LIMIT

    val children = mutableListOf<SceneNode>()
    // Ticks and labels, in paint order, hidden labels included so the mark count does not change
    // with the chart's width.
    val drawn = mutableListOf<SceneNode>()
    // The subset those two contribute to the axis's size. Upstream measures an axis by its ticks
    // and
    // labels and skips the gridlines, so switching a grid on does not widen the chart or push the
    // axis title away — and a label hidden by overlap removal drops out of the measurement too.
    val measured = mutableListOf<SceneNode>()
    val tickStroke = GuideStyle.stroke(spec.tickStyle, AxisDefaults.tickColor)
    val gridStroke = GuideStyle.stroke(spec.gridStyle, AxisDefaults.gridColor)
    // How far the axis itself is pushed off the plot, which the gridlines have to undo.
    val gridOffset = offsetOf(spec)

    // A gridline runs back across the plot, away from the side its axis is on: up from a bottom
    // axis, down from a top one, right from a left one, left from a right one.
    val gridSign = if (spec.orient == Orient.TOP || spec.orient == Orient.LEFT) 1.0 else -1.0

    // `gridScale` spans a *second* scale's range instead of the plotting area, which is how a grid
    // is drawn across a cell that is not the size of its own plot. Upstream takes the two ends in
    // that scale's own order — `range(gridScale)[0]` then `[1]` — so a descending range gives a
    // gridline whose start is the far end, which the plain form never does.
    val gridRange =
      spec.gridScale?.let { name ->
        val other = scales[name]
        if (other is PositionScale) {
          other.range.first() * gridSign to other.range.last() * gridSign
        } else {
          diagnostics.warn(
            DiagnosticCodes.SCALE_UNSUPPORTED_TYPE,
            "Axis 'gridScale' names '$name', which is not a scale with a range; the gridlines " +
              "span the plotting area instead",
            operator = spec.scale,
          )
          null
        }
      } ?: (0.0 to gridSign * (if (spec.orient.isVertical) gridSize.width else gridSize.height))

    // Gridlines first so they sit under the ticks, matching Vega's ordering.
    if (spec.grid) {
      val gridMeta = NodeMetadata(role = "axis-grid")
      // An `offset` moves the axis away from the plot, and the gridlines *back*: upstream gives
      // every gridline endpoint an offset of `sign * axis.offset`, which cancels the translation
      // the axis group carries. Without it a gridline is dragged along with its axis and floats
      // clear of the data it is there to measure.
      val undoOffset =
        when (spec.orient) {
          Orient.LEFT,
          Orient.TOP -> gridOffset
          Orient.RIGHT,
          Orient.BOTTOM -> -gridOffset
        }
      for (tick in ticks) {
        val at = AxisDefaults.crispRound(tick.position)
        children +=
          when (spec.orient) {
            Orient.BOTTOM,
            Orient.TOP ->
              RuleNode(
                ids.allocate(),
                at,
                gridRange.first + undoOffset,
                at,
                gridRange.second + undoOffset,
                gridStroke,
                metadata = gridMeta,
              )
            Orient.LEFT,
            Orient.RIGHT ->
              RuleNode(
                ids.allocate(),
                gridRange.first + undoOffset,
                at,
                gridRange.second + undoOffset,
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
        val tickNode =
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
        drawn += tickNode
        measured += tickNode
      }
    }

    if (spec.labels) {
      val labelAngle = numbers.resolve(spec.labelAngle, spec.scale) ?: 0.0
      val labels = mutableListOf<TextNode>()
      for (tick in ticks) {
        // The label's own `encode` may replace the *text* as well as its position — read through a
        // scale, which is how a chart labels a key with a display name it keeps in a second scale.
        // There is no axis property that could say it, because the mapping lives in the data.
        // `labelFlush` pushes the first and last labels inwards so they sit inside the plot
        // instead of hanging off its corners. It is an *alignment* rule, decided per label from
        // how close that label's scaled value is to an end of the range — and it moves the
        // alignment on the axis's own dimension only, so a bottom axis flushes its `align` and a
        // left one its `baseline`.
        val flushed = flushAnchor(spec, scale, tick)
        val run =
          TextRun(
            text = labelText(spec, tick) ?: tick.label,
            style = labelStyle,
            align =
              alignOf(spec.labelAlign)
                ?: (if (spec.orient.isVertical) null else flushed?.let(::flushAlign))
                ?: labelAlign(spec.orient),
            baseline =
              baselineOf(spec.labelBaseline)
                ?: (if (spec.orient.isVertical) flushed?.let(::flushBaseline) else null)
                ?: labelBaseline(spec.orient),
            limit = labelLimit,
          )
        val layout = textEngine.layout(run)
        // A label sits where *it* was placed, which on a band axis is the band's centre whatever
        // `bandPosition` did to the ticks. A null one is upstream's `NaN` — the extra tick carries
        // no value for the label to scale — and it stays a `NaN` here: a text node with no usable
        // anchor covers nothing and draws nothing, which is what upstream's scene and its own SVG
        // both say.
        val along = tick.labelPosition ?: Double.NaN
        val (defaultX, defaultY) =
          when (spec.orient) {
            Orient.BOTTOM -> along to labelOffset
            Orient.TOP -> along to -labelOffset
            Orient.LEFT -> -labelOffset to along
            Orient.RIGHT -> labelOffset to along
          }
        // A label's own `encode` block may place it somewhere the properties cannot say — off the
        // band's centre, at the tick's raw scale position. The datum is the tick: `datum.value` is
        // what the axis is labelling, `datum.label` the text it drew for it, which is what
        // upstream binds too.
        // `dx` and `dy` nudge the label without changing what it is anchored to, so they are added
        // to whatever placed it rather than replacing it.
        val x =
          (labelChannel(spec, "x", tick) ?: defaultX) + (labelChannel(spec, "dx", tick) ?: 0.0)
        val y =
          (labelChannel(spec, "y", tick) ?: defaultY) + (labelChannel(spec, "dy", tick) ?: 0.0)
        labels +=
          TextNode(
            id = ids.allocate(),
            x = x,
            y = y,
            layout = layout,
            // The angle alone: upstream leaves the alignment and baseline where the orientation put
            // them, so a turned label pivots about its anchor rather than being re-hung from it.
            angleDegrees = labelAngle,
            fill = GuideStyle.fill(spec.labelStyle, AxisDefaults.labelColor),
            // A label's own `encode` may hide it on a rule the axis has no property for — a
            // calendar shows the month name on the first week of each month and blanks the rest.
            // It still measures: upstream bounds a text item from its geometry whatever its
            // opacity, and only overlap removal takes one out of the measurement.
            opacity = labelChannel(spec, "opacity", tick) ?: 1.0,
            metadata = NodeMetadata(role = "axis-label"),
          )
      }
      // An axis removes overlapping labels only when asked; a legend does it by default. The
      // asymmetry is upstream's — see LabelOverlap.
      val method = LabelOverlap.Method.fromValue(spec.labelOverlap)
      val kept =
        if (method == null) labels
        else {
          LabelOverlap.visible(
            labels,
            method,
            numbers.resolve(spec.labelSeparation, spec.scale) ?: 0.0,
          )
        }
      // A hidden label stays in the scene at zero opacity, so the mark count does not change with
      // the chart's width — but it drops out of the measurement, which is what upstream does when
      // it
      // recomputes the label mark's bounds from the survivors.
      for (label in labels) {
        if (label in kept) {
          drawn += label
          // A label with no anchor has empty bounds, so measuring it changes nothing — which is
          // what upstream's `NaN` gets for free, every comparison against it being false.
          measured += label
        } else {
          drawn += label.copy(opacity = 0.0)
        }
      }
    }

    val tickAndLabelReach =
      measured.fold(RectD.Empty) { acc, node -> acc.union(node.transformedBounds) }
    children += drawn

    if (spec.domainLine) {
      val domainStroke = GuideStyle.stroke(spec.domainStyle, AxisDefaults.domainColor)
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

    // A title may be a signal: a chart that lets a control choose the measure retitles the axis
    // with the choice, and there is no constant to write down.
    val titleText = spec.title ?: spec.titleExpression?.let { numbers.resolveText(it, spec.scale) }
    val titleNode = titleText?.let { title(spec, it, scale, tickAndLabelReach) }
    titleNode?.let { children += it }

    val placement = groupTransform(spec, extent)
    val node =
      GroupNode(
        id = ids.allocate(),
        children = children,
        transform = placement,
        metadata =
          NodeMetadata(
            role = "axis",
            markName = spec.scale,
            // What a screen reader is told before it reaches the marks this axis frames.
            accessibility =
              GuideCaption.axis(
                  spec.orient.name.lowercase(),
                  titleText,
                  scale,
                  scaleTypes[spec.scale],
                  specifier,
                  spec.formatType,
                )
                ?.let {
                  AccessibilityDescriptor(label = it, role = "graphics-symbol", focusable = true)
                },
          ),
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
    }.coerceIn(
      numbers.resolve(spec.minExtent, spec.scale) ?: AxisDefaults.TITLE_MIN_EXTENT,
      numbers.resolve(spec.maxExtent, spec.scale) ?: AxisDefaults.TITLE_MAX_EXTENT,
    )

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

    // `titleX`, `titleY`, `titleAngle`, `titleAlign` and `titleBaseline` each replace what the
    // anchor and orientation would have chosen, in the axis group's own coordinates. A
    // parallel-coordinates plot sets all five in `config.axisY` so that every column's title lies
    // flat along the bottom rather than turned up the side of its own axis.
    val run =
      TextRun(
        text = text,
        style = GuideStyle.text(spec.titleStyle, fontSize, AxisDefaults.TITLE_FONT_WEIGHT),
        align =
          alignOf(spec.titleAlign)
            ?: when (anchor) {
              Anchor.START -> TextAlign.LEFT
              Anchor.END -> TextAlign.RIGHT
              Anchor.MIDDLE -> TextAlign.CENTER
            },
        baseline =
          baselineOf(spec.titleBaseline)
            ?: if (spec.orient == Orient.BOTTOM) TextBaseline.TOP else TextBaseline.BOTTOM,
      )
    return TextNode(
      id = ids.allocate(),
      x = numbers.resolve(spec.titleX, spec.scale) ?: if (spec.orient.isVertical) away else along,
      y = numbers.resolve(spec.titleY, spec.scale) ?: if (spec.orient.isVertical) along else away,
      layout = textEngine.layout(run),
      angleDegrees =
        numbers.resolve(spec.titleAngle, spec.scale)
          ?: when (spec.orient) {
            Orient.LEFT -> -90.0
            Orient.RIGHT -> 90.0
            else -> 0.0
          },
      fill = GuideStyle.fill(spec.titleStyle, AxisDefaults.titleColor),
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
    val offset = offsetOf(spec)
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

  /** An explicit `labelAlign`, or null to let the orientation decide. */
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
      else -> null
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
  /**
   * The ticks an axis draws, from the scale or from an explicit `values` list.
   *
   * Upstream's `validTicks`, reproduced rather than approximated, because three of its four steps
   * are surprises:
   * - a value that falls outside the scale's *range* is dropped, not clamped;
   * - the survivors are ordered by where they land, so a list written out of order comes out in
   *   order — and backwards when the range is reversed;
   * - if there are more of them than `tickCount` allows, every other one is dropped repeatedly
   *   until few enough remain, and if that leaves fewer than three the first and last are used
   *   instead. Five values with `tickCount: 4` therefore give three, not four.
   *
   * The fourth is the label format, and it is the one a specification is most likely to trip over:
   * with no `tickCount`, upstream formats using a count equal to the **number of values given**. So
   * `values: [0.5, 1.5]` on a `[0, 2]` domain formats at the precision a two-tick axis would use,
   * which is none, and both labels read as whole numbers. Reproduced, because a specification
   * written against upstream is looking at those labels.
   */
  /**
   * How far the axis is pushed off the plotting area's edge.
   *
   * Usually a number, and sometimes a whole value reference: a parallel-coordinates plot writes
   * `{"scale": "ord", "value": "Cylinders", "mult": -1}` and places one `orient: "left"` axis per
   * column at the position that column's own name scales to. There is no number to write down.
   */
  private fun offsetOf(spec: AxisSpec): Double =
    spec.offsetChannel?.let { channels?.channelNumber(it, VegaValue.EmptyObject) }
      ?: numbers.resolve(spec.offset, spec.scale)
      ?: 0.0

  /**
   * `tickExtra`: one more tick at the **start** of the first tick's band, labelled with nothing.
   *
   * Upstream appends a datum that carries `{extra: {value: <first tick's value>}}` and no `value`
   * of its own, and the scaled-value codegen reads the first as "that value's position, with no
   * bandwidth added" — so the extra tick lands on the leading edge that per-band ticks leave
   * unmarked. The datum's missing `value` is what makes its *label* land at `NaN`, carried here as
   * a null [Tick.labelPosition] rather than as a NaN, because an absence stays out of the
   * arithmetic downstream where a NaN would have to be kept out of it by hand.
   */
  private fun withExtraTick(ticks: List<Tick>, scale: VegaScale, spec: AxisSpec): List<Tick> {
    if (!spec.tickExtra || ticks.isEmpty()) return ticks
    val positional = scale as? PositionScale ?: return ticks
    val at = positional.position(ticks.first().value)
    if (!at.isFinite()) return ticks
    return ticks + Tick("", at + tickOffset(positional, spec), VegaValue.Null, labelPosition = null)
  }

  private fun ticksFor(scale: VegaScale, spec: AxisSpec, specifier: String?): List<Tick>? {
    // A scale with `bins` has its tick values already decided: upstream's `tickValues` returns the
    // boundaries themselves rather than asking the scale to generate any. An axis that *also* names
    // `values` still wins, as it does upstream, where `values` is checked first.
    val explicit =
      spec.values
        ?: scale.bins?.map { VegaValue.Num(it) }
        ?: return generatedTicks(scale, spec, specifier)
    if (scale !is PositionScale) return generatedTicks(scale, spec, specifier)

    // Bin boundaries are never thinned: upstream raises the count to the number of bins first, so
    // the halving that a long `values` list gets never applies to them.
    val count =
      if (spec.values == null && scale.bins != null) {
        maxOf(numbers.resolveInt(spec.tickCount, spec.scale) ?: 0, explicit.size)
      } else {
        numbers.resolveInt(spec.tickCount, spec.scale) ?: explicit.size.coerceAtLeast(1)
      }
    val label = labeller(scale, count, specifier, spec.formatType)

    val range = scale.range
    val low = kotlin.math.floor(minOf(range.first(), range.last()))
    val high = kotlin.math.ceil(maxOf(range.first(), range.last()))
    val descending = range.last() < range.first()

    val placed =
      explicit
        .map { it to scale.position(it) }
        .filter { (_, at) -> at.isFinite() && at >= low && at <= high }
        .sortedWith(if (descending) compareByDescending { it.second } else compareBy { it.second })
    return thin(placed, count).map { (value, at) ->
      Tick(label(value), at + bandOffset(scale, spec), value)
    }
  }

  /**
   * Upstream's thinning: halve the list until it fits, then fall back to the two ends.
   *
   * The fallback is what makes five values under `tickCount: 4` give three rather than four — the
   * halving overshoots and the endpoints are only restored when it drops below three.
   */
  private fun <T> thin(values: List<T>, count: Int): List<T> {
    if (count <= 0 || values.size <= 1) return values
    val ends = listOf(values.first(), values.last())
    var kept = values
    while (kept.size > count && kept.size >= 3) {
      kept = kept.filterIndexed { index, _ -> index % 2 == 0 }
    }
    return if (kept.size < 3) ends else kept
  }

  /**
   * A band scale positions a value at its band's *start*; a tick belongs at the centre.
   *
   * Half a pixel comes back off, the same shift the generated band ticks carry, so the labels stay
   * centred on the band while the ticks stay crisp.
   */
  /**
   * One channel of the axis's `encode.labels` block, resolved against the tick being labelled.
   *
   * `update` over `enter`, as everywhere else. Returns null when the block says nothing about this
   * channel, which leaves the label where the orientation put it.
   */
  /** A label's `encode` text, when the specification replaces it rather than formatting it. */
  private fun labelText(spec: AxisSpec, tick: Tick): String? {
    val encoder = channels ?: return null
    val entry = spec.encode["labels"]?.update?.get("text") ?: return null
    val datum =
      VegaValue.Obj(linkedMapOf("value" to tick.value, "label" to VegaValue.Str(tick.label)))
    return encoder.channelText(entry, datum)
  }

  private fun labelChannel(spec: AxisSpec, channel: String, tick: Tick): Double? {
    val encoder = channels ?: return null
    val block = spec.encode["labels"] ?: return null
    // `update` only. A guide writes its own position into `update` every pass, so a specification's
    // `enter` is overwritten before anything is drawn — verified upstream, where an axis label with
    // `enter: {x: 99}` does not move. Honouring `enter` here would place a label upstream leaves
    // where it was.
    val entry = block.update[channel] ?: return null
    val datum =
      VegaValue.Obj(linkedMapOf("value" to tick.value, "label" to VegaValue.Str(tick.label)))
    return encoder.channelNumber(entry, datum)
  }

  /**
   * Which end of the range a label is being flushed to, or null when it is not.
   *
   * Upstream's `flush(range, value, threshold, left, right, center)`, and the parts that are easy
   * to get wrong are the tie-break and the reversal: the ends are sorted before the comparison, so
   * a descending range still flushes its *low* end to the start; and the nearer end wins with `l <
   * r`, so a label exactly between two ends of an equally short range takes the far one.
   */
  private fun flushAnchor(spec: AxisSpec, scale: VegaScale, tick: Tick): FlushEnd? {
    val threshold = spec.labelFlush ?: return null
    val positional = scale as? PositionScale ?: return null
    val range = positional.range
    if (range.size < 2) return null
    val low = minOf(range.first(), range.last())
    val high = maxOf(range.first(), range.last())
    val at = positional.position(tick.value)
    if (!at.isFinite()) return null
    val fromLow = kotlin.math.abs(at - low)
    val fromHigh = kotlin.math.abs(high - at)
    return when {
      fromLow < fromHigh && fromLow <= threshold -> FlushEnd.START
      fromHigh <= threshold -> FlushEnd.END
      else -> null
    }
  }

  /** Which end of the range a flushed label was pulled towards. */
  private enum class FlushEnd {
    START,
    END,
  }

  private fun flushAlign(end: FlushEnd): TextAlign =
    if (end == FlushEnd.START) TextAlign.LEFT else TextAlign.RIGHT

  private fun flushBaseline(end: FlushEnd): TextBaseline =
    if (end == FlushEnd.START) TextBaseline.TOP else TextBaseline.BOTTOM

  private fun bandOffset(scale: PositionScale, spec: AxisSpec): Double {
    if (scale !is BandScale) return 0.0
    val position = numbers.resolve(spec.bandPosition, spec.scale) ?: AxisDefaults.BAND_POSITION
    return scale.bandwidth * position + tickOffset(scale, spec)
  }

  /**
   * `tickOffset`: how far a tick is nudged along the axis once its band position has placed it.
   *
   * The default is upstream's, and it is **not** zero for a band scale: `config.axisBand` carries a
   * `-0.5` that corrects the half-pixel the axis group's own translation adds, and it applies to a
   * band scale only — a point or ordinal axis never sees that block. A specification aiming ticks
   * at the band boundaries has to switch it off explicitly, which is why the property exists.
   */
  private fun tickOffset(scale: PositionScale, spec: AxisSpec): Double =
    numbers.resolve(spec.tickOffset, spec.scale)
      ?: if (scale is BandScale) -AxisDefaults.CRISP_OFFSET else 0.0

  /**
   * Where a band axis's label sits, which is the band's **centre** whatever the ticks do.
   *
   * Upstream's label mark hard-codes `band: 0.5` and takes only the tick *offset* from the shared
   * band settings, so `bandPosition: 1` moves the ticks to the edges and leaves the labels where
   * they were.
   */
  private fun labelOffsetAlong(scale: PositionScale, spec: AxisSpec): Double =
    if (scale !is BandScale) 0.0
    else scale.bandwidth * AxisDefaults.BAND_POSITION + tickOffset(scale, spec)

  /**
   * How an explicit value is labelled.
   *
   * A discrete scale has no formatter — upstream falls back to plain string coercion, which is why
   * a band axis over negative numbers keeps its hyphens where a linear one gets a minus sign.
   */
  private fun labeller(
    scale: PositionScale,
    count: Int,
    format: String? = null,
    formatType: String? = null,
  ): (VegaValue) -> String {
    // `formatType` decides the *grammar* before the scale gets a say, which is upstream's order in
    // `vega-scale`'s `tickFormat`: a time type wins over every scale type, including the discrete
    // ones whose labels would otherwise be their own values. It is what a chart uses to label a
    // band of instants, since there is no temporal scale anywhere to infer it from.
    val zone =
      when (formatType) {
        "time" -> TimeZone.currentSystemDefault()
        "utc" -> TimeZone.UTC
        else -> null
      }
    if (zone != null) {
      return { value ->
        val instant = value.asDouble()
        when {
          instant.isNaN() -> value.asString()
          // No specifier means upstream's *multi*-format, which picks its own granularity per
          // value rather than formatting them all alike.
          format == null -> TimeTicks.label(instant, zone)
          else -> TimeFormat.format(instant, format, zone)
        }
      }
    }
    // An explicit specifier replaces the precision the scale would have chosen, and applies only
    // where there is a number to format: upstream coerces a discrete domain's own values to strings
    // and never consults it, so a band axis keeps its labels whatever this says.
    if (format != null && scale !is BandScale && scale !is PointScale) {
      // Upstream resolves the specifier against the *span* being labelled, so a specifier that
      // names no precision takes as many decimals as the tick step needs rather than d3's fixed
      // six.
      val numeric =
        when (scale) {
          is LinearScale -> scale.domain
          is TransformedScale -> scale.domain
          is TimeScale -> scale.domain
        }
      val resolved = Ticks.spanSpecifier(format, numeric.first(), numeric.last(), count)
      return { value ->
        val number = value.asDouble()
        if (number.isNaN()) value.asString() else NumberFormatSubset.format(number, resolved)
      }
    }
    return when (scale) {
      is LinearScale -> { value ->
        scale.formatTick(value.asDouble(), count)
      }
      is TransformedScale -> { value ->
        scale.formatTick(value.asDouble(), count)
      }
      // A time label is written at its own granularity — a January tick carries the year — so it
      // comes from the tick itself rather than from a shared precision.
      is TimeScale -> { value ->
        TimeTicks.label(value.asDouble(), scale.zone)
      }
      else -> { value ->
        value.asString()
      }
    }
  }

  private fun generatedTicks(scale: VegaScale, spec: AxisSpec, specifier: String?): List<Tick>? =
    when (scale) {
      // A discrete domain's values *are* its labels unless a format type says how to read them,
      // which is the only way a band scale over instants reads as dates rather than as numbers.
      is BandScale -> {
        val label = labeller(scale, scale.domain.size, specifier, spec.formatType)
        val alongTick = bandOffset(scale, spec)
        val alongLabel = labelOffsetAlong(scale, spec)
        scale.domain.map { value ->
          val start = scale.position(VegaValue.Str(value))
          Tick(
            label(VegaValue.Str(value)),
            start + alongTick,
            VegaValue.Str(value),
            labelPosition = start + alongLabel,
          )
        }
      }
      is PointScale -> {
        val label = labeller(scale, scale.domain.size, specifier, spec.formatType)
        scale.domain.map { value ->
          Tick(
            label(VegaValue.Str(value)),
            scale.position(VegaValue.Str(value)),
            VegaValue.Str(value),
          )
        }
      }
      is LinearScale -> {
        val count =
          numbers.resolveInt(spec.tickCount, spec.scale) ?: AxisDefaults.DEFAULT_TICK_COUNT
        // Labels come from the scale rather than being formatted here, because a log scale blanks
        // the
        // crowded ones and only it knows which.
        val format = labeller(scale, count, specifier, spec.formatType)
        scale.ticks(count).zip(scale.tickLabels(count)).map { (value, label) ->
          Tick(
            if (specifier == null && spec.formatType == null) label
            else format(VegaValue.Num(value)),
            scale.apply(value),
            VegaValue.Num(value),
          )
        }
      }
      is TransformedScale -> {
        val count =
          numbers.resolveInt(spec.tickCount, spec.scale) ?: AxisDefaults.DEFAULT_TICK_COUNT
        val format = labeller(scale, count, specifier, spec.formatType)
        scale.ticks(count).zip(scale.tickLabels(count)).map { (value, label) ->
          Tick(
            if (specifier == null && spec.formatType == null) label
            else format(VegaValue.Num(value)),
            scale.apply(value),
            VegaValue.Num(value),
          )
        }
      }
      is TimeScale -> {
        val count =
          numbers.resolveInt(spec.tickCount, spec.scale) ?: AxisDefaults.DEFAULT_TICK_COUNT
        scale.ticks(count).zip(scale.tickLabels(count)).map { (value, label) ->
          Tick(label, scale.apply(value), VegaValue.Num(value))
        }
      }
      else -> null
    }
}
