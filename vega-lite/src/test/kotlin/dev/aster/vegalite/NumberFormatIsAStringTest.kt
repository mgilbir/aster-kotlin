package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A **number** format is taken only where it is a string; a **time** format on its truthiness.
 *
 * ```js
 * export function numberFormat({type, specifiedFormat, config, normalizeStack}) {
 *   // Specified format in axis/legend has higher precedence than fieldDef.format
 *   if (isString(specifiedFormat)) {
 *     return specifiedFormat;
 *   }
 *
 *   if (type === QUANTITATIVE) {
 *     // we only apply the default if the field is quantitative
 *     return normalizeStack ? config.normalizedNumberFormat : config.numberFormat;
 *   }
 *   return undefined;
 * }
 * ```
 *
 * `numberFormat` asks `isString` and falls through to the configured format otherwise, so an axis
 * written `{"format": {"condition": …}}` over a measure has **no format at all** — Vega has no
 * conditional format, and there is nothing else for such an object to mean. `timeFormat` asks only
 * `if (specifiedFormat)`, so the same object written over an instant is passed through as it
 * stands.
 *
 * This engine copied whatever was stated onto the axis, so the object reached Vega, and the theme's
 * own number format never got its turn. One specification in the wild corpus writes a conditional
 * format over a measure.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class NumberFormatIsAStringTest {

  /** Each axis and the format it settled on. */
  private fun formats(encoding: String, config: String = ""): String {
    val spec =
      """{"data":{"values":[{"a":"2020-01-01","b":2}]},"mark":"point",$config
         "encoding":{$encoding}}"""
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    return (compiled.fields["axes"] as VegaValue.Arr).values.joinToString(" | ") {
      it as VegaValue.Obj
      "${it.string("scale")}:" +
        (it.fields["format"]?.let { format ->
          VegaJson.write(format).replace(Regex("""\n\s*"""), "")
        } ?: "-")
    }
  }

  private val conditional =
    """{"value":"quantitative","condition":{"test":"datum.x==1","value":"%"}}"""

  /** The reported shape: a conditional format written over a measure. */
  @Test
  fun `a conditional format over a measure is no format`() {
    assertEquals(
      "x:- | y:- | x:- | y:-",
      formats(
        """"x":{"field":"a","type":"temporal"},
           "y":{"field":"b","type":"quantitative","axis":{"format":$conditional}}"""
      ),
    )
  }

  /** Over an **instant** the same object is passed through, the time format being truthy. */
  @Test
  fun `a conditional format over an instant is written as it stands`() {
    assertEquals(
      """x:- | y:- | x:{"value": "quantitative","condition": {"test": "datum.x==1",""" +
        """"value": "%"}} | y:-""",
      formats(
        """"x":{"field":"a","type":"temporal","axis":{"format":$conditional}},
           "y":{"field":"b","type":"quantitative"}"""
      ),
    )
  }

  /** Over a category there is no format and no default: `numberFormat` answers only a measure. */
  @Test
  fun `a conditional format over a category is no format`() {
    assertEquals(
      "y:- | x:- | y:-",
      formats(
        """"x":{"field":"a","type":"nominal","axis":{"format":$conditional}},
           "y":{"field":"b","type":"quantitative"}"""
      ),
    )
  }

  /** And the theme's own number format takes the place the object was refused. */
  @Test
  fun `the themed number format takes its place`() {
    assertEquals(
      """x:- | y:- | x:- | y:".3f"""",
      formats(
        """"x":{"field":"a","type":"temporal"},
           "y":{"field":"b","type":"quantitative","axis":{"format":$conditional}}""",
        config = """"config":{"numberFormat":".3f"},""",
      ),
    )
  }

  /** A stated **string** is the format, which is the whole of what must not change. */
  @Test
  fun `a stated string is the format`() {
    assertEquals(
      """x:- | y:- | x:- | y:".2f"""",
      formats(
        """"x":{"field":"a","type":"temporal"},
           "y":{"field":"b","type":"quantitative","axis":{"format":".2f"}}"""
      ),
    )
  }
}
