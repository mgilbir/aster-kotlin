package dev.aster.vega.dataflow.transform

import dev.aster.vega.expression.CachingExpressionCompiler
import dev.aster.vega.expression.ExpressionCompiler
import dev.aster.vega.expression.ExpressionScope
import dev.aster.vega.expression.JsSemantics
import dev.aster.vega.expression.VegaExpressionCompiler
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Reference vectors for the data transforms, every expected value produced by running the same
 * pipeline through upstream Vega (`oracle-js/src/transform-probe.js`).
 *
 * Several of these contradicted a first, plausible implementation and are called out where they
 * did. Note also that upstream *mutates* the tuples it is given, which is why the probe deep-copies
 * its input per run — the transforms here copy instead, so a pipeline cannot contaminate its own
 * input.
 */
class TransformReferenceTest {

  private val pipeline = TransformPipeline()

  /** The probe's dataset, including a null so missing-value handling is always exercised. */
  private val base =
    """
    [{"c": "a", "g": "x", "v": 1},
     {"c": "b", "g": "x", "v": 4},
     {"c": "c", "g": "y", "v": 9},
     {"c": "d", "g": "y", "v": 16},
     {"c": "e", "g": "y", "v": null}]
    """
      .trimIndent()

  private class TestContext : TransformContext {
    override val diagnostics: DiagnosticCollector = DiagnosticCollector()
    override val expressions: ExpressionCompiler =
      CachingExpressionCompiler(VegaExpressionCompiler())
    val signals = LinkedHashMap<String, VegaValue>()

    override val scope: ExpressionScope = scopeFor(VegaValue.Null)

    override fun setSignal(name: String, value: VegaValue) {
      signals[name] = value
    }

    override fun scopeFor(datum: VegaValue): ExpressionScope =
      object : ExpressionScope {
        override val datum: VegaValue = datum

        override fun signal(name: String): VegaValue = signals[name] ?: VegaValue.Null

        override fun dataset(name: String): List<VegaValue> = emptyList()
      }
  }

  private var context = TestContext()

  /**
   * Runs a pipeline and renders the result as sorted-key JSON, matching the probe's output form.
   */
  private fun run(transforms: String, data: String = base): String {
    context = TestContext()
    val input = (VegaJson.parse(data) as VegaValue.Arr).values
    val definitions = (VegaJson.parse(transforms) as VegaValue.Arr).values
    val output = pipeline.run(input, definitions, context)
    return output.joinToString(",", "[", "]") { asJson(it) }
  }

  private fun asJson(value: VegaValue): String =
    when (value) {
      is VegaValue.Null -> "null"
      is VegaValue.Bool -> value.value.toString()
      is VegaValue.Num ->
        if (!value.value.isFinite()) "null" else JsSemantics.numberToString(value.value)
      is VegaValue.Timestamp -> JsSemantics.numberToString(value.epochMillis)
      is VegaValue.Str -> "\"${value.value}\""
      is VegaValue.Arr -> value.values.joinToString(",", "[", "]") { asJson(it) }
      is VegaValue.Obj ->
        value.fields.entries
          .sortedBy { it.key }
          .joinToString(",", "{", "}") { "\"${it.key}\":${asJson(it.value)}" }
    }

  // ---- filter, formula ------------------------------------------------------

  @Test
  fun filter() {
    assertEquals(
      """[{"c":"b","g":"x","v":4},{"c":"c","g":"y","v":9},{"c":"d","g":"y","v":16}]""",
      run("""[{"type": "filter", "expr": "datum.v > 3"}]"""),
    )
  }

  @Test
  fun `filter with isValid drops the null row`() {
    assertEquals(
      """[{"c":"a","g":"x","v":1},{"c":"b","g":"x","v":4},{"c":"c","g":"y","v":9},{"c":"d","g":"y","v":16}]""",
      run("""[{"type": "filter", "expr": "isValid(datum.v)"}]"""),
    )
  }

  @Test
  fun `formula on a null yields zero because null coerces to zero`() {
    // Upstream gives double = 0 for the null row, since null * 2 is 0 in JavaScript.
    assertEquals(
      """[{"c":"a","double":2,"g":"x","v":1},{"c":"b","double":8,"g":"x","v":4},""" +
        """{"c":"c","double":18,"g":"y","v":9},{"c":"d","double":32,"g":"y","v":16},""" +
        """{"c":"e","double":0,"g":"y","v":null}]""",
      run("""[{"type": "formula", "expr": "datum.v * 2", "as": "double"}]"""),
    )
  }

