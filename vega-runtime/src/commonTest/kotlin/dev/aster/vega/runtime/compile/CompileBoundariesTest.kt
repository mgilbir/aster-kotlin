package dev.aster.vega.runtime.compile

import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.runtime.scale.TimeScale
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.TextNode
import dev.aster.vega.scene.flatten
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the compiler does with a specification that asks for something it cannot give.
 *
 * The audit's first design tension is that "nothing throws" was a promise with no mechanism: the
 * README stakes the diagnostic model on it and no public boundary had a catch-all or a depth cap,
 * so a *document* could take the host down seven different ways. These are the compiler's share of
 * that, plus the two scale findings that were wrong rather than fatal.
 */
class CompileBoundariesTest {

  private fun compile(json: String) = SpecCompiler().compileJson(json)

  private val table = """{"name": "t", "values": [{"c": "a", "v": 1}, {"c": "b", "v": 2}]}"""

  /**
   * C4 — `domainRaw` is how an interactive zoom publishes the exact interval it wants, and a time
   * scale was computing one and throwing it away.
   *
   * The committed `overview-plus-detail.vg.json` fixture is this shape: brush the overview, and the
   * detail panel recompiled with the full domain and rendered unzoomed. A static compile passes the
   * oracle because the brush signal is null at compile time, so the flagship interaction was inert
   * with nothing to read.
   */
  @Test
  fun `a time scale honours domainRaw`() {
    val spec =
      """
      {
        "width": 100, "height": 60, "padding": 0,
        "signals": [{"name": "brush", "value": [1577836800000, 1580515200000]}],
        "data": [$table],
        "scales": [{"name": "x", "type": "utc",
          "domain": [1500000000000, 1700000000000],
          "domainRaw": {"signal": "brush"},
          "range": "width"}],
        "marks": [{"type": "rect", "from": {"data": "t"}}]
      }
      """
        .trimIndent()
    val scale = compile(spec).scales.getValue("x") as TimeScale
    // The raw domain exactly: 2020-01-01 to 2020-02-01, not the declared span around it.
    assertEquals(listOf(1577836800000.0, 1580515200000.0), scale.domain)
  }

  /**
   * H2 — a niced time scale re-derived from the original domain, discarding `domainMin`/`domainMax`
   * and the padding computed just above it.
   *
   * Vega-Lite defaults every temporal scale to `nice: true`, so a VL chart's `scale.domainMax` on a
   * time axis silently showed the whole span. The comment beside the code said the opposite was
   * intended.
   */
  @Test
  fun `a niced time scale rounds out from its bounded domain`() {
    val spec =
      """
      {
        "width": 100, "height": 60, "padding": 0,
        "data": [$table],
        "scales": [{"name": "x", "type": "utc", "nice": true,
          "domain": [1500000000000, 1700000000000],
          "domainMax": 1580515200000,
          "range": "width"}],
        "marks": [{"type": "rect", "from": {"data": "t"}}]
      }
      """
        .trimIndent()
    val scale = compile(spec).scales.getValue("x") as TimeScale
    // Rounded outwards from the **bounded** end. Ignoring the bound niced from 1.7e12 and gave a
    // high end above it; honouring it gives one a nice step above 2020-02-01.
    assertTrue(
      scale.domain.last() < 1.6e12,
      "the domainMax was discarded: ${scale.domain}",
    )
    assertTrue(scale.domain.last() >= 1580515200000.0, "the bound must round *outwards*")
  }

  /**
   * C9 — `values` on a gradient legend was cast to a number it need not be.
   *
   * `"legends": [{"fill": "c", "values": ["2020-01-01"]}]` on a continuous colour scale — the
   * natural way to write date stops — threw a `ClassCastException` out of the public `compileJson`,
   * past every catch site in the engine.
   */
  @Test
  fun `a gradient legend with a value that is not a number reports rather than throwing`() {
    val spec =
      """
      {
        "width": 200, "height": 100, "padding": 5,
        "data": [$table],
        "scales": [{"name": "c", "type": "linear", "domain": [0, 10],
          "range": {"scheme": "viridis"}}],
        "legends": [{"fill": "c", "values": ["2020-01-01", 5]}],
        "marks": [{"type": "rect", "from": {"data": "t"}}]
      }
      """
        .trimIndent()
    val compiled = compile(spec)
    assertNotNull(compiled.scene)
    assertTrue(
      compiled.diagnostics.any {
        it.code == DiagnosticCodes.ENCODE_INVALID_VALUE && "2020-01-01" in it.message
      },
      "${compiled.diagnostics}",
    )
  }

