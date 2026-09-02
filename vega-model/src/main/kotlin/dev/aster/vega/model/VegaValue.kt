package dev.aster.vega.model

import io.github.mgilbir.ecma262.RegExp
import kotlin.jvm.JvmInline

/**
 * The generic value model used everywhere in the runtime: parsed specification literals, datum
 * fields, signal values, transform output and expression results.
 *
 * Numbers are always [Double] so that scale, transform and geometry arithmetic stays deterministic
 * across the whole pipeline (ADR 0009). Conversion to `Float` happens only at the Android rendering
 * boundary.
 */
public sealed interface VegaValue {

  public data object Null : VegaValue

  /**
   * JavaScript's `undefined`, which is **not** `null` and which a datum lacking a field yields.
   *
   * The two used to be one value here, and that one value behaved as `null`, which is the opposite
   * of what a chart needs: `Number(null)` is 0 and `Number(undefined)` is NaN, so a filter `datum.x
   * < 10` over rows that have no `x` at all **kept** every one of them where upstream drops them.
   * Ordinary dirty data, and a different chart. `String(undefined)` is `"undefined"` rather than
   * `"null"`, `undefined == null` is true while `undefined === null` is false, and `isDefined` is
   * the one predicate whose whole job is to tell them apart — it answers true for a field that is
   * present and null, and did not.
   *
   * Where it comes from is deliberately narrow: reading a property that is not there, and nothing
   * else. [field] still answers [Null] for a missing path, because that accessor feeds the encoders
   * and transforms, which treat both as missing anyway and would gain nothing but risk from the
   * distinction. What flows out of an expression is a signal value, and upstream's does carry
   * `undefined`, so this one does too.
   */
  public data object Undefined : VegaValue

  @JvmInline public value class Bool(public val value: Boolean) : VegaValue

  @JvmInline public value class Num(public val value: Double) : VegaValue

  @JvmInline public value class Str(public val value: String) : VegaValue

  /** A UTC instant in epoch milliseconds. Kept distinct from [Num] so time scales can dispatch. */
  @JvmInline public value class Timestamp(public val epochMillis: Double) : VegaValue

  @JvmInline public value class Arr(public val values: List<VegaValue>) : VegaValue

  /** Insertion-ordered so that canonical serialization can sort keys explicitly. */
  @JvmInline public value class Obj(public val fields: Map<String, VegaValue>) : VegaValue

  /**
   * A regular expression, which `regexp()` makes and `test()` uses.
   *
   * A variant of its own for the same reason [Timestamp] is one: `isRegExp` has to be able to
   * answer **true**, and a value that stringifies to `/pattern/flags` is not an object with two
   * fields. It carries the compiled [regex] so a filter over ten thousand rows compiles the pattern
   * once, and the [source] and [flags] it was made from because that is what the string form is
   * built out of.
   */
  public class Pattern(public val source: String, public val flags: String = "") : VegaValue {

    /**
     * Compiled once, here, because a `test()` in a filter runs per row.
     *
     * An **ECMA-262** engine rather than Kotlin's `Regex`, because a pattern in a specification is
     * JavaScript's and Kotlin's is the platform's — `java.util.regex` on Android, a different
     * engine on each native target. The four measured divergences that motivated the swap: Java's
     * `$` matches before a final newline where JavaScript's does not, `\a` is an identity escape in
     * Annex B and an error in Java, and `x{` and `[]` — legal, ordinary patterns in a browser —
     * *throw* on the JVM. A specification is data, often pasted data, so those two throws were a
     * chart taken down by a string someone typed.
     */
    public val regex: RegExp = RegExp.compile(source, flags)

    /** Upstream's `String(new RegExp(p, f))`, which is the literal a reader would have written. */
    public val text: String
      get() = "/$source/$flags"

    override fun equals(other: Any?): Boolean =
      other is Pattern && other.source == source && other.flags == flags

    override fun hashCode(): Int = 31 * source.hashCode() + flags.hashCode()

    override fun toString(): String = text

    // No flag translation any more. `i`, `m`, `s`, `g`, `y`, `u`, `v` and `d` all mean here what
    // they mean in a browser, including the three that had to be dropped before: `u`/`v` change
    // what
    // an escape denotes, and `g` decides whether `replace` replaces once or throughout — which this
    // engine applies itself, so the caller no longer branches on the flag text.
  }

