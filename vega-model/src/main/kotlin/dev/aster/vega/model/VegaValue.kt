package dev.aster.vega.model

/**
 * The generic value model used everywhere in the runtime: parsed specification literals, datum
 * fields, signal values, transform output and expression results.
 *
 * Numbers are always [Double] so that scale, transform and geometry arithmetic stays deterministic
 * across the whole pipeline (PROJECT_BRIEF.md 4.4). Conversion to `Float` happens only at the
 * Android rendering boundary.
 */
public sealed interface VegaValue {

  public data object Null : VegaValue

  @JvmInline public value class Bool(public val value: Boolean) : VegaValue

  @JvmInline public value class Num(public val value: Double) : VegaValue

  @JvmInline public value class Str(public val value: String) : VegaValue

  /** A UTC instant in epoch milliseconds. Kept distinct from [Num] so time scales can dispatch. */
  @JvmInline public value class Timestamp(public val epochMillis: Double) : VegaValue

  @JvmInline public value class Arr(public val values: List<VegaValue>) : VegaValue

  /** Insertion-ordered so that canonical serialization can sort keys explicitly. */
  @JvmInline public value class Obj(public val fields: Map<String, VegaValue>) : VegaValue

  public companion object {
    public val EmptyObject: Obj = Obj(emptyMap())
    public val EmptyArray: Arr = Arr(emptyList())

    public fun of(value: Boolean): VegaValue = Bool(value)

    public fun of(value: Double): VegaValue = Num(value)

    public fun of(value: Int): VegaValue = Num(value.toDouble())

    public fun of(value: String): VegaValue = Str(value)
  }
}

/** `true` for [VegaValue.Null] and for numeric NaN, matching Vega's notion of a missing value. */
public val VegaValue.isMissing: Boolean
  get() =
    when (this) {
      is VegaValue.Null -> true
      is VegaValue.Num -> value.isNaN()
      else -> false
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
    is VegaValue.Null -> Double.NaN
    is VegaValue.Arr -> if (values.size == 1) values[0].asDouble() else Double.NaN
    is VegaValue.Obj -> Double.NaN
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
    is VegaValue.Arr -> values.joinToString(",") { it.asString() }
    is VegaValue.Obj -> fields.entries.joinToString(",") { "${it.key}:${it.value.asString()}" }
  }

/** Vega truthiness: `null`, `false`, `0`, `NaN` and the empty string are falsey. */
public fun VegaValue.asBoolean(): Boolean =
  when (this) {
    is VegaValue.Bool -> value
    is VegaValue.Num -> value != 0.0 && !value.isNaN()
    is VegaValue.Timestamp -> epochMillis != 0.0 && !epochMillis.isNaN()
    is VegaValue.Str -> value.isNotEmpty()
    is VegaValue.Null -> false
    is VegaValue.Arr -> true
    is VegaValue.Obj -> true
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
 */
public fun parseFieldPath(path: String): List<String> {
  if (path.isEmpty()) return emptyList()
  val segments = mutableListOf<String>()
  val current = StringBuilder()
  var index = 0
  while (index < path.length) {
    when (val ch = path[index]) {
      '\\' -> {
        if (index + 1 < path.length) {
          current.append(path[index + 1])
          index += 2
        } else {
          index += 1
        }
      }
      '.' -> {
        segments.add(current.toString())
        current.setLength(0)
        index += 1
      }
      '[' -> {
        if (current.isNotEmpty() || segments.isEmpty()) {
          if (current.isNotEmpty()) {
            segments.add(current.toString())
            current.setLength(0)
          }
        }
        val close = path.indexOf(']', index)
        if (close < 0) {
          // Unterminated bracket: treat the remainder as literal text.
          current.append(path.substring(index))
          index = path.length
        } else {
          val raw = path.substring(index + 1, close).trim()
          segments.add(raw.removeSurrounding("\"").removeSurrounding("'"))
          index = close + 1
        }
      }
      else -> {
        current.append(ch)
        index += 1
      }
    }
  }
  if (current.isNotEmpty()) segments.add(current.toString())
  return segments
}
