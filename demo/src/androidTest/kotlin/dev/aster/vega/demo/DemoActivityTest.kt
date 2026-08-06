package dev.aster.vega.demo

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.aster.vega.android.AndroidTextEngine
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.runtime.VegaChartController
import dev.aster.vega.scene.toCanonicalJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DemoActivityTest {

  @Test
  fun activityLaunches() {
    ActivityScenario.launch(DemoActivity::class.java).use { scenario ->
      scenario.onActivity { activity -> assertTrue(!activity.isFinishing) }
    }
  }

  @Test
  fun everyHandAuthoredChartBuildsInBothThemes() {
    val engine = AndroidTextEngine()
    for (chart in DemoChart.entries.filter { !it.isSpec }) {
      for (dark in listOf(false, true)) {
        val scene = requireNotNull(chart.build(engine, dark)) { "${chart.label} built nothing" }
        assertTrue("${chart.label} produced an empty scene", scene.nodeCount > 1)
        assertTrue("${chart.label} has no size", scene.width > 0 && scene.height > 0)
      }
    }
  }

  /**
   * Every bundled specification compiles on the device, with the device's own text metrics.
   *
   * The differential tests prove these specifications match upstream using a stand-in text engine;
   * this proves they also survive real font measurement, which is what the chart on screen uses.
   */
  @Test
  fun everySpecificationCompilesOnDevice() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    for (chart in DemoChart.entries.filter { it.isSpec }) {
      val asset = requireNotNull(chart.specAsset)
      val json = context.assets.open(asset).bufferedReader().use { it.readText() }
      val controller = VegaChartController(textEngine = AndroidTextEngine())
      val compiled = controller.setSpec(json)

      assertTrue("$asset produced no scene", compiled.isUsable)
      val errors = compiled.diagnostics.filter { it.severity >= DiagnosticSeverity.ERROR }
      assertTrue("$asset reported $errors", errors.isEmpty())
      assertTrue("$asset drew nothing", controller.snapshot.scene.nodeCount > 1)
    }
  }

  @Test
  fun darkThemeChangesChromeButNotGeometry() {
    val engine = AndroidTextEngine()
    val light = requireNotNull(DemoChart.BAR.build(engine, dark = false))
    val dark = requireNotNull(DemoChart.BAR.build(engine, dark = true))

    assertTrue("background should differ", light.background != dark.background)
    // Colours differ, but the marks must land in exactly the same places.
    assertNotEquals(light.toCanonicalJson(), dark.toCanonicalJson())
    assertEquals(light.nodeCount, dark.nodeCount)
    assertEquals(light.contentBounds, dark.contentBounds)
  }
}
