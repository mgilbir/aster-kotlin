package dev.aster.vega.svg

import dev.aster.vega.fixtures.GoldenFiles
import dev.aster.vega.fixtures.SampleScenes
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.scene.AccessibilityDescriptor
import dev.aster.vega.scene.Fill
import dev.aster.vega.scene.GradientStop
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.ImageNode
import dev.aster.vega.scene.MetricTextEngine
import dev.aster.vega.scene.NodeMetadata
import dev.aster.vega.scene.PathData
import dev.aster.vega.scene.PathNode
import dev.aster.vega.scene.RectD
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.Scene
import dev.aster.vega.scene.SceneColor
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.SceneNodeIdAllocator
import dev.aster.vega.scene.ScenePaint
import dev.aster.vega.scene.SizeD
import dev.aster.vega.scene.Stroke
import dev.aster.vega.scene.StrokeCap
import dev.aster.vega.scene.SymbolNode
import dev.aster.vega.scene.TextAlign
import dev.aster.vega.scene.TextNode
import dev.aster.vega.scene.TextRun
import dev.aster.vega.scene.Transform2D
import dev.aster.vega.scene.flatten
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.xml.sax.InputSource

class SvgRendererTest {

  private val ids = SceneNodeIdAllocator()
  private val textEngine = MetricTextEngine()

  private fun sceneOf(vararg children: SceneNode, background: SceneColor? = null): Scene =
    Scene(
      width = 100.0,
      height = 50.0,
      background = background,
      root = GroupNode(id = ids.allocate(), children = children.toList()),
    )

  @Test
  fun `output is well-formed xml`() {
    for (scene in allSampleScenes()) {
      val svg = scene.toSvg()
      // Throws on malformed XML, which is the assertion.
      DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(InputSource(svg.reader()))
    }
  }

