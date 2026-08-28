package dev.aster.vega.runtime

import dev.aster.vega.expression.VegaExpressionCompiler
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaJson
import dev.aster.vega.runtime.compile.SpecCompiler
import dev.aster.vegalite.VegaLiteCompiler
import dev.aster.vegalite.VegaLiteInput
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **Nothing throws, on input built to make something recurse.**
 *
 * Every entry point below takes text a *reader* supplied — that is the whole premise of the paste
 * screens on three platforms — and behind each is at least one recursion whose depth that text
 * decides. A `StackOverflowError` is an `Error`, so the `guarded` blocks on those entry points
 * deliberately do not catch it (an `Error` is not a failed compile), and on Kotlin/Native nothing
 * can. So every one of these recursions has to be *bounded*, not caught, and this is the test that
 * says so.
 *
 * ### Why this exists rather than one test per limit
 *
 * There are already tests for `VegaJson.MAX_JSON_DEPTH`, `Limits.MAX_VIEW_DEPTH`, `MAX_GROUP_DEPTH`
 * and the expression parser's `MAX_DEPTH`, and each pins the limit it is about. What none of them
 * does is ask the *general* question — is there some other shape of input that makes something
 * recurse? — and that question is the one that keeps being answered wrongly:
 *
 * - The JSON parser overflowed before any of those limits could run, and only on Linux, because the
 *   depth a stack survives is the host's business and not the project's.
 * - `EventSelector`'s `one`/`between` are **mutually** recursive over bracket nesting in a selector
 *   string, so nothing that looks for a function calling itself would ever have found it. A pasted
 *   Vega-Lite document with five thousand `[` in a `select.on` took the compiler down.
 *
 * So the generators below are shapes rather than cases: each one takes a depth, and the assertion
 * is only ever "this came back". A new construct that recurses is a new generator here, and the
 * call graph over the compiled classes is how the list of candidates was built — `javap -c`, Tarjan
 * over the call edges, which finds mutual recursion that no syntactic pattern can.
 *
 * ### Reading a failure
 *
 * A failure here is a `StackOverflowError` naming the recursion that ran away. Fix it by *bounding*
 * that recursion — a depth counter, or a check on the input before descending — and not by widening
 * a catch, which cannot work on every target this ships to.
 */
class DeepInputTest {

  /**
   * The depths tried, and why they go this far.
   *
   * 5,000 is roughly where a 1 MB stack gives out on the recursions in this engine, and 100,000 is
   * far past any of them: a bound that is real holds at both, and a bound that is really "the stack
   * happened to be big enough" fails at one of them. The suite pins `-Xss1m` so these numbers mean
   * the same thing on a laptop and on CI.
   */
  private val depths = listOf(500, 5_000, 100_000)

  /** Each shape: a name, and a function from depth to the text to feed in. */
  private fun shapes(): List<Pair<String, (Int) -> Unit>> =
    listOf(
      "vega: nested group marks" to
        { n: Int ->
          val marks = "[{\"type\":\"group\",\"marks\":".repeat(n) + "[]" + "}]".repeat(n)
          compileVega("""{"width":10,"height":10,"padding":0,"marks":$marks}""")
        },
      "vega: nested json arrays in data" to
        { n: Int ->
          val values = "[".repeat(n) + "1" + "]".repeat(n)
          compileVega(
            """{"width":10,"height":10,"padding":0,"data":[{"name":"t","values":$values}],
               "marks":[]}"""
          )
        },
      "vega: nested expression parentheses" to
        { n: Int ->
          val expr = "(".repeat(n) + "1" + ")".repeat(n)
          compileVega(
            """{"width":10,"height":10,"padding":0,
               "signals":[{"name":"s","update":"$expr"}],"marks":[]}"""
          )
        },
      "vega: nested event selector brackets" to
        { n: Int ->
          val selector = "[".repeat(n) + "mousedown" + "]".repeat(n)
          compileVega(
            """{"width":10,"height":10,"padding":0,
               "signals":[{"name":"s","value":0,
                 "on":[{"events":"$selector","update":"1"}]}],"marks":[]}"""
          )
        },
      "vega-lite: nested layers" to
        { n: Int ->
          val doc = "{\"layer\":[".repeat(n) + """{"mark":"point","encoding":{}}""" + "]}".repeat(n)
          compileVegaLite(doc)
        },
      "vega-lite: nested select.on brackets" to
        { n: Int ->
          val selector = "[".repeat(n) + "mousedown" + "]".repeat(n)
          compileVegaLite(
            """{"data":{"values":[{"a":1}]},"mark":"point",
               "params":[{"name":"s","select":{"type":"point","on":"$selector"}}],
               "encoding":{"x":{"field":"a","type":"quantitative"}}}"""
          )
        },
      "vega-lite: a flat run of transforms" to
        { n: Int ->
          val transforms = (1..n).joinToString(",") { """{"calculate":"1","as":"f$it"}""" }
          compileVegaLite(
            """{"mark":"point","data":{"values":[]},"transform":[$transforms],"encoding":{}}"""
          )
        },
      "json: nested objects" to
        { n: Int ->
          val doc = """{"a":""".repeat(n) + "1" + "}".repeat(n)
          VegaJson.parseOrNull(doc, DiagnosticCollector())
        },
      "expression: nested calls" to
        { n: Int ->
          VegaExpressionCompiler().compile("abs(".repeat(n) + "1" + ")".repeat(n))
        },
      "expression: nested member access" to
        { n: Int ->
          VegaExpressionCompiler().compile("datum" + ".a".repeat(n))
        },
    )

