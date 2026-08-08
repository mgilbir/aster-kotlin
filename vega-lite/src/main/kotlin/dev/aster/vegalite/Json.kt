package dev.aster.vegalite

import dev.aster.vega.model.VegaValue

/**
 * Small builders over [VegaValue], because this module's whole job is assembling one large object.
 *
 * Key order matters here in a way it does not elsewhere: upstream emits its Vega specification with
 * a particular property order, and keeping the same one makes the two outputs diffable by eye. The
 * comparison itself is order-insensitive; a reader is not.
 */
internal class ObjectBuilder {
  private val fields = LinkedHashMap<String, VegaValue>()

  /** Omits a null, so an optional property is written as `put("x", maybe)` and nothing else. */
  fun put(key: String, value: VegaValue?) {
    if (value != null) fields[key] = value
  }

  fun put(key: String, value: String?) {
    if (value != null) fields[key] = VegaValue.Str(value)
  }

  fun put(key: String, value: Double?) {
    if (value != null) fields[key] = VegaValue.Num(value)
  }

  fun put(key: String, value: Int?) {
    if (value != null) fields[key] = VegaValue.Num(value.toDouble())
  }

  fun put(key: String, value: Boolean?) {
    if (value != null) fields[key] = VegaValue.Bool(value)
  }

  fun putAll(other: VegaValue.Obj?) {
    other?.fields?.forEach { (key, value) -> fields[key] = value }
  }

  fun remove(key: String) {
    fields.remove(key)
  }

  fun has(key: String): Boolean = fields.containsKey(key)

  val isEmpty: Boolean
    get() = fields.isEmpty()

  fun build(): VegaValue.Obj = VegaValue.Obj(fields)
}

internal fun obj(block: ObjectBuilder.() -> Unit): VegaValue.Obj =
  ObjectBuilder().apply(block).build()

internal fun arr(values: List<VegaValue>): VegaValue.Arr = VegaValue.Arr(values)

internal fun arr(vararg values: VegaValue): VegaValue.Arr = VegaValue.Arr(values.toList())

internal fun str(value: String): VegaValue.Str = VegaValue.Str(value)

internal fun num(value: Double): VegaValue.Num = VegaValue.Num(value)

internal fun num(value: Int): VegaValue.Num = VegaValue.Num(value.toDouble())

internal fun bool(value: Boolean): VegaValue.Bool = VegaValue.Bool(value)

internal fun strings(values: List<String>): VegaValue.Arr = VegaValue.Arr(values.map(::str))

/** `{"signal": "..."}`, the reference upstream writes wherever a value is computed at runtime. */
internal fun signalRef(expression: String): VegaValue.Obj = obj { put("signal", expression) }

internal val VegaValue.asObject: VegaValue.Obj?
  get() = this as? VegaValue.Obj

internal operator fun VegaValue?.get(key: String): VegaValue? =
  (this as? VegaValue.Obj)?.fields[key]

internal fun VegaValue?.string(key: String): String? = (this[key] as? VegaValue.Str)?.value

internal fun VegaValue?.number(key: String): Double? = (this[key] as? VegaValue.Num)?.value

internal fun VegaValue?.boolean(key: String): Boolean? = (this[key] as? VegaValue.Bool)?.value

internal fun VegaValue?.obj(key: String): VegaValue.Obj? = this[key] as? VegaValue.Obj

internal fun VegaValue?.array(key: String): List<VegaValue>? = (this[key] as? VegaValue.Arr)?.values

/** True when the key is present at all, however it is valued — `hasProperty` upstream. */
internal fun VegaValue?.has(key: String): Boolean =
  (this as? VegaValue.Obj)?.fields?.containsKey(key) == true

/**
 * A JavaScript string literal the way `vega-util`'s `stringValue` writes one, single-quoted.
 *
 * Every expression this compiler emits is text that Vega will parse, so a field name reaches it
 * through this rather than through string concatenation.
 */
internal fun jsString(value: String): String {
  val escaped = value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
  return "'$escaped'"
}

/** A double-quoted literal, which is what upstream uses inside `datum[...]` accessors. */
internal fun quoted(value: String): String {
  val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
  return "\"$escaped\""
}
