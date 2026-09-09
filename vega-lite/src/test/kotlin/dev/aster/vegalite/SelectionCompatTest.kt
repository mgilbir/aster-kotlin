package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Vega-Lite 4's `selection` becomes 5's `params`, shape by shape.
 *
 * Each case is the *normalised tree*, not the compiled chart, because that is what the pass is
 * responsible for: everything downstream is already tested against upstream on 627 examples, and a
 * test that compiled the whole chart would pass or fail for reasons far from here.
 *
 * The expectations are upstream's, read off `normalize/selectioncompat.ts` after an experiment
 * against its compiler had suggested the wrong answer for two of them — see [SelectionCompat].
 */
class SelectionCompatTest {

  private fun normalized(json: String): VegaValue.Obj =
    SelectionCompat.normalize(VegaJson.parse(json) as VegaValue.Obj)

  private fun params(spec: VegaValue.Obj): List<VegaValue.Obj> =
    (spec.fields["params"] as? VegaValue.Arr)?.values?.mapNotNull { it as? VegaValue.Obj }.orEmpty()

  private fun path(spec: VegaValue?, vararg keys: Any): VegaValue? {
    var here: VegaValue? = spec
    for (key in keys) {
      here =
        when (key) {
          is String -> (here as? VegaValue.Obj)?.fields?.get(key)
          is Int -> (here as? VegaValue.Arr)?.values?.getOrNull(key)
          else -> null
        }
    }
    return here
  }

  @Test
  fun `a single selection becomes a point parameter that does not toggle`() {
    val spec = normalized("""{"mark":"point","selection":{"s":{"type":"single"}}}""")
    assertNull(spec.fields["selection"], "the v4 spelling should be gone")
    val param = params(spec).single()
    assertEquals(VegaValue.Str("s"), param.fields["name"])
    assertEquals(VegaValue.Str("point"), path(param, "select", "type"))
    assertEquals(
      VegaValue.Bool(false),
      path(param, "select", "toggle"),
      "`single` is a point selection that a second click does not add to",
    )
  }

  @Test
  fun `a multi selection becomes a point parameter that does`() {
    val param =
      params(normalized("""{"mark":"point","selection":{"s":{"type":"multi"}}}""")).single()
    assertEquals(VegaValue.Str("point"), path(param, "select", "type"))
    assertNull(path(param, "select", "toggle"), "`multi` leaves toggle at its default")
  }

  @Test
  fun `interval keeps its type`() {
    val param =
      params(normalized("""{"mark":"point","selection":{"s":{"type":"interval"}}}""")).single()
    assertEquals(VegaValue.Str("interval"), path(param, "select", "type"))
  }

  /**
   * `init` becomes the parameter's `value` and `bind` stays on the parameter — but **`empty` goes
   * nowhere near `select`**. It is a property of the predicates that test the selection.
   */
  @Test
  fun `init bind and empty leave the select block`() {
    val param =
      params(
          normalized(
            """{"mark":"point","selection":{"s":{"type":"interval","init":{"a":1},
               "bind":"scales","empty":"none","encodings":["x"]}}}"""
          )
        )
        .single()
    assertEquals(VegaValue.Str("scales"), param.fields["bind"], "bind belongs to the parameter")
    assertTrue(param.fields["value"] is VegaValue.Obj, "init becomes the parameter's value")
    val select = param.fields["select"] as VegaValue.Obj
    assertNull(select.fields["init"])
    assertNull(select.fields["bind"])
    assertNull(select.fields["empty"], "empty is not a select option; it travels to the predicates")
    assertTrue(select.fields["encodings"] is VegaValue.Arr, "everything else stays in select")
  }

  @Test
  fun `a filter predicate becomes a param predicate`() {
    val spec =
      normalized(
        """{"mark":"point","selection":{"s":{"type":"interval"}},
           "transform":[{"filter":{"selection":"s"}}]}"""
      )
    val filter = path(spec, "transform", 0, "filter")
    assertEquals(VegaValue.Str("s"), path(filter, "param"))
    assertEquals(VegaValue.Bool(true), path(filter, "empty"), "no `empty: none`, so empty is true")
  }

  /** `empty: 'none'` on the definition is what the predicate's `empty: false` comes from. */
  @Test
  fun `emptiness travels from the definition to the predicate`() {
    val spec =
      normalized(
        """{"mark":"point","selection":{"s":{"type":"interval","empty":"none"}},
           "transform":[{"filter":{"selection":"s"}}]}"""
      )
    assertEquals(VegaValue.Bool(false), path(spec, "transform", 0, "filter", "empty"))
  }

  /**
   * And it travels **backwards**: a predicate may be written above the definition it tests.
   *
   * Upstream back-patches predicates it has already emitted; this pass collects every definition
   * first, which reaches the same answer without mutating a tree.
   */
  @Test
  fun `emptiness reaches a predicate written before the definition`() {
    val spec =
      normalized(
        """{"layer":[
             {"mark":"point","transform":[{"filter":{"selection":"s"}}]},
             {"mark":"point","selection":{"s":{"type":"interval","empty":"none"}}}
           ]}"""
      )
    assertEquals(
      VegaValue.Bool(false),
      path(spec, "layer", 0, "transform", 0, "filter", "empty"),
      "the definition is in the second layer, and the predicate is in the first",
    )
  }

