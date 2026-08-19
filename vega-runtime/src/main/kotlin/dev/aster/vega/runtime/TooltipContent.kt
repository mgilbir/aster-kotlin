package dev.aster.vega.runtime

import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asString
import dev.aster.vega.model.locale.VegaLocale
import dev.aster.vega.model.time.TimeFormat
import dev.aster.vega.runtime.scale.formatTickLabel
import dev.aster.vega.runtime.scale.groupThousands
import kotlin.math.round
import kotlinx.datetime.TimeZone

/** One line of a tooltip: what the field is called, and what it says. */
public data class TooltipRow(val label: String, val value: String)

/**
 * A tooltip a host can **show**, from the value the dataflow produced.
 *
 * Every renderer has reported the tooltip *value* since interaction was built, and no renderer
 * draws one: a bubble is a design-system decision and does not belong in an engine. What did belong
 * here and was missing is the step in between — turning `datum` into lines of text — and its
 * absence showed. Each host had to write it again, and the `ChartSession` on iOS was comparing the
 * stringified value against the literal `"{}"` to tell an empty tooltip from a real one. That check
 * is now this file's job.
 *
 * The shape is upstream's: `"tooltip": true` in Vega-Lite compiles to a tooltip whose value is the
 * row itself, and `vega-tooltip` renders an object as a table of its own fields in order, anything
 * else as a single value. That much is reproduced. What is **not** claimed is byte-fidelity with
 * `vega-tooltip`'s HTML: it is not among the packages this repository pins, so nothing here is
 * differentially verified against it, and the formatting below is deliberately this engine's own —
 * the number formatter its axes use, the date format its captions use — so that a tooltip and the
 * tick label beside it agree with each other. A tooltip disagreeing with the axis it sits on is the
 * defect that matters to a reader; matching a browser's `<table>` markup is not.
 *
 * A host that wants something else has [rows] and can ignore [text].
 */
public data class TooltipContent(val rows: List<TooltipRow>, val text: String) {

  public companion object {

    /**
     * The content for a tooltip value, or **null** where there is nothing to show.
     *
     * Null for three distinct cases, and the middle one is the one that bit: no tooltip at all, a
     * tooltip whose value is an **empty object** — which is what a mark with no `tooltip` channel
     * produces, so treating it as a tooltip put an empty bubble on every mark — and a value that
     * reads as nothing at all.
     *
     * @param zone the zone an instant is written in; null is the device's own, as everywhere else.
     */
    public fun of(
      value: VegaValue?,
      locale: VegaLocale = VegaLocale.EnglishUS,
      zone: TimeZone? = null,
    ): TooltipContent? {
      val rows =
        when (value) {
          null,
          VegaValue.Null -> return null
          is VegaValue.Obj -> {
            if (value.fields.isEmpty()) return null
            value.fields.map { (key, field) -> TooltipRow(key, text(field, locale, zone)) }
          }
          // An array is a list of values, not of fields, so its rows are numbered from one — a
          // reader
          // counting them is what the label has to serve, and zero-based positions read as data.
          is VegaValue.Arr -> {
            if (value.values.isEmpty()) return null
            value.values.mapIndexed { index, entry ->
              TooltipRow((index + 1).toString(), text(entry, locale, zone))
            }
          }
          else -> {
            val written = text(value, locale, zone)
            if (written.isEmpty()) return null else listOf(TooltipRow("", written))
          }
        }
      // A single unlabelled row is its own text; anything else is `label: value` a line at a time,
      // which is what a host puts in a bubble when it has no opinion of its own.
      val written =
        if (rows.size == 1 && rows.single().label.isEmpty()) rows.single().value
        else rows.joinToString("\n") { "${it.label}: ${it.value}" }
      return TooltipContent(rows, written)
    }

    /**
     * One value as text, in the chart's own language.
     *
     * A **number** goes through `formatTickLabel` at its shortest faithful precision, so the number
     * in a tooltip is written the way the number on the axis is — the locale's decimal separator,
     * its grouping, its typographic minus. An **instant** goes through the locale's own
     * date-and-time format rather than a hardcoded one, for the same reason. Anything nested is
     * written as its canonical JSON, which is what upstream does with an object inside a row.
     */
    private fun text(value: VegaValue, locale: VegaLocale, zone: TimeZone?): String =
      when (value) {
        VegaValue.Null -> ""
        is VegaValue.Bool -> value.value.toString()
        is VegaValue.Num ->
          if (value.value.isNaN() || value.value.isInfinite()) {
            groupThousands(value.value.toString(), locale)
          } else {
            formatTickLabel(value.value, decimalsOf(value.value), locale)
          }
        is VegaValue.Timestamp ->
          TimeFormat.format(
            value.epochMillis,
            locale.dateTime,
            zone ?: TimeZone.currentSystemDefault(),
            locale,
          )
        is VegaValue.Str -> value.value
        else -> value.asString()
      }

    /**
     * How many places a number needs to be itself, capped where a tooltip stops being readable.
     *
     * `Decimals.shortest` answers exactly — it is the fewest digits that identify the double — but
     * a value like `0.1 + 0.2` needs seventeen of them, and seventeen digits in a bubble is noise
     * rather than precision. Six is d3's own default significant-digit budget for a formatted
     * number and is where this stops too.
     */
    private fun decimalsOf(value: Double): Int {
      val exact = places(value)
      if (exact <= MAX_DECIMALS) return exact
      // Past the cap the value is **rounded** to it and asked again, rather than formatted at it:
      // the
      // cap would pad, so `0.1 + 0.2` came out as `0.300000` where a reader would have written
      // `0.3`.
      val rounded = round(value * FACTOR) / FACTOR
      return places(rounded).coerceAtMost(MAX_DECIMALS)
    }

    /** The decimal places in `String(x)`, the shortest form that identifies the double. */
    private fun places(value: Double): Int {
      val text = dev.aster.vega.model.Decimals.jsString(value)
      if (text.contains('e') || text.contains('E')) return MAX_DECIMALS
      val dot = text.indexOf('.')
      return if (dot < 0) 0 else text.length - dot - 1
    }

    private const val MAX_DECIMALS = 6

    private const val FACTOR = 1_000_000.0
  }
}
