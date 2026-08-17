package dev.aster.vega.dataflow.transform

import dev.aster.vega.dataflow.label.Anchors
import dev.aster.vega.dataflow.label.LabelCandidate
import dev.aster.vega.dataflow.label.LabelLayout
import dev.aster.vega.dataflow.label.Occupancy
import dev.aster.vega.dataflow.label.Scaler
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.asString
import dev.aster.vega.model.field
import kotlin.math.sqrt

/** The four values a label layout writes back, in the order upstream's `as` defaults to. */
private val LABEL_OUTPUT = listOf("x", "y", "opacity", "align", "baseline")

/**
 * `label`: places a text mark's items next to the marks they annotate, dropping any that collide.
 *
 * A mark-level transform over the text items. Each item's `datum` is the item it labels, so the box
 * to place against is that item's bounds, and the label is tried at each anchor in turn until one
 * fits in an occupancy bitmap that already holds every mark to be avoided plus every label already
 * placed.
 *
 * **The one place in this engine whose fidelity is not established against upstream.** Upstream
 * builds its occupancy bitmap by drawing the avoided marks into a `<canvas>` and reading the alpha
 * back; there is no canvas under Node, and upstream's own transform throws there rather than
 * producing a reference. The bitmap here is computed from the marks' geometry instead — see
 * [Occupancy] — which answers the same question except on pixels a shape barely grazes. That is
 * enough to change which anchor a label takes in a crowded chart, so every use of this transform
 * says so.
 *
 * Everything else is pinned: the bit algebra and the pixel scaler against upstream's own results in
 * `BitmapTest`, and the anchor arithmetic is a line-for-line port of `placeMarkLabel`.
 */
public object LabelTransform : Transform {
  override val type: String = "label"

  @Suppress("LongMethod", "CyclomaticComplexMethod")
  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    if (input.isEmpty()) return input
    val outputs = params.stringList("as").takeIf { it.size == LABEL_OUTPUT.size } ?: LABEL_OUTPUT

