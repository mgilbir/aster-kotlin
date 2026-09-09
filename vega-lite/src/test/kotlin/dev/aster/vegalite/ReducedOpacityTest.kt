package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A scatter is drawn at seven tenths opacity so overlaps read — unless the mark has already said
 * how solid it is.
 *
 * `initMarkDef` asks for **both** opacities before applying the default, and either one answering
 * is enough:
 * ```js
 * const specifiedOpacity = getMarkPropOrConfig('opacity', markDef, config);
 * const specifiedFillOpacity = getMarkPropOrConfig('fillOpacity', markDef, config);
 * if (specifiedOpacity === undefined && specifiedFillOpacity === undefined) {
 *   markDef.opacity = opacity(markDef.type, encoding);
 * }
 * ```
 *
 * A mark that states its `fillOpacity` has answered the question the default was going to answer,
 * and applying both would have drawn it at `0.9 * 0.7`. This looked only at `opacity`, and only on
 * the mark itself — so eight of the wild corpus's scatters came out fainter than upstream draws
 * them.
 *
 * Both are read through the mark, its style blocks and the configuration alike,
 * `getMarkPropOrConfig` having no `vgChannel` here to skip the style with. `strokeOpacity` is
 * **not** one of them: it says nothing about the fill, and upstream leaves the default standing.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class ReducedOpacityTest {

  private fun update(mark: String, config: String = "", extra: String = ""): VegaValue.Obj {
    val spec =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"a":1,"b":2,"c":"x"}]},"mark":$mark,$config
               "encoding":{"x":{"field":"a","type":"quantitative"},
                           "y":{"field":"b","type":"quantitative"}$extra}}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    val marks = (spec.fields["marks"] as? VegaValue.Arr)?.values.orEmpty()
    val symbol =
      marks
        .mapNotNull { it as? VegaValue.Obj }
        .first {
          (it.fields["type"] as? VegaValue.Str)?.value == "symbol"
        }
    return (symbol.fields["encode"] as? VegaValue.Obj)?.fields?.get("update") as? VegaValue.Obj
      ?: VegaValue.EmptyObject
  }

  private fun opacity(update: VegaValue.Obj) =
    (update.fields["opacity"] as? VegaValue.Obj)?.fields?.get("value")

  private val reduced = VegaValue.Num(0.7)

  /** A plain scatter takes the reduced opacity, which is the whole point of the rule. */
  @Test
  fun `a plain scatter is drawn at seven tenths`() {
    assertEquals(reduced, opacity(update(""""circle"""")))
  }

  /** The reported shape: a mark that states how solid its fill is. */
  @Test
  fun `a stated fill opacity suppresses it`() {
    val update = update("""{"type":"circle","fillOpacity":0.9}""")
    assertNull(opacity(update), "the mark has already said, and 0.9 * 0.7 is neither")
    assertEquals(
      VegaValue.Num(0.9),
      (update.fields["fillOpacity"] as? VegaValue.Obj)?.fields?.get("value"),
    )
  }

  /** A stated `opacity` suppresses the default and stands in its place, as it always did. */
  @Test
  fun `a stated opacity is used as it stands`() {
    assertEquals(VegaValue.Num(0.5), opacity(update("""{"type":"circle","opacity":0.5}""")))
  }

  /** The theme's fill opacity counts, whichever of the three blocks it is written in. */
  @Test
  fun `a fill opacity from the configuration suppresses it`() {
    assertNull(opacity(update(""""circle"""", """"config":{"circle":{"fillOpacity":0.9}},""")))
    assertNull(opacity(update(""""circle"""", """"config":{"mark":{"fillOpacity":0.9}},""")))
    assertNull(
      opacity(
        update(
          """{"type":"circle","style":"s"}""",
          """"config":{"style":{"s":{"fillOpacity":0.9}}},""",
        )
      ),
      "a style block counts here: there is no Vega channel name to skip it by",
    )
  }

  /** And the theme's plain opacity, which Vega reads from its own config rather than the mark. */
  @Test
  fun `an opacity from the configuration suppresses it without being written out`() {
    assertNull(opacity(update(""""circle"""", """"config":{"circle":{"opacity":0.4}},""")))
  }

  /** `strokeOpacity` is not one of the two: it says nothing about the fill. */
  @Test
  fun `a stated stroke opacity leaves it standing`() {
    assertEquals(reduced, opacity(update("""{"type":"circle","strokeOpacity":0.9}""")))
  }

  /** An `opacity` encoding replaces it with a scaled one, so there is no constant to write. */
  @Test
  fun `an opacity encoding suppresses it`() {
    val update = update(""""circle"""", extra = ""","opacity":{"field":"c","type":"nominal"}""")
    assertNull(opacity(update))
    assertEquals(
      VegaValue.Str("opacity"),
      (update.fields["opacity"] as? VegaValue.Obj)?.fields?.get("scale"),
    )
  }

  /** An aggregated plot has no overlaps to see through, and never took the reduction. */
  @Test
  fun `an aggregated plot does not take it`() {
    val update =
      update(
        """"circle"""",
        extra = "",
      )
    assertEquals(reduced, opacity(update), "the unaggregated case, for contrast")
    val aggregated =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"a":1,"b":2,"c":"x"}]},"mark":"circle",
               "encoding":{"x":{"field":"c","type":"nominal"},
                           "y":{"field":"b","type":"quantitative","aggregate":"sum"}}}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    val symbol =
      (aggregated.fields["marks"] as VegaValue.Arr)
        .values
        .mapNotNull { it as? VegaValue.Obj }
        .first { (it.fields["type"] as? VegaValue.Str)?.value == "symbol" }
    val encode = (symbol.fields["encode"] as VegaValue.Obj).fields["update"] as VegaValue.Obj
    assertNull(encode.fields["opacity"])
  }
}
