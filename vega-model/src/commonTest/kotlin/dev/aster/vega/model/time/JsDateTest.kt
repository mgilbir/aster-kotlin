package dev.aster.vega.model.time

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone

/**
 * `new Date(year, month, …)`, checked against what V8 actually answers.
 *
 * Every expectation below was read off node 20 with `TZ=Europe/Amsterdam`, which is the zone the
 * JVM tests and the oracle scripts both pin, so a local-time answer here is comparable with one
 * from upstream. Nothing is derived: a JavaScript date constructor has three rules that no calendar
 * library has, and each of them is a case here.
 */
class JsDateTest {

  private val amsterdam = TimeZone.of("Europe/Amsterdam")

  private fun make(
    year: Double,
    month: Double = 0.0,
    date: Double = 1.0,
    hours: Double = 0.0,
    minutes: Double = 0.0,
    seconds: Double = 0.0,
    millis: Double = 0.0,
  ) = JsDate.make(year, month, date, hours, minutes, seconds, millis, amsterdam)

  /** Every field rolls over, in both directions. `new Date(2012, 12, 1)` is January 2013. */
  @Test
  fun `every field rolls over`() {
    assertEquals(1356994800000.0, make(2012.0, 12.0, 1.0), "month 12 is January of the next year")
    assertEquals(1330556400000.0, make(2012.0, 1.0, 30.0), "30 February 2012 is 1 March")
    assertEquals(1325458800000.0, make(2012.0, 0.0, 1.0, 24.0), "hour 24 is the next midnight")
    assertEquals(1322694000000.0, make(2012.0, -1.0), "month -1 is December of the year before")
    assertEquals(1325286000000.0, make(2012.0, 0.0, 0.0), "date 0 is the last day of December")
    assertEquals(
      1325368800000.0,
      make(2012.0, 0.0, 1.0, -1.0),
      "hour -1 is the hour before midnight, on the previous day",
    )
    assertEquals(
      1356994801500.0,
      make(2012.0, 12.0, 1.0, 0.0, 0.0, 0.0, 1500.0),
      "1500 milliseconds is a second and a half, and rolls the seconds over",
    )
  }

  /**
   * A two-digit year is nineteen-hundreds — the constructor's own step 8, and the rule a
   * specification writing `{"year": 99}` is relying on.
   */
  @Test
  fun `a two-digit year is nineteen hundreds`() {
    assertEquals(917823600000.0, make(99.0, 1.0, 1.0), "year 99 is 1999")
    assertEquals(-2208988800000.0, make(0.0, 0.0, 1.0), "year 0 is 1900")
    // 100 is not two digits, so it is the year 100 — and the offset that far back is the zone's
    // own local mean time, which is why the number is not a round one.
    assertEquals(-59011460250000.0, make(100.0, 0.0, 1.0), "year 100 is the year 100, not 2000")
  }

  /** Out of range is an *Invalid Date*, whose time value is NaN — never an exception. */
  @Test
  fun `out of range is NaN rather than a throw`() {
    assertTrue(make(2012.0, 1e9).isNaN(), "a month a billion out is an Invalid Date")
    assertTrue(make(275760.0, 8.0, 15.0).isNaN(), "one day past the maximum time value")
    assertTrue(make(-271822.0, 0.0, 1.0).isNaN(), "one year before the minimum")
    assertTrue(make(Double.NaN).isNaN())
    assertTrue(make(Double.POSITIVE_INFINITY).isNaN())
    assertTrue(make(2012.0, Double.NaN).isNaN())
    assertTrue(make(2012.0, 0.0, 1.0, 0.0, 0.0, 0.0, Double.NaN).isNaN())
  }

  /** `Date.UTC(…)` is the same function read in a different zone. */
  @Test
  fun `the zone is the only difference between datetime and utc`() {
    assertEquals(1356998400000.0, JsDate.make(2012.0, 12.0, 1.0, zone = TimeZone.UTC))
    assertEquals(1356994800000.0, make(2012.0, 12.0, 1.0))
  }

  /** `TimeClip` on its own, since callers of date arithmetic reach it directly. */
  @Test
  fun `a time value outside the range is clipped to NaN`() {
    assertEquals(0.0, JsDate.clip(0.0))
    assertEquals(JsDate.MAX_TIME_VALUE, JsDate.clip(JsDate.MAX_TIME_VALUE))
    assertTrue(JsDate.clip(JsDate.MAX_TIME_VALUE + 1.0).isNaN())
    assertTrue(JsDate.clip(-JsDate.MAX_TIME_VALUE - 1.0).isNaN())
    assertTrue(JsDate.clip(Double.POSITIVE_INFINITY).isNaN())
  }
}
