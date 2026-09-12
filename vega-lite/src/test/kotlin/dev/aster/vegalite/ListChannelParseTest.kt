package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Every entry of a list channel is a field definition, and each is parsed.
 *
 * `getImplicitFromEncoding` walks the encoding with `model.forEachFieldDef`, which iterates the
 * mapping entry by entry — a `tooltip` naming four columns is four definitions, not one — and
 * `getFieldDef` reaches into a `condition` for its field. Each definition it finds decides a parse:
 * `date` for an instant, `number` for a `min` or `max`, and `flatten` for a field named through a
 * path, which is read out into a flat column of its own because no row has a key with a dot in it.
 *
 * This read only the channel's own definition, so a tooltip's **second** nested field was never
 * flattened and Vega looked for it under a name no row has — leaving the tooltip's second line
 * empty. Two specifications in the wild corpus disagreed for exactly that reason, both of them
 * tooltips over nested JSON.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class ListChannelParseTest {

  private fun flattened(encoding: String): List<String> {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson("""{"data":{"values":[{}]},"mark":"point","encoding":$encoding}""")
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    return (compiled.fields["data"] as VegaValue.Arr)
      .values
      .mapNotNull { it as? VegaValue.Obj }
      .flatMap { (it.fields["transform"] as? VegaValue.Arr)?.values.orEmpty() }
      .mapNotNull { it as? VegaValue.Obj }
      .filter { (it.fields["type"] as? VegaValue.Str)?.value == "formula" }
      .mapNotNull { (it.fields["as"] as? VegaValue.Str)?.value }
  }

  private val position =
    """"x":{"field":"a","type":"nominal"},"y":{"field":"b","type":"quantitative"}"""

  /** A nested field on an ordinary channel was always flattened. */
  @Test
  fun `a nested field on a position channel is flattened`() {
    assertEquals(
      listOf("v.c"),
      flattened(
        """{"x":{"field":"v.c","type":"nominal"},"y":{"field":"b","type":"quantitative"}}"""
      ),
    )
  }

  /** And one written as the whole tooltip, which is a single definition. */
  @Test
  fun `a nested field in a lone tooltip is flattened`() {
    assertEquals(
      listOf("v.c"),
      flattened("""{$position,"tooltip":{"field":"v.c","type":"nominal"}}"""),
    )
  }

  /** The reported shape: a tooltip of several columns, every one of them a definition. */
  @Test
  fun `every nested field in a tooltip list is flattened`() {
    assertEquals(
      listOf("v.c", "w.d"),
      flattened(
        """{$position,"tooltip":[{"field":"v.c","type":"nominal"},
                                 {"field":"w.d","type":"nominal"}]}"""
      ),
      "the second column was the one left out",
    )
  }

  /** `detail` is a list channel too, and takes the same walk. */
  @Test
  fun `every nested field in a detail list is flattened`() {
    assertEquals(
      listOf("v.c", "w.d"),
      flattened(
        """{$position,"detail":[{"field":"v.c","type":"nominal"},
                                {"field":"w.d","type":"nominal"}]}"""
      ),
    )
  }

  /** And a field that exists only inside a condition is reached, `getFieldDef` looking there. */
  @Test
  fun `a nested field in a condition is flattened`() {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{}]},"mark":"point","params":[{"name":"p","select":"point"}],
               "encoding":{$position,
                 "color":{"condition":{"param":"p","field":"v.c","type":"nominal"},"value":"red"}}}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    val names =
      (compiled.fields["data"] as VegaValue.Arr)
        .values
        .mapNotNull { it as? VegaValue.Obj }
        .flatMap { (it.fields["transform"] as? VegaValue.Arr)?.values.orEmpty() }
        .mapNotNull { it as? VegaValue.Obj }
        .filter { (it.fields["type"] as? VegaValue.Str)?.value == "formula" }
        .mapNotNull { (it.fields["as"] as? VegaValue.Str)?.value }
    assertEquals(listOf("v.c"), names)
  }
}
