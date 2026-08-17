package dev.aster.vega.runtime

import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.spec.SignalBind
import dev.aster.vega.model.spec.SignalSpec
import dev.aster.vega.runtime.scale.Ticks

/**
 * One control a chart asks a reader for, with the value it currently holds.
 *
 * Everything a host needs to draw a control and nothing about how: the label, the kind of control,
 * and the signal's value right now. A host renders these and calls [VegaChartController.setSignal]
 * with what the reader did — see `VegaChartControls` in `vega-android-canvas` for one that draws
 * them with Material widgets, and `SignalInputTest` for one that drives them with no widgets at
 * all.
 *
 * The value is the **resolved** one, so a slider knows where its handle goes and a drop-down knows
 * which option is selected. It is re-read on every publish, which is what makes the binding
 * two-way: a signal changed by a tap, by another signal or by a timer moves the control that shows
 * it.
 */
public data class SignalInput(
  val signal: String,
  /** What to show beside the control: the binding's `name`, else the signal's own. */
  val label: String,
  /** The control, with a [SignalBind.Range]'s bounds filled in; see [resolve]. */
  val bind: SignalBind,
  val value: VegaValue,
) {

  /** Which option a [SignalBind.Choice] currently holds, or -1 when the value is none of them. */
  public val selectedIndex: Int
    get() {
      val choice = bind as? SignalBind.Choice ?: return -1
      val text = SignalBind.valueText(value)
      return choice.options.indexOfFirst { SignalBind.valueText(it) == text }
    }

  public companion object {

    /**
     * The controls a specification asks for, in the order it declares them.
     *
     * A signal with no `bind` is not a control, which is most of them.
     */
    public fun of(signals: List<SignalSpec>, values: Map<String, VegaValue>): List<SignalInput> =
      signals.mapNotNull { signal ->
        val bind = signal.bind ?: return@mapNotNull null
        val value = values[signal.name] ?: signal.value ?: VegaValue.Null
        SignalInput(
          signal = signal.name,
          label = bind.name ?: signal.name,
          bind = resolve(bind, value),
          value = value,
        )
      }

    /**
     * Fills in a slider's bounds the way upstream's `range` generator does.
     *
     * None of the three is required, and the defaults are not the obvious ones — they are read off
     * the signal's own value so that a bare `{"input": "range"}` still produces a usable slider:
     *
     * - `max` is the stated one, else the larger of 100 and the value;
     * - `min` is the stated one, else the smaller of 0, that max, and the value;
     * - `step` is the stated one, else the tick step d3 would choose for a hundred divisions.
     *
     * Transcribed rather than approximated, because a slider whose bounds differ from upstream's
     * puts the same reader gesture at a different value, and every chart built on one would
     * disagree.
     */
    public fun resolve(bind: SignalBind, value: VegaValue): SignalBind {
      if (bind !is SignalBind.Range) return bind
      val current = value.asDouble()
      val stated = if (current.isNaN()) null else current
      val max = bind.max ?: maxOf(100.0, stated ?: 0.0).takeIf { it != 0.0 } ?: 100.0
      val min =
        bind.min?.takeIf { it != 0.0 } ?: minOf(0.0, max, stated ?: 0.0).takeIf { it != 0.0 } ?: 0.0
      val step = bind.step ?: Ticks.stepFrom(Ticks.tickIncrement(min, max, RANGE_DIVISIONS))
      return bind.copy(min = min, max = max, step = step.takeIf { it.isFinite() && it > 0.0 })
    }

    /** Upstream's `tickStep(min, max, 100)`: a hundred divisions across the slider. */
    private const val RANGE_DIVISIONS = 100
  }
}
