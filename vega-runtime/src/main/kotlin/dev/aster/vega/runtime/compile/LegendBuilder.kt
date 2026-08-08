package dev.aster.vega.runtime.compile

import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asString
import dev.aster.vega.model.spec.Direction
import dev.aster.vega.model.spec.LegendOrient
import dev.aster.vega.model.spec.LegendSpec
import dev.aster.vega.model.spec.LegendType
import dev.aster.vega.runtime.scale.BandScale
import dev.aster.vega.runtime.scale.BinnedScale
import dev.aster.vega.runtime.scale.LinearScale
import dev.aster.vega.runtime.scale.OrdinalScale
import dev.aster.vega.runtime.scale.PointScale
import dev.aster.vega.runtime.scale.SequentialColorScale
import dev.aster.vega.runtime.scale.TimeScale
import dev.aster.vega.runtime.scale.TransformedScale
import dev.aster.vega.runtime.scale.VegaScale
import dev.aster.vega.runtime.scale.formatTickLabel
import dev.aster.vega.scene.AccessibilityDescriptor
import dev.aster.vega.scene.Fill
import dev.aster.vega.scene.GradientStop
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.NodeMetadata
import dev.aster.vega.scene.RectD
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.SceneColor
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.SceneNodeId
import dev.aster.vega.scene.SceneNodeIdAllocator
import dev.aster.vega.scene.ScenePaint
import dev.aster.vega.scene.SizeD
import dev.aster.vega.scene.Stroke
import dev.aster.vega.scene.SymbolNode
import dev.aster.vega.scene.SymbolShape
import dev.aster.vega.scene.TextAlign
import dev.aster.vega.scene.TextBaseline
import dev.aster.vega.scene.TextEngine
import dev.aster.vega.scene.TextNode
import dev.aster.vega.scene.TextRun
import dev.aster.vega.scene.Transform2D
import dev.aster.vega.scene.transformedBounds
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The rectangles legend placement measures against.
 *
 * Upstream keeps two, not one, and the distinction is invisible until a chart has axes: a legend at
 * the right is pushed out by the *vertical* axes only, and one at the bottom by the horizontal
 * ones. A left axis therefore never moves a right-hand legend, even though it enlarges the drawing.
 */
internal data class GuideBounds(val horizontal: RectD, val vertical: RectD) {
  companion object {
    fun of(extent: PlotSize) =
      GuideBounds(
        RectD(0.0, 0.0, extent.width, extent.height),
        RectD(0.0, 0.0, extent.width, extent.height),
      )
  }
}

/**
 * Generates legend scene nodes: symbol swatches, gradient ramps, labels, titles and placement.
 *
 * All of the geometry is upstream's, established by reading the scenegraph a legend produces rather
 * than the documentation, because legend layout is pure arithmetic on [LegendDefaults] and a single
 * wrong constant moves every entry. The two least guessable parts:
 * - a symbol entry's row height is `max(ceil(sqrt(symbolSize) + symbolStrokeWidth),
 *   labelFontSize)`, which is why a 100-unit symbol next to a 10pt label occupies 12 units and not
 *   10 or 11
 * - a gradient swatch is a linear gradient sampled at `scale.ticks(15)` plus the domain ends, so a
 *   `[0, 100]` domain gets 21 stops rather than the two a naive implementation would emit
 *
 * Not generated: multi-column entry grids, label overlap removal, entry limits, `encode` overrides
 * and discrete (banded) gradients. The parser reports each of those.
 */
