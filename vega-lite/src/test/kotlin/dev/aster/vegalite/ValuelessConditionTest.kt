package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A condition that states **no value** is still a rule, and falls to the mark's own.
 *
 * `wrapCondition` builds one value ref per condition and spreads whatever the reference function
 * answers into it:
 * ```js
 * const conditionalValueRefs = conditions.map((c) => {
 *   const conditionValueRef = mainRefFn(c);
 *   return {test: conditionalTest(c, vgChannel, config), ...conditionValueRef};
 * });
 * ```
 *
 * and for a non-position channel that function carries a `defaultRef` — the mark's own value for
 * the property. So `{"condition": {"test": {"param": "p"}}, "value": 0}` reads *"whatever the mark
 * draws it at while the box is ticked, and invisible otherwise"*, which is how a chart hides its
 * labels behind a checkbox. Where the mark has no such default the ref is the **test alone**,
 * leaving the property unset for the rows the test picks.
 *
 * This engine dropped a condition it could get no value out of, so the rule came out as the
 * unconditional arm by itself — `{"value": 0}` — and the checkbox did nothing at all. Two
 * specifications in the wild corpus are written that way.
 *
 * The default is the mark's, through everything that speaks for the mark: its own property, the
 * theme's block for that mark type, and the faded 0.7 `initMarkdef` writes onto a point-like mark
 * before any encode block is built. The unconditional arm ends at the same value, `wrapCondition`
 * building it with the same function.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class ValuelessConditionTest {

  private fun opacity(body: String): List<String> {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"a":1,"b":2,"c":"x"}]},
               "params":[{"name":"p","bind":{"input":"checkbox"}}],$body}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    val mark = (compiled.fields["marks"] as VegaValue.Arr).values.first() as VegaValue.Obj
    val rule = mark.obj("encode")?.obj("update")?.fields?.get("opacity")
    return ((rule as? VegaValue.Arr)?.values ?: listOfNotNull(rule)).map { entry ->
      val ref = entry as VegaValue.Obj
      listOfNotNull(
          ref.string("test")?.let { "test=$it" },
          ref.fields["value"]?.let { "value=${(it as VegaValue.Num).value}" },
        )
        .joinToString(",")
    }
  }

  private val xy =
    """"x":{"field":"a","type":"quantitative"},"y":{"field":"b","type":"quantitative"}"""
  private val valueless = """{"condition":{"test":{"param":"p"}},"value":0}"""

  /** The reported shape: a text label hidden unless a checkbox is ticked. */
  @Test
  fun `a valueless condition on a mark with no default is the test alone`() {
    assertEquals(
      listOf("test=!!p", "value=0.0"),
      opacity(""""mark":"text","encoding":{$xy,"text":{"field":"c"},"opacity":$valueless}"""),
    )
  }

  /** A line and a bar have no opacity of their own either. */
  @Test
  fun `a line and a bar go the same way`() {
    assertEquals(
      listOf("test=!!p", "value=0.0"),
      opacity(""""mark":"line","encoding":{$xy,"opacity":$valueless}"""),
    )
    assertEquals(
      listOf("test=!!p", "value=0.0"),
      opacity(""""mark":"bar","encoding":{$xy,"opacity":$valueless}"""),
    )
  }

  /**
   * A **point** is drawn faded, and that 0.7 is on the mark before any encode block is built — so
   * the condition falls to it.
   */
  @Test
  fun `a valueless condition on a point falls to its faded opacity`() {
    assertEquals(
      listOf("test=!!p,value=0.7", "value=0.0"),
      opacity(""""mark":"point","encoding":{$xy,"opacity":$valueless}"""),
    )
  }

  /** The mark's own property answers before anything else. */
  @Test
  fun `a valueless condition falls to the mark's stated opacity`() {
    assertEquals(
      listOf("test=!!p,value=0.9", "value=0.0"),
      opacity(
        """"mark":{"type":"text","opacity":0.9},
           "encoding":{$xy,"text":{"field":"c"},"opacity":$valueless}"""
      ),
    )
  }

  /** And the theme's block for that mark type after it. */
  @Test
  fun `a valueless condition falls to the theme's opacity for the mark`() {
    assertEquals(
      listOf("test=!!p,value=0.8", "value=0.0"),
      opacity(
        """"mark":"text","config":{"text":{"opacity":0.8}},
           "encoding":{$xy,"text":{"field":"c"},"opacity":$valueless}"""
      ),
    )
  }

  /**
   * With **no** unconditional arm the rule ends at the same default, `wrapCondition` building both
   * with the one function.
   */
  @Test
  fun `a condition with nothing to fall back to ends at the mark's default`() {
    assertEquals(
      listOf("test=!!p,value=0.7", "value=0.7"),
      opacity(""""mark":"point","encoding":{$xy,"opacity":{"condition":{"test":{"param":"p"}}}}"""),
    )
  }

  /** A valueless condition beside one with a value keeps its place in the rule. */
  @Test
  fun `a valueless condition keeps its place among the others`() {
    assertEquals(
      listOf("test=!!p,value=0.7", "test=datum.a>0,value=0.5", "value=0.0"),
      opacity(
        """"mark":"point",
           "encoding":{$xy,"opacity":{"condition":[{"test":{"param":"p"}},
                                                   {"test":"datum.a>0","value":0.5}],"value":0}}"""
      ),
    )
  }
}