  private fun compileVega(json: String) {
    SpecCompiler().compileJson(json)
  }

  private fun compileVegaLite(json: String) {
    VegaLiteCompiler().compileJson(json)
    VegaLiteInput.toVega(json)
  }

  /**
   * Input that asks for too much **memory**, which is the same failure one resource over.
   *
   * An `OutOfMemoryError` is an `Error`, so `SpecCompiler`'s guard does not catch it for exactly
   * the reason it does not catch a `StackOverflowError` — and on Kotlin/Native neither is catchable
   * at all. So a document must not be able to ask for an allocation the heap cannot serve, and the
   * limits that stop it (`MAX_SEQUENCE`, `MAX_TICK_COUNT`, `MAX_CROSSED_CELLS`, `MAX_REPEAT_CELLS`)
   * have to hold on the *count a specification wrote*, before anything is allocated.
   *
   * `sequence` is here because it was the one that did not: three numbers from the document decided
   * the row count directly, so `{"stop": 1e9}` was an `OutOfMemoryError` about four seconds later.
   * Its own expression twin had been bounded since it was written, which is what made the gap an
   * asymmetry rather than an oversight.
   */
  @Test
  fun `no shape of oversized input throws`() {
    val escaped = mutableListOf<String>()
    val huge = listOf(1_000_000, 1_000_000_000)
    for (n in huge) {
      val cases =
        listOf(
          "sequence of $n rows" to
            """{"width":10,"height":10,"padding":0,
               "data":[{"name":"t","transform":[
                 {"type":"sequence","start":0,"stop":$n}]}],"marks":[]}""",
          "axis asking for $n ticks" to
            """{"width":10,"height":10,"padding":0,
               "data":[{"name":"t","values":[{"a":1}]}],
               "scales":[{"name":"x","type":"linear","domain":[0,1],"range":"width"}],
               "axes":[{"orient":"bottom","scale":"x","tickCount":$n}],"marks":[]}""",
        )
      for ((name, json) in cases) {
        try {
          SpecCompiler().compileJson(json)
        } catch (failure: Throwable) {
          escaped += "$name: ${failure::class.simpleName}"
        }
      }
    }
    assertTrue(
      escaped.isEmpty(),
      "input this large must come back as a diagnostic, not as a throw:\n" +
        escaped.joinToString("\n") { "  $it" },
    )
  }

  @Test
  fun `no shape of deeply nested input throws`() {
    val escaped = mutableListOf<String>()
    for ((name, build) in shapes()) {
      for (depth in depths) {
        // `Throwable`, deliberately and only here: the point of the test is to notice an `Error`
        // that production code is right not to catch.
        try {
          build(depth)
        } catch (failure: Throwable) {
          escaped += "$name at depth $depth: ${failure::class.simpleName}"
        }
      }
    }
    assertTrue(
      escaped.isEmpty(),
      "input this deep must come back as a diagnostic, not as a throw:\n" +
        escaped.joinToString("\n") { "  $it" },
    )
  }

  /**
   * And the shapes still *work* at a depth a real document reaches.
   *
   * Without this the test above is satisfied by an engine that refuses everything, which is the
   * failure mode every limit in this repository has to be guarded against — a gate that passes
   * because it checked nothing.
   */
  @Test
  fun `the same shapes still compile at an ordinary depth`() {
    val marks = "[{\"type\":\"group\",\"marks\":".repeat(3) + "[]" + "}]".repeat(3)
    assertTrue(
      SpecCompiler().compileJson("""{"width":10,"height":10,"padding":0,"marks":$marks}""").scene !=
        null,
      "three nested groups is an ordinary chart",
    )
    val layered = "{\"layer\":[".repeat(3) + """{"mark":"point","encoding":{}}""" + "]}".repeat(3)
    assertTrue(VegaLiteCompiler().compileJson(layered).vega != null, "three layers is ordinary")
    assertTrue(
      VegaExpressionCompiler().compile("abs(abs(abs(1)))")
        is dev.aster.vega.expression.ExpressionResult.Compiled,
      "three nested calls is ordinary",
    )
  }
}
