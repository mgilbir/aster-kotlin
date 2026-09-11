package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A parameter belongs to the mark that was **written**, not to what it expands into.
 *
 * ```js
 * const {params, projection, mark, name, encoding: e, ...outerSpec} = spec;
 * ...
 * const layer: NormalizedUnitSpec[] = [
 *   {
 *     name,
 *     ...(params ? {params} : {}),
 * ```
 *
 * A line that draws its own points is two marks, and the parameters go on the **first** of them
 * alone: they were declared on the mark the specification wrote, and the overlay is something the
 * normalizer added. This engine handed them to every member, so one declaration was claimed twice —
 * invisible until a second plot declares the same name, because that plot then finds its own
 * declaration already taken and reacts to nothing at all. Two specifications in the wild corpus are
 * a concatenation whose two plots both declare a `countrysel`.
 *
 * ```js
 * const {mark, encoding: _encoding, params, projection: _p, ...outerSpec} = spec;
 * ...
 * // TODO(https://github.com/vega/vega-lite/issues/3702): add selection support
 * if (params) {
 *   log.warn(log.message.selectionNotSupported('boxplot'));
 * }
 * ```
 *
 * A **composite** mark takes none at all: its normalizer lifts the parameters off the specification
 * and does nothing with them, so the summary is drawn and nothing reacts. Upstream says so in a
 * warning and has an issue open about it — what a click on one of the five marks a box plot draws
 * would pick is the question it has not answered — and a compiler that built the parameter anyway
 * draws a chart that reacts where upstream's does not.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class ExpandedMarkParamsTest {

  /** The stores and signals the chart declares, and which marks show a pointer. */
  private fun built(spec: String): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    fun names(value: VegaValue?) =
      (value as? VegaValue.Arr)?.values.orEmpty().map { (it as VegaValue.Obj).string("name") }
    fun cursors(marks: List<VegaValue>): List<String> = marks.flatMap { mark ->
      mark as VegaValue.Obj
      val cursor = mark.obj("encode")?.obj("update")?.fields?.get("cursor")
      (if (cursor == null) emptyList() else listOf("${mark.string("name")}")) +
        cursors((mark.fields["marks"] as? VegaValue.Arr)?.values.orEmpty())
    }
    val stores = names(compiled.fields["data"]).filter { it?.endsWith("_store") == true }
    return "stores=$stores signals=${names(compiled.fields["signals"])} " +
      "pointers=${cursors((compiled.fields["marks"] as VegaValue.Arr).values)}"
  }

  private val data = """"data":{"values":[{"a":1,"b":"p"}]}"""
  private val encoding =
    """"encoding":{"x":{"field":"a","type":"quantitative"},"y":{"field":"b","type":"nominal"}}"""
  private val pick = """"params":[{"name":"pick","select":{"type":"point","fields":["b"]}}]"""
  private val machinery =
    "signals=[y_step, height, unit, pick, pick_tuple, pick_tuple_fields, pick_toggle, pick_modify]"

  /** A line that draws its own points builds the parameter once, on the line. */
  @Test
  fun `an overlaid mark builds its parameter on the mark that was written`() {
    assertEquals(
      "stores=[pick_store] $machinery pointers=[layer_0_marks]",
      built("""{$data,"mark":{"type":"line","point":true},$encoding,$pick}"""),
    )
  }

  /** An area that draws both a line and points is the same answer with two overlays. */
  @Test
  fun `two overlays leave the parameter on the first member`() {
    assertEquals(
      "stores=[pick_store] $machinery pointers=[layer_0_marks]",
      built("""{$data,"mark":{"type":"area","line":true,"point":true},$encoding,$pick}"""),
    )
  }

  /** A mark that was written with no overlay is unchanged, which is most of the corpus. */
  @Test
  fun `a plain mark builds its parameter as it always did`() {
    assertEquals(
      "stores=[pick_store] $machinery pointers=[marks]",
      built("""{$data,"mark":"line",$encoding,$pick}"""),
    )
  }

  /** A **box plot** takes none: no store, no signals, and nothing reacting. */
  @Test
  fun `a box plot builds no parameter`() {
    assertEquals(
      "stores=[] signals=[y_step, height] pointers=[]",
      built("""{$data,"mark":"boxplot",$encoding,$pick}"""),
    )
  }

  /** Nor does an error bar, which is the same normalizer. */
  @Test
  fun `an error bar builds no parameter`() {
    assertEquals(
      "stores=[] signals=[y_step, height] pointers=[]",
      built("""{$data,"mark":"errorbar",$encoding,$pick}"""),
    )
  }

  /** Dropping it is reported: a chart that does not react where it says it should says so. */
  @Test
  fun `dropping a composite mark's parameter is reported`() {
    val compiled = VegaLiteCompiler().compileJson("""{$data,"mark":"boxplot",$encoding,$pick}""")
    assertEquals(
      listOf(VegaLiteDiagnostics.UNSUPPORTED_PARAMETER to "$.params"),
      compiled.diagnostics.map { it.code to it.jsonPath },
    )
  }

  /** Two plots that declare the same name get one copy of the machinery each. */
  @Test
  fun `each plot that declares a name builds its own machinery`() {
    val spec =
      """{$data,"hconcat":[
         {"mark":{"type":"line","point":true},$encoding,$pick},
         {"mark":"circle",$encoding,$pick}]}"""
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    assertEquals(
      listOf(
        "concat_0_group=[pick_tuple, pick_tuple_fields, pick_toggle, pick_modify]",
        "concat_1_group=[pick_tuple, pick_tuple_fields, pick_toggle, pick_modify]",
      ),
      (compiled.fields["marks"] as VegaValue.Arr).values.map { group ->
        group as VegaValue.Obj
        "${group.string("name")}=" +
          (group.fields["signals"] as? VegaValue.Arr)?.values.orEmpty().map {
            (it as VegaValue.Obj).string("name")
          }
      },
    )
  }
}
