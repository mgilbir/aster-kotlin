package dev.aster.vega.model.spec

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `mergeConfig`, against the rules upstream's own has.
 *
 * A configuration merge that is nearly right is a theme that mostly applies, which is worse than
 * one that does not: the failures are per-property and look like bugs in the chart. So these are
 * transcribed from `vega-util`'s `mergeConfig` and `writeConfig` rather than from a reading of what
 * a merge ought to do — including the two recursion exceptions, which are the only reason a `style`
 * block behaves differently from an `axis` block.
 */
class ConfigMergeTest {

  private fun merge(vararg json: String?): VegaValue.Obj? =
    mergeConfig(*json.map { it?.let(VegaJson::parse) }.toTypedArray())

  private fun VegaValue.Obj.at(vararg path: String): VegaValue? {
    var current: VegaValue? = this
    for (step in path) current = (current as? VegaValue.Obj)?.fields?.get(step)
    return current
  }

  @Test
  fun `a later source wins, property by property inside a block`() {
    val merged =
      merge(
        """{"axis": {"labelColor": "#111", "titleColor": "#111"}}""",
        """{"axis": {"labelColor": "#eee"}}""",
      )!!

    assertEquals("#eee", merged.at("axis", "labelColor")?.asString())
    assertEquals("#111", merged.at("axis", "titleColor")?.asString(), "not replaced wholesale")
  }

  @Test
  fun `an object inside a block overwrites, because upstream does not recurse there`() {
    val merged =
      merge(
        """{"axis": {"labelFont": {"family": "serif", "size": 11}}}""",
        """{"axis": {"labelFont": {"family": "sans-serif"}}}""",
      )!!

    // One level, and no further: the later font object replaces the earlier one rather than merging
    // with it, so its `size` is gone. That is upstream's behaviour and a theme has to know it.
    assertEquals("sans-serif", merged.at("axis", "labelFont", "family")?.asString())
    assertNull(merged.at("axis", "labelFont", "size"))
  }

  @Test
  fun `a style block recurses one level further, which is one of upstream's two exceptions`() {
    val merged =
      merge(
        """{"style": {"cell": {"stroke": "#111", "strokeWidth": 1}}}""",
        """{"style": {"cell": {"stroke": "#eee"}, "guide-label": {"fill": "#eee"}}}""",
      )!!

    assertEquals("#eee", merged.at("style", "cell", "stroke")?.asString())
    assertEquals(
      1.0,
      (merged.at("style", "cell", "strokeWidth") as VegaValue.Num).value,
      "the named style merged rather than being replaced",
    )
    assertEquals("#eee", merged.at("style", "guide-label", "fill")?.asString())
  }

  @Test
  fun `a legend block recurses into its layout and nowhere else`() {
    val merged =
      merge(
        """{"legend": {"layout": {"direction": "vertical", "margin": 8}, "symbolType": "circle"}}""",
        """{"legend": {"layout": {"direction": "horizontal"}}}""",
      )!!

    assertEquals("horizontal", merged.at("legend", "layout", "direction")?.asString())
    assertEquals(
      8.0,
      (merged.at("legend", "layout", "margin") as VegaValue.Num).value,
      "`layout` is the other exception upstream makes",
    )
    assertEquals("circle", merged.at("legend", "symbolType")?.asString())
  }

  @Test
  fun `signals merge by name rather than being appended`() {
    val merged =
      merge(
        """{"signals": [{"name": "a", "value": 1}, {"name": "b", "value": 2}]}""",
        """{"signals": [{"name": "b", "value": 20}, {"name": "c", "value": 3}]}""",
      )!!

    val signals = merged.fields["signals"] as VegaValue.Arr
    assertEquals(
      listOf("a", "b", "c"),
      signals.values.map { (it as VegaValue.Obj).fields["name"]?.asString() },
    )
    val b = signals.values.first { (it as VegaValue.Obj).fields["name"]?.asString() == "b" }
    assertEquals(20.0, ((b as VegaValue.Obj).fields["value"] as VegaValue.Num).value)
  }

  @Test
  fun `a scalar replaces, so a background is settled and not combined`() {
    val merged = merge("""{"background": "#fff"}""", """{"background": "#101418"}""")!!
    assertEquals("#101418", merged.fields["background"]?.asString())
  }

  @Test
  fun `nothing in means nothing out`() {
    assertNull(merge(null, null))
    assertNull(mergeConfig())
    // A non-object is not a configuration and is ignored rather than throwing: this is often data
    // somebody else wrote.
    assertNull(mergeConfig(VegaValue.Str("theme")))
  }

  /**
   * The keys upstream refuses at every level.
   *
   * JavaScript's reason is the prototype chain, and Kotlin has no such hole — but the merged object
   * is handed back to a caller that may serialise it into a JavaScript host, so a value this engine
   * let through would arrive there. Refused for that reason rather than by inheritance.
   */
  @Test
  fun `prototype keys are dropped, at the top and inside a block`() {
    val merged =
      merge(
        """{"__proto__": {"polluted": true}, "axis": {"constructor": 1, "labelColor": "#eee"}}"""
      )!!

    assertTrue("__proto__" !in merged.fields, "top-level: ${merged.fields.keys}")
    val axis = merged.fields["axis"] as VegaValue.Obj
    assertTrue("constructor" !in axis.fields, "inside a block: ${axis.fields.keys}")
    assertEquals(
      "#eee",
      axis.fields["labelColor"]?.asString(),
      "and the rest of the block survives",
    )
  }
}
