package dev.aster.vega.runtime.compile

import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.asNumberOrNull
import dev.aster.vega.model.asString
import dev.aster.vega.model.locale.VegaLocale
import dev.aster.vega.model.spec.Anchor
import dev.aster.vega.model.spec.AxisSpec
import dev.aster.vega.model.spec.Orient
import dev.aster.vega.model.spec.ScaleType
import dev.aster.vega.model.time.TimeFormat
import dev.aster.vega.model.time.TimeInterval
import dev.aster.vega.model.time.TimeStepper
import dev.aster.vega.runtime.scale.BandScale
import dev.aster.vega.runtime.scale.BinOrdinalScale
import dev.aster.vega.runtime.scale.BinnedScale
import dev.aster.vega.runtime.scale.LinearScale
import dev.aster.vega.runtime.scale.PointScale
import dev.aster.vega.runtime.scale.PositionScale
import dev.aster.vega.runtime.scale.QuantileScale
import dev.aster.vega.runtime.scale.QuantizeScale
import dev.aster.vega.runtime.scale.ThresholdScale
import dev.aster.vega.runtime.scale.Ticks
import dev.aster.vega.runtime.scale.TimeScale
import dev.aster.vega.runtime.scale.TimeTicks
import dev.aster.vega.runtime.scale.TransformedScale
import dev.aster.vega.runtime.scale.VegaScale
import dev.aster.vega.runtime.scale.formatTickLabel
import dev.aster.vega.scene.AccessibilityDescriptor
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.NodeMetadata
import dev.aster.vega.scene.RectD
import dev.aster.vega.scene.RuleNode
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
import dev.aster.vega.scene.Transform2D
import dev.aster.vega.scene.transformedBounds
import kotlin.math.floor
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
/** The three grammars a guide's `format` can be written in, which is upstream's whole list. */
private val FORMAT_TYPES = setOf("number", "time", "utc")

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
  /**
   * The language every generated name and number is written in.
   *
   * A tick label's month, a caption's sentence, a thousands separator. Defaults to d3's `en-US`, so
   * a chart compiled without one is what upstream draws.
   */
  private val locale: VegaLocale = VegaLocale.EnglishUS,
  /**
   * What a `formatType: "time"` label and caption are written in; null is the device's own zone.
   *
   * A **temporal scale** does not read this — its zone is settled when the scale is built, and
   * `utc` stays UTC — so this is the case where a guide declares the grammar itself, over a band of
   * instants with no temporal scale to infer one from.
   */
  private val timeZone: TimeZone? = null,
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
  public fun build(
    declared: AxisSpec,
    extent: PlotSize,
    gridSize: PlotSize = extent,
  ): BuiltAxis? {
    // A styling property written as a signal is substituted here, once, so everything below reads
    // plain constants: an axis whose label colour comes from a control is the ordinary case, and
    // the
    // alternative is resolving the same expression at each of a hundred reads.
    val spec =
      declared.copy(
        // `formatType` may be chosen by a signal, and it has to be resolved before anything reads
        // it:
        // it decides which *grammar* the format string is written in, so a chart switching a column
        // between a count and a date switches both together.
        formatType =
          declared.formatType
            ?: declared.formatTypeExpression
              ?.let { numbers.resolveText(it, declared.scale) }
              ?.lowercase()
              ?.takeIf { it in FORMAT_TYPES },
        labelStyle = GuideStyle.resolved(declared.labelStyle, numbers, declared.scale),
        tickStyle = GuideStyle.resolved(declared.tickStyle, numbers, declared.scale),
        gridStyle = GuideStyle.resolved(declared.gridStyle, numbers, declared.scale),
        domainStyle = GuideStyle.resolved(declared.domainStyle, numbers, declared.scale),
        titleStyle = GuideStyle.resolved(declared.titleStyle, numbers, declared.scale),
      )
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
    val labelAlong = numbers.resolve(spec.labelOffset, spec.scale) ?: 0.0
    val flushOffset = numbers.resolve(spec.labelFlushOffset, spec.scale) ?: 0.0

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
        val at = tickCoordinate(tick.position, spec)
        // A gridline's own `encode` block, resolved against the tick: this is how a chart picks the
        // zero line out of the rest, and Vega-Lite writes every conditional guide property this way
        // because Vega has no conditional guide properties of its own.
        // Per gridline, for the same reason as a tick: the `encode` block may read the value it
        // marks.
        val gridMetaFor = partMetadata(spec, "grid", tickDatum(tick), gridMeta)
        val gridNode =
          when (spec.orient) {
            Orient.BOTTOM,
            Orient.TOP ->
              RuleNode(
                ids.allocate(),
                at,
                gridRange.first + undoOffset,
                at,
                gridRange.second + undoOffset,
                strokeFor(spec, "grid", tickDatum(tick), gridStroke),
                metadata = gridMetaFor,
              )
            Orient.LEFT,
            Orient.RIGHT ->
              RuleNode(
                ids.allocate(),
                gridRange.first + undoOffset,
                at,
                gridRange.second + undoOffset,
                at,
                strokeFor(spec, "grid", tickDatum(tick), gridStroke),
                metadata = gridMetaFor,
              )
          }
        children += positioned(gridNode, spec, "grid", tickDatum(tick))
      }
    }

    if (spec.ticks) {
      val tickMeta = NodeMetadata(role = "axis-tick")
      for (tick in ticks) {
        // Per tick rather than once, because a tooltip may read the tick's own value: `{"signal":
        // "datum.value"}` on a tick is how an axis says what each mark stands for.
        val tickMetaFor = partMetadata(spec, "ticks", tickDatum(tick), tickMeta)
        val at = tickCoordinate(tick.position, spec)
        val paint = strokeFor(spec, "ticks", tickDatum(tick), tickStroke)
        val tickNode =
          when (spec.orient) {
            Orient.BOTTOM ->
              RuleNode(ids.allocate(), at, 0.0, at, tickSize, paint, metadata = tickMetaFor)
            Orient.TOP ->
              RuleNode(ids.allocate(), at, 0.0, at, -tickSize, paint, metadata = tickMetaFor)
            Orient.LEFT ->
              RuleNode(ids.allocate(), 0.0, at, -tickSize, at, paint, metadata = tickMetaFor)
            Orient.RIGHT ->
              RuleNode(ids.allocate(), 0.0, at, tickSize, at, paint, metadata = tickMetaFor)
          }
        val placedTick = positioned(tickNode, spec, "ticks", tickDatum(tick))
        drawn += placedTick
        measured += placedTick
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
            // The labels' own `encode` comes first: an angle supplied by a signal cannot be
            // compared at compile time, so Vega-Lite writes the *comparison* into the encode and
            // leaves the axis property off. Reading only the property left the labels at the
            // anchor the orientation would have chosen.
            align =
              alignOf(labelString(spec, "align", tick) ?: spec.labelAlign)
                ?: (if (spec.orient.isVertical) null else flushed?.let(::flushAlign))
                ?: labelAlign(spec.orient),
            baseline =
              baselineOf(labelString(spec, "baseline", tick) ?: spec.labelBaseline)
                ?: (if (spec.orient.isVertical) flushed?.let(::flushBaseline) else null)
                ?: labelBaseline(spec.orient),
            limit = labelLimit,
            ellipsis = labelString(spec, "ellipsis", tick) ?: "\u2026",
            lineBreak = labelString(spec, "lineBreak", tick)?.takeIf { it.isNotEmpty() },
          )
        val layout = textEngine.layout(run)
        // A label sits where *it* was placed, which on a band axis is the band's centre whatever
        // `bandPosition` did to the ticks. A null one is upstream's `NaN` — the extra tick carries
        // no value for the label to scale — and it stays a `NaN` here: a text node with no usable
        // anchor covers nothing and draws nothing, which is what upstream's scene and its own SVG
        // both say.
        // `labelOffset` slides the label along the axis — the other direction from `labelPadding`.
        // Applied here rather than where the ticks are generated, because it applies to every scale
        // type and a band scale's own centring is already in `labelPosition`.
        // `labelFlushOffset` nudges a flushed label *further* along the axis, away from the end it
        // was flushed to: the alignment alone puts a first label's left edge on the range's start,
        // and this pushes it inwards from there. Upstream applies it as a `dx`/`dy` and only when
        // the corresponding alignment was left to the flush rule to decide — an explicit
        // `labelAlign` means the label is not being flushed, so there is nothing to nudge.
        val flushNudge =
          if (flushed == null || flushOffset == 0.0) {
            0.0
          } else if (spec.orient.isVertical) {
            if (spec.labelBaseline != null) 0.0
            else if (flushed == FlushEnd.START) -flushOffset else flushOffset
          } else {
            if (spec.labelAlign != null) 0.0
            else if (flushed == FlushEnd.START) -flushOffset else flushOffset
          }
        val along = tick.labelPosition?.plus(labelAlong)?.plus(flushNudge) ?: Double.NaN
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
            metadata =
              partMetadata(spec, "labels", tickDatum(tick), NodeMetadata(role = "axis-label")),
          )
      }
      // An axis removes overlapping labels only when asked; a legend does it by default. The
      // asymmetry is upstream's — see LabelOverlap.
      val method = LabelOverlap.Method.fromValue(spec.labelOverlap)
      val reduced =
        if (method == null) labels
        else {
          LabelOverlap.visible(
            labels,
            method,
            numbers.resolve(spec.labelSeparation, spec.scale) ?: 0.0,
          )
        }
      // `labelBound` culls whatever still hangs outside the scale's own range, and it runs
      // **after**
      // the overlap reduction over *every* label rather than only the survivors — upstream's
      // `Overlap` does the bound test last, on `source`. It is what keeps the first and last labels
      // of a rotated axis from sticking out past the plot; the tolerance is how far they may, and
      // upstream's default when `labelBound: true` says nothing more precise is one unit.
      val kept = boundedLabels(reduced, spec, scale)
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
      // The domain line has no tick of its own, so its `encode` resolves against nothing — the same
      // empty datum a title uses.
      val domainMeta =
        partMetadata(spec, "domain", VegaValue.EmptyObject, NodeMetadata(role = "axis-domain"))
      // Upstream encodes the domain line's endpoints as range positions 0 and 1 of the axis scale,
      // so a scale that does not span the whole plotting area gets a correspondingly short line.
      val span = rangeEnds(scale)
      val from = span?.firstOrNull() ?: 0.0
      val to = span?.lastOrNull() ?: if (spec.orient.isVertical) extent.height else extent.width
      val domainNode =
        when (spec.orient) {
          Orient.BOTTOM,
          Orient.TOP ->
            RuleNode(ids.allocate(), from, 0.0, to, 0.0, domainStroke, metadata = domainMeta)
          Orient.LEFT,
          Orient.RIGHT ->
            RuleNode(ids.allocate(), 0.0, from, 0.0, to, domainStroke, metadata = domainMeta)
        }
      // The domain line has no tick of its own, so its `encode` resolves against the same empty
      // datum its metadata does.
      children += positioned(domainNode, spec, "domain", VegaValue.EmptyObject)
    }

    // A title may be a signal: a chart that lets a control choose the measure retitles the axis
    // with the choice, and there is no constant to write down.
    // `resolveLines`, not `resolveText`: an axis title given as an array is two lines, exactly as a
    // legend's is, and stringifying it would join them with a comma on one line.
    // The title's own `encode` block may replace the words, and what a screen reader hears is what
    // the
    // axis actually says: upstream's caption is built from the item's text, so a title written in
    // an
    // encode block is read out and the one it replaced is not.
    val titleText =
      titleString(spec, "text")
        ?: spec.title
        ?: spec.titleExpression?.let { numbers.resolveLines(it, spec.scale) }
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
            //
            // `aria: false` removes the axis from the accessibility tree entirely, and a
            // `description` replaces the caption this engine would otherwise generate — a chart
            // whose
            // axis is self-explanatory in context says so rather than having its scale read out.
            accessibility =
              if (!spec.aria) {
                null
              } else {
                (spec.description
                    ?: GuideCaption.axis(
                      spec.orient.name.lowercase(),
                      // Spoken as one phrase; see LegendBuilder.caption for why the newline goes.
                      titleText?.replace("\n", " "),
                      scale,
                      scaleTypes[spec.scale],
                      specifier,
                      spec.formatType,
                      locale,
                      timeZone,
                    ))
                  ?.let {
                    AccessibilityDescriptor(
                      label = it,
                      role = "graphics-symbol",
                      // Upstream's `AriaGuides`: a reader hears "axis" and then the caption.
                      roleDescription = "axis",
                      focusable = true,
                    )
                  }
              },
          ),
      )

    val delta = numbers.resolve(spec.translate, spec.scale) ?: AxisDefaults.CRISP_OFFSET
    val guide =
      extentRect(spec, scale, tickAndLabelReach)
        .union(tickAndLabelReach)
        .union(titleNode?.bounds ?: RectD.Empty)
        // The axis's own nudge onto the pixel grid is taken back out: upstream measures the axis at
        // `x` and only then places the item at `x + delta`, so the half pixel is in the drawing and
        // not in the measurement. `translate` is what that nudge is, which is why this reads it
        // back
        // rather than subtracting a constant — a `translate: 0` axis has nothing to take out.
        .translate(placement.e - delta, placement.f - delta)
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
    val range = rangeEnds(scale)
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

    // Along the axis, the title sits wherever the anchor says on the *scale's range*, not on the
    // plotting area — the two differ inside a group.
    val range = rangeEnds(scale)
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
        // A title's own `encode` block may replace the words, break them or truncate them, and none
        // of the three has a property behind it: `titleLimit` says *how wide*, and `ellipsis` says
        // what
        // the truncation looks like. The datum is empty — an axis title labels the axis, not a tick
        // —
        // which is upstream's `Collect(null, [{}])` for the same mark.
        text = titleString(spec, "text") ?: text,
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
        limit = numbers.resolve(spec.titleLimit, spec.scale) ?: 0.0,
        ellipsis = titleString(spec, "ellipsis") ?: "\u2026",
        lineBreak = titleString(spec, "lineBreak")?.takeIf { it.isNotEmpty() },
      )
    val layout = textEngine.layout(run)
    // A **multi-line** title sits further out by the height of its extra lines: upstream's
    // `axisTitleLayout` places it at `sign * (offset + dl + pad)` where `dl` is `multiLineOffset`.
    // Without it a two-line title crept back towards the axis and overlapped the labels, because
    // the
    // block grows away from an anchor baselined at its bottom.
    val extraLines = (layout.lines.size - 1) * layout.metrics.lineHeight
    val away =
      if (spec.orient == Orient.TOP || spec.orient == Orient.LEFT) {
        -(depth + extraLines + padding)
      } else {
        depth + extraLines + padding
      }
    // `dx` and `dy` nudge the title without changing what it is anchored to, exactly as they do on
    // a
    // label, so they are added to whatever placed it.
    val nudgeX = titleNumber(spec, "dx") ?: 0.0
    val nudgeY = titleNumber(spec, "dy") ?: 0.0
    return TextNode(
      id = ids.allocate(),
      x =
        (numbers.resolve(spec.titleX, spec.scale) ?: if (spec.orient.isVertical) away else along) +
          nudgeX,
      y =
        (numbers.resolve(spec.titleY, spec.scale) ?: if (spec.orient.isVertical) along else away) +
          nudgeY,
      layout = layout,
      angleDegrees =
        numbers.resolve(spec.titleAngle, spec.scale)
          ?: when (spec.orient) {
            Orient.LEFT -> -90.0
            Orient.RIGHT -> 90.0
            else -> 0.0
          },
      fill = GuideStyle.fill(spec.titleStyle, AxisDefaults.titleColor),
      metadata =
        partMetadata(
          spec,
          "title",
          VegaValue.EmptyObject,
          NodeMetadata(role = "axis-title", markName = spec.scale),
        ),
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
    // `translate` replaces the half pixel; `position` slides the axis along its own direction,
    // which
    // is the *other* axis from the one `offset` moves it along. Upstream's `axisLayout` computes
    // both
    // and adds the translation last, so `position` is measured before the nudge and not after it.
    val delta = numbers.resolve(spec.translate, spec.scale) ?: AxisDefaults.CRISP_OFFSET
    val along = numbers.resolve(spec.position, spec.scale) ?: 0.0
    return when (spec.orient) {
      Orient.BOTTOM -> Transform2D.translate(along + delta, extent.height + offset + delta)
      Orient.TOP -> Transform2D.translate(along + delta, -offset + delta)
      Orient.LEFT -> Transform2D.translate(-offset + delta, along + delta)
      Orient.RIGHT -> Transform2D.translate(extent.width + offset + delta, along + delta)
    }
  }

  /**
   * `labelBound`, which culls **nothing** — and that is upstream's behaviour, not a gap.
   *
   * The documented meaning is "drop a label that hangs past the scale's range", and implementing
   * that would make this engine disagree with upstream on every chart that sets it. Upstream's
   * `Overlap` transform applies the test as `boundRectangle.encloses(item.bounds)` and runs it
   * **before** the label bounds exist: `Bound` comes later in the mark's pipeline, so on a static
   * render every item still holds a *cleared* `Bounds` of `[+INF, +INF, -INF, -INF]`, which any
   * rectangle trivially encloses. Nothing is ever outside.
   *
   * Verified rather than reasoned: a band axis 120 units wide whose first label overflows by 68
   * keeps that label under `labelBound: false`, `true` and `40` alike. The `axis-label-bound`
   * fixture is that experiment, and it is why the property is consumed rather than reported — a
   * diagnostic saying "not implemented" would overstate a gap with no visible consequence.
   */
  private fun boundedLabels(
    labels: List<TextNode>,
    @Suppress("UNUSED_PARAMETER") spec: AxisSpec,
    @Suppress("UNUSED_PARAMETER") scale: VegaScale,
  ): List<TextNode> = labels

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
   * How many ticks to ask a continuous scale for, after `tickMinStep` has had its say.
   *
   * `tickMinStep` does not place ticks; it *caps the count* — `tickCount` in `vega-scale/ticks.js`.
   * A span that holds only one minimum step allows two ticks, its two ends, whatever the axis asked
   * for. Vega-Lite writes the step on every bucketed axis as one bucket's duration, so an axis over
   * two months offers two ticks rather than the sixteen a request for ten would otherwise produce.
   *
   * @param refine whether to keep shrinking the count while the step d3 would choose is still under
   *   the minimum. Upstream does that only for a plain numeric scale: `!scale.bins &&
   *   !isLogarithmic && !isTemporal`, because those three do not shrink their step monotonically
   *   with the count.
   */
  private fun tickCountFor(spec: AxisSpec, domain: List<Double>, refine: Boolean): Int {
    var count = numbers.resolveInt(spec.tickCount, spec.scale) ?: AxisDefaults.DEFAULT_TICK_COUNT
    val minStep = numbers.resolve(spec.tickMinStep, spec.scale) ?: return count
    if (domain.isEmpty()) return count
    val lo = minOf(domain.first(), domain.last())
    val hi = maxOf(domain.first(), domain.last())
    val spans = (hi - lo) / minStep
    // `Math.floor((hi - lo) / minStep || 1) + 1`: a span of nothing still allows two ticks.
    val allowed = floor(if (spans.isFinite() && spans != 0.0) spans else 1.0).toInt() + 1
    count = minOf(count, allowed)
    if (refine && lo < hi) {
      while (count > 1 && Ticks.stepFrom(Ticks.tickIncrement(lo, hi, count)) < minStep) count--
    }
    return count.coerceAtLeast(1)
  }

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

  /**
   * The two ends of the scale's range, which is what an axis is drawn along.
   *
   * Upstream reads them off the scale's own range whatever kind of scale it is — `{"scale": s,
   * "range": 0}` and `{"scale": s, "range": 1}` for the domain line's endpoints, and
   * `span(range(s))` for the length. That includes a **discretizing** scale, whose range is a list
   * of steps rather than an interval: its first and last entries are still the two ends, so the
   * axis spans them and its title is centred between them. Reading only [PositionScale] here left
   * every such axis drawn from zero, with a title stacked at the origin.
   */
  private fun rangeEnds(scale: VegaScale): List<Double>? =
    when (scale) {
      is PositionScale -> scale.range
      is BinnedScale -> scale.rangeValues.mapNotNull { it.asNumberOrNull() }.takeIf { it.size >= 2 }
      else -> null
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
  private fun labelText(spec: AxisSpec, tick: Tick): String? = labelString(spec, "text", tick)

  /**
   * A text channel of a label's own `encode`, resolved against the tick.
   *
   * `text` is the one a specification usually writes, but `ellipsis` and `lineBreak` are read the
   * same way and change what the reader sees: the first is the mark a truncated label ends with and
   * the second is the character a long label is broken on, so a path can be shown as a stack.
   * Neither has a guide *property* upstream, which is why they are resolved here rather than
   * folded.
   */
  /**
   * The item metadata one part of a guide's `encode` can carry: a tooltip, a cursor, a paint order
   * and what a screen reader hears.
   *
   * These are the five that reach the **item** rather than its geometry or its paint, and a guide
   * had no way to set any of them: a hoverable axis label, a tick that says what it marks, a label
   * raised over its neighbours, a decorative title kept out of the accessibility tree. Every one is
   * already a mark channel, and this is the same reading against the guide's own datum.
   *
   * `aria: false` removes the item from the tree — upstream marks it `aria-hidden` — and
   * `description` replaces what would otherwise be spoken. The two are read together for that
   * reason: a description on an item that has opted out is not a contradiction, it is simply
   * unreachable.
   */
  private fun partMetadata(
    spec: AxisSpec,
    part: String,
    datum: VegaValue,
    base: NodeMetadata,
  ): NodeMetadata {
    val block = spec.encode[part]?.update ?: return base
    val encoder = channels ?: return base
    fun channel(name: String) = block[name]
    val tooltip =
      channel("tooltip")?.let { encoder.channelAny(it, datum) }?.takeIf { it !is VegaValue.Null }
    val cursor =
      channel("cursor")?.let { encoder.channelText(it, datum) }?.takeIf { it.isNotEmpty() }
    val href = channel("href")?.let { encoder.channelText(it, datum) }?.takeIf { it.isNotEmpty() }
    val zindex = channel("zindex")?.let { encoder.channelNumber(it, datum) }?.toInt()
    val hidden = channel("aria")?.let { encoder.channelBoolean(it, datum) } == false
    val description =
      channel("description")?.let { encoder.channelText(it, datum) }?.takeIf { it.isNotBlank() }
    return base.copy(
      tooltip = tooltip ?: base.tooltip,
      cursor = cursor ?: base.cursor,
      href = href ?: base.href,
      zindex = zindex ?: base.zindex,
      accessibility =
        when {
          hidden -> null
          description != null ->
            AccessibilityDescriptor(label = description, role = "graphics-symbol", focusable = true)
          else -> base.accessibility
        },
    )
  }

  /** The datum a guide part's `encode` resolves against: the tick, or nothing for a title. */
  private fun tickDatum(tick: Tick): VegaValue =
    VegaValue.Obj(linkedMapOf("value" to tick.value, "label" to VegaValue.Str(tick.label)))

  /** A text channel of the axis **title**'s own `encode`, whose datum is empty. */
  private fun titleString(spec: AxisSpec, channel: String): String? {
    val encoder = channels ?: return null
    val entry = spec.encode["title"]?.update?.get(channel) ?: return null
    return encoder.channelText(entry, VegaValue.EmptyObject)?.takeIf { it.isNotEmpty() }
  }

  private fun titleNumber(spec: AxisSpec, channel: String): Double? {
    val encoder = channels ?: return null
    val entry = spec.encode["title"]?.update?.get(channel) ?: return null
    return encoder.channelNumber(entry, VegaValue.EmptyObject)?.takeIf { it.isFinite() }
  }

  private fun labelString(spec: AxisSpec, channel: String, tick: Tick): String? {
    val encoder = channels ?: return null
    val entry = spec.encode["labels"]?.update?.get(channel) ?: return null
    val datum =
      VegaValue.Obj(linkedMapOf("value" to tick.value, "label" to VegaValue.Str(tick.label)))
    return encoder.channelText(entry, datum)
  }

  /**
   * A part's own `encode` restyling **one** line rather than all of them.
   *
   * A guide encode channel is normally folded into the property it duplicates at parse time —
   * `encode.ticks.update.strokeWidth` *is* `tickWidth` — and that fold is right for a constant and
   * for a signal over the chart's own state. It cannot carry a signal that reads `datum`, because
   * at parse time there is no tick to read: `{"signal": "datum.value === marked ? 2 : 1"}` resolved
   * to the false branch for every tick. Resolving the same channels again here, per tick, costs
   * nothing where they are constant — the answer is the one the fold already produced — and is the
   * only way the datum-dependent form can work at all.
   */
  private fun strokeFor(
    spec: AxisSpec,
    part: String,
    datum: VegaValue,
    base: Stroke,
  ): Stroke {
    val block = spec.encode[part]?.update ?: return base
    val encoder = channels ?: return base
    fun number(name: String) = block[name]?.let { encoder.channelNumber(it, datum) }
    fun text(name: String) =
      block[name]?.let { encoder.channelText(it, datum) }?.takeIf { it.isNotEmpty() }
    val colour = text("stroke")?.let { SceneColor.parse(it) }
    // A dash pattern is an array, so it comes back as a value rather than a number.
    val dash =
      (block["strokeDash"]?.let { encoder.channelAny(it, datum) } as? VegaValue.Arr)
        ?.values
        ?.map { it.asDouble() }
        ?.takeIf { it.isNotEmpty() }
    return base.copy(
      paint = colour?.let { ScenePaint.Solid(it) } ?: base.paint,
      width = number("strokeWidth") ?: base.width,
      cap = text("strokeCap")?.let { GuideStyle.capOf(it) } ?: base.cap,
      dashArray = dash ?: base.dashArray,
      dashOffset = number("strokeDashOffset") ?: base.dashOffset,
      opacity = number("strokeOpacity") ?: base.opacity,
    )
  }

  /**
   * A part's own `encode` moving the line the axis drew.
   *
   * `encode.ticks.update.y` is not decoration: upstream merges the block into the tick's own
   * encoders and applies it **last**, so a specification can lift every tick off the axis or
   * stretch one across the plot — `warming-stripes` reaches through a tick to draw a marker at a
   * temperature. Probed rather than assumed, because a guide writing its own geometry on every pass
   * could just as easily have overwritten it: `{"y": {"value": -3}, "x2": {"value": 13}}` on a
   * bottom axis gives `y = -3` and `x2 = 13` with the computed `x` and `y2` left alone.
   */
  private fun positioned(node: RuleNode, spec: AxisSpec, part: String, datum: VegaValue): RuleNode {
    val block = spec.encode[part]?.update ?: return node
    val encoder = channels ?: return node
    fun at(name: String) = block[name]?.let { encoder.channelNumber(it, datum) }
    val x1 = at("x")
    val y1 = at("y")
    val x2 = at("x2")
    val y2 = at("y2")
    if (x1 == null && y1 == null && x2 == null && y2 == null) return node
    return node.copy(
      x1 = x1 ?: node.x1,
      y1 = y1 ?: node.y1,
      x2 = x2 ?: node.x2,
      y2 = y2 ?: node.y2,
    )
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

  /**
   * A tick's drawn coordinate, rounded unless `tickRound: false` says not to.
   *
   * Rounding is what keeps a one-unit tick on a pixel centre rather than straddling two, and
   * upstream's default is on. Switching it off is for a chart drawn at a fractional device ratio,
   * where rounding to whole units moves a tick by up to half a device pixel.
   */
  private fun tickCoordinate(position: Double, spec: AxisSpec): Double =
    if (spec.tickRound == false) position else AxisDefaults.crispRound(position)

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
    // `formatType` decides the grammar and the shared formatter knows how; see [GuideFormat].
    GuideFormat.timeLabeller(format, formatType, locale, timeZone)?.let { write ->
      return { value ->
        val instant = value.asDouble()
        if (instant.isNaN()) value.asString() else write(instant)
      }
    }
    // A **time** scale reads its specifier as a time specifier, without needing a `formatType` to
    // say so: upstream's `tickFormat` asks the scale, and a temporal scale's own formatter is d3's
    // `timeFormat`. Falling through to the numeric branch below fed `%b %d` to a number formatter,
    // which printed the epoch milliseconds unchanged — a chart labelled `1580515200000` where
    // upstream labelled it `Feb 01`.
    if (format != null && scale is TimeScale) {
      return { value ->
        val instant = value.asDouble()
        if (instant.isNaN()) value.asString()
        else TimeFormat.format(instant, format, scale.zone, locale)
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
      val labeller = Ticks.spanFormatter(format, numeric.first(), numeric.last(), count, locale)
      return { value ->
        val number = value.asDouble()
        if (number.isNaN()) value.asString() else labeller(number)
      }
    }
    return when (scale) {
      is LinearScale -> { value ->
        scale.formatTick(value.asDouble(), count, locale)
      }
      is TransformedScale -> { value ->
        scale.formatTick(value.asDouble(), count, locale)
      }
      // A time label is written at its own granularity — a January tick carries the year — so it
      // comes from the tick itself rather than from a shared precision.
      is TimeScale -> { value ->
        TimeTicks.label(value.asDouble(), scale.zone, locale)
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
          GuideFormat.countWithMinStep(
            numbers.resolveInt(spec.tickCount, spec.scale) ?: AxisDefaults.DEFAULT_TICK_COUNT,
            numbers.resolve(spec.tickMinStep, spec.scale),
            scale.domain,
            linear = true,
          )
        // Labels come from the scale rather than being formatted here, because a log scale blanks
        // the
        // crowded ones and only it knows which.
        val format = labeller(scale, count, specifier, spec.formatType)
        scale.ticks(count).zip(scale.tickLabels(count, locale)).map { (value, label) ->
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
          GuideFormat.countWithMinStep(
            numbers.resolveInt(spec.tickCount, spec.scale) ?: AxisDefaults.DEFAULT_TICK_COUNT,
            numbers.resolve(spec.tickMinStep, spec.scale),
            scale.domain,
            // A log or power scale's steps are not linear in the count, so upstream applies only
            // the
            // cap and not the walk-down.
            linear = false,
          )
        val format = labeller(scale, count, specifier, spec.formatType)
        scale.ticks(count).zip(scale.tickLabels(count, locale)).map { (value, label) ->
          Tick(
            if (specifier == null && spec.formatType == null) label
            else format(VegaValue.Num(value)),
            scale.apply(value),
            VegaValue.Num(value),
          )
        }
      }
      is TimeScale -> {
        // `tickCount` may name a calendar unit instead of a number, and then it decides the ticks
        // outright: every boundary of that unit inside the domain, however many that is. Upstream
        // hands the interval to the scale in place of the count, so nothing downstream chooses a
        // step at all.
        val stepper =
          TimeInterval.forUnit(spec.tickInterval)?.let { (interval, implied) ->
            // `"quarter"` carries a step of its own — three months — and a `step` written beside it
            // multiplies rather than replaces it, which is what `timeMonth.every(3).every(n)`
            // means.
            TimeStepper(interval, implied * (spec.tickStep ?: 1), scale.zone)
          }
        if (stepper != null) {
          val format = labeller(scale, AxisDefaults.DEFAULT_TICK_COUNT, specifier, spec.formatType)
          return TimeTicks.intervalTicks(scale.domain, stepper).map { value ->
            Tick(
              if (specifier == null && spec.formatType == null)
                TimeTicks.label(value, scale.zone, locale)
              else format(VegaValue.Num(value)),
              scale.apply(value),
              VegaValue.Num(value),
            )
          }
        }
        val count =
          GuideFormat.countWithMinStep(
            numbers.resolveInt(spec.tickCount, spec.scale) ?: AxisDefaults.DEFAULT_TICK_COUNT,
            numbers.resolve(spec.tickMinStep, spec.scale),
            scale.domain,
            linear = false,
          )
        // A time scale labels each tick at its own granularity — a January tick carries the year —
        // *unless* the axis names a format, which then applies to every tick alike. The linear
        // branch
        // above has always made that distinction and this one had not, so an explicit `%b` on a
        // time
        // axis was ignored and every label came back multi-formatted.
        val format = labeller(scale, count, specifier, spec.formatType)
        scale.ticks(count).zip(scale.tickLabels(count, locale)).map { (value, label) ->
          Tick(
            if (specifier == null && spec.formatType == null) label
            else format(VegaValue.Num(value)),
            scale.apply(value),
            VegaValue.Num(value),
          )
        }
      }
      // The four discretizing scales, whose output is a short list of steps rather than a length.
      // Upstream's `tickValues` decides among them by what the scale *has*: bins first, then a
      // `ticks` method, and the domain when it has neither — which is why each of the four ticks at
      // something different, and only one of them is filtered.
      is BinnedScale -> {
        val count =
          numbers.resolveInt(spec.tickCount, spec.scale) ?: AxisDefaults.DEFAULT_TICK_COUNT
        val values =
          when (scale) {
            // d3's quantize delegates to the linear scale it is built on, so an axis on one is
            // ticked over the *domain* it buckets rather than at the buckets.
            is QuantizeScale -> Ticks.ticks(scale.domain.first(), scale.domain.last(), count)
            // A quantile scale's domain is the column itself: one tick per sample.
            is QuantileScale -> scale.sampleDomain
            // A threshold scale's domain *is* its cut points.
            is ThresholdScale -> scale.domain
            // The one with `bins`, so the one that is filtered and thinned; see [validTicks].
            is BinOrdinalScale -> validTicks(scale, scale.bins, maxOf(count, scale.bins.size))
          }
        val label = binnedLabeller(scale, count, specifier, spec.formatType)
        values.map { value ->
          // The position is whatever the scale maps the value *to*, which for a scale whose range
          // is
          // a list of colours is not a number at all: upstream writes NaN onto the item and draws
          // nothing, and so does this.
          val at = scale.scale(VegaValue.Num(value)).asNumberOrNull() ?: Double.NaN
          Tick(label(VegaValue.Num(value)), at, VegaValue.Num(value))
        }
      }
      else -> null
    }

  /**
   * Upstream's `validTicks`: keep the candidates that land inside the range, in range order.
   *
   * The filter is what drops a bin scale's last edge — it bounds the topmost bucket rather than
   * opening one, so it maps to nothing — and the thinning that follows is the same halving a long
   * `values` list gets, ends restored when it overshoots below three.
   */
  /**
   * How a discretizing scale's ticks are labelled, which is not how a positional scale's are.
   *
   * Upstream asks the scale for a `tickFormat` and falls back to plain string coercion when it has
   * none — and of the four, only `quantize` has one, because d3 builds it on a linear scale. So a
   * `format` specifier on an axis over a threshold, quantile or bin-ordinal scale is **ignored**
   * upstream rather than applied, and ignoring it here is the faithful reading rather than an
   * omission.
   */
  private fun binnedLabeller(
    scale: BinnedScale,
    count: Int,
    specifier: String?,
    formatType: String?,
  ): (VegaValue) -> String {
    if (scale is QuantizeScale) {
      GuideFormat.timeLabeller(specifier, formatType, locale, timeZone)?.let { write ->
        return { value ->
          val instant = value.asDouble()
          if (instant.isNaN()) value.asString() else write(instant)
        }
      }
      val low = scale.domain.firstOrNull() ?: 0.0
      val high = scale.domain.lastOrNull() ?: 1.0
      if (specifier != null) {
        val labeller = Ticks.spanFormatter(specifier, low, high, count, locale)
        return { value -> labeller(value.asDouble()) }
      }
      val step = Ticks.stepFrom(Ticks.tickIncrement(low, high, count))
      val precision = if (step.isFinite()) Ticks.precisionForStep(step) else 0
      return { value -> formatTickLabel(value.asDouble(), precision, locale) }
    }
    return { value -> value.asString() }
  }

  private fun validTicks(scale: BinnedScale, candidates: List<Double>, count: Int): List<Double> {
    val positions = scale.rangeValues.mapNotNull { it.asNumberOrNull() }
    if (positions.isEmpty()) return emptyList()
    val low = kotlin.math.floor(positions.min())
    val high = kotlin.math.ceil(positions.max())
    val placed =
      candidates
        .map { it to (scale.scale(VegaValue.Num(it)).asNumberOrNull() ?: Double.NaN) }
        .filter { (_, at) -> at.isFinite() && at >= low && at <= high }
        .sortedBy { it.second }
    return thin(placed, count).map { it.first }
  }
}
