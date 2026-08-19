package dev.aster.vega.runtime.compile

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.time.VegaTimeZones
import dev.aster.vega.runtime.scale.TimeScale
import dev.aster.vega.scene.TextNode
import dev.aster.vega.scene.flatten
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which zone a chart's **local** time is in, when it is not the device's.
 *
 * Upstream needs no such input: a browser is always on the machine it draws for, so "local" is the
 * only local there is. An app is not. A reader's zone comes from their profile as often as from
 * their handset — somebody travelling, a tablet left on a factory-set zone, a household account
 * read from two places — and a chart *of days* has to agree with the rest of the app about which
 * day a measurement was on. A diary bucketed into morning and evening is the same question asked
 * twice: it has no Vega-Lite time unit at all, so a host bins it itself and then has to be sure the
 * axis it hands the buckets to is on the same clock.
 *
 * A naive timestamp is what makes it visible. `2026-05-20T00:30` carries no offset, so it names
 * different instants in different zones — and `Date.parse` reads it in local time, which is why the
 * zone reaches *parsing* as well as formatting. That is not a liberty taken here: it is the rule
 * the differential corpus is compared against, and the seam only decides what local **is**.
 *
 * Two zones are used throughout, both real and both far enough apart to be unmistakable:
 * `Pacific/Kiritimati` at UTC+14 and `Pacific/Niue` at UTC−11, twenty-five hours apart. A test that
 * asserted on Amsterdam would pass in summer and fail in winter.
 */
class TimeZoneTest {

  private val plusFourteen = requireNotNull(VegaTimeZones.of("Pacific/Kiritimati"))
  private val minusEleven = requireNotNull(VegaTimeZones.of("Pacific/Niue"))

  /** One reading, half an hour past midnight — the instant a zone disagrees about the date of. */
  private val justAfterMidnight =
    """
    {
      "width": 300, "height": 120, "padding": 5,
      "data": [{
        "name": "t",
        "values": [{"t": "2026-05-20T00:30:00", "v": 18}],
        "format": {"parse": {"t": "date"}}
      }],
      "scales": [
        {"name": "x", "type": "time", "domain": {"data": "t", "field": "t"}, "range": "width"}
      ],
      "marks": [{"type": "symbol", "from": {"data": "t"}, "encode": {"enter": {
        "x": {"scale": "x", "field": "t"}, "y": {"value": 60}}}}]
    }
    """
      .trimIndent()

  private fun compile(json: String, zone: TimeZone?) =
    SpecCompiler(VegaHeadlessTextEngine(), timeZone = zone).compileJson(json)

  private fun labels(json: String, zone: TimeZone?): List<String> =
    requireNotNull(compile(json, zone).scene) { "no scene" }
      .flatten()
      .map { it.node }
      .filterIsInstance<TextNode>()
      .filter { it.metadata.role == "axis-label" }
      .map { it.layout.run.text }

  /**
   * A number the compiled chart reports, read through a signal.
   *
   * `data('t')[0].t` is how a specification itself reaches a parsed column, so this asserts on what
   * a chart can see rather than on an internal.
   */
  private fun reported(json: String, zone: TimeZone?, name: String): Double {
    // `Timestamp` as well as `Num`: a parsed date keeps the type that says it is one, which is what
    // lets `isDate` answer and what a scale reads as a number anyway.
    val value = compile(json, zone).signals.signal(name)
    return requireNotNull(
      when (value) {
        is VegaValue.Num -> value.value
        is VegaValue.Timestamp -> value.epochMillis
        else -> null
      }
    ) {
      "$name is $value"
    }
  }

  private fun parsedInstant(json: String, zone: TimeZone?): Double =
    reported(json.withSignal("parsed", "data('t')[0].t"), zone, "parsed")

  /**
   * The same specification with one more signal in it, for a test to read a value out through.
   *
   * The update is written with single quotes, which is what a Vega expression uses anyway, so
   * nothing here has to escape anything.
   */
  private fun String.withSignal(name: String, update: String): String =
    replace(
      "\"data\": [",
      """"signals": [{"name": "$name", "update": "$update"}],
      "data": [""",
    )

  @Test
  fun `a naive timestamp in the data is read in the zone the host named`() {
    // 2026-05-20T00:30 is 25 hours apart in the two zones, and nothing in the payload says which.
    val east = parsedInstant(justAfterMidnight, plusFourteen)
    val west = parsedInstant(justAfterMidnight, minusEleven)
    assertEquals(25.0, (west - east) / 3_600_000.0, "UTC+14 to UTC-11 is 25 hours")
    // And each is the instant that clock actually names, not merely a different one.
    assertEquals(1779186600000.0, east, "2026-05-20T00:30+14:00")
    assertEquals(1779276600000.0, west, "2026-05-20T00:30-11:00")
  }

  @Test
  fun `a time scale is built in that zone, so its ticks are that calendar's`() {
    val east = requireNotNull(compile(justAfterMidnight, plusFourteen).scales["x"] as? TimeScale)
    val west = requireNotNull(compile(justAfterMidnight, minusEleven).scales["x"] as? TimeScale)

    assertEquals(plusFourteen, east.zone)
    assertEquals(minusEleven, west.zone)
  }