  public companion object {
    public val EmptyObject: Obj = Obj(emptyMap())
    public val EmptyArray: Arr = Arr(emptyList())

    public fun of(value: Boolean): VegaValue = Bool(value)

    public fun of(value: Double): VegaValue = Num(value)

    public fun of(value: Int): VegaValue = Num(value.toDouble())

    public fun of(value: String): VegaValue = Str(value)
  }
}

/**
 * `true` for [VegaValue.Null], [VegaValue.Undefined] and numeric NaN, matching Vega's notion of a
 * missing value.
 */
public val VegaValue.isMissing: Boolean
  get() =
    when (this) {
      is VegaValue.Null,
      is VegaValue.Undefined -> true
      is VegaValue.Num -> value.isNaN()
      else -> false
    }

/**
 * `_ == null` in JavaScript: true for both [VegaValue.Null] and [VegaValue.Undefined].
 *
 * The loose comparison against `null` is the one JavaScript idiom that deliberately covers both,
 * and upstream leans on it everywhere — `toNumber`, `toString`, `toBoolean` and `isValid` are all
 * written as `_ == null ? …`. Spelling it once keeps a call site from picking one of the two by
 * accident, which is what the whole of C1 was.
 */
public val VegaValue.isNullish: Boolean
  get() = this is VegaValue.Null || this is VegaValue.Undefined

/**
 * A value that already **is** a number, as a number: `Num` or `Timestamp`, and nothing else.
 *
 * The distinction matters because a date is a `Timestamp` here and a `Date` upstream, and both
 * engines treat one as a number for arithmetic while refusing to call it one:
 * `isNumber(datetime(…))` is false on both sides. So a numeric *read* has to accept a timestamp — a
 * `datetime()` handed to a scale is a number to that scale — while a type *test* must not. A raw
 * `as? VegaValue.Num` does neither: it silently answers null for a date, which is a mark that does
 * not draw.
 */
public fun VegaValue.asNumberOrNull(): Double? =
  when (this) {
    is VegaValue.Num -> value
    is VegaValue.Timestamp -> epochMillis
    else -> null
  }

/**
 * Vega's coercion to number. Returns `NaN` rather than throwing, because Vega expressions and
 * scales are expected to propagate `NaN` instead of failing the whole dataflow.
 */
public fun VegaValue.asDouble(): Double =
  when (this) {
    is VegaValue.Num -> value
    is VegaValue.Timestamp -> epochMillis
    is VegaValue.Bool -> if (value) 1.0 else 0.0
    is VegaValue.Str -> value.trim().toDoubleOrNull() ?: Double.NaN
    is VegaValue.Null,
    is VegaValue.Undefined -> Double.NaN
    is VegaValue.Arr -> if (values.size == 1) values[0].asDouble() else Double.NaN
    is VegaValue.Obj -> Double.NaN
    // `+/a/` in JavaScript is NaN too: a pattern is not a quantity.
    is VegaValue.Pattern -> Double.NaN
  }

/**
 * Vega's coercion to string. Numbers use [canonicalNumberString] so that the same value always
 * produces the same text in labels, SVG output and snapshots.
 */
public fun VegaValue.asString(): String =
  when (this) {
    is VegaValue.Str -> value
    is VegaValue.Num -> canonicalNumberString(value)
    is VegaValue.Timestamp -> canonicalNumberString(epochMillis)
    is VegaValue.Bool -> value.toString()
    is VegaValue.Null -> "null"
    is VegaValue.Undefined -> "undefined"
    is VegaValue.Arr -> values.joinToString(",") { it.asString() }
    is VegaValue.Obj -> fields.entries.joinToString(",") { "${it.key}:${it.value.asString()}" }
    // `'' + regexp('a.b','i')` is `/a.b/i`, which is the literal a reader would have written.
    is VegaValue.Pattern -> text
  }

/** Vega truthiness: `null`, `false`, `0`, `NaN` and the empty string are falsey. */
public fun VegaValue.asBoolean(): Boolean =
  when (this) {
    is VegaValue.Bool -> value
    is VegaValue.Num -> value != 0.0 && !value.isNaN()
    is VegaValue.Timestamp -> epochMillis != 0.0 && !epochMillis.isNaN()
    is VegaValue.Str -> value.isNotEmpty()
    is VegaValue.Null,
    is VegaValue.Undefined -> false
    is VegaValue.Arr -> true
    is VegaValue.Obj -> true
    // Every object is truthy in JavaScript, and a pattern is one.
    is VegaValue.Pattern -> true
  }

/**
 * Resolves a dotted/bracketed Vega field reference such as `a.b`, `a[0]` or `a["b c"]`.
 *
 * Returns [VegaValue.Null] for any missing segment rather than throwing, because Vega treats
 * unresolved field paths as missing data.
 */
