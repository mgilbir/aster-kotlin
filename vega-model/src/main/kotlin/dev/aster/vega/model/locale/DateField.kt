package dev.aster.vega.model.locale

/**
 * One of the three fields a date order is made of.
 *
 * Public because a *language's* order is a fact two modules need and only one can read:
 * `VegaLocale` holds d3's `%x` and `:vega-lite` has to write its own table of specifiers — `%b %d,
 * %Y`, with the month as a **name** — in that order. Reordering upstream's shape is not the same
 * operation as taking the locale's numeric pattern, and neither module can do the other's half.
 *
 * There is no weekday here and no time. This is the order of the three fields that can be written
 * in more than one order and mean the same date.
 */
public enum class DateField {
  YEAR,
  MONTH,

  /** The day of the month — `%d` or `%e`, never a weekday. */
  DATE,
}
