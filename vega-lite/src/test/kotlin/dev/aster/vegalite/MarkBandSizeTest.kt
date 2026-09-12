package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A rect-based mark's `width` or `height` is its band size.
 *
 * `getBandSize` in `channeldef.ts` settles the size before it looks at the scale at all:
 * ```js
 * const size = getMarkPropOrConfig(useVlSizeChannel ? 'size' : sizeChannel, mark, config, {
 *   vgChannel: sizeChannel,
 * });
 * if (size !== undefined) return size;
 * ```
 *
 * and `getMarkPropOrConfig` reads `mark[vgChannel]` first of all. So `{"type": "bar", "width": 25}`
 * is 25 wide, whatever its band or the configured band size would have made it. This engine looked
 * for `size` and never for the Vega name, so every such bar came out at the configured band size —
 * 25 of the wild corpus's disagreements over a mark's width, and the largest single cause left.
 *
 * Two things it is *not*. A `{"band": 0.5}` is a **fraction** of the band, not a size, and stays on
 * the bandwidth path; and a `width` in a **style block** is not read at all, because
 * `getMarkConfig` looks a style up under the Vega-Lite name — its own comment: "if there is
 * vgChannel, skip vl channel. For example, vl size for text is vg fontSize, but config.mark.size is
 * only for point size."
 *
 * Every expectation here was compiled with upstream rather than reasoned about.
 */
class MarkBandSizeTest {

  private fun update(json: String): VegaValue.Obj {
    val spec =
      VegaJson.parse(
        requireNotNull(VegaLiteCompiler().compileJson(json).toJson()) { "did not compile" }
      ) as VegaValue.Obj
    val marks = (spec.fields["marks"] as? VegaValue.Arr)?.values.orEmpty()
    return marks
      .mapNotNull { it as? VegaValue.Obj }
      .firstNotNullOf { mark ->
        (mark.fields["encode"] as? VegaValue.Obj)?.let { it.fields["update"] as? VegaValue.Obj }
      }
  }

  private fun chart(mark: String, x: String, y: String, config: String = "") =
    update(
      """
      {"data":{"values":[{"a":"A","b":28,"t":"2020-01-01"}]},
       "mark":$mark,$config
       "encoding":{"x":$x,"y":$y}}
      """
    )

  private val nominalX = """{"field":"a","type":"nominal"}"""
  private val quantitativeY = """{"field":"b","type":"quantitative"}"""

  private fun value(update: VegaValue.Obj, key: String): VegaValue? =
    (update.fields[key] as? VegaValue.Obj)?.fields?.get("value")

  /** The reported shape: a bar against a continuous axis, 25 units wide because it said so. */
  @Test
  fun `a width stated on the mark is the band size`() {
    val update =
      chart("""{"type":"bar","width":25}""", """{"field":"t","type":"temporal"}""", quantitativeY)
    assertEquals(VegaValue.Num(25.0), value(update, "width"))
  }

  /**
   * And on a **band** scale it centres the mark, which is the other half of the same rule:
   * upstream's `defaultBandAlign` is `'top'` only where the band size is *relative*, and a number
   * is not.
   */
  @Test
  fun `a width stated on the mark centres it in its band`() {
    val update = chart("""{"type":"rect","width":20}""", nominalX, quantitativeY)
    assertEquals(VegaValue.Num(20.0), value(update, "width"))
    assertNull(update.fields["x"], "a mark with a width of its own does not span the band")
    val centre = update.fields["xc"] as? VegaValue.Obj
    assertEquals(VegaValue.Num(0.5), centre?.fields?.get("band"), "it sits at the band's middle")
  }

  /** The same along the other direction, where the size channel is `height`. */
  @Test
  fun `a height stated on the mark sizes a horizontal bar`() {
    val update =
      update(
        """
        {"data":{"values":[{"a":"A","b":28}]},
         "mark":{"type":"bar","height":12},
         "encoding":{"y":$nominalX,"x":$quantitativeY}}
        """
      )
    assertEquals(VegaValue.Num(12.0), value(update, "height"))
    assertEquals(
      VegaValue.Num(0.5),
      (update.fields["yc"] as? VegaValue.Obj)?.fields?.get("band"),
    )
  }

  /** A `size` is read first — `if (encoding.size || markDef.size)` runs before `getBandSize`. */
  @Test
  fun `a stated size outranks a stated width`() {
    val update = chart("""{"type":"bar","width":25,"size":9}""", nominalX, quantitativeY)
    assertEquals(VegaValue.Num(9.0), value(update, "width"))
  }

  /** `getMarkConfig` reaches the mark type's own configuration under the Vega name. */
  @Test
  fun `a width configured for the mark type is the band size`() {
    val update =
      chart(""""bar"""", nominalX, quantitativeY, config = """"config":{"bar":{"width":17}},""")
    assertEquals(VegaValue.Num(17.0), value(update, "width"))
  }

  /** A width written as an expression is a signal, as a value read off a mark always is. */
  @Test
  fun `a width stated as an expression becomes a signal`() {
    val update = chart("""{"type":"bar","width":{"expr":"3*4"}}""", nominalX, quantitativeY)
    assertEquals(
      VegaValue.Str("3*4"),
      (update.fields["width"] as? VegaValue.Obj)?.fields?.get("signal"),
    )
  }

  /**
   * A **fraction** of the band is not a size: it stays on the bandwidth path, and the mark still
   * spans from the band's leading edge rather than being centred. Both gallery examples that this
   * rule first broke — `tick_width_band` and `bar_axis_space_saving` — were this shape.
   */
  @Test
  fun `a relative band size is not a stated size`() {
    val update = chart("""{"type":"bar","width":{"band":0.5}}""", nominalX, quantitativeY)
    assertNull(value(update, "width"), "it is a fraction of the bandwidth, not a number of units")
    assertEquals(
      VegaValue.Str("max(0.25, 0.5 * bandwidth('x'))"),
      (update.fields["width"] as? VegaValue.Obj)?.fields?.get("signal"),
    )
    assertEquals(
      VegaValue.Num(0.25),
      (update.fields["x"] as? VegaValue.Obj)?.fields?.get("band"),
      "`(1 - band) / 2` of the way in, rather than centred",
    )
  }

  /**
   * A `width` in a **style block** is not read, upstream looking a style up under the Vega-Lite
   * name only. Checked against upstream, which leaves the bar filling its band.
   */
  @Test
  fun `a width in a style block is not a band size`() {
    val update =
      chart(
        """{"type":"bar","style":"thin"}""",
        nominalX,
        quantitativeY,
        config = """"config":{"style":{"thin":{"width":8}}},""",
      )
    assertNull(value(update, "width"), "a style's `width` is not the size channel")
    assertEquals(
      VegaValue.Str("max(0.25, bandwidth('x'))"),
      (update.fields["width"] as? VegaValue.Obj)?.fields?.get("signal"),
    )
  }
}
