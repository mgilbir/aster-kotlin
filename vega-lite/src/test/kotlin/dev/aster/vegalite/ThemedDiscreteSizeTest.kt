package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A discrete position is sized by a **step** only where the theme leaves it to one.
 *
 * ```js
 * if (hasDiscreteDomain(scaleType)) {
 *   const size = getViewConfigDiscreteSize(config.view, sizeType);
 *   if (isVgRangeStep(range) || isStep(size)) {
 *     return 'step';
 *   } else {
 *     return size;
 *   }
 * }
 * ```
 *
 * `getViewConfigDiscreteSize` reads `view.height` before `view.discreteHeight` and answers a
 * `{step: …}` only where the answer is one, so a theme that states a plain depth settles every
 * strip in the document at that depth — however many categories it holds.
 *
 * This compiler wrote out the depth but went on calling it a step behind the theme's back, and it
 * is a **concatenation** that asks: `parseNonUnitLayoutSizeForChannel` abandons the merge where a
 * child's size is a step, so a column of themed strips came out with a size signal each instead of
 * the one they share. Every name derived from it moved with them — the group's own height, the
 * range of its scale, and every clamp an interval brush is bounded by. One specification in the
 * wild corpus is a themed column of strips a brush is dragged across.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class ThemedDiscreteSizeTest {

  /** The chart's size signals, its hoisted width and height, and the depth each plot is given. */
  private fun sizes(spec: String): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    val signals =
      (compiled.fields["signals"] as? VegaValue.Arr)?.values.orEmpty().joinToString(",") {
        it as VegaValue.Obj
        val settled =
          it.fields["value"]?.let { value -> VegaJson.write(value) } ?: it.string("update")
        "${it.string("name")}=$settled"
      }
    val groups =
      (compiled.fields["marks"] as VegaValue.Arr).values.joinToString(",") {
        it as VegaValue.Obj
        val height = it.obj("encode")?.obj("update")?.obj("height")?.string("signal")
        "${it.string("name")}:$height"
      }
    val hoisted =
      listOf("width", "height").joinToString(",") {
        "$it=${compiled.fields[it]?.let { value -> VegaJson.write(value) }}"
      }
    return "[$signals] $hoisted | $groups"
  }

  private val rows = """"data":{"values":[{"a":1,"b":"x"}]}"""
  private val plot =
    """"mark":"circle","encoding":{"x":{"field":"a","type":"quantitative"},
                                   "y":{"field":"b","type":"nominal"}}"""

  /** The reported shape: a themed depth is one depth, so the column merges onto `childHeight`. */
  @Test
  fun `a themed depth is shared by a column of strips`() {
    assertEquals(
      "[childHeight=500] width=300,height=null | concat_0_group:childHeight,concat_1_group:childHeight",
      sizes("""{$rows,"config":{"view":{"height":500}},"vconcat":[{$plot},{$plot}]}"""),
    )
  }

  /** `view.discreteHeight` says the same thing, which is the property the read falls back to. */
  @Test
  fun `a themed discrete depth is shared too`() {
    assertEquals(
      "[childHeight=500] width=300,height=null | concat_0_group:childHeight,concat_1_group:childHeight",
      sizes("""{$rows,"config":{"view":{"discreteHeight":500}},"vconcat":[{$plot},{$plot}]}"""),
    )
  }

  /** With nothing themed the depth **is** a step, and two plots on a step do not merge. */
  @Test
  fun `an unthemed column keeps a step each`() {
    assertEquals(
      "[concat_0_y_step=20," +
        "concat_0_height=bandspace(domain('concat_0_y').length, 1, 0.5) * concat_0_y_step," +
        "concat_1_y_step=20," +
        "concat_1_height=bandspace(domain('concat_1_y').length, 1, 0.5) * concat_1_y_step] " +
        "width=300,height=null | concat_0_group:concat_0_height,concat_1_group:concat_1_height",
      sizes("""{$rows,"vconcat":[{$plot},{$plot}]}"""),
    )
  }

  /**
   * A theme whose discrete size **is** a step is one as well: it is the shape that is asked about,
   * not which property carried it.
   *
   * Written with the step this compiler already uses. What a themed step of some *other* size comes
   * to is a gap of its own — `getDiscretePositionSize` reads the theme's step where this reads only
   * `view.step` — and not this rule's.
   */
  @Test
  fun `a themed step is still a step`() {
    assertEquals(
      "[concat_0_y_step=20," +
        "concat_0_height=bandspace(domain('concat_0_y').length, 1, 0.5) * concat_0_y_step," +
        "concat_1_y_step=20," +
        "concat_1_height=bandspace(domain('concat_1_y').length, 1, 0.5) * concat_1_y_step] " +
        "width=300,height=null | concat_0_group:concat_0_height,concat_1_group:concat_1_height",
      sizes(
        """{$rows,"config":{"view":{"discreteHeight":{"step":20}}},"vconcat":[{$plot},{$plot}]}"""
      ),
    )
  }

  /** And a plot that states a step of its own is a step in spite of the theme. */
  @Test
  fun `a stated step outranks a themed depth`() {
    assertEquals(
      "[concat_0_y_step=30," +
        "concat_0_height=bandspace(domain('concat_0_y').length, 1, 0.5) * concat_0_y_step," +
        "concat_1_y_step=30," +
        "concat_1_height=bandspace(domain('concat_1_y').length, 1, 0.5) * concat_1_y_step] " +
        "width=300,height=null | concat_0_group:concat_0_height,concat_1_group:concat_1_height",
      sizes(
        """{$rows,"config":{"view":{"height":500}},
           "vconcat":[{$plot,"height":{"step":30}},{$plot,"height":{"step":30}}]}"""
      ),
    )
  }

  /** One plot on its own is the same rule read without a merge: the themed depth, written out. */
  @Test
  fun `one strip takes the themed depth`() {
    assertEquals(
      "[] width=300,height=500 | marks:null",
      sizes("""{$rows,"config":{"view":{"height":500}},$plot}"""),
    )
    assertEquals(
      "[y_step=20,height=bandspace(domain('y').length, 1, 0.5) * y_step] width=300,height=null | " +
        "marks:null",
      sizes("""{$rows,$plot}"""),
    )
  }
}
