package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A **facet** channel is none of the composite mark's own.
 *
 * Upstream normalises a grid into the operator form before a composite mark is reached at all, so
 * the cell it hands the mark has no `row` or `column` in its encoding and there is nothing there to
 * carry: `errorBarParams` spreads what it is given. This compiler folds the operator form the other
 * way, and the channel was then carried like any other — **named in the tooltip**, where upstream
 * names only what the mark itself draws, so a reader hovering an interval was told which cell they
 * were in.
 *
 * The grid's own columns are not grouped by in the mark's own transform either:
 * ```ts
 * if (child instanceof AggregateNode || ...) {
 *   child.addDimensions(node.fields);
 * }
 * ```
 *
 * `moveFacetDown` walks the partition down past the summary and the summary picks the facet's
 * fields up on the way, so the copy that stands **beside** the grid groups by them and the copy
 * inside each cell does not — each cell holding one value of them already. Written into the
 * transform, both copies carried them: the cell's summary grouped by a column it cannot vary, and
 * the two layers' summaries were no longer the same question, so the grouping was computed once per
 * layer where upstream computes it once. That half is asserted by `CompositeFacetChannelTest`'s
 * neighbour `RenamedCellOwnsItsTransformsTest`, which is where the cell's own steps first reach the
 * output.
 *
 * Four specifications in the wild corpus draw an error bar inside a grid.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class CompositeFacetChannelTest {

  /** What each mark names in its tooltip. */
  private fun named(spec: String): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    fun walk(mark: VegaValue.Obj): List<String> =
      listOfNotNull(
        mark.obj("encode")?.obj("update")?.obj("tooltip")?.string("signal")?.let { tip ->
          Regex("\"([^\"]+)\":").findAll(tip).map { it.groupValues[1] }.joinToString(",")
        }
      ) +
        (mark.fields["marks"] as? VegaValue.Arr)?.values.orEmpty().flatMap {
          walk(it as VegaValue.Obj)
        }
    return (compiled.fields["marks"] as VegaValue.Arr)
      .values
      .flatMap { walk(it as VegaValue.Obj) }
      .joinToString(" | ")
  }

  private val rows = """"data":{"values":[{"a":1,"b":2,"r":"x","k":"z"}]}"""
  private val bar =
    """{"mark":"bar","encoding":{"x":{"field":"a","type":"quantitative"},
                                 "y":{"field":"b","type":"nominal"}}}"""
  private val errorbar =
    """{"mark":{"type":"errorbar"},
       "encoding":{"x":{"field":"a","type":"quantitative"},
                   "y":{"field":"b","type":"nominal"}}}"""

  private fun grid(cell: String) =
    """{$rows,"facet":{"row":{"field":"r","type":"ordinal","title":"Dataset Size"}},"spec":$cell}"""

  /** The reported shape: an error bar inside a grid names what it draws, and not the cell. */
  @Test
  fun `a grid's column names nothing in the tooltip`() {
    assertEquals(
      "Mean of a,Mean + stderr of a,Mean - stderr of a,b",
      named(grid("""{"layer":[$bar,$errorbar]}""")),
    )
  }

  /** Outside a grid there is no column to leave out, which is the shape that must not change. */
  @Test
  fun `an error bar outside a grid names the same`() {
    assertEquals(
      "Mean of a,Mean + stderr of a,Mean - stderr of a,b",
      named("""{$rows,"layer":[$bar,$errorbar]}"""),
    )
  }

  /** A channel the mark **does** draw with still names itself. */
  @Test
  fun `a colour still names itself`() {
    assertEquals(
      "Mean of a,Mean + stderr of a,Mean - stderr of a,b,k",
      named(
        grid(
          """{"layer":[$bar,{"mark":{"type":"errorbar"},
             "encoding":{"x":{"field":"a","type":"quantitative"},
                         "y":{"field":"b","type":"nominal"},
                         "color":{"field":"k","type":"nominal"}}}]}"""
        )
      ),
    )
  }

  /** And an interval the rows already carry names its own two ends. */
  @Test
  fun `a ranged error bar in a grid names its ends`() {
    assertEquals(
      "a,a + e,a - e,b",
      named(
        """{"data":{"values":[{"a":1,"b":2,"r":"x","e":0.5}]},
           "facet":{"row":{"field":"r","type":"ordinal","title":"Dataset Size"}},
           "spec":{"layer":[
             {"mark":"bar","encoding":{"x":{"field":"a","type":"quantitative"},
                                       "y":{"field":"b","type":"quantitative"}}},
             {"mark":{"type":"errorbar"},
              "encoding":{"x":{"field":"a","type":"quantitative"},"xError":{"field":"e"},
                          "y":{"field":"b","type":"quantitative"}}}]}}"""
      ),
    )
  }
}
