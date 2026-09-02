package dev.aster.vega.fixtures

import dev.aster.vega.model.VegaValue
import dev.aster.vega.scene.AccessibilityDescriptor
import dev.aster.vega.scene.Fill
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.MetricTextEngine
import dev.aster.vega.scene.NodeMetadata
import dev.aster.vega.scene.PathData
import dev.aster.vega.scene.PathNode
import dev.aster.vega.scene.PointD
import dev.aster.vega.scene.RectD
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.RuleNode
import dev.aster.vega.scene.Scene
import dev.aster.vega.scene.SceneColor
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.SceneNodeIdAllocator
import dev.aster.vega.scene.ScenePaint
import dev.aster.vega.scene.Stroke
import dev.aster.vega.scene.StrokeCap
import dev.aster.vega.scene.StrokeJoin
import dev.aster.vega.scene.SymbolNode
import dev.aster.vega.scene.SymbolShape
import dev.aster.vega.scene.TextAlign
import dev.aster.vega.scene.TextBaseline
import dev.aster.vega.scene.TextEngine
import dev.aster.vega.scene.TextNode
import dev.aster.vega.scene.TextRun
import dev.aster.vega.scene.TextStyle
import dev.aster.vega.scene.Transform2D
import dev.aster.vega.scene.spokenNumber

/**
 * Hand-authored scenes, built without any Vega parsing.
 *
 * Milestone 1 uses these to exercise the scene graph, the Canvas renderer and the SVG serializer
 * before a specification compiler exists. They are also the fixtures the demo application renders,
 * so a visual regression shows up in the demo and in tests together.
 *
 * Every builder takes a [TextEngine] because text measurement is part of layout: passing the
 * Android engine produces the scene the device will actually draw, while [MetricTextEngine]
 * produces the deterministic scene the JVM tests assert on.
 */
public object SampleScenes {

  public data class Category(val label: String, val value: Double)

  public val monthlyRevenue: List<Category> =
    listOf(
      Category("Jan", 28.0),
      Category("Feb", 55.0),
      Category("Mar", 43.0),
      Category("Apr", 91.0),
      Category("May", 81.0),
      Category("Jun", 53.0),
      Category("Jul", 19.0),
      Category("Aug", 87.0),
    )

  public val trend: List<PointD> =
    listOf(
      PointD(0.0, 12.0),
      PointD(1.0, 43.0),
      PointD(2.0, 31.0),
      PointD(3.0, 68.0),
      PointD(4.0, 55.0),
      PointD(5.0, 78.0),
      PointD(6.0, 62.0),
      PointD(7.0, 95.0),
    )

  /**
   * Colours the sample charts draw with.
   *
   * Axis, grid and label colours have to change with the background, otherwise a chart authored for
   * white is unreadable on a dark surface. The mark colours stay put so light and dark renders of
   * the same fixture differ only in chrome.
   */
  public data class Palette(
    val background: SceneColor,
    val title: SceneColor,
    val label: SceneColor,
    val axis: SceneColor,
    val grid: SceneColor,
  ) {
    internal val axisStroke: Stroke
      get() = Stroke(paint = ScenePaint.solid(axis), width = 1.0)

    internal val gridStroke: Stroke
      get() = Stroke(paint = ScenePaint.solid(grid), width = 1.0, dashArray = listOf(2.0, 2.0))

    public companion object {
      public val Light: Palette =
        Palette(
          background = SceneColor.White,
          title = SceneColor.parse("#222222")!!,
          label = SceneColor.parse("#444444")!!,
          axis = SceneColor.parse("#888888")!!,
          grid = SceneColor.parse("#dddddd")!!,
        )

      public val Dark: Palette =
        Palette(
          background = SceneColor(0.11, 0.12, 0.14),
          title = SceneColor.parse("#f2f2f2")!!,
          label = SceneColor.parse("#c9c9c9")!!,
          axis = SceneColor.parse("#8b8b8b")!!,
          grid = SceneColor.parse("#3a3d42")!!,
        )
    }
  }

  private val labelStyle = TextStyle(fontFamily = "sans-serif", fontSize = 11.0)
  private val titleStyle = TextStyle(fontFamily = "sans-serif", fontSize = 15.0, fontWeight = 600)

