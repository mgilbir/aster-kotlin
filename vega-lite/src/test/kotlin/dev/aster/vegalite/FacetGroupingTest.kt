package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A step the partition is walked past is grouped by the **grid's** own columns as well as its own.
 *
 * ```ts
 * if (child instanceof AggregateNode || child instanceof StackNode ||
 *     child instanceof WindowTransformNode || child instanceof JoinAggregateTransformNode) {
 *   child.addDimensions(node.fields);
 * }
 * child.swapWithParent();
 * moveFacetDown(node);
 * ```
 *
 * `moveFacetDown` walks the partition down past the cell's chain one node at a time, and each of
 * the four nodes that **group** picks up the facet's fields on the way. A count a cell states is a
 * count within that cell; hoisted above the grid without the grid's own columns it counts the whole
 * table instead, and every cell of the trellis then draws the same number.
 *
 * This compiler already did that for the aggregate an *encoding* asks for and not for one a
 * `transform` states, so a confusion matrix computed per revision came out computed once over every
 * revision at once. One specification in the wild corpus states such a transform.
 *
 * A **grid's own** transforms are none of this: the partition is appended after them and never
 * moves past them, so they keep the grouping they were written with.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class FacetGroupingTest {

  /** Each dataset and the steps it runs, each step with the columns it groups by. */
  private fun steps(spec: String): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    return (compiled.fields["data"] as VegaValue.Arr).values.joinToString(" | ") {
      it as VegaValue.Obj
      val steps =
        (it.fields["transform"] as? VegaValue.Arr)?.values.orEmpty().joinToString(",") { step ->
          step as VegaValue.Obj
          val groupby =
            (step.fields["groupby"] as? VegaValue.Arr)?.values?.joinToString("+") { field ->
              (field as? VegaValue.Str)?.value.orEmpty()
            }
          step.string("type").orEmpty() + (groupby?.let { g -> "[$g]" } ?: "")
        }
      "${it.string("name")}($steps)"
    }
  }

  private val rows = """"data":{"values":[{"a":1,"b":2,"g":"x"}]}"""
  private val enc =
    """"encoding":{"x":{"field":"sa","type":"quantitative"},
                                   "y":{"field":"b","type":"quantitative"}}"""

  /** The reported shape: a cell that aggregates, which is an aggregate within the cell. */
  @Test
  fun `a cell's aggregate groups by the grid's columns`() {
    assertEquals(
      "source_0() | data_0(aggregate[b+g],filter) | column_domain(aggregate[g])",
      steps(
        """{$rows,"facet":{"column":{"field":"g","type":"nominal"}},
           "spec":{"transform":[{"aggregate":[{"op":"sum","field":"a","as":"sa"}],
                                 "groupby":["b"]}],"mark":"point",$enc}}"""
      ),
    )
  }

  /** The same for the other three that group — a `joinaggregate`. */
  @Test
  fun `a cell's joinaggregate groups by the grid's columns`() {
    assertEquals(
      "source_0() | data_0(joinaggregate[b+g],filter) | column_domain(aggregate[g])",
      steps(
        """{$rows,"facet":{"column":{"field":"g","type":"nominal"}},
           "spec":{"transform":[{"joinaggregate":[{"op":"max","field":"a","as":"sa"}],
                                 "groupby":["b"]}],"mark":"point",$enc}}"""
      ),
    )
  }

  /** And a `window`, whose ranking runs within the cell too. */
  @Test
  fun `a cell's window groups by the grid's columns`() {
    assertEquals(
      "source_0() | data_0(window[b+g],filter) | column_domain(aggregate[g])",
      steps(
        """{$rows,"facet":{"column":{"field":"g","type":"nominal"}},
           "spec":{"transform":[{"window":[{"op":"rank","as":"sa"}],"groupby":["b"]}],
                   "mark":"point",$enc}}"""
      ),
    )
  }

  /** A step that does not group is per-row and has nothing to add. */
  @Test
  fun `a cell's calculate is left alone`() {
    assertEquals(
      "source_0() | data_0(formula,filter) | column_domain(aggregate[g])",
      steps(
        """{$rows,"facet":{"column":{"field":"g","type":"nominal"}},
           "spec":{"transform":[{"calculate":"datum.a","as":"sa"}],"mark":"point",$enc}}"""
      ),
    )
  }

  /**
   * The **grid's own** keeps the grouping it was written with, and the cell's beside it does not:
   * the partition is appended after the grid's pass and only ever walks past the cell's.
   */
  @Test
  fun `a grid's own transform keeps its own grouping`() {
    assertEquals(
      "source_0() | data_0(joinaggregate[b],joinaggregate[b+g],filter) | " +
        "column_domain(aggregate[g])",
      steps(
        """{$rows,"transform":[{"joinaggregate":[{"op":"max","field":"a","as":"m"}],
                                "groupby":["b"]}],
           "facet":{"column":{"field":"g","type":"nominal"}},
           "spec":{"transform":[{"joinaggregate":[{"op":"min","field":"a","as":"sa"}],
                                 "groupby":["b"]}],"mark":"point",$enc}}"""
      ),
    )
  }

  /** A **fork** below the partition, where the shared step is hoisted over it all the same. */
  @Test
  fun `a layer's shared aggregate groups by the grid's columns`() {
    assertEquals(
      "source_0() | data_0(aggregate[b+g]) | column_domain(aggregate[g]) | data_3(filter)",
      steps(
        """{$rows,"facet":{"column":{"field":"g","type":"nominal"}},
           "spec":{"transform":[{"aggregate":[{"op":"sum","field":"a","as":"sa"}],
                                 "groupby":["b"]}],
                   "layer":[{"mark":"point",$enc},{"mark":"line",$enc}]}}"""
      ),
    )
  }

  /**
   * A facet written in the **encoding** keeps the transforms on the grid, not on the cell:
   * ```ts
   * const {mark, width, projection, height, view, params, encoding: _, ...outerSpec} = spec;
   * return this.mapFacet({...outerSpec, ...layout, facet: facetMapping, spec: {…, mark, encoding}});
   * ```
   *
   * `mapFacetedUnit` moves the mark and the encoding down and leaves everything else where it was,
   * a `transform` among it. So a rolling mean written beside a `facet` channel is one rolling mean
   * over the whole table, computed before the grid is cut — which is what such a chart asks for.
   */
  @Test
  fun `a transform beside a facet channel belongs to the grid`() {
    assertEquals(
      "source_0() | data_0(window[b],filter) | column_domain(aggregate[g])",
      steps(
        """{$rows,"transform":[{"window":[{"op":"rank","as":"sa"}],"groupby":["b"]}],
           "mark":"point",
           "encoding":{"x":{"field":"sa","type":"quantitative"},
                       "y":{"field":"b","type":"quantitative"},
                       "column":{"field":"g","type":"nominal"}}}"""
      ),
    )
  }

  /** And with no grid at all nothing is added, which is every other chart. */
  @Test
  fun `an unfacetted aggregate keeps its own grouping`() {
    assertEquals(
      "source_0() | data_0(aggregate[b],filter)",
      steps(
        """{$rows,"transform":[{"aggregate":[{"op":"sum","field":"a","as":"sa"}],
                                "groupby":["b"]}],"mark":"point",$enc}"""
      ),
    )
  }
}