  /**
   * H1 — a finite but enormous `tickCount` was an out-of-memory error on the way to a list.
   *
   * Upstream hangs on the same specification, so this is a documented clamp rather than a
   * divergence to hide: the limit is named and the diagnostic says what was asked for.
   */
  @Test
  fun `an enormous tick count is clamped and said so`() {
    val spec =
      """
      {
        "width": 200, "height": 100, "padding": 5,
        "data": [$table],
        "scales": [{"name": "x", "type": "linear", "domain": [0, 1], "range": "width"}],
        "axes": [{"scale": "x", "orient": "bottom", "tickCount": 1000000000}],
        "marks": [{"type": "rect", "from": {"data": "t"}}]
      }
      """
        .trimIndent()
    val compiled = compile(spec)
    assertTrue(
      compiled.diagnostics.any { it.code == DiagnosticCodes.COMPILE_LIMIT_EXCEEDED },
      "${compiled.diagnostics}",
    )
    val labels = compiled.scene!!.flatten().map { it.node }.filterIsInstance<TextNode>()
    // The clamp bounds it; the endpoints make it one more than the count.
    assertTrue(labels.size <= NumberResolver.MAX_TICK_COUNT + 1, "${labels.size} labels")
  }

  /**
   * H4 — a group mark is the only construct that nests, and it nests by recursion.
   *
   * A few thousand levels was a `StackOverflowError`: an `Error` rather than an exception, caught
   * by nothing typed, and unrecoverable on Kotlin/Native.
   *
   * **Eighty groups, not five hundred**, and the difference is the interaction between two limits
   * that guard two different recursions. `MAX_GROUP_DEPTH` is about the *compiler* walking a mark
   * tree, and reaching it produces a diagnostic and still draws what it can — which is what this
   * test is about. `VegaJson.MAX_JSON_DEPTH` is about the *parser* descending, and it has to run
   * first, so a document past it never reaches the compiler at all. A group costs about two JSON
   * levels, so five hundred of them is roughly a thousand levels: past the parser's limit, and
   * refused with `COMPILE_LIMIT_EXCEEDED` before `MAX_GROUP_DEPTH` is ever consulted. Both are
   * refusals; only one of them leaves a scene, and this asserts that one.
   *
   * A hundred is comfortably past `MAX_GROUP_DEPTH` (64) and comfortably inside the parser's budget
   * (about 250 groups), so it exercises the limit it means to.
   */
  @Test
  fun `a mark tree nested past the limit is a diagnostic rather than an overflow`() {
    fun nest(depth: Int): String =
      if (depth == 0) """{"type": "rect", "from": {"data": "t"}}"""
      else
        """{"type": "group", "encode": {"enter": {"width": {"value": 10},
              "height": {"value": 10}}}, "marks": [${nest(depth - 1)}]}"""
    val spec =
      """
      {"width": 100, "height": 100, "padding": 0, "data": [$table],
       "marks": [${nest(80)}]}
      """
        .trimIndent()
    val compiled = compile(spec)
    assertNotNull(compiled.scene, "a scene is still produced")
    assertTrue(
      compiled.diagnostics.any { it.code == DiagnosticCodes.COMPILE_LIMIT_EXCEEDED },
      "${compiled.diagnostics.take(4)}",
    )
    // Nesting a chart actually uses still compiles.
    val shallow =
      """{"width": 100, "height": 100, "padding": 0, "data": [$table],
          "marks": [${nest(4)}]}"""
    assertTrue(
      compile(shallow).diagnostics.none { it.code == DiagnosticCodes.COMPILE_LIMIT_EXCEEDED },
      "four levels is an ordinary chart",
    )
  }

