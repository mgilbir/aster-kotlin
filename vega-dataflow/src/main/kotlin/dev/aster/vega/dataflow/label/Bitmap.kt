package dev.aster.vega.dataflow.label

import kotlin.math.sqrt

/**
 * A bit per pixel, packed thirty-two to a word, with whole *ranges* tested at once.
 *
 * Label placement asks one question over and over: is anything already drawn in this rectangle? A
 * boolean array answers it a pixel at a time; this answers it a word at a time, which is what makes
 * trying eight anchor positions for each of four thousand labels affordable.
 *
 * The two prefix tables are the trick. `RIGHT0[k]` has its top `32 - k` bits set and `RIGHT1[k]`
 * its bottom `k`, so the first and last words of a range can be masked to exactly the span asked
 * for without a loop. Ported from `vega-label/src/util/Bitmap.js`.
 */
internal class Bitmap(private val width: Int, private val height: Int) {
  private val array = IntArray((width * height + SIZE) / SIZE)

  fun get(x: Int, y: Int): Boolean {
    val index = y * width + x
    return array[index ushr DIV] and (1 shl (index and MOD)) != 0
  }

  fun set(x: Int, y: Int) {
    val index = y * width + x
    array[index ushr DIV] = array[index ushr DIV] or (1 shl (index and MOD))
  }

  /** Is any bit set inside the rectangle? Scanned from the bottom row up, as upstream does. */
  fun getRange(x: Int, y: Int, x2: Int, y2: Int): Boolean {
    var r = y2
    while (r >= y) {
      val start = r * width + x
      val end = r * width + x2
      val indexStart = start ushr DIV
      val indexEnd = end ushr DIV
      if (indexStart == indexEnd) {
        if (array[indexStart] and RIGHT0[start and MOD] and RIGHT1[(end and MOD) + 1] != 0) {
          return true
        }
      } else {
        if (array[indexStart] and RIGHT0[start and MOD] != 0) return true
        if (array[indexEnd] and RIGHT1[(end and MOD) + 1] != 0) return true
        for (i in indexStart + 1 until indexEnd) if (array[i] != 0) return true
      }
      r--
    }
    return false
  }

  fun setRange(x: Int, yFrom: Int, x2: Int, y2: Int) {
    var y = yFrom
    while (y <= y2) {
      val start = y * width + x
      val end = y * width + x2
      val indexStart = start ushr DIV
      val indexEnd = end ushr DIV
      if (indexStart == indexEnd) {
        array[indexStart] =
          array[indexStart] or (RIGHT0[start and MOD] and RIGHT1[(end and MOD) + 1])
      } else {
        array[indexStart] = array[indexStart] or RIGHT0[start and MOD]
        array[indexEnd] = array[indexEnd] or RIGHT1[(end and MOD) + 1]
        for (i in indexStart + 1 until indexEnd) array[i] = -1
      }
      y++
    }
  }

  fun outOfBounds(x: Int, y: Int, x2: Int, y2: Int): Boolean =
    x < 0 || y < 0 || y2 >= height || x2 >= width

  /** The packed words, for a test that wants to check the masking rather than trust `getRange`. */
  internal fun words(): IntArray = array

  private companion object {
    const val DIV = 5
    const val MOD = 31
    const val SIZE = 32

    /** `RIGHT1[k]` has its bottom `k` bits set; `RIGHT0` is its complement. */
    val RIGHT1 = IntArray(SIZE + 1)
    val RIGHT0 = IntArray(SIZE + 1)

    init {
      RIGHT1[0] = 0
      RIGHT0[0] = RIGHT1[0].inv()
      for (i in 1..SIZE) {
        RIGHT1[i] = (RIGHT1[i - 1] shl 1) or 1
        RIGHT0[i] = RIGHT1[i].inv()
      }
    }
  }
}

/**
 * The mapping between chart units and bitmap pixels, `vega-label/src/util/scaler.js`.
 *
 * A megapixel is the budget: past that the bitmap is coarsened rather than grown, so labelling a
 * 4,000 by 3,000 surface costs the same as labelling a 1,000 by 1,000 one. Below it the ratio is 1
 * and a pixel is a unit.
 *
 * [padding] is how far outside the surface a label may reach. It widens the bitmap on both sides,
 * which is why the transform can place a label that hangs off the edge without indexing out of it.
 */
internal class Scaler(val width: Double, val height: Double, val padding: Double) {
  val ratio: Double = maxOf(1.0, sqrt(width * height / 1e6))
  private val w: Int = ((width + 2 * padding + ratio) / ratio).toInt()
  private val h: Int = ((height + 2 * padding + ratio) / ratio).toInt()

  /** Truncates toward zero, as `~~` does — not `floor`, which differs for a negative. */
  fun scale(value: Double): Int = ((value + padding) / ratio).toInt()

  fun invert(value: Int): Double = value * ratio - padding

  fun bitmap(): Bitmap = Bitmap(w, h)

  val pixelWidth: Int
    get() = w

  val pixelHeight: Int
    get() = h
}
