package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The column a grid is **split by** is read before it is cut.
 *
 * ```js
 * if (isUnitModel(model) || isFacetModel(model)) {
 *   // Parse encoded fields
 *   model.forEachFieldDef((fieldDef, channel) => { ... add(fieldDef) ... });
 * }
 * ```
 *
 * `getImplicitFromEncoding` is asked of a facet model as much as of a unit, and its answer goes in
 * between that model's transforms and its bucketing — `parseData` runs the same sequence for every
 * model there is. A grid split by a date therefore reads that column as a date, and only then cuts
 * it into the months its cells stand for.
 *
 * A cell's encoding no longer mentions the column — a facet says nothing about what a cell looks
 * like — so this compiler, which built the parse from the cell's encoding alone, never asked for
 * it. The column stayed as it arrived, and a `timeunit` bucketing text found nothing to bucket:
 * every cell of the grid was captioned from a date that was never a date. Two specifications in the
 * wild corpus split a grid by a column stated as an instant.
 *
 * Where the table is read from a **url** the same parse reaches Vega's loader as a `format.parse`
 * rather than a formula, the parse node sitting directly under the source; written out, the source
 * is closed the moment its rows are listed and a formula is all that is left. That is
 * `assembleData`'s own rule and not a facet's, but a facet is where this compiler was missing it.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class FacetChannelParseTest {

  private fun compiled(spec: String) =
    VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
      as VegaValue.Obj

  /** Each dataset, its stated parse, and the steps it runs, as one line. */
  private fun data(spec: String): String =
    (compiled(spec).fields["data"] as VegaValue.Arr).values.joinToString(" | ") {
      it as VegaValue.Obj
      val parse =
        it.obj("format")?.obj("parse")?.fields?.entries?.joinToString(",") { (field, kind) ->
          "$field=${(kind as? VegaValue.Str)?.value}"
        }
      val steps =
        (it.fields["transform"] as? VegaValue.Arr)?.values.orEmpty().joinToString(",") { step ->
          step as VegaValue.Obj
          when (step.string("type")) {
            "formula" -> "formula:${step.string("expr")}"
            else -> step.string("type").orEmpty()
          }
        }
      "${it.string("name")}${parse?.let { p -> "[$p]" } ?: ""}(${steps})"
    }

  private val rows = """"data":{"values":[{"label":"2020-05-20","other":"2021-01-02","v":8}]}"""

  /** The reported shape: a grid split by a bucketed instant, over a table written out. */
  @Test
  fun `a grid splits on a date it has read as a date`() {
    assertEquals(
      "source_0() | " +
        """data_0(formula:toDate(datum["label"]),timeunit,stack,filter) | """ +
        "column_domain(aggregate)",
      data(
        """{$rows,"mark":"bar",
           "encoding":{"column":{"field":"label","type":"temporal","timeUnit":"utcday"},
                       "y":{"field":"v","type":"quantitative"}}}"""
      ),
    )
  }

  /** The grid's own column is read **first**: a facet model's pass runs before its child's. */
  @Test
  fun `the grid's column is read before the cell's`() {
    assertEquals(
      "source_0() | " +
        """data_0(formula:toDate(datum["label"]),formula:toDate(datum["other"]),timeunit,""" +
        "stack,filter) | column_domain(aggregate)",
      data(
        """{$rows,"mark":"bar",
           "encoding":{"column":{"field":"label","type":"temporal","timeUnit":"utcday"},
                       "x":{"field":"other","type":"temporal"},
                       "y":{"field":"v","type":"quantitative"}}}"""
      ),
    )
  }

  /** A plain instant, with nothing to bucket it — the parse is the type's, not the unit's. */
  @Test
  fun `a grid splits on a plain date it has read as a date`() {
    assertEquals(
      """source_0() | data_0(formula:toDate(datum["label"]),stack,filter) | """ +
        "column_domain(aggregate)",
      data(
        """{$rows,"mark":"bar",
           "encoding":{"column":{"field":"label","type":"temporal"},
                       "y":{"field":"v","type":"quantitative"}}}"""
      ),
    )
  }

  /** Read from a **url**, where the same parse is the loader's own rather than a formula. */
  @Test
  fun `a url's grid column is read by the loader`() {
    assertEquals(
      "source_0[label=date](stack,filter) | column_domain(aggregate)",
      data(
        """{"data":{"url":"a.json"},"mark":"bar",
           "encoding":{"column":{"field":"label","type":"temporal"},
                       "y":{"field":"v","type":"quantitative"}}}"""
      ),
    )
  }

  /**
   * A **fork** below the partition, where the facet model's pass stands alone above it: the parse
   * belongs to that pass and not to either member's.
   */
  @Test
  fun `a grid over a layer reads its column above the partition`() {
    assertEquals(
      "source_0() | " +
        """data_0(formula:toDate(datum["label"]),formula:toDate(datum["other"]),timeunit) | """ +
        "column_domain(aggregate) | data_3(filter)",
      data(
        """{$rows,"facet":{"column":{"field":"label","type":"temporal","timeUnit":"utcday"}},
           "spec":{"layer":[
             {"mark":"point","encoding":{"x":{"field":"other","type":"temporal"},
                                         "y":{"field":"v","type":"quantitative"}}},
             {"mark":"line","encoding":{"x":{"field":"other","type":"temporal"},
                                        "y":{"field":"v","type":"quantitative"}}}]}}"""
      ),
    )
  }

  /** A column named through a path is flattened into one of its own, the same `add` deciding it. */
  @Test
  fun `a grid splits on a nested column read out flat`() {
    assertEquals(
      """source_0() | data_0(formula:toDate(datum["r"] && datum["r"]["d"]),stack,filter) | """ +
        "column_domain(aggregate)",
      data(
        """{"data":{"values":[{"r":{"d":"2020-05-20"},"v":8}]},"mark":"bar",
           "encoding":{"column":{"field":"r.d","type":"temporal"},
                       "y":{"field":"v","type":"quantitative"}}}"""
      ),
    )
  }

  /** A grid split by a plain category asks for no parse at all, which is every other trellis. */
  @Test
  fun `a grid split by a category asks for no parse`() {
    assertEquals(
      "source_0() | data_0(stack,filter) | column_domain(aggregate)",
      data(
        """{$rows,"mark":"bar",
           "encoding":{"column":{"field":"label","type":"nominal"},
                       "y":{"field":"v","type":"quantitative"}}}"""
      ),
    )
  }
}