  // ---- collect --------------------------------------------------------------

  @Test
  fun `collect sorts missing values first in ascending order`() {
    // The opposite of the SQL convention, and verified against upstream.
    assertEquals(
      """[{"c":"e","g":"y","v":null},{"c":"a","g":"x","v":1},{"c":"b","g":"x","v":4},""" +
        """{"c":"c","g":"y","v":9},{"c":"d","g":"y","v":16}]""",
      run("""[{"type": "collect", "sort": {"field": "v"}}]"""),
    )
  }

  @Test
  fun `collect descending`() {
    assertEquals(
      """[{"c":"d","g":"y","v":16},{"c":"c","g":"y","v":9},{"c":"b","g":"x","v":4},""" +
        """{"c":"a","g":"x","v":1},{"c":"e","g":"y","v":null}]""",
      run("""[{"type": "collect", "sort": {"field": "v", "order": "descending"}}]"""),
    )
  }

  @Test
  fun `collect sorts by several fields with per-field order`() {
    assertEquals(
      """[{"c":"b","g":"x","v":4},{"c":"a","g":"x","v":1},{"c":"d","g":"y","v":16},""" +
        """{"c":"c","g":"y","v":9},{"c":"e","g":"y","v":null}]""",
      run(
        """[{"type": "collect", "sort": {"field": ["g", "v"], "order": ["ascending", "descending"]}}]"""
      ),
    )
  }

  // ---- project, identifier --------------------------------------------------

  @Test
  fun project() {
    assertEquals(
      """[{"c":"a","v":1},{"c":"b","v":4},{"c":"c","v":9},{"c":"d","v":16},{"c":"e","v":null}]""",
      run("""[{"type": "project", "fields": ["c", "v"]}]"""),
    )
  }

  @Test
  fun `project renames with as`() {
    assertEquals(
      """[{"name":"a","value":1},{"name":"b","value":4},{"name":"c","value":9},""" +
        """{"name":"d","value":16},{"name":"e","value":null}]""",
      run("""[{"type": "project", "fields": ["c", "v"], "as": ["name", "value"]}]"""),
    )
  }

  @Test
  fun `identifier numbers from one`() {
    val output = run("""[{"type": "identifier", "as": "id"}]""")
    assertTrue(output.contains(""""id":1"""), output)
    assertTrue(output.contains(""""id":5"""), output)
  }

  // ---- extent ---------------------------------------------------------------

  @Test
  fun `extent publishes a signal and leaves the data alone`() {
    // Capture the untransformed baseline first: `run` resets the context, so calling it inside the
    // assertion would wipe the signals and diagnostics being checked.
    val untouched = run("[]")
    val output = run("""[{"type": "extent", "field": "v", "signal": "ext"}]""")
    assertEquals(untouched, output)
    assertEquals(
      VegaValue.Arr(listOf(VegaValue.Num(1.0), VegaValue.Num(16.0))),
      context.signals["ext"],
    )
  }

  // ---- aggregate ------------------------------------------------------------

  @Test
  fun `aggregate with no parameters counts rows`() {
    assertEquals("""[{"count":5}]""", run("""[{"type": "aggregate"}]"""))
  }

  @Test
  fun `aggregate groups and counts`() {
    assertEquals(
      """[{"count":2,"g":"x"},{"count":3,"g":"y"}]""",
      run("""[{"type": "aggregate", "groupby": ["g"]}]"""),
    )
  }

  @Test
  fun `aggregate sum names the output op underscore field`() {
    assertEquals(
      """[{"g":"x","sum_v":5},{"g":"y","sum_v":25}]""",
      run("""[{"type": "aggregate", "groupby": ["g"], "fields": ["v"], "ops": ["sum"]}]"""),
    )
  }

  @Test
  fun `aggregate computes several ops at once`() {
    assertEquals(
      """[{"g":"x","max_v":4,"mean_v":2.5,"min_v":1,"sum_v":5},""" +
        """{"g":"y","max_v":16,"mean_v":12.5,"min_v":9,"sum_v":25}]""",
      run(
        """[{"type": "aggregate", "groupby": ["g"], "fields": ["v","v","v","v"],
            "ops": ["sum","mean","min","max"]}]"""
      ),
    )
  }

