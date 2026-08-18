package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A `config` block the **host** supplies, which is how an app themes a chart it did not write.
 *
 * The case is concrete and comes from an adopter: a server produces the specification and
 * hard-codes the colours it chose for a white page — `scale.scheme: "tableau10"`, a white point
 * overlay — and the app draws it on a dark card. `Config` is internal and was built from the
 * specification alone, so there was no way in at all; a host had to rewrite the payload to say
 * anything about colour.
 *
 * The merge is `vega-util`'s `mergeConfig`, and the specification is the later and therefore
 * winning source: a theme is a **default** and a stated value overrides it. Which is also why the
 * tests below are as much about what a host *cannot* do — two of Vega-Lite's own precedence rules
 * are not negotiable, and knowing that is what stops somebody debugging their theme for an
 * afternoon.
 */
class HostConfigTest {

  private val chart =
    """
    {
      "data": {"values": [{"a": 1, "b": 2}]},
      "mark": "point",
      "encoding": {
        "x": {"field": "a", "type": "quantitative"},
        "y": {"field": "b", "type": "quantitative"}
      }
    }
    """
      .trimIndent()

  private fun compile(spec: String, host: String? = null): VegaValue.Obj {
    val hostConfig = host?.let { VegaJson.parse(it) }
    val compiled = VegaLiteCompiler(hostConfig).compile(VegaJson.parse(spec))
    return VegaJson.parse(requireNotNull(compiled.toJson()) { "no output" }) as VegaValue.Obj
  }

  private fun VegaValue.Obj.at(vararg path: String): VegaValue? {
    var current: VegaValue? = this
    for (step in path) current = (current as? VegaValue.Obj)?.fields?.get(step)
    return current
  }

  @Test
  fun `a host's configuration reaches the compiled chart`() {
    val vega = compile(chart, """{"axis": {"labelColor": "#e6e6e6", "domainColor": "#666"}}""")

    assertEquals("#e6e6e6", vega.at("config", "axis", "labelColor")?.asString())
    assertEquals("#666", vega.at("config", "axis", "domainColor")?.asString())
  }

  @Test
  fun `the specification's own configuration wins, property by property`() {
    val themed =
      chart.replace(
        """"mark": "point",""",
        """"mark": "point", "config": {"axis": {"labelColor": "#111"}},""",
      )
    val vega = compile(themed, """{"axis": {"labelColor": "#e6e6e6", "titleColor": "#e6e6e6"}}""")

    // The specification named the label colour, so it keeps it; it said nothing about the title
    // colour, so the host's stands. A merge that replaced the whole block would have lost one of
    // the
    // two, which is the difference between a theme and a switch.
    assertEquals("#111", vega.at("config", "axis", "labelColor")?.asString())
    assertEquals("#e6e6e6", vega.at("config", "axis", "titleColor")?.asString())
  }

  @Test
  fun `a host can set the surface a chart is drawn on`() {
    val vega = compile(chart, """{"background": "#101418", "view": {"stroke": null}}""")

    // `background` is lifted to a top-level Vega property, and `view` becomes the `cell` style —
    // both
    // of which a dark chart needs and neither of which a specification usually mentions.
    assertEquals("#101418", vega.fields["background"]?.asString())
    assertEquals(
      VegaValue.Null,
      (vega.at("config", "style", "cell") as? VegaValue.Obj)?.fields?.get("stroke"),
      "an explicit null is how the plotting area's light grey outline is turned off",
    )
  }

  @Test
  fun `a named style merges rather than replacing, as upstream's own recursion says`() {
    val themed =
      chart.replace(
        """"mark": "point",""",
        """"mark": "point", "config": {"style": {"cell": {"stroke": "#333"}}},""",
      )
    val vega = compile(themed, """{"style": {"cell": {"strokeWidth": 2, "stroke": "#eee"}}}""")

    val cell = vega.at("config", "style", "cell") as VegaValue.Obj
    assertEquals("#333", cell.fields["stroke"]?.asString(), "the specification's own colour")
    assertEquals(
      2.0,
      (cell.fields["strokeWidth"] as VegaValue.Num).value,
      "and the host's width beside it — `style` is one of the two blocks upstream recurses into",
    )
  }

  /**
   * The honest half, and the review that asked for this seam had already found it.
   *
   * A mark's own encoded property beats every configuration block. A specification writing
   * `mark.point.fill` therefore keeps its white point on a dark card whatever a host supplies,
   * because `Normalize.pointOverlay` uses that `point` object **verbatim** as the overlay mark's
   * definition. A host that has to change that is rewriting the specification — and can inject its
   * `config` in the same pass, which is why this seam is still worth having.
   */
  @Test
  fun `a mark's own property is not something a host configuration can reach`() {
    val withPoint =
      chart.replace(
        """"mark": "point",""",
        """"mark": {"type": "line", "point": {"fill": "white", "filled": false}},""",
      )
    val vega = compile(withPoint, """{"point": {"fill": "#7aa2f7"}, "mark": {"fill": "#7aa2f7"}}""")

    // The overlay mark carries the specification's white, from its own mark definition.
    val marks = vega.fields["marks"] as VegaValue.Arr
    val fills =
      marks.values.mapNotNull { mark ->
        ((mark as? VegaValue.Obj)?.at("encode", "update", "fill") as? VegaValue.Obj)
          ?.fields
          ?.get("value")
          ?.asString()
      }
    assertEquals(
      listOf("white"),
      fills,
      "if this ever reads #7aa2f7, a host configuration has started beating a mark's own property " +
        "and the note in VegaLiteCompiler.hostConfig is wrong",
    )
  }

  @Test
  fun `no host configuration leaves a chart exactly as it was`() {
    assertEquals(compile(chart), compile(chart, null))
  }
}
