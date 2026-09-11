package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which dimension a `"container"` size measures is asked of the **signal's name**.
 *
 * ```js
 * const isWidth = name.endsWith('width');
 * const expr = isWidth ? 'containerSize()[0]' : 'containerSize()[1]';
 * const defaultValue = getViewConfigContinuousSize(model.config.view, isWidth ? 'width' : 'height');
 * ```
 *
 * The name a **cell** carries is `childWidth`, whose capital W the test does not match — so a cell
 * told to fill its container measures the container's *height* for its width, and takes the themed
 * height where there is nothing to measure. It is upstream's own slip, and it is what upstream
 * emits: a chart drawn against a different answer would lay out differently from the one the
 * specification's author is looking at, which is the whole of what this compiler is for.
 *
 * This compiler asked the channel instead, so a responsive concatenation measured the wrong way
 * about. One specification in the wild corpus is a row of container-sized plots.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class ContainerSizeTest {

  /** Each size signal, and the expression or value it settles on. */
  private fun sizes(spec: String): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    return (compiled.fields["signals"] as? VegaValue.Arr)
      ?.values
      .orEmpty()
      .map { it as VegaValue.Obj }
      .filter { it.string("name")?.contains(Regex("idth|eight")) == true }
      .joinToString(",") {
        val settled =
          it.string("init")
            ?: it.string("update")
            ?: it.fields["value"]?.let { value -> VegaJson.write(value) }
        "${it.string("name")}: $settled"
      }
  }

  private val rows = """"data":{"values":[{"a":1,"b":2}]}"""
  private val plot =
    """"mark":"point","encoding":{"x":{"field":"a","type":"quantitative"},
                                  "y":{"field":"b","type":"quantitative"}}"""

  /** A chart's own width is a width, and measures the container's first dimension. */
  @Test
  fun `a chart's container width measures across`() {
    assertEquals(
      "width: isFinite(containerSize()[0]) ? containerSize()[0] : 300",
      sizes("""{$rows,"width":"container",$plot}"""),
    )
  }

  /** And its height measures the second, which is the rule read the way it reads. */
  @Test
  fun `a chart's container height measures down`() {
    assertEquals(
      "height: isFinite(containerSize()[1]) ? containerSize()[1] : 300",
      sizes("""{$rows,"height":"container",$plot}"""),
    )
  }

  /**
   * A **cell**'s width is called `childWidth`, which does not end in `width` — so it measures the
   * container's second dimension. Upstream's own slip, reproduced deliberately.
   */
  @Test
  fun `a cell's container width measures down`() {
    assertEquals(
      "childWidth: isFinite(containerSize()[1]) ? containerSize()[1] : 300",
      sizes("""{$rows,"hconcat":[{"width":"container",$plot},{"width":"container",$plot}]}"""),
    )
  }

  /** The themed fallback follows the same test: a cell's width falls back to the themed height. */
  @Test
  fun `a cell's container width falls back to the themed height`() {
    assertEquals(
      "childWidth: isFinite(containerSize()[1]) ? containerSize()[1] : 222",
      sizes(
        """{$rows,"config":{"view":{"continuousWidth":111,"continuousHeight":222}},
           "hconcat":[{"width":"container",$plot},{"width":"container",$plot}]}"""
      ),
    )
  }

  /** Where the name does end in `width`, the themed width is what it falls back to. */
  @Test
  fun `a chart's container width falls back to the themed width`() {
    assertEquals(
      "width: isFinite(containerSize()[0]) ? containerSize()[0] : 111",
      sizes(
        """{$rows,"width":"container",$plot,
           "config":{"view":{"continuousWidth":111,"continuousHeight":222}}}"""
      ),
    )
  }
}
