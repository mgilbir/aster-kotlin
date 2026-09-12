package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * An axis caption may be several lines, and goes out as the list it was written as.
 *
 * ```js
 * function assembleTitle(title, config) {
 *   if (!title) return undefined;
 *   if (isArray(title) && !isText(title)) {
 *     return title.map((fieldDef) => defaultTitle(fieldDef, config)).join(', ');
 *   }
 *   return title;
 * }
 * ```
 *
 * The `!isText(title)` is what picks out the *other* kind of array — a list of field definitions,
 * which is what a shared axis's merged titles are, joined with commas. A list of **strings** is
 * already text, and Vega draws it one line per entry.
 *
 * This kept only single-string titles, folding them into the list of definitions the merge counts,
 * and dropped a list of lines on the floor: four specifications in the wild corpus caption an axis
 * with two lines — an arrow over a phrase, which is how a chart labels a direction — and came out
 * with no caption at all.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class AxisTitleLinesTest {

  private fun xTitle(spec: String): VegaValue? {
    val compiled =
      VegaJson.parse(
        requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "did not compile" }
      ) as VegaValue.Obj
    return (compiled.fields["axes"] as VegaValue.Arr)
      .values
      .mapNotNull { it as? VegaValue.Obj }
      .first {
        (it.fields["scale"] as? VegaValue.Str)?.value == "x" &&
          (it.fields["grid"] as? VegaValue.Bool)?.value != true
      }
      .fields["title"]
  }

  private fun chart(x: String) =
    xTitle(
      """
      {"data":{"values":[{"a":1,"b":2}]},"mark":"point",
       "encoding":{"x":$x,"y":{"field":"b","type":"quantitative"}}}
      """
    )

  private val twoLines = VegaValue.Arr(listOf(VegaValue.Str("one"), VegaValue.Str("two")))

  /** The reported shape: a caption of two lines, written on the channel. */
  @Test
  fun `a caption of several lines is kept as a list`() {
    assertEquals(twoLines, chart("""{"field":"a","type":"quantitative","title":["one","two"]}"""))
  }

  /** And the same written inside the `axis` block, which is the other place a caption goes. */
  @Test
  fun `a caption of several lines in the axis block is kept`() {
    assertEquals(
      twoLines,
      chart("""{"field":"a","type":"quantitative","axis":{"title":["one","two"]}}"""),
    )
  }

  /** One string is still one string, not a list of one. */
  @Test
  fun `a caption of one line is unchanged`() {
    assertEquals(
      VegaValue.Str("one"),
      chart("""{"field":"a","type":"quantitative","title":"one"}"""),
    )
  }

  /** An empty list is not text and captions nothing. */
  @Test
  fun `an empty list captions nothing`() {
    assertNull(chart("""{"field":"a","type":"quantitative","title":[]}"""))
  }

  /** Across layers it survives the merge, where the joined single-line titles would not apply. */
  @Test
  fun `a caption of several lines survives a layer merge`() {
    assertEquals(
      twoLines,
      xTitle(
        """
        {"data":{"values":[{"a":1,"b":2}]},
         "layer":[
           {"mark":"line","encoding":{"x":{"field":"a","type":"quantitative","title":["one","two"]},
                                      "y":{"field":"b","type":"quantitative"}}},
           {"mark":"line","encoding":{"x":{"field":"a","type":"quantitative"},
                                      "y":{"field":"b","type":"quantitative"}}}]}
        """
      ),
    )
  }
}
