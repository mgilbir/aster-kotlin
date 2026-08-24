package dev.aster.vega.model

/**
 * A [VegaValue] read by a **foreign host** that does not know its shape.
 *
 * The same boundary problem `ForeignPaint` exists for, in the type a host reads data through. `Obj`
 * and `Arr` are `value class`es, so `fields` and `values` live on bodies that have **no Obj-C
 * representation**: from Swift a datum is readable one *known* field at a time — `field(_:path:)`,
 * `asString`, `asDouble` — and not at all if the question is "what is in here".
 *
 * That is enough for a host that knows its own data and not for one displaying whatever a
 * specification carried. Reported as #120, whose author was reading `NodeMetadata.datum` and could
 * get at a named field but could not enumerate one.
 *
 * Two things here are deliberately **not** what the existing extensions do.
 *
 * [string], [number] and [boolean] do not coerce. `asString` renders a number, a boolean, an array
 * and an object all as text — `3`, `true`, `a,b`, `k:v` — which is right for an expression and
 * wrong for a host deciding how to display a field: it cannot tell a string that says "3" from a
 * number. These answer null for anything but their own kind.
 *
 * [kind] names the shape, so a host can ask once rather than trying each reader in turn. It is the
 * same trick `SceneNode.foreignKind()` plays for a mark, and the same strings the Kotlin side would
 * use.
 */
public object ForeignValue {

  /**
   * What this value *is*: `"null"`, `"boolean"`, `"number"`, `"string"`, `"timestamp"`, `"array"`,
   * `"object"`, `"pattern"`, or `"missing"` for a null reference.
   *
   * Deliberately strings rather than an enum: an enum crosses as a boxed class a host has to import
   * and compare, where a string is a `switch` in any language. `foreignKind()` made the same
   * choice.
   */
  public fun kind(value: VegaValue?): String =
    when (value) {
      null -> "missing"
      is VegaValue.Null -> "null"
      is VegaValue.Bool -> "boolean"
      is VegaValue.Num -> "number"
      is VegaValue.Str -> "string"
      is VegaValue.Timestamp -> "timestamp"
      is VegaValue.Arr -> "array"
      is VegaValue.Obj -> "object"
      is VegaValue.Pattern -> "pattern"
    }

  /** An object's keys in insertion order, or empty for anything else. */
  public fun keys(value: VegaValue?): List<String> =
    (value as? VegaValue.Obj)?.fields?.keys?.toList().orEmpty()

  /**
   * How many entries a value holds: an array's length, an object's field count, 0 for anything
   * else.
   *
   * One function for both because a host walking a datum asks the same question of either, and
   * `kind` is what says which it is holding.
   */
  public fun count(value: VegaValue?): Int =
    when (value) {
      is VegaValue.Arr -> value.values.size
      is VegaValue.Obj -> value.fields.size
      else -> 0
    }

  /** The element at [index] of an array, or null when it is not one or the index is outside it. */
  public fun at(value: VegaValue?, index: Int): VegaValue? =
    (value as? VegaValue.Arr)?.values?.getOrNull(index)

  /** The value under [key], or null when this is not an object or has no such field. */
  public fun get(value: VegaValue?, key: String): VegaValue? =
    (value as? VegaValue.Obj)?.fields?.get(key)

  /**
   * The text of a string value, and **null for anything else** — see the note on coercion above.
   */
  public fun string(value: VegaValue?): String? = (value as? VegaValue.Str)?.value

  /**
   * The number of a numeric value, and null for anything else.
   *
   * A [VegaValue.Timestamp] answers its epoch milliseconds, because it *is* a number and a host
   * displaying one wants it; `kind` is what distinguishes the two.
   */
  public fun number(value: VegaValue?): Double? =
    when (value) {
      is VegaValue.Num -> value.value
      is VegaValue.Timestamp -> value.epochMillis
      else -> null
    }

  /** The flag of a boolean value, and null for anything else. */
  public fun boolean(value: VegaValue?): Boolean? = (value as? VegaValue.Bool)?.value
}