  @Test
  fun `count counts tuples while valid and missing split on the value`() {
    // The distinction that is easiest to get backwards: group y has three rows, one of them null,
    // so count_v is 3 and valid_v is 2.
    assertEquals(
      """[{"count_v":2,"g":"x","missing_v":0,"valid_v":2},""" +
        """{"count_v":3,"g":"y","missing_v":1,"valid_v":2}]""",
      run(
        """[{"type": "aggregate", "groupby": ["g"], "fields": ["v","v","v"],
            "ops": ["count","valid","missing"]}]"""
      ),
    )
  }

  @Test
  fun `variance and stdev are the sample forms`() {
    // Over [1,4,9,16]: sample variance 43, not the population 32.25.
    val output =
      run(
        """[{"type": "aggregate", "fields": ["v","v","v"], "ops": ["variance","stdev","median"]}]"""
      )
    assertTrue(output.contains(""""variance_v":43"""), output)
    assertTrue(output.contains(""""median_v":6.5"""), output)
    assertTrue(output.contains(""""stdev_v":6.55743852"""), output)
  }

  @Test
  fun `aggregate renames with as`() {
    assertEquals(
      """[{"g":"x","total":5},{"g":"y","total":25}]""",
      run(
        """[{"type": "aggregate", "groupby": ["g"], "fields": ["v"], "ops": ["sum"], "as": ["total"]}]"""
      ),
    )
  }

  @Test
  fun joinaggregate() {
    assertEquals(
      """[{"c":"a","g":"x","sum_v":5,"v":1},{"c":"b","g":"x","sum_v":5,"v":4},""" +
        """{"c":"c","g":"y","sum_v":25,"v":9},{"c":"d","g":"y","sum_v":25,"v":16},""" +
        """{"c":"e","g":"y","sum_v":25,"v":null}]""",
      run("""[{"type": "joinaggregate", "groupby": ["g"], "fields": ["v"], "ops": ["sum"]}]"""),
    )
  }

  @Test
  fun `an unknown aggregate op is reported`() {
    run("""[{"type": "aggregate", "fields": ["v"], "ops": ["kurtosis"]}]""")
    assertTrue(
      context.diagnostics.diagnostics.any {
        it.code == DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED && it.message.contains("kurtosis")
      }
    )
  }

  // ---- bin ------------------------------------------------------------------

  private val numbers = """[{"v":1},{"v":2},{"v":3},{"v":7},{"v":11},{"v":19},{"v":23}]"""

  @Test
  fun `bin at the default maxbins picks a step of two for a span of 25`() {
    assertEquals(
      """[{"bin0":0,"bin1":2,"v":1},{"bin0":2,"bin1":4,"v":2},{"bin0":2,"bin1":4,"v":3},""" +
        """{"bin0":6,"bin1":8,"v":7},{"bin0":10,"bin1":12,"v":11},{"bin0":18,"bin1":20,"v":19},""" +
        """{"bin0":22,"bin1":24,"v":23}]""",
      run("""[{"type": "bin", "field": "v", "extent": [0, 25]}]""", numbers),
    )
  }

  @Test
  fun `bin honours maxbins`() {
    assertEquals(
      """[{"bin0":0,"bin1":5,"v":1},{"bin0":0,"bin1":5,"v":2},{"bin0":0,"bin1":5,"v":3},""" +
        """{"bin0":5,"bin1":10,"v":7},{"bin0":10,"bin1":15,"v":11},{"bin0":15,"bin1":20,"v":19},""" +
        """{"bin0":20,"bin1":25,"v":23}]""",
      run("""[{"type": "bin", "field": "v", "extent": [0, 25], "maxbins": 5}]""", numbers),
    )
  }

  @Test
  fun `an explicit step overrides maxbins`() {
    assertEquals(
      run("""[{"type": "bin", "field": "v", "extent": [0, 25], "maxbins": 5}]""", numbers),
      run("""[{"type": "bin", "field": "v", "extent": [0, 25], "step": 5}]""", numbers),
    )
  }

