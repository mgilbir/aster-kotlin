package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A legend one layer switches **off** stays off for the merged legend.
 *
 * `parseLegendForChannel` builds a component for a disabled legend rather than none, and records
 * the `disable` as **explicit** whenever the channel wrote a `legend` at all:
 * ```js
 * const disable = legend !== undefined ? !legend : legendConfig.disable;
 * legendCmpt.set('disable', disable, legend !== undefined);
 * if (disable) return legendCmpt;
 * ```
 *
 * `mergeLegendComponent` then folds `disable` with `mergeValuesWithExplicit` like any other
 * property, and `assembleLegend` answers nothing for a component the fold left disabled.
 *
 * Dropping the disabled component instead — which is what this engine did — let the *other* layer's
 * legend stand: four specifications in the wild corpus drew a key their first layer had switched
 * off, and one of them a key captioned `gender, t, t`, the three layers' titles joined.
 *
 * The rule cuts both ways, which is what makes it a rule about explicitness rather than about
 * disabling. A layer writing `"legend": {}` says explicitly that its legend is *not* disabled, so
 * it brings back a key `config.legend.disable` had switched off; and between two layers that both
 * state one, the first stands, as it does for every other property.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class DisabledLegendMergeTest {

  /** Each merged legend as `title/orient`, or nothing where none is drawn. */
  private fun legends(first: String, second: String, extra: String = ""): List<String> {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"a":1,"t":"x"}]}$extra,
               "layer":[{"mark":"point","encoding":{"x":{"field":"a","type":"quantitative"},
                          "color":$first}},
                        {"mark":"line","encoding":{"x":{"field":"a","type":"quantitative"},
                          "color":$second}}]}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    return (compiled.fields["legends"] as? VegaValue.Arr)
      .let { it?.values.orEmpty() }
      .mapNotNull { it as? VegaValue.Obj }
      .map { "${it.string("title")}${it.string("orient")?.let { o -> "/$o" } ?: ""}" }
  }

  private val plain = """{"field":"t","type":"nominal"}"""
  private val off = """{"field":"t","type":"nominal","legend":null}"""

  /** The reported shape: the first layer switches the key off and the second says nothing. */
  @Test
  fun `a legend the first layer switches off is not drawn`() {
    assertEquals(emptyList<String>(), legends(off, plain))
  }

  /** And the second layer switching it off, which is the half that worked by accident. */
  @Test
  fun `a legend the second layer switches off is not drawn`() {
    assertEquals(emptyList<String>(), legends(plain, off))
  }

  /** Both layers agreeing is the same answer by an easier route. */
  @Test
  fun `two layers that both switch it off draw nothing`() {
    assertEquals(emptyList<String>(), legends(off, off))
  }

  /**
   * Between two layers that both **state** a legend the first stands, so an `off` followed by a
   * stated block leaves the key away and the other order leaves it drawn.
   */
  @Test
  fun `between two stated legends the first layer wins`() {
    val left = """{"field":"t","type":"nominal","legend":{"orient":"left"}}"""
    assertEquals(emptyList<String>(), legends(off, left), "off first, so off")
    assertEquals(listOf("t/left"), legends(left, off), "on first, so on — and its orient with it")
  }

  /** `"legend": false` is the same statement written another way. */
  @Test
  fun `a legend stated as false is switched off`() {
    assertEquals(
      emptyList<String>(),
      legends("""{"field":"t","type":"nominal","legend":false}""", plain),
    )
  }

  /** And `disable` written **inside** the block, which is read off it like any other property. */
  @Test
  fun `a disable inside the block is switched off`() {
    assertEquals(
      emptyList<String>(),
      legends("""{"field":"t","type":"nominal","legend":{"disable":true}}""", plain),
    )
  }

  /**
   * The other direction: a theme that drops every key is a *derived* answer, so one layer stating a
   * legend at all brings the key back.
   */
  @Test
  fun `a stated block beats a theme that disables every legend`() {
    val theme = ""","config":{"legend":{"disable":true}}"""
    assertEquals(
      emptyList<String>(),
      legends(plain, plain, theme),
      "neither layer states one, so the theme stands",
    )
    assertEquals(
      listOf("t"),
      legends("""{"field":"t","type":"nominal","legend":{}}""", plain, theme),
      "one layer says explicitly that its legend is not disabled",
    )
  }

  /**
   * Resolved **independently**, there is nothing to merge into: the enabled layer draws its own key
   * and the disabled one draws none.
   */
  @Test
  fun `an independently resolved legend is unaffected`() {
    assertEquals(
      listOf("t"),
      legends(off, plain, ""","resolve":{"legend":{"color":"independent"}}"""),
    )
  }

  /** With neither layer saying anything, the key is drawn as it always was. */
  @Test
  fun `two silent layers keep their legend`() {
    assertEquals(listOf("t"), legends(plain, plain))
  }
}
