package dev.aster.vega.compose.mp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.aster.vega.scene.Scene

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
 * @param fit how a scene that is not the size of its slot is placed in it.
 */
@Composable
public fun VegaChart(
  scene: Scene,
  modifier: Modifier = Modifier,
  fit: SceneFit = SceneFit.Contain,
  textMeasurer: TextMeasurer = rememberTextMeasurer(),
) {
  val walk = remember { SceneWalk() }
  // The scene's size first, so a caller's modifier can override it and a caller without one still
  // gets a chart. Scene units are CSS pixels, which is what a dp is on the platforms this runs on.
  Canvas(modifier = Modifier.size(scene.width.dp, scene.height.dp).then(modifier)) {
    val scale =
      when (fit) {
        SceneFit.None -> 1.0f
        SceneFit.Contain ->
          if (scene.width <= 0.0 || scene.height <= 0.0) {
            1.0f
          } else {
            minOf(size.width / scene.width.toFloat(), size.height / scene.height.toFloat())
          }
      }
    // Centred in whatever is left over, which is what makes a chart in a slot of the wrong aspect
    // ratio look placed rather than stuck to a corner. `None` means what it says and does not move.
    val left = if (fit == SceneFit.None) 0f else (size.width - scene.width.toFloat() * scale) / 2f
    val top = if (fit == SceneFit.None) 0f else (size.height - scene.height.toFloat() * scale) / 2f
    translate(left = left, top = top) {
      scale(scale = scale, pivot = androidx.compose.ui.geometry.Offset.Zero) {
        walk.draw(scene, DrawScopeTarget(this, textMeasurer))
      }
    }
  }
}

/** How a scene is placed in a slot that is not its own size. */
public enum class SceneFit {
  /** Scaled to fit, keeping its aspect ratio, and centred. */
  Contain,

  /** Drawn at its own size, in the slot's top-left corner. */
  None,
}
