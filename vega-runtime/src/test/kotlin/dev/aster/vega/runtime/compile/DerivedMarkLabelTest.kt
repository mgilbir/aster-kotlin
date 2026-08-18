package dev.aster.vega.runtime.compile

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.scene.flatten
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The label a mark gets when the specification supplies none, and the value it speaks.
 *
 * The label itself is a deliberate divergence from upstream and `MarkEncoder.describe` explains
 * why: exploring a touch screen means landing on individual marks, and an unlabelled one announces
 * nothing. What was wrong is the **value**. `describe` read the first scaled channel's column
 * straight off the datum, and on any time series the first scaled channel is temporal — so a reader
 * exploring a line of depression scores heard a thirteen-digit epoch number where they expected a
 * date. The formatted text existed already, on the scale that placed the mark.
 */
class DerivedMarkLabelTest {

  /**
   * The labels marks carry, joined the way `AccessibilityTree` joins them.
   *
   * @param derivedOnly the default, because a guide carries a caption of its own and this file is
   *   about the labels a *mark* gets when the specification supplies none.
   */
  private fun labels(json: String, derivedOnly: Boolean = true): List<String> =
    requireNotNull(SpecCompiler(VegaHeadlessTextEngine()).compileJson(json).scene) { "no scene" }
      .flatten()
      .map { it.node }
      .mapNotNull { node ->
        node.metadata.accessibility
          ?.takeIf { !derivedOnly || it.derived }
          ?.takeIf { node.metadata.role == "mark" }
          ?.let { descriptor ->
            descriptor.value?.let { "${descriptor.label}: $it" } ?: descriptor.label
          }
      }

  /** A weekly score at ten in the morning, which is the shape a captured payload has. */
  private val timeSeries =
    """
    {
      "width": 300, "height": 120, "padding": 5,
      "data": [{
        "name": "t",
        "values": [{"t": "2026-06-17T10:00:00", "v": 5.25}],
        "format": {"parse": {"t": "date"}}
      }],
      "scales": [
        {"name": "x", "type": "time", "domain": {"data": "t", "field": "t"}, "range": "width"},
        {"name": "y", "type": "linear", "domain": [0, 27], "range": "height"}
      ],
      "marks": [{"type": "symbol", "from": {"data": "t"}, "encode": {"enter": {
        "x": {"scale": "x", "field": "t"}, "y": {"scale": "y", "field": "v"}}}}]
    }
    """
      .trimIndent()

  @Test
  fun `a temporal channel is spoken as a date, not as an epoch`() {
    val label = labels(timeSeries).single()

    assertFalse(
      label.contains("1781"),
      "the epoch is being read out — this is the defect: $label",
    )
    // The instant carries a time of day, so the reader gets both: en-US order, since no locale was
    // supplied.
    assertEquals("6/17/2026 10:00:00 AM: 5", label)
  }

  @Test
  fun `an instant at midnight is spoken as a date alone`() {
    // What a daily series carries, and what `timeUnit: yearmonthdate` truncates to: no clock to
    // report, so none is read out.
    val label = labels(timeSeries.replace("2026-06-17T10:00:00", "2026-06-17T00:00:00")).single()
    assertEquals("6/17/2026: 5", label)
  }

  @Test
  fun `a continuous value keeps the decimals its own scale would label`() {
    // The y value is 5.25 over a domain of [0, 27]: the scale's own tick step implies no decimals,
    // so
    // a reader hears "5" — the number the axis beside them shows, rather than the full double.
    assertTrue(labels(timeSeries).single().endsWith(": 5"), labels(timeSeries).single())

    // The same value on a domain a tenth as wide, where the step does imply a decimal.
    val fine = timeSeries.replace(""""domain": [0, 27]""", """"domain": [5, 5.5]""")
    assertTrue(labels(fine).single().endsWith(": 5.3"), labels(fine).single())
  }

  @Test
  fun `a category is spoken as itself`() {
    val json =
      """
      {
        "width": 200, "height": 100, "padding": 5,
        "data": [{"name": "t", "values": [{"c": "Total score", "v": 18}]}],
        "scales": [
          {"name": "x", "type": "band", "domain": {"data": "t", "field": "c"}, "range": "width"},
          {"name": "y", "type": "linear", "domain": [0, 27], "range": "height"}
        ],
        "marks": [{"type": "rect", "from": {"data": "t"}, "encode": {"enter": {
          "x": {"scale": "x", "field": "c"}, "width": {"scale": "x", "band": 1},
          "y": {"scale": "y", "field": "v"}, "y2": {"scale": "y", "value": 0}}}}]
      }
      """
        .trimIndent()

    assertEquals("Total score: 18", labels(json).single())
  }

  @Test
  fun `the specification's own description still wins`() {
    val json =
      timeSeries.replace(
        """"y": {"scale": "y", "field": "v"}""",
        """"y": {"scale": "y", "field": "v"},
           "description": {"value": "17 June: five"}""",
      )
    // Not derived any more, so it is read with the filter off — which is the point: a specification
    // that says something gets exactly what it asked for.
    assertEquals(listOf("17 June: five"), labels(json, derivedOnly = false))
  }
}
