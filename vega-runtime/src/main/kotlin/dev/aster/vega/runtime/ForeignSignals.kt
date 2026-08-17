package dev.aster.vega.runtime

import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.spec.SignalBind
import dev.aster.vega.runtime.compile.CompiledSpec

/**
 * A chart's **controls**, read and written through accessors a foreign host can actually call.
 *
 * The same boundary problem `ForeignPaint` solves for a fill, and for the same reason: every
 * interesting [VegaValue] — `Num`, `Str`, `Bool`, `Timestamp`, `Arr`, `Obj` — is a `@JvmInline
 * value class`, and a value class implementing an interface has no Obj-C representation, so all six
 * are **absent from the generated header**. From Swift a signal's value is therefore an opaque
 * `VegaValue` that cannot be read, and — worse — cannot be *constructed*, which means a host could
 * see a slider and had no way to tell the chart the reader had moved it.
 *
 * `Null` and `Pattern` do cross, being an object and a plain class. That is the whole of what
 * crossed before this file.
 *
 * As with the renderer accessors, the values stay value classes: a signal is read on every pulse of
 * every dataflow, and boxing one to suit one platform's calling convention would be the wrong
 * trade. The questions a host asks get plain functions instead.
 */
public object ForeignSignals {

  // --- Reading a value -------------------------------------------------------

  /** `null`, `boolean`, `number`, `string`, `timestamp`, `array`, `object` or `pattern`. */
  public fun kind(value: VegaValue?): String =
    when (value) {
      null,
      VegaValue.Null -> "null"
      is VegaValue.Bool -> "boolean"
      is VegaValue.Num -> "number"
      is VegaValue.Str -> "string"
      is VegaValue.Timestamp -> "timestamp"
      is VegaValue.Arr -> "array"
      is VegaValue.Obj -> "object"
      is VegaValue.Pattern -> "pattern"
    }

  /** The number a value holds, or null when it is not one. A timestamp answers its epoch millis. */
  public fun number(value: VegaValue?): Double? =
    when (value) {
      is VegaValue.Num -> value.value
      is VegaValue.Timestamp -> value.epochMillis
      else -> null
    }

  /** The boolean a value holds, or null when it is not one. */
  public fun boolean(value: VegaValue?): Boolean? = (value as? VegaValue.Bool)?.value

  /**
   * How a value reads as text, for a label beside a control or in a drop-down.
   *
   * This is [SignalBind.valueText], which is upstream's own formatting for a bound value rather
   * than a second opinion about it — so a host's drop-down shows what a browser's would.
   */
  public fun text(value: VegaValue?): String = SignalBind.valueText(value ?: VegaValue.Null)

  // --- Making a value --------------------------------------------------------

  /** A number, for a slider's new position. */
  public fun ofNumber(value: Double): VegaValue = VegaValue.Num(value)

  /** A boolean, for a checkbox. */
  public fun ofBoolean(value: Boolean): VegaValue = VegaValue.Bool(value)

  /** A string, for a text field. */
  public fun ofString(value: String): VegaValue = VegaValue.Str(value)

  // --- The controls a chart asks for ----------------------------------------

  /**
   * The controls this compiled chart asks a reader for, with the values they currently hold.
   *
   * One call, because the alternative from a foreign host is three: the specification's signals,
   * the scope's resolved values, and then [SignalInput.of] over both. A host that has to assemble
   * that correctly is a host that can assemble it wrongly.
   *
   * Empty for most charts, which declare no `bind` at all.
   */
  public fun inputs(compiled: CompiledSpec): List<SignalInput> {
    val signals = compiled.spec?.signals ?: return emptyList()
    return SignalInput.of(signals, compiled.signals.values)
  }

  /** A [SignalBind.Range]'s bounds, resolved, as `[min, max, step]`. */
  public fun rangeBounds(bind: SignalBind): List<Double>? {
    val range = bind as? SignalBind.Range ?: return null
    // A specification may leave any of the three out, and `SignalInput.resolve` has already filled
    // them in from the signal's own value by the time a host sees this.
    return listOf(range.min ?: 0.0, range.max ?: 100.0, range.step ?: 1.0)
  }

  /** A [SignalBind.Choice]'s options, as the values a signal would become. */
  public fun choiceOptions(bind: SignalBind): List<VegaValue>? =
    (bind as? SignalBind.Choice)?.options

  /**
   * What a reader sees for each of a [SignalBind.Choice]'s options.
   *
   * `labels` defaults to empty and may be shorter than `options`, in which case the option's own
   * text stands in — upstream's `labels[i] || options[i]`, decided here so no host repeats it.
   */
  public fun choiceLabels(bind: SignalBind): List<String>? {
    val choice = bind as? SignalBind.Choice ?: return null
    return choice.options.mapIndexed { index, option ->
      choice.labels.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: text(option)
    }
  }

  /** Whether a [SignalBind.Choice] should be drawn as radio buttons rather than a drop-down. */
  public fun isRadio(bind: SignalBind): Boolean = (bind as? SignalBind.Choice)?.radio ?: false

  /** Which control this is: `checkbox`, `range`, `choice`, `field` or `unknown`. */
  public fun bindKind(bind: SignalBind?): String =
    when (bind) {
      is SignalBind.Checkbox -> "checkbox"
      is SignalBind.Range -> "range"
      is SignalBind.Choice -> "choice"
      is SignalBind.Field -> "field"
      null -> "unknown"
    }
}