internal class LegendBuilder(
  private val scales: Map<String, VegaScale>,
  private val ids: SceneNodeIdAllocator,
  private val textEngine: TextEngine,
  private val diagnostics: DiagnosticCollector,
  private val numbers: NumberResolver,
  /**
   * Resolves a legend `encode` channel that has no property behind it — a label read through a
   * scale, a swatch's fill opacity. Optional, because a legend built without one still draws.
   */
  private val channels: MarkEncoder? = null,
) {

  /** The datum a legend part's `encode` block is resolved against: the entry it is drawing. */
  private fun entryDatum(entry: Entry): VegaValue =
    VegaValue.Obj(linkedMapOf("value" to entry.value, "label" to VegaValue.Str(entry.label)))

  /**
   * One channel of a legend part's `encode`, resolved against the entry, as a number.
   *
   * `enter` and `update` both, unlike an axis label's position: a legend writes its swatch's paint
   * in `enter` and leaves it there, so an `enter` block survives where an axis label's would not.
   */
  private fun entryNumber(spec: LegendSpec, part: String, channel: String, entry: Entry): Double? {
    val encoder = channels ?: return null
    val block = spec.encode[part] ?: return null
    return encoder.channelNumber(block.effective[channel] ?: return null, entryDatum(entry))
  }

  /** The same, as text — which is what turns a legend entry's id into its name. */
  private fun entryText(spec: LegendSpec, part: String, channel: String, entry: Entry): String? {
    val encoder = channels ?: return null
    val block = spec.encode[part] ?: return null
    return encoder.channelText(block.effective[channel] ?: return null, entryDatum(entry))
  }

  /**
   * One legend, sized but not yet placed.
   *
   * Its node id is reserved before its content is built, so a legend group still numbers lower than
   * everything inside it — the hit index reads ids as paint order, and a group that outnumbered its
   * own children would win every tap meant for them.
   */
  private class Built(
    val spec: LegendSpec,
    val id: SceneNodeId,
    val content: List<SceneNode>,
    val size: SizeD,
  )

  /**
   * Builds every legend and places it relative to the plotting area.
   *
   * @param extent the enclosing group's size, which the corner orientations measure against.
   * @param guides where the axes reach, which pushes an edge-placed legend outwards.
   */
  fun build(specs: List<LegendSpec>, extent: PlotSize, guides: GuideBounds): List<SceneNode> {
    if (specs.isEmpty()) return emptyList()

    val built = specs.sortedBy { it.zindex }.mapNotNull { buildOne(it) }
    val placed = mutableListOf<SceneNode>()
    // Legends sharing an orientation stack against each other, so they are placed as a group.
    for ((orient, group) in built.groupBy { it.spec.orient }) {
      placed += place(orient, group, extent, guides)
    }
    return placed
  }

  // ---- one legend's content ---------------------------------------------------

  private fun buildOne(spec: LegendSpec): Built? {
    val id = ids.allocate()
    val scaleName = spec.scale ?: return null
    val scale = scales[scaleName]
    if (scale == null) {
      diagnostics.error(
        DiagnosticCodes.SCALE_UNSUPPORTED_TYPE,
        "Legend refers to scale '$scaleName', which was not built; the legend was skipped",
        operator = scaleName,
      )
      return null
    }

    val type = resolveType(spec, scale) ?: return null
    val padding = numbers.resolve(spec.padding, scaleName) ?: LegendDefaults.PADDING
    val titlePadding = numbers.resolve(spec.titlePadding, scaleName) ?: LegendDefaults.TITLE_PADDING

    val title = spec.title?.let { titleNode(spec, scaleName, it, padding) }
    val titleReach = title?.let { it.bounds.height + titlePadding } ?: 0.0

    val entries =
      when (type) {
        LegendType.SYMBOL -> symbolEntries(spec, scale, scaleName)
        LegendType.GRADIENT -> gradientEntries(spec, scale, scaleName)
        LegendType.DISCRETE -> null
      } ?: return null

    val body =
      GroupNode(
        id = ids.allocate(),
        children = entries,
        transform = Transform2D.translate(padding, padding + titleReach),
        metadata = NodeMetadata(role = "legend-entry", markName = scaleName),
      )

    val content = listOfNotNull(body, title)
    // Upstream anchors the content bounds at the padding and rounds the result up, so a legend is
    // always a whole number of units across.
    val bounds = content.fold(RectD.Empty) { acc, node -> acc.union(node.transformedBounds) }
    val size =
      if (bounds.isEmpty) SizeD(2 * padding, 2 * padding)
      else SizeD(ceil(bounds.right + padding), ceil(bounds.bottom + padding))
    return Built(spec, id, content, size)
  }

  /**
   * Decides between a symbol legend and a gradient one.
   *
   * Upstream only derives the type when the legend maps a single colour channel; a legend that also
   * encodes `size` or `shape` has to be symbols, because a gradient cannot show them.
   */
  private fun resolveType(spec: LegendSpec, scale: VegaScale): LegendType? {
    val stated = spec.type
    if (stated == LegendType.DISCRETE) {
      diagnostics.warn(
        DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED,
        "Discrete (banded) gradient legends are not implemented; legend for scale " +
          "'${spec.scale}' was skipped",
        operator = spec.scale,
      )
      return null
    }
    if (stated != null) return stated

    val colourOnly = spec.channelCount == 1 && (spec.fill != null || spec.stroke != null)
    return if (colourOnly && scale is SequentialColorScale) LegendType.GRADIENT
    else LegendType.SYMBOL
  }

  private fun titleNode(
    spec: LegendSpec,
    scaleName: String,
    text: String,
    padding: Double,
  ): TextNode {
    val fontSize = numbers.resolve(spec.titleFontSize, scaleName) ?: LegendDefaults.TITLE_FONT_SIZE
    val run =
      TextRun(
        text = text,
        style = GuideStyle.text(spec.titleStyle, fontSize, LegendDefaults.TITLE_FONT_WEIGHT),
        align = TextAlign.LEFT,
        baseline = TextBaseline.TOP,
      )
    return TextNode(
      id = ids.allocate(),
      x = padding,
      y = padding,
      layout = textEngine.layout(run),
      fill = GuideStyle.fill(spec.titleStyle, LegendDefaults.titleColor),
      metadata = NodeMetadata(role = "legend-title", markName = spec.scale),
    )
  }

  // ---- symbol legends ---------------------------------------------------------

  /** One legend entry: the value the scale maps, and the text shown beside it. */
  private class Entry(val value: VegaValue, val label: String)

  /**
   * How many decimals a set of cut points needs to stay distinguishable.
   *
   * A banded legend labels the cut points, and they are derived rather than chosen — quartiles of a
   * column land wherever they land. Formatting them all to the same width is what makes the column
   * of labels readable, and taking the width from the *values* rather than from a tick step is what
   * stops `2.5` being labelled `2` beside a `5`.
   */
  private fun decimalsFor(values: List<Double>): Int {
    for (decimals in 0..6) {
      if (values.all { kotlin.math.abs(it - roundTo(it, decimals)) < 1e-9 }) return decimals
    }
    return 6
  }

  private fun roundTo(value: Double, decimals: Int): Double {
    var factor = 1.0
    repeat(decimals) { factor *= 10 }
    return kotlin.math.round(value * factor) / factor
  }

  private fun symbolEntries(
    spec: LegendSpec,
    scale: VegaScale,
    scaleName: String,
  ): List<SceneNode> {
    val entries = entryValues(spec, scale, scaleName)
    if (entries.isEmpty()) return emptyList()

    val vertical = isVertical(spec)
    val labelFontSize =
      numbers.resolve(spec.labelFontSize, scaleName) ?: LegendDefaults.LABEL_FONT_SIZE
    val labelOffset = numbers.resolve(spec.labelOffset, scaleName) ?: LegendDefaults.LABEL_OFFSET
    val strokeWidth =
      numbers.resolve(spec.symbolStrokeWidth, scaleName) ?: LegendDefaults.SYMBOL_STROKE_WIDTH
    val declaredSize = numbers.resolve(spec.symbolSize, scaleName) ?: LegendDefaults.SYMBOL_SIZE
    val shape = symbolShape(spec)

    val sizes = entries.map { symbolSizeFor(spec, it.value, declaredSize) }
    // A legend over a *shape* scale draws each entry with the shape that scale gives it, rather
    // than one symbol repeated down the column. The legend exists to say which outline means which
    // category, so a column of identical circles is not a smaller version of the right answer.
    val shapes = entries.map { symbolShapeFor(spec, it.value, shape) }
    // A row is as tall as the taller of its symbol and its label, and upstream rounds the symbol's
    // contribution up before comparing: this is the number every offset within a cell derives from.
    val boxes = sizes.map { maxOf(ceil(sqrt(it) + strokeWidth), labelFontSize) }
    // A vertical legend aligns every label at the widest symbol; a horizontal one packs each entry
    // against its own symbol. That is upstream's `datum.offset` versus `datum.size`.
    val widest = boxes.max()

    val labelStyle = GuideStyle.text(spec.labelStyle, labelFontSize, defaultWeight = 400)
    val labelLimit = numbers.resolve(spec.labelLimit, scaleName) ?: LegendDefaults.LABEL_LIMIT

    // Build each entry at its own origin first: the layout below needs to know how far a cell
    // reaches
    // before it can decide where the next one starts.
    val cells = entries.mapIndexed { index, entry ->
      val box = boxes[index]
      val anchor = if (vertical) widest else box
      val centre = box * 0.5
      val labelX = anchor + LegendDefaults.SYMBOL_OFFSET + labelOffset
      val run =
        TextRun(
          text = entryText(spec, "labels", "text", entry) ?: entry.label,
          style = labelStyle,
          align = TextAlign.LEFT,
          baseline = TextBaseline.MIDDLE,
          limit = labelLimit,
        )
      listOf(
        SymbolNode(
          id = ids.allocate(),
          x = anchor * 0.5 + LegendDefaults.SYMBOL_OFFSET,
          y = centre,
          size = sizes[index],
          shape = shapes[index],
          fill =
            symbolFill(spec, entry.value)?.let { fill ->
              // `fillOpacity` fades what is inside the swatch and leaves its outline alone, which
              // is not what `symbolOpacity` does — that fades both. There is no property for the
              // first, so it comes from the encode block or not at all.
              entryNumber(spec, "symbols", "fillOpacity", entry)?.let { fill.copy(opacity = it) }
                ?: fill
            },
          stroke = symbolStroke(spec, entry.value, strokeWidth),
          // `symbolOpacity` is the item's overall opacity upstream, not a fill or stroke opacity —
          // it fades the outline with the swatch rather than only what is inside it. The `encode`
          // block wins over the property, and it is where an *interactive* legend lives: a swatch
          // that dims when its series is deselected writes a conditional rule here, and there is no
          // property that could express one.
          opacity =
            entryNumber(spec, "symbols", "opacity", entry) ?: spec.symbolStyle.opacity ?: 1.0,
          metadata = NodeMetadata(role = "legend-symbol", markName = scaleName, datumIndex = index),
        ),
        TextNode(
          id = ids.allocate(),
          x = labelX,
          y = centre,
          layout = textEngine.layout(run),
          fill = GuideStyle.fill(spec.labelStyle, LegendDefaults.labelColor),
          metadata = NodeMetadata(role = "legend-label", markName = scaleName, datumIndex = index),
        ),
      )
    }

    val rowPadding = numbers.resolve(spec.rowPadding, scaleName) ?: LegendDefaults.ROW_PADDING
    val columnPadding =
      numbers.resolve(spec.columnPadding, scaleName) ?: LegendDefaults.COLUMN_PADDING
    // Upstream's default is one column when the entries run down and one row when they run across;
    // `columns` overrides either.
    val columns =
      numbers.resolveInt(spec.columns, scaleName)?.coerceAtLeast(1)
        ?: if (vertical) 1 else cells.size
    return place(cells, columns, rowPadding, columnPadding, scaleName)
  }

  /**
   * Places the entries in a grid.
   *
   * A multi-column legend fills *down* each column before moving across, which is how a reader
   * scans a list; the nodes come back in row-major order, which is the order they are drawn in.
   */
  private fun place(
    cells: List<List<SceneNode>>,
    columns: Int,
    rowPadding: Double,
    columnPadding: Double,
    scaleName: String,
  ): List<SceneNode> {
    val order = GridLayout.columnMajorOrder(cells.size, columns)
    val ordered = order.map { cells[it] }
    val boxes = ordered.map { cell ->
      cell.fold(RectD.Empty) { acc, node -> acc.union(node.bounds) }
    }
    val offsets = GridLayout.place(boxes, GridLayout.Options(columns, rowPadding, columnPadding))

    return ordered.indices.map { position ->
      val offset = offsets[position]
      GroupNode(
        id = ids.allocate(),
        children = ordered[position],
        transform = Transform2D.translate(offset.x, offset.y),
        // Upstream calls this a "scope" group; naming it for what it is keeps a legend entry
        // distinguishable from a group mark's cell, which shares that role.
        metadata =
          NodeMetadata(
            role = "legend-entry-item",
            markName = scaleName,
            datumIndex = order[position],
          ),
      )
    }
  }

  private fun symbolShape(spec: LegendSpec): SymbolShape {
    val name = spec.symbolType ?: return SymbolShape.CIRCLE
    return namedShape(name)
      ?: run {
        diagnostics.warn(
          DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
          "Legend symbolType '$name' is not implemented; drawing a circle instead",
          operator = spec.scale,
        )
        SymbolShape.CIRCLE
      }
  }

  /** Vega's symbol names, which a `shape` scale's range and a legend's `symbolType` both use. */
  private fun namedShape(name: String): SymbolShape? =
    when (name.lowercase()) {
      "circle" -> SymbolShape.CIRCLE
      "square" -> SymbolShape.SQUARE
      "cross" -> SymbolShape.CROSS
      "diamond" -> SymbolShape.DIAMOND
      "triangle" -> SymbolShape.TRIANGLE
      "triangle-up" -> SymbolShape.TRIANGLE_UP
      "triangle-down" -> SymbolShape.TRIANGLE_DOWN
      "triangle-left" -> SymbolShape.TRIANGLE_LEFT
      "triangle-right" -> SymbolShape.TRIANGLE_RIGHT
      "stroke" -> SymbolShape.STROKE
      "arrow" -> SymbolShape.ARROW
      "wedge" -> SymbolShape.WEDGE
      else -> null
    }

  /** A `size` legend takes each swatch's size from the scale; every other legend uses one size. */
  /**
   * The outline one legend entry draws with.
   *
   * A `shape` scale maps the entry's own value to a symbol name; anything else repeats the legend's
   * `symbolType`. An unmappable value falls back to that too, rather than to a blank space.
   */
  private fun symbolShapeFor(
    spec: LegendSpec,
    value: VegaValue,
    declared: SymbolShape,
  ): SymbolShape {
    val shapeScale = spec.shape?.let { scales[it] } ?: return declared
    val mapped = (shapeScale.scale(value) as? VegaValue.Str)?.value ?: return declared
    return namedShape(mapped) ?: declared
  }

  private fun symbolSizeFor(spec: LegendSpec, value: VegaValue, declared: Double): Double {
    val sizeScale = spec.size?.let { scales[it] } ?: return declared
    val mapped = sizeScale.scale(value)
    val number = (mapped as? VegaValue.Num)?.value
    return if (number != null && number.isFinite() && number > 0.0) number else declared
  }

  /**
   * A legend swatch's fill.
   *
   * A legend that maps no colour still gets an explicit transparent fill rather than none, which is
   * what upstream does — a `size` legend's swatches are outlines, and saying "transparent" says so
   * where saying nothing would leave it to whatever default the renderer has.
   *
   * The test is on the **fill** channel alone (`config.symbolBaseFillColor`, applied by upstream
   * under `if (!spec.fill)`), not on whether the legend maps any colour at all. A legend over a
   * `stroke` scale therefore gets the transparent fill too — it draws the same either way, but a
   * comparison against upstream can see the difference and a stroke-only legend is common.
   */
  private fun symbolFill(spec: LegendSpec, value: VegaValue): Fill? {
    val fillScale = spec.fill?.let { scales[it] }
    if (fillScale == null) return Fill.of(SceneColor.Transparent)
    val colour = SceneColor.parse(fillScale.scale(value).asString()) ?: return null
    return Fill.of(colour)
  }

  /**
   * A legend symbol's stroke.
   *
   * When the legend maps no colour at all — a `size` or `shape` legend — upstream outlines the
   * swatch in grey and leaves it unfilled, rather than inventing a fill the scale never assigned.
   */
  private fun symbolStroke(spec: LegendSpec, value: VegaValue, width: Double): Stroke? {
    val dash = spec.symbolStyle.dash ?: emptyList()
    // An explicit `symbolStrokeColor` outlines every swatch, whatever the scales say.
    spec.symbolStyle.color
      ?.let { SceneColor.parse(it) }
      ?.let {
        return Stroke(paint = ScenePaint.Solid(it), width = width, dashArray = dash)
      }
    val strokeScale = spec.stroke?.let { scales[it] }
    if (strokeScale != null) {
      val colour = SceneColor.parse(strokeScale.scale(value).asString())
      if (colour != null) {
        return Stroke(paint = ScenePaint.Solid(colour), width = width, dashArray = dash)
      }
    }
    if (spec.fill != null || spec.stroke != null) return null
    return Stroke(
      paint = ScenePaint.Solid(LegendDefaults.symbolBaseStrokeColor),
      width = width,
      dashArray = dash,
    )
  }

  // ---- gradient legends -------------------------------------------------------

  private fun gradientEntries(
    spec: LegendSpec,
    scale: VegaScale,
    scaleName: String,
  ): List<SceneNode>? {
    if (scale !is SequentialColorScale) {
      diagnostics.error(
        DiagnosticCodes.SCALE_UNSUPPORTED_TYPE,
        "A gradient legend needs a continuous colour scale; '$scaleName' is not one",
        operator = scaleName,
      )
      return null
    }

    val vertical = isVertical(spec)
    val length = numbers.resolve(spec.gradientLength, scaleName) ?: LegendDefaults.GRADIENT_LENGTH
    val thickness =
      numbers.resolve(spec.gradientThickness, scaleName) ?: LegendDefaults.GRADIENT_THICKNESS
    val labelFontSize =
      numbers.resolve(spec.labelFontSize, scaleName) ?: LegendDefaults.LABEL_FONT_SIZE
    val labelOffset =
      numbers.resolve(spec.labelOffset, scaleName) ?: LegendDefaults.GRADIENT_LABEL_OFFSET

    val swatch =
      RectNode(
        id = ids.allocate(),
        x = 0.0,
        y = 0.0,
        width = if (vertical) thickness else length,
        height = if (vertical) length else thickness,
        // A vertical ramp runs bottom to top, so the domain's low end sits at the bottom of the
        // swatch and reads the same way as a y axis.
        fill =
          Fill(
            ScenePaint.LinearGradient(
              x1 = 0.0,
              y1 = if (vertical) 1.0 else 0.0,
              x2 = if (vertical) 0.0 else 1.0,
              y2 = 0.0,
              stops = gradientStops(scale),
            )
          ),
        stroke =
          Stroke(
            paint = ScenePaint.Solid(LegendDefaults.gradientStrokeColor),
            width = LegendDefaults.GRADIENT_STROKE_WIDTH,
          ),
        metadata = NodeMetadata(role = "legend-gradient", markName = scaleName),
      )

    val nodes = mutableListOf<SceneNode>(swatch)
    val labelStyle = GuideStyle.text(spec.labelStyle, labelFontSize, defaultWeight = 400)
    val labelLimit = numbers.resolve(spec.labelLimit, scaleName) ?: LegendDefaults.LABEL_LIMIT
    val labels = mutableListOf<TextNode>()

    for ((index, entry) in gradientLabels(spec, scale, scaleName).withIndex()) {
      val fraction = scale.fraction((entry.value as VegaValue.Num).value)
      // The end labels hang inside the swatch rather than past it, so a ramp's extremes stay
      // legible
      // against the chart edge.
      val run =
        TextRun(
          text = entryText(spec, "labels", "text", entry) ?: entry.label,
          style = labelStyle,
          align =
            if (vertical) TextAlign.LEFT
            else if (fraction <= 0.0) TextAlign.LEFT
            else if (fraction >= 1.0) TextAlign.RIGHT else TextAlign.CENTER,
          baseline =
            if (!vertical) TextBaseline.TOP
            else if (fraction <= 0.0) TextBaseline.BOTTOM
            else if (fraction >= 1.0) TextBaseline.TOP else TextBaseline.MIDDLE,
        )
      labels +=
        TextNode(
          id = ids.allocate(),
          x = if (vertical) thickness + labelOffset else fraction * length,
          y = if (vertical) (1.0 - fraction) * length else thickness + labelOffset,
          layout = textEngine.layout(run),
          fill = GuideStyle.fill(spec.labelStyle, LegendDefaults.labelColor),
          metadata = NodeMetadata(role = "legend-label", markName = scaleName, datumIndex = index),
        )
    }

    // A legend removes overlapping labels *by default*, where an axis does so only when asked —
    // `labelOverlap: true` lives in upstream's `legend` config block and the `axis` block has no
    // entry. A horizontal ramp squeezed short is where it shows: the middle labels go, the ends
    // stay.
    val method = LabelOverlap.Method.fromValue(spec.labelOverlap ?: "parity")
    val kept =
      if (method == null) labels
      else {
        LabelOverlap.visible(
          labels,
          method,
          numbers.resolve(spec.labelSeparation, scaleName) ?: 0.0,
        )
      }
    for (label in labels) nodes += if (label in kept) label else label.copy(opacity = 0.0)
    return nodes
  }

  /**
   * The colour stops of a gradient swatch.
   *
   * Sampled at the scale's own tick values rather than at even intervals, and with the domain ends
   * added, which is what upstream does — so a multi-stop ramp bends in the same places on both
   * sides.
   */
  private fun gradientStops(scale: SequentialColorScale): List<GradientStop> {
    val lo = scale.domain.first()
    val hi = scale.domain.last()
    val values = LinkedHashSet<Double>()
    values += lo
    values +=
      scale.ticks(LegendDefaults.GRADIENT_STOP_COUNT).filter { it in minOf(lo, hi)..maxOf(lo, hi) }
    values += hi
    return values
      .sortedBy { scale.fraction(it) }
      .mapNotNull { value ->
        scale.colorAt(value)?.let { GradientStop(scale.fraction(value), it) }
      }
  }

  private fun gradientLabels(
    spec: LegendSpec,
    scale: SequentialColorScale,
    scaleName: String,
  ): List<Entry> {
    spec.values?.let { explicit ->
      return explicit.map { Entry(it, it.asString()) }
    }
    val length = numbers.resolve(spec.gradientLength, scaleName) ?: LegendDefaults.GRADIENT_LENGTH
    // Upstream scales the label count to the ramp's length rather than using a fixed five, so a
    // short
    // gradient does not end up with labels on top of each other.
    val count =
      numbers.resolveInt(spec.tickCount, scaleName) ?: maxOf(2, 2 * floor(length / 100.0).toInt())
    val values = scale.ticks(count)
    val labels = scale.tickLabels(count)
    // Two ticks across a whole ramp says almost nothing, so upstream labels the domain's own ends
    // instead — which is why a [0, 19] domain reads "0" and "19" rather than "0" and "10".
    if (values.size < 3 && scale.domain.first() != scale.domain.last()) {
      val ends = listOf(scale.domain.first(), scale.domain.last())
      return ends.map { Entry(VegaValue.Num(it), scale.formatTick(it, count)) }
    }
    return values.indices.map { Entry(VegaValue.Num(values[it]), labels[it]) }
  }

  // ---- entry values -----------------------------------------------------------

  /**
   * The values a symbol legend shows.
   *
   * A discrete scale lists its whole domain; a continuous one is ticked, because a legend cannot
   * show infinitely many values. Every scale type this engine builds can do one or the other, so
   * this always produces entries — a new scale type will fail to compile here rather than silently
   * produce an empty legend.
   */
  private fun entryValues(spec: LegendSpec, scale: VegaScale, scaleName: String): List<Entry> {
    spec.values?.let { explicit ->
      return explicit.map { Entry(it, it.asString()) }
    }
    val count = numbers.resolveInt(spec.tickCount, scaleName) ?: LegendDefaults.SYMBOL_TICK_COUNT
    return when (scale) {
      is OrdinalScale -> scale.domain.map { Entry(VegaValue.Str(it), it) }
      is BandScale -> scale.domain.map { Entry(VegaValue.Str(it), it) }
      is PointScale -> scale.domain.map { Entry(VegaValue.Str(it), it) }
      is LinearScale ->
        scale.ticks(count).zip(scale.tickLabels(count)).map { (v, l) -> Entry(VegaValue.Num(v), l) }
      is TransformedScale ->
        scale.ticks(count).zip(scale.tickLabels(count)).map { (v, l) -> Entry(VegaValue.Num(v), l) }
      is SequentialColorScale ->
        scale.ticks(count).zip(scale.tickLabels(count)).map { (v, l) -> Entry(VegaValue.Num(v), l) }
      is TimeScale ->
        scale.ticks(count).zip(scale.tickLabels(count)).map { (v, l) -> Entry(VegaValue.Num(v), l) }
      // A banded legend, approximately. Upstream draws one as a *stacked colour bar* —
      // `legend-band`
      // rects of `gradientLength / buckets` each, bottom upwards, with the labels sitting at the
      // boundaries between them. This draws ordinary symbol swatches instead, which shows the right
      // colours against the right cut points but is not the same picture, so it is reported.
      is BinnedScale -> {
        diagnostics.warn(
          DiagnosticCodes.SCALE_UNSUPPORTED_TYPE,
          "A legend for a '${scaleName}' discretizing scale is drawn upstream as a stacked colour " +
            "bar; this draws symbol swatches with the same colours and cut points instead",
          operator = scaleName,
        )
        val decimals = decimalsFor(scale.thresholds)
        scale.bucketRepresentatives.mapIndexed { index, value ->
          val label = scale.thresholds.getOrNull(index - 1)
          Entry(VegaValue.Num(value), label?.let { formatTickLabel(it, decimals) } ?: "")
        }
      }
    }
  }

  /**
   * Whether one legend's own contents run down or across.
   *
   * Vertical for both legend kinds unless the specification says otherwise — including along the
   * top and bottom edges, where a horizontal run would look more natural. Upstream's
   * `symbolDirection` and `gradientDirection` are both `vertical` and neither depends on `orient`;
   * what `orient` does affect is how *several* legends stack against each other, which [place]
   * handles.
   */
  private fun isVertical(spec: LegendSpec): Boolean = spec.direction != Direction.HORIZONTAL

  // ---- placement --------------------------------------------------------------

  /**
   * Places the legends that share an orientation.
   *
   * Sides stack away from the edge they hang off; edges and corners run along it. A group anchored
   * by its far edge — a left-hand legend, or anything at the right — is offset by its own width,
   * which is why the sizes have to be known before anything can be positioned.
   */
  private fun place(
    orient: LegendOrient,
    group: List<Built>,
    extent: PlotSize,
    guides: GuideBounds,
  ): List<SceneNode> {
    if (orient == LegendOrient.NONE) {
      return group.map { built ->
        val name = built.spec.scale ?: "legend"
        val x = numbers.resolve(built.spec.legendX, name) ?: 0.0
        val y = numbers.resolve(built.spec.legendY, name) ?: 0.0
        node(built, x, y)
      }
    }

    val offset =
      group.mapNotNull { numbers.resolve(it.spec.offset, it.spec.scale ?: "legend") }.maxOrNull()
        ?: LegendDefaults.OFFSET
    val stacksDown = orient == LegendOrient.LEFT || orient == LegendOrient.RIGHT
    val totalRun =
      if (stacksDown) group.sumOf { it.size.height } + LegendDefaults.MARGIN * (group.size - 1)
      else group.sumOf { it.size.width } + LegendDefaults.MARGIN * (group.size - 1)

    val nodes = mutableListOf<SceneNode>()
    var run = 0.0
    for (built in group) {
      val w = built.size.width
      val h = built.size.height
      val x =
        when (orient) {
          LegendOrient.LEFT -> floor(guides.vertical.left) - offset - w
          LegendOrient.RIGHT -> ceil(guides.vertical.right) + offset
          LegendOrient.TOP,
          LegendOrient.BOTTOM,
          LegendOrient.TOP_LEFT,
          LegendOrient.BOTTOM_LEFT -> run
          LegendOrient.TOP_RIGHT,
          LegendOrient.BOTTOM_RIGHT -> extent.width - offset - totalRun + run
          LegendOrient.NONE -> 0.0
        }
      val y =
        when (orient) {
          LegendOrient.LEFT,
          LegendOrient.RIGHT -> run
          LegendOrient.TOP -> floor(guides.horizontal.top) - offset - h
          LegendOrient.BOTTOM -> ceil(guides.horizontal.bottom) + offset
          LegendOrient.TOP_LEFT,
          LegendOrient.TOP_RIGHT -> offset
          LegendOrient.BOTTOM_LEFT,
          LegendOrient.BOTTOM_RIGHT -> extent.height - offset - h
          LegendOrient.NONE -> 0.0
        }
      // A corner legend sits inside the plotting area, so it is inset from the edge rather than
      // measured from the axes.
      val insetX =
        when (orient) {
          LegendOrient.TOP_LEFT,
          LegendOrient.BOTTOM_LEFT -> x + offset
          else -> x
        }
      nodes += node(built, insetX, y)
      run += (if (stacksDown) h else w) + LegendDefaults.MARGIN
    }
    return nodes
  }

  /**
   * What a screen reader is told about a legend.
   *
   * The channels are listed in the order Vega does, so "fill color and size" rather than whichever
   * order this happens to read them in — a caption that varies by implementation is a caption a
   * reader cannot learn.
   */
  private fun caption(built: Built): String? {
    val spec = built.spec
    val channels =
      listOfNotNull(
        spec.fill?.let { "fill" },
        spec.stroke?.let { "stroke" },
        spec.size?.let { "size" },
        spec.shape?.let { "shape" },
        spec.opacity?.let { "opacity" },
      )
    val scaleName = spec.scale ?: return null
    val scale = scales[scaleName] ?: return null
    val kind = if (resolveType(spec, scale) == LegendType.GRADIENT) "gradient" else "symbol"
    return GuideCaption.legend(kind, spec.title, channels, scale)
  }

  private fun node(built: Built, x: Double, y: Double): SceneNode =
    GroupNode(
      id = built.id,
      children = built.content,
      transform = Transform2D.translate(x, y),
      size = built.size,
      metadata =
        NodeMetadata(
          role = "legend",
          markName = built.spec.scale,
          accessibility =
            caption(built)?.let {
              AccessibilityDescriptor(label = it, role = "graphics-symbol", focusable = true)
            },
        ),
    )
}
