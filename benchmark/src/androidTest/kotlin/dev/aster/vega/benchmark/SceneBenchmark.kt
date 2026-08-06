package dev.aster.vega.benchmark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.aster.vega.android.AndroidCanvasSceneRenderer
import dev.aster.vega.android.AndroidTextEngine
import dev.aster.vega.fixtures.SampleScenes
import dev.aster.vega.scene.HitTestOptions
import dev.aster.vega.scene.PointD
import dev.aster.vega.scene.Scene
import dev.aster.vega.scene.SceneHitIndex
import dev.aster.vega.scene.toCanonicalJson
import dev.aster.vega.svg.toSvg
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Microbenchmarks for the stages named in PROJECT_BRIEF.md 18.6.
 *
 * Emulator numbers are not authoritative; release thresholds must come from a physical device
 * (PROJECT_BRIEF.md 18.6). Record fixture, device, Android version, build type and percentiles in
 * STATUS.md when a target is missed.
 */
@RunWith(AndroidJUnit4::class)
class SceneBenchmark {

  @get:Rule val benchmarkRule = BenchmarkRule()

  private val textEngine = AndroidTextEngine()

  private fun bitmapFor(scene: Scene): Pair<Bitmap, Canvas> {
    val bitmap =
      Bitmap.createBitmap(
        scene.width.toInt().coerceAtLeast(1),
        scene.height.toInt().coerceAtLeast(1),
        Bitmap.Config.ARGB_8888,
      )
    return bitmap to Canvas(bitmap)
  }

  @Test
  fun sceneBuild100Bars() {
    benchmarkRule.measureRepeated { SampleScenes.barChart(textEngine) }
  }

  @Test
  fun sceneBuild1000Symbols() {
    benchmarkRule.measureRepeated { SampleScenes.symbolStressTest(count = 1_000) }
  }

  @Test
  fun sceneBuild10000Symbols() {
    benchmarkRule.measureRepeated { SampleScenes.symbolStressTest(count = 10_000) }
  }

  @Test
  fun canvasDrawBarChart() {
    val scene = SampleScenes.barChart(textEngine)
    val (_, canvas) = bitmapFor(scene)
    val renderer = AndroidCanvasSceneRenderer(textEngine)
    val viewport = RectF(0f, 0f, scene.width.toFloat(), scene.height.toFloat())
    benchmarkRule.measureRepeated { renderer.render(scene, canvas, viewport, 1f) }
  }

  @Test
  fun canvasDraw10000Symbols() {
    val scene = SampleScenes.symbolStressTest(count = 10_000)
    val (_, canvas) = bitmapFor(scene)
    val renderer = AndroidCanvasSceneRenderer(textEngine)
    val viewport = RectF(0f, 0f, scene.width.toFloat(), scene.height.toFloat())
    benchmarkRule.measureRepeated { renderer.render(scene, canvas, viewport, 1f) }
  }

  @Test
  fun hitTestIndexBuild100000Symbols() {
    val scene = SampleScenes.symbolStressTest(count = 100_000, width = 2000.0, height = 2000.0)
    benchmarkRule.measureRepeated { SceneHitIndex(scene, HitTestOptions.Touch) }
  }

  @Test
  fun hitTestQueryAmong100000Symbols() {
    val scene = SampleScenes.symbolStressTest(count = 100_000, width = 2000.0, height = 2000.0)
    val index = SceneHitIndex(scene, HitTestOptions.Touch)
    var probe = 0
    benchmarkRule.measureRepeated {
      // Vary the probe so the benchmark cannot benefit from repeatedly hitting one cell.
      probe = (probe + 37) % 2000
      index.hitTest(PointD(probe.toDouble(), (probe * 7 % 2000).toDouble()))
    }
  }

  @Test
  fun svgSerializeBarChart() {
    val scene = SampleScenes.barChart(textEngine)
    benchmarkRule.measureRepeated { scene.toSvg() }
  }

  @Test
  fun canonicalSnapshotBarChart() {
    val scene = SampleScenes.barChart(textEngine)
    benchmarkRule.measureRepeated { scene.toCanonicalJson() }
  }

  @Test
  fun textLayout1000Labels() {
    val engine = AndroidTextEngine()
    benchmarkRule.measureRepeated {
      for (i in 0 until 1_000) {
        engine.measure(dev.aster.vega.scene.TextRun("label $i"))
      }
    }
  }
}