  /**
   * M2 — a comparator that answers zero for a pair it cannot order is not a total order, and the
   * JVM's TimSort throws on one once there are 32 or more items.
   *
   * A field some rows lack is all it takes, and a line chart over a thousand points is the ordinary
   * case. JavaScript's sort does not check, so upstream simply produces some order.
   */
  @Test
  fun `a sort over a field some rows lack does not throw`() {
    val rows =
      (0 until 200).joinToString(", ") { index ->
        if (index % 3 == 0) """{"v": $index}""" else """{"v": $index, "k": ${200 - index}}"""
      }
    val spec =
      """
      {
        "width": 200, "height": 100, "padding": 0,
        "data": [{"name": "t", "values": [$rows]}],
        "scales": [{"name": "x", "type": "linear", "domain": [0, 200], "range": "width"}],
        "marks": [{"type": "rect", "from": {"data": "t"},
          "sort": {"field": "datum.k"},
          "encode": {"enter": {"x": {"scale": "x", "field": "v"},
            "width": {"value": 1}, "y": {"value": 0}, "height": {"value": 10}}}}]
      }
      """
        .trimIndent()
    val compiled = compile(spec)
    val scene = assertNotNull(compiled.scene)
    assertEquals(200, scene.flatten().map { it.node }.filterIsInstance<RectNode>().size)
  }

  /**
   * M1 — a missing scale is a property of the specification, and it was reported once per row.
   *
   * A 10,000-row mark produced 10,000 identical ERROR diagnostics and buried everything else.
   * `reportOnce` was in the same file, unused by any of the three per-datum reports.
   */
  @Test
  fun `a missing scale is reported once rather than once per datum`() {
    val rows = (0 until 60).joinToString(", ") { """{"v": $it}""" }
    val spec =
      """
      {
        "width": 200, "height": 100, "padding": 0,
        "data": [{"name": "t", "values": [$rows]}],
        "marks": [{"type": "rect", "from": {"data": "t"},
          "encode": {"enter": {"x": {"scale": "nope", "field": "v"},
            "width": {"value": 1}, "y": {"value": 0}, "height": {"value": 10}}}}]
      }
      """
        .trimIndent()
    val compiled = compile(spec)
    assertEquals(
      1,
      compiled.diagnostics.count { it.code == DiagnosticCodes.SCALE_NOT_BUILT },
      "${compiled.diagnostics.size} diagnostics for 60 rows",
    )
  }

  /**
   * M7 — a padding wider than the size it is measured inside leaves a negative plotting area.
   *
   * Upstream does the same — probed — so this is not clamped. What it was not doing is saying so,
   * and a chart whose plotting area came out negative is one nobody can read for a reason that is
   * nowhere in the picture.
   */
  @Test
  fun `a padding wider than the chart is reported`() {
    val spec =
      """
      {
        "width": 50, "height": 50, "padding": 40,
        "autosize": {"type": "none", "contains": "padding"},
        "data": [$table],
        "marks": [{"type": "rect", "from": {"data": "t"}}]
      }
      """
        .trimIndent()
    assertTrue(
      compile(spec).diagnostics.any {
        it.code == DiagnosticCodes.COMPILE_LIMIT_EXCEEDED && "negative" in it.message
      },
      "${compile(spec).diagnostics}",
    )
  }

  /**
   * The boundary itself: a compiler defect comes back as a diagnostic, not as a crash.
   *
   * There is no way to make the compiler throw from a *specification* any more — that was the point
   * of the six fixes above — so this reaches the guard through a text engine that fails, which is
   * the shape of every remaining unknown: something the compiler leans on that does not behave.
   */
  @Test
  fun `a defect inside the compiler is a fatal diagnostic rather than a crash`() {
    val failing =
      object : dev.aster.vega.scene.TextEngine {
        override fun measure(
          text: dev.aster.vega.scene.TextRun,
          constraint: dev.aster.vega.scene.SizeD?,
        ): dev.aster.vega.scene.TextMetrics = throw IllegalStateException("no font table")

        override fun layout(
          text: dev.aster.vega.scene.TextRun,
          constraint: dev.aster.vega.scene.SizeD?,
        ): dev.aster.vega.scene.TextLayout = throw IllegalStateException("no font table")
      }
    val spec =
      """
      {
        "width": 200, "height": 100, "padding": 5,
        "data": [$table],
        "scales": [{"name": "x", "type": "linear", "domain": [0, 1], "range": "width"}],
        "axes": [{"scale": "x", "orient": "bottom"}],
        "marks": [{"type": "rect", "from": {"data": "t"}}]
      }
      """
        .trimIndent()
    val compiled = SpecCompiler(textEngine = failing).compileJson(spec)
    val fatal = compiled.diagnostics.single { it.code == DiagnosticCodes.COMPILE_FAILED }
    assertEquals(DiagnosticSeverity.FATAL, fatal.severity)
    assertTrue("no font table" in fatal.message, fatal.message)
    // And the exception is carried, so a host can report it as one.
    assertNotNull(fatal.cause)
  }
}
