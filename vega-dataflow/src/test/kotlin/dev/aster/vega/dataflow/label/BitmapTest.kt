package dev.aster.vega.dataflow.label

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The two halves of `vega-label` that have nothing to do with a canvas, against upstream's own
 * results.
 *
 * Worth pinning precisely because the rest of that transform **cannot** be pinned: its occupancy
 * bitmap is a rasterisation, and there is no oracle for one under Node. What can be checked is the
 * bit algebra every placement decision goes through and the pixel mapping underneath it — and both
 * are the parts where an off-by-one is invisible until a label lands on top of a point.
 */
class BitmapTest {

  private fun bitmap(): Bitmap =
    Bitmap(40, 12).apply {
      set(3, 1)
      set(39, 1)
      set(0, 11)
      set(20, 5)
    }

  @Test
  fun `a bit reads back where it was set and nowhere else`() {
    val b = bitmap()
    assertEquals(
      "101110",
      listOf(3 to 1, 4 to 1, 39 to 1, 0 to 11, 20 to 5, 21 to 5).joinToString("") { (x, y) ->
        if (b.get(x, y)) "1" else "0"
      },
    )
  }

  @Test
  fun `a range hits when anything inside it is set`() {
    val b = bitmap()
    val ranges =
      listOf(
        intArrayOf(0, 0, 5, 2),
        intArrayOf(5, 0, 10, 0),
        intArrayOf(35, 1, 39, 1),
        intArrayOf(0, 10, 0, 11),
        intArrayOf(10, 4, 25, 6),
        intArrayOf(21, 5, 30, 5),
      )
    assertEquals(
      "101110",
      ranges.joinToString("") { if (b.getRange(it[0], it[1], it[2], it[3])) "1" else "0" },
    )
  }

  @Test
  fun `setting a range fills exactly the span asked for, across word boundaries`() {
    val b = bitmap()
    // 8..33 spans three words of the bit vector, so the first and last are masked and the middle
    // is filled whole. Getting the masks wrong shows up only at the edges.
    b.setRange(8, 3, 33, 4)
    val ranges =
      listOf(
        intArrayOf(8, 3, 8, 3),
        intArrayOf(33, 4, 33, 4),
        intArrayOf(7, 3, 7, 3),
        intArrayOf(34, 3, 34, 4),
        intArrayOf(8, 3, 33, 4),
        intArrayOf(9, 3, 9, 3),
      )
    assertEquals(
      "110011",
      ranges.joinToString("") { if (b.getRange(it[0], it[1], it[2], it[3])) "1" else "0" },
    )
    // And the words themselves, unsigned, which is the strongest form this can be checked in.
    assertEquals(
      "0,2048,32768,0,67108863,4294967040,268435459,0,0,0,0,0,0,16777216,0,0",
      b.words().joinToString(",") { (it.toLong() and 0xFFFFFFFFL).toString() },
    )
  }

  @Test
  fun `out of bounds is checked on all four sides`() {
    val b = bitmap()
    val ranges =
      listOf(
        intArrayOf(0, 0, 39, 11),
        intArrayOf(-1, 0, 5, 5),
        intArrayOf(0, -1, 5, 5),
        intArrayOf(0, 0, 40, 5),
        intArrayOf(0, 0, 5, 12),
      )
    assertEquals(
      "01111",
      ranges.joinToString("") { if (b.outOfBounds(it[0], it[1], it[2], it[3])) "1" else "0" },
    )
  }

  @Test
  fun `the scaler truncates toward zero and coarsens past a megapixel`() {
    // The ratio is 1 until the surface passes a megapixel, and then the bitmap is coarsened rather
    // than grown — labelling a wall-sized chart costs what labelling a small one does. And the
    // mapping **truncates**, which is not `floor`: a coordinate a padding-width left of the origin
    // rounds towards zero, not away from it.

    Scaler(900.0, 500.0, 0.0).let { s ->
      assertEquals(1.0, s.ratio, 1e-12, "ratio for 900x500+0")
      assertEquals(
        "0/1/-1/7/-7/100/899",
        listOf(0.0, 1.0, -1.0, 7.5, -7.5, 100.0, 899.9).joinToString("/") {
          s.scale(it).toString()
        },
      )
      assertEquals("0/1/5", listOf(0, 1, 5).joinToString("/") { fmt(s.invert(it)) })
    }

    Scaler(900.0, 500.0, 12.5).let { s ->
      assertEquals(1.0, s.ratio, 1e-12, "ratio for 900x500+12.5")
      assertEquals(
        "12/13/11/20/5/112/912",
        listOf(0.0, 1.0, -1.0, 7.5, -7.5, 100.0, 899.9).joinToString("/") {
          s.scale(it).toString()
        },
      )
      assertEquals("-12.5/-11.5/-7.5", listOf(0, 1, 5).joinToString("/") { fmt(s.invert(it)) })
    }

    Scaler(4000.0, 3000.0, 40.0).let { s ->
      assertEquals(3.4641016151377544, s.ratio, 1e-12, "ratio for 4000x3000+40")
      assertEquals(
        "11/11/11/13/9/40/271",
        listOf(0.0, 1.0, -1.0, 7.5, -7.5, 100.0, 899.9).joinToString("/") {
          s.scale(it).toString()
        },
      )
      assertEquals(
        "-40/-36.53589838486224/-22.67949192431123",
        listOf(0, 1, 5).joinToString("/") { fmt(s.invert(it)) },
      )
    }

    Scaler(10.0, 10.0, 3.0).let { s ->
      assertEquals(1.0, s.ratio, 1e-12, "ratio for 10x10+3")
      assertEquals(
        "3/4/2/10/-4/103/902",
        listOf(0.0, 1.0, -1.0, 7.5, -7.5, 100.0, 899.9).joinToString("/") {
          s.scale(it).toString()
        },
      )
      assertEquals("-3/-2/2", listOf(0, 1, 5).joinToString("/") { fmt(s.invert(it)) })
    }
  }

  /** JavaScript's own rendering, so a whole number has no decimal point. */
  private fun fmt(v: Double): String =
    if (v == kotlin.math.floor(v) && kotlin.math.abs(v) < 1e21) v.toLong().toString()
    else v.toString()
}
