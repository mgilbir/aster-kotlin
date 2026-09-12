package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A name **derived** from a model's own is a variable name; a name the specification stated is not.
 *
 * ```js
 * public getName(text: string) {
 *   return varName((this.name ? `${this.name}_` : '') + text);
 * }
 * ```
 *
 * `getName` is how every dataset, signal, scale and mark group is named, and it puts the whole
 * joined string through `varName` — so a chart called `Amount Bar Chart` has a layer called
 * `Amount_Bar_Chart_layer_0`. This engine joined the parts and left them, and a chart whose name
 * held a space or a hyphen came out with names Vega cannot parse as identifiers: `Amount Bar
 * Chart_layer_0` in a dataset reference, and the same inside the expression a selection stores its
 * unit under.
 *
 * `this.name` itself is **not** put through it — `spec.name ?? parentGivenName` — so a name the
 * specification wrote is used as written wherever a name rather than an identifier is wanted. The
 * `unit` a selection records is exactly that place:
 * ```js
 * let name = escape ? stringValue(model.name) : model.name;
 * ```
 *
 * so a layer that names *itself* `L 1` stores `"L 1"` and takes `L_1_marks` for its mark. Both are
 * checked below, because the two halves of that rule are easy to conflate.
 *
 * Two specifications in the wild corpus differ for it, both of them charts whose title-cased name
 * reaches a selection's unit.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class DerivedModelNameTest {

  private fun compiled(spec: String): VegaValue.Obj =
    VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
      as VegaValue.Obj

  private fun markNames(spec: String): List<String> =
    (compiled(spec).fields["marks"] as VegaValue.Arr).values.mapNotNull {
      (it as? VegaValue.Obj)?.string("name")
    }

  private fun dataNames(spec: String): List<String> =
    (compiled(spec).fields["data"] as VegaValue.Arr).values.mapNotNull {
      (it as? VegaValue.Obj)?.string("name")
    }

  private val rows = """"data":{"values":[{"a":1,"b":2}]}"""
  private val position =
    """"encoding":{"x":{"field":"a","type":"quantitative"},
                   "y":{"field":"b","type":"quantitative"}}"""

  /** The reported shape: a chart whose name has spaces in it, and a layer beneath it. */
  @Test
  fun `a layer under a spaced name is named with underscores`() {
    assertEquals(
      listOf("A_B_c_layer_0_marks", "A_B_c_layer_1_marks"),
      markNames(
        """{"name":"A B c",$rows,
            "layer":[{"mark":"point",$position},{"mark":"line",$position}]}"""
      ),
    )
  }

  /** A composite mark's parts are layers of layers, and every level goes through the same joint. */
  @Test
  fun `a box plot's parts are named with underscores`() {
    assertEquals(
      listOf(
        "A_B_c_layer_0_layer_0_marks",
        "A_B_c_layer_0_layer_1_layer_0_marks",
        "A_B_c_layer_0_layer_1_layer_1_marks",
        "A_B_c_layer_1_layer_0_marks",
        "A_B_c_layer_1_layer_1_marks",
      ),
      markNames(
        """{"name":"A B c",$rows,"mark":"boxplot",
            "encoding":{"x":{"field":"a","type":"quantitative"}}}"""
      ),
    )
  }

  /** A concatenation's plots too. */
  @Test
  fun `a concatenation's plots are named with underscores`() {
    assertEquals(
      listOf("A_B_c_concat_0_group", "A_B_c_concat_1_group"),
      markNames(
        """{"name":"A B c",$rows,
            "concat":[{"mark":"point",$position},{"mark":"line",$position}]}"""
      ),
    )
  }

  /** And a trellis's bands, its cell and the dataset its headers are titled from. */
  @Test
  fun `a trellis's bands and domain are named with underscores`() {
    val spec =
      """{"name":"A B c",$rows,"mark":"point",
          "encoding":{"x":{"field":"a","type":"quantitative"},
                      "y":{"field":"b","type":"quantitative"},
                      "column":{"field":"a","type":"nominal"}}}"""
    assertEquals(
      listOf(
        "column-title",
        "A_B_c_row_header",
        "A_B_c_column_header",
        "A_B_c_column_footer",
        "A_B_c_cell",
      ),
      markNames(spec),
    )
    assertEquals(listOf("source_0", "data_0", "A_B_c_column_domain"), dataNames(spec))
  }

  /**
   * The other half: a **stated** name is used as written where a name rather than an identifier is
   * wanted, which is the `unit` a selection records.
   */
  @Test
  fun `a selection records the model's name as written`() {
    val units =
      (compiled(
            """
            {"name":"A B c",$rows,"params":[{"name":"p","select":"point"}],
             "mark":"point",$position}
            """
          )
          .fields["signals"]
          as VegaValue.Arr)
        .values
        .mapNotNull { it as? VegaValue.Obj }
        .flatMap { signal ->
          (signal.fields["on"] as? VegaValue.Arr)?.values.orEmpty().mapNotNull {
            (it as? VegaValue.Obj)?.string("update")
          }
        }
        .filter { it.contains("unit:") }
    assertTrue(units.isNotEmpty(), "the selection stores a unit")
    assertTrue(
      units.all { it.contains("""{unit: "A B c"""") },
      "the name as written, spaces and all — was: $units",
    )
  }

  /**
   * And a selection declared **inside** a layer records the derived name, which is where the two
   * halves meet: that name is an identifier because the layer took it from its parent rather than
   * writing it.
   */
  @Test
  fun `a selection inside a layer records the derived name`() {
    val units =
      (compiled(
            """
            {"name":"A B c",$rows,
             "layer":[{"mark":"point","params":[{"name":"p","select":"point"}],$position},
                      {"mark":"line",$position}]}
            """
          )
          .fields["signals"]
          as VegaValue.Arr)
        .values
        .mapNotNull { it as? VegaValue.Obj }
        .flatMap { signal ->
          (signal.fields["on"] as? VegaValue.Arr)?.values.orEmpty().mapNotNull {
            (it as? VegaValue.Obj)?.string("update")
          }
        }
        .filter { it.contains("unit:") }
    assertTrue(units.isNotEmpty(), "the selection stores a unit")
    assertTrue(
      units.all { it.contains("""{unit: "A_B_c_layer_0"""") },
      "an identifier, because the layer was given its name rather than writing it — was: $units",
    )
  }

  /** A name that needs no cleaning is unchanged, so the rule is invisible in every other chart. */
  @Test
  fun `an ordinary name is unaffected`() {
    assertEquals(
      listOf("plot_layer_0_marks", "plot_layer_1_marks"),
      markNames(
        """{"name":"plot",$rows,
            "layer":[{"mark":"point",$position},{"mark":"line",$position}]}"""
      ),
    )
  }
}
