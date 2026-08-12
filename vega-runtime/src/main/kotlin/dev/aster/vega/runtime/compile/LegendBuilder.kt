package dev.aster.vega.runtime.compile

import dev.aster.vega.expression.NumberFormatSubset
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
import dev.aster.vega.runtime.scale.QuantileScale
import dev.aster.vega.runtime.scale.SequentialColorScale
import dev.aster.vega.runtime.scale.Ticks
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

  /** Upstream's `3 * 10`: three ticks at ten times the resolution, for a band label's precision. */
  private val BAND_FORMAT_COUNT = 30

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

    val entries =
      when (type) {
        LegendType.SYMBOL -> symbolEntries(spec, scale, scaleName)
        LegendType.GRADIENT -> gradientEntries(spec, scale, scaleName)
        LegendType.DISCRETE -> discreteEntries(spec, scale, scaleName)
      } ?: return null

    // A title on the left is centred against what the entries drew, so it cannot be placed until
    // they exist — and for a gradient it is centred against the **bar alone**, not against the
    // labels under it, which is upstream's `entry.items[0]` and the reason a ramp's title sits
    // level with the colours rather than with the whole block.
    val alongside = spec.titleOrient == "left"
    val centreOver =
      if (type == LegendType.GRADIENT || type == LegendType.DISCRETE) {
        entries.firstOrNull()?.transformedBounds?.bottom ?: 0.0
      } else entries.fold(RectD.Empty) { acc, node -> acc.union(node.transformedBounds) }.bottom
    val title =
      // A legend may name itself from a signal, exactly as an axis does — a chart whose measure is
      // chosen by a control has no constant to write down.
      titleTextOf(spec, scaleName)?.let {
        titleNode(spec, scaleName, it, padding, alongside, 0.5 * centreOver)
      }
    val titleReach = title?.let { it.bounds.height + titlePadding } ?: 0.0
    val titleAside =
      if (alongside && title != null) ceil(title.bounds.width) + titlePadding else 0.0

    val body =
      GroupNode(
        id = ids.allocate(),
        children = entries,
        transform =
          Transform2D.translate(
            padding + titleAside,
            padding + if (alongside) 0.0 else titleReach,
          ),
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
    // A `gradient` asked for over a discretizing scale is a *banded* one; upstream's `legendType`
    // says so twice, once when it derives the type and once when it honours a stated one.
    if (stated != null) {
      return if (stated == LegendType.GRADIENT && scale is BinnedScale) LegendType.DISCRETE
      else stated
    }

    val colourOnly = spec.channelCount == 1 && (spec.fill != null || spec.stroke != null)
    return when {
      !colourOnly -> LegendType.SYMBOL
      scale is SequentialColorScale -> LegendType.GRADIENT
      scale is BinnedScale -> LegendType.DISCRETE
      else -> LegendType.SYMBOL
    }
  }

  /**
   * @param alongside `titleOrient: "left"`, which also changes the title's anchor.
   * @param centre half the height the entries reach, for the vertical centring that anchor implies.
   */
  /** The legend's title, whether written down or computed. */
  private fun titleTextOf(spec: LegendSpec, scaleName: String): String? =
    spec.title ?: spec.titleExpression?.let { numbers.resolveLines(it, scaleName) }

  private fun titleNode(
    spec: LegendSpec,
    scaleName: String,
    text: String,
    padding: Double,
    alongside: Boolean = false,
    centre: Double = 0.0,
  ): TextNode {
    val fontSize = numbers.resolve(spec.titleFontSize, scaleName) ?: LegendDefaults.TITLE_FONT_SIZE
    val run =
      TextRun(
        text = text,
        style = GuideStyle.text(spec.titleStyle, fontSize, LegendDefaults.TITLE_FONT_WEIGHT),
        // `titleAlign` and `titleBaseline` are overrides: upstream derives both from the title's
        // orientation and its anchor in `enter` and writes the explicit ones into `update`.
        align = GuideStyle.alignOf(spec.titleStyle.align) ?: TextAlign.LEFT,
        limit = numbers.resolve(spec.titleLimit, scaleName) ?: LegendDefaults.TITLE_LIMIT,
        // Upstream reads a left or right title as `middle`-anchored where a top one is
        // `start`-anchored, and the baseline follows the anchor.
        baseline =
          GuideStyle.baselineOf(spec.titleStyle.baseline)
            ?: if (alongside) TextBaseline.MIDDLE else TextBaseline.TOP,
      )
    return TextNode(
      id = ids.allocate(),
      x = padding,
      y = padding + if (alongside) centre else 0.0,
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
    val entries = limited(spec, entryValues(spec, scale, scaleName), scaleName)
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
    val clipHeight = numbers.resolve(spec.clipHeight, scaleName)
    // A row is as tall as the taller of its symbol and its label, and upstream rounds the symbol's
    // contribution up before comparing: this is the number every offset within a cell derives from.
    val measured = sizes.map { maxOf(ceil(sqrt(it) + strokeWidth), labelFontSize) }
    // `clipHeight` replaces that measurement **vertically only**. The horizontal anchor still comes
    // from the real symbols — upstream's `datum.offset` is the widest of them whatever the rows are
    // clipped to — so a clipped legend's labels still clear its biggest circle.
    val boxes = measured.map { clipHeight ?: it }
    // A vertical legend aligns every label at the widest symbol; a horizontal one packs each entry
    // against its own symbol. That is upstream's `datum.offset` versus `datum.size`.
    val widest = measured.max()

    val labelStyle = GuideStyle.text(spec.labelStyle, labelFontSize, defaultWeight = 400)
    val labelLimit = numbers.resolve(spec.labelLimit, scaleName) ?: LegendDefaults.LABEL_LIMIT
    // `symbolOffset` shifts the swatch **and its label** along the row: upstream builds the label's
    // own offset by extending the symbol's, so the gap between the two stays `labelOffset` whatever
    // the symbol offset is.
    val symbolOffset = numbers.resolve(spec.symbolOffset, scaleName) ?: LegendDefaults.SYMBOL_OFFSET

    // Build each entry at its own origin first: the layout below needs to know how far a cell
    // reaches
    // before it can decide where the next one starts.
    val cells = entries.mapIndexed { index, entry ->
      val box = boxes[index]
      val anchor = if (vertical) widest else measured[index]
      val centre = box * 0.5
      val labelX = anchor + symbolOffset + labelOffset
      val run =
        TextRun(
          text = entryText(spec, "labels", "text", entry) ?: entry.label,
          style = labelStyle,
          align = GuideStyle.alignOf(spec.labelStyle.align) ?: TextAlign.LEFT,
          baseline = GuideStyle.baselineOf(spec.labelStyle.baseline) ?: TextBaseline.MIDDLE,
          limit = labelLimit,
        )
      listOf(
        SymbolNode(
          id = ids.allocate(),
          x = anchor * 0.5 + symbolOffset,
          y = centre,
          size = sizes[index],
          shape = shape,
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
    return place(cells, columns, rowPadding, columnPadding, scaleName, clipHeight)
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
    /**
     * `clipHeight`: the row height the layout must use, whatever the symbols measure.
     *
     * Null for an ordinary legend, where a row is as tall as what is in it. With one set, a symbol
     * larger than the row **overflows** it rather than pushing the next row down, which is the
     * entire reason a specification sets one.
     */
    clipHeight: Double? = null,
  ): List<SceneNode> {
    val order = GridLayout.columnMajorOrder(cells.size, columns)
    val ordered = order.map { cells[it] }
    val boxes = ordered.map { cell ->
      val measured = cell.fold(RectD.Empty) { acc, node -> acc.union(node.bounds) }
      if (clipHeight == null) measured else RectD(measured.left, 0.0, measured.right, clipHeight)
    }
    val offsets = GridLayout.place(boxes, GridLayout.Options(columns, rowPadding, columnPadding))

    return ordered.indices.map { position ->
      val offset = offsets[position]
      GroupNode(
        id = ids.allocate(),
        children = ordered[position],
        transform = Transform2D.translate(offset.x, offset.y),
        // With a `clipHeight` the entry is a **clipped** box: a symbol larger than the row spills
        // out of it, and the legend is sized as though it had not. That is what the property is
        // for — a size legend whose largest swatch is 5,000 units would otherwise be seventy units
        // tall per row and as wide as its biggest circle.
        size = clipHeight?.let { SizeD(boxes[position].width, it) },
        clip = clipHeight?.let { RectD(0.0, 0.0, boxes[position].width, it) },
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
    return when (name.lowercase()) {
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
      else -> {
        diagnostics.warn(
          DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
          "Legend symbolType '$name' is not implemented; drawing a circle instead",
          operator = spec.scale,
        )
        SymbolShape.CIRCLE
      }
    }
  }

  /** A `size` legend takes each swatch's size from the scale; every other legend uses one size. */
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
  /**
   * A legend symbol's fill.
   *
   * `symbolFillColor` is a **fallback**, not an override: upstream passes it to `addEncoders` and
   * then overwrites the channel from the scale for every legend that maps one, so a `fill` scale
   * wins and a `size` or `shape` legend takes the stated colour. Its own default in that case is
   * `config.symbolBaseFillColor`, which is transparent — an unfilled swatch with a grey outline.
   */
  private fun symbolFill(spec: LegendSpec, value: VegaValue): Fill? {
    val fillScale = spec.fill?.let { scales[it] }
    if (fillScale == null) {
      val stated = spec.symbolFillColor?.let { SceneColor.parse(it) }
      return Fill.of(stated ?: SceneColor.Transparent)
    }
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
    val dashOffset = spec.symbolStyle.dashOffset ?: 0.0
    fun outline(colour: SceneColor) =
      Stroke(
        paint = ScenePaint.Solid(colour),
        width = width,
        dashArray = dash,
        dashOffset = dashOffset,
      )
    // An explicit `symbolStrokeColor` outlines every swatch, whatever the scales say.
    spec.symbolStyle.color
      ?.let { SceneColor.parse(it) }
      ?.let {
        return outline(it)
      }
    val strokeScale = spec.stroke?.let { scales[it] }
    if (strokeScale != null) {
      val colour = SceneColor.parse(strokeScale.scale(value).asString())
      if (colour != null) return outline(colour)
    }
    if (spec.fill != null || spec.stroke != null) return null
    return outline(LegendDefaults.symbolBaseStrokeColor)
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
            paint =
              ScenePaint.Solid(
                spec.gradientStrokeColor?.let { SceneColor.parse(it) }
                  ?: LegendDefaults.gradientStrokeColor
              ),
            width =
              numbers.resolve(spec.gradientStrokeWidth, scaleName)
                ?: LegendDefaults.GRADIENT_STROKE_WIDTH,
          ),
        // `gradientOpacity` fades the whole ramp — the outline with the colours — because upstream
        // puts it on the item rather than on either paint.
        opacity = numbers.resolve(spec.gradientOpacity, scaleName) ?: 1.0,
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

  // ---- banded legends ---------------------------------------------------------

  /**
   * A discretizing scale's legend: a stack of bands, one per bucket, labelled at their edges.
   *
   * Not a symbol legend with the right colours, and not a smooth ramp either. A `quantize` or
   * `quantile` scale has a finite number of colours and each stands for a *range*, so upstream
   * draws the range: a bar the length of a gradient, cut into bands as wide as the intervals they
   * represent, with the cut points written beside the cuts.
   *
   * Two details are upstream's and neither is guessable. The lowest band's label is **empty** —
   * that bucket reaches to negative infinity and there is no number to write at its foot. And the
   * bands are measured against the scale's *input* extent, not against equal shares, so a quantile
   * scale's bands come out uneven, which is the entire point of a quantile scale.
   */
  private fun discreteEntries(
    spec: LegendSpec,
    scale: VegaScale,
    scaleName: String,
  ): List<SceneNode>? {
    if (scale !is BinnedScale) {
      diagnostics.error(
        DiagnosticCodes.SCALE_UNSUPPORTED_TYPE,
        "A banded legend needs a discretizing scale; '$scaleName' is not one",
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

    val values = scale.legendValues
    val label = bandLabeller(spec, scale, scaleName)
    // A band's own share of the bar: from where its lower edge sits to where the next one does,
    // and the last runs to the end.
    val starts = values.indices.map { if (it == 0) 0.0 else scale.legendFraction(values[it]) }
    val ends =
      values.indices.map {
        if (it == values.size - 1) 1.0 else scale.legendFraction(values[it + 1])
      }

    val nodes = mutableListOf<SceneNode>()
    for (index in values.indices) {
      val near = starts[index]
      val far = ends[index]
      val colour = scale.scale(VegaValue.Num(scale.bucketRepresentatives.getOrElse(index) { 0.0 }))
      val paint = SceneColor.parse(colour.asString())
      // A vertical bar runs bottom to top, so the lowest bucket is drawn at the foot of it.
      val u0 = if (vertical) (1.0 - near) * length else near * length
      val u1 = if (vertical) (1.0 - far) * length else far * length
      nodes +=
        RectNode(
          id = ids.allocate(),
          x = if (vertical) 0.0 else minOf(u0, u1),
          y = if (vertical) minOf(u0, u1) else 0.0,
          width = if (vertical) thickness else kotlin.math.abs(u1 - u0),
          height = if (vertical) kotlin.math.abs(u1 - u0) else thickness,
          fill = paint?.let { Fill(ScenePaint.Solid(it)) },
          // The same hairline the continuous ramp carries: a stroke of width zero, which paints
          // nothing and is still on the item. It is `gradientStrokeColor`, not a band's own idea.
          stroke =
            Stroke(
              paint = ScenePaint.Solid(LegendDefaults.gradientStrokeColor),
              width = LegendDefaults.GRADIENT_STROKE_WIDTH,
            ),
          metadata = NodeMetadata(role = "legend-band", markName = scaleName, datumIndex = index),
        )
    }

    val labelStyle = GuideStyle.text(spec.labelStyle, labelFontSize, defaultWeight = 400)
    val labels = mutableListOf<TextNode>()
    for (index in values.indices) {
      val fraction = starts[index]
      val run =
        TextRun(
          text = label(index, values[index]),
          style = labelStyle,
          align =
            if (vertical) TextAlign.LEFT
            else if (fraction <= 0.0) TextAlign.LEFT
            else if (fraction >= 1.0) TextAlign.RIGHT else TextAlign.CENTER,
          baseline =
            if (!vertical) TextBaseline.TOP
            else if (fraction <= 0.0) TextBaseline.BOTTOM
            else if (fraction >= 1.0) TextBaseline.TOP else TextBaseline.MIDDLE,
          limit = numbers.resolve(spec.labelLimit, scaleName) ?: LegendDefaults.LABEL_LIMIT,
        )
      labels +=
        TextNode(
          id = ids.allocate(),
          x = if (vertical) thickness + labelOffset else fraction * length,
          y = if (vertical) (1.0 - fraction) * length else thickness + labelOffset,
          layout = textEngine.layout(run),
          fill = GuideStyle.fill(spec.labelStyle, LegendDefaults.labelColor),
          metadata = NodeMetadata(role = "legend-label", markName = scaleName, datumIndex = index),
          // The lowest band has no lower bound to write; the item is still here and still measured.
          absent = index == 0 && spec.values == null,
        )
    }

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
    for (entry in labels) nodes += if (entry in kept) entry else entry.copy(opacity = 0.0)
    return nodes
  }

  /**
   * How a band's lower edge is written.
   *
   * The format's precision comes from the **narrowest** interval on the scale rather than from the
   * whole span — upstream's `thresholdFormat` — so a scale whose buckets are a tenth of a percent
   * apart labels them to a tenth of a percent even though its domain runs from zero to fifteen.
   */
  private fun bandLabeller(
    spec: LegendSpec,
    scale: BinnedScale,
    scaleName: String,
  ): (Int, Double) -> String {
    spec.values?.let { explicit ->
      return { index, _ -> explicit.getOrNull(index)?.asString() ?: "" }
    }
    val reference = if (scale is QuantileScale) scale.thresholds else scale.legendExtent.toList()
    val step =
      when {
        reference.size > 1 -> (1 until reference.size).minOf { reference[it] - reference[it - 1] }
        reference.size == 1 -> reference[0]
        else -> 1.0
      }
    val specifier = spec.format?.let { Ticks.spanSpecifier(it, 0.0, step, BAND_FORMAT_COUNT) }
    val decimals = decimalsFor(scale.thresholds)
    return { index, value ->
      // The first band opens at negative infinity, and upstream writes nothing rather than a
      // number that bounds nothing.
      if (index == 0) ""
      else if (specifier != null) NumberFormatSubset.format(value, specifier)
      else formatTickLabel(value, decimals)
    }
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
    val format = numberLabeller(spec, scale.domain.first(), scale.domain.last(), count)
    val labels = format?.let { f -> values.map { f(it) } } ?: scale.tickLabels(count)
    // Two ticks across a whole ramp says almost nothing, so upstream labels the domain's own ends
    // instead — which is why a [0, 19] domain reads "0" and "19" rather than "0" and "10".
    if (values.size < 3 && scale.domain.first() != scale.domain.last()) {
      val ends = listOf(scale.domain.first(), scale.domain.last())
      return ends.map {
        Entry(VegaValue.Num(it), format?.invoke(it) ?: scale.formatTick(it, count))
      }
    }
    return values.indices.map { Entry(VegaValue.Num(values[it]), labels[it]) }
  }

  /**
   * The legend's own `format`, resolved against the span it labels.
   *
   * Null when the specification named none, which leaves the scale's own tick labels alone. The
   * resolution is upstream's `formatSpan`: a specifier with no precision takes as many decimals as
   * the tick step needs, which is what makes `"%"` over a `[-0.06, 0.06]` ramp read `−6%` instead
   * of `−6.000000%`.
   */
  private fun numberLabeller(
    spec: LegendSpec,
    low: Double,
    high: Double,
    count: Int,
  ): ((Double) -> String)? {
    val specifier = spec.format ?: return null
    val resolved = Ticks.spanSpecifier(specifier, low, high, count)
    return { value -> NumberFormatSubset.format(value, resolved) }
  }

  /**
   * `symbolLimit`: the most entries a symbol legend will show.
   *
   * Upstream keeps `limit - 1` of them and spends the last slot on a summary — `…12 entries` — so a
   * limit of 5 shows four swatches and a fifth row saying how many were left out. The count in that
   * row is of the entries **not shown**, and the swatch beside it takes the *next* value's own
   * size, so a size legend's summary row is drawn at the size of the first thing it stands for. A
   * limit that the entries already fit inside does nothing at all.
   */
  private fun limited(spec: LegendSpec, entries: List<Entry>, scaleName: String): List<Entry> {
    val limit = numbers.resolveInt(spec.symbolLimit, scaleName) ?: return entries
    if (limit <= 0 || entries.size <= limit) return entries
    val kept = entries.take(limit - 1)
    val remainder = entries.size - kept.size
    return kept + Entry(entries[kept.size].value, "\u2026$remainder entries")
  }

  /** Numeric entries, with the legend's own format applied when it named one. */
  private fun numeric(
    spec: LegendSpec,
    values: List<Double>,
    labels: List<String>,
    count: Int,
  ): List<Entry> {
    val write = numberLabeller(spec, values.firstOrNull() ?: 0.0, values.lastOrNull() ?: 1.0, count)
    return values.indices.map { index ->
      Entry(VegaValue.Num(values[index]), write?.invoke(values[index]) ?: labels[index])
    }
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
      // A legend's own `format` wins over the scale's tick labels, exactly as an axis's does: a
      // rate scale labelled `.1%` reads "10.0%" and not "0.1".
      is LinearScale -> numeric(spec, scale.ticks(count), scale.tickLabels(count), count)
      is TransformedScale -> numeric(spec, scale.ticks(count), scale.tickLabels(count), count)
      is SequentialColorScale -> numeric(spec, scale.ticks(count), scale.tickLabels(count), count)
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
    // Upstream's `LegendScales` order, which is also the order a caption reads them in: size
    // first, then shape, then the two colours. "for size and fill color", not the reverse.
    val channels =
      listOfNotNull(
        spec.size?.let { "size" },
        spec.shape?.let { "shape" },
        spec.fill?.let { "fill" },
        spec.stroke?.let { "stroke" },
        spec.opacity?.let { "opacity" },
      )
    val scaleName = spec.scale ?: return null
    val scale = scales[scaleName] ?: return null
    val kind =
      when (resolveType(spec, scale)) {
        LegendType.GRADIENT -> "gradient"
        LegendType.DISCRETE -> "discrete"
        else -> "symbol"
      }
    // A caption is spoken, not drawn, so a two-line title is read as one phrase: upstream's
    // `array(item.text).join(' ')`. The lines reach here already joined by the newline the text
    // node draws on, and turning them back into spaces is the same operation.
    val title = titleTextOf(spec, scaleName)?.replace("\n", " ")
    return GuideCaption.legend(kind, title, channels, scale, spec.format)
  }

  private fun node(built: Built, x: Double, y: Double): SceneNode {
    val spec = built.spec
    // The legend's own background, which nothing drew until now. Its width and dash come from
    // `config.legend` rather than from the legend itself, which is upstream's rule and not a tidy
    // one; see [LegendSpec.fillColor].
    val backgroundFill = spec.fillColor?.let { SceneColor.parse(it) }?.let { Fill.of(it) }
    val backgroundStroke =
      spec.strokeColor
        ?.let { SceneColor.parse(it) }
        ?.let {
          Stroke(
            paint = ScenePaint.Solid(it),
            width = spec.backgroundStrokeWidth ?: 1.0,
            dashArray = spec.backgroundStrokeDash.orEmpty(),
          )
        }
    return GroupNode(
      id = built.id,
      children = built.content,
      transform = Transform2D.translate(x, y),
      size = built.size,
      fill = backgroundFill,
      stroke = backgroundStroke,
      cornerRadius = numbers.resolve(spec.cornerRadius, spec.scale ?: "legend") ?: 0.0,
      metadata =
        NodeMetadata(
          role = "legend",
          markName = built.spec.scale,
          // `aria: false` takes the legend out of the accessibility tree; a `description` replaces
          // the caption this engine generates from the scale.
          accessibility =
            if (!spec.aria) {
              null
            } else {
              (spec.description ?: caption(built))?.let {
                AccessibilityDescriptor(label = it, role = "graphics-symbol", focusable = true)
              }
            },
        ),
    )
  }
}
