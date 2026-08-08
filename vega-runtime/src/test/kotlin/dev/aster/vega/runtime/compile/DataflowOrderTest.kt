package dev.aster.vega.runtime.compile

import dev.aster.vega.expression.CachingExpressionCompiler
import dev.aster.vega.expression.VegaExpressionCompiler
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.spec.SpecParser
import dev.aster.vega.runtime.scale.LinearScale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The order datasets, scales and signals resolve in.
 *
 * Stated over whole specifications rather than hand-built specs, because the interesting cases are
 * all about how a real chart is written. The differential fixtures prove the *result*; these prove
 * the ordering itself, which is otherwise only visible as a chart being subtly wrong.
 */
class DataflowOrderTest {

  private fun order(json: String, diagnostics: DiagnosticCollector = DiagnosticCollector()) =
    SpecParser().parseJson(json).spec!!.let { spec ->
      DataflowOrder.of(
          spec.data,
          spec.scales,
          spec.signals,
          CachingExpressionCompiler(VegaExpressionCompiler()),
          diagnostics,
        )
        .order
    }

  @Test
  fun `a scale over a dataset, and a dataset over that scale, interleave`() {
    // `probability-density`, reduced to the cycle it is not: the scale needs the data and the
    // second
    // dataset needs the scale. Three phases in any fixed arrangement leave one of the two empty.
    val json =
      """
      {
        "data": [
          {"name": "points", "values": [{"u": 1}, {"u": 2}]},
          {"name": "density", "source": "points", "transform": [
            {"type": "density", "extent": {"signal": "domain('xscale')"},
             "distribution": {"function": "kde", "field": "u"}}
          ]}
        ],
        "scales": [
          {"name": "xscale", "type": "linear", "range": "width",
           "domain": {"data": "points", "field": "u"}}
        ]
      }
      """
    assertEquals(
      listOf(Operator.Data("points"), Operator.Scale("xscale"), Operator.Data("density")),
      order(json),
    )
  }

  @Test
  fun `a signal reading no dataset comes before every dataset`() {
    // The property the old three-phase seeding had, kept: a transform parameter written as a signal
    // has to read a number, and `clamp(year, 1980, 2010)` is the ordinary shape of one.
    val json =
      """
      {
        "signals": [
          {"name": "year", "value": 1990},
          {"name": "shown", "update": "clamp(year, 1980, 2010)"},
          {"name": "biggest", "update": "data('t')[0].v"}
        ],
        "data": [{"name": "t", "values": [{"v": 1}]}]
      }
      """
    val result = order(json)
    val firstData = result.indexOfFirst { it is Operator.Data }
    assertTrue(result.indexOf(Operator.Signal("year")) < firstData, result.toString())
    assertTrue(result.indexOf(Operator.Signal("shown")) < firstData, result.toString())
    // And the one that *does* read a dataset waits for it.
    assertTrue(result.indexOf(Operator.Signal("biggest")) > firstData, result.toString())
  }

  @Test
  fun `a scale with a width range waits on a declared width signal`() {
    // A chart whose width is computed sizes its scales from the computed value, not from the
    // property that only seeded it.
    val json =
      """
      {
        "signals": [{"name": "width", "update": "40 * 3"}],
        "data": [{"name": "t", "values": [{"v": 1}]}],
        "scales": [
          {"name": "x", "type": "linear", "range": "width",
           "domain": {"data": "t", "field": "v"}}
        ]
      }
      """
    val result = order(json)
    assertTrue(
      result.indexOf(Operator.Signal("width")) < result.indexOf(Operator.Scale("x")),
      result.toString(),
    )
  }

  @Test
  fun `a signal calling scale is built after that scale`() {
    // The Wilkinson dot plot's shape: a step in data units turned into pixels.
    val json =
      """
      {
        "signals": [{"name": "size", "update": "scale('x', 1) - scale('x', 0)"}],
        "data": [{"name": "t", "values": [{"v": 1}]}],
        "scales": [
          {"name": "x", "type": "linear", "range": "width",
           "domain": {"data": "t", "field": "v"}}
        ]
      }
      """
    val result = order(json)
    assertTrue(
      result.indexOf(Operator.Scale("x")) < result.indexOf(Operator.Signal("size")),
      result.toString(),
    )
  }

