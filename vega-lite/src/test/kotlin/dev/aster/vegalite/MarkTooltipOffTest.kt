package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A mark that switches its tooltip off says so, and stops being reachable.
 *
 * ```js
 * function markDefProperties(mark: MarkDef, ignore: Ignore) {
 *   return VG_MARK_CONFIGS.reduce((m, prop) => {
 *     if (!ALWAYS_IGNORE.has(prop) && hasProperty(mark, prop) && (ignore as any)[prop] !== 'ignore') {
 *       m[prop] = signalOrValueRef(mark[prop]);
 *     }
 * ```
 *
 * `tooltip` is one of Vega's own mark properties, so whatever the mark definition states is written
 * out as a value — `null` and `false` included — and the tooltip *encoder* that runs afterwards
 * overwrites it wherever it has something to say. This engine took the property off that pass
 * entirely and left the encoder to answer alone, so a mark written `{"tooltip": null}` came out
 * with no tooltip entry at all.
 *
 * ```js
 * interactive: unitCount > 0 || model.mark === 'geoshape' || !!model.encoding.tooltip || !!model.markDef.tooltip,
 * ```
 *
 * And the **truthiness** of it is what makes the mark reachable. Asking whether the property was
 * stated made a mark that switched its tooltip off the one mark in a layer that swallowed the click
 * — upstream leaves it interactive `false`, so the click falls through to the layer whose selection
 * it belongs to.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class MarkTooltipOffTest {

  /** Whether the second layer's mark is reachable, and what its tooltip is. */
  private fun member(mark: String, encoding: String = ""): String {
    val position =
      """"x":{"field":"a","type":"quantitative"},"y":{"field":"b","type":"quantitative"}"""
    val spec =
      """{"data":{"values":[{"a":1,"b":2}]},"encoding":{$position},
         "layer":[
           {"mark":"area","params":[{"name":"pick","select":{"type":"interval",
             "encodings":["x"]}}]},
           {"mark":$mark${if (encoding.isEmpty()) "" else ""","encoding":{$position$encoding}"""}}]}"""
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    val mark1 =
      (compiled.fields["marks"] as VegaValue.Arr)
        .values
        .map { it as VegaValue.Obj }
        .first {
          it.string("name") == "layer_1_marks"
        }
    val tooltip = mark1.obj("encode")?.obj("update")?.fields?.get("tooltip")
    return "interactive=${mark1.fields["interactive"]?.let { VegaJson.write(it).trim() } ?: "absent"} " +
      "tooltip=${tooltip?.let { VegaJson.write(it).replace(Regex("""\n\s*"""), "") } ?: "absent"}"
  }

  /** The reported shape: a mark whose tooltip is switched off with a null. */
  @Test
  fun `a null tooltip is written and takes the mark out of reach`() {
    assertEquals(
      """interactive=false tooltip={"value": null}""",
      member("""{"type":"rect","tooltip":null}"""),
    )
  }

  /** A `false` is as untrue as a null, and written just as plainly. */
  @Test
  fun `a false tooltip is written too`() {
    assertEquals(
      """interactive=false tooltip={"value": false}""",
      member("""{"type":"rect","tooltip":false}"""),
    )
  }

  /** A mark that asks for the whole encoding is reachable, and the encoder answers. */
  @Test
  fun `a tooltip of true is the encoding's own`() {
    assertEquals(
      """interactive=true tooltip={"signal": "{\"a\": format(datum[\"a\"], \"\"), """ +
        """\"b\": format(datum[\"b\"], \"\")}"}""",
      member("""{"type":"rect","tooltip":true}"""),
    )
  }

  /** A stated string is that string, and the mark is reachable. */
  @Test
  fun `a tooltip of a string is that string`() {
    assertEquals(
      """interactive=true tooltip={"value": "hello"}""",
      member("""{"type":"rect","tooltip":"hello"}"""),
    )
  }

  /** A mark that says nothing about a tooltip gets no entry, and is out of reach. */
  @Test
  fun `a mark with no tooltip has none`() {
    assertEquals("interactive=false tooltip=absent", member("""{"type":"rect"}"""))
  }

  /** An **encoded** tooltip makes the mark reachable, as it always did. */
  @Test
  fun `an encoded tooltip is the encoder's answer`() {
    assertEquals(
      """interactive=true tooltip={"signal": "format(datum[\"a\"], \"\")"}""",
      member("""{"type":"rect"}""", ""","tooltip":{"field":"a","type":"quantitative"}"""),
    )
  }

  /** And an encoding that switches it off leaves the mark out of reach with no entry. */
  @Test
  fun `an encoded null tooltip is no tooltip`() {
    assertEquals(
      "interactive=false tooltip=absent",
      member("""{"type":"rect"}""", ""","tooltip":null"""),
    )
  }
}
