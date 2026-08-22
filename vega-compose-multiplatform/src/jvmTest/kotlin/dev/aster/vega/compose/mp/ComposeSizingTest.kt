package dev.aster.vega.compose.mp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.aster.vega.runtime.compile.SpecCompiler
import dev.aster.vega.runtime.load.DenyLoader
import dev.aster.vega.scene.MetricTextEngine
import dev.aster.vega.scene.Scene
import kotlin.test.Test

/**
 * Where a chart's size comes from, and what `fit` can do about it.
 *
 * `Modifier.size` was appended unconditionally, so a caller that bounded neither dimension got the
 * scene's own size whatever `fit` said — and for a `width: "container"` chart that is
 * `config.view.continuousWidth`, 300, plus its axes, however much room was going. `fit` is
 * documented as how a scene that is not the size of its slot is placed in it, and in that case the
 * slot and the scene were the same size by construction, so it had nothing to do and said nothing
 * about it.
 *
 * The order is the other half of this and it is asserted here too: the caller's modifier goes
 * first, so a bound it states wins. Both halves in one file because the trap is the interaction
 * between them.
 */
@OptIn(ExperimentalTestApi::class)
class ComposeSizingTest {

  /** A chart 120 by 60, which is the size to look for when the scene decides. */
  private val scene: Scene =
    requireNotNull(
      SpecCompiler(textEngine = MetricTextEngine(), loader = DenyLoader)
        .compileJson(
          """
          {"${'$'}schema": "https://vega.github.io/schema/vega/v6.json",
           "width": 120, "height": 60, "padding": 0,
           "data": [{"name": "t", "values": [{"c": "a", "v": 1}]}],
           "scales": [{"name": "x", "type": "band", "domain": {"data": "t", "field": "c"},
                       "range": "width"}],
           "marks": [{"type": "rect", "from": {"data": "t"}, "encode": {"update": {
             "x": {"scale": "x", "field": "c"}, "width": {"scale": "x", "band": 1},
             "y": {"value": 0}, "y2": {"value": 60}}}}]}
          """
            .trimIndent()
        )
        .scene
    )

  @Test
  fun `with no bound from the caller the default is the scene's own size`() = runComposeUiTest {
    setContent {
      Box(Modifier.requiredSize(600.dp, 300.dp)) {
        VegaChart(scene, modifier = Modifier.testTag("chart"))
      }
    }
    // 600 by 300 of room and the chart takes 120 by 60, which is what `SceneSizing.Scene` means and
    // what this always did. Stated as a test because it is a *decision* rather than an accident.
    onNodeWithTag("chart").assertWidthIsEqualTo(120.dp).assertHeightIsEqualTo(60.dp)
  }

  @Test
  fun `Fill takes the slot and leaves fit to place the scene in it`() = runComposeUiTest {
    setContent {
      Box(Modifier.requiredSize(600.dp, 300.dp)) {
        VegaChart(scene, sizing = SceneSizing.Fill, modifier = Modifier.testTag("chart"))
      }
    }
    onNodeWithTag("chart").assertWidthIsEqualTo(600.dp).assertHeightIsEqualTo(300.dp)
  }

  @Test
  fun `a bound the caller states wins over both`() = runComposeUiTest {
    setContent {
      Box(Modifier.requiredSize(600.dp, 300.dp)) {
        VegaChart(
          scene,
          sizing = SceneSizing.Fill,
          modifier = Modifier.width(240.dp).testTag("chart"),
        )
      }
    }
    // The caller's modifier comes first, so its width is the constraint everything after it is
    // measured against — including `fillMaxSize`, which fills to a bound rather than past one.
    onNodeWithTag("chart").assertWidthIsEqualTo(240.dp).assertHeightIsEqualTo(300.dp)
  }
}
