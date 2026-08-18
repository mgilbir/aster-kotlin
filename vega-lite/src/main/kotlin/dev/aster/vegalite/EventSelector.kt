package dev.aster.vegalite

import dev.aster.vega.model.VegaValue

/**
 * Vega's event selector grammar, as a parser — `vega-event-selector`.
 *
 * A selection states the events it listens for as a *string*, and Vega parses it into the stream
 * objects a specification would otherwise spell out: `"[pointerdown[!event.shiftKey], pointerup] >
 * pointermove"` is a drag that starts only when the shift key is up. Passing the string through
 * unread — which is what this compiler did — writes a selection that listens for an event called
 * `[pointerdown[!event.shiftKey], pointerup] > pointermove`, and so listens for nothing at all.
 *
 * Ported rather than approximated, because the grammar has more in it than it looks: a source
 * before a colon, a mark name after an `@`, filters in brackets that may themselves contain
 * brackets, a throttle in braces, a `!` for consumption, and commas that separate whole selectors
 * except inside any of those.
 */
internal object EventSelector {

  /** The mark types a bare word before a colon may name, rather than a source. */
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

  /** Parses a selector into the streams it stands for, in the order they were written. */
  fun parse(selector: String, source: String = "view"): List<VegaValue> =
    split(selector.trim()).map { one(it, source) }

  private fun one(part: String, source: String): VegaValue =
    if (part.startsWith("[")) between(part, source) else stream(part, source)

  /**
   * `find`: the next unnested [end], skipping anything inside brackets or braces.
   *
   * A filter may contain a bracket of its own — `[datum.x[0] > 1]` — so the search counts depth
   * rather than stopping at the first match.
   */
  private fun find(s: String, from: Int, end: Char, push: String = "", pop: String = ""): Int {
    var depth = 0
    var i = from
    while (i < s.length) {
      val c = s[i]
      when {
        depth == 0 && c == end -> return i
        pop.contains(c) -> depth--
        push.contains(c) -> depth++
      }
      i++
    }
    return i
  }

  /** `parseMerge`: the comma-separated selectors, commas inside brackets or braces not counting. */
  private fun split(s: String): List<String> {
    val out = mutableListOf<String>()
    var start = 0
    var i = 0
    while (i < s.length) {
      i = find(s, i, ',', "[{", "]}")
      out += s.substring(start, i).trim()
      start = ++i
    }
    return out
  }

  /**
   * `parseBetween`: `[start, end] > during`, which is the whole of how a drag is written.
   *
   * The pair in brackets are the events that open and close the window, and the stream after the
   * `>` is what is listened for while it is open.
   */
  private fun between(s: String, source: String): VegaValue {
    val close = find(s, 1, ']', "[", "]")
    if (close >= s.length) return VegaValue.Str(s)
    val pair = split(s.substring(1, close)).map { one(it, source) }
    val rest = s.substring(close + 1).trim()
    if (!rest.startsWith(">") || pair.size != 2) return VegaValue.Str(s)
    val inner = one(rest.substring(1).trim(), source)
    // A between selector over a stream that is itself one nests rather than merging: the inner
    // stream keeps its own window and this one wraps it.
    if ((inner as? VegaValue.Obj)?.fields?.containsKey("between") == true) {
      return obj {
        put("between", arr(pair))
        put("stream", inner)
      }
    }
    return obj {
      (inner as? VegaValue.Obj)?.fields?.forEach { (key, value) -> put(key, value) }
      put("between", arr(pair))
    }
  }

  /** `parseStream`: one event, with its source, mark, filters, throttle and consumption. */
  private fun stream(selector: String, defaultSource: String): VegaValue {
    var s = selector
    var throttle = 0.0
    var debounce = 0.0
    // The throttle is written last, in braces, and is taken off before anything else is read.
    if (s.endsWith("}")) {
      val open = s.lastIndexOf('{')
      if (open < 0) return VegaValue.Str(selector)
      val parts = s.substring(open + 1, s.length - 1).split(",").map { it.trim().toDoubleOrNull() }
      if (parts.isEmpty() || parts.any { it == null }) return VegaValue.Str(selector)
      throttle = parts[0] ?: 0.0
      debounce = parts.getOrNull(1) ?: 0.0
      s = s.substring(0, open).trim()
    }
    if (s.isEmpty()) return VegaValue.Str(selector)

    val words = mutableListOf<String>()
    var filters: MutableList<String>? = null
    var i = 0
    var start = 0
    val named = s[0] == '@'
    if (named) i++

    var colon = find(s, i, ':')
    if (colon < s.length) {
      words += s.substring(start, colon).trim()
      colon++
      start = colon
      i = colon
    }
    i = find(s, i, '[')
    if (i == s.length) {
      words += s.substring(start, s.length).trim()
    } else {
      words += s.substring(start, i).trim()
      filters = mutableListOf()
      start = ++i
      if (start == s.length) return VegaValue.Str(selector)
    }
    while (i < s.length) {
      i = find(s, i, ']')
      if (i == s.length) return VegaValue.Str(selector)
      filters!! += s.substring(start, i).trim()
      if (i < s.length - 1 && s[++i] != '[') return VegaValue.Str(selector)
      start = ++i
    }
    if (words.isEmpty()) return VegaValue.Str(selector)

    var type = words.last()
    return obj {
      // The source is the default until the selector names one, which it does with a word before
      // the colon: `window:pointerup` listens outside the plot, `pointerup` inside it.
      put("source", defaultSource)
      if (words.size > 1) {
        type = words[1]
        when {
          named -> put("markname", words[0].removePrefix("@"))
          words[0] in MARK_TYPES -> put("marktype", words[0])
          else -> put("source", words[0])
        }
      }
      // `type!` consumes the event: the stream takes it and nothing below sees it, which is how a
      // wheel that zooms a plot stops the page from scrolling under it.
      val consume = type.endsWith("!")
      put("type", if (consume) type.dropLast(1) else type)
      if (consume) put("consume", VegaValue.Bool(true))
      filters?.let { put("filter", arr(it.map { text -> VegaValue.Str(text) })) }
      if (throttle != 0.0) put("throttle", throttle)
      if (debounce != 0.0) put("debounce", debounce)
    }
  }
}
