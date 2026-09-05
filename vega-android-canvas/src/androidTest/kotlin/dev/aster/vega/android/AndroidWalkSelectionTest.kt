package dev.aster.vega.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.aster.vega.scene.Fill
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.MetricTextEngine
import dev.aster.vega.scene.RuleNode
import dev.aster.vega.scene.Scene
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.SceneNodeIdAllocator
import dev.aster.vega.scene.ScenePaint
import dev.aster.vega.scene.Stroke
import dev.aster.vega.scene.TextNode
import dev.aster.vega.scene.TextRun
import dev.aster.vega.scene.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Android canvas draws the items the scene says it should, and no others.
 *
 * The counterpart of `SvgWalkSelectionTest`, which does this for the export over the whole fixture
 * corpus on the JVM. This renderer has no JVM home — every test in the module is instrumented,
 * because what it does that matters is issue real `Canvas` calls with real `Paint` and `Typeface`,
 * and a recording fake would go green without exercising any of it. So the equivalent lives here,
 * and runs where the other instrumented suites run: on a device, and in CI at API 26 and 36.
 *
 * **Counted by intercepting the real `Canvas`.** A subclass that tallies the calls and then
 * delegates draws the same pixels as the renderer otherwise would, so this is a statement about the
 * renderer's own behaviour rather than about a parallel implementation of it — the distinction
 * `SceneWalk`'s documentation makes about its own recording target.
 *
 * The four guards in `paintsNothing` are what this pins. Each was missing from at least one of the
 * four walks over a scene, and the one that shipped — a label an axis had hidden being painted
 * black — was invisible to every suite because each renderer's tests asserted about that renderer.
 */
@RunWith(AndroidJUnit4::class)
class AndroidWalkSelectionTest {

  private val ids = SceneNodeIdAllocator()
  private val text = MetricTextEngine()

  /** A real `Canvas` that counts what it was asked to draw before drawing it. */
  private class CountingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
    var labels = 0
    var rules = 0

    override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
      labels++
      super.drawText(text, x, y, paint)
    }

    override fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, paint: Paint) {
      rules++
      super.drawLine(startX, startY, stopX, stopY, paint)
    }
  }

  private fun label(
    content: String,
    x: Double = 10.0,
    y: Double = 10.0,
    opacity: Double = 1.0,
    visible: Boolean = true,
    absent: Boolean = false,
  ) =
    TextNode(
      id = ids.allocate(),
      x = x,
      y = y,
      layout = text.layout(TextRun(content, TextStyle(fontSize = 10.0))),
      opacity = opacity,
      visible = visible,
      absent = absent,
      fill = Fill(ScenePaint.Black),
    )

  private fun render(vararg children: SceneNode): CountingCanvas {
    val scene =
      Scene(
        width = 200.0,
        height = 100.0,
        background = null,
        root = GroupNode(id = ids.allocate(), children = children.toList()),
      )
    val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
    val canvas = CountingCanvas(bitmap)
    AndroidCanvasSceneRenderer().render(scene, canvas, RectF(0f, 0f, 200f, 100f), pixelScale = 1f)
    return canvas
  }

  /**
   * One label of five reaches the canvas, and it is the only one the scene asks to be drawn.
   *
   * All four clauses in one case, because they are one predicate and a renderer either asks it or
   * does not. Written as a single count rather than four so that a renderer which asked a *stale*
   * copy of the predicate — the failure this whole line of work is about — cannot pass four
   * separate assertions by getting three of them right.
   */
  @Test
  fun onlyTheLabelsTheSceneDrawsReachTheCanvas() {
    val canvas =
      render(
        label("kept"),
        label("hidden by the axis", opacity = 0.0),
        label("not visible", visible = false),
        label("no text property at all", absent = true),
        label("no usable anchor", x = Double.NaN),
      )
    assertEquals("only the one drawable label should have been painted", 1, canvas.labels)
  }

  /** A transparent *group* is not an invisible one: its children are still drawn. */
  @Test
  fun aTransparentGroupStillDrawsItsChildren() {
    val inner =
      GroupNode(
        id = ids.allocate(),
        opacity = 0.0,
        children = listOf(label("inside a faded group")),
      )
    assertEquals(1, render(inner).labels)
  }

  /** An invisible group takes its children with it, which is the other half of that rule. */
  @Test
  fun anInvisibleGroupTakesItsChildrenWithIt() {
    val inner =
      GroupNode(
        id = ids.allocate(),
        visible = false,
        children = listOf(label("inside a hidden group")),
      )
    assertEquals(0, render(inner).labels)
  }

  /** And the same predicate governs a rule, so the count is not a fact about text alone. */
  @Test
  fun onlyTheRulesTheSceneDrawsReachTheCanvas() {
    fun rule(opacity: Double = 1.0, visible: Boolean = true) =
      RuleNode(
        id = ids.allocate(),
        x1 = 0.0,
        y1 = 5.0,
        x2 = 100.0,
        y2 = 5.0,
        stroke = Stroke(ScenePaint.Black, width = 1.0),
        opacity = opacity,
        visible = visible,
      )
    val canvas = render(rule(), rule(opacity = 0.0), rule(visible = false))
    assertEquals(1, canvas.rules)
  }
}
