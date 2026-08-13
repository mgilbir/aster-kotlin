package dev.aster.vega.model.spec

import dev.aster.vega.model.canonicalNumberString

/**
 * One event stream: what to listen to, and what has to be true for it to fire.
 *
 * The [between] pair is the piece that makes a drag expressible without any state of its own —
 * `[mousedown, mouseup] > mousemove` fires only for moves that happen after a press and before the
 * release. That is the whole reason the selector language exists rather than a list of event names.
 *
 * @param nested set when a `between` wraps another stream that already had one of its own. Upstream
 *   keeps the two apart rather than flattening them, because the outer pair gates the inner *pair*
 *   and not the inner stream.
 */
public data class EventStream(
  val source: String = SOURCE_VIEW,
  val type: String? = null,
  val markType: String? = null,
  val markName: String? = null,
  val filters: List<String> = emptyList(),
  val throttle: Double? = null,
  val debounce: Double? = null,
  val consume: Boolean = false,
  val between: List<EventStream> = emptyList(),
  val nested: EventStream? = null,
) {
  public companion object {
    public const val SOURCE_VIEW: String = "view"
    public const val SOURCE_WINDOW: String = "window"

    /** A stream reaching only into the group it was declared in, rather than the whole view. */
    public const val SOURCE_SCOPE: String = "scope"

    /**
     * A clock rather than an input: `{"type": "timer", "throttle": 500}`. It has no event type of
     * its own, so the [type] carries the interval — which is also the key `config.events.timer`
     * tests, upstream's `permit(view, 'timer', throttle)`.
     */
    public const val SOURCE_TIMER: String = "timer"
  }
}

/** What went wrong, so the caller can report it against the right place in the specification. */
public class EventSelectorException(message: String) : IllegalArgumentException(message)

/**
 * The event-selector language: `"[mousedown, mouseup] > mousemove[event.shiftKey]{100, 50}"`.
 *
 * Ported from `vega-event-selector`. It is a hand-written scanner rather than a grammar because the
 * bracket forms nest and the throttle is read from the **end** backwards, which no left-to-right
 * tokeniser handles cleanly.
 *
 * The pieces, in the order they are peeled off:
 * - a trailing `{throttle, debounce}` in milliseconds, either of which may be omitted;
 * - one or more trailing `[filter]` expressions, all of which must hold;
 * - a leading `[a, b] >` pair, which gates everything after it;
 * - a `source:type` prefix, where the source is `view`, `window`, `scope`, a **mark type** like
 *   `rect`, or `@name` for a named mark. Which of those it is comes down to whether the word is a
 *   known mark type — so a mark *named* `rect` cannot be selected without the `@`;
 * - a trailing `!` on the type, meaning the event is consumed and no other stream sees it.
 *
 * Commas separate independent streams, so one selector string can produce several.
 */
public object EventSelector {

  /** The names that are read as mark types rather than as event sources. */
  private val MARK_TYPES =
    setOf(
      "*",
      "arc",
      "area",
      "group",
      "image",
      "line",
      "path",
      "rect",
      "rule",
      "shape",
      "symbol",
      "text",
      "trail",
    )

  private val ILLEGAL = Regex("[\\[\\]{}]")

  /**
   * @param defaultSource what a stream with no explicit source listens to. A stream declared inside
   *   a group defaults to `scope`, not to `view`.
   * @throws EventSelectorException if the string cannot be read; the message names the input.
   */
  public fun parse(
    selector: String,
    defaultSource: String = EventStream.SOURCE_VIEW,
  ): List<EventStream> =
    splitTopLevel(selector.trim()).map { asTimerStream(parseOne(it, defaultSource)) }

  /**
   * Upstream's `eventStream`: a `type` of `"timer"` names a **source** and not an event.
   *
   * Both spellings mean the same clock — `"timer{500}"` as a selector string and `{"type": "timer",
   * "throttle": 500}` as an object — so both are folded onto the [EventStream.SOURCE_TIMER] source
   * here. The throttle becomes the stream's type because that is what a timer is identified and
   * permitted by, and everything that only makes sense for a pointer is dropped, as upstream drops
   * it.
   */
  public fun asTimerStream(stream: EventStream): EventStream =
    if (stream.type != EventStream.SOURCE_TIMER || stream.source == EventStream.SOURCE_TIMER) {
      stream
    } else {
      EventStream(
        source = EventStream.SOURCE_TIMER,
        type = canonicalNumberString(stream.throttle ?: 0.0),
        throttle = stream.throttle,
        filters = stream.filters,
        consume = stream.consume,
        between = stream.between,
      )
    }

  private fun parseOne(text: String, defaultSource: String): EventStream =
    if (text.startsWith("[")) parseBetween(text, defaultSource)
    else parseStream(text, defaultSource)

