package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * An assembled scale writes six names in a fixed order, and the rest after them.
 *
 * `assembleScalesForModel` builds the object from a literal rather than by accumulating into one,
 * so the order is stated rather than incidental:
 * ```js
 * const {name, type, selectionExtent, domains: _d, range: _r, reverse, ...otherScaleProps} = scale;
 * scales.push({
 *   name, type,
 *   ...(domain ? {domain} : {}),
 *   ...(domainRaw ? {domainRaw} : {}),
 *   range,
 *   ...(reverse !== undefined ? {reverse} : {}),
 *   ...otherScaleProps,
 * });
 * ```
 *
 * `domainRaw` and `reverse` are the two this compiler left to fall in wherever they happened to be
 * set — a selection's raw domain arriving after the range, a reverse anywhere at all — because
 * every other key comes out of one map in insertion order.
 *
 * **This is checked here and not by a fixture on purpose.** `SpecDiff` ignores object key order, by
 * design and for good reason: two specifications that differ only in the order of their keys are
 * the same specification to Vega, and to every reader of it that is not a human diffing bytes. So
 * the fixture gate, the scene comparison and the 21251-case schema sweep all agree either way, and
 * none of them can hold this rule. `RoundedStackGroupOrderTest` and `JavaScriptKeyOrderTest` are
 * the same shape of test for the same reason.
 *
 * Both expectations were read off upstream rather than reasoned about:
 * ```
 * config.scale.xReverse    x -> name, type, domain, range, reverse, nice, zero
 * a scale-bound selection  x -> name, type, domain, domainRaw, range, nice, zero
 * ```
 */
class ScaleKeyOrderTest {

  private fun scaleKeys(specification: String, scaleName: String): List<String> {
    val compiled =
      VegaJson.parse(
        requireNotNull(VegaLiteCompiler().compileJson(specification).toJson()) {
          "the specification did not compile"
        }
      ) as VegaValue.Obj
    val scales = compiled.fields["scales"] as VegaValue.Arr
    val scale =
      scales.values
        .map { it as VegaValue.Obj }
        .first { (it.fields["name"] as? VegaValue.Str)?.value == scaleName }
    return scale.fields.keys.toList()
  }

  @Test
  fun `a reversed scale writes its reverse straight after its range`() {
    assertEquals(
      listOf("name", "type", "domain", "range", "reverse", "nice", "zero"),
      scaleKeys(
        """
        {"data":{"values":[{"v":1},{"v":2}]},
         "mark":"point",
         "encoding":{"x":{"field":"v","type":"quantitative"},
                     "y":{"field":"v","type":"quantitative"}},
         "config":{"scale":{"xReverse":true}}}
        """,
        "x",
      ),
    )
  }

  @Test
  fun `a scale a selection binds writes its raw domain between the domain and the range`() {
    assertEquals(
      listOf("name", "type", "domain", "domainRaw", "range", "nice", "zero"),
      scaleKeys(
        """
        {"data":{"values":[{"v":1},{"v":2}]},
         "mark":"point",
         "params":[{"name":"grid","select":"interval","bind":"scales"}],
         "encoding":{"x":{"field":"v","type":"quantitative"},
                     "y":{"field":"v","type":"quantitative"}}}
        """,
        "x",
      ),
    )
  }

  /**
   * The scale beside it, which has neither, to say that nothing else moved.
   *
   * A hoist that wrote its names unconditionally would put an empty `domainRaw` and `reverse` into
   * every scale in the chart; this asserts the ordinary shape is untouched.
   */
  @Test
  fun `a scale with neither keeps the shape it had`() {
    assertEquals(
      listOf("name", "type", "domain", "range", "nice", "zero"),
      scaleKeys(
        """
        {"data":{"values":[{"v":1},{"v":2}]},
         "mark":"point",
         "params":[{"name":"grid","select":"interval","bind":"scales"}],
         "encoding":{"x":{"field":"v","type":"quantitative"},
                     "y":{"field":"v","type":"quantitative"}}}
        """,
        "y",
      ),
    )
  }
}
