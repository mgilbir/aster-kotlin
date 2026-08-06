package dev.aster.vega.demo

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.aster.vega.android.AndroidTextEngine
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
  fun everyDemoChartBuildsInBothThemes() {
    val engine = AndroidTextEngine()
    for (chart in DemoChart.entries) {
      for (dark in listOf(false, true)) {
        val scene = chart.build(engine, dark)
        assertTrue("${chart.label} produced an empty scene", scene.nodeCount > 1)
        assertTrue("${chart.label} has no size", scene.width > 0 && scene.height > 0)
      }
    }
  }

  @Test
  fun darkThemeChangesChromeButNotGeometry() {
    val engine = AndroidTextEngine()
    val light = DemoChart.BAR.build(engine, dark = false)
    val dark = DemoChart.BAR.build(engine, dark = true)

    assertTrue("background should differ", light.background != dark.background)
    // Colours differ, but the marks must land in exactly the same places.
    assertNotEquals(light.toCanonicalJson(), dark.toCanonicalJson())
    assertEquals(light.nodeCount, dark.nodeCount)
    assertEquals(light.contentBounds, dark.contentBounds)
  }
}