  /**
   * Splits on commas that are not inside brackets or braces.
   *
   * `"[a, b] > c, d"` is two streams and not four: the commas inside the `between` pair and inside
   * a throttle belong to those, which is why this cannot be a plain `split`.
   */
  private fun splitTopLevel(text: String): List<String> {
    val out = mutableListOf<String>()
    var start = 0
    var i = 0
    while (i < text.length) {
      i = findUnnested(text, i, ',')
      out += text.substring(start, i).trim()
      i++
      start = i
    }
    if (out.isEmpty()) throw EventSelectorException("Empty event selector: '$text'")
    return out
  }

  /** The index of the next [target] at nesting depth zero, or the end of the string. */
  private fun findUnnested(text: String, from: Int, target: Char): Int {
    var depth = 0
    var i = from
    while (i < text.length) {
      val c = text[i]
      when {
        depth == 0 && c == target -> return i
        c == ']' || c == '}' -> depth--
        c == '[' || c == '{' -> depth++
      }
      i++
    }
    return i
  }

  private fun parseBetween(text: String, defaultSource: String): EventStream {
    val close = findUnnested(text.substring(1), 0, ']') + 1
    if (close >= text.length) throw EventSelectorException("Empty between selector: '$text'")

    val parts = splitTopLevel(text.substring(1, close))
    if (parts.size != 2) {
      throw EventSelectorException("A between selector needs exactly two events: '$text'")
    }
    val rest = text.substring(close + 1).trim()
    if (!rest.startsWith(">")) {
      throw EventSelectorException("Expected '>' after a between selector: '$text'")
    }

    val pair = parts.map { parseOne(it, defaultSource) }
    val stream = parseOne(rest.substring(1).trim(), defaultSource)
    // A stream that already carries its own pair is wrapped rather than overwritten: the outer pair
    // gates the inner pair, which is not the same as gating the inner stream.
    return if (stream.between.isNotEmpty()) {
      EventStream(between = pair, nested = stream)
    } else {
      stream.copy(between = pair)
    }
  }

  private fun parseStream(input: String, defaultSource: String): EventStream {
    var s = input
    var throttle: Double? = null
    var debounce: Double? = null

    // The throttle comes off the end first, because a filter may contain a brace of its own.
    if (s.endsWith("}")) {
      val open = s.lastIndexOf('{')
      if (open < 0) throw EventSelectorException("Unmatched right brace: '$input'")
      val numbers =
        s.substring(open + 1, s.length - 1)
          .split(',')
          .map { it.trim() }
          .also { parts ->
            if (parts.size > 2 || parts.all { it.isEmpty() }) {
              throw EventSelectorException("Invalid throttle: '$input'")
            }
          }
      // Either may be blank — `{, 100}` is a debounce with no throttle — but a non-blank one that
      // is not a number is an error rather than a zero.
      throttle = numbers.getOrNull(0).toMilliseconds(input)
      debounce = numbers.getOrNull(1).toMilliseconds(input)
      s = s.substring(0, open).trim()
    }
    if (s.isEmpty()) throw EventSelectorException("Invalid event selector: '$input'")

    var i = 0
    val named = s[0] == '@'
    if (named) i++

    val source = mutableListOf<String>()
    var start = 0
    val colon = findUnnested(s, i, ':')
    if (colon < s.length) {
      source += s.substring(start, colon).trim()
      i = colon + 1
      start = i
    }

    var filters: MutableList<String>? = null
    i = findUnnested(s, i, '[')
    if (i == s.length) {
      source += s.substring(start, s.length).trim()
    } else {
      source += s.substring(start, i).trim()
      filters = mutableListOf()
      i++
      start = i
      if (start == s.length) throw EventSelectorException("Unmatched left bracket: '$input'")
    }

    while (i < s.length) {
      i = findUnnested(s, i, ']')
      if (i == s.length) throw EventSelectorException("Unmatched left bracket: '$input'")
      filters!! += s.substring(start, i).trim()
      i++
      if (i < s.length && s[i] != '[') {
        throw EventSelectorException("Expected a left bracket: '$input'")
      }
      i++
      start = i
    }

    if (source.isEmpty() || ILLEGAL.containsMatchIn(source.last())) {
      throw EventSelectorException("Invalid event selector: '$input'")
    }

    var type: String
    var markType: String? = null
    var markName: String? = null
    var streamSource = defaultSource
    if (source.size > 1) {
      type = source[1]
      when {
        named -> markName = source[0].substring(1)
        source[0] in MARK_TYPES -> markType = source[0]
        else -> streamSource = source[0]
      }
    } else {
      type = source[0]
    }

    var consume = false
    if (type.endsWith("!")) {
      consume = true
      type = type.dropLast(1)
    }

    return EventStream(
      source = streamSource,
      type = type,
      markType = markType,
      markName = markName,
      filters = filters ?: emptyList(),
      throttle = throttle?.takeIf { it != 0.0 },
      debounce = debounce?.takeIf { it != 0.0 },
      consume = consume,
    )
  }

  private fun String?.toMilliseconds(input: String): Double? {
    if (this.isNullOrEmpty()) return null
    return toDoubleOrNull() ?: throw EventSelectorException("Invalid throttle: '$input'")
  }
}