  @Test
  fun `an axis over one instant labels it with the day that zone is on`() {
    // The same *instant* in both charts — the payload's own offset settles it — and two dates.
    val fixed =
      justAfterMidnight
        .replace("\"2026-05-20T00:30:00\"", "\"2026-05-20T12:00:00Z\"")
        .replace(
          "\"scales\": [",
          """"axes": [{"orient": "bottom", "scale": "x", "format": "%d %B", "tickCount": 1}],
      "scales": [""",
        )

    assertTrue(
      labels(fixed, plusFourteen).contains("21 May"),
      labels(fixed, plusFourteen).toString(),
    )
    assertTrue(labels(fixed, minusEleven).contains("20 May"), labels(fixed, minusEleven).toString())
  }

  /** `timeunit`, which is how every "by day" chart is built and the reason this seam exists. */
  private val bucketedByDay =
    """
    {
      "width": 300, "height": 120, "padding": 5,
      "data": [{
        "name": "t",
        "values": [{"t": "2026-05-20T12:00:00Z"}],
        "format": {"parse": {"t": "date"}},
        "transform": [{"type": "timeunit", "field": "t", "units": ["year", "month", "date"]}]
      }],
      "marks": []
    }
    """
      .trimIndent()

  @Test
  fun `timeunit buckets into the host's day, not the device's`() {
    fun startOfDay(zone: TimeZone?): Double =
      reported(bucketedByDay.withSignal("bucket", "data('t')[0].unit0"), zone, "bucket")

    // Midday UTC is already the 21st in Kiritimati and still the 20th in Niue, so the buckets are
    // different days — and, as **instants**, their midnights are only an hour apart. Worth stating,
    // because "25 hours apart" is true of the two clocks and not of the two boundaries: the day
    // starting later on the calendar starts earlier in absolute time.
    assertEquals(1779271200000.0, startOfDay(plusFourteen), "2026-05-21T00:00+14:00")
    assertEquals(1779274800000.0, startOfDay(minusEleven), "2026-05-20T00:00-11:00")
    assertEquals(-1.0, (startOfDay(plusFourteen) - startOfDay(minusEleven)) / 3_600_000.0)
  }

  @Test
  fun `the local expression functions answer in that zone, and the utc twins do not`() {
    val spec =
      """
      {
        "width": 100, "height": 100,
        "signals": [
          {"name": "hour", "update": "hours(toDate('2026-05-20T00:30:00'))"},
          {"name": "utcHour", "update": "utchours('2026-05-20T00:30:00')"},
          {"name": "written", "update": "timeFormat(toDate('2026-05-20T00:30:00'), '%d %H:%M')"}
        ],
        "marks": []
      }
      """
        .trimIndent()

    fun signal(zone: TimeZone?, name: String) = compile(spec, zone).signals.signal(name)

    // A naive string is parsed in local time and read back in it: half past midnight, both zones.
    assertEquals(VegaValue.Num(0.0), signal(plusFourteen, "hour"))
    assertEquals(VegaValue.Num(0.0), signal(minusEleven, "hour"))
    assertEquals(VegaValue.Str("20 00:30"), signal(plusFourteen, "written"))

    // `utchours` parses in local time too — `Date.parse` has no other mode — and only *reads* in
    // UTC. So the same string is 10:30 the previous day east of the line and 11:30 the same day
    // west
    // of it, which is upstream's answer and looks like a bug until it is written down.
    assertEquals(VegaValue.Num(10.0), signal(plusFourteen, "utcHour"))
    assertEquals(VegaValue.Num(11.0), signal(minusEleven, "utcHour"))
  }

  @Test
  fun `a utc scale and a utc parse pattern are not touched by it`() {
    val spec =
      justAfterMidnight
        .replace("\"type\": \"time\"", "\"type\": \"utc\"")
        .replace("\"t\": \"date\"", "\"t\": \"utc:%Y-%m-%dT%H:%M:%S\"")

    assertEquals(parsedInstant(spec, plusFourteen), parsedInstant(spec, minusEleven))
    assertEquals(1779237000000.0, parsedInstant(spec, plusFourteen), "read as UTC by both")
    val scale = requireNotNull(compile(spec, plusFourteen).scales["x"] as? TimeScale)
    assertEquals(TimeZone.UTC, scale.zone, "a `utc` scale stays UTC whatever a host says")
  }

  @Test
  fun `saying nothing is the device's own zone`() {
    // The default is not "UTC" and not a captured constant: it is what every host had before this
    // seam, which is what keeps the differential corpus meaning what it says.
    assertEquals(
      parsedInstant(justAfterMidnight, VegaTimeZones.device),
      parsedInstant(justAfterMidnight, null),
    )
    val scale = requireNotNull(compile(justAfterMidnight, null).scales["x"] as? TimeScale)
    assertEquals(VegaTimeZones.device, scale.zone)
  }

  @Test
  fun `an identifier the platform does not know is null rather than a throw`() {
    // Where this arrives from decides the answer: a profile field, or a server's payload. A Kotlin
    // exception crossing into Swift or Java ends the process, so a host gets a null and decides.
    assertNull(VegaTimeZones.of("Mars/Olympus_Mons"))
    assertNull(VegaTimeZones.of(""))
    assertNotEquals(null, VegaTimeZones.of("UTC"))
    assertEquals(TimeZone.UTC, VegaTimeZones.utc)
  }
}
