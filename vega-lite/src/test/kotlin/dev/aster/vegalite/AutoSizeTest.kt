package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * How a chart is sized is settled twice: before it is compiled, and again when it is assembled.
 *
 * `normalizeAutoSize` runs first and knows the *shape* of the chart — whether there is one plotting
 * area a fit could stretch — and merges the theme's sizing with the chart's over a `"container"`
 * default. `getTopLevelProperties` runs last and knows the *size* it came out as, which is what
 * lets it drop a fit that a step per category has already settled:
 * ```js
 * if (width && height && isFitType(autosize.type)) {
 *   if (width === 'step' && height === 'step') { log.warn(log.message.droppingFit()); autosize.type = 'pad'; }
 *   else if (width === 'step' || height === 'step') {
 *     const sizeType = width === 'step' ? 'width' : 'height';
 *     const inverseSizeType = sizeType === 'width' ? 'height' : 'width';
 *     autosize.type = getFitType(inverseSizeType);
 *   }
 * }
 * ```
 *
 * This engine ran one merge and neither rule. So a bar chart as wide as its bars that asked to be
 * fitted was told to stretch a plotting area whose size its own data had settled; a `"container"`
 * size on a grid was fitted rather than discarded; a theme's sizing was not read at all; and the
 * `resize` an axis on a parameter needs was added even where the chart had settled its own sizing,
 * which upstream reaches only under `autosize === undefined`.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class AutoSizeTest {

  private fun autosize(spec: String): VegaValue? {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    return compiled.fields["autosize"]
  }

  private fun json(text: String) = VegaJson.parse(text)

  private val data = """"data":{"values":[{"c":"x","b":1,"d":"q"}]}"""

  /** A chart whose two positions are as discrete or as continuous as asked for. */
  private fun chart(x: String, y: String, sizing: String = "", size: String = "") =
    """{$data,"mark":"bar",$sizing$size
       "encoding":{"x":{"field":"c","type":"$x"},"y":{"field":"b","type":"$y"}}}"""

  // -- A fit a step has already settled ---------------------------------------------------------

  /** The reported shape: a plotting area sized by its own data cannot also fill the surface. */
  @Test
  fun `a fit is given up where both directions are stepped`() {
    assertEquals(
      json("""{"type":"pad","resize":true}"""),
      autosize(chart("nominal", "nominal", """"autosize":{"type":"fit","resize":true},""")),
    )
  }

  /** And where that leaves nothing but the default, nothing is written. */
  @Test
  fun `a bare fit both of whose directions are stepped is written as nothing`() {
    assertEquals(null, autosize(chart("nominal", "nominal", """"autosize":"fit",""")))
  }

  /** Half a fit survives: the direction whose size was not settled by a step. */
  @Test
  fun `a fit whose width is stepped survives along the height`() {
    assertEquals(
      VegaValue.Str("fit-y"),
      autosize(chart("nominal", "quantitative", """"autosize":"fit",""")),
    )
  }

  @Test
  fun `a fit whose height is stepped survives along the width`() {
    assertEquals(
      VegaValue.Str("fit-x"),
      autosize(chart("quantitative", "nominal", """"autosize":"fit",""")),
    )
  }

  /** A stated number is a size like any other, and the step is then the other direction's. */
  @Test
  fun `a stated width leaves the fit to be dropped along the height`() {
    assertEquals(
      VegaValue.Str("fit-x"),
      autosize(chart("nominal", "nominal", """"autosize":"fit",""", """"width":200,""")),
    )
  }

  /** A fit already narrowed to one direction is dropped whole where both are stepped. */
  @Test
  fun `a half fit is given up where both directions are stepped`() {
    assertEquals(null, autosize(chart("nominal", "nominal", """"autosize":"fit-x",""")))
  }

  /** Nothing is stepped, so the fit stands. */
  @Test
  fun `a fit over two continuous positions stands`() {
    assertEquals(
      VegaValue.Str("fit"),
      autosize(chart("quantitative", "quantitative", """"autosize":"fit",""")),
    )
  }

  /** A layer has one plotting area too, and its size can be a step. */
  @Test
  fun `a layer whose directions are stepped gives up its fit`() {
    assertEquals(
      null,
      autosize(
        """{$data,"autosize":"fit","layer":[
             {"mark":"bar","encoding":{"x":{"field":"c","type":"nominal"},
                                       "y":{"field":"b","type":"nominal"}}},
             {"mark":"point","encoding":{"x":{"field":"c","type":"nominal"},
                                         "y":{"field":"b","type":"nominal"}}}]}"""
      ),
    )
  }

  // -- A fit where there is nothing to fit ------------------------------------------------------

  /**
   * A grid is laid out from its cells, so there is no one area to stretch. Upstream turns the fit
   * into a `pad`, which is then the default it has nothing to say about — and the property the
   * specification wrote carries over instead, unnormalized. The warning is the whole of what
   * upstream does about it, and this reproduces the carry-over rather than repairing it.
   */
  @Test
  fun `a fit on a grid is warned about and carried over as written`() {
    assertEquals(
      VegaValue.Str("fit"),
      autosize(
        """{$data,"autosize":"fit","facet":{"field":"d","type":"nominal"},
           "spec":{"mark":"bar","encoding":{"x":{"field":"c","type":"nominal"},
                                            "y":{"field":"b","type":"quantitative"}}}}"""
      ),
    )
  }

  /** Where the block says more than the fit, the `pad` it became is what comes out. */
  @Test
  fun `a fit on a grid becomes a pad wherever the block survives the merge`() {
    assertEquals(
      json("""{"type":"pad","resize":true}"""),
      autosize(
        """{$data,"autosize":{"type":"fit","resize":true},"facet":{"field":"d","type":"nominal"},
           "spec":{"mark":"bar","encoding":{"x":{"field":"c","type":"nominal"},
                                            "y":{"field":"b","type":"quantitative"}}}}"""
      ),
    )
  }

  /** A `"container"` size on a grid is discarded rather than fitted. */
  @Test
  fun `a container width on a grid is discarded`() {
    assertEquals(
      null,
      autosize(
        """{$data,"width":"container","facet":{"field":"d","type":"nominal"},
           "spec":{"mark":"point","encoding":{"x":{"field":"b","type":"quantitative"}}}}"""
      ),
    )
  }

  // -- The two merges ---------------------------------------------------------------------------

  /** A theme states the sizing its document's charts share. */
  @Test
  fun `a themed sizing is read`() {
    assertEquals(
      VegaValue.Str("fit-x"),
      autosize(
        """{$data,"config":{"autosize":"fit-x"},"mark":"point",
           "encoding":{"x":{"field":"b","type":"quantitative"},
                       "y":{"field":"b","type":"quantitative"}}}"""
      ),
    )
  }

  /** And the chart overrides it property by property rather than whole. */
  @Test
  fun `a chart overrides the themed sizing one property at a time`() {
    assertEquals(
      json("""{"type":"fit","contains":"padding"}"""),
      autosize(
        """{$data,"config":{"autosize":{"contains":"padding"}},"autosize":"fit","mark":"point",
           "encoding":{"x":{"field":"b","type":"quantitative"},
                       "y":{"field":"b","type":"quantitative"}}}"""
      ),
    )
  }

  /** A `"container"` size fits that direction, and includes the padding in what it measures. */
  @Test
  fun `a container width fits the width and contains the padding`() {
    assertEquals(
      json("""{"type":"fit-x","contains":"padding"}"""),
      autosize(
        """{$data,"width":"container","mark":"point",
           "encoding":{"x":{"field":"b","type":"quantitative"},
                       "y":{"field":"b","type":"quantitative"}}}"""
      ),
    )
  }

  /** A chart that asks to be padded keeps what the container default said about the padding. */
  @Test
  fun `a stated pad beside a container width keeps the containment`() {
    assertEquals(
      json("""{"type":"pad","contains":"padding"}"""),
      autosize(
        """{$data,"width":"container","autosize":"pad","mark":"point",
           "encoding":{"x":{"field":"b","type":"quantitative"},
                       "y":{"field":"b","type":"quantitative"}}}"""
      ),
    )
  }

  // -- The resize an axis on a parameter needs --------------------------------------------------

  private fun orientedByParameter(sizing: String = "") =
    """{$data,$sizing"mark":"point",
       "encoding":{"x":{"field":"b","type":"quantitative","axis":{"orient":{"expr":"'bottom'"}}},
                   "y":{"field":"b","type":"quantitative"}}}"""

  /** The drawing is re-laid out when the axis changes sides, so the surface may resize. */
  @Test
  fun `an axis oriented by a parameter asks for a resize`() {
    assertEquals(json("""{"type":"pad","resize":true}"""), autosize(orientedByParameter()))
  }

  /**
   * But only where nothing else settled the sizing: `getTopLevelProperties` adds the `resize` under
   * `autosize === undefined`, which a chart stating its own sizing is not.
   */
  @Test
  fun `a chart that states its own sizing is not given a resize`() {
    assertEquals(
      VegaValue.Str("fit"),
      autosize(orientedByParameter(""""autosize":"fit",""")),
    )
  }

  /** Not even where what it stated was the default, which is written as nothing at all. */
  @Test
  fun `a chart stating the default sizing is not given a resize`() {
    assertEquals(null, autosize(orientedByParameter(""""autosize":"pad",""")))
  }

  /** A chart asking not to be resized says so, and is not overruled. */
  @Test
  fun `a chart that refuses to be resized keeps its refusal`() {
    assertEquals(
      json("""{"type":"pad","resize":false}"""),
      autosize(orientedByParameter(""""autosize":{"type":"pad","resize":false},""")),
    )
  }
}
