package dev.aster.vega.dataflow.label

import kotlin.math.abs
import kotlin.math.sqrt

/** One label being placed: what it says, how big it is, and what it is a label *of*. */
internal class LabelCandidate(
  /** The text's own font size, which is the label's height for placement purposes. */
  val textHeight: Double,
  /** How wide the text is, measured by the engine's own text metrics. */
  val textWidth: Double,
  /**
   * The box being labelled, as upstream's six numbers: `x1, xmid, x2, y1, ymid, y2`.
   *
   * Six rather than four because an anchor may be a *side* or a *centre*, and both are read by the
   * same index arithmetic — `boundary[1 + dx]` and `boundary[4 + dy]`.
   */
  val boundary: DoubleArray,
) {
  var x: Double = Double.NaN
  var y: Double = Double.NaN
  var align: String = "center"
  var baseline: String = "middle"
  var placed: Boolean = false
}

/** The eight anchors upstream names, as the two-bit pairs its arithmetic reads. */
internal object Anchors {
  private const val TOP = 0x0
  private const val MIDDLE = 0x4
  private const val BOTTOM = 0x8
  private const val LEFT = 0x0
  private const val CENTER = 0x1
  private const val RIGHT = 0x2

  val codes: Map<String, Int> =
    mapOf(
      "top-left" to TOP + LEFT,
      "top" to TOP + CENTER,
      "top-right" to TOP + RIGHT,
      "left" to MIDDLE + LEFT,
      "middle" to MIDDLE + CENTER,
      "right" to MIDDLE + RIGHT,
      "bottom-left" to BOTTOM + LEFT,
      "bottom" to BOTTOM + CENTER,
      "bottom-right" to BOTTOM + RIGHT,
    )

  /** Upstream's default order, which is also its order of preference. */
  val default: List<String> =
    listOf(
      "top-left",
      "left",
      "bottom-left",
      "top",
      "bottom",
      "top-right",
      "right",
      "bottom-right",
    )
}

/**
 * Places labels next to the things they label, skipping any that would overlap.
 *
 * The algorithm is upstream's `placeMarkLabel`: for each label in turn, try each anchor and offset
 * in order, and take the first position whose rectangle is free in the occupancy bitmap; then mark
 * that rectangle occupied so later labels avoid it. Labels are attempted in priority order, so what
 * gets dropped in a crowded chart is the least important thing rather than the last thing.
 *
 * Two details are upstream's and both change the picture. The **diagonal** anchors are offset by
 * `1/sqrt(2)` rather than by the full offset, so a label above-and-right of a point sits the same
 * distance away as one directly above it. And a label whose width is not yet known is tested at one
 * pixel wide first — if even that does not fit, the width is never measured, which is what makes
 * labelling four thousand points affordable.
 *
 * Ported from `vega-label/src/{LabelLayout,util/placeMarkLabel}.js`.
 */
