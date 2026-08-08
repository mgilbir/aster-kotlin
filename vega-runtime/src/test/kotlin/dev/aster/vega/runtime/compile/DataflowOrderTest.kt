package dev.aster.vega.runtime.compile

import dev.aster.vega.expression.CachingExpressionCompiler
import dev.aster.vega.expression.VegaExpressionCompiler
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.spec.SpecParser
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
