package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A window over the **whole partition**, computing nothing a window alone can compute, is a
 * join-aggregate.
 *
 * ```js
 * if (frame && frame[0] === null && frame[1] === null && ops.every((o) => isAggregateOp(o))) {
 *   // when the window does not rely on any particular window ops or frame, switch to a simpler and
 *   // more efficient joinaggregate
 *   return {type: 'joinaggregate', as, ops, fields, ...(groupby !== undefined ? {groupby} : {})};
 * }
 * ```
 *
 * Every row of the partition gets the same answer, and Vega has a transform that says exactly that.
 * `[null, null]` is how a specification asks for the whole partition — the commonest window there
 * is, *this row against the median of all of them* — and this compiler wrote a window transform
 * instead, carrying a `sort`, a `frame` and a list of nulls for parameters no operation here takes.
 * Three specifications in the wild corpus ask for it.
 *
 * A stated `sort` changes nothing: over the whole partition there is nothing for an order to do. An
 * operation only a window can compute, a one-sided frame, or no frame at all leaves it a window.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class WindowJoinAggregateTest {

  private fun transforms(transform: String): VegaValue {
    val spec =
      """{"data":{"values":[{"a":1,"c":"x"}]},"transform":[$transform],"mark":"bar",
         "encoding":{"x":{"field":"a","type":"quantitative"},
                     "y":{"field":"c","type":"nominal"}}}"""
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    val found =
      (compiled.fields["data"] as VegaValue.Arr)
        .values
        .flatMap {
          (it as VegaValue.Obj).fields["transform"]?.let { t -> (t as VegaValue.Arr).values }
            ?: emptyList()
        }
        .filter { (it as VegaValue.Obj).string("type") in setOf("window", "joinaggregate") }
    return VegaValue.Arr(found)
  }

  private fun json(text: String) = VegaJson.parse(text)

  /** The reported shape: every row against the median of all of them. */
  @Test
  fun `a median over the whole partition is a join-aggregate`() {
    assertEquals(
      json("""[{"type":"joinaggregate","as":["m"],"ops":["median"],"fields":["a"]}]"""),
      transforms("""{"window":[{"op":"median","field":"a","as":"m"}],"frame":[null,null]}"""),
    )
  }

  /** The grouping comes across, a partition being what it groups by. */
  @Test
  fun `a grouped window keeps its grouping`() {
    assertEquals(
      json(
        """[{"type":"joinaggregate","as":["m"],"ops":["median"],"fields":["a"],"groupby":["c"]}]"""
      ),
      transforms(
        """{"window":[{"op":"median","field":"a","as":"m"}],"frame":[null,null],"groupby":["c"]}"""
      ),
    )
  }

  /** A stated order changes nothing: over the whole partition there is nothing for one to do. */
  @Test
  fun `a stated sort does not keep it a window`() {
    assertEquals(
      json("""[{"type":"joinaggregate","as":["m"],"ops":["median"],"fields":["a"]}]"""),
      transforms(
        """{"window":[{"op":"median","field":"a","as":"m"}],"frame":[null,null],
           "sort":[{"field":"a"}]}"""
      ),
    )
  }

  /** An operation only a window can compute stays a window. */
  @Test
  fun `a rank over the whole partition stays a window`() {
    assertEquals(
      json(
        """[{"type":"window","params":[null],"as":["r"],"ops":["rank"],"fields":[null],
            "sort":{"field":[],"order":[]},"frame":[null,null]}]"""
      ),
      transforms("""{"window":[{"op":"rank","as":"r"}],"frame":[null,null]}"""),
    )
  }

  /** And so does one aggregate beside one of those. */
  @Test
  fun `an aggregate beside a window operation stays a window`() {
    assertEquals(
      json(
        """[{"type":"window","params":[null,null],"as":["m","r"],"ops":["median","rank"],
            "fields":["a",null],"sort":{"field":[],"order":[]},"frame":[null,null]}]"""
      ),
      transforms(
        """{"window":[{"op":"median","field":"a","as":"m"},{"op":"rank","as":"r"}],
           "frame":[null,null]}"""
      ),
    )
  }

  /** A frame is what asks for the whole partition, so a window without one is still a window. */
  @Test
  fun `a window with no frame stays a window`() {
    assertEquals(
      json(
        """[{"type":"window","params":[null],"as":["m"],"ops":["median"],"fields":["a"],
            "sort":{"field":[],"order":[]}}]"""
      ),
      transforms("""{"window":[{"op":"median","field":"a","as":"m"}]}"""),
    )
  }

  /** A one-sided frame is a *running* answer, which is the thing only a window computes. */
  @Test
  fun `a one-sided frame stays a window`() {
    assertEquals(
      json(
        """[{"type":"window","params":[null],"as":["m"],"ops":["median"],"fields":["a"],
            "sort":{"field":[],"order":[]},"frame":[null,0]}]"""
      ),
      transforms("""{"window":[{"op":"median","field":"a","as":"m"}],"frame":[null,0]}"""),
    )
  }
}
