package dev.aster.vega.model.time

import kotlinx.datetime.TimeZone

/**
 * The zone a chart's *local* time is in, and how a host names one safely.
 *
 * Every date in a Vega specification is an instant, and every question a chart asks about one —
 * which day this row falls on, which hour a `timeunit` buckets it into, what a tick label says —
 * has an answer only in some zone. Upstream has exactly two: the browser's own zone for `time`
 * scales and `timeunit: {"timezone": "local"}`, and UTC for the `utc` forms. This engine keeps both
 * and adds one thing upstream cannot need, because a browser is always *on* the device it draws
 * for: a host may say which zone "local" means.
 *
 * An app needs that when the zone the reader lives in is not the zone the device is set to. A
 * measurement taken at 00:30 in Amsterdam is the 20th there and the 19th in UTC, and a chart of
 * days has to agree with the rest of the app about which one it was — a diary bucketed by day, or
 * by morning and evening, is the case that has no Vega-Lite time unit at all and has to be binned
 * against a stated zone before it reaches a scale.
 *
 * **Null means the device's own zone, read when it is needed** — which is what every host got
 * before this seam existed, and is right for the common case. The zone is not captured once: a
 * process that outlives a change of the system zone keeps answering with the current one, exactly
 * as `TimeZone.currentSystemDefault()` does.
 *
 * Parsing is unaffected, for the same reason a locale does not change it: a naive timestamp in a
 * specification's *data* is read in local time because that is what `Date.parse` does, and this
 * seam decides what local **is** rather than whether it applies.
 */
public object VegaTimeZones {

  /** The device's own zone, read now. What a null zone means everywhere in this engine. */
  public val device: TimeZone
    get() = TimeZone.currentSystemDefault()

  /** UTC, named here so a foreign host has one place to reach every zone this engine takes. */
  public val utc: TimeZone
    get() = TimeZone.UTC

  /**
   * The zone with this IANA identifier, or **null** where the platform does not know it.
   *
   * `TimeZone.of` throws on an unknown identifier, and a throw is the wrong answer at a host
   * boundary: a Kotlin exception crossing into Swift or Java terminates the app, and the identifier
   * usually arrives from a server or a user profile rather than from the code. So this returns
   * null, a host decides between the device's zone and refusing to draw, and either way it is a
   * decision somebody wrote rather than a crash.
   */
  public fun of(zoneId: String): TimeZone? =
    try {
      TimeZone.of(zoneId)
    } catch (_: IllegalArgumentException) {
      // `IllegalTimeZoneException` on every platform kotlinx-datetime supports, which is an
      // `IllegalArgumentException`. Caught by its supertype so this compiles for every target
      // without an `expect`/`actual` pair for one throw.
      null
    }
}
