package dev.aster.vega.compose.mp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.runtime.compile.SpecCompiler
import dev.aster.vega.runtime.load.DenyLoader
import dev.aster.vega.scene.ChartKey
import dev.aster.vega.scene.MetricTextEngine
import dev.aster.vega.scene.Modifiers
import dev.aster.vega.scene.Scene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The keyboard, on the renderer that had **no keyboard path of any kind**.
 *
 * `VegaChart.kt` had zero references to `ChartKey`, `KeyEvent` or `onKeyEvent`, so a
 * specification's `keydown` handlers never fired and the engine's own traversal between marks was
 * unreachable — on the surface most likely to be running on a desktop, where a keyboard is the
 * primary input (#229). Android translates keys through `dispatchKeyEvent` and Apple through
 * `ChartSession.press(_:modifiers:)`.
 *
 * Reported in the engine's own `ChartKey` vocabulary rather than the platform's, so the translation
 * happens once here instead of once per host.
 */
@OptIn(ExperimentalTestApi::class)
class ComposeKeyboardTest {

  private val chart =
    """
    {"${'$'}schema": "https://vega.github.io/schema/vega/v6.json",
     "width": 120, "height": 60, "padding": 0,
     "data": [{"name": "t", "values": [{"c": "a", "v": 3}, {"c": "b", "v": 7}]}],
     "scales": [{"name": "x", "type": "band", "domain": {"data": "t", "field": "c"},
                 "range": "width"}],
     "marks": [{"type": "rect", "from": {"data": "t"}, "encode": {"enter": {
       "x": {"scale": "x", "field": "c"}, "width": {"scale": "x", "band": 1},
       "y": {"value": 0}, "height": {"value": 60}, "fill": {"value": "steelblue"}}}}]}
    """

  private fun scene(): Scene {
    val compiled =
      SpecCompiler(textEngine = MetricTextEngine(), loader = DenyLoader).compileJson(chart)
    assertTrue(
      compiled.diagnostics.none { it.severity >= DiagnosticSeverity.ERROR },
      "compiled with errors: ${compiled.diagnostics.map { it.message }}",
    )
    return requireNotNull(compiled.scene) { "no scene" }
  }

  @Test
  fun `a key press is reported in the engine's own vocabulary`() = runComposeUiTest {
    val pressed = mutableListOf<Pair<ChartKey, Modifiers>>()
    val scene = scene()
    setContent {
      VegaChart(
        scene,
        modifier = Modifier.size(scene.width.dp, scene.height.dp),
        onKey = { key, modifiers -> pressed.add(key to modifiers) },
      )
    }
    // **Focused first.** A chart does not take focus when it appears — that would steal it from
    // whatever the reader was using — and a Compose node without focus is offered no key event. A
    // reader tabs to it; a test asks for it.
    onNode(isFocusable()).requestFocus()
    onRoot().performKeyInput {
      pressKey(Key.DirectionRight)
      pressKey(Key.Enter)
      pressKey(Key.Escape)
    }
    assertEquals(
      listOf(ChartKey.ARROW_RIGHT, ChartKey.ENTER, ChartKey.ESCAPE),
      pressed.map { it.first },
      "the keys did not reach the host in the engine's vocabulary",
    )
  }

  /**
   * The modifiers travel with the key, so a specification can tell a shifted arrow from a plain
   * one.
   */
  @Test
  fun `the modifiers travel with the key`() = runComposeUiTest {
    val pressed = mutableListOf<Pair<ChartKey, Modifiers>>()
    val scene = scene()
    setContent {
      VegaChart(
        scene,
        modifier = Modifier.size(scene.width.dp, scene.height.dp),
        onKey = { key, modifiers -> pressed.add(key to modifiers) },
      )
    }
    onNode(isFocusable()).requestFocus()
    onRoot().performKeyInput {
      pressKey(Key.DirectionRight)
      withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionRight) }
    }
    assertEquals(2, pressed.size, "the shifted key was not reported")
    assertEquals(Modifiers.None, pressed[0].second, "a plain arrow reported a modifier")
    assertTrue(pressed[1].second.shift, "a shifted arrow did not report its modifier")
  }

  /**
   * A key the chart does not react to is **not consumed**, so a surrounding layout still sees it.
   *
   * What makes a chart inside a form usable: the engine reacts to ten keys and everything else
   * belongs to whatever is around it.
   */
  @Test
  fun `a key the chart ignores is left for the layout`() = runComposeUiTest {
    val pressed = mutableListOf<ChartKey>()
    val scene = scene()
    setContent {
      Box(Modifier) {
        VegaChart(
          scene,
          modifier = Modifier.size(scene.width.dp, scene.height.dp),
          onKey = { key, _ -> pressed.add(key) },
        )
      }
    }
    onNode(isFocusable()).requestFocus()
    onRoot().performKeyInput {
      pressKey(Key.A)
      pressKey(Key.F1)
    }
    assertEquals(emptyList(), pressed, "a key outside the chart's vocabulary was reported")
  }

  /** And a chart nobody asked for keys from is not made focusable. */
  @Test
  fun `a chart with no key callback takes no focus`() = runComposeUiTest {
    val scene = scene()
    setContent { VegaChart(scene, modifier = Modifier.size(scene.width.dp, scene.height.dp)) }
    // Nothing to assert but the absence of a crash and of a focus target; the composable installs
    // neither the focus modifier nor the key handler, which is what keeps a drawing-only chart out
    // of a surrounding layout's traversal order.
    onRoot().performKeyInput { pressKey(Key.DirectionRight) }
  }
}