  @Test
  fun `a cycle is reported as the path that closed it and still yields an order`() {
    // A scale over a dataset whose own transform reads that scale's domain: genuinely circular.
    val json =
      """
      {
        "data": [
          {"name": "t", "values": [{"v": 1}], "transform": [
            {"type": "formula", "as": "w", "expr": "1"},
            {"type": "filter", "expr": "datum.v > 0"},
            {"type": "extent", "field": "v", "signal": "span"},
            {"type": "collect", "sort": {"field": {"signal": "domain('x')[0]"}}}
          ]}
        ],
        "scales": [
          {"name": "x", "type": "linear", "range": "width",
           "domain": {"data": "t", "field": "v"}}
        ]
      }
      """
    val diagnostics = DiagnosticCollector()
    val result = order(json, diagnostics)
    // Every operator still gets a place, so the chart draws and the report is what says it is
    // wrong.
    assertEquals(2, result.size)
    val cycle = diagnostics.diagnostics.single { it.code == DiagnosticCodes.SIGNAL_CYCLE }
    assertTrue(cycle.message.contains("dataset 't'"), cycle.message)
    assertTrue(cycle.message.contains("scale 'x'"), cycle.message)
  }

  /**
   * A name identifies an operator, so a name declared twice is one operator, not two.
   *
   * Upstream refuses the specification outright — "Duplicate data set name" — and this reports it
   * and lets the later definition win, which is how a duplicate *signal* has always been handled
   * here. Counting the declarations instead of the names made the order shorter than the node list
   * it was filling, which took the whole compile down with a `NoSuchElementException`.
   */
  @Test
  fun `a duplicate name is one operator, reported, with the later definition winning`() {
    val json =
      """
      {
        "width": 100, "height": 50,
        "data": [
          {"name": "t", "values": [{"v": 1}]},
          {"name": "t", "values": [{"v": 9}]}
        ],
        "scales": [
          {"name": "x", "type": "linear", "domain": [0, 1], "range": [0, 10]},
          {"name": "x", "type": "linear", "domain": [0, 2], "range": [0, 20]}
        ]
      }
      """
    val diagnostics = DiagnosticCollector()
    assertEquals(listOf(Operator.Data("t"), Operator.Scale("x")), order(json, diagnostics))
    assertEquals(
      listOf(
        "Duplicate dataset 't'; the later definition wins",
        "Duplicate scale 'x'; the later definition wins",
      ),
      diagnostics.diagnostics.map { it.message },
    )

    // And the whole compile survives it, which is the part that did not.
    val compiled = SpecCompiler().compileJson(json)
    assertTrue(compiled.isUsable)
    assertEquals(listOf(0.0, 2.0), (compiled.scales["x"] as LinearScale).domain)
  }

  /**
   * A transform's `signal` names a signal it **writes**, so reading it waits for that dataset.
   *
   * Nothing declares the name, which is what made it invisible: `span` looked like a signal reading
   * nothing and went first, against a name with no value. Upstream has no such gap —
   * `parseTransform` does `scope.addSignal(spec.signal, scope.proxy(t))`, so the published name is
   * an operator standing in for the transform.
   */
  @Test
  fun `a signal reading a signal a transform published waits for that dataset`() {
    val json =
      """
      {
        "signals": [{"name": "span", "update": "vals[1] - vals[0]"}],
        "data": [
          {"name": "t", "values": [{"v": 3}, {"v": 11}],
           "transform": [{"type": "extent", "field": "v", "signal": "vals"}]}
        ]
      }
      """
    assertEquals(listOf(Operator.Data("t"), Operator.Signal("span")), order(json))
  }

  /**
   * And a dataset reading a signal an *earlier* dataset published waits for it too.
   *
   * The same edge, reached through a `{"signal": ...}` transform parameter rather than through a
   * declared signal's expression.
   */
  @Test
  fun `a dataset reading another dataset's published signal waits for it`() {
    val json =
      """
      {
        "data": [
          {"name": "later", "values": [{"v": 1}],
           "transform": [{"type": "filter", "expr": "true"},
                         {"type": "formula", "as": "w", "expr": "1"},
                         {"type": "collect", "sort": {"field": {"signal": "vals[0]"}}}]},
          {"name": "first", "values": [{"v": 3}],
           "transform": [{"type": "extent", "field": "v", "signal": "vals"}]}
        ]
      }
      """
    assertEquals(listOf(Operator.Data("first"), Operator.Data("later")), order(json))
  }

  @Test
  fun `a range element written as a signal is an edge like any other`() {
    // `[{"signal": "barStep"}, {"signal": "width"}]` — the array is not a reference, but each of
    // its
    // elements may be, and a scale built before them lands on the wrong numbers.
    val json =
      """
      {
        "signals": [{"name": "barStep", "update": "7 * 3"}],
        "data": [{"name": "t", "values": [{"v": 1}]}],
        "scales": [
          {"name": "x", "type": "linear",
           "range": [{"signal": "barStep"}, 100],
           "domain": {"data": "t", "field": "v"}}
        ]
      }
      """
    val result = order(json)
    assertTrue(
      result.indexOf(Operator.Signal("barStep")) < result.indexOf(Operator.Scale("x")),
      result.toString(),
    )
  }
}
