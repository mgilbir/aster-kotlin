package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The stack is computed **before** its order is aligned with the colour domain.
 *
 * `UnitModel`'s constructor runs `this.stack = stack(mark, encoding)` and only then
 * `this.alignStackOrderWithColorDomain()`, which may write an `order` channel of its own. So
 * `stackBy` — the channels that split a column into segments — was settled without it, and the
 * `_«field»_sort_index` column that rule adds is not one of the stack's own dimensions, however
 * much `order` counts as a non-position channel.
 *
 * A stacked **area** is where it shows: `stackby` is the `impute` transform's groupby, so counting
 * the sort index filled each colour's missing values per index instead of per colour. Four
 * specifications in the wild corpus state a colour domain over a stacked area, which is the shape
 * that triggers the alignment.
 *
 * A **user-written** order channel does contribute — it was there when the stack was computed. That
 * contrast is the whole of the rule, and it is the last case here.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class StackByBeforeAlignmentTest {

  private fun groupbys(colour: String, extra: String = ""): List<Pair<String, List<String>>> {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"y":2020,"v":1,"p":"dem"}]},"mark":"area",
               "encoding":{"x":{"field":"y","type":"quantitative"},
                           "y":{"field":"v","type":"quantitative","stack":true},
                           "color":$colour$extra}}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    return (compiled.fields["data"] as VegaValue.Arr)
      .values
      .mapNotNull { it as? VegaValue.Obj }
      .flatMap { (it.fields["transform"] as? VegaValue.Arr)?.values.orEmpty() }
      .mapNotNull { it as? VegaValue.Obj }
      .mapNotNull { t ->
        val type = (t.fields["type"] as? VegaValue.Str)?.value ?: return@mapNotNull null
        if (type != "impute" && type != "stack") return@mapNotNull null
        type to
          (t.fields["groupby"] as? VegaValue.Arr)?.values.orEmpty().mapNotNull {
            (it as? VegaValue.Str)?.value
          }
      }
  }

  private val withDomain = """{"field":"p","type":"nominal","scale":{"domain":["dem","rep"]}}"""
  private val plain = """{"field":"p","type":"nominal"}"""

  /** The reported shape: a stated colour domain, which adds an order channel of its own. */
  @Test
  fun `the sort index the alignment adds does not group the impute`() {
    assertEquals(listOf("impute" to listOf("p"), "stack" to listOf("y")), groupbys(withDomain))
  }

  /** With no domain there is no alignment, and the answer is the same — which is the point. */
  @Test
  fun `a stack with no colour domain groups the same way`() {
    assertEquals(listOf("impute" to listOf("p"), "stack" to listOf("y")), groupbys(plain))
  }

  /**
   * A **user's** order channel was there when the stack was computed, so it is one of its
   * dimensions. This is the contrast that makes the rule about *ordering* rather than about the
   * `order` channel.
   */
  @Test
  fun `an order channel the specification wrote does group the impute`() {
    assertEquals(
      listOf("impute" to listOf("p", "v"), "stack" to listOf("y")),
      groupbys(withDomain, ""","order":{"field":"v","type":"quantitative"}"""),
    )
  }
}
