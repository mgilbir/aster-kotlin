package dev.aster.vega.demo

import dev.aster.vega.fixtures.SampleScenes
import dev.aster.vega.scene.Scene
import dev.aster.vega.scene.TextEngine

/**
 * The chart catalogue the demo renders.
 *
 * Two kinds of entry, deliberately side by side. The hand-authored ones build a [Scene] directly
 * and are what the renderer was developed against; the [specAsset] ones load Vega or Vega-Lite JSON
 * from the app's assets and compile it, which is what a user of this library actually does. Both go
 * through the same controller and the same surface, so switching between them shows whether the
 * compiler produces scenes the rest of the stack is happy with.
 */
public enum class DemoChart(
  public val label: String,
  public val specAsset: String? = null,
  /**
   * True for the entry whose specification the user supplies rather than the app.
   *
   * It is the only entry that can fail, which makes it the only one where the diagnostics matter to
   * somebody other than us — so it is the reason the demo shows them at all.
   */
  public val isPasted: Boolean = false,
) {
  BAR("Bar"),
  STACKED_BAR("Stacked bar"),
  LINE("Line"),
  AREA("Area"),
  SCATTER("Scatter"),
  STRESS("10k symbols"),
  SPEC_TITLES("Spec: titles", "titles.vg.json"),
  SPEC_LEGENDS("Spec: legends", "legends.vg.json"),
  SPEC_FACETS("Spec: facets", "facet-trellis.vg.json"),
  // A Vega-Lite specification, compiled to Vega before the runtime ever sees it — a layered chart,
  // so what is on screen is something the Vega-Lite grammar states in six lines and the Vega one
  // does not state at all.
  SPEC_VEGA_LITE("Spec: Vega-Lite", "layered.vl.json"),
  PASTED("Paste your own", isPasted = true);

  /** True when this entry is compiled from a specification rather than built by hand. */
  public val isSpec: Boolean
    get() = specAsset != null || isPasted

  /**
   * Builds the scene for a hand-authored entry.
   *
   * [dark] selects a palette rather than only swapping the background: axis, grid and label colours
   * have to change too, otherwise the chrome authored for a white surface is unreadable. The
   * specification entries have no equivalent — their colours come from the specification, which is
   * the point — so this returns `null` for them.
   */
  public fun build(textEngine: TextEngine, dark: Boolean): Scene? {
    val palette = if (dark) SampleScenes.Palette.Dark else SampleScenes.Palette.Light
    return when (this) {
      BAR -> SampleScenes.barChart(textEngine, palette = palette)
      STACKED_BAR -> SampleScenes.stackedBarChart(textEngine, palette = palette)
      LINE -> SampleScenes.lineChart(textEngine, palette = palette)
      AREA -> SampleScenes.areaChart(textEngine, palette = palette)
      SCATTER -> SampleScenes.scatterPlot(textEngine, palette = palette)
      STRESS -> SampleScenes.symbolStressTest(count = 10_000, palette = palette)
      SPEC_TITLES,
      SPEC_LEGENDS,
      SPEC_FACETS,
      SPEC_VEGA_LITE,
      PASTED -> null
    }
  }
}
