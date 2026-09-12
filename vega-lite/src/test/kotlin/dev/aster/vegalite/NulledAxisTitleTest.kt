package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A caption the **channel** nulls is as much the axis's `null` as one the axis block nulls.
 *
 * ```js
 * if (v1Val == null || v2Val === null) {
 *   return {explicit: v1.explicit, value: null};
 * }
 * ```
 *
 * `mergeTitleComponent` answers `null` for either side being it, and a layer that says its position
 * needs no caption has said so for the axis the layers share. This compiler read the `null` only
 * off the `axis` block, so a layer writing `"title": null` on its channel lost to whatever an
 * earlier layer had named, and an axis the specification asked to leave unlabelled came out
 * labelled.
 *
 * Two specifications in the wild corpus draw an error bar over a bar and null the error bar's
 * caption so the two do not both name the axis.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class NulledAxisTitleTest {

  /** Every axis, by the scale it measures and the caption it carries. */
  private fun titles(spec: String): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    return (compiled.fields["axes"] as? VegaValue.Arr)?.values.orEmpty().joinToString(";") {
      it as VegaValue.Obj
      "${it.string("scale")}=${it.fields["title"]?.let { title -> VegaJson.write(title) } ?: "<none>"}"
    }
  }

  private val rows = """"data":{"values":[{"a":1,"b":2,"e":0.5}]}"""
  private val y = """"y":{"field":"b","type":"quantitative"}"""

  private fun layer(title: String?, mark: String = "point", extra: String = "") =
    """{"mark":"$mark","encoding":{"x":{"field":"a","type":"quantitative"${
      if (title == null) "" else ""","title":$title"""
    }},$y$extra}}"""

  /** The reported shape: one layer names the axis, the next says it needs no name. */
  @Test
  fun `a channel's null clears a caption another layer gave`() {
    assertEquals(
      """x=<none>;y=<none>;x=<none>;y="b"""",
      titles("""{$rows,"layer":[${layer("\"Mean Duration\"")},${layer("null")}]}"""),
    )
  }

  /** Either side of the merge, which is what `mergeTitleComponent` says in so many words. */
  @Test
  fun `the null need not come second`() {
    assertEquals(
      """x=<none>;y=<none>;x=<none>;y="b"""",
      titles("""{$rows,"layer":[${layer("null")},${layer("\"Mean Duration\"")}]}"""),
    )
  }

  /** The same written on the `axis` block, which is where this was already read. */
  @Test
  fun `a nulled axis block clears it too`() {
    assertEquals(
      """x=<none>;y=<none>;x=<none>;y="b"""",
      titles(
        """{$rows,"layer":[${layer("\"One\"")},
           {"mark":"point","encoding":{"x":{"field":"a","type":"quantitative",
             "axis":{"title":null}},$y}}]}"""
      ),
    )
  }

  /** Two layers that each name the axis **join** their names, which must not change. */
  @Test
  fun `two names join`() {
    assertEquals(
      """x=<none>;y=<none>;x="One, Two";y="b"""",
      titles("""{$rows,"layer":[${layer("\"One\"")},${layer("\"Two\"")}]}"""),
    )
  }

  /** One name twice is one name, and a name against silence is that name. */
  @Test
  fun `a name against silence is that name`() {
    assertEquals(
      """x=<none>;y=<none>;x="One";y="b"""",
      titles("""{$rows,"layer":[${layer("\"One\"")},${layer("\"One\"")}]}"""),
    )
    assertEquals(
      """x=<none>;y=<none>;x="One";y="b"""",
      titles("""{$rows,"layer":[${layer("\"One\"")},${layer(null)}]}"""),
    )
  }

  /** With nothing said at all the column's own name is the caption. */
  @Test
  fun `silence leaves the column's own name`() {
    assertEquals(
      """x=<none>;y=<none>;x="a";y="b"""",
      titles("""{$rows,"layer":[${layer(null)},${layer(null)}]}"""),
    )
  }

  /**
   * The `axis` block is asked **first**, so a caption written there outranks a channel that nulls
   * it: the null is the channel's last word only where the axis has none of its own.
   */
  @Test
  fun `an axis block that names it outranks the channel's null`() {
    assertEquals(
      """x=<none>;y=<none>;x="Named";y="b"""",
      titles(
        """{$rows,"mark":"point","encoding":{"x":{"field":"a","type":"quantitative",
           "title":null,"axis":{"title":"Named"}},$y}}"""
      ),
    )
    assertEquals(
      """x=<none>;y=<none>;x="One, Named";y="b"""",
      titles(
        """{$rows,"layer":[${layer("\"One\"")},
           {"mark":"point","encoding":{"x":{"field":"a","type":"quantitative","title":null,
             "axis":{"title":"Named"}},$y}}]}"""
      ),
    )
  }

  /** And the reported shape itself: an error bar drawn over a bar, its own caption nulled. */
  @Test
  fun `an error bar may decline to name the axis`() {
    assertEquals(
      """x=<none>;y=<none>;x=<none>;y="b"""",
      titles(
        """{$rows,"layer":[${layer("\"Mean Duration\"", mark = "bar")},
           ${layer("null", mark = "errorbar", extra = ""","xError":{"field":"e"}""")}]}"""
      ),
    )
  }
}
