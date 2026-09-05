package dev.aster.vega.compose.mp

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.runtime.compile.SpecCompiler
import dev.aster.vega.runtime.load.DenyLoader
import dev.aster.vega.scene.ChartAction
import dev.aster.vega.scene.ChartActionKind
import dev.aster.vega.scene.MetricTextEngine
import dev.aster.vega.scene.Scene
import dev.aster.vega.scene.SceneNodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The accessibility tree this renderer exposes, which until now it did not.
 *
 * `AccessibilityTree` is good work and it is common code, and `VegaChart` was 69 lines of `Canvas`
 * with no `semantics` block in it — so a chart drawn by Compose Multiplatform was one silent
 * drawing while the Android View and the Swift renderer both spoke. For a reader-facing chart that
 * is not an enhancement, and an adopter said so plainly.
 *
 * Semantics are not pixels, so `ImageComposeScene` cannot see any of this; Compose's own test
 * harness can, and runs on the desktop with no window and no device.
 */
@OptIn(ExperimentalTestApi::class)
class ComposeAccessibilityTest {

  private val chart =
    """
    {"${'$'}schema": "https://vega.github.io/schema/vega/v6.json",
     "width": 160, "height": 80, "padding": 5,
     "data": [{"name": "t", "values": [{"c": "Total", "v": 18}, {"c": "Sleep", "v": 7}]}],
     "scales": [
       {"name": "x", "type": "band", "domain": {"data": "t", "field": "c"}, "range": "width"},
       {"name": "y", "type": "linear", "domain": [0, 27], "range": "height"}],
     "axes": [{"orient": "bottom", "scale": "x", "title": "Question"}],
     "marks": [{"type": "rect", "from": {"data": "t"}, "encode": {"enter": {
       "x": {"scale": "x", "field": "c"}, "width": {"scale": "x", "band": 1},
       "y": {"scale": "y", "field": "v"}, "y2": {"scale": "y", "value": 0},
       "fill": {"value": "steelblue"}}}}]}
    """

  private fun scene(): Scene {
    val compiled =
      SpecCompiler(textEngine = MetricTextEngine(), loader = DenyLoader).compileJson(chart)
    val complaints =
      compiled.diagnostics.filter {
        it.severity == DiagnosticSeverity.ERROR || it.severity == DiagnosticSeverity.FATAL
      }
    assertTrue(complaints.isEmpty(), "compiled with errors: ${complaints.map { it.message }}")
    return requireNotNull(compiled.scene) { "no scene" }
  }

  @Test
  fun `every mark is an element a reader can land on`() = runComposeUiTest {
    setContent { VegaChart(scene()) }

    // The two bars, by the label the engine derived for them: a category and its value.
    onNodeWithContentDescription("Total: 18").assertExists()
    onNodeWithContentDescription("Sleep: 7").assertExists()
  }

  @Test
  fun `an element has a frame, so it can be explored by touch`() = runComposeUiTest {
    setContent { VegaChart(scene()) }

    // The point of positioning them: a reader moving a finger over the chart lands on the bar under
    // it. An element with no frame can be swiped through and not touched, which is the defect the
    // Swift renderer's overlay exists to avoid — `accessibilityChildren` there yields frames of
    // `(inf, inf, 0, 0)`.
    onNodeWithContentDescription("Total: 18").assertWidthIsAtLeast(1.dp).assertHeightIsAtLeast(1.dp)
  }

  @Test
  fun `the axis is described, and is not announced as a button`() = runComposeUiTest {
    setContent { VegaChart(scene(), onActivate = {}) }

    val axis =
      onAllNodes(
        SemanticsMatcher("has an axis caption") { node ->
          node.config
            .getOrElseNullable(SemanticsProperties.ContentDescription) { null }
            ?.any { it.startsWith("X-axis") } == true
        }
      )
    axis.assertCountEquals(1)
    // Announcing a caption as a button tells a reader they can activate it, and activating it does
    // nothing. Both existing hosts do exactly that, which is what `activatable` is for.
    axis
      .onFirst()
      .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Role))
      .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
  }

  @Test
  fun `a mark is a button only when there is something to activate`() = runComposeUiTest {
    setContent { VegaChart(scene()) }
    // No callback: the chart is being looked at, so nothing offers an activation.
    onNodeWithContentDescription("Total: 18")
      .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
  }

  @Test
  fun `activating a mark hands its node to the host`() = runComposeUiTest {
    val activated = mutableListOf<SceneNodeId>()
    setContent { VegaChart(scene(), onActivate = { activated += it }) }

    onNodeWithContentDescription("Sleep: 7")
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
      .performSemanticsAction(SemanticsActions.OnClick)

    assertEquals(1, activated.size, "the host was not told: $activated")
  }

  @Test
  fun `the drawing itself says nothing, so nothing is read out twice`() = runComposeUiTest {
    setContent { VegaChart(scene()) }

    // The canvas clears its semantics: every announcement belongs to an element in the overlay.
    // Without that a reader meets the whole chart as one unlabelled node and then its parts.
    val described =
      onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
        .fetchSemanticsNodes()
    // Three: the two bars and the axis, whose caption carries its title inside it rather than
    // standing beside it as a fourth element.
    assertEquals(
      3,
      described.size,
      "the two bars and the axis: " +
        described.map {
          it.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }
        },
    )
  }

  /**
   * The chart's **own** actions — zooming, resetting — on the chart's own node.
   *
   * They belong to the whole chart rather than to any mark, which is why they are not on
   * `AccessibleElement`, and until this **no host on any platform wired them**: the feature was
   * built, tested and documented against `AccessibilityNodeInfo.addAction` and
   * `UIAccessibilityCustomAction` and the call was never made anywhere (#226). Compose's
   * `customActions` is the same primitive in this framework's spelling.
   *
   * Handed in rather than read, because this composable takes a `Scene` and not a controller — the
   * same reason `onActivate` reports a `SceneNodeId` instead of selecting anything itself.
   */
  @Test
  fun `the chart's own actions are offered on its own node`() = runComposeUiTest {
    val invoked = mutableListOf<ChartActionKind>()
    setContent {
      VegaChart(
        scene(),
        chartActions =
          listOf(
            ChartAction(ChartActionKind.ZOOM_IN, "Zoom in"),
            ChartAction(ChartActionKind.RESET_ZOOM, "Reset zoom"),
          ),
        onChartAction = { invoked += it },
      )
    }
    val node =
      onNode(
        SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions),
        useUnmergedTree = true,
      )
    node.assertExists()
    val actions = node.fetchSemanticsNode().config[SemanticsActions.CustomActions]
    assertEquals(
      listOf("Zoom in", "Reset zoom"),
      actions.map { it.label },
      "the chart's node carries the wrong actions, with the wrong labels",
    )
    actions.first().action()
    assertEquals(
      listOf(ChartActionKind.ZOOM_IN),
      invoked,
      "invoking a custom action did not reach the host",
    )
  }

  /** No actions handed in, no action node: nothing is offered that would do nothing. */
  @Test
  fun `a chart with no actions offers none`() = runComposeUiTest {
    setContent { VegaChart(scene()) }
    onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions), useUnmergedTree = true)
      .assertDoesNotExist()
  }
}
