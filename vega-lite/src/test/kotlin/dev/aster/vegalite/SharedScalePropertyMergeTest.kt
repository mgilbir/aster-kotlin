package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A scale property one layer **stated** settles it for the shared scale, whichever layer states it.
 *
 * `parseNonUnitScaleProperty` folds a shared scale property by property with
 * `mergeValuesWithExplicit`, and an explicit value beats a derived one. Between two of the same
 * kind the first still wins, which is what makes a candlestick's rules and bars agree on a padding
 * only one of them mentions.
 *
 * This took the first layer's answer for everything. A colour range listed on the **second** member
 * of a layer therefore lost to the first member's default scheme, and the chart was drawn in
 * category colours the specification had replaced. Three specifications in the wild corpus write
 * their palette that way — on the layer that needs it rather than on the first one.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class SharedScalePropertyMergeTest {

  private fun colourRange(vararg colours: String): VegaValue? {
    val layers =
      colours.joinToString(",") { colour ->
        """{"mark":"line","encoding":{"x":{"field":"a","type":"quantitative"},
           "y":{"field":"b","type":"quantitative"},"color":$colour}}"""
      }
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson("""{"data":{"values":[{"a":1,"b":2,"c":"x"}]},"layer":[$layers]}""")
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    return (compiled.fields["scales"] as VegaValue.Arr)
      .values
      .mapNotNull { it as? VegaValue.Obj }
      .first { (it.fields["name"] as? VegaValue.Str)?.value == "color" }
      .fields["range"]
  }

  private val bare = """{"datum":"one"}"""
  private val ranged = """{"datum":"two","scale":{"range":["red","blue"]}}"""
  private val redBlue = VegaValue.Arr(listOf(VegaValue.Str("red"), VegaValue.Str("blue")))

  /** The reported shape: the palette is on the second layer. */
  @Test
  fun `a range stated on the second layer settles the scale`() {
    assertEquals(redBlue, colourRange(bare, ranged))
  }

  /** And on the first, which worked before by accident of ordering. */
  @Test
  fun `a range stated on the first layer settles it too`() {
    assertEquals(redBlue, colourRange(ranged, bare))
  }

  /** A `scheme` is the same property said another way, and is explicit for the same reason. */
  @Test
  fun `a scheme stated on the second layer settles the scale`() {
    assertEquals(
      VegaValue.Obj(linkedMapOf("scheme" to VegaValue.Str("viridis"))),
      colourRange(bare, """{"datum":"two","scale":{"scheme":"viridis"}}"""),
    )
  }

  /** Between two layers that both state one, the first wins — the tie-breaker is unchanged. */
  @Test
  fun `two stated ranges leave the first standing`() {
    assertEquals(
      VegaValue.Arr(listOf(VegaValue.Str("green"))),
      colourRange("""{"datum":"one","scale":{"range":["green"]}}""", ranged),
    )
  }

  /** With neither stating one, the derived scheme stands as it always did. */
  @Test
  fun `with nothing stated the default palette stands`() {
    assertEquals(VegaValue.Str("category"), colourRange(bare, """{"datum":"two"}"""))
  }
}