  @Test
  fun `nice false starts the first bin at the extent`() {
    assertEquals(
      """[{"bin0":1,"bin1":3,"v":1},{"bin0":1,"bin1":3,"v":2},{"bin0":3,"bin1":5,"v":3},""" +
        """{"bin0":7,"bin1":9,"v":7},{"bin0":11,"bin1":13,"v":11},{"bin0":19,"bin1":21,"v":19},""" +
        """{"bin0":21,"bin1":23,"v":23}]""",
      run("""[{"type": "bin", "field": "v", "extent": [1, 23], "nice": false}]""", numbers),
    )
  }

  @Test
  fun `a value outside the extent gets null bounds rather than being clamped`() {
    assertEquals(
      """[{"bin0":null,"bin1":null,"v":-3},{"bin0":4,"bin1":6,"v":5},{"bin0":null,"bin1":null,"v":99}]""",
      run(
        """[{"type": "bin", "field": "v", "extent": [0, 10], "maxbins": 5}]""",
        """[{"v":-3},{"v":5},{"v":99}]""",
      ),
    )
  }

  @Test
  fun `bin renames with as`() {
    val output =
      run(
        """[{"type": "bin", "field": "v", "extent": [0, 25], "maxbins": 5, "as": ["lo","hi"]}]""",
        numbers,
      )
    assertTrue(output.contains(""""lo":0,"""), output)
    assertTrue(output.contains(""""hi":5"""), output)
  }

  // ---- stack ----------------------------------------------------------------

  private val stackData =
    """[{"c":"a","g":"p","v":3},{"c":"a","g":"q","v":5},{"c":"b","g":"p","v":2},{"c":"b","g":"q","v":8}]"""

  @Test
  fun `stack accumulates within a group`() {
    assertEquals(
      """[{"c":"a","g":"p","v":3,"y0":0,"y1":3},{"c":"a","g":"q","v":5,"y0":3,"y1":8},""" +
        """{"c":"b","g":"p","v":2,"y0":0,"y1":2},{"c":"b","g":"q","v":8,"y0":2,"y1":10}]""",
      run("""[{"type": "stack", "groupby": ["c"], "field": "v"}]""", stackData),
    )
  }

  @Test
  fun `stack center aligns groups against the widest one`() {
    // Group a totals 8 and group b totals 10, so a is offset by 1.
    assertEquals(
      """[{"c":"a","g":"p","v":3,"y0":1,"y1":4},{"c":"a","g":"q","v":5,"y0":4,"y1":9},""" +
        """{"c":"b","g":"p","v":2,"y0":0,"y1":2},{"c":"b","g":"q","v":8,"y0":2,"y1":10}]""",
      run("""[{"type": "stack", "groupby": ["c"], "field": "v", "offset": "center"}]""", stackData),
    )
  }

  @Test
  fun `stack normalize divides by the group total`() {
    assertEquals(
      """[{"c":"a","g":"p","v":3,"y0":0,"y1":0.375},{"c":"a","g":"q","v":5,"y0":0.375,"y1":1},""" +
        """{"c":"b","g":"p","v":2,"y0":0,"y1":0.2},{"c":"b","g":"q","v":8,"y0":0.2,"y1":1}]""",
      run(
        """[{"type": "stack", "groupby": ["c"], "field": "v", "offset": "normalize"}]""",
        stackData,
      ),
    )
  }

  @Test
  fun `stack sort changes the stacking order but not the row order`() {
    assertEquals(
      """[{"c":"a","g":"p","v":3,"y0":5,"y1":8},{"c":"a","g":"q","v":5,"y0":0,"y1":5},""" +
        """{"c":"b","g":"p","v":2,"y0":8,"y1":10},{"c":"b","g":"q","v":8,"y0":0,"y1":8}]""",
      run(
        """[{"type": "stack", "groupby": ["c"], "field": "v",
            "sort": {"field": "g", "order": "descending"}}]""",
        stackData,
      ),
    )
  }

  @Test
  fun `negative values stack away from zero, separately from positive ones`() {
    // Not a single running total: [3,-5,2] gives [0,3], [0,-5] and [3,5].
    assertEquals(
      """[{"c":"a","v":3,"y0":0,"y1":3},{"c":"a","v":-5,"y0":0,"y1":-5},{"c":"a","v":2,"y0":3,"y1":5}]""",
      run(
        """[{"type": "stack", "groupby": ["c"], "field": "v"}]""",
        """[{"c":"a","v":3},{"c":"a","v":-5},{"c":"a","v":2}]""",
      ),
    )
  }

