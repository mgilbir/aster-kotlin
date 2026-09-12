package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A title written on one of a layer's members is the **chart's** title.
 *
 * ```js
 * public assembleTitle(): VgTitle {
 *   let title = super.assembleTitle();
 *   if (title) return title;
 *   // If title does not provide layer, look into children
 *   for (const child of this.children) {
 *     title = child.assembleTitle();
 *     if (title) return title;
 *   }
 *   return undefined;
 * }
 * ```
 *
 * A layer's members are drawn in one group, so there is no child group for such a title to sit over
 * — and rather than lose it, `LayerModel` promotes it. The first member that has one wins, depth
 * first, and a title on the layer itself outranks every one of them. A **concatenation** does not
 * do this: its children have groups of their own, and a title written on one stays there.
 *
 * This engine dropped it. 14 specifications in the wild corpus — Altair's output, which writes the
 * title on the layer that carries the text mark — came out with no title at all.
 *
 * Two smaller facts of the same function are checked here too: a title needs `text` to be a title,
 * and `isText` accepts an array of strings as readily as one string.
 */
class LayerTitleTest {

  private fun title(spec: String): VegaValue? =
    (VegaJson.parse(
        requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "did not compile" }
      ) as VegaValue.Obj)
      .fields["title"]

  private fun text(title: VegaValue?): VegaValue? = (title as? VegaValue.Obj)?.fields?.get("text")

  private val unit =
    """{"mark":"point","encoding":{"x":{"field":"a","type":"quantitative"},
       "y":{"field":"b","type":"quantitative"}}}"""

  private fun titled(caption: String) =
    """{"mark":"point","title":$caption,"encoding":{"x":{"field":"a","type":"quantitative"},
       "y":{"field":"b","type":"quantitative"}}}"""

  private fun layered(vararg members: String) =
    title("""{"data":{"values":[{"a":1,"b":2}]},"layer":[${members.joinToString(",")}]}""")

  /** The reported shape: the title is on the second member and belongs to the chart. */
  @Test
  fun `a title on a layer member becomes the chart's title`() {
    val title = layered(unit, titled(""""child""""))
    assertEquals(VegaValue.Str("child"), text(title))
    assertEquals(
      VegaValue.Str("group"),
      (title as? VegaValue.Obj)?.fields?.get("frame"),
      "it is assembled as the member's own title, and a unit's is framed to its group",
    )
  }

  /** The first member that has one wins; the rest are not looked at. */
  @Test
  fun `the first titled member wins`() {
    assertEquals(
      VegaValue.Str("first"),
      text(layered(titled(""""first""""), titled(""""second""""))),
    )
  }

  /** And the layer's own title outranks all of them. */
  @Test
  fun `a title on the layer itself outranks its members`() {
    val title =
      title(
        """{"data":{"values":[{"a":1,"b":2}]},"title":"top","layer":[${titled(""""child"""")}]}"""
      )
    assertEquals(VegaValue.Str("top"), text(title))
  }

  /** The walk is depth first, so a title inside a nested layer is found before a later sibling. */
  @Test
  fun `a title inside a nested layer is found`() {
    val nested = """{"layer":[$unit,${titled(""""deep"""")}]}"""
    assertEquals(VegaValue.Str("deep"), text(layered(nested, unit)))
  }

  /**
   * A member whose title has no `text` has nothing to promote, and the walk carries on past it —
   * `if (title.text)` is what makes `assembleTitle` answer nothing for such a block.
   */
  @Test
  fun `a member whose title has no text is passed over`() {
    assertEquals(
      VegaValue.Str("real"),
      text(layered(titled("""{"anchor":"end"}"""), titled(""""real""""))),
    )
  }

  /**
   * A title of **no words** is no title either: `if (title.text)` is falsy for the empty string,
   * and writing one out reserved the space above the chart for it. `""` is what a specification
   * written by a tool that always emits the key leaves behind, and three in the wild corpus do.
   */
  @Test
  fun `a title of no words is not written at all`() {
    val empty =
      """{"data":{"values":[{"a":1,"b":2}]},"mark":"point","title":%s,
         "encoding":{"x":{"field":"a","type":"quantitative"},
                     "y":{"field":"b","type":"quantitative"}}}"""
    assertNull(title(empty.format("\"\"")), "an empty string")
    assertNull(title(empty.format("""{"text":""}""")), "an empty text")
    assertNull(title(empty.format("[]")), "an empty list of lines")
  }

  /** The same rule at the top: a block of title properties with no text is not a title. */
  @Test
  fun `a title with no text is not written at all`() {
    assertNull(
      title(
        """
        {"data":{"values":[{"a":1,"b":2}]},"mark":"point","title":{"anchor":"start"},
         "encoding":{"x":{"field":"a","type":"quantitative"},
                     "y":{"field":"b","type":"quantitative"}}}
        """
      )
    )
  }

  /** `isText`: a title over several lines is a list of them, and becomes the `text`. */
  @Test
  fun `a title written as an array of strings is a title`() {
    val title =
      title(
        """
        {"data":{"values":[{"a":1,"b":2}]},"mark":"point","title":["one","two"],
         "encoding":{"x":{"field":"a","type":"quantitative"},
                     "y":{"field":"b","type":"quantitative"}}}
        """
      )
    assertEquals(
      VegaValue.Arr(listOf(VegaValue.Str("one"), VegaValue.Str("two"))),
      text(title),
      "it becomes the title's `text` rather than standing as the title itself",
    )
    assertEquals(VegaValue.Str("group"), (title as? VegaValue.Obj)?.fields?.get("frame"))
  }

  /**
   * A **concatenation** keeps a child's title on the child, having a group for it. Checked here so
   * that the promotion stays the layer's own rule rather than becoming every composition's.
   */
  @Test
  fun `a concatenation does not promote its child's title`() {
    val spec =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """{"data":{"values":[{"a":1,"b":2}]},"hconcat":[$unit,${titled(""""child"""")}]}"""
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    assertNull(spec.fields["title"], "the chart itself is untitled")
    val groupTitles =
      (spec.fields["marks"] as? VegaValue.Arr)
        ?.values
        ?.mapNotNull { (it as? VegaValue.Obj)?.fields?.get("title") }
        .orEmpty()
    assertEquals(1, groupTitles.size, "the caption stays on the plot that was captioned")
    assertEquals(VegaValue.Str("child"), text(groupTitles.single()))
  }
}
