package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A stated colour domain orders the stack, and the question of *whether it stacks* is asked of the
 * stack.
 *
 * `alignStackOrderWithColorDomain` in `unit.ts` adds a `_«field»_sort_index` column and points the
 * `order` channel at it, so a chart that lists its colour domain is drawn in that order rather than
 * merely having its legend in it.
 *
 * Three things here were reimplemented rather than ported, and each was wrong:
 *
 * 1. **Whether the view stacks.** Upstream tests `this.stack`, the properties its own stack code
 *    computed. This approximated it as "a quantitative position that aggregates", which misses a
 *    chart that stacks because it *said* `stack: true`. 62 charts in the wild corpus differed by
 *    exactly the one formula that omission costs.
 * 2. **An offset that already has a `sort`.** Upstream's `else` reaches the stack branch; this
 *    returned outright. No observable difference has been found for it — a chart dodged by an
 *    offset channel does not stack, so the branch returns either way — and it is aligned anyway,
 *    because a rule that agrees by accident stops agreeing the moment something else changes.
 * 3. **Which way round.** Upstream reads the *resolved* `markDef.orient`, which `initMarkDef` has
 *    already inferred from the encoding. Reading the stated value instead ordered a horizontal
 *    stack backwards — caught by the 627-example gallery before it reached the corpus.
 */
class StackOrderFromColorDomainTest {

  private fun compiled(json: String): VegaValue.Obj =
    VegaJson.parse(
      requireNotNull(VegaLiteCompiler().compileJson(json).toJson()) { "did not compile" }
    ) as VegaValue.Obj

  /** Every `formula` in the compiled specification, as `as` to `expr`. */
  private fun formulas(spec: VegaValue.Obj): Map<String, String> =
    (spec.fields["data"] as? VegaValue.Arr)
      ?.values
      .orEmpty()
      .flatMap {
        (it as? VegaValue.Obj)
          ?.let { d -> (d.fields["transform"] as? VegaValue.Arr)?.values.orEmpty() }
          .orEmpty()
      }
      .mapNotNull { it as? VegaValue.Obj }
      .filter { (it.fields["type"] as? VegaValue.Str)?.value == "formula" }
      .mapNotNull { t ->
        val name = (t.fields["as"] as? VegaValue.Str)?.value ?: return@mapNotNull null
        name to ((t.fields["expr"] as? VegaValue.Str)?.value ?: "")
      }
      .toMap()

  /** The `sort` of the `stack` transform, which is where the ordering actually lands. */
  private fun stackSort(spec: VegaValue.Obj): VegaValue.Obj? =
    (spec.fields["data"] as? VegaValue.Arr)
      ?.values
      .orEmpty()
      .flatMap {
        (it as? VegaValue.Obj)
          ?.let { d -> (d.fields["transform"] as? VegaValue.Arr)?.values.orEmpty() }
          .orEmpty()
      }
      .mapNotNull { it as? VegaValue.Obj }
      .firstOrNull { (it.fields["type"] as? VegaValue.Str)?.value == "stack" }
      ?.let { it.fields["sort"] as? VegaValue.Obj }

  private fun firstOrder(spec: VegaValue.Obj): String? =
    ((stackSort(spec)?.fields?.get("order") as? VegaValue.Arr)?.values?.firstOrNull()
        as? VegaValue.Str)
      ?.value

  /**
   * The reported case: it stacks because the specification says so, with no aggregate anywhere.
   *
   * This is the shape our approximation missed — and the smallest chart in the corpus whose only
   * disagreement with upstream was the missing formula.
   */
  @Test
  fun `a chart that stacks by saying so is ordered by the colour domain`() {
    val spec =
      compiled(
        """
        {"data":{"values":[{"year":2000,"party":"dem","total":1}]},
         "mark":"area",
         "encoding":{
           "x":{"field":"year","type":"quantitative"},
           "y":{"field":"total","type":"quantitative","stack":true},
           "color":{"field":"party","type":"nominal",
                    "scale":{"domain":["dem","rep","other","decline"]}}}}
        """
      )
    assertEquals(
      "indexof([\"dem\",\"rep\",\"other\",\"decline\"], datum['party'])",
      formulas(spec)["_party_sort_index"],
      "the stack order has to be computed even with nothing aggregated",
    )
  }

