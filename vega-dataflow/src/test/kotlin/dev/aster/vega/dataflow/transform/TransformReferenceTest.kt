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
    val tables = LinkedHashMap<String, List<VegaValue>>()

    override val scope: ExpressionScope = scopeFor(VegaValue.Null)

    override fun setSignal(name: String, value: VegaValue) {
      signals[name] = value
    }

    override fun scopeFor(datum: VegaValue): ExpressionScope =
      object : ExpressionScope {
        override val datum: VegaValue = datum

        override fun signal(name: String): VegaValue = signals[name] ?: VegaValue.Null

        override fun dataset(name: String): List<VegaValue> = tables[name] ?: emptyList()
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
  fun `the registry covers the transforms the brief lists, plus nine more`() {
    val fromTheBrief =
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
    // Nine are not on the brief's list. `timeunit` is the date half of `bin`; `pie` turns a column
    // of numbers into the angles an arc mark needs; `window` is what a running total, a rank and a
    // moving average all are; `sequence` is how a specification draws a function with no data to
    // bind to; `lookup` is the only join there is; and `impute` is what stops a line jumping the
    // gap where a series has no row. `cross`, `pivot` and `countpattern` are the reshaping three:
    // a matrix, a long table made wide, and the word counts a cloud is drawn from.
    assertEquals(
      fromTheBrief +
        "timeunit" +
        "pie" +
        "window" +
        "sequence" +
        "lookup" +
        "impute" +
        "cross" +
        "pivot" +
        "countpattern",
      TransformRegistry.Default.types,
    )
  }

  // ---- window ---------------------------------------------------------------

  /**
   * The probe's dataset for `window`, two partitions of three rows each.
   *
   * Every expected string below came from `oracle-js/src/transform-probe.js` running the same
   * definition, which is the only way to settle what the frame defaults to and which operations
   * ignore it.
   */
  private val windowRows =
    """
    [{"c": "a", "t": 1, "v": 10},
     {"c": "a", "t": 2, "v": 20},
     {"c": "a", "t": 3, "v": 5},
     {"c": "b", "t": 1, "v": 7},
     {"c": "b", "t": 2, "v": 3},
     {"c": "b", "t": 3, "v": 9}]
    """
      .trimIndent()

  /**
   * The default frame is `[null, 0]` — the start of the partition up to and including this row — so
   * a bare `sum` is a running total and not a partition total. With no `groupby` the whole dataset
   * is one partition, which is why the total carries on across the change from `a` to `b`.
   */
  @Test
  fun `window sums cumulatively by default`() {
    assertEquals(
      """[{"c":"a","run":10,"t":1,"v":10},{"c":"a","run":30,"t":2,"v":20},""" +
        """{"c":"a","run":35,"t":3,"v":5},{"c":"b","run":42,"t":1,"v":7},""" +
        """{"c":"b","run":45,"t":2,"v":3},{"c":"b","run":54,"t":3,"v":9}]""",
      run(
        """[{"type": "window", "ops": ["sum"], "fields": ["v"], "as": ["run"]}]""",
        windowRows,
      ),
    )
  }

  @Test
  fun `window partitions and ranks within each group`() {
    assertEquals(
      """[{"c":"a","n":1,"rk":1,"run":10,"t":1,"v":10},""" +
        """{"c":"a","n":2,"rk":2,"run":30,"t":2,"v":20},""" +
        """{"c":"a","n":3,"rk":3,"run":35,"t":3,"v":5},""" +
        """{"c":"b","n":1,"rk":1,"run":7,"t":1,"v":7},""" +
        """{"c":"b","n":2,"rk":2,"run":10,"t":2,"v":3},""" +
        """{"c":"b","n":3,"rk":3,"run":19,"t":3,"v":9}]""",
      run(
        """[{"type": "window", "groupby": ["c"], "sort": {"field": "t"},
             "ops": ["sum", "rank", "row_number"], "fields": ["v", null, null],
             "as": ["run", "rk", "n"]}]""",
        windowRows,
      ),
    )
  }

  /** A frame of `[-1, 0]` is this row and the one before it: a two-row moving average. */
  @Test
  fun `window honours an explicit frame`() {
    assertEquals(
      """[{"c":"a","ma":10,"t":1,"v":10},{"c":"a","ma":15,"t":2,"v":20},""" +
        """{"c":"a","ma":12.5,"t":3,"v":5},{"c":"b","ma":6,"t":1,"v":7},""" +
        """{"c":"b","ma":5,"t":2,"v":3},{"c":"b","ma":6,"t":3,"v":9}]""",
      run(
        """[{"type": "window", "ops": ["mean"], "fields": ["v"], "as": ["ma"],
             "frame": [-1, 0]}]""",
        windowRows,
      ),
    )
  }

  /** `[null, null]` is the whole partition, which is the total rather than the running total. */
  @Test
  fun `an unbounded frame covers the whole partition`() {
    assertEquals(
      """[{"c":"a","t":1,"tot":54,"v":10},{"c":"a","t":2,"tot":54,"v":20},""" +
        """{"c":"a","t":3,"tot":54,"v":5},{"c":"b","t":1,"tot":54,"v":7},""" +
        """{"c":"b","t":2,"tot":54,"v":3},{"c":"b","t":3,"tot":54,"v":9}]""",
      run(
        """[{"type": "window", "ops": ["sum"], "fields": ["v"], "as": ["tot"],
             "frame": [null, null]}]""",
        windowRows,
      ),
    )
  }

  /** Ranking operations look at the whole partition and ignore the frame entirely. */
  @Test
  fun `lag and lead reach outside the frame`() {
    assertEquals(
      """[{"c":"a","next":20,"prev":null,"t":1,"v":10},""" +
        """{"c":"a","next":5,"prev":10,"t":2,"v":20},""" +
        """{"c":"a","next":7,"prev":20,"t":3,"v":5},""" +
        """{"c":"b","next":3,"prev":5,"t":1,"v":7},""" +
        """{"c":"b","next":9,"prev":7,"t":2,"v":3},""" +
        """{"c":"b","next":null,"prev":3,"t":3,"v":9}]""",
      run(
        """[{"type": "window", "ops": ["lag", "lead"], "fields": ["v", "v"],
             "as": ["prev", "next"]}]""",
        windowRows,
      ),
    )
  }

  /**
   * `rank` restarts at the row's own index after a tie, so a run of ties gives 1, 1, 3 — where
   * `dense_rank` counts distinct values and gives 1, 1, 2.
   */
  @Test
  fun `the ranking family matches upstream on a tied sort`() {
    assertEquals(
      """[{"cd":0.6666666666666666,"dr":2,"pr":0.5,"rk":2,"rn":2,"t":1,"v":10},""" +
        """{"cd":1,"dr":3,"pr":1,"rk":3,"rn":3,"t":2,"v":20},""" +
        """{"cd":0.3333333333333333,"dr":1,"pr":0,"rk":1,"rn":1,"t":1,"v":7}]""",
      run(
        """[{"type": "window",
             "ops": ["row_number", "rank", "dense_rank", "percent_rank", "cume_dist"],
             "as": ["rn", "rk", "dr", "pr", "cd"], "sort": {"field": "v"}}]""",
        """[{"t": 1, "v": 10}, {"t": 2, "v": 20}, {"t": 1, "v": 7}]""",
      ),
    )
  }

  @Test
  fun `an unimplemented window operation is reported`() {
    run("""[{"type": "window", "ops": ["nonesuch"], "as": ["x"]}]""", windowRows)
    assertTrue(
      context.diagnostics.diagnostics.any { it.message.contains("nonesuch") },
      context.diagnostics.diagnostics.toString(),
    )
  }

  // ---- sequence, lookup -----------------------------------------------------

  /** `stop` is exclusive, so this is five rows and not six, and the field is named `data`. */
  @Test
  fun `sequence generates rows from nothing`() {
    assertEquals(
      """[{"data":0},{"data":1},{"data":2},{"data":3},{"data":4}]""",
      run("""[{"type": "sequence", "start": 0, "stop": 5}]""", "[]"),
    )
    assertEquals(
      """[{"data":1},{"data":3},{"data":5},{"data":7},{"data":9}]""",
      run("""[{"type": "sequence", "start": 1, "stop": 10, "step": 2}]""", "[]"),
    )
    assertEquals(
      """[{"x":0},{"x":1},{"x":2}]""",
      run("""[{"type": "sequence", "start": 0, "stop": 3, "as": "x"}]""", "[]"),
    )
  }

  @Test
  fun `sequence counts backwards and in fractions`() {
    assertEquals(
      """[{"data":5},{"data":3},{"data":1}]""",
      run("""[{"type": "sequence", "start": 5, "stop": 0, "step": -2}]""", "[]"),
    )
    // Multiplied out from the start rather than accumulated, so the steps do not drift.
    assertEquals(
      """[{"data":0},{"data":0.25},{"data":0.5},{"data":0.75}]""",
      run("""[{"type": "sequence", "start": 0, "stop": 1, "step": 0.25}]""", "[]"),
    )
  }

  private val lookupRows = """[{"k": "a", "v": 1}, {"k": "b", "v": 2}, {"k": "zz", "v": 3}]"""

  private fun runWithTable(transforms: String): String {
    context = TestContext()
    context.tables["other"] =
      (VegaJson.parse(
          """[{"id": "a", "label": "Alpha", "n": 10}, {"id": "b", "label": "Bravo", "n": 20}]"""
        ) as VegaValue.Arr)
        .values
    val input = (VegaJson.parse(lookupRows) as VegaValue.Arr).values
    val definitions = (VegaJson.parse(transforms) as VegaValue.Arr).values
    return pipeline.run(input, definitions, context).joinToString(",", "[", "]") { asJson(it) }
  }

  @Test
  fun `lookup copies named values and defaults an unmatched row`() {
    assertEquals(
      """[{"k":"a","name":"Alpha","v":1},{"k":"b","name":"Bravo","v":2},""" +
        """{"k":"zz","name":null,"v":3}]""",
      runWithTable(
        """[{"type": "lookup", "from": "other", "key": "id", "fields": ["k"],
             "values": ["label"], "as": ["name"]}]"""
      ),
    )
    assertEquals(
      """[{"k":"a","label":"Alpha","v":1},{"k":"b","label":"Bravo","v":2},""" +
        """{"k":"zz","label":null,"v":3}]""",
      runWithTable(
        """[{"type": "lookup", "from": "other", "key": "id", "fields": ["k"],
             "values": ["label"]}]"""
      ),
    )
    assertEquals(
      """[{"k":"a","name":"Alpha","v":1},{"k":"b","name":"Bravo","v":2},""" +
        """{"k":"zz","name":"?","v":3}]""",
      runWithTable(
        """[{"type": "lookup", "from": "other", "key": "id", "fields": ["k"],
             "values": ["label"], "as": ["name"], "default": "?"}]"""
      ),
    )
  }

  /** With no `values`, the whole matched row lands in the field `as` names. */
  @Test
  fun `lookup without values writes the whole matched row`() {
    assertEquals(
      """[{"k":"a","row":{"id":"a","label":"Alpha","n":10},"v":1},""" +
        """{"k":"b","row":{"id":"b","label":"Bravo","n":20},"v":2},""" +
        """{"k":"zz","row":null,"v":3}]""",
      runWithTable(
        """[{"type": "lookup", "from": "other", "key": "id", "fields": ["k"], "as": ["row"]}]"""
      ),
    )
  }

  // ---- impute ---------------------------------------------------------------

  /**
   * The key domain is the union across the **whole dataset**, not per group — a group is missing a
   * key precisely when some other group has it — and the new rows are **appended** rather than
   * merged into position.
   */
  private val imputeRows =
    """
    [{"c": "a", "t": 1, "v": 10},
     {"c": "a", "t": 3, "v": 30},
     {"c": "b", "t": 1, "v": 7},
     {"c": "b", "t": 2, "v": 9},
     {"c": "b", "t": 3, "v": 11}]
    """
      .trimIndent()

  @Test
  fun `impute fills a group's missing keys from the whole dataset's`() {
    val expected =
      """[{"c":"a","t":1,"v":10},{"c":"a","t":3,"v":30},{"c":"b","t":1,"v":7},""" +
        """{"c":"b","t":2,"v":9},{"c":"b","t":3,"v":11},{"c":"a","t":2,"v":0}]"""
    assertEquals(
      expected,
      run(
        """[{"type": "impute", "key": "t", "field": "v", "groupby": ["c"],
             "method": "value", "value": 0}]""",
        imputeRows,
      ),
    )
    // `value` with a value of zero is also the default, which is easy to assume is `null`.
    assertEquals(
      expected,
      run("""[{"type": "impute", "key": "t", "field": "v", "groupby": ["c"]}]""", imputeRows),
    )
  }

  /** An aggregate method summarises the group's *existing* values: a has 10 and 30, so 20. */
  @Test
  fun `impute can fill from an aggregate of the group`() {
    assertEquals(
      """[{"c":"a","t":1,"v":10},{"c":"a","t":3,"v":30},{"c":"b","t":1,"v":7},""" +
        """{"c":"b","t":2,"v":9},{"c":"b","t":3,"v":11},{"c":"a","t":2,"v":20}]""",
      run(
        """[{"type": "impute", "key": "t", "field": "v", "groupby": ["c"], "method": "mean"}]""",
        imputeRows,
      ),
    )
  }

  /** `keyvals` replaces the domain, so a series can be padded past where its data ever reached. */
  @Test
  fun `keyvals can name keys nothing in the data has`() {
    assertEquals(
      """[{"c":"a","t":1,"v":10},{"c":"a","t":3,"v":30},{"c":"b","t":1,"v":7},""" +
        """{"c":"b","t":2,"v":9},{"c":"b","t":3,"v":11},{"c":"a","t":2,"v":0},""" +
        """{"c":"a","t":4,"v":0},{"c":"b","t":4,"v":0}]""",
      run(
        """[{"type": "impute", "key": "t", "field": "v", "groupby": ["c"],
             "keyvals": [1, 2, 3, 4], "method": "value", "value": 0}]""",
        imputeRows,
      ),
    )
  }

  /** With no groups there is nothing to be missing from, so nothing is added. */
  @Test
  fun `impute without a groupby adds nothing`() {
    assertEquals(
      """[{"c":"a","t":1,"v":10},{"c":"a","t":3,"v":30},{"c":"b","t":1,"v":7},""" +
        """{"c":"b","t":2,"v":9},{"c":"b","t":3,"v":11}]""",
      run(
        """[{"type": "impute", "key": "t", "field": "v", "method": "value", "value": -1}]""",
        imputeRows,
      ),
    )
  }

  // ---- cross, pivot, countpattern -------------------------------------------

  /**
   * A crossed row holds the two originals **whole**, under `a` and `b`, rather than merging their
   * fields — which is what the name suggests and would lose a column whenever both sides share one.
   */
  @Test
  fun `cross pairs every row with every row`() {
    assertEquals(
      """[{"a":{"k":"a","v":1},"b":{"k":"a","v":1}},{"a":{"k":"a","v":1},"b":{"k":"b","v":2}},""" +
        """{"a":{"k":"b","v":2},"b":{"k":"a","v":1}},{"a":{"k":"b","v":2},"b":{"k":"b","v":2}}]""",
      run("""[{"type": "cross"}]""", """[{"k": "a", "v": 1}, {"k": "b", "v": 2}]"""),
    )
  }

  /** The filter sees the pair, so it is how a specification takes half of a matrix. */
  @Test
  fun `cross filters the pairs as it forms them`() {
    assertEquals(
      """[{"a":{"k":"a","v":1},"b":{"k":"b","v":2}},{"a":{"k":"a","v":1},"b":{"k":"c","v":3}},""" +
        """{"a":{"k":"b","v":2},"b":{"k":"c","v":3}}]""",
      run(
        """[{"type": "cross", "filter": "datum.a.v < datum.b.v"}]""",
        """[{"k": "a", "v": 1}, {"k": "b", "v": 2}, {"k": "c", "v": 3}]""",
      ),
    )
  }

  private val wide =
    """
    [{"k": "a", "c": "x", "v": 1},
     {"k": "a", "c": "y", "v": 2},
     {"k": "b", "c": "x", "v": 3},
     {"k": "b", "c": "y", "v": 4},
     {"k": "b", "c": "z", "v": 9}]
    """
      .trimIndent()

  @Test
  fun `pivot turns rows into columns`() {
    assertEquals(
      """[{"c":"a","k":"a","x":1,"y":2},{"c":"b","k":"b","x":3,"y":4,"z":9}]"""
        .let { _ ->
          """[{"k":"a","x":1,"y":2},{"k":"b","x":3,"y":4,"z":9}]"""
        },
      run("""[{"type": "pivot", "field": "c", "value": "v", "groupby": ["k"]}]""", wide),
    )
    // With no groups the whole dataset collapses to one row, and the cells are summed.
    assertEquals(
      """[{"x":4,"y":6,"z":9}]""",
      run("""[{"type": "pivot", "field": "c", "value": "v"}]""", wide),
    )
  }

  /**
   * `limit` keeps the **alphabetically first** columns, not the commonest or the earliest — the
   * names are sorted before the limit is applied, so a column that sorts late disappears however
   * often it occurs.
   */
  @Test
  fun `pivot sorts its column names before limiting them`() {
    assertEquals(
      """[{"k":"a","x":1,"y":2},{"k":"b","x":3,"y":4}]""",
      run(
        """[{"type": "pivot", "field": "c", "value": "v", "groupby": ["k"], "limit": 2}]""",
        wide,
      ),
    )
  }

  private val sentences = """[{"t": "the cat sat on the mat"}, {"t": "the dog sat"}]"""

  /** Counts come out in first-appearance order, not sorted by count. */
  @Test
  fun `countpattern counts the words in a column`() {
    assertEquals(
      """[{"count":3,"text":"the"},{"count":1,"text":"cat"},{"count":2,"text":"sat"},""" +
        """{"count":1,"text":"on"},{"count":1,"text":"mat"},{"count":1,"text":"dog"}]""",
      run("""[{"type": "countpattern", "field": "t"}]""", sentences),
    )
  }

  /** The stopword list is anchored to whole tokens, so `on` does not take `dog` with it. */
  @Test
  fun `countpattern drops whole stopwords and can fold case`() {
    assertEquals(
      """[{"count":1,"text":"cat"},{"count":2,"text":"sat"},{"count":1,"text":"mat"},""" +
        """{"count":1,"text":"dog"}]""",
      run("""[{"type": "countpattern", "field": "t", "stopwords": "the|on"}]""", sentences),
    )
    assertEquals(
      """[{"count":3,"text":"THE"},{"count":1,"text":"CAT"},{"count":2,"text":"SAT"},""" +
        """{"count":1,"text":"ON"},{"count":1,"text":"MAT"},{"count":1,"text":"DOG"}]""",
      run("""[{"type": "countpattern", "field": "t", "case": "upper"}]""", sentences),
    )
  }
}
