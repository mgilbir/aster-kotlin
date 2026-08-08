package dev.aster.vega.runtime.compile

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.TextAlign
import dev.aster.vega.scene.TextBaseline
import dev.aster.vega.scene.TextNode
import dev.aster.vega.scene.flatten
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Chart titles, subtitles and axis titles.
 *
 * The positions are reference vectors read off upstream compiling the same specification, with the
 * text engine that reproduces upstream's canvas-free measurement. A title is placed against how far
 * the *drawing* reaches — including its axes and legends — so these tests are also the ones that
 * pin that measurement.
 */
class TitleTest {

  private fun compile(json: String) = SpecCompiler(VegaHeadlessTextEngine()).compileJson(json)

  private fun nodes(json: String): List<SceneNode> {
    val compiled = compile(json)
    assertTrue(
      compiled.diagnostics.none { it.severity >= DiagnosticSeverity.ERROR },
      "expected a clean compile; got ${compiled.diagnostics}",
    )
    return requireNotNull(compiled.scene).flatten().map { it.node }
  }

  private fun role(json: String, role: String): List<SceneNode> =
    nodes(json).filter { it.metadata.role == role }

  /**
   * A 200x100 chart with a left axis, which is what the reference vectors were measured against.
   *
   * The axis matters: it reaches 23 units left of the plotting area and 5 above it, and a title is
   * placed against that rather than against the plot.
   */
  private fun spec(title: String, axes: String = """{"orient": "left", "scale": "y"}""") =
    """
    {
      "width": 200, "height": 100, "padding": 0, "autosize": "none",
      "title": $title,
      "scales": [{"name": "y", "type": "linear", "domain": [0, 10], "range": "height"}],
      "axes": [$axes]
    }
    """
      .trimIndent()

  // ---- the chart title --------------------------------------------------------

