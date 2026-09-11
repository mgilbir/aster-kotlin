package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A mark given a size of its own is placed by the **edge its alignment names**.
 *
 * ```js
 * const ALIGNED_X_CHANNEL = {left: 'x', center: 'xc', right: 'x2'};
 * const BASELINED_Y_CHANNEL = {top: 'y', middle: 'yc', bottom: 'y2'};
 * ...
 * if (channel === 'x') {
 *   return ALIGNED_X_CHANNEL[alignExcludingSignal || (defaultAlign === 'top' ? 'left' : 'center')];
 * } else {
 *   return BASELINED_Y_CHANNEL[alignExcludingSignal || defaultAlign];
 * }
 * ```
 *
 * A picture aligned to the **right** is placed by its right edge and the width runs back from it.
 * This compiler asked only whether the mark was centred in its band, so every such mark was written
 * with an `xc`: a picture tucked into the corner of a plot was drawn half outside it, and one
 * aligned to the left half a width too far along. One specification in the wild corpus puts a
 * picture in a corner that way.
 *
 * A word the map has no key for is answered by the **bare** channel, which is what
 * `BASELINED_Y_CHANNEL[…] ?? channel` comes to: a `"line-top"` baseline is a top for this even
 * though the map does not list it.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class AlignedPositionTest {

  /** Where the mark is placed, and how large. */
  private fun placed(mark: String): String {
    val spec =
      """{"data":{"values":[{"u":"a.png"}]},"mark":$mark,
         "encoding":{"url":{"field":"u","type":"nominal"}}}"""
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    val update =
      ((compiled.fields["marks"] as VegaValue.Arr).values[0] as VegaValue.Obj)
        .obj("encode")!!
        .obj("update")!!
    return update.fields
      .filterKeys { it in setOf("x", "y", "xc", "yc", "x2", "y2", "width", "height") }
      .entries
      .joinToString(",") { (key, value) ->
        "$key=${VegaJson.write(value).replace(Regex("""\n\s*"""), "")}"
      }
  }

  private val size = """"width":100,"height":150"""

  /** The reported shape: a picture in the corner, aligned right and hung from the top. */
  @Test
  fun `a picture aligned right is placed by its right edge`() {
    assertEquals(
      """x2={"field": {"group": "width"}},width={"value": 100},y={"value": 0},""" +
        """height={"value": 150}""",
      placed("""{"type":"image","align":"right","baseline":"line-top",$size}"""),
    )
  }

  /** `"top"` is the word the map does list, and it answers the same. */
  @Test
  fun `a top baseline is the same as a line-top one`() {
    assertEquals(
      """x2={"field": {"group": "width"}},width={"value": 100},y={"value": 0},""" +
        """height={"value": 150}""",
      placed("""{"type":"image","align":"right","baseline":"top",$size}"""),
    )
  }

  /** The other two edges, which is the rest of the map. */
  @Test
  fun `a picture aligned left and sat on the bottom is placed by those edges`() {
    assertEquals(
      """x={"field": {"group": "width"}},width={"value": 100},y2={"value": 0},""" +
        """height={"value": 150}""",
      placed("""{"type":"image","align":"left","baseline":"bottom",$size}"""),
    )
  }

  /** And a mark that states no alignment is centred, which is what must not change. */
  @Test
  fun `a picture with no alignment is centred`() {
    assertEquals(
      """xc={"field": {"group": "width"}},width={"value": 100},yc={"value": 0},""" +
        """height={"value": 150}""",
      placed("""{"type":"image",$size}"""),
    )
  }

  /** So is one that says so, `center` and `middle` being the words for it. */
  @Test
  fun `a picture aligned centre is centred`() {
    assertEquals(
      """xc={"field": {"group": "width"}},width={"value": 100},yc={"value": 0},""" +
        """height={"value": 150}""",
      placed("""{"type":"image","align":"center","baseline":"middle",$size}"""),
    )
  }
}
