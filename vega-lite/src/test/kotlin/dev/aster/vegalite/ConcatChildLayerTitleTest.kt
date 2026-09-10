package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A plot that is a layer promotes a title from one of its members, exactly as the chart does.
 *
 * `LayerModel.assembleTitle` is the same function whether the layer is the whole chart or one plot
 * of a concatenation — it looks into its children when it has no title of its own. Reading only the
 * plot's own title left a concatenation of layers untitled cell by cell, with the captions sitting
 * on the layers that carry the text marks. That is how Altair writes a small-multiples chart, and
 * two specifications in the wild corpus are written that way.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class ConcatChildLayerTitleTest {

  private fun groupTitles(spec: String): List<String?> {
    val compiled =
      VegaJson.parse(
        requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "did not compile" }
      ) as VegaValue.Obj
    return (compiled.fields["marks"] as VegaValue.Arr)
      .values
      .mapNotNull { it as? VegaValue.Obj }
      .filter { (it.fields["type"] as? VegaValue.Str)?.value == "group" }
      .map { group ->
        ((group.fields["title"] as? VegaValue.Obj)?.fields?.get("text") as? VegaValue.Str)?.value
      }
  }

  private val unit =
    """{"mark":"point","encoding":{"x":{"field":"a","type":"quantitative"},
       "y":{"field":"b","type":"quantitative"}}}"""

  private fun titled(caption: String) =
    """{"mark":"point","title":"$caption","encoding":{"x":{"field":"a","type":"quantitative"},
       "y":{"field":"b","type":"quantitative"}}}"""

  /** The reported shape: each cell is a layer whose caption sits on its second member. */
  @Test
  fun `each concatenated layer takes the title from its own member`() {
    assertEquals(
      listOf("first", "second"),
      groupTitles(
        """
        {"data":{"values":[{"a":1,"b":2}]},
         "hconcat":[{"layer":[$unit,${titled("first")}]},
                    {"layer":[$unit,${titled("second")}]}]}
        """
      ),
    )
  }

  /** A plot's own title still wins, and an untitled sibling stays untitled. */
  @Test
  fun `a plot's own title wins and an untitled plot stays untitled`() {
    assertEquals(
      listOf("own", null),
      groupTitles(
        """
        {"data":{"values":[{"a":1,"b":2}]},
         "hconcat":[{"mark":"point","title":"own",
                     "encoding":{"x":{"field":"a","type":"quantitative"},
                                 "y":{"field":"b","type":"quantitative"}}},
                    $unit]}
        """
      ),
    )
  }

  /** And the layer's own title outranks its members', one level down as at the top. */
  @Test
  fun `a titled layer outranks its members`() {
    assertEquals(
      listOf("outer"),
      groupTitles(
        """
        {"data":{"values":[{"a":1,"b":2}]},
         "hconcat":[{"title":"outer","layer":[$unit,${titled("inner")}]}]}
        """
      ),
    )
  }
}
