package dev.aster.vega.runtime

import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue

/**
 * Building the rows a host hands the engine, from a language that cannot name a Kotlin value class.
 *
 * `VegaValue`'s variants are `@JvmInline value class`es, and a value class has no Obj-C
 * representation — which is why `ForeignSignals` exists for signals and why this exists for data. A
 * Swift or Java host builds rows through these and gets back the interface type, boxed once.
 *
 * The leaf values are `ForeignSignals.ofNumber`, `ofString` and `ofBoolean`, already there for the
 * same reason; this adds what a **table** needs on top of them: a row, an instant, a missing value,
 * and a whole table read out of JSON for a host that already holds one.
 */
public object ForeignData {

  /** One row: a column name to a value, in the order given. */
  public fun row(fields: Map<String, VegaValue>): VegaValue = VegaValue.Obj(LinkedHashMap(fields))

  /**
   * An instant, for a host that holds a date rather than a string.
   *
   * `VegaValue.Timestamp`, which is a *date* and not merely a number: a time scale dispatches on it
   * and `isDate` answers true, so a column built this way needs no `format.parse` entry at all.
   * That is the difference worth having at this boundary — a Swift `Date` or a Java `Instant`
   * formatted to a string, only to be parsed back, would go through a zone twice and can land on
   * the wrong day.
   */
  public fun instant(epochMillis: Double): VegaValue = VegaValue.Timestamp(epochMillis)

  /** The missing value, which is not the same as a column a row does not carry. */
  public fun missing(): VegaValue = VegaValue.Null

  /**
   * Rows read from a JSON array, or **null** where the text is not one.
   *
   * For a host whose data is already JSON — a cached response, a store that speaks it — so it does
   * not build a row at a time across the boundary. The reading is the engine's own parser, so a
   * number, a string and a null mean here exactly what they would have meant inline in the
   * specification.
   *
   * Null rather than an empty list, and null rather than a throw: an empty table is a legitimate
   * answer a chart should draw as empty, so it cannot double as the failure. A Kotlin throw
   * crossing into Swift would end the process.
   */
  public fun rowsFromJson(json: String): List<VegaValue>? {
    val parsed = VegaJson.parseOrNull(json, DiagnosticCollector())
    return (parsed as? VegaValue.Arr)?.values
  }
}
