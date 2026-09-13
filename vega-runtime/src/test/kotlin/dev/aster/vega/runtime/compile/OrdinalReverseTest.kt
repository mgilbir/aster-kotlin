package dev.aster.vega.runtime.compile

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asString
import dev.aster.vega.runtime.scale.VegaScale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `reverse` flips the **range** of an ordinal scale, as it does of every other.
 *
 * ```js
 * if (range) scale.range(flip(range, _.reverse));
 * ```
 *
 * `configureRange` ends by flipping whatever range it assembled, whichever family the scale belongs
 * to, and it is the ordinary way to say "darkest first" without rewriting the list. This engine
 * applied it to the continuous families, to `band` and `point`, and to the binned ones — and not to
 * `ordinal`. An area chart that asks for its three opacities in reverse got them the right way
 * round and shaded every band wrongly, which is a wrong picture with nothing in it to say so.
 *
 * A **scheme** is reversed too, and that is the same rule applied after the colours are resolved.
 *
 * Every expectation was read off a live upstream view.
 */
class OrdinalReverseTest {

  private fun scales(json: String): Map<String, VegaScale> {
    val compiled =
      SpecCompiler(VegaHeadlessTextEngine())
        .compileJson(
          """{"width": 10, "height": 10, "padding": 0, "autosize": "none", "scales": [$json]}"""
        )
    check(
      compiled.diagnostics.none { it.severity >= dev.aster.vega.model.DiagnosticSeverity.ERROR }
    ) {
      "compile reported ${compiled.diagnostics.map { it.message }}"
    }
    return compiled.scales
  }

  private fun applied(scale: VegaScale, inputs: List<String>): String =
    inputs.joinToString(" ") { scale.scale(VegaValue.Str(it)).asString() }

  @Test
  fun `a reversed ordinal range runs the other way`() {
    val built =
      scales(
        """{"name": "o", "type": "ordinal", "domain": ["a", "b", "c", "d"],
                           "range": [0.5, 0.7, 0.9], "reverse": true}"""
      )
    assertEquals("0.9 0.7 0.5 0.9", applied(built.getValue("o"), listOf("a", "b", "c", "d")))
  }

  /** And without it, the way it was written — which is what this did for both. */
  @Test
  fun `an ordinal range that is not reversed is unchanged`() {
    val built =
      scales(
        """{"name": "p", "type": "ordinal", "domain": ["a", "b", "c", "d"],
                           "range": [0.5, 0.7, 0.9]}"""
      )
    assertEquals("0.5 0.7 0.9 0.5", applied(built.getValue("p"), listOf("a", "b", "c", "d")))
  }

  /** A scheme is a range like any other once it has been resolved to colours. */
  @Test
  fun `a reversed scheme starts at the far end`() {
    val built =
      scales(
        """{"name": "s", "type": "ordinal", "domain": ["a", "b", "c"],
                           "scheme": "category10", "reverse": true}"""
      )
    assertEquals("#17becf #bcbd22 #7f7f7f", applied(built.getValue("s"), listOf("a", "b", "c")))
  }

  /** A band scale already did this, and must keep doing it. */
  @Test
  fun `a reversed band scale places its first category last`() {
    val built =
      scales(
        """{"name": "b", "type": "band", "domain": ["a", "b", "c"],
                           "range": [0, 90], "reverse": true}"""
      )
    assertEquals("60 30 0", applied(built.getValue("b"), listOf("a", "b", "c")))
  }
}
