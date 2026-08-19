package dev.aster.vega.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.aster.vega.model.time.VegaTimeZones
import dev.aster.vega.runtime.VegaChartController
import dev.aster.vega.scene.TextNode
import dev.aster.vega.scene.flatten
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The time-zone seam on a device, which is where the identifiers are real.
 *
 * The JVM tests establish the arithmetic; this establishes the two things only a device can say.
 * The platform's own zone database answers `VegaTimeZones.of`, so an identifier that resolves in a
 * desktop JDK also resolves in ART rather than silently falling back — that failure mode would be
 * invisible in a unit test and would put every chart on the wrong day for an app in production. And
 * a controller built the way an Android host builds one carries the zone through a compile it does
 * not otherwise parameterise.
 *
 * A device also has a zone of its own, so the default is checked here against `java.util.TimeZone`
 * rather than against another kotlinx value: the claim "null means the device's zone" is a claim
 * about the platform.
 */
@RunWith(AndroidJUnit4::class)
class ChartTimeZoneInstrumentedTest {

  /** One reading at midday UTC, which is already tomorrow at UTC+14 and still today at UTC−11. */
  private val spec =
    """
    {
      "width": 200, "height": 100, "padding": 5,
      "data": [{
        "name": "t",
        "values": [{"t": "2026-05-20T12:00:00Z", "v": 1}],
        "format": {"parse": {"t": "date"}}
      }],
      "scales": [
        {"name": "x", "type": "time", "domain": {"data": "t", "field": "t"}, "range": "width"}
      ],
      "axes": [{"orient": "bottom", "scale": "x", "format": "%d %B", "tickCount": 1}],
      "marks": [{"type": "symbol", "from": {"data": "t"}, "encode": {"enter": {
        "x": {"scale": "x", "field": "t"}, "y": {"value": 50}}}}]
    }
    """
      .trimIndent()

  private fun labels(zoneId: String?): List<String> {
    val zone = zoneId?.let { requireNotNull(VegaTimeZones.of(it)) { "ART does not know $it" } }
    val controller = VegaChartController(textEngine = AndroidTextEngine(), timeZone = zone)
    val compiled = controller.setSpec(spec)
    val scene = requireNotNull(compiled.scene) { "no scene: ${compiled.diagnostics}" }
    return scene
      .flatten()
      .map { it.node }
      .filterIsInstance<TextNode>()
      .filter { it.metadata.role == "axis-label" }
      .map { it.layout.run.text }
  }

  @Test
  fun aHostSuppliedZoneDecidesWhichDayAMeasurementIsOn() {
    assertTrue("east of the date line", labels("Pacific/Kiritimati").any { it == "21 May" })
    assertTrue("west of it", labels("Pacific/Niue").any { it == "20 May" })
  }

  @Test
  fun theDeviceZoneIsWhatTheEngineFallsBackTo() {
    val deviceId = java.util.TimeZone.getDefault().id
    assertEquals(
      "the default has to be the device's own zone, not UTC and not a captured constant",
      labels(deviceId),
      labels(null),
    )
  }

  @Test
  fun anIdentifierThePlatformDoesNotCarryIsNullRatherThanAThrow() {
    // The identifier usually arrives from a profile or a payload, so this is the path a bad one
    // takes.
    assertNull(VegaTimeZones.of("Mars/Olympus_Mons"))
    assertNotNull(VegaTimeZones.of("Europe/Amsterdam"))
  }
}