  /** A reference to a name nothing defines is `empty: true`, which is upstream's `?? true`. */
  @Test
  fun `an undefined selection name still yields a predicate`() {
    val spec = normalized("""{"mark":"point","transform":[{"filter":{"selection":"ghost"}}]}""")
    assertEquals(VegaValue.Str("ghost"), path(spec, "transform", 0, "filter", "param"))
    assertEquals(VegaValue.Bool(true), path(spec, "transform", 0, "filter", "empty"))
  }

  /**
   * A condition becomes a **`test`**, not a `param`.
   *
   * The shape a straight rename gets wrong, and the reason this pass is a port rather than an
   * interpretation.
   */
  @Test
  fun `an encoding condition becomes a test predicate`() {
    val spec =
      normalized(
        """{"mark":"point","selection":{"s":{"type":"interval"}},
           "encoding":{"color":{"condition":{"selection":"s","value":"red"},"value":"grey"}}}"""
      )
    val condition = path(spec, "encoding", "color", "condition")
    assertNull(path(condition, "param"), "a v4 condition does not become a param condition")
    assertNull(path(condition, "selection"), "and the old key is gone")
    assertEquals(VegaValue.Str("s"), path(condition, "test", "param"))
    assertEquals(VegaValue.Str("red"), path(condition, "value"), "the rest of the entry survives")
  }

  @Test
  fun `a condition already written with param is left alone`() {
    val spec =
      normalized(
        """{"mark":"point","params":[{"name":"s","select":"interval"}],
           "encoding":{"color":{"condition":{"param":"s","value":"red"},"value":"grey"}}}"""
      )
    assertEquals(VegaValue.Str("s"), path(spec, "encoding", "color", "condition", "param"))
    assertNull(path(spec, "encoding", "color", "condition", "test"))
  }

  @Test
  fun `a logical composition of selection names is walked`() {
    val spec =
      normalized(
        """{"mark":"point",
           "selection":{"a":{"type":"interval"},"b":{"type":"interval","empty":"none"}},
           "transform":[{"filter":{"selection":{"and":["a",{"not":"b"}]}}}]}"""
      )
    val and = path(spec, "transform", 0, "filter", "and") as VegaValue.Arr
    assertEquals(VegaValue.Str("a"), path(and.values[0], "param"))
    assertEquals(VegaValue.Bool(true), path(and.values[0], "empty"))
    assertEquals(VegaValue.Str("b"), path(and.values[1], "not", "param"))
    assertEquals(
      VegaValue.Bool(false),
      path(and.values[1], "not", "empty"),
      "each leaf carries the emptiness of the selection it names",
    )
  }

  @Test
  fun `a bin extent and a lookup source and a scale domain are renamed`() {
    val spec =
      normalized(
        """{"mark":"point","selection":{"s":{"type":"interval"}},
           "transform":[{"bin":{"extent":{"selection":"s"}},"field":"a","as":"b"},
                        {"lookup":"a","from":{"selection":"s","key":"k","fields":["f"]}}],
           "encoding":{"x":{"field":"a","type":"quantitative",
                            "scale":{"domain":{"selection":"s"}}}}}"""
      )
    assertEquals(VegaValue.Str("s"), path(spec, "transform", 0, "bin", "extent", "param"))
    assertEquals(VegaValue.Str("s"), path(spec, "transform", 1, "from", "param"))
    assertEquals(
      VegaValue.Str("k"),
      path(spec, "transform", 1, "from", "key"),
      "the rest of a lookup's `from` survives the rename",
    )
    assertEquals(VegaValue.Str("s"), path(spec, "encoding", "x", "scale", "domain", "param"))
  }

  /** An ordinary expression filter is not a selection and must come through untouched. */
  @Test
  fun `an expression filter is left exactly as it was`() {
    val spec = normalized("""{"mark":"point","transform":[{"filter":"datum.a > 1"}]}""")
    assertEquals(VegaValue.Str("datum.a > 1"), path(spec, "transform", 0, "filter"))
  }

  @Test
  fun `a selection on a unit inside a layer stays on that unit`() {
    val spec =
      normalized(
        """{"layer":[{"mark":"point","selection":{"s":{"type":"interval"}}},{"mark":"line"}]}"""
      )
    assertNull(spec.fields["params"], "it does not float up to the chart")
    assertEquals(VegaValue.Str("s"), path(spec, "layer", 0, "params", 0, "name"))
    assertNull(path(spec, "layer", 1, "params"), "nor sideways onto the other member")
  }

  @Test
  fun `an existing params array is kept alongside a converted selection`() {
    val spec =
      normalized(
        """{"mark":"point","params":[{"name":"p","expr":"1"}],
           "selection":{"s":{"type":"interval"}}}"""
      )
    assertEquals(listOf("p", "s"), params(spec).map { (it.fields["name"] as VegaValue.Str).value })
  }

  /** A specification with no `selection` anywhere is not rebuilt at all. */
  @Test
  fun `a specification without the v4 spelling is left alone`() {
    val json = """{"mark":"point","params":[{"name":"s","select":"interval"}]}"""
    val spec = VegaJson.parse(json) as VegaValue.Obj
    assertTrue(!SelectionCompat.applies(spec), "nothing to do, so nothing is walked")
  }
}
