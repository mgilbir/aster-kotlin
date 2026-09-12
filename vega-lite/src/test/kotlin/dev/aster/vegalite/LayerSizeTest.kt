package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A layer is as wide as its **first member**, and as wide as itself where no member says.
 *
 * ```js
 * size: isFrameMixins(spec)
 *   ? {...parentGivenSize, ...(spec.width !== undefined ? {width: spec.width} : {}), ...}
 *   : parentGivenSize,
 * ```
 * ```js
 * mergedSize = mergeValuesWithExplicit(mergedSize, childSize, sizeType, '', defaultTieBreaker);
 * ```
 *
 * A member's own size overrides the one the level above handed it — that spread is the whole of it
 * — and `parseNonUnitLayoutSizeForChannel` then merges the members', the first of them winning a
 * disagreement with a warning.
 *
 * So a chart written `"width": "container"` whose layers are each 600 wide is 600 wide: the members
 * were handed the container and then said otherwise, and there is nothing left for the page to
 * settle. This compiler read the chart's own size first, so the layers' width was never consulted
 * and such a chart measured the element it was drawn in instead — a responsive width where the
 * specification had asked for a fixed one. One specification in the wild corpus is written that
 * way.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class LayerSizeTest {

  /** The chart's width, and whether a signal settles it instead. */
  private fun width(spec: String): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    val signal =
      (compiled.fields["signals"] as? VegaValue.Arr)
        ?.values
        .orEmpty()
        .map { it as VegaValue.Obj }
        .any { it.string("name") == "width" }
    return "${compiled.fields["width"]?.let { VegaJson.write(it) } ?: "-"}${if (signal) " +signal" else ""}"
  }

  private val rows = """"data":{"values":[{"a":1,"b":2}]}"""
  private val enc =
    """"encoding":{"x":{"field":"a","type":"quantitative"},
                                   "y":{"field":"b","type":"quantitative"}}"""

  /** The reported shape: a responsive chart whose layers state a width of their own. */
  @Test
  fun `a member's own width settles a container chart`() {
    assertEquals(
      "600",
      width(
        """{$rows,"width":"container","layer":[
             {"mark":"line",$enc,"width":600},{"mark":"line",$enc,"width":600}]}"""
      ),
    )
  }

  /** The **first** member settles it, and a later one disagreeing does not. */
  @Test
  fun `a later member does not settle it`() {
    assertEquals(
      "- +signal",
      width(
        """{$rows,"width":"container","layer":[
             {"mark":"line",$enc},{"mark":"line",$enc,"width":600}]}"""
      ),
    )
  }

  /** With nothing above them, the first member that states one settles it. */
  @Test
  fun `the first member to state a width settles it`() {
    assertEquals(
      "600",
      width("""{$rows,"layer":[{"mark":"line",$enc},{"mark":"line",$enc,"width":600}]}"""),
    )
  }

  /** And where no member states one, the chart's own stands — which is every other layer. */
  @Test
  fun `the chart's own width stands where no member states one`() {
    assertEquals(
      "400",
      width("""{$rows,"width":400,"layer":[{"mark":"line",$enc},{"mark":"line",$enc}]}"""),
    )
  }

  /** A chart's own width beats a later member's, being the first member's by inheritance. */
  @Test
  fun `the chart's own width beats a later member's`() {
    assertEquals(
      "400",
      width(
        """{$rows,"width":400,"layer":[{"mark":"line",$enc},{"mark":"line",$enc,"width":600}]}"""
      ),
    )
  }

  /** A plain chart asking the page for its width still asks for it. */
  @Test
  fun `a container chart with no member width still measures the page`() {
    assertEquals("- +signal", width("""{$rows,"width":"container","mark":"line",$enc}"""))
  }
}
