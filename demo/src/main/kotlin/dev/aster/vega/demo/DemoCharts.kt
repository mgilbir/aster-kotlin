package dev.aster.vega.demo

import dev.aster.vega.fixtures.SampleScenes
import dev.aster.vega.scene.Scene
import dev.aster.vega.scene.TextEngine

/**
 * The chart catalogue the demo renders.
 *
 * Every entry is built from a hand-authored scene and from the Android text engine, so the demo
 * shows exactly the geometry the device will draw. Milestone 3 replaces these builders with Vega
 * JSON loaded from assets.
 */
public enum class DemoChart(public val label: String) {
  BAR("Bar"),
  STACKED_BAR("Stacked bar"),
  LINE("Line"),
  AREA("Area"),
  SCATTER("Scatter"),
  STRESS("10k symbols");

  /**
   * Builds the scene for this entry.
   *
   * [dark] selects a palette rather than only swapping the background: axis, grid and label colours
   * have to change too, otherwise the chrome authored for a white surface is unreadable.
   */
  public fun build(textEngine: TextEngine, dark: Boolean): Scene {
    val palette = if (dark) SampleScenes.Palette.Dark else SampleScenes.Palette.Light
    return when (this) {
      BAR -> SampleScenes.barChart(textEngine, palette = palette)
      STACKED_BAR -> SampleScenes.stackedBarChart(textEngine, palette = palette)
      LINE -> SampleScenes.lineChart(textEngine, palette = palette)
      AREA -> SampleScenes.areaChart(textEngine, palette = palette)
      SCATTER -> SampleScenes.scatterPlot(textEngine, palette = palette)
      STRESS -> SampleScenes.symbolStressTest(count = 10_000, palette = palette)
    }
  }
}
