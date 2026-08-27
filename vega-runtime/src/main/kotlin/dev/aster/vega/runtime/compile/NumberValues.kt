package dev.aster.vega.runtime.compile

import dev.aster.vega.expression.ExpressionCompiler
import dev.aster.vega.expression.ExpressionEvaluationException
import dev.aster.vega.expression.ExpressionResult
import dev.aster.vega.expression.JsSemantics
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.spec.ChannelValue
import dev.aster.vega.model.spec.NumberValue

/**
 * Resolves a property that a specification may have supplied as a signal.
 *
 * Mostly numbers, hence the name, but a title's words can come from a signal too.
 *
 * Returns `null` when the value is absent or its expression fails, so the caller applies its own
 * default rather than a shared one — the default for `padding` is not the default for `tickSize`.
 */
public class NumberResolver(
  private val expressions: ExpressionCompiler,
  private val scope: SignalScope,
  private val diagnostics: DiagnosticCollector,
  /**
   * How a **scaled** guide number is read, when there are scales to read it through.
   *
   * Upstream's `numberValue` is a value reference, so `{"scale": "ord", "value": "Cylinders"}` is a
   * legal axis `offset` — and resolving one needs the scales, which this class has no business
   * holding. The guide builders pass the encoder's own channel resolution instead, so a guide
   * number and a mark channel are read by exactly the same code. Absent where no scales exist yet,
   * and then a scaled value is reported rather than guessed at.
   */
  private val scaled: ((ChannelValue) -> Double?)? = null,
) {

  public fun resolve(value: NumberValue?, owner: String): Double? =
    when (value) {
      null -> null
      is NumberValue.Constant -> value.value
      is NumberValue.Reference -> {
        val read = scaled
        if (read == null) {
          diagnostics.warn(
            dev.aster.vega.model.DiagnosticCodes.SCALE_UNSUPPORTED_TYPE,
            "'$owner' takes a scaled value, which needs a scale that is not built yet",
            operator = owner,
          )
          null
        } else {
          read(value.channel)
        }
      }
      is NumberValue.Signal ->
        when (val compiled = expressions.compile(value.expression)) {
          is ExpressionResult.Failed -> {
            diagnostics.add(compiled.diagnostic.copy(operator = owner))
            null
          }
          is ExpressionResult.Compiled ->
            try {
              JsSemantics.toNumber(compiled.expression.evaluate(scope)).takeIf { !it.isNaN() }
            } catch (e: ExpressionEvaluationException) {
              diagnostics.add(e.diagnostic.copy(operator = owner))
              null
            }
        }
    }

  /**
   * The same, as a whole number — and **not** through Kotlin's `toInt`, which is a trap here.
   *
   * `Double.POSITIVE_INFINITY.toInt()` is `Int.MAX_VALUE`, so a `"tickCount": {"signal": "1/0"}`
   * asked an axis for two billion ticks and exhausted the heap. Upstream answers *no* ticks for
   * that — `tickIncrement` gives a step of zero and `ticks` returns `[]` — so a non-finite count
   * becomes zero, which every caller already treats as "none". Found by replaying d3-array's own
   * vectors, which pass `Infinity` on purpose.
   */
  public fun resolveInt(value: NumberValue?, owner: String): Int? =
    resolve(value, owner)?.let { if (it.isFinite()) it.toInt() else 0 }

  /**
   * A `tickCount`, clamped to something this compiler will actually build.
   *
   * A tick count is an ordinary number in a specification and nothing bounded it, so `"tickCount":
   * 1e9` asked for a billion-element list — an out-of-memory error on the way to building it, or,
   * through `countWithMinStep`'s walk-down, a loop of a billion iterations first. Upstream hangs on
   * the same specification, so this is a documented clamp rather than a divergence to hide: the
   * limit is named, the diagnostic says what was asked for and what was used, and `ScaleResolver`'s
   * `MAX_BINS` is the same idea one file over.
   *
   * Ten thousand is far past any axis a person reads and far below any allocation that matters.
   */
  public fun resolveTickCount(value: NumberValue?, owner: String): Int? {
    val requested = resolveInt(value, owner) ?: return null
    if (requested <= MAX_TICK_COUNT) return requested
    diagnostics.warn(
      DiagnosticCodes.COMPILE_LIMIT_EXCEEDED,
      "A tick count of $requested was asked for on '$owner' and $MAX_TICK_COUNT is the most this " +
        "engine will build; the guide was drawn with $MAX_TICK_COUNT",
      operator = owner,
    )
    return MAX_TICK_COUNT
  }

  public companion object {
    /** See [resolveTickCount]. */
    public const val MAX_TICK_COUNT: Int = 10_000
  }

  /**
   * Evaluates an expression to text, for the properties whose value is words rather than a size.
   */
  public fun resolveText(expression: String, owner: String): String? =
    when (val compiled = expressions.compile(expression)) {
      is ExpressionResult.Failed -> {
        diagnostics.add(compiled.diagnostic.copy(operator = owner))
        null
      }
      is ExpressionResult.Compiled ->
        try {
          JsSemantics.toStringValue(compiled.expression.evaluate(scope))
        } catch (e: ExpressionEvaluationException) {
          diagnostics.add(e.diagnostic.copy(operator = owner))
          null
        }
    }

  /**
   * A guide's title, which upstream lets a signal supply as **lines** rather than as one string.
   *
   * `['Local Density', '(Normalized)']` is a two-line title: upstream's `textLines` reads an array
   * as the lines and collapses a one-element array to its element. Stringifying it instead joins
   * the lines with a comma and draws them on one, which is a different chart and a wider legend.
   */
  public fun resolveLines(expression: String, owner: String): String? {
    val value = resolveValue(expression, owner) ?: return null
    if (value !is VegaValue.Arr) return JsSemantics.toStringValue(value)
    if (value.values.size == 1) return JsSemantics.toStringValue(value.values.first())
    return value.values.joinToString("\n") { JsSemantics.toStringValue(it) }
  }

  /** The raw value of a signal, for a property whose shape depends on what the signal holds. */
  public fun resolveValue(expression: String, owner: String): VegaValue? =
    when (val compiled = expressions.compile(expression)) {
      is ExpressionResult.Failed -> {
        diagnostics.add(compiled.diagnostic.copy(operator = owner))
        null
      }
      is ExpressionResult.Compiled ->
        try {
          compiled.expression.evaluate(scope)
        } catch (e: ExpressionEvaluationException) {
          diagnostics.add(e.diagnostic.copy(operator = owner))
          null
        }
    }

  /**
   * Evaluates an expression to a list of values, for the places a signal supplies a whole array.
   *
   * A scale domain is the common one: the `extent` transform publishes a two-element array and a
   * specification points a scale straight at it. A signal that is not an array is treated as a
   * one-element list rather than rejected, which is what upstream's own array coercion does.
   */
  public fun resolveList(expression: String, owner: String): List<VegaValue>? =
    when (val compiled = expressions.compile(expression)) {
      is ExpressionResult.Failed -> {
        diagnostics.add(compiled.diagnostic.copy(operator = owner))
        null
      }
      is ExpressionResult.Compiled ->
        try {
          when (val value = compiled.expression.evaluate(scope)) {
            is VegaValue.Arr -> value.values
            VegaValue.Null -> null
            else -> listOf(value)
          }
        } catch (e: ExpressionEvaluationException) {
          diagnostics.add(e.diagnostic.copy(operator = owner))
          null
        }
    }
}
