package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A legend that states the colour of its swatches takes the mark's paint off them.
 *
 * ```js
 * } else if (hasProperty(out.fill, 'field')) {
 *   // For others, set fill to some opaque value (or nothing if a color is already set)
 *   if (symbolFillColor) {
 *     delete out.fill;
 *   } else {
 *     out.fill = signalOrValueRef(config.legend.symbolBaseFillColor ?? 'black');
 *     out.fillOpacity = signalOrValueRef(opacity ?? 1);
 *   }
 * }
 * ```
 *
 * A swatch cannot resolve a *scaled* paint — a size legend's swatches are all one colour, since
 * size is what they are showing — so it is drawn in a base colour at the mark's opacity. Unless the
 * legend named a colour for them, and then it is drawn in that: the base colour this compiler wrote
 * would be painted over by the legend's own, and the opacity beside it applied twice.
 *
 * Three specifications in the wild corpus state one, each a size legend beside a colour legend.
 *
 * The same is true of the outline, where a stated `symbolStrokeColor` takes the mark's off — as a
 * *scaled* outline does, the swatch having no way to resolve one.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class SwatchColourTest {

  /** Each legend as the channel it explains and what it paints its swatches with. */
  private fun swatches(legend: String, config: String = ""): List<String> {
    val spec =
      """{"data":{"values":[{"a":1,"b":2,"c":"x"}]},$config"mark":"circle",
         "encoding":{"x":{"field":"a","type":"quantitative"},
                     "color":{"field":"c","type":"nominal"},
                     "size":{"field":"b","type":"quantitative"$legend}}}"""
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    return (compiled.fields["legends"] as VegaValue.Arr)
      .values
      .map { it as VegaValue.Obj }
      .map {
        val explains = listOf("fill", "size", "stroke", "opacity").firstNotNullOfOrNull(it::string)
        val update =
          ((it.fields["encode"] as? VegaValue.Obj)?.fields?.get("symbols") as? VegaValue.Obj)
            ?.fields
            ?.get("update")
        "$explains|${VegaJson.write(update ?: VegaValue.Null).replace(Regex("""\n\s*"""), "")}"
      }
  }

  /** The reported shape: a size legend that paints its own swatches. */
  @Test
  fun `a stated swatch colour takes the mark's paint off`() {
    assertEquals(
      listOf("""color|{"opacity": {"value": 0.7}}""", """size|{"opacity": {"value": 0.7}}"""),
      swatches(""","legend":{"symbolFillColor":"darkred","symbolStrokeColor":"darkred"}"""),
    )
  }

  /** The fill alone is enough: with no fill to outline, the transparent outline goes too. */
  @Test
  fun `a stated swatch fill is enough`() {
    assertEquals(
      listOf("""color|{"opacity": {"value": 0.7}}""", """size|{"opacity": {"value": 0.7}}"""),
      swatches(""","legend":{"symbolFillColor":"darkred"}"""),
    )
  }

  /** With nothing stated, the swatch is the base colour at the mark's opacity. */
  @Test
  fun `an unstated swatch is painted in the base colour`() {
    assertEquals(
      listOf(
        """color|{"opacity": {"value": 0.7}}""",
        """size|{"fill": {"value": "black"},"fillOpacity": {"value": 0.7},""" +
          """"opacity": {"value": 0.7},"stroke": {"value": "transparent"}}""",
      ),
      swatches(""),
    )
  }

  /** A theme may state it for every legend at once. */
  @Test
  fun `a themed swatch colour takes the mark's paint off too`() {
    assertEquals(
      listOf("""color|{"opacity": {"value": 0.7}}""", """size|{"opacity": {"value": 0.7}}"""),
      swatches("", config = """"config":{"legend":{"symbolFillColor":"darkred"}},"""),
    )
  }
}
