package dev.aster.vega.compose.mp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.aster.vega.model.locale.VegaCaptions
import dev.aster.vega.scene.AccessibilityTree
import dev.aster.vega.scene.AccessibleElement
import dev.aster.vega.scene.Scene
import dev.aster.vega.scene.SceneNodeId
import kotlin.math.roundToInt

/**
 * Draws a compiled [Scene], on Android, iOS or the desktop.
 *
 * The scene is what the engine produced; this composable only paints it. Nothing here recompiles a
 * specification, so a chart redraws at the cost of a traversal.
 *
 * A chart takes the scene's **own** size unless [modifier] says otherwise — a specification
 * declares a width and a height, so that is the size it wants, and a caller who wants something
 * else says so and gets [fit] applied. The alternative, a `Canvas` with no intrinsic size, is worse
 * than it sounds: a zero-sized layer still put solid fills on the screen here while resolving every
 * *gradient* to black, which is a bug report about colours rather than about layout.
 *
 * **It is also the accessibility tree.** The engine computes one in common code and this exposes
 * it: one zero-alpha element per focusable mark, axis, legend and title, positioned over the thing
 * it describes so a reader can *touch* it rather than only swipe through it. Without that the chart
 * was one silent drawing on this renderer while the Android View and the Swift one both spoke.
 *
 * @param fit how a scene that is not the size of its slot is placed in it.
 * @param selectedNodeIds the marks currently selected, so a reader is told which. From a
 *   controller's interaction state where there is one.
 * @param captions the language the one sentence the tree writes itself is in — the dense-chart
 *   summary. Everything else is already in the chart's own locale, having come from the compiler.
 * @param onActivate what to do when a reader activates a mark, or a caller taps one. Null leaves
 *   the chart inert, which is right for a chart that is only being looked at.
 *
 * A callback rather than a controller because this module depends on `vega-scene` alone: a scene is
 * all a renderer needs, and taking `vega-runtime` here to dispatch a tap would make every host that
 * only draws pay for a dataflow. A host that has a controller passes `onActivate = {
 * controller.dispatch(ChartInputEventTap(...)) }`, which is also where a tooltip comes from.
 */
@Composable
public fun VegaChart(
  scene: Scene,
  modifier: Modifier = Modifier,
  fit: SceneFit = SceneFit.Contain,
  textEngine: ComposeTextEngine = rememberVegaTextEngine(),
  selectedNodeIds: Set<SceneNodeId> = emptySet(),
  captions: VegaCaptions = VegaCaptions.English,
  onActivate: ((SceneNodeId) -> Unit)? = null,
) {
  val walk = remember { SceneWalk() }
  val density = LocalDensity.current.density
  // Cached against the scene's identity and the selection, which is what the engine's own
  // documentation asks a host to do: `elements` flattens the scene, so recomputing it on every
  // recomposition would walk the tree for a pointer that moved.
  val elements =
    remember(scene, selectedNodeIds, captions) {
      AccessibilityTree.elements(scene, selectedNodeIds, captions)
    }

  Box(modifier = Modifier.size(scene.width.dp, scene.height.dp).then(modifier)) {
    // The scene's size first, so a caller's modifier can override it and a caller without one still
    // gets a chart. Scene units are CSS pixels, which is what a dp is on the platforms this runs
    // on.
    Canvas(
      modifier =
        Modifier.matchParentSize()
          // The drawing says nothing on its own: every announcement belongs to an element in the
          // overlay, and a canvas that also carried a description would be read out twice.
          .clearAndSetSemantics {}
    ) {
      val placement = placement(scene, size.width, size.height, fit, density)
      translate(left = placement.left, top = placement.top) {
        scale(scale = placement.scale, pivot = Offset.Zero) {
          walk.draw(
            scene,
            DrawScopeTarget(
              scope = this,
              textMeasurer = textEngine.measurer,
              fontFamilyResolver = textEngine.fontFamilyResolver,
            ),
          )
        }
      }
    }

    AccessibilityOverlay(
      elements = elements,
      scene = scene,
      fit = fit,
      density = density,
      onActivate = onActivate,
      modifier = Modifier.matchParentSize(),
    )
  }
}

/**
 * The accessibility elements, laid out over the parts of the chart they describe.
 *
 * Positioned rather than merely listed, and that distinction is the whole of it: a reader exploring
 * by touch lands on a *rectangle*, and a set of elements without frames can be swiped through but
 * not touched. The Swift renderer says the same thing in `SceneCanvas.accessibilityOverlay`, and
 * its header records why — `accessibilityChildren` there yields frames of `(inf, inf, 0, 0)` that a
 * reader cannot reach.
 *
 * One `Layout` rather than a modifier per child, because the frames have to be measured against the
 * **placed** chart: the fit scale and the centring offset come from the slot's own size, which only
 * a layout can see. It is also what keeps one copy of that arithmetic — two copies is how a
 * reader's finger lands beside the mark it looked like it hit, a defect this project has had on
 * Android and in the Swift renderer, both of which now share one placement between drawing and
 * touching.
 */