  /** And a chart that does not stack at all gets no such column. */
  @Test
  fun `a chart that does not stack is left alone`() {
    val spec =
      compiled(
        """
        {"data":{"values":[{"a":1,"b":2,"party":"dem"}]},
         "mark":"point",
         "encoding":{
           "x":{"field":"a","type":"quantitative"},
           "y":{"field":"b","type":"quantitative"},
           "color":{"field":"party","type":"nominal","scale":{"domain":["dem","rep"]}}}}
        """
      )
    assertNull(formulas(spec)["_party_sort_index"], "there is no stack whose order this could be")
  }

  /**
   * A **vertical** stack counts down, so the first listed colour sits at the bottom.
   *
   * The orientation is the resolved one: neither of these charts states `orient`.
   */
  @Test
  fun `a vertical stack is ordered descending`() {
    val spec =
      compiled(
        """
        {"data":{"values":[{"c":"a","v":1,"party":"dem"}]},
         "mark":"bar",
         "encoding":{
           "x":{"field":"c","type":"nominal"},
           "y":{"field":"v","type":"quantitative","aggregate":"sum"},
           "color":{"field":"party","type":"nominal","scale":{"domain":["dem","rep"]}}}}
        """
      )
    assertEquals("descending", firstOrder(spec))
  }

  /** A **horizontal** one counts up. Reading the stated `orient` instead got this backwards. */
  @Test
  fun `a horizontal stack is ordered ascending`() {
    val spec =
      compiled(
        """
        {"data":{"values":[{"c":"a","v":1,"party":"dem"}]},
         "mark":"bar",
         "encoding":{
           "y":{"field":"c","type":"nominal"},
           "x":{"field":"v","type":"quantitative","aggregate":"sum"},
           "color":{"field":"party","type":"nominal","scale":{"domain":["dem","rep"]}}}}
        """
      )
    assertEquals(
      "ascending",
      firstOrder(spec),
      "the mark states no orient, so the one inferred from the encoding decides",
    )
  }

  /**
   * A grouped chart whose offset already states a `sort` gets **no stack order at all**.
   *
   * Upstream's `else` does reach the stack branch here — the offset has a sort, so the first arm is
   * skipped — and the branch then returns on `if (!this.stack)`, because a chart dodged by an
   * offset channel does not stack. Checked against upstream rather than reasoned about: following
   * the code path suggested a `_party_sort_index` column, and upstream emits none.
   */
  @Test
  fun `a dodged chart whose offset states a sort gets no stack order`() {
    val spec =
      compiled(
        """
        {"data":{"values":[{"c":"a","v":1,"party":"dem"}]},
         "mark":"bar",
         "encoding":{
           "x":{"field":"c","type":"nominal"},
           "xOffset":{"field":"party","type":"nominal","sort":["rep","dem"]},
           "y":{"field":"v","type":"quantitative","aggregate":"sum"},
           "color":{"field":"party","type":"nominal","scale":{"domain":["dem","rep"]}}}}
        """
      )
    assertNull(
      formulas(spec)["_party_sort_index"],
      "the branch is reached and then returns: a dodged chart has no stack to order",
    )
    assertNull(stackSort(spec), "and there is no stack transform to carry an order")
  }

  /** A chart with its own `order` channel has said what it wants and is not second-guessed. */
  @Test
  fun `a stated order channel wins`() {
    val spec =
      compiled(
        """
        {"data":{"values":[{"c":"a","v":1,"party":"dem"}]},
         "mark":"bar",
         "encoding":{
           "x":{"field":"c","type":"nominal"},
           "y":{"field":"v","type":"quantitative","aggregate":"sum"},
           "order":{"field":"v","type":"quantitative"},
           "color":{"field":"party","type":"nominal","scale":{"domain":["dem","rep"]}}}}
        """
      )
    assertNull(formulas(spec)["_party_sort_index"])
  }
}