  @Test
  fun `root element declares size and viewBox`() {
    val svg = sceneOf().toSvg()
    assertTrue(svg.contains("""width="100""""))
    assertTrue(svg.contains("""height="50""""))
    assertTrue(svg.contains("""viewBox="0 0 100 50""""))
    assertTrue(svg.contains("""xmlns="http://www.w3.org/2000/svg""""))
  }

  @Test
  fun `serialization is deterministic`() {
    val first = SampleScenes.stackedBarChart(textEngine).toSvg()
    val second = SampleScenes.stackedBarChart(textEngine).toSvg()
    assertEquals(first, second)
  }

  @Test
  fun `text content and attributes are xml escaped`() {
    val node =
      TextNode(
        id = ids.allocate(),
        x = 5.0,
        y = 5.0,
        layout = textEngine.layout(TextRun("a < b & c > d \"q\"")),
        fill = Fill.of(SceneColor.Black),
        metadata =
          NodeMetadata(
            accessibility =
              AccessibilityDescriptor(label = "label & <tag>", role = "graphics-symbol")
          ),
      )
    val svg = sceneOf(node).toSvg()
    assertTrue(svg.contains("a &lt; b &amp; c &gt; d &quot;q&quot;"))
    assertTrue(svg.contains("""aria-label="label &amp; &lt;tag&gt;""""))
    assertFalse(svg.contains("<tag>"))
  }

  @Test
  fun `escapeXml leaves plain text untouched`() {
    assertTrue("plain text" === escapeXml("plain text"))
  }

  @Test
  fun `identity transforms are omitted and non-identity transforms use matrix form`() {
    val plain = RectNode(id = ids.allocate(), x = 0.0, y = 0.0, width = 1.0, height = 1.0)
    assertFalse(sceneOf(plain).toSvg().contains("transform="))

    val moved = plain.copy(transform = Transform2D.translate(3.0, 4.0))
    assertTrue(sceneOf(moved).toSvg().contains("""transform="matrix(1 0 0 1 3 4)""""))
  }

  @Test
  fun `unfilled shapes emit fill none rather than defaulting to black`() {
    val outline =
      RectNode(
        id = ids.allocate(),
        x = 0.0,
        y = 0.0,
        width = 10.0,
        height = 10.0,
        stroke = Stroke(paint = ScenePaint.Black, width = 2.0),
      )
    val svg = sceneOf(outline).toSvg()
    assertTrue(svg.contains("""fill="none""""))
    assertTrue(svg.contains("""stroke="#000000""""))
    assertTrue(svg.contains("""stroke-width="2""""))
  }

  @Test
  fun `stroke details are serialized only when they differ from svg defaults`() {
    val dashed =
      RectNode(
        id = ids.allocate(),
        x = 0.0,
        y = 0.0,
        width = 10.0,
        height = 10.0,
        stroke =
          Stroke(
            paint = ScenePaint.Black,
            width = 1.0,
            cap = StrokeCap.ROUND,
            dashArray = listOf(2.0, 3.0),
            dashOffset = 1.0,
            opacity = 0.5,
          ),
      )
    val svg = sceneOf(dashed).toSvg()
    assertFalse(svg.contains("stroke-width="), "width 1 is the SVG default")
    assertTrue(svg.contains("""stroke-linecap="round""""))
    assertTrue(svg.contains("""stroke-dasharray="2,3""""))
    assertTrue(svg.contains("""stroke-dashoffset="1""""))
    assertTrue(svg.contains("""stroke-opacity="0.5""""))
  }

  @Test
  fun `identical gradients share one generated definition`() {
    val gradient =
      ScenePaint.LinearGradient(
        x1 = 0.0,
        y1 = 0.0,
        x2 = 0.0,
        y2 = 1.0,
        stops =
          listOf(
            GradientStop(0.0, SceneColor.Black),
            GradientStop(1.0, SceneColor.White),
          ),
      )
    val a =
      RectNode(
        id = ids.allocate(),
        x = 0.0,
        y = 0.0,
        width = 10.0,
        height = 10.0,
        fill = Fill(gradient),
      )
    val b =
      RectNode(
        id = ids.allocate(),
        x = 20.0,
        y = 0.0,
        width = 10.0,
        height = 10.0,
        fill = Fill(gradient),
      )
    val svg = sceneOf(a, b).toSvg()

    assertEquals(1, Regex("<linearGradient").findAll(svg).count())
    assertEquals(2, Regex("""url\(#vg0\)""").findAll(svg).count())
  }

  @Test
  fun `generated ids are stable across runs and prefixable`() {
    val gradient =
      ScenePaint.RadialGradient(
        cx = 0.5,
        cy = 0.5,
        radius = 0.5,
        stops = listOf(GradientStop(0.0, SceneColor.White), GradientStop(1.0, SceneColor.Black)),
      )
    fun build() =
      Scene(
        width = 10.0,
        height = 10.0,
        background = null,
        root =
          GroupNode(
            id = SceneNodeIdAllocator().allocate(),
            children =
              listOf(
                RectNode(
                  id = SceneNodeIdAllocator().allocate(),
                  x = 0.0,
                  y = 0.0,
                  width = 10.0,
                  height = 10.0,
                  fill = Fill(gradient),
                )
              ),
          ),
      )
    assertEquals(build().toSvg(), build().toSvg())
    assertTrue(build().toSvg(SvgOptions(idPrefix = "chart1")).contains("""id="chart1g0""""))
  }

  @Test
  fun `clip rectangles become clipPath definitions`() {
    val clipped =
      GroupNode(
        id = ids.allocate(),
        children =
          listOf(RectNode(id = ids.allocate(), x = 0.0, y = 0.0, width = 500.0, height = 500.0)),
        clip = RectD(0.0, 0.0, 20.0, 20.0),
      )
    val svg = sceneOf(clipped).toSvg()
    assertTrue(svg.contains("<clipPath id=\"vc0\">"))
    assertTrue(svg.contains("""clip-path="url(#vc0)""""))
  }

  @Test
  fun `a group paints a rectangle of its own size, clipped or not`() {
    // Vega-Lite's plotting area: a group that states a size and a border and does not clip. The
    // export read the clip alone, so every chart it compiled lost the thin grey box around its
    // plot — visible only by putting the SVG beside upstream's.
    val cell =
      GroupNode(
        id = ids.allocate(),
        children =
          listOf(RectNode(id = ids.allocate(), x = 1.0, y = 1.0, width = 2.0, height = 2.0)),
        size = SizeD(40.0, 30.0),
        stroke = Stroke(ScenePaint.Solid(SceneColor.parse("#ddd")!!)),
      )
    val svg = sceneOf(cell).toSvg()
    assertTrue(
      svg.contains("""<rect x="0" y="0" width="40" height="30""""),
      "the cell's own border is missing:\n$svg",
    )

    // And a group that paints nothing still paints nothing, size or no size.
    val plain = GroupNode(id = ids.allocate(), children = emptyList(), size = SizeD(40.0, 30.0))
    assertFalse(sceneOf(plain).toSvg().contains("<rect"))
  }

  @Test
  fun `background is emitted as a rect when opaque`() {
    assertTrue(sceneOf(background = SceneColor.White).toSvg().contains("""fill="#ffffff""""))
    assertFalse(sceneOf(background = SceneColor.Transparent).toSvg().contains("<rect"))
    assertFalse(sceneOf(background = null).toSvg().contains("<rect"))
  }

  @Test
  fun `multiline text uses tspans anchored to the same x`() {
    val node =
      TextNode(
        id = ids.allocate(),
        x = 10.0,
        y = 20.0,
        layout = textEngine.layout(TextRun("first\nsecond", align = TextAlign.CENTER)),
        fill = Fill.of(SceneColor.Black),
      )
    val svg = sceneOf(node).toSvg()
    assertEquals(2, Regex("<tspan").findAll(svg).count())
    assertEquals(2, Regex("""<tspan x="10"""").findAll(svg).count())
    assertTrue(svg.contains("""text-anchor="middle""""))
  }

  @Test
  fun `symbols are serialized as explicit paths`() {
    val symbol =
      SymbolNode(
        id = ids.allocate(),
        x = 25.0,
        y = 25.0,
        size = 100.0,
        fill = Fill.of(SceneColor.Black),
      )
    val svg = sceneOf(symbol).toSvg()
    assertTrue(svg.contains("<path d=\"M"))
    assertTrue(svg.contains("C"), "a circle should be emitted as cubic segments")
  }

  @Test
  fun `unresolved image reports a warning instead of being dropped silently`() {
    val image =
      ImageNode(
        id = ids.allocate(),
        url = "asset://logo.png",
        x = 0.0,
        y = 0.0,
        width = 10.0,
        height = 10.0,
      )
    val referenced = SvgRenderer(SvgOptions()).render(sceneOf(image))
    assertTrue(referenced.warnings.isEmpty())
    assertTrue(referenced.svg.contains("""xlink:href="asset://logo.png""""))

    val strict =
      SvgRenderer(SvgOptions(imagePolicy = SvgImagePolicy.REQUIRE_RESOLVED)).render(sceneOf(image))
    assertFalse(strict.svg.contains("<image"))
    assertEquals(1, strict.warnings.size)
    assertEquals(DiagnosticCodes.EXPORT_IMAGE_UNRESOLVED, strict.warnings.single().code)
  }

  @Test
  fun `invisible nodes are omitted`() {
    val hidden =
      RectNode(
        id = ids.allocate(),
        x = 0.0,
        y = 0.0,
        width = 10.0,
        height = 10.0,
        fill = Fill.of(SceneColor.Black),
        visible = false,
      )
    assertFalse(sceneOf(hidden).toSvg().contains("<rect"))
  }

  @Test
  fun `mark count matches the drawable nodes in the scene`() {
    for (scene in allSampleScenes()) {
      val svg = scene.toSvg()
      val drawable =
        scene
          .flatten()
          .map { it.node }
          .filter { it.visible && it.opacity > 0.0 && it !is GroupNode }
      val background = scene.background
      val backgroundRects = if (background != null && !background.isTransparent) 1 else 0
      // `<defs>` holds clip-path rectangles that are definitions, not marks.
      val body = svg.replace(Regex("""<defs>.*?</defs>""", RegexOption.DOT_MATCHES_ALL), "")
      val emitted =
        Regex("""<(rect|line|path|text|image)[ >]""").findAll(body).count() - backgroundRects
      assertEquals(drawable.size, emitted, "mark count mismatch for a sample scene")
    }
  }

  @Test
  fun `precision option controls coordinate output`() {
    val node =
      PathNode(
        id = ids.allocate(),
        path =
          PathData.build {
            moveTo(1.0 / 3.0, 0.0)
            lineTo(1.0, 1.0)
          },
        stroke = Stroke(paint = ScenePaint.Black),
      )
    assertTrue(sceneOf(node).toSvg(SvgOptions(precision = 2)).contains("M0.33,0"))
    assertTrue(sceneOf(node).toSvg(SvgOptions(precision = 5)).contains("M0.33333,0"))
  }

  @Test
  fun `compact output has no newlines between elements`() {
    val svg =
      sceneOf(RectNode(id = ids.allocate(), x = 0.0, y = 0.0, width = 1.0, height = 1.0))
        .toSvg(SvgOptions(pretty = false))
    assertFalse(svg.contains('\n'))
  }

  @Test
  fun `bar chart svg matches its golden`() {
    GoldenFiles.assertMatches("svg/bar-chart.svg", SampleScenes.barChart(textEngine).toSvg())
  }

  @Test
  fun `line chart svg matches its golden`() {
    GoldenFiles.assertMatches("svg/line-chart.svg", SampleScenes.lineChart(textEngine).toSvg())
  }

  @Test
  fun `area chart svg matches its golden`() {
    GoldenFiles.assertMatches("svg/area-chart.svg", SampleScenes.areaChart(textEngine).toSvg())
  }

  @Test
  fun `scatter plot svg matches its golden`() {
    GoldenFiles.assertMatches(
      "svg/scatter-plot.svg",
      SampleScenes.scatterPlot(textEngine, pointCount = 12).toSvg(),
    )
  }

  @Test
  fun `stacked bar chart svg matches its golden`() {
    GoldenFiles.assertMatches(
      "svg/stacked-bar-chart.svg",
      SampleScenes.stackedBarChart(textEngine).toSvg(),
    )
  }

  private fun allSampleScenes(): List<Scene> =
    listOf(
      SampleScenes.barChart(textEngine),
      SampleScenes.lineChart(textEngine),
      SampleScenes.areaChart(textEngine),
      SampleScenes.scatterPlot(textEngine, pointCount = 12),
      SampleScenes.stackedBarChart(textEngine),
    )
}