    val size = params.numberList("size")
    if (size.size < 2) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "label needs a 'size' of [width, height]; without it there is no surface to place on",
        operator = type,
      )
      return input
    }

    context.diagnostics.warn(
      DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED,
      "label placement is computed from the avoided marks' geometry rather than from a rasterised " +
        "bitmap: upstream draws them into a canvas and reads the alpha back, and there is none " +
        "here — its own transform fails in the same place. The two agree except on pixels a shape " +
        "barely grazes, which is enough to move a label to a different anchor or to drop one a " +
        "crowded chart would otherwise fit. Every other part of the layout is upstream's",
      operator = type,
    )

    val anchorNames =
      params.stringList("anchor").ifEmpty { Anchors.default }.filter { it in Anchors.codes }
    val declaredOffsets = params.numberList("offset").ifEmpty { listOf(1.0) }
    val positions = maxOf(declaredOffsets.size, anchorNames.size)
    val offsets = LabelLayout.offsetsOf(declaredOffsets, positions)
    val anchors = LabelLayout.anchorsOf(anchorNames, positions)

    // Each item's own font size is its height, and its measured width its width. A text item that
    // never had either is not placeable.
    val candidates = input.map { item ->
      val fontSize = item.field("fontSize").asDouble().takeIf { !it.isNaN() } ?: 11.0
      val text = item.field("text").asString()
      LabelCandidate(fontSize, context.measureText(text, fontSize), boundaryOf(item))
    }

    val maxWidth = candidates.maxOfOrNull { it.textWidth } ?: 0.0
    val maxHeight = candidates.maxOfOrNull { it.textHeight } ?: 0.0
    val declaredPadding = params.fields["padding"]
    val padding =
      when {
        // `padding: null` means unbounded: the bitmap grows to hold the largest label at the
        // largest offset rather than clipping it at the surface's edge.
        declaredPadding is VegaValue.Null ->
          maxOf(maxWidth, maxHeight) + (declaredOffsets.maxOrNull() ?: 0.0)
        declaredPadding != null -> params.number("padding") ?: 0.0
        else -> 0.0
      }

    val scaler = Scaler(size[0], size[1], padding)
    val interior = scaler.bitmap()
    val labelInside = LabelLayout.anyInside(anchors, offsets)
    val border = if (labelInside) scaler.bitmap() else null

    // Everything the labels must avoid: the marks named by `avoidMarks`, and — unless told not to —
    // the items being labelled themselves.
    val occupancy = Occupancy(scaler, interior)
    for (name in params.stringList("avoidMarks")) cover(occupancy, context.scope.dataset(name))
    val avoidBase = (params.fields["avoidBaseMark"] as? VegaValue.Bool)?.value ?: true
    if (avoidBase) cover(occupancy, input.map { it.field("datum") })
    border?.let { outline ->
      cover(Occupancy(scaler, outline), input.map { it.field("datum") })
    }

    // Priority order: a `sort` on the transform decides which labels win a crowded region. Without
    // one, the data's own order does.
    val order = candidates.indices.toList()
    LabelLayout(scaler, interior, border, anchors, offsets).place(order.map { candidates[it] })

    return input.mapIndexed { index, item ->
      val c = candidates[index]
      item.withFields(
        linkedMapOf(
          outputs[0] to if (c.placed) VegaValue.Num(c.x) else VegaValue.Null,
          outputs[1] to if (c.placed) VegaValue.Num(c.y) else VegaValue.Null,
          outputs[2] to VegaValue.Num(if (c.placed) 1.0 else 0.0),
          outputs[3] to VegaValue.Str(c.align),
          outputs[4] to VegaValue.Str(c.baseline),
        )
      )
    }
  }

  /**
   * The six numbers a label is placed against: the labelled item's box and its midpoints.
   *
   * A mark with no extent of its own — a symbol reduced to a point, a datum with only `x` and `y` —
   * gives the same coordinate six times, which is upstream's `xy` accessor and places the label
   * around the point.
   */
  private fun boundaryOf(item: VegaValue): DoubleArray {
    val datum = item.field("datum")
    val x = datum.field("x").asDouble()
    val y = datum.field("y").asDouble()
    val size = datum.field("size").asDouble()
    if (!size.isNaN() && size > 0) {
      // A symbol's `size` is its area, so its radius is what bounds it.
      val r = sqrt(size / kotlin.math.PI)
      return doubleArrayOf(x - r, x, x + r, y - r, y, y + r)
    }
    if (x.isNaN() || y.isNaN()) return doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    return doubleArrayOf(x, x, x, y, y, y)
  }

  /**
   * Marks one mark's coverage, taking its items as a whole.
   *
   * As a whole because a **line** is not a list of independent points: its items are the vertices,
   * and what it covers is the segments between them. A mark whose items carry a `size` is a set of
   * symbols and each one is its own disc; anything else falls back to whatever box each item has.
   */
  private fun cover(into: Occupancy, items: List<VegaValue>) {
    if (items.isEmpty()) return
    val sized = items.all { !it.field("size").asDouble().isNaN() }
    if (sized) {
      for (item in items) {
        val x = item.field("x").asDouble()
        val y = item.field("y").asDouble()
        val size = item.field("size").asDouble()
        if (x.isNaN() || y.isNaN()) continue
        if (size > 0) into.disc(x, y, sqrt(size / kotlin.math.PI)) else into.point(x, y)
      }
      return
    }
    val positional = items.all {
      !it.field("x").asDouble().isNaN() && !it.field("y").asDouble().isNaN()
    }
    val boxed = items.any {
      !it.field("width").asDouble().isNaN() ||
        !it.field("x2").asDouble().isNaN() ||
        !it.field("y2").asDouble().isNaN()
    }
    if (positional && !boxed && items.size > 1) {
      val width = items[0].field("strokeWidth").asDouble().takeIf { !it.isNaN() } ?: 1.0
      for (i in 1 until items.size) {
        into.segment(
          items[i - 1].field("x").asDouble(),
          items[i - 1].field("y").asDouble(),
          items[i].field("x").asDouble(),
          items[i].field("y").asDouble(),
          width,
        )
      }
      return
    }
    for (item in items) {
      val x = item.field("x").asDouble()
      val y = item.field("y").asDouble()
      val x2 = item.field("x2").asDouble()
      val y2 = item.field("y2").asDouble()
      val width = item.field("width").asDouble()
      val height = item.field("height").asDouble()
      when {
        !width.isNaN() && !height.isNaN() && !x.isNaN() && !y.isNaN() ->
          into.box(x, y, x + width, y + height)
        !x2.isNaN() && !y2.isNaN() -> into.box(x, y, x2, y2)
        !x.isNaN() && !y.isNaN() -> into.point(x, y)
        else -> Unit
      }
    }
  }
}
