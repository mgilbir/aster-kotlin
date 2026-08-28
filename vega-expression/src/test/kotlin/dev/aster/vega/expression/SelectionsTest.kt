package dev.aster.vega.expression

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The `vlSelection*` family, against vectors taken from upstream.
 *
 * A selection is an ordinary dataset — rows of `{unit, fields, values}` — so these need a scope
 * with data in it rather than the empty one the other expression vectors use. Every expectation
 * below came from running the same expression through upstream over the same rows.
 */
class SelectionsTest {

  private val compiler = VegaExpressionCompiler()

  private fun datasets(): Map<String, List<VegaValue>> =
    mapOf(
      // Two units brushing overlapping ranges, which is what makes union and intersect differ.
      "sel" to
        rows(
          """[{"unit": "u1", "fields": [{"field": "v", "type": "R"}], "values": [[2, 6]]},
              {"unit": "u2", "fields": [{"field": "v", "type": "R"}], "values": [[5, 9]]}]"""
        ),
      // An enumerated selection, where one unit lists a single value and the other an array of
      // them.
      "enums" to
        rows(
          """[{"unit": "u1", "fields": [{"field": "c", "type": "E"}], "values": ["a"]},
              {"unit": "u2", "fields": [{"field": "c", "type": "E"}], "values": [["b", "c"]]}]"""
        ),
      // `R-RE` is the binned form: the upper edge belongs to the next bin.
      "bins" to
        rows(
          """[{"unit": "u1", "fields": [{"field": "v", "type": "R-RE"}], "values": [[2, 6]]}]"""
        ),
      "ids" to
        rows(
          """[{"unit": "u1", "_vgsid_": 3}, {"unit": "u2", "_vgsid_": 3},
              {"unit": "u1", "_vgsid_": 5}]"""
        ),
    )

  private fun rows(json: String): List<VegaValue> = (VegaJson.parse(json) as VegaValue.Arr).values

  private fun evaluate(source: String): String {
    val result = compiler.compile(source)
    check(result is ExpressionResult.Compiled) { "failed to parse '$source': $result" }
    val data = datasets()
    val scope =
      object : ExpressionScope {
        override val datum: VegaValue = VegaValue.EmptyObject

        override fun signal(name: String): VegaValue = VegaValue.Null

        override fun dataset(name: String): List<VegaValue> = data[name] ?: emptyList()
      }
    return asJson(result.expression.evaluate(scope))
  }

  /**
   * Rendered the way `JSON.stringify` renders it, so the expectations are the oracle's own output.
   *
   * A local copy of the rule `ExpressionReferenceTest` uses, because these vectors need a scope
   * with datasets in it and that test's is deliberately empty.
   */
  private fun asJson(value: VegaValue): String =
    when (value) {
      is VegaValue.Null -> "null"
      // `eval-probe.js` writes the literal `undefined` where `JSON.stringify` gives nothing.
      is VegaValue.Undefined -> "undefined"
      is VegaValue.Bool -> value.value.toString()
      is VegaValue.Num ->
        if (!value.value.isFinite()) "null" else JsSemantics.numberToString(value.value)
      is VegaValue.Timestamp -> JsSemantics.numberToString(value.epochMillis)
      is VegaValue.Str -> "\"${value.value}\""
      is VegaValue.Arr -> value.values.joinToString(",", "[", "]") { asJson(it) }
      is VegaValue.Obj ->
        value.fields.entries.joinToString(",", "{", "}") { "\"${it.key}\":${asJson(it.value)}" }
      // `JSON.stringify(/a/)` is `{}` — a RegExp has no enumerable properties.
      is VegaValue.Pattern -> "{}"
    }

  /**
   * `isTuple` answered from **origin**, which is what upstream's row identity records.
   *
   * Upstream marks every tuple its dataflow produces with an id under a Symbol and looks for it.
   * Attaching an id to every row here would cost the value model its equality and its serialisation
   * for one predicate, so the question is answered from where the value *came from* instead:
   * `datum`, anything reached through it, an element of `data('name')`, and an item's `.datum` are
   * tuples; literals and scalars are not. Each of these was probed against upstream and each
   * agrees.
   *
   * The edge, stated: a value **laundered through a signal** reads false here whatever it holds. A
   * signal carrying a dataset row is not something a specification writes, and a signal carrying an
   * object literal — which is what one does write — is false on both sides.
   */
  @Test
  fun `isTuple tells a row of data from an object written down`() {
    assertEquals("true", evaluate("""isTuple(datum)"""))
    assertEquals("true", evaluate("""isTuple(data('sel')[0])"""))
    assertEquals("false", evaluate("""isTuple({a: 1})"""))
    assertEquals("false", evaluate("""isTuple(5)"""))
    assertEquals("false", evaluate("""isTuple(null)"""))
    assertEquals("false", evaluate("""isTuple([1, 2])"""))
  }

  @Test
  fun `a range selection unions across units and intersects on request`() {
    assertEquals("true", evaluate("""vlSelectionTest('sel', {v: 3})"""))
    assertEquals("false", evaluate("""vlSelectionTest('sel', {v: 3}, 'intersect')"""))
    assertEquals("true", evaluate("""vlSelectionTest('sel', {v: 5.5}, 'intersect')"""))
    assertEquals("false", evaluate("""vlSelectionTest('sel', {v: 99})"""))
  }

  @Test
  fun `an enumerated selection takes one value or an array of them`() {
    assertEquals("true", evaluate("""vlSelectionTest('enums', {c: 'b'})"""))
    assertEquals("false", evaluate("""vlSelectionTest('enums', {c: 'z'})"""))
  }

  /** The whole point of `R-RE`: a value on the upper edge belongs to the *next* bin. */
  @Test
  fun `a binned selection excludes its upper edge`() {
    assertEquals("false", evaluate("""vlSelectionTest('bins', {v: 6})"""))
    assertEquals("true", evaluate("""vlSelectionTest('bins', {v: 2})"""))
  }

  @Test
  fun `resolving widens for a union and narrows for an intersection`() {
    assertEquals("""{"v":[2,9]}""", evaluate("""vlSelectionResolve('sel')"""))
    assertEquals("""{"v":[5,6]}""", evaluate("""vlSelectionResolve('sel', 'intersect')"""))
    assertEquals("""{"c":["a","b","c"]}""", evaluate("""vlSelectionResolve('enums')"""))
  }

  /**
   * An id selection matches by `_vgsid_`, and intersecting means every unit must have chosen it.
   *
   * The one **stated difference** is in `vlSelectionResolve` over such a store: upstream builds its
   * answer with `d3.union`, which is a `Set`, and a `Set` serializes as `{}` — so upstream's own
   * value is opaque to anything that reads it back. This returns the ids as an array, which carries
   * the same membership and can be read.
   */
  @Test
  fun `an id selection matches by _vgsid_`() {
    assertEquals("true", evaluate("""vlSelectionIdTest('ids', {_vgsid_: 3})"""))
    assertEquals("true", evaluate("""vlSelectionIdTest('ids', {_vgsid_: 3}, 'intersect')"""))
    assertEquals("false", evaluate("""vlSelectionIdTest('ids', {_vgsid_: 5}, 'intersect')"""))
    assertEquals("""{"_vgsid_":[3,5]}""", evaluate("""vlSelectionResolve('ids')"""))
  }
}
