package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A repetition variable standing in a **list** channel is resolved like any other.
 *
 * ```js
 * if (isArray(channelDef)) {
 *   // array cannot have condition
 *   out[channel] = channelDef
 *     .map((cd) => replaceRepeaterInChannelDef(cd, repeater))
 *     .filter((cd) => cd);
 * } else {
 *   …
 * }
 * ```
 *
 * `replaceRepeaterInMapping` maps over the array rather than passing it along. This passed it
 * along, so `{"field": {"repeat": "repeat"}}` written as one entry of a `tooltip` was never
 * resolved: it named a column that is an object rather than a name, and was dropped for having no
 * field. The tooltip then showed every column but the repeated one — which is the one the chart is
 * repeating over.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class RepeatInListChannelTest {

  @Test
  fun `a repeat variable in a tooltip list is resolved per repetition`() {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"A":1,"B":2,"C":"x"}]},"repeat":["A","B"],
               "spec":{"mark":"point",
                 "encoding":{"x":{"field":{"repeat":"repeat"},"type":"quantitative"},
                             "y":{"field":"B","type":"quantitative"},
                             "tooltip":[{"field":"C","title":"cee"},
                                        {"field":{"repeat":"repeat"},"type":"quantitative"}]}}}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj

    val signals =
      (compiled.fields["marks"] as VegaValue.Arr)
        .values
        .mapNotNull { it as? VegaValue.Obj }
        .flatMap { (it.fields["marks"] as? VegaValue.Arr)?.values.orEmpty() }
        .mapNotNull { it as? VegaValue.Obj }
        .mapNotNull { mark ->
          ((mark.fields["encode"] as? VegaValue.Obj)?.fields?.get("update") as? VegaValue.Obj)
            ?.let { it.fields["tooltip"] as? VegaValue.Obj }
            ?.let { (it.fields["signal"] as? VegaValue.Str)?.value }
        }

    assertEquals(
      listOf(
        """{"cee": isValid(datum["C"]) ? isArray(datum["C"]) ? join(datum["C"], '\n') : """ +
          """datum["C"] : ""+datum["C"], "A": format(datum["A"], "")}""",
        """{"cee": isValid(datum["C"]) ? isArray(datum["C"]) ? join(datum["C"], '\n') : """ +
          """datum["C"] : ""+datum["C"], "B": format(datum["B"], "")}""",
      ),
      signals,
      "each repetition names its own column in the tooltip",
    )
  }
}
