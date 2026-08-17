package dev.aster.vega.android

import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.aster.vega.runtime.VegaChartController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a screen reader is actually told about a chart.
 *
 * Both assertions here come from pointing TalkBack at the demo on an emulator rather than from
 * reading the code: the accessibility *tree* was already correct and well covered, and what it said
 * was wrong in two ways that only listening reveals.
 */
@RunWith(AndroidJUnit4::class)
class ChartAccessibilityInstrumentedTest {

  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

  private val spec =
    """
    {
      "description": "Monthly rainfall in millimetres.",
      "width": 200, "height": 100, "padding": 0,
      "data": [{"name": "t", "values": [
        {"c": "Jan", "v": 28}, {"c": "Feb", "v": 55.5}
      ]}],
      "scales": [
        {"name": "x", "type": "band", "domain": {"data": "t", "field": "c"}, "range": "width"},
        {"name": "y", "type": "linear", "domain": {"data": "t", "field": "v"}, "range": "height"}
      ],
      "marks": [{
        "type": "rect", "from": {"data": "t"},
        "encode": {"enter": {
          "x": {"scale": "x", "field": "c"},
          "width": {"scale": "x", "band": 1},
          "y": {"scale": "y", "field": "v"},
          "y2": {"scale": "y", "value": 0}
        }}
      }]
    }
    """
      .trimIndent()

  private fun <T> onMainThread(block: () -> T): T {
    var result: T? = null
    var failure: Throwable? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      try {
        result = block()
      } catch (t: Throwable) {
        failure = t
      }
    }
    failure?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
  }

  private fun laidOutView(): VegaChartView = onMainThread {
    val controller = VegaChartController(textEngine = AndroidTextEngine())
    controller.setSpec(spec)
    val view = VegaChartView(context)
    view.controller = controller
    view.measure(
      View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
      View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
    )
    view.layout(0, 0, 200, 100)
    view
  }

  /**
   * A whole number is said as a whole number.
   *
   * The canonical form is `28.0`, because it has to round-trip and compare; TalkBack reads that as
   * "twenty-eight point zero", so a chart of whole numbers sounds as though every value carried a
   * spurious decimal. A genuine fraction still keeps its digits.
   */
  @Test
  fun wholeNumbersAreNotReadWithASpuriousDecimal() {
    val labels =
      laidOutView().controller.snapshot.scene.let { scene ->
        val out = mutableListOf<String>()
        fun walk(node: dev.aster.vega.scene.SceneNode) {
          node.metadata.accessibility?.let { a ->
            out += a.value?.let { "${a.label}: $it" } ?: a.label
          }
          if (node is dev.aster.vega.scene.GroupNode) node.children.forEach { walk(it) }
        }
        walk(scene.root)
        out
      }
    assertTrue(labels.toString(), labels.contains("Jan: 28"))
    assertTrue("a real fraction keeps its digits", labels.contains("Feb: 55.5"))
  }

  /**
   * The chart says what it is before it says what is in it.
   *
   * Every fixture here already carried a `description` and nothing read it, so a screen reader
   * announced a list of labelled values and never said they were a chart, let alone of what.
   */
  @Test
  fun theChartAnnouncesWhatItIs() {
    assertEquals("Monthly rainfall in millimetres.", laidOutView().contentDescription)
  }

  /** A hand-built scene has no specification, so a host's own description is left alone. */
  @Test
  fun aHostSuppliedDescriptionIsNotOverwritten() {
    val view = onMainThread {
      val view = VegaChartView(context)
      view.contentDescription = "set by the host"
      view.controller = VegaChartController()
      view
    }
    assertEquals("set by the host", view.contentDescription)
  }
}
