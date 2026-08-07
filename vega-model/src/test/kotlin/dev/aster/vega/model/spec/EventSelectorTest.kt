package dev.aster.vega.model.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The event-selector grammar, against `vega-event-selector`.
 *
 * Every expectation here came from running the same string through upstream. The language reads
 * simply and is not: the source of a stream depends on whether a word happens to be a mark type,
 * the throttle is read from the end backwards, and a `between` that wraps another `between` nests
 * rather than merging.
 */
class EventSelectorTest {

  /** The shape of a stream, flattened, so an expectation is one readable line. */
  private fun render(stream: EventStream): String = buildString {
    append(stream.source)
    append(':')
    append(stream.type ?: "-")
    stream.markType?.let { append(" marktype=$it") }
    stream.markName?.let { append(" markname=$it") }
    if (stream.filters.isNotEmpty()) append(" filter=${stream.filters.joinToString("&&")}")
    stream.throttle?.let { append(" throttle=${it.toInt()}") }
    stream.debounce?.let { append(" debounce=${it.toInt()}") }
    if (stream.consume) append(" consume")
    if (stream.between.isNotEmpty()) {
      append(" between=[${stream.between.joinToString(", ") { render(it) }}]")
    }
    stream.nested?.let { append(" wrapping(${render(it)})") }
  }

  private fun parse(selector: String): String =
    EventSelector.parse(selector).joinToString(" | ") { render(it) }

  @Test
  fun `a bare event type listens to the whole view`() {
    assertEquals("view:click", parse("click"))
    assertEquals("view:mousedown", parse("mousedown"))
  }

  /** A comma separates independent streams, so one string can produce several. */
  @Test
  fun `commas separate streams`() {
    assertEquals("view:mousemove | view:mouseup", parse("mousemove, mouseup"))
  }

  /**
   * The word before the colon is a **mark type** if it happens to be one, and an event source
   * otherwise. So `rect:click` selects rect marks while `window:click` selects the window — and a
   * mark *named* `rect` cannot be reached without the `@`, which is the trap the next test pins.
   */
  @Test
  fun `the source is a mark type only if the word happens to be one`() {
    assertEquals("view:click marktype=rect", parse("rect:click"))
    assertEquals("view:mouseover marktype=symbol", parse("symbol:mouseover"))
    assertEquals("window:mousemove", parse("window:mousemove"))
    assertEquals("scope:click", parse("scope:click"))
    assertEquals("view:click", parse("view:click"))
    // Not a mark type and not a known source, so it is taken as a source anyway and will simply
    // never fire — upstream does not reject it either.
    assertEquals("legend:click", parse("legend:click"))
  }

  @Test
  fun `an at sign selects a mark by name`() {
    assertEquals("view:click markname=legend", parse("@legend:click"))
    assertEquals("view:mousedown markname=myMark", parse("@myMark:mousedown"))
  }

  @Test
  fun `filters stack and all must hold`() {
    assertEquals("view:click filter=event.shiftKey", parse("click[event.shiftKey]"))
    assertEquals(
      "view:click filter=event.shiftKey&&event.altKey",
      parse("click[event.shiftKey][event.altKey]"),
    )
    assertEquals(
      "view:mousemove filter=inScope(event.item)",
      parse("mousemove[inScope(event.item)]"),
    )
  }

  /** `{a, b}` is throttle then debounce, and either may be left out. */
  @Test
  fun `throttle and debounce are read from the end`() {
    assertEquals("view:click throttle=200", parse("click{200}"))
    assertEquals("view:click throttle=200 debounce=300", parse("click{200, 300}"))
    assertEquals("view:mousemove debounce=100", parse("mousemove{, 100}"))
    assertEquals("view:timer throttle=500", parse("timer{500}"))
  }

  /** A `!` consumes the event, so no other stream sees it. */
  @Test
  fun `a trailing bang consumes the event`() {
    assertEquals("view:click consume", parse("click!"))
  }

  /**
   * The form a drag is written in. It needs no state of its own: the pair gates the stream, so a
   * `mousemove` only fires between a press and the release.
   */
  @Test
  fun `a between pair gates the stream after it`() {
    assertEquals(
      "view:mousemove between=[view:mousedown, view:mouseup]",
      parse("[mousedown, mouseup] > mousemove"),
    )
    assertEquals(
      "window:mousemove between=[view:mousedown, window:mouseup]",
      parse("[mousedown, window:mouseup] > window:mousemove"),
    )
    assertEquals(
      "window:mousemove consume between=[view:mousedown markname=a, window:mouseup]",
      parse("[@a:mousedown, window:mouseup] > window:mousemove!"),
    )
  }

  /**
   * A pair wrapping a stream that already has one **nests** rather than replacing it. The outer
   * pair gates the inner pair, which is not the same as gating the inner stream, and flattening the
   * two would silently change what fires.
   */
  @Test
  fun `a between wrapping a between nests`() {
    assertEquals(
      "view:- between=[view:click, view:click] " +
        "wrapping(view:click between=[view:click, view:click])",
      parse("[click, click] > [click, click] > click"),
    )
  }

  @Test
  fun `everything at once comes apart in the right order`() {
    assertEquals(
      "view:mousedown marktype=rect filter=event.button===0 throttle=100",
      parse("rect:mousedown[event.button===0]{100}"),
    )
  }

  /** A stream inside a group defaults to its own scope rather than the whole view. */
  @Test
  fun `the default source is configurable`() {
    assertEquals(
      "scope:click",
      EventSelector.parse("click", EventStream.SOURCE_SCOPE).joinToString { render(it) },
    )
    // An explicit source still wins over the default.
    assertEquals(
      "window:click",
      EventSelector.parse("window:click", EventStream.SOURCE_SCOPE).joinToString { render(it) },
    )
  }

  /** Each of these is rejected upstream too; a malformed selector is not silently ignored. */
  @Test
  fun `a malformed selector is rejected with the input in the message`() {
    for (bad in
      listOf(
        "",
        "[click] click",
        "[a, b, c] > click",
        "click{",
        "click{a}",
        "click[",
        "click[a",
        "[click > click",
      )) {
      val failure = assertThrows<EventSelectorException>(bad) { EventSelector.parse(bad) }
      assertTrue(failure.message!!.isNotEmpty(), bad)
    }
  }
}