internal class LabelLayout(
  private val scaler: Scaler,
  private val interior: Bitmap,
  /** The border bitmap, used only where a label may sit *inside* the mark it labels. */
  private val border: Bitmap?,
  private val anchors: IntArray,
  private val offsets: DoubleArray,
) {

  private val aligns = arrayOf("right", "center", "left")
  private val baselines = arrayOf("bottom", "middle", "top")

  /** Places every candidate in the order given, and reports how many were placed. */
  fun place(candidates: List<LabelCandidate>): Int {
    var placed = 0
    for (candidate in candidates) if (placeOne(candidate)) placed++
    return placed
  }

  @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
  private fun placeOne(d: LabelCandidate): Boolean {
    val boundary = d.boundary
    val textHeight = d.textHeight

    // A mark entirely off the surface is not labelled at all.
    if (
      boundary[2] < 0 ||
        boundary[5] < 0 ||
        boundary[0] > scaler.width ||
        boundary[3] > scaler.height
    ) {
      return false
    }

    var textWidth = d.textWidth
    for (i in anchors.indices) {
      val dx = (anchors[i] and 0x3) - 1
      val dy = ((anchors[i] ushr 0x2) and 0x3) - 1

      val isInside = (dx == 0 && dy == 0) || offsets[i] < 0
      // A diagonal anchor is offset along the diagonal, so every anchor is the same distance out.
      val sizeFactor = if (dx != 0 && dy != 0) 1 / sqrt(2.0) else 1.0
      val insideFactor = if (offsets[i] < 0) -1.0 else 1.0

      var x1 = boundary[1 + dx] + offsets[i] * dx * sizeFactor
      val yc =
        boundary[4 + dy] + (insideFactor * textHeight * dy) / 2 + offsets[i] * dy * sizeFactor
      val y1 = yc - textHeight / 2
      val y2 = yc + textHeight / 2

      var sx1 = scaler.scale(x1)
      val sy1 = scaler.scale(y1)
      val sy2 = scaler.scale(y2)

      if (textWidth == 0.0) {
        // One pixel wide: if that does not fit, nothing of this label will, and its width need
        // never be measured.
        if (!test(sx1, sx1, sy1, sy2, isInside)) continue
        textWidth = d.textWidth
      }

      val xc = x1 + (insideFactor * textWidth * dx) / 2
      x1 = xc - textWidth / 2
      val x2 = xc + textWidth / 2

      sx1 = scaler.scale(x1)
      val sx2 = scaler.scale(x2)

      if (test(sx1, sx2, sy1, sy2, isInside)) {
        d.x = if (dx == 0) xc else if (dx * insideFactor < 0) x2 else x1
        d.y = if (dy == 0) yc else if (dy * insideFactor < 0) y2 else y1
        d.align = aligns[dx * insideFactor.toInt() + 1]
        d.baseline = baselines[dy * insideFactor.toInt() + 1]
        d.placed = true
        interior.setRange(sx1, sy1, sx2, sy2)
        return true
      }
    }
    return false
  }

  /**
   * Is this rectangle free?
   *
   * A label allowed *inside* its mark is tested against the **border** bitmap, which holds only the
   * marks' outlines — so it may overlap a filled shape but not cross its edge. One that must stay
   * outside is tested against the interior.
   */
  private fun test(x1: Int, x2: Int, y1: Int, y2: Int, isInside: Boolean): Boolean {
    if (interior.outOfBounds(x1, y1, x2, y2)) return false
    val against = if (isInside && border != null) border else interior
    return !against.getRange(x1, y1, x2, y2)
  }

  internal companion object {
    /**
     * The offsets and anchors, each padded to the longer of the two lists as upstream pads them.
     */
    fun offsetsOf(declared: List<Double>, count: Int): DoubleArray {
      val out = DoubleArray(count)
      val n = declared.size
      for (i in 0 until n) out[i] = declared[i]
      for (i in n until count) out[i] = if (n > 0) declared[n - 1] else 0.0
      return out
    }

    fun anchorsOf(declared: List<String>, count: Int): IntArray {
      val out = IntArray(count)
      val n = declared.size
      for (i in 0 until n) out[i] = out[i] or (Anchors.codes[declared[i]] ?: 0)
      for (i in n until count) out[i] = if (n > 0) out[n - 1] else 0
      return out
    }

    /** True when any anchor would put a label inside the mark it labels. */
    fun anyInside(anchors: IntArray, offsets: DoubleArray): Boolean {
      for (i in anchors.indices) {
        if (anchors[i] == 0x5 || offsets[i] < 0) return true
      }
      return false
    }

    /** `padding: null` means a label may go anywhere; the bitmap grows to hold it. */
    fun paddingFor(
      declared: Double?,
      maxTextWidth: Double,
      maxTextHeight: Double,
      offsets: List<Double>,
    ): Double = declared ?: (maxOf(maxTextWidth, maxTextHeight) + (offsets.maxOrNull() ?: 0.0))

    fun absMax(values: DoubleArray): Double = values.maxOfOrNull { abs(it) } ?: 0.0
  }
}
