package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A layer member's channel **replaces** the chart's unless it names something to measure.
 *
 * ```js
 * if (isFieldOrDatumDef(channelDef)) {
 *   // Field/Datum Def can inherit properties from its parent
 *   const mergedChannelDef = {...parentChannelDef, ...channelDef};
 *   merged[channel] = mergedChannelDef;
 * } else if (hasConditionalFieldOrDatumDef(channelDef)) {
 *   merged[channel] = {...channelDef, condition: {...parentChannelDef, ...channelDef.condition}};
 * } else if (channelDef || channelDef === null) {
 *   merged[channel] = channelDef;
 * }
 * ```
 *
 * `mergeEncoding` spreads the chart's definition under the member's **only** where the member's is
 * a field or datum def — that is what lets a shared `x` state the type and a member's `x` name only
 * the column. Everything else takes the channel over outright.
 *
 * This compiler spread any two objects together, so a member drawing its label at the corner of the
 * plot — `{"value": "width"}` for its `x` — came out still measuring a column: placed against a
 * scale it had said it did not want, filtered for the rows that column had no value in, described
 * by a field it does not show, and contributing to a colour domain it takes no part in. Four
 * specifications in the wild corpus write a layer that way.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class LayerEncodingMergeTest {

  /** The second member's `x`, and every scale the chart ended up with. */
  private fun member(x: String): String {
    val spec =
      """{"data":{"values":[{"a":"p","b":2,"c":3}]},
         "encoding":{"x":{"field":"a","type":"nominal"},
                     "y":{"field":"b","type":"quantitative"},
                     "color":{"field":"a","type":"nominal","scale":{"scheme":"plasma"}}},
         "layer":[{"mark":"point"},{"mark":"rule","encoding":{"x":$x}}]}"""
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    fun written(value: VegaValue?) =
      value?.let { VegaJson.write(it).replace(Regex("""\n\s*"""), "") } ?: "-"
    val update = (compiled.fields["marks"] as VegaValue.Arr).values[1] as VegaValue.Obj
    val encode = update.obj("encode")!!.obj("update")!!
    val scales =
      (compiled.fields["scales"] as VegaValue.Arr).values.joinToString(",") {
        it as VegaValue.Obj
        "${it.string("name")}:${it.string("type") ?: "linear"}"
      }
    return "x=${written(encode.fields["x"])} x2=${written(encode.fields["x2"])} [$scales]"
  }

  /** The reported shape: a member placing its mark at the edge of the plot. */
  @Test
  fun `a stated value takes the channel over`() {
    assertEquals(
      """x={"field": {"group": "width"}} x2=- [x:point,y:linear,color:ordinal]""",
      member("""{"value":"width"}"""),
    )
  }

  /** An **empty** definition takes it over too, which is how a member says it has no `x`. */
  @Test
  fun `an empty definition takes the channel over`() {
    assertEquals(
      """x={"field": {"group": "width"}} x2={"value": 0} [x:point,y:linear,color:ordinal]""",
      member("{}"),
    )
  }

  /** And so does one that states only how to draw the axis, naming nothing to draw. */
  @Test
  fun `a definition naming no column takes the channel over`() {
    assertEquals(
      """x={"field": {"group": "width"}} x2={"value": 0} [x:point,y:linear,color:ordinal]""",
      member("""{"axis":{"labelAngle":0}}"""),
    )
  }

  /**
   * A definition that **does** name a column inherits: the chart's `type` reaches it, which is why
   * the scale is a band and not the linear one a typeless column would have taken.
   */
  @Test
  fun `a column inherits the chart's own type`() {
    assertEquals(
      """x={"scale": "x","field": "c","band": 0.5} x2=- [x:band,y:linear,color:ordinal]""",
      member("""{"field":"c"}"""),
    )
  }

  /**
   * A **datum** is a literal standing where a column would, and inherits the same way: the band
   * scale is the chart's own `nominal` reaching the member, a typeless literal having taken a
   * linear one.
   *
   * Only the scale is asserted here. Upstream also writes `"band": 0.5` on a literal placed against
   * a band scale, which this compiler does not — a gap of its own, and not this rule's.
   */
  @Test
  fun `a datum inherits the chart's own type`() {
    assertEquals(
      "[x:band,y:linear,color:ordinal]",
      member("""{"datum":5}""").substringAfter(" [").let { "[$it" },
    )
  }

  /** A **condition** that names a column inherits into the condition, that being the half of it. */
  @Test
  fun `a conditional column inherits into its condition`() {
    assertEquals(
      """x={"value": 5} x2=- [x:band,y:linear,color:ordinal]""",
      member("""{"condition":{"test":"true","field":"c"},"value":5}"""),
    )
  }

  /**
   * And what it inherits is the chart's own `type`: a measure conditioned onto colour comes out on
   * the **ordinal** scale the chart's category asked for, its column joining that domain, rather
   * than on a ramp of its own.
   */
  @Test
  fun `a conditional colour inherits the chart's own type`() {
    val spec =
      """{"data":{"values":[{"a":"p","b":2,"c":3}]},
         "encoding":{"x":{"field":"a","type":"nominal"},
                     "y":{"field":"b","type":"quantitative"},
                     "color":{"field":"a","type":"nominal"}},
         "layer":[{"mark":"point"},
                  {"mark":"bar","encoding":{
                     "color":{"condition":{"test":"true","field":"b"},"value":"red"}}}]}"""
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    val colour =
      (compiled.fields["scales"] as VegaValue.Arr)
        .values
        .map { it as VegaValue.Obj }
        .first {
          it.string("name") == "color"
        }
    assertEquals(
      """{"name": "color","type": "ordinal","domain": {"fields": [{"data": "data_1",""" +
        """"field": "a"},{"data": "data_2","field": "b"}],"sort": true},"range": "category"}""",
      VegaJson.write(colour).replace(Regex("""\n\s*"""), ""),
    )
  }

  /** What the chart says about the **scale** reaches the condition too, and not the channel. */
  @Test
  fun `a conditional colour inherits the chart's own scale`() {
    val spec =
      """{"data":{"values":[{"a":"p","b":2}]},
         "encoding":{"x":{"field":"a","type":"nominal"},
                     "color":{"field":"a","type":"nominal","scale":{"scheme":"sinebow"}}},
         "layer":[{"mark":"point","encoding":{"color":{"value":"#333"}}},
                  {"mark":"bar","encoding":{
                     "color":{"condition":{"test":"true","field":"b"},"value":"red"}}}]}"""
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    val colour =
      (compiled.fields["scales"] as VegaValue.Arr)
        .values
        .map { it as VegaValue.Obj }
        .first {
          it.string("name") == "color"
        }
    assertEquals(
      """{"name": "color","type": "ordinal","domain": {"data": "source_0","field": "b",""" +
        """"sort": true},"range": {"scheme": "sinebow"}}""",
      VegaJson.write(colour).replace(Regex("""\n\s*"""), ""),
    )
  }

  /**
   * A condition that names only a **value** is not such a channel: nothing of the chart's reaches
   * it, and the member takes no part in the scale — the chart is left with no colour scale at all.
   */
  @Test
  fun `a conditional value inherits nothing`() {
    val spec =
      """{"data":{"values":[{"a":"p","b":2}]},
         "encoding":{"x":{"field":"a","type":"nominal"},
                     "color":{"field":"a","type":"nominal","scale":{"scheme":"sinebow"}}},
         "layer":[{"mark":"point","encoding":{"color":{"value":"#333"}}},
                  {"mark":"bar","encoding":{
                     "color":{"condition":{"test":"true","value":"red"},"value":"blue"}}}]}"""
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    assertEquals(
      "x",
      (compiled.fields["scales"] as VegaValue.Arr).values.joinToString(",") {
        (it as VegaValue.Obj).string("name").orEmpty()
      },
    )
  }

  /**
   * A **count** names no column of its own — it counts rows — and `isFieldDef` asks for it by name
   * for that reason. It inherits like any other: the chart's axis reaches the member's own scale.
   */
  @Test
  fun `a count inherits what the chart says about the channel`() {
    val spec =
      """{"data":{"values":[{"a":"p","b":2,"c":3}]},
         "encoding":{"x":{"field":"a","type":"nominal"},
                     "y":{"field":"b","type":"quantitative"},
                     "size":{"field":"b","type":"quantitative","scale":{"range":[100,400]}}},
         "layer":[{"mark":"point","encoding":{"size":{"value":30}}},
                  {"mark":"point","encoding":{"size":{"aggregate":"count"}}}]}"""
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    val size =
      (compiled.fields["scales"] as VegaValue.Arr)
        .values
        .map { it as VegaValue.Obj }
        .first {
          it.string("name") == "size"
        }
    assertEquals(
      """{"name": "size","type": "linear","domain": {"data": "data_2","field": "__count"},""" +
        """"range": [100,400],"zero": true}""",
      VegaJson.write(size).replace(Regex("""\n\s*"""), ""),
    )
  }

  /**
   * A member that takes the colour over with a value of its own takes no part in the colour scale:
   * only the other member's column is in its domain, and the scheme it asked for is the one drawn.
   */
  @Test
  fun `a member that states a colour takes no part in the colour scale`() {
    val spec =
      """{"data":{"values":[{"a":"p","b":2,"c":3}]},
         "encoding":{"x":{"field":"a","type":"nominal"},
                     "y":{"field":"b","type":"quantitative"},
                     "color":{"field":"a","type":"nominal","scale":{"scheme":"plasma"}}},
         "layer":[{"mark":"point","encoding":{"color":{"value":"#333"}}},
                  {"mark":"bar","encoding":{
                     "color":{"field":"a","type":"nominal","scale":{"scheme":"sinebow"}}}}]}"""
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    val colour =
      (compiled.fields["scales"] as VegaValue.Arr)
        .values
        .map { it as VegaValue.Obj }
        .first {
          it.string("name") == "color"
        }
    assertEquals(
      """{"name": "color","type": "ordinal","domain": {"data": "data_2","field": "a",""" +
        """"sort": true},"range": {"scheme": "sinebow"}}""",
      VegaJson.write(colour).replace(Regex("""\n\s*"""), ""),
    )
  }
}