  @Test
  fun `structurally identical rows stack as separate segments`() {
    // Tracked by position, not by value, so duplicates do not collapse into one span.
    assertEquals(
      """[{"c":"a","v":2,"y0":0,"y1":2},{"c":"a","v":2,"y0":2,"y1":4}]""",
      run(
        """[{"type": "stack", "groupby": ["c"], "field": "v"}]""",
        """[{"c":"a","v":2},{"c":"a","v":2}]""",
      ),
    )
  }

  // ---- fold, flatten --------------------------------------------------------

  @Test
  fun `fold keeps the original fields alongside the key value pair`() {
    assertEquals(
      """[{"c":"a","g":"x","key":"g","v":1,"value":"x"},{"c":"a","g":"x","key":"v","v":1,"value":1}]""",
      run("""[{"type": "fold", "fields": ["g", "v"]}]""", """[{"c":"a","g":"x","v":1}]"""),
    )
  }

  @Test
  fun `flatten replaces the array field and drops empty arrays`() {
    assertEquals(
      """[{"c":"a","list":1},{"c":"a","list":2},{"c":"b","list":3}]""",
      run(
        """[{"type": "flatten", "fields": ["list"]}]""",
        """[{"c":"a","list":[1,2]},{"c":"b","list":[3]},{"c":"c","list":[]}]""",
      ),
    )
  }

  @Test
  fun `flatten with as keeps the array and can record the index`() {
    assertEquals(
      """[{"c":"a","i":0,"item":1,"list":[1,2]},{"c":"a","i":1,"item":2,"list":[1,2]},""" +
        """{"c":"b","i":0,"item":3,"list":[3]}]""",
      run(
        """[{"type": "flatten", "fields": ["list"], "as": ["item"], "index": "i"}]""",
        """[{"c":"a","list":[1,2]},{"c":"b","list":[3]}]""",
      ),
    )
  }

  // ---- pipeline behaviour ---------------------------------------------------

  @Test
  fun `transforms chain`() {
    assertEquals(
      """[{"g":"y","sum_v":25}]""",
      run(
        """[{"type": "filter", "expr": "datum.g === 'y'"},
            {"type": "aggregate", "groupby": ["g"], "fields": ["v"], "ops": ["sum"]}]"""
      ),
    )
  }

  @Test
  fun `an unimplemented transform stops the pipeline and says so`() {
    val output =
      run(
        """[{"type": "formula", "expr": "1", "as": "one"},
            {"type": "kde", "field": "v"},
            {"type": "filter", "expr": "false"}]"""
      )
    // The formula ran; the filter after the unknown transform did not.
    assertTrue(output.contains(""""one":1"""), output)
    assertTrue(output.contains(""""c":"a""""), output)
    val diagnostic =
      context.diagnostics.diagnostics.first {
        it.code == DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED
      }
    assertTrue(diagnostic.message.contains("kde"), diagnostic.message)
  }

  @Test
  fun `the input list is never mutated`() {
    val input = (VegaJson.parse(base) as VegaValue.Arr).values
    val before = input.map { asJson(it) }
    pipeline.run(
      input,
      (VegaJson.parse("""[{"type": "formula", "expr": "datum.v * 2", "as": "double"}]""")
          as VegaValue.Arr)
        .values,
      TestContext(),
    )
    assertEquals(before, input.map { asJson(it) })
  }

  @Test
  fun `a filter with a broken expression leaves the data alone rather than dropping everything`() {
    val untouched = run("[]")
    val output = run("""[{"type": "filter", "expr": "1 +"}]""")
    assertEquals(untouched, output)
    assertTrue(
      context.diagnostics.diagnostics.any { it.code == DiagnosticCodes.EXPRESSION_PARSE_ERROR }
    )
  }

  @Test
  fun `the registry covers the transforms the brief lists`() {
    val expected =
      setOf(
        "filter",
        "formula",
        "collect",
        "aggregate",
        "joinaggregate",
        "extent",
        "bin",
        "stack",
        "project",
        "identifier",
        "fold",
        "flatten",
      )
    assertEquals(expected, TransformRegistry.Default.types)
  }
}