  @Test
  fun `a bare string is a title`() {
    val title = role(spec(""""Just words""""), "title-text").single() as TextNode
    assertEquals("Just words", title.text)
    assertTrue(role(spec(""""Just words""""), "title-subtitle").isEmpty())
  }

  @Test
  fun `a title and subtitle are separate nodes, the subtitle below and smaller`() {
    val json = spec("""{"text": "Tt", "subtitle": "Ss"}""")
    val title = role(json, "title-text").single() as TextNode
    val subtitle = role(json, "title-subtitle").single() as TextNode
    assertEquals("Tt", title.text)
    assertEquals("Ss", subtitle.text)
    assertEquals(TitleDefaults.FONT_SIZE, title.layout.run.style.fontSize)
    assertEquals(TitleDefaults.SUBTITLE_FONT_SIZE, subtitle.layout.run.style.fontSize)
    assertEquals(TitleDefaults.FONT_WEIGHT, title.layout.run.style.fontWeight)
    assertEquals(TitleDefaults.SUBTITLE_FONT_WEIGHT, subtitle.layout.run.style.fontWeight)
    // The subtitle sits the title's own height plus subtitlePadding below it: 13 + 3.
    assertEquals(0.0, title.y, 1e-9)
    assertEquals(16.0, subtitle.y, 1e-9)
    assertEquals(TextBaseline.TOP, title.layout.run.baseline)
  }

  /**
   * Where a title group lands for each orientation and anchor, on the 200x100 chart above.
   *
   * All twelve read off upstream. Two are worth watching: a left-oriented title's anchor runs
   * bottom to top, so `start` is the *lower* edge; and a right-oriented one is placed a further
   * title-width out, because after its quarter turn its box extends backwards from the anchor.
   */
  @ParameterizedTest
  @CsvSource(
    "top,start,-23,-36",
    "top,middle,88.5,-36",
    "top,end,200,-36",
    "bottom,start,-23,109",
    "bottom,middle,88.5,109",
    "bottom,end,200,109",
    "left,start,-54,105",
    "left,middle,-54,50",
    "left,end,-54,-5",
    "right,start,231,-5",
    "right,middle,231,50",
    "right,end,231,105",
  )
  fun `a title lands where upstream puts it`(
    orient: String,
    anchor: String,
    x: Double,
    y: Double,
  ) {
    val json =
      spec("""{"text": "Tt", "subtitle": "Ss", "orient": "$orient", "anchor": "$anchor"}""")
    val group = nodes(json).filterIsInstance<GroupNode>().single { it.metadata.role == "title" }
    assertEquals(x, group.transform.e, 1e-9, "$orient/$anchor x")
    assertEquals(y, group.transform.f, 1e-9, "$orient/$anchor y")
  }

  @Test
  fun `the anchor also decides how the text is aligned`() {
    fun align(anchor: String) =
      (role(spec("""{"text": "Tt", "anchor": "$anchor"}"""), "title-text").single() as TextNode)
        .layout
        .run
        .align
    assertEquals(TextAlign.LEFT, align("start"))
    assertEquals(TextAlign.CENTER, align("middle"))
    assertEquals(TextAlign.RIGHT, align("end"))
  }

  @Test
  fun `a side title turns a quarter turn`() {
    fun angle(orient: String) =
      (role(spec("""{"text": "Tt", "orient": "$orient"}"""), "title-text").single() as TextNode)
        .angleDegrees
    assertEquals(0.0, angle("top"))
    assertEquals(0.0, angle("bottom"))
    assertEquals(-90.0, angle("left"))
    assertEquals(90.0, angle("right"))
  }

  @Test
  fun `a title is placed against the whole drawing, not the plotting area`() {
    // With a left axis the drawing starts 23 units left of the plot, so a centred title sits left
    // of
    // the plot's own centre. `frame: "group"` opts out and centres on the plot instead.
    val drawing =
      nodes(spec("""{"text": "Tt"}""")).filterIsInstance<GroupNode>().single {
        it.metadata.role == "title"
      }
    val framed =
      nodes(spec("""{"text": "Tt", "frame": "group"}""")).filterIsInstance<GroupNode>().single {
        it.metadata.role == "title"
      }
    assertEquals(88.5, drawing.transform.e, 1e-9)
    assertEquals(100.0, framed.transform.e, 1e-9)
  }

  @Test
  fun `a title with no text is rejected`() {
    val compiled = compile(spec("""{"subtitle": "Only a subtitle"}"""))
    assertTrue(
      compiled.diagnostics.any {
        it.severity >= DiagnosticSeverity.ERROR && it.message.contains("needs a 'text'")
      },
      compiled.diagnostics.toString(),
    )
  }

  @Test
  fun `unimplemented title properties are reported by name`() {
    val compiled = compile(spec("""{"text": "Tt", "align": "left", "angle": 45, "limit": 20}"""))
    val messages = compiled.diagnostics.map { it.message }
    // `dx` was on this list and is implemented; it moves the whole surface with it.
    for (name in listOf("align", "angle", "limit")) {
      assertTrue(messages.any { it.contains("'$name'") }, "$name not reported in $messages")
    }
  }

  // ---- axis titles ------------------------------------------------------------

  private fun axisSpec(axes: String) =
    """
    {
      "width": 200, "height": 100, "padding": 0, "autosize": "none",
      "scales": [
        {"name": "x", "type": "linear", "domain": [0, 10], "range": "width"},
        {"name": "y", "type": "linear", "domain": [0, 10], "range": "height"}
      ],
      "axes": [$axes]
    }
    """
      .trimIndent()

  /**
   * Where an axis title sits, relative to its own axis group.
   *
   * Read off upstream. The offset is however far the ticks and labels reach plus a padding of 4,
   * and the position along the axis is the midpoint of the scale's *range*. A vertical axis turns
   * its title a quarter turn and baselines it at the bottom in both directions, because after the
   * rotation the baseline runs along the axis rather than across it.
   */
  @ParameterizedTest
  @CsvSource(
    "bottom,100,21,0",
    "top,100,-21,0",
    "left,-27,50,-90",
    "right,27,50,90",
  )
  fun `an axis title clears its ticks and labels`(
    orient: String,
    x: Double,
    y: Double,
    angle: Double,
  ) {
    val scale = if (orient == "left" || orient == "right") "y" else "x"
    val json = axisSpec("""{"orient": "$orient", "scale": "$scale", "title": "T"}""")
    val title = role(json, "axis-title").single() as TextNode
    assertEquals(x, title.x, 1e-9, "$orient x")
    assertEquals(y, title.y, 1e-9, "$orient y")
    assertEquals(angle, title.angleDegrees, 1e-9, "$orient angle")
    assertEquals(
      if (orient == "bottom") TextBaseline.TOP else TextBaseline.BOTTOM,
      title.layout.run.baseline,
      orient,
    )
  }

  @Test
  fun `a gridline does not push the axis title away`() {
    // Upstream measures an axis by its ticks and labels only, so turning a grid on — which draws
    // right across the chart — must not move the title or resize the surface.
    val without =
      role(axisSpec("""{"orient": "left", "scale": "y", "title": "T"}"""), "axis-title").single()
    val with =
      role(
          axisSpec("""{"orient": "left", "scale": "y", "title": "T", "grid": true}"""),
          "axis-title",
        )
        .single()
    assertEquals((without as TextNode).x, (with as TextNode).x, 1e-9)
  }

  @Test
  fun `titlePadding moves the title further out`() {
    val far =
      role(
          axisSpec("""{"orient": "bottom", "scale": "x", "title": "T", "titlePadding": 20}"""),
          "axis-title",
        )
        .single() as TextNode
    // The default padding of 4 puts it at 21, so 20 puts it 16 further out.
    assertEquals(37.0, far.y, 1e-9)
  }

  @Test
  fun `an axis title anchors along the scale's range`() {
    fun x(anchor: String) =
      (role(
            axisSpec(
              """{"orient": "bottom", "scale": "x", "title": "T", "titleAnchor": "$anchor"}"""
            ),
            "axis-title",
          )
          .single() as TextNode)
        .x
    assertEquals(0.0, x("start"), 1e-9)
    assertEquals(100.0, x("middle"), 1e-9)
    assertEquals(200.0, x("end"), 1e-9)
  }

  @Test
  fun `an axis without a title produces no title node`() {
    assertTrue(role(axisSpec("""{"orient": "bottom", "scale": "x"}"""), "axis-title").isEmpty())
  }
}
