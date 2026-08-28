package dev.aster.vega.model.spec

import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asString
import dev.aster.vega.model.isNullish

/**
 * `bind` — the control a specification asks for so a **reader** can change a signal.
 *
 * A hundred and forty-nine of the diagnostics Vega's own examples produced said the same thing
 * here: a binding creates an input widget and there was no equivalent. That is half true and was
 * the wrong conclusion. Upstream's binding is two things bolted together — a *description* of a
 * control, and a DOM implementation of it — and only the second half is a browser's. The
 * description is grammar like any other, so it is parsed like any other, and what a host does with
 * it is the host's business: this module names the control and nothing more.
 *
 * Which is also why there is no Compose in sight. The same description drives a Material slider on
 * Android, a native control on another platform, or a test that sets a value with no widget at all
 * — see `SignalInput` in `vega-runtime` for the value that goes with it, and
 * `VegaChartController.setSignal` for the way back.
 *
 * The five shapes are upstream's, which are the five its `bind.js` generates rather than a taxonomy
 * invented here. [name] is the label a reader sees, defaulting to the signal's own name, and
 * [debounceMillis] is how long a host should let a value settle before acting on it — a slider
 * dragged across its range asks for one redraw at the end, not two hundred.
 */
public sealed interface SignalBind {

  /** The label, or null to use the signal's name — upstream's `param.name || param.signal`. */
  public val name: String?

  /** `debounce`, in milliseconds. */
  public val debounceMillis: Double?

  /** A checkbox, for a signal that is on or off. Its value is a **boolean**. */
  public data class Checkbox(
    override val name: String? = null,
    override val debounceMillis: Double? = null,
  ) : SignalBind

  /**
   * A slider, for a number in a range.
   *
   * All three bounds are optional in a specification and upstream fills them in from the signal's
   * own value, which is why they are nullable here and resolved where the value is known — see
   * `SignalInput`.
   */
  public data class Range(
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    override val name: String? = null,
    override val debounceMillis: Double? = null,
  ) : SignalBind

  /**
   * One of a fixed set of values, as a drop-down or as a row of radio buttons.
   *
   * The two differ only in how they are drawn, which is why upstream describes them with one shape
   * and one required property. [labels] is what the reader sees and [options] is what the signal
   * becomes; a missing or short label list falls back to the option itself, upstream's `labels[i]
   * || option`.
   */
  public data class Choice(
    val options: List<VegaValue>,
    val labels: List<String> = emptyList(),
    /** `radio` rather than `select`: the same choice with every option visible at once. */
    val radio: Boolean = false,
    override val name: String? = null,
    override val debounceMillis: Double? = null,
  ) : SignalBind {

    /** What to show for the option at [index], which is its label if it has one. */
    public fun labelAt(index: Int): String =
      labels.getOrNull(index)?.takeIf { it.isNotEmpty() }
        ?: options.getOrNull(index)?.let { valueText(it) }
        ?: ""
  }

  /**
   * A typed field, which is every other input a specification can name.
   *
   * Upstream passes `input` straight through as an HTML input type, so the vocabulary is open:
   * `text`, `number`, `color`, `date`, `time`, `month`, `password`, and whatever a browser adds
   * next. [input] is carried verbatim rather than mapped onto an enum, because a host knows better
   * than this module which of its own widgets fits — and a host that does not recognise one can
   * fall back to a text field, exactly as a browser does.
   *
   * [attributes] is the rest of the binding, and it is open for the same reason. Upstream's generic
   * generator copies *every* remaining property onto the input element — `placeholder`,
   * `autocomplete`, `maxlength`, `min` and `max` on a `number`, `title`, anything — and its schema
   * agrees: the variant for an input outside the four structured kinds is the only one of the five
   * that sets `additionalProperties: true`. So these are grammar rather than stray keys, and
   * dropping them with a diagnostic would report a gap that is really a decision not to carry a
   * hint the specification took the trouble to write. `job-voyager`, one of Vega's own examples,
   * asks for a `placeholder` on the field that filters it.
   *
   * A host uses the ones it has a widget for and ignores the rest, which is also what a browser
   * does with an attribute the element does not know. Values stay [VegaValue]s rather than strings
   * because a `number` field's `min` is a number to whatever honours it.
   */
  public data class Field(
    val input: String,
    val attributes: Map<String, VegaValue> = emptyMap(),
    override val name: String? = null,
    override val debounceMillis: Double? = null,
  ) : SignalBind {

    /** An attribute as text, for the many that are one: `placeholder`, `title`, `pattern`. */
    public fun attributeText(key: String): String? {
      val value = attributes[key] ?: return null
      // `valueText` blanks a null, so an attribute written as `null` reads as absent rather than as
      // the word.
      return valueText(value).takeIf { it.isNotEmpty() }
    }
  }

  public companion object {
    /**
     * How a value is shown and compared, upstream's `value + ''`.
     *
     * `valuesEqual` in `bind.js` compares an option to a signal by string, so `2` and `"2"` are the
     * same option — which matters because an option list written in JSON and a signal computed by
     * an expression often disagree about which of the two they hold.
     */
    public fun valueText(value: VegaValue): String = if (value.isNullish) "" else value.asString()
  }
}