@Composable
private fun AccessibilityOverlay(
  elements: List<AccessibleElement>,
  scene: Scene,
  fit: SceneFit,
  density: Float,
  onActivate: ((SceneNodeId) -> Unit)?,
  modifier: Modifier = Modifier,
) {
  if (elements.isEmpty()) return
  Layout(
    modifier = modifier,
    content = {
      for (element in elements) {
        val nodeId = element.nodeId
        val activate = if (element.activatable && nodeId != null) onActivate else null
        Box(
          Modifier.semantics {
            contentDescription = element.label
            selected = element.selected
            // A **button only where activating it does something.** Both existing hosts announce
            // every element as a button — `className = "android.widget.Button"` on Android,
            // `.isButton` on iOS — so a reader is told they can activate an axis caption and then
            // nothing happens when they try. `AccessibleElement.activatable` is the engine's own
            // answer to which elements are marks.
            if (activate != null) {
              role = Role.Button
              onClick {
                activate(nodeId!!)
                true
              }
            }
          }
        )
      }
    },
  ) { measurables, constraints ->
    val placement =
      placement(
        scene,
        constraints.maxWidth.toFloat(),
        constraints.maxHeight.toFloat(),
        fit,
        density,
      )
    // At least a pixel in each direction: a rule, an axis domain line or a zero-height bar has no
    // extent on one axis, and a reader cannot land on a frame of no size.
    val placeables = measurables.mapIndexed { index, measurable ->
      val bounds = elements[index].bounds
      measurable.measure(
        Constraints.fixed(
          width = (bounds.width * placement.scale).roundToInt().coerceAtLeast(1),
          height = (bounds.height * placement.scale).roundToInt().coerceAtLeast(1),
        )
      )
    }
    layout(constraints.maxWidth, constraints.maxHeight) {
      placeables.forEachIndexed { index, placeable ->
        val bounds = elements[index].bounds
        placeable.place(
          x = (bounds.left * placement.scale + placement.left).roundToInt(),
          y = (bounds.top * placement.scale + placement.top).roundToInt(),
        )
      }
    }
  }
}

/** How a scene is placed in a slot that is not its own size. */
public enum class SceneFit {
  /** Scaled to fit, keeping its aspect ratio, and centred. */
  Contain,

  /** Drawn at its own size — one scene unit per dp — in the slot's top-left corner. */
  None,
}

/** The fit scale and the centring offset, in pixels, shared by the drawing and the overlay. */
private data class Placement(val scale: Float, val left: Float, val top: Float)

/**
 * Where a scene sits inside a slot, computed once and used by everything that has to agree about
 * it.
 *
 * Both the drawing and the accessibility frames go through this. A second copy of the arithmetic is
 * how a reader's finger lands next to the mark it looked like it hit — a defect this project has
 * had twice, once on Android and once in the Swift renderer, which is why both of those share one
 * placement too.
 */
private fun placement(
  scene: Scene,
  width: Float,
  height: Float,
  fit: SceneFit,
  density: Float,
): Placement {
  val scale =
    when (fit) {
      // **The density, not one.** A scene unit is a dp, so "its own size" means one scene unit per
      // dp — which is `density` pixels. Drawing at a scale of 1 made the chart come out at a third
      // of
      // its size on a 3x screen while every glyph was drawn at the right size, because text went
      // through `sp` and everything else did not. Both halves of that are fixed here and in
      // `DrawScopeTarget.text`.
      SceneFit.None -> density
      SceneFit.Contain ->
        if (scene.width <= 0.0 || scene.height <= 0.0) {
          density
        } else {
          // Already in pixels on both sides — the slot's size is in pixels and the scene's own size
          // is in dp — so this factor carries the density with it.
          minOf(width / scene.width.toFloat(), height / scene.height.toFloat())
        }
    }
  // Centred in whatever is left over, which is what makes a chart in a slot of the wrong aspect
  // ratio
  // look placed rather than stuck to a corner. `None` means what it says and does not move.
  return Placement(
    scale = scale,
    left = if (fit == SceneFit.None) 0f else (width - scene.width.toFloat() * scale) / 2f,
    top = if (fit == SceneFit.None) 0f else (height - scene.height.toFloat() * scale) / 2f,
  )
}
