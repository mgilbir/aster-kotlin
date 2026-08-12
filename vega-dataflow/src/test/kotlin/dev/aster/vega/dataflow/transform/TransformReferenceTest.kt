package dev.aster.vega.dataflow.transform

import dev.aster.vega.expression.CachingExpressionCompiler
import dev.aster.vega.expression.ExpressionCompiler
import dev.aster.vega.expression.ExpressionScope
import dev.aster.vega.expression.JsSemantics
import dev.aster.vega.expression.RandomStream
import dev.aster.vega.expression.VegaExpressionCompiler
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asString
import dev.aster.vega.model.field
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
    override var tree: TreeSource? = null

    override val diagnostics: DiagnosticCollector = DiagnosticCollector()
    override val expressions: ExpressionCompiler =
      CachingExpressionCompiler(VegaExpressionCompiler())
    val signals = LinkedHashMap<String, VegaValue>()
    val tables = LinkedHashMap<String, List<VegaValue>>()

    /**
     * One stream for the whole context, as a compile has.
     *
     * `ci0`/`ci1` consume 1,000 x n draws per group, so a fresh stream per scope would give the
     * second group the first group's numbers and the vectors below would not match upstream.
     */
    val stream = RandomStream()

    override val scope: ExpressionScope = scopeFor(VegaValue.Null)

    override fun setSignal(name: String, value: VegaValue) {
      signals[name] = value
    }

    override fun scopeFor(datum: VegaValue): ExpressionScope =
      object : ExpressionScope {
        override val datum: VegaValue = datum

        override fun signal(name: String): VegaValue = signals[name] ?: VegaValue.Null

        override fun dataset(name: String): List<VegaValue> = tables[name] ?: emptyList()

        override val random: RandomStream = stream
      }
  }

  private var context = TestContext()

  /**
   * Runs a pipeline and renders the result as sorted-key JSON, matching the probe's output form.
   */
  /**
   * `product`, and the two exponentially weighted means, against upstream's own output.
   *
   * These are the only aggregate operations whose answer depends on the **order** of the rows: each
   * value is weighted by `rate` to the power of how many rows follow it, so the last row counts
   * most. `exponential` normalises by `(1 - r) / (1 - r^n)` so the weights sum to one, and
   * `exponentialb` only scales by `(1 - r)` — which is why the two differ on a group of three and
   * agree on nothing. They are also the only two that read `aggregate_params`, positionally
   * alongside `ops`.
   *
   * Upstream, on `[{g:a,x:2},{g:a,x:3},{g:a,x:5},{g:b,x:4},{g:b,x:6}]` at a rate of 0.5:
   * ```
   * [{"g":"a","prod":30,"exp":4,"expb":3.5},
   *  {"g":"b","prod":24,"exp":5.333333333333333,"expb":4}]
   * ```
   */
  @Test
  fun `product and the exponentially weighted means`() {
    assertEquals(
      """[{"exp":4,"expb":3.5,"g":"a","prod":30},""" +
        """{"exp":5.333333333333333,"expb":4,"g":"b","prod":24}]""",
      run(
        """[{"type": "aggregate", "groupby": ["g"], "fields": ["x", "x", "x"],
            "ops": ["product", "exponential", "exponentialb"],
            "aggregate_params": [null, 0.5, 0.5],
            "as": ["prod", "exp", "expb"]}]""",
        """[{"g": "a", "x": 2}, {"g": "a", "x": 3}, {"g": "a", "x": 5},
            {"g": "b", "x": 4}, {"g": "b", "x": 6}]""",
      ),
    )
  }

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

  /**
   * `dotbin` places dots on a grid of its own, and `smooth` moves some of them.
   *
   * Both passes are pinned because the second is the kind of thing that looks right and is not: it
   * swaps dots between neighbouring stacks within a quarter step, so a single misplaced dot changes
   * one column's height and nothing else. These are the 48 points of Vega's own dot plot, and every
   * value came out of upstream.
   */
  @Test
  fun `dotbin places dots the way upstream does, smoothed and not`() {
    val pts =
      "[6.3,2.1,9.1,15.8,5.2,10.9,8.3,11.0,3.2,7.6,6.3,8.6,6.6,9.5,4.8,12.0,3.3,11.0,4.7,10.4," +
        "7.4,2.1,7.7,17.9,6.1,8.2,8.4,11.9,10.8,13.8,14.3,15.2,10.0,11.9,6.5,7.5,10.6,7.4,8.4," +
        "5.7,4.9,3.2,8.1,11.0,4.9,13.2,9.7,12.8]"
    val rows =
      (VegaJson.parse(pts) as VegaValue.Arr).values.map {
        VegaValue.Obj(linkedMapOf("data" to it))
      }
    val expected =
      mapOf(
        false to
          "6,2.1,9.4,15.5,4.95,10.9,8.35,10.9,3.25,7.55,6,8.35,6.55,9.4,4.95,11.95,3.25,10.9," +
            "4.95,10.3,7.55,2.1,7.55,17.9,6,8.35,8.35,11.95,10.9,14.05,14.05,15.5,10.3,11.95," +
            "6.55,7.55,10.3,7.55,8.35,6,4.95,3.25,8.35,10.9,4.95,13,9.4,13",
        true to
          "6,2.1,9.4,15.5,4.95,10.3,8.35,10.9,3.25,7.55,6.55,8.35,6.55,9.4,4.95,11.95,3.25,10.9," +
            "4.95,10.3,7.55,2.1,7.55,17.9,6,8.35,8.35,11.95,10.9,14.05,14.05,15.5,10.3,11.95," +
            "6.55,7.55,10.3,7.55,8.35,6,4.95,3.25,8.35,10.9,4.95,13,9.4,13",
      )
    for ((smooth, want) in expected) {
      val params =
        VegaJson.parse("""{"type":"dotbin","field":"data","smooth":$smooth,"step":0.65}""")
          as VegaValue.Obj
      val result = pipeline.run(rows, listOf(params), TestContext())
      assertEquals(want, result.joinToString(",") { it.field("bin").asString() }, "smooth=$smooth")
    }
  }

  /**
   * A value landing exactly on a bin boundary belongs to the bin it opens, not the one below.
   *
   * In exact arithmetic `(9.1 - 1.95) / 0.65` is 11; in doubles it is 10.999999999999998, and
   * flooring that puts the row one column to the left. Upstream adds 1e-14 inside the floor for
   * this, and three of these 48 points land on a boundary — enough to change which column of a
   * histogram is the tallest, and nothing else about the chart.
   */
  @Test
  fun `a value on a bin boundary lands in the bin it opens`() {
    val pts =
      "[6.3,2.1,9.1,15.8,5.2,10.9,8.3,11.0,3.2,7.6,6.3,8.6,6.6,9.5,4.8,12.0,3.3,11.0,4.7,10.4," +
        "7.4,2.1,7.7,17.9,6.1,8.2,8.4,11.9,10.8,13.8,14.3,15.2,10.0,11.9,6.5,7.5,10.6,7.4,8.4," +
        "5.7,4.9,3.2,8.1,11.0,4.9,13.2,9.7,12.8]"
    val rows =
      (VegaJson.parse(pts) as VegaValue.Arr).values.map {
        VegaValue.Obj(linkedMapOf("data" to it))
      }
    val params =
      VegaJson.parse("""{"type":"bin","field":"data","step":0.65,"extent":[2.1,17.9]}""")
        as VegaValue.Obj
    val result = pipeline.run(rows, listOf(params), TestContext())
    // Every one from upstream. 9.1, 10.4 and 6.5 are the three on a boundary.
    assertEquals(
      "5.85,1.95,9.1,15.6,5.2,10.4,7.8,10.4,2.6,7.15,5.85,8.45,6.5,9.1,4.55,11.7,3.25,10.4," +
        "4.55,10.4,7.15,1.95,7.15,17.55,5.85,7.8,7.8,11.7,10.4,13.65,14.3,14.95,9.75,11.7,6.5," +
        "7.15,10.4,7.15,7.8,5.2,4.55,2.6,7.8,10.4,4.55,13,9.1,12.35",
      result.joinToString(",") { it.field("bin0").asString() },
    )
  }

  // ---- aggregate ------------------------------------------------------------

  @Test
  fun `aggregate with no parameters counts rows`() {
    assertEquals("""[{"count":5}]""", run("""[{"type": "aggregate"}]"""))
  }

  /**
   * `argmin` and `argmax` return the **whole tuple** at the extreme, not the value.
   *
   * That is what makes them useful and is easy to implement as the value by mistake: a chart labels
   * a point by aggregating with one and then reading any column of the row that came back —
   * `datum.lo.c`, not `datum.lo`.
   */
  @Test
  fun `argmin and argmax return the tuple, and stderr the standard error`() {
    assertEquals(
      """[{"g":"x","hi":{"c":"b","g":"x","v":4},"lo":{"c":"a","g":"x","v":1},"se":1.5},""" +
        """{"g":"y","hi":{"c":"d","g":"y","v":16},"lo":{"c":"c","g":"y","v":9},"se":3.5}]""",
      run(
        """[{"type": "aggregate", "groupby": ["g"], "fields": ["v", "v", "v"],
             "ops": ["argmin", "argmax", "stderr"], "as": ["lo", "hi", "se"]}]"""
      ),
    )
  }

  @Test
  fun `argmin and argmax over the whole dataset skip the row whose field is missing`() {
    assertEquals(
      """[{"hi":{"c":"d","g":"y","v":16},"lo":{"c":"a","g":"x","v":1},"se":3.278719262151}]""",
      run(
        """[{"type": "aggregate", "fields": ["v", "v", "v"],
             "ops": ["argmin", "argmax", "stderr"], "as": ["lo", "hi", "se"]}]"""
      ),
    )
  }

  /** One value has no spread, so upstream leaves the field off the row entirely. */
  @Test
  fun `stderr of a single value is absent rather than zero`() {
    assertEquals(
      """[{}]""",
      run(
        """[{"type": "filter", "expr": "datum.c === 'a'"},
            {"type": "aggregate", "fields": ["v"], "ops": ["stderr"], "as": ["se"]}]"""
      ),
    )
  }

  /** An aggregate over nothing produces no rows, not a row of nulls. */
  @Test
  fun `aggregate over an empty input produces no rows`() {
    assertEquals(
      """[]""",
      run(
        """[{"type": "filter", "expr": "false"},
            {"type": "aggregate", "fields": ["v"], "ops": ["argmin"], "as": ["lo"]}]"""
      ),
    )
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
            {"type": "geojson", "field": "v"},
            {"type": "filter", "expr": "false"}]"""
      )
    // The formula ran; the filter after the unknown transform did not.
    assertTrue(output.contains(""""one":1"""), output)
    assertTrue(output.contains(""""c":"a""""), output)
    val diagnostic =
      context.diagnostics.diagnostics.first {
        it.code == DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED
      }
    assertTrue(diagnostic.message.contains("geojson"), diagnostic.message)
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

  /**
   * `ci0`/`ci1`, pinned against upstream with the same seeded generator on both sides.
   *
   * Two things this settles that reading the code cannot. The interval is a **bootstrap**, so it is
   * a property of the draw sequence and not of the data alone — which is why it can be pinned at
   * all only now that both engines share a generator. And the two ends come from *one* run: asking
   * for both does not resample twice, so the second group's numbers depend on the first group
   * having consumed exactly 1,000 x n draws and no more.
   */
  @Test
  fun `ci0 and ci1 are upstream's bootstrap`() {
    val rows =
      listOf(1.0, 2.0, 3.0, 9.0, 4.0).map { row("a", it) } +
        listOf(10.0, 20.0, 30.0, 11.0, 12.0).map { row("b", it) }
    val output =
      AggregateTransform.apply(
        rows,
        VegaJson.parse(
          """{"type": "aggregate", "groupby": ["g"], "fields": ["v", "v", "v"],
              "ops": ["mean", "ci0", "ci1"], "as": ["mean", "ci0", "ci1"]}"""
        ) as VegaValue.Obj,
        context,
      )
    assertEquals(
      listOf(
        """{"ci0":1.8,"ci1":6.4,"g":"a","mean":3.8}""",
        """{"ci0":10.8,"ci1":24,"g":"b","mean":16.6}""",
      ),
      output.map { asJson(it) },
    )
  }

  private fun row(group: String, value: Double): VegaValue =
    VegaValue.Obj(linkedMapOf("g" to VegaValue.Str(group), "v" to VegaValue.Num(value)))

  @Test
  fun `the registry covers the transforms the brief lists, plus thirty-five more`() {
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
    // Eleven are not on the brief's list. `timeunit` is the date half of `bin`; `pie` turns a
    // column
    // of numbers into the angles an arc mark needs; `window` is what a running total, a rank and a
    // moving average all are; `sequence` is how a specification draws a function with no data to
    // bind to; `lookup` is the only join there is; and `impute` is what stops a line jumping the
    // gap where a series has no row. `cross`, `pivot` and `countpattern` are the reshaping three:
    // a matrix, a long table made wide, and the word counts a cloud is drawn from. `quantile`,
    // `regression`, `loess`, `kde`, `density` and `dotbin` are the statistical family. `treelinks`
    // and `linkpath` are what turns a laid-out tree into the edges drawn between its nodes.
    // `crossfilter` and `resolvefilter` are the pair an interactive cross-filter is built from:
    // one records which range query each row fails, the other keeps the rows every dimension but
    // its own admits. `isocontour`, `geopath`, `kde2d` and `heatmap` are the raster family: a
    // density estimated over a grid, marching squares over that grid, the GeoJSON it produces
    // written out as an outline, and the grid itself painted as an image. `force` is the one
    // transform that is a simulation rather than a calculation: it places the nodes of a graph by
    // running d3-force to a standstill. `geoshape` and `graticule` are the map pair: a GeoJSON
    // feature drawn through a projection, the grid of meridians and parallels under it, and a
    // longitude and latitude placed on the page. `voronoi` is the region of the plane nearest to
    // each point, which an interactive scatter plot draws invisibly so the pointer has something to
    // hit. `label` places a text mark beside the marks it annotates, dropping the ones that would
    // collide — the one transform here whose fidelity is not established against upstream, because
    // upstream's own needs a canvas Node has not got.
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
        "countpattern" +
        "quantile" +
        "regression" +
        "loess" +
        "kde" +
        "density" +
        "dotbin" +
        "stratify" +
        "nest" +
        "treemap" +
        "partition" +
        "pack" +
        "tree" +
        "treelinks" +
        "linkpath" +
        "crossfilter" +
        "resolvefilter" +
        "isocontour" +
        "geopath" +
        "kde2d" +
        "heatmap" +
        "force" +
        "geoshape" +
        "geopoint" +
        "graticule" +
        "voronoi" +
        "label",
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

  // ---- quantile, regression -------------------------------------------------

  private val ten =
    """[{"v": 1}, {"v": 2}, {"v": 3}, {"v": 4}, {"v": 5},
                        {"v": 6}, {"v": 7}, {"v": 8}, {"v": 9}, {"v": 10}]"""

  /**
   * The probabilities sit at the **middle** of each step, `(i + 0.5) × step`, so they never ask for
   * the minimum or the maximum. Asking for 0 and 1 would pin a quantile plot's ends to the two most
   * extreme observations, which is exactly what such a plot is trying not to do.
   */
  @Test
  fun `quantile samples the middle of each step`() {
    assertEquals(
      """[{"prob":0.125,"value":2.125},{"prob":0.375,"value":4.375},""" +
        """{"prob":0.625,"value":6.625},{"prob":0.875,"value":8.875}]""",
      run("""[{"type": "quantile", "field": "v", "step": 0.25}]""", ten),
    )
    assertEquals(
      """[{"prob":0.1,"value":1.9},{"prob":0.5,"value":5.5},{"prob":0.9,"value":9.1}]""",
      run("""[{"type": "quantile", "field": "v", "probs": [0.1, 0.5, 0.9]}]""", ten),
    )
  }

  @Test
  fun `the default step gives a hundred probabilities from 0_005 to 0_995`() {
    val output = run("""[{"type": "quantile", "field": "v"}]""", ten)
    assertEquals(100, output.split("},{").size)
    assertTrue(output.startsWith("""[{"prob":0.005,"""), output.take(40))
    assertTrue(output.endsWith("""value":9.955}]"""), output.takeLast(40))
  }

  private val scatter =
    """
    [{"x": 1, "y": 2.1}, {"x": 2, "y": 3.9}, {"x": 3, "y": 6.2},
     {"x": 4, "y": 7.8}, {"x": 5, "y": 10.1}, {"x": 6, "y": 11.9}]
    """
      .trimIndent()

  /** A line between two points *is* the line, so a linear fit needs no sampled curve. */
  @Test
  fun `a linear regression is its two endpoints`() {
    assertEquals(
      """[{"x":1,"y":2.0571428571428596},{"x":6,"y":11.94285714285714}]""",
      run("""[{"type": "regression", "x": "x", "y": "y", "method": "linear"}]""", scatter),
    )
    // `linear` is also the default, and `extent` moves the two ends.
    assertEquals(
      """[{"x":0,"y":0.08000000000000362},{"x":10,"y":19.851428571428563}]""",
      run("""[{"type": "regression", "x": "x", "y": "y", "extent": [0, 10]}]""", scatter),
    )
  }

  @Test
  fun `params reports the fit rather than the fitted points`() {
    assertEquals(
      """[{"coef":[0.08000000000000362,1.977142857142856],"rSquared":0.9983821199232757}]""",
      run("""[{"type": "regression", "x": "x", "y": "y", "params": true}]""", scatter),
    )
  }

  private val curvy =
    """
    [{"x": 1, "y": 2}, {"x": 2, "y": 5}, {"x": 3, "y": 11}, {"x": 4, "y": 21},
     {"x": 5, "y": 34}, {"x": 6, "y": 52}, {"x": 7, "y": 71}, {"x": 8, "y": 99}]
    """
      .trimIndent()

  private fun coefficients(method: String, extra: String = ""): String =
    run(
      """[{"type": "regression", "x": "x", "y": "y", "method": "$method", "params": true$extra}]""",
      curvy,
    )

  /**
   * Every method's coefficients, against upstream. What each pair *means* differs by method, and
   * the shape of the list is the only clue: `pow` reports a multiplier and an exponent rather than
   * an intercept and a slope, and the polynomials report their terms from the constant upward.
   */
  @Test
  fun `each regression method reports its own kind of coefficients`() {
    assertEquals("""[{"coef":[36.875],"rSquared":0}]""", coefficients("constant"))
    assertEquals(
      """[{"coef":[-18.827471327599483,42.02135381282752],"rSquared":0.726756178797916}]""",
      coefficients("log"),
    )
    assertEquals(
      """[{"coef":[3.456988384045931,0.4298715892180333],"rSquared":0.9806420833787446}]""",
      coefficients("exp"),
    )
    assertEquals(
      """[{"coef":[1.5884436383130596,1.921653211967671],"rSquared":0.9771828870809403}]""",
      coefficients("pow"),
    )
    assertEquals(
      """[{"coef":[4.017857142857139,-3.458333333333334,1.8988095238095237],""" +
        """"rSquared":0.9993541765255546}]""",
      coefficients("quad"),
    )
    assertEquals(
      """[{"coef":[1.1428571428571672,-0.4671717171717553,1.114718614718624,""" +
        """0.05808080808080738],"rSquared":0.9995923010228036}]""",
      coefficients("poly", ""","order": 3"""),
    )
  }

  /** A constant fit is a horizontal line, so it needs two endpoints like a linear one. */
  @Test
  fun `a constant fit is the mean of y, drawn end to end`() {
    assertEquals(
      """[{"x":1,"y":36.875},{"x":8,"y":36.875}]""",
      run("""[{"type": "regression", "x": "x", "y": "y", "method": "constant"}]""", curvy),
    )
  }

  /**
   * The adaptive sampler, which is the point of this test rather than the quadratic.
   *
   * Note the spacing: 0.07 apart where the curve is turning near x = 1, widening to 0.28 by the
   * time it has straightened out at x = 8. A uniform grid of the same 48 points would have put them
   * 0.15 apart everywhere, and the bend is where that shows.
   */
  @Test
  fun `a curved fit is sampled where it bends, not on a grid`() {
    val output = run("""[{"type": "regression", "x": "x", "y": "y", "method": "quad"}]""", curvy)
    val points = output.split("},{")
    assertEquals(48, points.size)
    assertTrue(output.startsWith("""[{"x":1,"y":2.4583333333333286},{"x":1.07,"""), output.take(60))
    assertTrue(
      output.endsWith("""{"x":7.72,"y":90.48553333333334},{"x":8,"y":97.875}]"""),
      output.takeLast(60),
    )
  }

  @Test
  fun `a log fit is sampled the same way`() {
    val output = run("""[{"type": "regression", "x": "x", "y": "y", "method": "log"}]""", scatter)
    assertEquals(35, output.split("},{").size)
    assertTrue(output.startsWith("""[{"x":1,"y":1.0813382366396915}"""), output.take(50))
    assertTrue(output.endsWith("""{"x":6,"y":10.752485834986695}]"""), output.takeLast(40))
  }

  /** Fitting a curve to no more points than it has parameters would say nothing; upstream skips. */
  @Test
  fun `a group with no more points than parameters is skipped`() {
    val two = """[{"x": 1, "y": 2}, {"x": 2, "y": 5}]"""
    assertEquals(
      "[]",
      run("""[{"type": "regression", "x": "x", "y": "y", "method": "quad"}]""", two),
    )
    assertTrue(
      context.diagnostics.diagnostics.any { it.message.contains("more parameters than data") },
      context.diagnostics.diagnostics.toString(),
    )
  }

  @Test
  fun `an unknown regression method is reported`() {
    run("""[{"type": "regression", "x": "x", "y": "y", "method": "cubic"}]""", scatter)
    assertTrue(
      context.diagnostics.diagnostics.any { it.message.contains("cubic") },
      context.diagnostics.diagnostics.toString(),
    )
  }

  // ---- loess ----------------------------------------------------------------

  /**
   * A local fit over a wide window smooths; over a narrow one it does not.
   *
   * The default bandwidth of 0.3 across eight points gives a window of two, and a straight line
   * through two points passes through both — so the "smoothed" output is the input exactly. That is
   * not a bug and it is worth pinning: a loess with too small a bandwidth silently does nothing.
   */
  @Test
  fun `loess smooths only as far as its bandwidth reaches`() {
    assertEquals(
      """[{"x":1,"y":2},{"x":2,"y":5},{"x":3,"y":11},{"x":4,"y":21},{"x":5,"y":34},""" +
        """{"x":6,"y":52},{"x":7,"y":71},{"x":8,"y":99}]""",
      run("""[{"type": "loess", "x": "x", "y": "y"}]""", curvy),
    )
    assertEquals(
      """[{"x":1,"y":1.6605545158476431},{"x":2,"y":5.858431622822565},""" +
        """{"x":3,"y":12.168605655250985},{"x":4,"y":21.82788783657626},""" +
        """{"x":5,"y":35.51686928081588},{"x":6,"y":52.22088818332823},""" +
        """{"x":7,"y":74.33942427425657},{"x":8,"y":98.31241958028772}]""",
      run("""[{"type": "loess", "x": "x", "y": "y", "bandwidth": 0.6}]""", curvy),
    )
  }

  // ---- kde, density, dotbin -------------------------------------------------

  private val sample =
    """
    [{"v": 2}, {"v": 3}, {"v": 3}, {"v": 4}, {"v": 5}, {"v": 5},
     {"v": 5}, {"v": 6}, {"v": 7}, {"v": 9}, {"v": 12}, {"v": 3.5}]
    """
      .trimIndent()

  private val twoGroups =
    """
    [{"g": "a", "v": 1}, {"g": "a", "v": 2}, {"g": "a", "v": 2}, {"g": "a", "v": 4},
     {"g": "b", "v": 5}, {"g": "b", "v": 6}, {"g": "b", "v": 6}, {"g": "b", "v": 9}]
    """
      .trimIndent()

  /**
   * The bandwidth is Scott's rule when unset, which is the whole answer here: none of these numbers
   * follows from the data alone.
   */
  @Test
  fun `a kernel density estimate matches upstream`() {
    assertEquals(
      """[{"density":0.09011414403662595,"value":2},{"density":0.1582932515223536,"value":4},""" +
        """{"density":0.11843695231771774,"value":6},{"density":0.05326135520557964,"value":8},""" +
        """{"density":0.029723008771871905,"value":10},""" +
        """{"density":0.026355564726171482,"value":12}]""",
      run("""[{"type": "kde", "field": "v", "steps": 5}]""", sample),
    )
  }

  /** `cumulative` climbs to 1 instead; `counts` scales a probability by the group's size. */
  @Test
  fun `cumulative and counts change what the density means`() {
    assertEquals(
      """[{"density":0.10253359103054159,"value":2},{"density":0.365935355859532,"value":4},""" +
        """{"density":0.658637755376812,"value":6},{"density":0.8233133572463371,"value":8},""" +
        """{"density":0.9018160893305828,"value":10},""" +
        """{"density":0.9570644326217687,"value":12}]""",
      run("""[{"type": "kde", "field": "v", "steps": 5, "cumulative": true}]""", sample),
    )
    assertEquals(
      """[{"density":1.0798231538090275,"value":2},{"density":2.0738574033025037,"value":4.5},""" +
        """{"density":0.8624520359708714,"value":7},{"density":0.388042675977441,"value":9.5},""" +
        """{"density":0.4033756216361776,"value":12}]""",
      run(
        """[{"type": "kde", "field": "v", "steps": 4, "bandwidth": 1, "counts": true}]""",
        sample,
      ),
    )
  }

  /** Each group gets its own extent, so the two densities do not share x positions. */
  @Test
  fun `grouped densities are sampled over their own extents`() {
    assertEquals(
      """[{"density":0.22182889446291434,"g":"a","value":1},""" +
        """{"density":0.2734615629587992,"g":"a","value":2},""" +
        """{"density":0.19497578501765456,"g":"a","value":3},""" +
        """{"density":0.1278390154599367,"g":"a","value":4},""" +
        """{"density":0.22075438991637106,"g":"b","value":5},""" +
        """{"density":0.2325431290214444,"g":"b","value":6.333333333333333},""" +
        """{"density":0.09359008457132215,"g":"b","value":7.666666666666666},""" +
        """{"density":0.1019849518627684,"g":"b","value":9}]""",
      run(
        """[{"type": "kde", "field": "v", "groupby": ["g"], "steps": 3, "bandwidth": 1}]""",
        twoGroups,
      ),
    )
  }

  /** The normal CDF is West's rational approximation, not an integral — worth pinning exactly. */
  @Test
  fun `a named distribution is sampled over its extent`() {
    assertEquals(
      """[{"density":0.0044318484119380075,"value":-3},""" +
        """{"density":0.07895015830089419,"value":-1.7999999999999998},""" +
        """{"density":0.3332246028917997,"value":-0.5999999999999996},""" +
        """{"density":0.3332246028917997,"value":0.5999999999999996},""" +
        """{"density":0.07895015830089407,"value":1.8000000000000007},""" +
        """{"density":0.0044318484119380075,"value":3}]""",
      run(
        """[{"type": "density", "extent": [-3, 3], "steps": 5,
             "distribution": {"function": "normal", "mean": 0, "stdev": 1}}]""",
        sample,
      ),
    )
    assertEquals(
      """[{"density":0.001349898031630115,"value":-3},""" +
        """{"density":0.03593031911292582,"value":-1.7999999999999998},""" +
        """{"density":0.2742531177500737,"value":-0.5999999999999996},""" +
        """{"density":0.7257468822499262,"value":0.5999999999999996},""" +
        """{"density":0.9640696808870742,"value":1.8000000000000007},""" +
        """{"density":0.9986501019683699,"value":3}]""",
      run(
        """[{"type": "density", "extent": [-3, 3], "steps": 5, "method": "cdf",
             "distribution": {"function": "normal"}}]""",
        sample,
      ),
    )
  }

  @Test
  fun `lognormal and uniform densities match upstream`() {
    assertEquals(
      """[{"density":0.18411619590349992,"value":0.5},""" +
        """{"density":0.46192945435622274,"value":1.375},""" +
        """{"density":0.25838163819756943,"value":2.25},""" +
        """{"density":0.12058133105227524,"value":3.125},""" +
        """{"density":0.055832224931867395,"value":4}]""",
      run(
        """[{"type": "density", "extent": [0.5, 4], "steps": 4,
             "distribution": {"function": "lognormal", "mean": 0.5, "stdev": 0.6}}]""",
        sample,
      ),
    )
    // A uniform density is zero outside its own bounds rather than undefined.
    assertEquals(
      """[{"density":0,"value":-1},{"density":0.5,"value":0},{"density":0.5,"value":1},""" +
        """{"density":0.5,"value":2},{"density":0,"value":3}]""",
      run(
        """[{"type": "density", "extent": [-1, 3], "steps": 4,
             "distribution": {"function": "uniform", "min": 0, "max": 2}}]""",
        sample,
      ),
    )
  }

  /** A mixture normalises its weights, so `[0.3, 0.7]` and `[3, 7]` are the same curve. */
  @Test
  fun `a mixture blends its parts by normalised weight`() {
    val expected =
      """[{"density":0.016197289953956417,"value":-2},""" +
        """{"density":0.11968268412043688,"value":0},""" +
        """{"density":0.016384652270027257,"value":2},""" +
        """{"density":0.5585593416297352,"value":4},""" +
        """{"density":1.8736413883569446e-4,"value":6}]"""
    assertEquals(
      expected,
      run(
        """[{"type": "density", "extent": [-2, 6], "steps": 4,
             "distribution": {"function": "mixture", "weights": [0.3, 0.7], "distributions": [
               {"function": "normal", "mean": 0, "stdev": 1},
               {"function": "normal", "mean": 4, "stdev": 0.5}]}}]""",
        sample,
      ),
    )
    assertEquals(
      expected,
      run(
        """[{"type": "density", "extent": [-2, 6], "steps": 4,
             "distribution": {"function": "mixture", "weights": [3, 7], "distributions": [
               {"function": "normal", "mean": 0, "stdev": 1},
               {"function": "normal", "mean": 4, "stdev": 0.5}]}}]""",
        sample,
      ),
    )
  }

  /** A kde distribution can read the rows itself, which is why it alone may omit the extent. */
  @Test
  fun `a kde distribution takes its extent from its own data`() {
    assertEquals(
      """[{"density":0.017465444564237414,"value":0},""" +
        """{"density":0.10804378902250249,"value":2.4000000000000004},""" +
        """{"density":0.1499032509624765,"value":4.800000000000001},""" +
        """{"density":0.07593112615316225,"value":7.199999999999999},""" +
        """{"density":0.03343298798682608,"value":9.600000000000001},""" +
        """{"density":0.02525733414523565,"value":12}]""",
      run(
        """[{"type": "density", "extent": [0, 12], "steps": 5,
             "distribution": {"function": "kde", "field": "v", "bandwidth": 1.5}}]""",
        sample,
      ),
    )
  }

  /**
   * Without `steps` the sampler adapts, and a bell over six standard deviations wants 105 points.
   */
  @Test
  fun `a density with no step count is sampled adaptively`() {
    val output =
      run(
        """[{"type": "density", "extent": [-3, 3],
             "distribution": {"function": "normal"}}]""",
        sample,
      )
    assertEquals(105, output.split("},{").size)
  }

  /**
   * A dot plot is not a histogram with round marks: there are no fixed bin edges. A stack opens at
   * the first value that did not fit in the previous one, and every dot in it takes the midpoint of
   * that stack's first and last value — so the stacks sit where the data is dense.
   */
  @Test
  fun `dotbin stacks by proximity rather than on a grid`() {
    // The default step is a thirtieth of the extent, here 0.33, so nothing shares a stack.
    assertEquals(
      """[{"bin":2,"v":2},{"bin":3,"v":3},{"bin":3,"v":3},{"bin":4,"v":4},{"bin":5,"v":5},""" +
        """{"bin":5,"v":5},{"bin":5,"v":5},{"bin":6,"v":6},{"bin":7,"v":7},{"bin":9,"v":9},""" +
        """{"bin":12,"v":12},{"bin":3.5,"v":3.5}]""",
      run("""[{"type": "dotbin", "field": "v"}]""", sample),
    )
    // A wider step gathers 2, 3, 3 into one stack at 2.5, and 5, 5, 5, 6 into one at 5.5. Note the
    // rows come back in their original order, so the last row is still the 3.5 — now at 3.75.
    assertEquals(
      """[{"bin":2.5,"v":2},{"bin":2.5,"v":3},{"bin":2.5,"v":3},{"bin":3.75,"v":4},""" +
        """{"bin":5.5,"v":5},{"bin":5.5,"v":5},{"bin":5.5,"v":5},{"bin":5.5,"v":6},""" +
        """{"bin":7,"v":7},{"bin":9,"v":9},{"bin":12,"v":12},{"bin":3.75,"v":3.5}]""",
      run("""[{"type": "dotbin", "field": "v", "step": 1.5}]""", sample),
    )
  }

  /** Smoothing trades a dot's own value for a less jagged outline, by swapping between stacks. */
  @Test
  fun `dotbin smoothing moves dots between adjacent stacks`() {
    assertEquals(
      """[{"bin":2.5,"v":2},{"bin":2.5,"v":3},{"bin":3.75,"v":3},{"bin":3.75,"v":4},""" +
        """{"bin":5.5,"v":5},{"bin":3.75,"v":5},{"bin":7,"v":5},{"bin":7,"v":6},""" +
        """{"bin":7,"v":7},{"bin":9,"v":9},{"bin":12,"v":12},{"bin":3.75,"v":3.5}]""",
      run("""[{"type": "dotbin", "field": "v", "step": 1.5, "smooth": true}]""", sample),
    )
  }

  @Test
  fun `dotbin stacks each group separately`() {
    assertEquals(
      """[{"bin":1,"g":"a","v":1},{"bin":2,"g":"a","v":2},{"bin":2,"g":"a","v":2},""" +
        """{"bin":4,"g":"a","v":4},{"bin":5,"g":"b","v":5},{"bin":6,"g":"b","v":6},""" +
        """{"bin":6,"g":"b","v":6},{"bin":9,"g":"b","v":9}]""",
      run("""[{"type": "dotbin", "field": "v", "groupby": ["g"], "step": 1}]""", twoGroups),
    )
  }

  // ---- stratify, nest, treemap, partition -----------------------------------

  private val forest =
    """
    [{"id": "root", "parent": null, "size": 0},
     {"id": "a", "parent": "root", "size": 0},
     {"id": "b", "parent": "root", "size": 0},
     {"id": "a1", "parent": "a", "size": 4},
     {"id": "a2", "parent": "a", "size": 6},
     {"id": "b1", "parent": "b", "size": 3},
     {"id": "b2", "parent": "b", "size": 7},
     {"id": "b3", "parent": "b", "size": 2}]
    """
      .trimIndent()

  private val STRATIFY = """{"type": "stratify", "key": "id", "parentKey": "parent"}"""

  /** Only the coordinates, so the expectations stay readable at eight nodes a piece. */
  private fun boxes(transform: String, data: String = forest): String =
    run("[$STRATIFY, $transform]", data)
      .let { Regex("\\{[^}]*}").findAll(it).toList() }
      .joinToString(" ") { match ->
        Regex(""""(x0|y0|x1|y1|depth|children)":(-?[0-9.eE-]+)""")
          .findAll(match.value)
          .associate { it.groupValues[1] to it.groupValues[2] }
          .let {
            "${it["x0"]},${it["y0"]},${it["x1"]},${it["y1"]}/${it["depth"]}:${it["children"]}"
          }
      }

  /**
   * The tree never reaches the data. `stratify` returns exactly its input, and the layout after it
   * writes coordinates back onto those same rows — so a mark sees a flat table with four more
   * columns, which is why no part of the data model had to learn about trees.
   */
  @Test
  fun `stratify returns its rows unchanged`() {
    assertEquals(run("[$STRATIFY]", forest), run("[]", forest))
  }

  @Test
  fun `a squarified treemap matches upstream`() {
    assertEquals(
      "0,0,100,100/0:2 0,0,100,45.45454545454546/1:2 0,45.45454545454546,100,100/1:3 " +
        "0,0,40,45.45454545454546/2:0 40,0,100,45.45454545454546/2:0 " +
        "0,45.45454545454546,83.33333333333333,61.81818181818183/2:0 " +
        "0,61.81818181818183,83.33333333333333,100/2:0 " +
        "83.33333333333333,45.45454545454546,100,100/2:0",
      boxes("""{"type": "treemap", "field": "size", "size": [100, 100]}"""),
    )
  }

  /**
   * The other tilings, which differ in what they preserve. `dice` and `slice` keep sibling order
   * exactly and will produce slivers; `binary` keeps order and splits recursively; `slicedice`
   * alternates by depth, which is what makes its nesting readable.
   */
  @Test
  fun `each treemap tiling matches upstream`() {
    assertEquals(
      "0,0,100,100/0:2 0,0,100,45.45454545454545/1:2 0,45.45454545454545,100,100/1:3 " +
        "0,0,40,45.45454545454545/2:0 40,0,100,45.45454545454545/2:0 " +
        "0,45.45454545454545,25,100/2:0 25,45.45454545454545,83.33333333333333,100/2:0 " +
        "83.33333333333333,45.45454545454545,100,100/2:0",
      boxes("""{"type": "treemap", "field": "size", "method": "binary", "size": [100, 100]}"""),
    )
    assertEquals(
      "0,0,100,100/0:2 0,0,45.45454545454546,100/1:2 45.45454545454546,0,100,100/1:3 " +
        "0,0,18.181818181818183,100/2:0 18.181818181818183,0,45.45454545454545,100/2:0 " +
        "45.45454545454546,0,59.09090909090909,100/2:0 " +
        "59.09090909090909,0,90.9090909090909,100/2:0 90.9090909090909,0,100,100/2:0",
      boxes("""{"type": "treemap", "field": "size", "method": "dice", "size": [100, 100]}"""),
    )
    assertEquals(
      "0,0,100,100/0:2 0,0,100,45.45454545454546/1:2 0,45.45454545454546,100,100/1:3 " +
        "0,0,100,18.181818181818183/2:0 0,18.181818181818183,100,45.45454545454545/2:0 " +
        "0,45.45454545454546,100,59.09090909090909/2:0 " +
        "0,59.09090909090909,100,90.9090909090909/2:0 0,90.9090909090909,100,100/2:0",
      boxes("""{"type": "treemap", "field": "size", "method": "slice", "size": [100, 100]}"""),
    )
    assertEquals(
      "0,0,100,100/0:2 0,0,45.45454545454546,100/1:2 45.45454545454546,0,100,100/1:3 " +
        "0,0,45.45454545454546,40/2:0 0,40,45.45454545454546,100/2:0 " +
        "45.45454545454546,0,100,25/2:0 45.45454545454546,25,100,83.33333333333334/2:0 " +
        "45.45454545454546,83.33333333333334,100,100.00000000000001/2:0",
      boxes("""{"type": "treemap", "field": "size", "method": "slicedice", "size": [100, 100]}"""),
    )
  }

  /** Half the inner padding comes off each side, so a gap between siblings is one padding wide. */
  @Test
  fun `treemap padding and rounding match upstream`() {
    assertEquals(
      "0,0,100,100/0:2 2,2,98,45/1:2 2,47,98,98/1:3 4,4,40,43/2:0 42,4,96,43/2:0 " +
        "4,49,80,61/2:0 4,63,80,96/2:0 82,49,96,96/2:0",
      boxes(
        """{"type": "treemap", "field": "size", "size": [100, 100],
            "padding": 2, "round": true}"""
      ),
    )
  }

  /** With no `field`, a branch is sized by how many leaves hang off it rather than by a measure. */
  @Test
  fun `a treemap with no field counts its leaves`() {
    assertEquals(
      "0,0,100,100/0:2 0,0,100,40/1:2 0,40,100,100/1:3 0,0,50,40/2:0 50,0,100,40/2:0 " +
        "0,40,66.66666666666667,70/2:0 0,70,66.66666666666667,100/2:0 " +
        "66.66666666666667,40,100,100/2:0",
      boxes("""{"type": "treemap", "size": [100, 100]}"""),
    )
  }

  /** An icicle plot: every level gets an equal band whatever its values, and dices its width. */
  @Test
  fun `a partition layout matches upstream`() {
    assertEquals(
      "0,0,100,33.333333333333336/0:2 0,33.333333333333336,45.45454545454546," +
        "66.66666666666667/1:2 45.45454545454546,33.333333333333336,100,66.66666666666667/1:3 " +
        "0,66.66666666666667,18.181818181818183,100/2:0 " +
        "18.181818181818183,66.66666666666667,45.45454545454545,100/2:0 " +
        "45.45454545454546,66.66666666666667,59.09090909090909,100/2:0 " +
        "59.09090909090909,66.66666666666667,90.9090909090909,100/2:0 " +
        "90.9090909090909,66.66666666666667,100,100/2:0",
      boxes("""{"type": "partition", "field": "size", "size": [100, 100]}"""),
    )
    assertEquals(
      "1,1,99,32/0:2 1,33,45,66/1:2 46,33,99,66/1:3 1,67,18,99/2:0 19,67,45,99/2:0 " +
        "46,67,59,99/2:0 60,67,90,99/2:0 91,67,99,99/2:0",
      boxes(
        """{"type": "partition", "field": "size", "size": [100, 100],
            "padding": 1, "round": true}"""
      ),
    )
  }

  private val nested =
    """
    [{"g": "x", "h": "p", "n": 1}, {"g": "x", "h": "p", "n": 2}, {"g": "x", "h": "q", "n": 3},
     {"g": "y", "h": "p", "n": 4}, {"g": "y", "h": "r", "n": 5}]
    """
      .trimIndent()

  /**
   * `nest` groups where `stratify` links, and its interior nodes are invented rather than found —
   * so without `generate` they size the layout but appear nowhere in the output.
   */
  @Test
  fun `a nested tree lays out without emitting its interior nodes`() {
    assertEquals(
      """[{"children":0,"depth":3,"g":"x","h":"p","n":1,"x0":0,"x1":50,"y0":0,""" +
        """"y1":13.333333333333334},{"children":0,"depth":3,"g":"x","h":"p","n":2,"x0":0,""" +
        """"x1":50,"y0":13.333333333333334,"y1":40},{"children":0,"depth":3,"g":"x","h":"q",""" +
        """"n":3,"x0":50,"x1":100,"y0":0,"y1":40},{"children":0,"depth":3,"g":"y","h":"p",""" +
        """"n":4,"x0":0,"x1":44.44444444444444,"y0":40,"y1":100},{"children":0,"depth":3,""" +
        """"g":"y","h":"r","n":5,"x0":44.44444444444444,"x1":100,"y0":40,"y1":100}]""",
      run(
        """[{"type": "nest", "keys": ["g", "h"]},
            {"type": "treemap", "field": "n", "size": [100, 100]}]""",
        nested,
      ),
    )
  }

  /**
   * With `generate`, the groups join the data — level by level from the root, as upstream emits.
   */
  @Test
  fun `generate adds a row per interior node`() {
    val output =
      run(
        """[{"type": "nest", "keys": ["g", "h"], "generate": true},
            {"type": "treemap", "field": "n", "size": [100, 100]}]""",
        nested,
      )
    // Five leaves, then the root, the two 'g' groups, and the four 'h' groups.
    assertEquals(12, output.split("},{").size)
    assertTrue(
      output.contains(""""children":2,"depth":0,"x0":0,"x1":100,"y0":0,"y1":100"""),
      output,
    )
    assertTrue(
      output.contains(""""children":2,"depth":1,"key":"x","x0":0,"x1":100,"y0":0,"y1":40"""),
      output,
    )
    assertTrue(
      context.diagnostics.diagnostics.any { it.message.contains("'values'") },
      context.diagnostics.diagnostics.toString(),
    )
  }

  /** A tree assembled from bad links is a different tree, not a smaller one, so each case stops. */
  @Test
  fun `stratify reports a broken tree rather than repairing it`() {
    val twoRoots =
      """[{"id": "a", "parent": null}, {"id": "b", "parent": null}, {"id": "c", "parent": "a"}]"""
    run("[$STRATIFY]", twoRoots)
    assertTrue(
      context.diagnostics.diagnostics.any {
        it.message.contains("more than one row with no parent")
      },
      context.diagnostics.diagnostics.toString(),
    )

    val missing = """[{"id": "a", "parent": null}, {"id": "b", "parent": "nowhere"}]"""
    run("[$STRATIFY]", missing)
    assertTrue(
      context.diagnostics.diagnostics.any { it.message.contains("'nowhere'") },
      context.diagnostics.diagnostics.toString(),
    )

    val duplicate =
      """[{"id": "a", "parent": null}, {"id": "a", "parent": null}, {"id": "c", "parent": "a"}]"""
    run("[$STRATIFY]", duplicate)
    assertTrue(
      context.diagnostics.diagnostics.any { it.message.contains("more than one row") },
      context.diagnostics.diagnostics.toString(),
    )
  }

  @Test
  fun `a layout with no tree before it is reported`() {
    run("""[{"type": "treemap", "field": "size", "size": [10, 10]}]""", forest)
    assertTrue(
      context.diagnostics.diagnostics.any { it.message.contains("put a 'stratify'") },
      context.diagnostics.diagnostics.toString(),
    )
  }

  // ---- pack, tree -----------------------------------------------------------

  /** Only x, y, r and depth, so an eight-node layout stays readable as an expectation. */
  private fun circles(transform: String): String =
    run("[$STRATIFY, $transform]", forest)
      .let { Regex("\\{[^}]*}").findAll(it).toList() }
      .joinToString(" ") { match ->
        Regex(""""(x|y|r|depth)":(-?[0-9.eE-]+)""")
          .findAll(match.value)
          .associate { it.groupValues[1] to it.groupValues[2] }
          .let { "${it["x"]},${it["y"]}" + (it["r"]?.let { r -> ",$r" } ?: "") + "/${it["depth"]}" }
      }

  /**
   * A leaf's radius is the square root of its value, so **area** carries the quantity — a radius
   * proportional to value would square the difference. The packing itself is Welzl's enclosing
   * circle over a seeded shuffle, which is why it comes out the same every run.
   */
  @Test
  fun `circle packing matches upstream`() {
    assertEquals(
      "50,50,50/0 25.19736415175014,50,25.19736415175014/1 " +
        "75.19736415175014,50,24.80263584824986/1 " +
        "11.325956731384244,50,11.325956731384244/2 36.52332088313438,50,13.871407420365896/2 " +
        "60.21218638409848,49.48369549411809,9.80856625154212/2 " +
        "85.00358507120094,49.48369549411809,14.982832435560345/2 " +
        "68.34924779954463,65.3343046817553,8.008660808187225/2",
      circles("""{"type": "pack", "field": "size", "size": [100, 100]}"""),
    )
  }

  /** Padding is applied in the finished chart's units, so it is rescaled into the pack's own. */
  @Test
  fun `pack padding matches upstream`() {
    assertEquals(
      "50,50,50.00000000000001/0 25.716357660545793,50,23.35686860131813/1 " +
        "74.53661313093197,50,23.103897809840372/1 " +
        "13.626801782784357,50,8.907823664329023/2 35.803925854488654,50,10.909811348147603/2 " +
        "61.560578003500964,48.815264127207506,7.714401585741121/2 " +
        "83.4184117177851,48.815264127207506,11.78394306931535/2 " +
        "69.06235484490092,63.36819409834665,6.298782518661054/2",
      circles("""{"type": "pack", "field": "size", "size": [100, 100], "padding": 3}"""),
    )
  }

  /**
   * `radius` reads off the layout node, not the row — so naming a data column gives every circle a
   * radius of zero. That is upstream's behaviour, matched here rather than corrected, and reported.
   */
  @Test
  fun `pack radius resolves against the node and is reported`() {
    assertEquals(
      "50,50,0/0 50,50,0/1 50,50,0/1 50,50,0/2 50,50,0/2 50,50,0/2 50,50,0/2 50,50,0/2",
      circles("""{"type": "pack", "radius": "size", "size": [100, 100]}"""),
    )
    assertTrue(
      context.diagnostics.diagnostics.any { it.message.contains("read off the layout node") },
      context.diagnostics.diagnostics.toString(),
    )
  }

  /**
   * The tidy layout puts every node at its own depth and guarantees identical subtrees are drawn
   * identically; the dendrogram puts every leaf on the last row whatever its depth. Here the tree
   * is uniform, so the two land within a rounding of each other — which is exactly the case where
   * the choice does not matter, and worth pinning so a later change to either is visible.
   */
  @Test
  fun `both tree layouts match upstream`() {
    assertEquals(
      "46.42857142857143,0/0 21.42857142857143,50/1 71.42857142857143,50/1 " +
        "14.285714285714286,100/2 28.571428571428573,100/2 57.142857142857146,100/2 " +
        "71.42857142857143,100/2 85.71428571428572,100/2",
      circles("""{"type": "tree", "field": "size", "size": [100, 100]}"""),
    )
    assertEquals(
      "46.42857142857143,0/0 21.428571428571427,50/1 71.42857142857143,50/1 " +
        "14.285714285714285,100/2 28.57142857142857,100/2 57.14285714285714,100/2 " +
        "71.42857142857143,100/2 85.71428571428571,100/2",
      circles("""{"type": "tree", "method": "cluster", "size": [100, 100]}"""),
    )
  }

  /** `separation` off packs cousins as tightly as siblings, so the five leaves space evenly. */
  @Test
  fun `separation off packs cousins as tightly as siblings`() {
    assertEquals(
      "45,0/0 20,50/1 70,50/1 10,100/2 30,100/2 50,100/2 70,100/2 90,100/2",
      circles("""{"type": "tree", "size": [100, 100], "separation": false}"""),
    )
  }

  /** `nodeSize` fixes the spacing per node and lets the diagram be whatever size that comes to. */
  @Test
  fun `nodeSize sizes the nodes rather than the diagram`() {
    assertEquals(
      "0,0/0 -35,30/1 35,30/1 -45,60/2 -25,60/2 15,60/2 35,60/2 55,60/2",
      circles("""{"type": "tree", "nodeSize": [20, 30]}"""),
    )
  }

  @Test
  fun `an unknown tree method is reported`() {
    run("""[$STRATIFY, {"type": "tree", "method": "radial", "size": [10, 10]}]""", forest)
    assertTrue(
      context.diagnostics.diagnostics.any { it.message.contains("radial") },
      context.diagnostics.diagnostics.toString(),
    )
  }

  // ---- treelinks, linkpath --------------------------------------------------

  /** Four nodes, so a tidy layout lands on round numbers and the path vectors stay readable. */
  private val fork =
    """
    [{"id": "root", "parent": null},
     {"id": "a", "parent": "root"},
     {"id": "b", "parent": "root"},
     {"id": "a1", "parent": "a"}]
    """
      .trimIndent()

  private fun rows(transforms: String, data: String): List<VegaValue> {
    context = TestContext()
    return pipeline.run(
      (VegaJson.parse(data) as VegaValue.Arr).values,
      (VegaJson.parse(transforms) as VegaValue.Arr).values,
      context,
    )
  }

  /**
   * `root` and `a` are the only nodes with children, so an edge list that came out depth-first
   * would read `root>a a>a1 a>a2 root>b …` and put a different link under every mark.
   */
  @Test
  fun `treelinks emits one row per edge, breadth-first`() {
    assertEquals(
      "root>a root>b a>a1 a>a2 b>b1 b>b2 b>b3",
      rows("""[$STRATIFY, {"type": "treelinks"}]""", forest).joinToString(" ") {
        "${it.field("source.id").asString()}>${it.field("target.id").asString()}"
      },
    )
  }

  /** The whole row goes into each end, so an accessor reaches through `source` or `target`. */
  @Test
  fun `an edge carries both rows whole`() {
    val edge = rows("""[$STRATIFY, {"type": "treelinks"}]""", fork).first()
    assertEquals("root", edge.field("source.id").asString())
    assertEquals("a", edge.field("target.id").asString())
  }

  private val LAID_OUT =
    """$STRATIFY, {"type": "tree", "size": [100, 100]}, {"type": "treelinks"}"""

  /** The text `linkpath` wrote on each edge, in the order the edges came out. */
  private fun paths(transform: String, output: String = "path"): String =
    rows("[$LAID_OUT, $transform]", fork).joinToString(" ") { it.field(output).asString() }

  private fun linkpath(shape: String, orient: String): String =
    paths("""{"type": "linkpath", "shape": "$shape", "orient": "$orient"}""")

  /**
   * `line`, `arc` and `curve` are symmetric in x and y, so upstream gives them no per-orientation
   * form and a Cartesian `orient` falls through to the bare shape. Pinned in both orientations
   * because a lookup that failed to fall back would report an unsupported pair instead.
   */
  @Test
  fun `the symmetric shapes ignore a Cartesian orientation`() {
    val line = "M50,0L25,50 M50,0L75,50 M25,50L25,100"
    assertEquals(line, linkpath("line", "horizontal"))
    assertEquals(line, linkpath("line", "vertical"))

    val arc =
      "M50,0A27.95084971874737,27.95084971874737 116.56505117707799 0 1 25,50 " +
        "M50,0A27.95084971874737,27.95084971874737 63.43494882292201 0 1 75,50 " +
        "M25,50A25,25 90 0 1 25,100"
    assertEquals(arc, linkpath("arc", "horizontal"))
    assertEquals(arc, linkpath("arc", "vertical"))

    val curve = "M50,0C55,15 40,45 25,50 M50,0C65,5 80,35 75,50 M25,50C35,60 35,90 25,100"
    assertEquals(curve, linkpath("curve", "horizontal"))
    assertEquals(curve, linkpath("curve", "vertical"))
  }

  /** `orthogonal` and `diagonal` bend towards the axis the tree grows along, so orient decides. */
  @Test
  fun `the bending shapes follow the orientation`() {
    assertEquals(
      "M50,0V50H25 M50,0V50H75 M25,50V100H25",
      linkpath("orthogonal", "horizontal"),
    )
    assertEquals("M50,0H25V50 M50,0H75V50 M25,50H25V100", linkpath("orthogonal", "vertical"))
    assertEquals(
      "M50,0C37.5,0 37.5,50 25,50 M50,0C62.5,0 62.5,50 75,50 M25,50C25,50 25,100 25,100",
      linkpath("diagonal", "horizontal"),
    )
    assertEquals(
      "M50,0C50,25 25,25 25,50 M50,0C50,25 75,25 75,50 M25,50C25,75 25,75 25,100",
      linkpath("diagonal", "vertical"),
    )
  }

  /**
   * Radially the four accessors are angle and radius rather than x and y, so the same layout that
   * drew a fan of straight lines above now wraps around the origin.
   */
  @Test
  fun `every radial shape matches upstream`() {
    assertEquals(
      "M0,0L49.56014059317368,-6.617587504888651 " +
        "M0,0L46.087563486237464,-19.389081770471524 " +
        "M49.56014059317368,-6.617587504888651L99.12028118634736,-13.235175009777302",
      linkpath("line", "radial"),
    )
    assertEquals(
      "M0,0A25,25 -7.605512172941977 0 1 49.56014059317368,-6.617587504888651 " +
        "M0,0A25,25 -22.816536518825938 0 1 46.087563486237464,-19.389081770471524 " +
        "M49.56014059317368,-6.617587504888651A25,25 -7.605512172941977 0 1 " +
        "99.12028118634736,-13.235175009777302",
      linkpath("arc", "radial"),
    )
    assertEquals(
      "M0,0C8.588510617657006,-11.235545619612466 38.32459497356121,-15.206098122545658 " +
        "49.56014059317368,-6.617587504888651 " +
        "M0,0C5.3396963431531885,-13.0953290513418 32.992234434895664,-24.728778113624713 " +
        "46.087563486237464,-19.389081770471524 " +
        "M49.56014059317368,-6.617587504888651C58.148651210830685,-17.853133124501117 " +
        "87.88473556673489,-21.82368562743431 99.12028118634736,-13.235175009777302",
      linkpath("curve", "radial"),
    )
    assertEquals(
      "M0,0C24.124150712302832,-6.559371342598219 24.78007029658684,-3.3087937524443256 " +
        "49.56014059317368,-6.617587504888651 " +
        "M0,0C24.124150712302832,-6.559371342598219 23.043781743118732,-9.694540885235762 " +
        "46.087563486237464,-19.389081770471524 " +
        "M49.56014059317368,-6.617587504888651C74.34021088976051,-9.926381257332977 " +
        "74.34021088976051,-9.926381257332977 99.12028118634736,-13.235175009777302",
      linkpath("diagonal", "radial"),
    )
    // The first two edges leave the root, whose radius is zero, so their arc is a point — upstream
    // emits the degenerate `A0,0` rather than skipping it, and the renderer draws the line only.
    assertEquals(
      "M0,0A0,0 0 0,1 0,0L49.56014059317368,-6.617587504888651 " +
        "M0,0A0,0 0 0,0 0,0L46.087563486237464,-19.389081770471524 " +
        "M49.56014059317368,-6.617587504888651A50,50 0 0,0 " +
        "49.56014059317368,-6.617587504888651L99.12028118634736,-13.235175009777302",
      linkpath("orthogonal", "radial"),
    )
  }

  /** With nothing said, upstream draws a straight `line` and writes it to `path`. */
  @Test
  fun `linkpath defaults to a straight line written to path`() {
    assertEquals("M50,0L25,50 M50,0L75,50 M25,50L25,100", paths("""{"type": "linkpath"}"""))
  }

  /** The radial tree example names all four, because radially they are not x and y at all. */
  @Test
  fun `explicit accessors and a renamed output match upstream`() {
    assertEquals(
      "M0,50L50,25 M0,50L50,75 M50,25L100,25",
      paths(
        """{"type": "linkpath", "sourceX": "source.y", "sourceY": "source.x",
             "targetX": "target.y", "targetY": "target.x", "as": "wire"}""",
        output = "wire",
      ),
    )
  }

  @Test
  fun `treelinks with no tree before it is reported`() {
    assertTrue(rows("""[{"type": "treelinks"}]""", fork).isEmpty())
    assertTrue(
      context.diagnostics.diagnostics.any { it.message.contains("treelinks needs a tree") },
      context.diagnostics.diagnostics.toString(),
    )
  }

  /**
   * Position is the only link left between a node and its row once a transform has copied it, so a
   * filter in between is refused rather than silently joining the wrong pair of rows.
   */
  @Test
  fun `rows dropped between the tree and treelinks are reported`() {
    val output =
      rows(
        """[$STRATIFY, {"type": "filter", "expr": "datum.id != 'b'"},
            {"type": "treelinks"}]""",
        fork,
      )
    assertTrue(output.isEmpty())
    assertTrue(
      context.diagnostics.diagnostics.any { it.message.contains("added or removed rows") },
      context.diagnostics.diagnostics.toString(),
    )
  }

  @Test
  fun `a shape with no form for the orientation is reported`() {
    paths("""{"type": "linkpath", "shape": "orthogonal", "orient": "diagonal"}""")
    assertTrue(
      context.diagnostics.diagnostics.any {
        it.message.contains("no 'orthogonal' shape for a 'diagonal' layout")
      },
      context.diagnostics.diagnostics.toString(),
    )
  }

  /** Nothing re-runs a transform here, so the parameter that would trigger it says so. */
  @Test
  fun `linkpath require is reported`() {
    paths("""{"type": "linkpath", "require": "hover"}""")
    assertTrue(
      context.diagnostics.diagnostics.any { it.message.contains("'require'") },
      context.diagnostics.diagnostics.toString(),
    )
  }
}
