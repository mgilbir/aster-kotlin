package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A label's anchors are derived from the **normalised** angle, the one that is written out.
 *
 * `defaultLabelAngle` normalises what the specification wrote — `((angle % 360) + 360) % 360` — and
 * hands that on to both `defaultLabelAlign` and `defaultLabelBaseline`. The two read the angle as a
 * position on the circle:
 * ```js
 * if ((45 < angle && angle < 135) || (225 < angle && angle < 315)) return 'middle';
 * return (angle <= 45 || 315 <= angle) === (orient === 'top') ? 'bottom' : 'top';
 * ```
 *
 * This normalised the angle for the value it *emitted* and then compared the raw one. A label at
 * minus ninety degrees is a label at two hundred and seventy: it satisfies `225 < a && a < 315` and
 * is anchored through its middle. As minus ninety it satisfies neither arm, falls to `angle <= 45`
 * — which is true of every negative angle — and was anchored by its top, so a column of vertical
 * date labels hung a line below the axis it belongs to. Ten specifications in the wild corpus wrote
 * `labelAngle: -90`, which is how a chart of dates is usually written.
 *
 * The expectations are upstream's, compiled for each angle on both channels.
 */
class LabelAngleAnchorTest {

  private fun axis(channel: String, angle: Int): VegaValue.Obj {
    val other = if (channel == "x") "y" else "x"
    val spec =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"a":"A","b":2}]},"mark":"point",
               "encoding":{"$channel":{"field":"a","type":"nominal","axis":{"labelAngle":$angle}},
                           "$other":{"field":"b","type":"quantitative"}}}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    val axes = (spec.fields["axes"] as? VegaValue.Arr)?.values.orEmpty()
    return axes
      .mapNotNull { it as? VegaValue.Obj }
      .first {
        (it.fields["scale"] as? VegaValue.Str)?.value == channel &&
          it.fields.containsKey("labelAngle")
      }
  }

  private fun anchors(channel: String, angle: Int): Triple<Double?, String?, String?> {
    val axis = axis(channel, angle)
    return Triple(
      (axis.fields["labelAngle"] as? VegaValue.Num)?.value,
      (axis.fields["labelAlign"] as? VegaValue.Str)?.value,
      (axis.fields["labelBaseline"] as? VegaValue.Str)?.value,
    )
  }

  /** The reported shape: the usual way to write a column of dates. */
  @Test
  fun `a vertical label on the bottom axis is anchored through its middle`() {
    assertEquals(Triple(270.0, "right", "middle"), anchors("x", -90))
  }

  /**
   * Every angle upstream was asked for, on the horizontal axis. Minus forty-five and minus a
   * hundred and thirty-five are the pair that show the fault was not only about `middle`: read raw
   * they both take the `angle <= 45` arm, and they normalise to opposite sides of the circle.
   */
  @Test
  fun `the horizontal axis follows upstream at every angle`() {
    assertEquals(Triple(315.0, "right", "top"), anchors("x", -45))
    assertEquals(Triple(225.0, "right", "bottom"), anchors("x", -135))
    assertEquals(Triple(90.0, "left", "middle"), anchors("x", -270))
    assertEquals(
      Triple(45.0, "left", "top"),
      anchors("x", 45),
      "a positive angle was already right",
    )
    assertEquals(Triple(90.0, "left", "middle"), anchors("x", 90))
    assertEquals(
      Triple(90.0, "left", "middle"),
      anchors("x", 450),
      "a full turn past the same place",
    )
  }

  /**
   * And the vertical axis, whose rule has a third answer: an angle along the axis leaves the
   * baseline to Vega, and upstream writes none.
   */
  @Test
  fun `the vertical axis follows upstream at every angle`() {
    assertEquals(Triple(270.0, "center", "bottom"), anchors("y", -90))
    assertEquals(Triple(315.0, "right", null), anchors("y", -45))
    assertEquals(Triple(225.0, "left", null), anchors("y", -135))
    assertEquals(Triple(90.0, "center", "top"), anchors("y", -270))
    assertEquals(Triple(45.0, "right", null), anchors("y", 45))
    assertEquals(Triple(90.0, "center", "top"), anchors("y", 90))
  }
}