  /** Padding around the plotting area, matching Vega's default `padding: 5` plus room for axes. */
  private data class Padding(
    val left: Double = 44.0,
    val top: Double = 34.0,
    val right: Double = 12.0,
    val bottom: Double = 30.0,
  )

  public fun barChart(
    textEngine: TextEngine = MetricTextEngine(),
    width: Double = 400.0,
    height: Double = 260.0,
    palette: Palette = Palette.Light,
  ): Scene {
    val ids = SceneNodeIdAllocator()
    val padding = Padding()
    val plot = plotRect(width, height, padding)
    val maxValue = 100.0
    val bandWidth = plot.width / monthlyRevenue.size
    val barPadding = bandWidth * 0.1

    val children = mutableListOf<SceneNode>()
    children += title(ids, textEngine, "Monthly revenue", width, padding, palette)
    children += yAxis(ids, textEngine, plot, maxValue, ticks = 5, palette = palette)
    children += xBandAxis(ids, textEngine, plot, monthlyRevenue.map { it.label }, palette)

    monthlyRevenue.forEachIndexed { index, category ->
      val barHeight = plot.height * (category.value / maxValue)
      children +=
        RectNode(
          id = ids.allocate(),
          x = plot.left + index * bandWidth + barPadding,
          y = plot.bottom - barHeight,
          width = bandWidth - 2 * barPadding,
          height = barHeight,
          fill = Fill.of(SceneColor.parse("#4682b4")!!),
          metadata =
            NodeMetadata(
              markName = "bars",
              role = "mark",
              datumId = index.toLong(),
              datumIndex = index,
              interactive = true,
              tooltip = VegaValue.Str("${category.label}: ${category.value}"),
              accessibility =
                AccessibilityDescriptor(
                  label = category.label,
                  value = spokenNumber(category.value),
                  role = "graphics-symbol",
                  focusable = true,
                ),
            ),
        )
    }

    return scene(width, height, ids, children, palette)
  }

  public fun stackedBarChart(
    textEngine: TextEngine = MetricTextEngine(),
    width: Double = 400.0,
    height: Double = 260.0,
    palette: Palette = Palette.Light,
  ): Scene {
    val ids = SceneNodeIdAllocator()
    val padding = Padding()
    val plot = plotRect(width, height, padding)
    val series =
      listOf(
        "product" to SceneColor.parse("#4682b4")!!,
        "services" to SceneColor.parse("#f0a35e")!!,
        "support" to SceneColor.parse("#7fb069")!!,
      )
    // Three deterministic splits of each total, so the stack always sums to the bar's value.
    val splits = listOf(0.55, 0.3, 0.15)
    val maxValue = 100.0
    val bandWidth = plot.width / monthlyRevenue.size
    val barPadding = bandWidth * 0.15

    val children = mutableListOf<SceneNode>()
    children += title(ids, textEngine, "Revenue by segment", width, padding, palette)
    children += yAxis(ids, textEngine, plot, maxValue, ticks = 5, palette = palette)
    children += xBandAxis(ids, textEngine, plot, monthlyRevenue.map { it.label }, palette)

    monthlyRevenue.forEachIndexed { index, category ->
      var cursor = plot.bottom
      series.forEachIndexed { seriesIndex, (name, color) ->
        val segmentValue = category.value * splits[seriesIndex]
        val segmentHeight = plot.height * (segmentValue / maxValue)
        children +=
          RectNode(
            id = ids.allocate(),
            x = plot.left + index * bandWidth + barPadding,
            y = cursor - segmentHeight,
            width = bandWidth - 2 * barPadding,
            height = segmentHeight,
            fill = Fill.of(color),
            metadata =
              NodeMetadata(
                markName = "stack",
                role = "mark",
                datumIndex = index * series.size + seriesIndex,
                interactive = true,
                tooltip = VegaValue.Str("${category.label} $name"),
                accessibility =
                  AccessibilityDescriptor(
                    label = "${category.label} $name",
                    value = spokenNumber(segmentValue),
                    focusable = true,
                  ),
              ),
          )
        cursor -= segmentHeight
      }
    }

    children += legend(ids, textEngine, series.map { it.first to it.second }, plot, palette)
    return scene(width, height, ids, children, palette)
  }

