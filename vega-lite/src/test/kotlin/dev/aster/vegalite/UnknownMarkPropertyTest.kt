package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A mark definition is read by asking it for the properties Vega has, not by copying the ones it
 * happens to hold.
 *
 * `markDefProperties` in `compile/mark/encode/base.ts` walks `VG_MARK_CONFIGS` and asks the mark
 * for each entry. Nothing else it holds is looked at, so a word Vega has no mark channel for cannot
 * reach the encode block. This engine iterated the *mark's* own keys instead and skipped a list of
 * known Vega-Lite-only ones — which means anything not on that list went out verbatim.
 *
 * The wild corpus is full of specifications written by hand, and a misspelling is what found this:
 * `"fontsize": 7.5`, which is not a Vega-Lite property — the real one is `fontSize`. Upstream
 * ignores it and this engine emitted `"fontsize": {"value": 7.5}` into a Vega mark, where it is
 * equally meaningless. A denylist cannot be completed, because the thing it has to exclude is every
 * word nobody has written yet.
 */
class UnknownMarkPropertyTest {

  private fun compiled(json: String): VegaValue.Obj =
    VegaJson.parse(
      requireNotNull(VegaLiteCompiler().compileJson(json).toJson()) { "did not compile" }
    ) as VegaValue.Obj

  /** The `encode.update` block of the first mark, which is where a mark property lands. */
  private fun update(spec: VegaValue.Obj): VegaValue.Obj {
    val marks = (spec.fields["marks"] as? VegaValue.Arr)?.values.orEmpty()
    val mark = marks.mapNotNull { it as? VegaValue.Obj }.first()
    val encode = mark.fields["encode"] as? VegaValue.Obj
    return (encode?.fields?.get("update") as? VegaValue.Obj) ?: VegaValue.EmptyObject
  }

  private fun textMark(property: String, value: String) =
    compiled(
      """
      {"data":{"values":[{"a":1}]},
       "mark":{"type":"text","$property":$value},
       "encoding":{"x":{"field":"a","type":"quantitative"}}}
      """
    )

  /** The reported shape: a lowercase `s`, and Vega has no `fontsize`. */
  @Test
  fun `a misspelled property does not reach the mark`() {
    assertNull(
      update(textMark("fontsize", "7.5")).fields["fontsize"],
      "Vega has no `fontsize` channel, so upstream never asks the mark for one",
    )
  }

  /** Spelled as Vega-Lite actually spells it, it goes out — the filter is not a blanket refusal. */
  @Test
  fun `the property it was a misspelling of still reaches the mark`() {
    assertEquals(
      VegaValue.Obj(linkedMapOf("value" to VegaValue.Num(7.5))),
      update(textMark("fontSize", "7.5")).fields["fontSize"],
    )
  }

  /**
   * A property from some future version is dropped for the same reason as a typo, which is the
   * point of asking rather than copying: neither has to be listed anywhere.
   */
  @Test
  fun `a property this version has never heard of is dropped`() {
    val update = update(textMark("someLaterVegaLiteWord", """"green""""))
    assertNull(update.fields["someLaterVegaLiteWord"])
  }

  /**
   * `theta2` is a Vega-Lite word with no Vega mark channel of its own — it is written out as
   * `endAngle` by the code that resolves polar bounds. On a mark that has no polar bounds it is
   * simply not a property, and passing it through put a channel Vega does not have on a text mark.
   */
  @Test
  fun `a polar bound on a mark without polar bounds is dropped`() {
    assertNull(update(textMark("theta2", "1.5")).fields["theta2"])
  }

  /** And the ordinary case is untouched: a valid property on a valid mark still arrives. */
  @Test
  fun `an ordinary mark property is unaffected`() {
    val update =
      update(
        compiled(
          """
          {"data":{"values":[{"a":1}]},
           "mark":{"type":"point","strokeWidth":3,"cursor":"crosshair"},
           "encoding":{"x":{"field":"a","type":"quantitative"}}}
          """
        )
      )
    assertEquals(
      VegaValue.Obj(linkedMapOf("value" to VegaValue.Num(3.0))),
      update.fields["strokeWidth"],
    )
    assertEquals(
      VegaValue.Obj(linkedMapOf("value" to VegaValue.Str("crosshair"))),
      update.fields["cursor"],
    )
  }
}
