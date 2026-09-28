package dev.aster.vega.runtime

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/**
 * What an unreadable or empty paint does to a mark's **measurement**.
 *
 * The record half of this is compared by `a-fill-that-is-not-a-colour-is-still-a-fill`. The
 * measuring half cannot be: upstream keeps an empty stroke on its item and refuses to measure it,
 * and this engine can do the second without the first, so a fixture over both would fail on a
 * difference nothing can see. This is the part that has a consequence.
 */
class UnreadablePaintTest {

  /** A declared size of zero, so the surface is measured from the mark and nothing else. */
  private fun width(stroke: String?): Double {
    val strokeJson = stroke?.let { """"stroke":{"value":"$it"},"strokeWidth":{"value":2},""" } ?: ""
    val controller = VegaChartController()
    controller.setSpec(
      """
      {"width":0,"height":0,"padding":0,"autosize":{"type":"pad"},
       "marks":[{"type":"rect","encode":{"update":{
         "x":{"value":0},"y":{"value":0},"width":{"value":30},"height":{"value":14},
         $strokeJson"fill":{"value":"#eee"}}}}]}
      """
        .trimIndent()
    )
    return controller.lastCompiled!!.scene!!.width
  }

  @Test
  fun `an empty stroke does not widen a mark and an unreadable one does`() {
    // `boundStroke` opens `if (item.stroke && item.opacity !== 0 && item.strokeOpacity !== 0)`, and
    // that first test is **falsiness**: `""` fails it and `"not a colour"` passes it. So a mark
    // with
    // an empty stroke measures as though it had none, and a mark with a stroke nothing can paint
    // measures a stroke-width wider. Probed against upstream, which bounds the two rects 30 and 32.
    assertEquals(30.0, width(null), "no stroke")
    assertEquals(30.0, width(""), "an empty stroke is falsey to `boundStroke`")
    assertEquals(32.0, width("not a colour"), "an unreadable stroke is truthy and still measures")
  }
}