  public fun lineChart(
    textEngine: TextEngine = MetricTextEngine(),
    width: Double = 400.0,
    height: Double = 260.0,
    palette: Palette = Palette.Light,
  ): Scene {
    val ids = SceneNodeIdAllocator()
    val padding = Padding()
    val plot = plotRect(width, height, padding)
    val maxValue = 100.0

    val children = mutableListOf<SceneNode>()
    children += title(ids, textEngine, "Trend", width, padding, palette)
    children += yAxis(ids, textEngine, plot, maxValue, ticks = 5, palette = palette)
    children += xLinearAxis(ids, textEngine, plot, trend.size - 1, palette)

    val points = trend.map { toPlot(it, plot, trend.size - 1, maxValue) }
    children +=
      PathNode(
        id = ids.allocate(),
        path = PathData.build { polyline(points) },
        stroke =
          Stroke(
            paint = ScenePaint.solid(SceneColor.parse("#4682b4")!!),
            width = 2.0,
            cap = StrokeCap.ROUND,
            join = StrokeJoin.ROUND,
          ),
        metadata = NodeMetadata(markName = "line", role = "mark", interactive = true),
      )
    return scene(width, height, ids, children, palette)
  }

  public fun areaChart(
    textEngine: TextEngine = MetricTextEngine(),
    width: Double = 400.0,
    height: Double = 260.0,
    palette: Palette = Palette.Light,
  ): Scene {
    val ids = SceneNodeIdAllocator()
    val padding = Padding()
    val plot = plotRect(width, height, padding)
    val maxValue = 100.0

    val children = mutableListOf<SceneNode>()
    children += title(ids, textEngine, "Cumulative", width, padding, palette)
    children += yAxis(ids, textEngine, plot, maxValue, ticks = 5, palette = palette)
    children += xLinearAxis(ids, textEngine, plot, trend.size - 1, palette)

    val points = trend.map { toPlot(it, plot, trend.size - 1, maxValue) }
    val area = PathData.build {
      polyline(points)
      lineTo(points.last().x, plot.bottom)
      lineTo(points.first().x, plot.bottom)
      close()
    }
    children +=
      PathNode(
        id = ids.allocate(),
        path = area,
        fill =
          Fill(
            paint =
              ScenePaint.LinearGradient(
                x1 = 0.0,
                y1 = 0.0,
                x2 = 0.0,
                y2 = 1.0,
                stops =
                  listOf(
                    dev.aster.vega.scene.GradientStop(0.0, SceneColor.parse("#4682b4")!!),
                    dev.aster.vega.scene.GradientStop(
                      1.0,
                      SceneColor.parse("#4682b4")!!.withAlpha(0.15),
                    ),
                  ),
              )
          ),
        stroke = Stroke(paint = ScenePaint.solid(SceneColor.parse("#2c5d80")!!), width = 1.5),
        metadata = NodeMetadata(markName = "area", role = "mark", interactive = true),
      )
    return scene(width, height, ids, children, palette)
  }

  public fun scatterPlot(
    textEngine: TextEngine = MetricTextEngine(),
    width: Double = 400.0,
    height: Double = 260.0,
    pointCount: Int = 60,
    palette: Palette = Palette.Light,
  ): Scene {
    val ids = SceneNodeIdAllocator()
    val padding = Padding()
    val plot = plotRect(width, height, padding)

    val children = mutableListOf<SceneNode>()
    children += title(ids, textEngine, "Scatter", width, padding, palette)
    children += yAxis(ids, textEngine, plot, 100.0, ticks = 5, palette = palette)
    children += xLinearAxis(ids, textEngine, plot, 100, palette)

    val shapes = SymbolShape.entries.filter { it != SymbolShape.STROKE }
    for (index in 0 until pointCount) {
      // A deterministic low-discrepancy sequence: reproducible without a random source.
      val u = fract(index * 0.7548776662466927)
      val v = fract(index * 0.5698402909980532)
      children +=
        SymbolNode(
          id = ids.allocate(),
          x = plot.left + u * plot.width,
          y = plot.top + v * plot.height,
          size = 40.0 + (index % 5) * 25.0,
          shape = shapes[index % shapes.size],
          fill = Fill(paint = ScenePaint.solid(SceneColor.parse("#4682b4")!!), opacity = 0.7),
          stroke = Stroke(paint = ScenePaint.solid(SceneColor.parse("#2c5d80")!!), width = 1.0),
          metadata =
            NodeMetadata(
              markName = "points",
              role = "mark",
              datumIndex = index,
              interactive = true,
              tooltip = VegaValue.Str("point $index"),
            ),
        )
    }
    return scene(width, height, ids, children, palette)
  }

