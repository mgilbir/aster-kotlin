package dev.aster.vega.runtime

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.locale.VegaLocale
import dev.aster.vega.scene.PointD
import dev.aster.vega.scene.SizeD
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A tooltip a host can put on screen, which is the step nobody owned.
 *
 * The value has always been reported — `interactionState.tooltip`, straight from the dataflow — and
 * no renderer draws a bubble, deliberately: a bubble is a design-system decision. What was missing
 * was in between, turning `datum` into lines, and each host improvised it. The iOS session's
 * improvisation was comparing a stringified value against the literal `"{}"` to tell an empty
 * tooltip from a real one, which is the sort of thing that survives for a year and then produces an
 * empty bubble on every mark.
 */
class TooltipContentTest {

  /** JUnit 5's `assertNotNull` returns `Unit`, so this narrows the type as well as asserting. */
  private fun <T : Any> present(value: T?, message: String? = null): T {
    assertNotNull(value, message)
    return value!!
  }

  private val dutch =
    VegaLocale.EnglishUS.copy(
      decimal = ",",
      thousands = ".",
      dateTime = "%d-%m-%Y %H:%M:%S",
    )

  @Test
  fun `a row becomes one line per field, in the order the fields are written`() {
    val content =
      present(
        TooltipContent.of(
          VegaValue.Obj(
            linkedMapOf(
              "Question" to VegaValue.Str("Total score"),
              "Value" to VegaValue.Num(18.0),
            )
          )
        )
      )

    assertEquals(
      listOf("Question" to "Total score", "Value" to "18"),
      content.rows.map { it.label to it.value },
    )
    assertEquals("Question: Total score\nValue: 18", content.text)
  }

  @Test
  fun `a number is written the way the axis beside it writes one`() {
    // The point of going through `formatTickLabel`: a tooltip that says `1,234.5` next to an axis
    // that
    // says `1.234,5` is a chart disagreeing with itself in front of a reader.
    val value = VegaValue.Obj(linkedMapOf("v" to VegaValue.Num(1234.5)))
    assertEquals("v: 1,234.5", present(TooltipContent.of(value)).text)
    assertEquals("v: 1.234,5", present(TooltipContent.of(value, dutch)).text)
  }

  @Test
  fun `a long binary fraction is cut where a tooltip stops being readable`() {
    // 0.1 + 0.2 needs seventeen digits to be itself. Seventeen digits in a bubble is noise, so six
    // —
    // d3's own significant-digit budget — is where this stops.
    val sum = VegaValue.Num(0.1 + 0.2)
    assertEquals("0.3", present(TooltipContent.of(sum)).text)
  }

  @Test
  fun `an instant is written in the locale's own date and time`() {
    val value = VegaValue.Obj(linkedMapOf("t" to VegaValue.Timestamp(1779278400000.0)))
    val utc = present(TooltipContent.of(value, VegaLocale.EnglishUS, TimeZone.UTC)).text
    // d3's `en-US` writes `dateTime` as `%x, %X`, so this is upstream's own wording for the default
    // locale rather than a format chosen here.
    assertEquals("t: 5/20/2026, 12:00:00 PM", utc)
    assertEquals(
      "t: 20-05-2026 12:00:00",
      present(TooltipContent.of(value, dutch, TimeZone.UTC)).text,
    )
  }

  @Test
  fun `nothing to show is null, and an empty object is nothing to show`() {
    assertNull(TooltipContent.of(null))
    assertNull(TooltipContent.of(VegaValue.Null))
    // The one that mattered: a mark with no `tooltip` channel gets an empty object, and treating
    // that
    // as a tooltip is an empty bubble on every mark in the chart.
    assertNull(TooltipContent.of(VegaValue.EmptyObject))
    assertNull(TooltipContent.of(VegaValue.Arr(emptyList())))
    assertNull(TooltipContent.of(VegaValue.Str("")))
  }

  @Test
  fun `a bare value is its own line, with no label invented for it`() {
    val content = present(TooltipContent.of(VegaValue.Str("Total score")))
    assertEquals(listOf(TooltipRow("", "Total score")), content.rows)
    assertEquals("Total score", content.text, "a host with no opinion shows exactly the value")
  }

  @Test
  fun `an array is numbered from one, because a reader counts from one`() {
    val content =
      present(TooltipContent.of(VegaValue.Arr(listOf(VegaValue.Num(3.0), VegaValue.Str("b")))))
    assertEquals(listOf("1" to "3", "2" to "b"), content.rows.map { it.label to it.value })
  }

  /** And the whole way through: a tap on a mark, and the lines a host would draw. */
  @Test
  fun `a controller reports the tooltip of the mark under the pointer`() {
    val controller =
      VegaChartController(
        textEngine = VegaHeadlessTextEngine(),
        containerSize = SizeD(200.0, 100.0),
      )
    controller.setSpec(
      """
      {
        "width": 200, "height": 100, "padding": 0,
        "data": [{"name": "t", "values": [{"c": "Total", "v": 18}]}],
        "scales": [
          {"name": "x", "type": "band", "domain": {"data": "t", "field": "c"}, "range": "width"},
          {"name": "y", "type": "linear", "domain": [0, 27], "range": "height"}
        ],
        "marks": [{"type": "rect", "from": {"data": "t"}, "encode": {"enter": {
          "x": {"scale": "x", "field": "c"}, "width": {"scale": "x", "band": 1},
          "y": {"scale": "y", "field": "v"}, "y2": {"scale": "y", "value": 0},
          "tooltip": {"signal": "datum"}}}}]
      }
      """
        .trimIndent()
    )

    assertNull(controller.tooltipContent, "nothing is under the pointer yet")

    controller.dispatch(ChartInputEvent.Tap(PointD(100.0, 60.0)))
    val content = present(controller.tooltipContent, "a tap on the bar produced no tooltip")

    // The datum's own fields, formatted: what a host draws in a bubble at `tooltipAnchor`.
    assertEquals("c: Total\nv: 18", content.text)
    assertEquals(
      PointD(100.0, 60.0),
      controller.snapshot.interactionState.tooltipAnchor,
      "the anchor is the point the host dispatched, in its own pixels",
    )
  }
}