public fun VegaValue.field(path: String): VegaValue {
  var current: VegaValue = this
  for (segment in parseFieldPath(path)) {
    current =
      when (current) {
        is VegaValue.Obj -> current.fields[segment] ?: return VegaValue.Null
        is VegaValue.Arr -> {
          val index = segment.toIntOrNull() ?: return VegaValue.Null
          current.values.getOrNull(index) ?: return VegaValue.Null
        }
        else -> return VegaValue.Null
      }
  }
  return current
}

/**
 * Splits a Vega field reference into path segments.
 *
 * Supports `a.b`, `a[0]`, `a['b']`, `a["b"]` and backslash-escaped separators (`a\.b` is a single
 * segment named `a.b`).
 *
 * A transcription of vega-util's `splitAccessPath`, which is the function every upstream `field`
 * accessor is built from, rather than a re-derivation of what the notation looks like it means. The
 * re-derivation was wrong in ways only the original explains: a `.` after a closing bracket
 * separates nothing, because upstream splits on a separator only when there are characters in front
 * of it — so `list[1].b` is three segments and not four with an empty one in the middle, and `a..b`
 * and `.a` are likewise two segments and one. A quote is a quote only immediately after the `[`
 * that opened its bracket, which is what lets `a["b]c"]` keep its `]` and `a[ "b" ]` tolerate the
 * spaces, and what a scan for the next `]` cannot do.
 *
 * One deliberate divergence: upstream *throws* on an unterminated bracket or quote. A field path is
 * data, often pasted data, so the unterminated remainder becomes a literal segment instead and the
 * lookup simply misses (ADR 0011).
 */
public fun parseFieldPath(path: String): List<String> {
  if (path.isEmpty()) return emptyList()
  val length = path.length
  val segments = mutableListOf<String>()
  // Text carried over a backslash escape, prepended to the next segment pushed.
  val carried = StringBuilder()
  // The start of the segment being read, and the start of the open bracket's contents: 0 while no
  // bracket is open, and -1 once a quoted bracket segment has been pushed and only its `]` remains.
  var start = 0
  var bracket = 0
  var quote: Char? = null
  // Where an unterminated bracket or quote began, and how many segments were complete before it,
  // so the remainder can be recovered as literal text instead of throwing.
  var openAt = -1
  var openSegments = 0

  fun push(end: Int) {
    segments.add(carried.toString() + path.substring(start, end))
    carried.setLength(0)
    start = end + 1
  }

  var index = 0
  while (index < length) {
    val ch = path[index]
    when {
      ch == '\\' -> {
        // The escaped character stays in the pending window, so it lands in the next segment
        // verbatim: `a\.b` is the single segment `a.b`.
        carried.append(path, start, index)
        start = index + 1
        index += 2
        continue
      }
      quote != null -> {
        if (ch == quote) {
          push(index)
          quote = null
          // Not 0: the bracket this quote sat in is still open, and only its `]` closes it. A
          // top-level quoted path never reaches 0, which is exactly why upstream rejects one.
          bracket = -1
        }
      }
      start == bracket && (ch == '"' || ch == '\'') -> {
        if (bracket == 0) {
          openAt = index
          openSegments = segments.size
        }
        start = index + 1
        quote = ch
      }
      ch == '.' && bracket == 0 -> if (index > start) push(index) else start = index + 1
      ch == '[' -> {
        if (index > start) push(index)
        openAt = index
        openSegments = segments.size
        bracket = index + 1
        start = index + 1
      }
      ch == ']' -> {
        when {
          // The bracket's contents are the segment.
          bracket > 0 -> {
            push(index)
            bracket = 0
            openAt = -1
          }
          // A quoted bracket already pushed its segment when the quote closed; this only shuts it.
          bracket < 0 -> {
            start = index + 1
            bracket = 0
            openAt = -1
          }
          // A `]` with no bracket open is where upstream errors. Here it is an ordinary character,
          // so it stays in the segment and the lookup misses — dropping it instead would let
          // `a]b` read a field genuinely named `b`, which is worse than reading nothing.
          else -> {}
        }
      }
    }
    index += 1
  }

  if (bracket != 0 || quote != null) {
    // Unterminated. Everything since the opener is literal text, including the opener itself.
    while (segments.size > openSegments) segments.removeAt(segments.size - 1)
    segments.add(path.substring(openAt))
    return segments
  }
  if (index > start) push(minOf(index, length))
  return segments
}