  /**
   * A large scene for performance work. [count] symbols laid out deterministically, with no text,
   * so benchmarks measure geometry and drawing rather than text layout.
   */
  public fun symbolStressTest(
    count: Int,
    width: Double = 400.0,
    height: Double = 400.0,
    palette: Palette = Palette.Light,
  ): Scene {
    val ids = SceneNodeIdAllocator()
    val children = ArrayList<SceneNode>(count)
    for (index in 0 until count) {
      children +=
        SymbolNode(
          id = ids.allocate(),
          x = fract(index * 0.7548776662466927) * width,
          y = fract(index * 0.5698402909980532) * height,
          size = 12.0,
          shape = SymbolShape.CIRCLE,
          fill = Fill.of(SceneColor.parse("#4682b4")!!),
          metadata = NodeMetadata(markName = "points", datumIndex = index, interactive = true),
        )
    }
    return scene(width, height, ids, children, palette)
  }

  // ---- shared pieces -------------------------------------------------------

  private fun scene(
    width: Double,
    height: Double,
    ids: SceneNodeIdAllocator,
    children: List<SceneNode>,
    palette: Palette,
  ): Scene =
    Scene(
      width = width,
      height = height,
      background = palette.background,
      root =
        GroupNode(
          id = ids.allocate(),
          children = children,
          clip = RectD(0.0, 0.0, width, height),
          metadata = NodeMetadata(role = "graphics-document"),
        ),
      revision = 1L,
    )

  private fun plotRect(width: Double, height: Double, padding: Padding): RectD =
    RectD(padding.left, padding.top, width - padding.right, height - padding.bottom)

  private fun toPlot(point: PointD, plot: RectD, maxX: Int, maxY: Double): PointD =
    PointD(
      x = plot.left + (point.x / maxX) * plot.width,
      y = plot.bottom - (point.y / maxY) * plot.height,
    )

  private fun title(
    ids: SceneNodeIdAllocator,
    textEngine: TextEngine,
    text: String,
    width: Double,
    padding: Padding,
    palette: Palette,
  ): SceneNode {
    val run =
      TextRun(
        text = text,
        style = titleStyle,
        align = TextAlign.CENTER,
        baseline = TextBaseline.TOP,
      )
    return TextNode(
      id = ids.allocate(),
      x = width / 2.0,
      y = padding.top / 3.0,
      layout = textEngine.layout(run),
      fill = Fill.of(palette.title),
      metadata =
        NodeMetadata(
          role = "title",
          accessibility = AccessibilityDescriptor(label = text, role = "heading"),
        ),
    )
  }

  private fun yAxis(
    ids: SceneNodeIdAllocator,
    textEngine: TextEngine,
    plot: RectD,
    maxValue: Double,
    ticks: Int,
    palette: Palette,
  ): SceneNode {
    val children = mutableListOf<SceneNode>()
    children +=
      RuleNode(
        id = ids.allocate(),
        x1 = plot.left,
        y1 = plot.top,
        x2 = plot.left,
        y2 = plot.bottom,
        stroke = palette.axisStroke,
      )
    for (i in 0..ticks) {
      val value = maxValue * i / ticks
      val y = plot.bottom - plot.height * i / ticks
      children +=
        RuleNode(
          id = ids.allocate(),
          x1 = plot.left,
          y1 = y,
          x2 = plot.right,
          y2 = y,
          stroke = palette.gridStroke,
        )
      children +=
        RuleNode(
          id = ids.allocate(),
          x1 = plot.left - 4.0,
          y1 = y,
          x2 = plot.left,
          y2 = y,
          stroke = palette.axisStroke,
        )
      children +=
        TextNode(
          id = ids.allocate(),
          x = plot.left - 7.0,
          y = y,
          layout =
            textEngine.layout(
              TextRun(
                text = formatTick(value),
                style = labelStyle,
                align = TextAlign.RIGHT,
                baseline = TextBaseline.MIDDLE,
              )
            ),
          fill = Fill.of(palette.label),
        )
    }
    return GroupNode(
      id = ids.allocate(),
      children = children,
      metadata = NodeMetadata(role = "axis", markName = "y-axis"),
    )
  }

