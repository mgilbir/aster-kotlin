package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A selection declared **above** a composition belongs to every view below it.
 *
 * ```js
 * public assembleSelectionSignals(): Signal[] {
 *   return assembleUnitSelectionSignals(this, []);
 * }
 * ```
 *
 * `assembleUnitSelectionSignals` runs per **unit model**, and a parameter written on the chart is
 * inherited by each of them rather than being the chart's alone. Three things follow, and this
 * compiler had all three the other way:
 *
 * - the **machinery** is written in each plot's own group, where the marks it watches are. Kept at
 *   the top instead, one set of signals watched the marks of two plots at once and the pointer over
 *   either of them wrote the same tuple.
 * - the **unit** each tuple records is that plot's name — `unitName(model)` is the name of the
 *   model the signal is written for. Recorded empty, every plot's tuples claimed to come from the
 *   same unit and a selection resolved per plot could not tell them apart.
 * - each view needs the **identifier** after its aggregate, `requiresSelectionId(model)` asking the
 *   unit model: the rows an aggregate makes are not the rows that went in, and a selection that
 *   remembers by identity has nothing to remember them by.
 *
 * One specification in the wild corpus picks rows that way from a row of plots.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class SharedSelectionTest {

  /** The chart's signals, each group's own, and the datasets. */
  private fun assembled(spec: String): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    fun names(of: VegaValue?) =
      (of as? VegaValue.Arr)?.values.orEmpty().joinToString(",") {
        (it as VegaValue.Obj).string("name").orEmpty()
      }
    val groups =
      (compiled.fields["marks"] as VegaValue.Arr).values.joinToString(" ") {
        it as VegaValue.Obj
        "${it.string("name")}[${names(it.fields["signals"])}]"
      }
    val data =
      (compiled.fields["data"] as VegaValue.Arr).values.joinToString(",") {
        it as VegaValue.Obj
        val steps =
          (it.fields["transform"] as? VegaValue.Arr)?.values.orEmpty().joinToString(",") { step ->
            (step as VegaValue.Obj).string("type").orEmpty()
          }
        "${it.string("name")}($steps)"
      }
    return "${names(compiled.fields["signals"])} | $groups | $data"
  }

  /** The `unit` the first group's tuple records. */
  private fun unitOf(spec: String): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    val tuple =
      ((compiled.fields["marks"] as VegaValue.Arr).values[0] as VegaValue.Obj)
        .fields["signals"]
        ?.let { (it as VegaValue.Arr).values }
        .orEmpty()
        .map { it as VegaValue.Obj }
        .first { it.string("name") == "pick_tuple" }
    return Regex("""\{unit: [^,]*""")
      .find(VegaJson.write(tuple))
      ?.value
      .orEmpty()
      .replace("\\\"", "\"")
  }

  private val rows = """"data":{"values":[{"c":"a","v":1}]}"""
  private val plot =
    """"mark":"bar","encoding":{"x":{"field":"c","type":"nominal"},
                                "y":{"aggregate":"sum","field":"v","type":"quantitative"}}"""
  private val pick = """{"name":"pick","select":{"type":"point"}}"""

  /** The reported shape: one selection over a row of plots. */
  @Test
  fun `a chart's selection is machinery in every plot`() {
    assertEquals(
      "concat_0_x_step,concat_0_width,concat_1_x_step,concat_1_width,unit,pick | " +
        "concat_0_group[pick_tuple,pick_toggle,pick_modify] " +
        "concat_1_group[pick_tuple,pick_toggle,pick_modify] | " +
        "pick_store(collect),source_0(),data_0(identifier,aggregate,identifier,filter)",
      assembled("""{$rows,"params":[$pick],"hconcat":[{$plot},{$plot}]}"""),
    )
  }

  /** And each copy records the plot it was picked in. */
  @Test
  fun `each copy records its own plot`() {
    assertEquals(
      """{unit: "concat_0"""",
      unitOf("""{$rows,"params":[$pick],"hconcat":[{$plot},{$plot}]}"""),
    )
  }

  /** A selection declared on **one** plot is that plot's alone, which is what must not change. */
  @Test
  fun `a plot's own selection stays its own`() {
    assertEquals(
      "concat_0_x_step,concat_0_width,concat_1_x_step,concat_1_width,unit,pick | " +
        "concat_0_group[pick_tuple,pick_toggle,pick_modify] concat_1_group[] | " +
        "pick_store(collect),source_0(),data_0(identifier,aggregate),data_1(filter)," +
        "data_2(identifier,filter)",
      assembled("""{$rows,"hconcat":[{$plot,"params":[$pick]},{$plot}]}"""),
    )
  }

  /** Over a single plot there is no group to write into, and the machinery stays at the top. */
  @Test
  fun `a chart's selection over one plot stays at the top`() {
    assertEquals(
      "x_step,width,unit,pick,pick_tuple,pick_toggle,pick_modify | marks[] | " +
        "pick_store(collect),source_0(),data_0(identifier,aggregate,identifier,filter)",
      assembled("""{$rows,"params":[$pick],$plot}"""),
    )
  }
}
