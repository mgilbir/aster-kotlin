package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * A key captioned with nothing is a key with no caption.
 *
 * `assembleLegend` strips a falsy title on the way out:
 * ```js
 * if (!legend.title) {
 *   // title schema doesn't include null, ''
 *   delete legend.title;
 * }
 * ```
 *
 * Any falsy caption, so `""` takes the caption off exactly as `null` does — and this engine dropped
 * only the `null`, writing `"title": ""` into eleven of the wild corpus's legends and reserving the
 * space for a caption that says nothing.
 *
 * The **place** it happens is the other half. It is at assembly, after the layers have been merged,
 * because the caption is what `mergeValuesWithExplicit` settled between them: a layer that states
 * `"title": null` has been explicit, and an explicit value beats a sibling's derived one. Stripping
 * it any earlier takes the key away, and the merge then fills the caption back in from the other
 * layer — which is exactly what a first attempt at this did.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class LegendTitleTest {

  private fun legends(spec: String): List<VegaValue.Obj> =
    ((VegaJson.parse(
          requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "did not compile" }
        ) as VegaValue.Obj)
        .fields["legends"]
        as? VegaValue.Arr)
      ?.values
      ?.mapNotNull { it as? VegaValue.Obj }
      .orEmpty()

  private val position =
    """"x":{"field":"a","type":"quantitative"},"y":{"field":"b","type":"quantitative"}"""

  private fun chart(colour: String) =
    legends(
      """
      {"data":{"values":[{"a":1,"b":2,"c":"x"}]},"mark":"point",
       "encoding":{$position,"color":$colour}}
      """
    )

  private fun layered(first: String, second: String) =
    legends(
      """
      {"data":{"values":[{"a":1,"b":2,"c":"x"}]},
       "layer":[{"mark":"line","encoding":{$position,"color":$first}},
                {"mark":"point","encoding":{$position,"color":$second}}]}
      """
    )

  private fun assertUncaptioned(legends: List<VegaValue.Obj>) {
    assertEquals(1, legends.size, "there is still a key; it is the caption that goes")
    assertFalse(
      legends.single().fields.containsKey("title"),
      "the property is deleted rather than written empty",
    )
  }

  /** The reported shape: an empty caption on the channel. */
  @Test
  fun `an empty title on the channel leaves the key uncaptioned`() {
    assertUncaptioned(chart("""{"field":"c","type":"nominal","title":""}"""))
  }

  /** And on the legend block, which is the other place a caption is written. */
  @Test
  fun `an empty title on the legend block leaves the key uncaptioned`() {
    assertUncaptioned(chart("""{"field":"c","type":"nominal","legend":{"title":""}}"""))
  }

  /** `null` did this already, and is kept honest beside it. */
  @Test
  fun `a null title leaves the key uncaptioned`() {
    assertUncaptioned(chart("""{"field":"c","type":"nominal","title":null}"""))
  }

  /**
   * The merge case, and the one that decides *where* the rule belongs: one layer states `null` and
   * the other leaves the caption to be derived from the column's name. The stated value is the
   * explicit one and wins, so the merged key has no caption — whichever order the layers are in.
   */
  @Test
  fun `a stated null in one layer uncaptions the merged key`() {
    val bare = """{"field":"c","type":"nominal"}"""
    val stated = """{"field":"c","type":"nominal","title":null}"""
    assertUncaptioned(layered(stated, bare))
    assertUncaptioned(layered(bare, stated))
  }

  /** A caption that says something is still written, the rule being about falsy ones only. */
  @Test
  fun `a real title is kept`() {
    assertEquals(
      VegaValue.Str("kept"),
      chart("""{"field":"c","type":"nominal","title":"kept"}""").single().fields["title"],
    )
  }

  /** And a key with no title stated at all is captioned by the column, as it always was. */
  @Test
  fun `a key with nothing stated is captioned by its column`() {
    assertEquals(
      VegaValue.Str("c"),
      chart("""{"field":"c","type":"nominal"}""").single().fields["title"],
    )
  }
}