  private fun xBandAxis(
    ids: SceneNodeIdAllocator,
    textEngine: TextEngine,
    plot: RectD,
    labels: List<String>,
    palette: Palette,
  ): SceneNode {
    val children = mutableListOf<SceneNode>()
    children +=
      RuleNode(
        id = ids.allocate(),
        x1 = plot.left,
        y1 = plot.bottom,
        x2 = plot.right,
        y2 = plot.bottom,
        stroke = palette.axisStroke,
      )
    val bandWidth = plot.width / labels.size
    labels.forEachIndexed { index, label ->
      val center = plot.left + (index + 0.5) * bandWidth
      children +=
        RuleNode(
          id = ids.allocate(),
          x1 = center,
          y1 = plot.bottom,
          x2 = center,
          y2 = plot.bottom + 4.0,
          stroke = palette.axisStroke,
        )
      children +=
        TextNode(
          id = ids.allocate(),
          x = center,
          y = plot.bottom + 7.0,
          layout =
            textEngine.layout(
              TextRun(
                text = label,
                style = labelStyle,
                align = TextAlign.CENTER,
                baseline = TextBaseline.TOP,
              )
            ),
          fill = Fill.of(palette.label),
        )
    }
    return GroupNode(
      id = ids.allocate(),
      children = children,
      metadata = NodeMetadata(role = "axis", markName = "x-axis"),
    )
  }

  private fun xLinearAxis(
    ids: SceneNodeIdAllocator,
    textEngine: TextEngine,
    plot: RectD,
    maxValue: Int,
    palette: Palette,
  ): SceneNode {
    val children = mutableListOf<SceneNode>()
    children +=
      RuleNode(
        id = ids.allocate(),
        x1 = plot.left,
        y1 = plot.bottom,
        x2 = plot.right,
        y2 = plot.bottom,
        stroke = palette.axisStroke,
      )
    val ticks = 4
    for (i in 0..ticks) {
      val x = plot.left + plot.width * i / ticks
      children +=
        RuleNode(
          id = ids.allocate(),
          x1 = x,
          y1 = plot.bottom,
          x2 = x,
          y2 = plot.bottom + 4.0,
          stroke = palette.axisStroke,
        )
      children +=
        TextNode(
          id = ids.allocate(),
          x = x,
          y = plot.bottom + 7.0,
          layout =
            textEngine.layout(
              TextRun(
                text = formatTick(maxValue.toDouble() * i / ticks),
                style = labelStyle,
                align = TextAlign.CENTER,
                baseline = TextBaseline.TOP,
              )
            ),
          fill = Fill.of(palette.label),
        )
    }
    return GroupNode(
      id = ids.allocate(),
      children = children,
      metadata = NodeMetadata(role = "axis", markName = "x-axis"),
    )
  }

  private fun legend(
    ids: SceneNodeIdAllocator,
    textEngine: TextEngine,
    entries: List<Pair<String, SceneColor>>,
    plot: RectD,
    palette: Palette,
  ): SceneNode {
    val children = mutableListOf<SceneNode>()
    val swatch = 9.0
    entries.forEachIndexed { index, (label, color) ->
      val y = plot.top + index * 14.0
      children +=
        RectNode(
          id = ids.allocate(),
          x = 0.0,
          y = y,
          width = swatch,
          height = swatch,
          fill = Fill.of(color),
        )
      children +=
        TextNode(
          id = ids.allocate(),
          x = swatch + 4.0,
          y = y + swatch / 2.0,
          layout =
            textEngine.layout(
              TextRun(
                text = label,
                style = labelStyle,
                align = TextAlign.LEFT,
                baseline = TextBaseline.MIDDLE,
              )
            ),
          fill = Fill.of(palette.label),
        )
    }
    return GroupNode(
      id = ids.allocate(),
      children = children,
      // Legends are positioned by a group transform rather than by baking offsets into every child.
      transform = Transform2D.translate(plot.right - 74.0, 0.0),
      metadata = NodeMetadata(role = "legend", markName = "legend"),
    )
  }

  private fun formatTick(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

  private fun fract(value: Double): Double = value - kotlin.math.floor(value)
}
