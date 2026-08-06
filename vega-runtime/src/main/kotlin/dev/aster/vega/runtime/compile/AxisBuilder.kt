package dev.aster.vega.runtime.compile

import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.spec.AxisSpec
import dev.aster.vega.model.spec.Orient
import dev.aster.vega.runtime.scale.BandScale
import dev.aster.vega.runtime.scale.LinearScale
import dev.aster.vega.runtime.scale.PointScale
import dev.aster.vega.runtime.scale.VegaScale
import dev.aster.vega.scene.Fill
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.NodeMetadata
import dev.aster.vega.scene.RuleNode
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.SceneNodeIdAllocator
import dev.aster.vega.scene.ScenePaint
import dev.aster.vega.scene.Stroke
import dev.aster.vega.scene.TextAlign
import dev.aster.vega.scene.TextBaseline
import dev.aster.vega.scene.TextEngine
import dev.aster.vega.scene.TextNode
import dev.aster.vega.scene.TextRun
import dev.aster.vega.scene.TextStyle
import dev.aster.vega.scene.Transform2D

/**
 * Generates axis scene nodes: ticks, labels, gridlines and the domain line.
 *
 * Geometry follows what upstream Vega actually emits, established by inspecting its scenegraph:
 * - the axis group is translated by half a pixel so 1-pixel lines land on pixel centres
 * - a tick's coordinate is its scale position rounded to a whole pixel in that translated space
 * - a band scale's positions are the band centres, shifted back half a pixel, so labels centre on
 *   the band while ticks stay crisp
 *
 * Titles are not generated; the parser reports that.
 */
public class AxisBuilder(
  private val scales: Map<String, VegaScale>,
  private val ids: SceneNodeIdAllocator,
  private val textEngine: TextEngine,
  private val diagnostics: DiagnosticCollector,
  /** Resolves axis properties that a specification supplied as signals. */
  private val numbers: NumberResolver,
) {

  /** One tick's label text and its position along the axis. */
  private data class Tick(val label: String, val position: Double)

  public fun build(spec: AxisSpec, plot: PlotSize): SceneNode? {
    val scale = scales[spec.scale]
    if (scale == null) {
      diagnostics.error(
        DiagnosticCodes.SCALE_UNSUPPORTED_TYPE,
        "Axis refers to scale '${spec.scale}', which was not built; the axis was skipped",
        operator = spec.scale,
      )
      return null
    }

    val ticks = ticksFor(scale, spec)
    if (ticks == null) {
      diagnostics.error(
        DiagnosticCodes.SCALE_UNSUPPORTED_TYPE,
        "Cannot generate ticks for scale '${spec.scale}'; the axis was skipped",
        operator = spec.scale,
      )
      return null
    }

    val tickSize = numbers.resolve(spec.tickSize, spec.scale) ?: AxisDefaults.TICK_SIZE
    val labelPadding = numbers.resolve(spec.labelPadding, spec.scale) ?: AxisDefaults.LABEL_PADDING
    val labelOffset = tickSize + labelPadding
    val fontSize = numbers.resolve(spec.labelFontSize, spec.scale) ?: AxisDefaults.LABEL_FONT_SIZE
    val labelStyle = TextStyle(fontFamily = AxisDefaults.LABEL_FONT_FAMILY, fontSize = fontSize)

    val children = mutableListOf<SceneNode>()
    val tickStroke =
      Stroke(paint = ScenePaint.Solid(AxisDefaults.tickColor), width = AxisDefaults.TICK_WIDTH)
    val gridStroke =
      Stroke(paint = ScenePaint.Solid(AxisDefaults.gridColor), width = AxisDefaults.TICK_WIDTH)

    // Gridlines first so they sit under the ticks, matching Vega's ordering.
    if (spec.grid) {
      val gridMeta = NodeMetadata(role = "axis-grid")
      for (tick in ticks) {
        val at = AxisDefaults.crispRound(tick.position)
        children +=
          when (spec.orient) {
            Orient.BOTTOM,
            Orient.TOP ->
              RuleNode(ids.allocate(), at, 0.0, at, -plot.height, gridStroke, metadata = gridMeta)
            Orient.LEFT,
            Orient.RIGHT ->
              RuleNode(ids.allocate(), 0.0, at, plot.width, at, gridStroke, metadata = gridMeta)
          }
      }
    }

    if (spec.ticks) {
      val tickMeta = NodeMetadata(role = "axis-tick")
      for (tick in ticks) {
        val at = AxisDefaults.crispRound(tick.position)
        children +=
          when (spec.orient) {
            Orient.BOTTOM ->
              RuleNode(ids.allocate(), at, 0.0, at, tickSize, tickStroke, metadata = tickMeta)
            Orient.TOP ->
              RuleNode(ids.allocate(), at, 0.0, at, -tickSize, tickStroke, metadata = tickMeta)
            Orient.LEFT ->
              RuleNode(ids.allocate(), 0.0, at, -tickSize, at, tickStroke, metadata = tickMeta)
            Orient.RIGHT ->
              RuleNode(ids.allocate(), 0.0, at, tickSize, at, tickStroke, metadata = tickMeta)
          }
      }
    }

    if (spec.labels) {
      for (tick in ticks) {
        val run =
          TextRun(
            text = tick.label,
            style = labelStyle,
            align = labelAlign(spec.orient),
            baseline = labelBaseline(spec.orient),
          )
        val layout = textEngine.layout(run)
        val (x, y) =
          when (spec.orient) {
            Orient.BOTTOM -> tick.position to labelOffset
            Orient.TOP -> tick.position to -labelOffset
            Orient.LEFT -> -labelOffset to tick.position
            Orient.RIGHT -> labelOffset to tick.position
          }
        children +=
          TextNode(
            id = ids.allocate(),
            x = x,
            y = y,
            layout = layout,
            fill = Fill.of(AxisDefaults.labelColor),
            metadata = NodeMetadata(role = "axis-label"),
          )
      }
    }

    if (spec.domainLine) {
      val domainStroke =
        Stroke(paint = ScenePaint.Solid(AxisDefaults.domainColor), width = AxisDefaults.TICK_WIDTH)
      val domainMeta = NodeMetadata(role = "axis-domain")
      children +=
        when (spec.orient) {
          Orient.BOTTOM,
          Orient.TOP ->
            RuleNode(ids.allocate(), 0.0, 0.0, plot.width, 0.0, domainStroke, metadata = domainMeta)
          Orient.LEFT,
          Orient.RIGHT ->
            RuleNode(
              ids.allocate(),
              0.0,
              plot.height,
              0.0,
              0.0,
              domainStroke,
              metadata = domainMeta,
            )
        }
    }

    return GroupNode(
      id = ids.allocate(),
      children = children,
      transform = groupTransform(spec, plot),
      metadata = NodeMetadata(role = "axis", markName = spec.scale),
    )
  }

  /**
   * Where the axis group sits.
   *
   * The half-pixel offset is Vega's, not a rounding artefact of ours: it makes 1-pixel ticks land
   * on pixel centres instead of straddling two pixels.
   */
  private fun groupTransform(spec: AxisSpec, plot: PlotSize): Transform2D {
    val offset = numbers.resolve(spec.offset, spec.scale) ?: 0.0
    return when (spec.orient) {
      Orient.BOTTOM ->
        Transform2D.translate(
          AxisDefaults.CRISP_OFFSET,
          plot.height + AxisDefaults.CRISP_OFFSET + offset,
        )
      Orient.TOP ->
        Transform2D.translate(AxisDefaults.CRISP_OFFSET, AxisDefaults.CRISP_OFFSET - offset)
      Orient.LEFT ->
        Transform2D.translate(AxisDefaults.CRISP_OFFSET - offset, AxisDefaults.CRISP_OFFSET)
      Orient.RIGHT ->
        Transform2D.translate(
          plot.width + AxisDefaults.CRISP_OFFSET + offset,
          AxisDefaults.CRISP_OFFSET,
        )
    }
  }

  private fun labelAlign(orient: Orient): TextAlign =
    when (orient) {
      Orient.BOTTOM,
      Orient.TOP -> TextAlign.CENTER
      Orient.LEFT -> TextAlign.RIGHT
      Orient.RIGHT -> TextAlign.LEFT
    }

  private fun labelBaseline(orient: Orient): TextBaseline =
    when (orient) {
      Orient.BOTTOM -> TextBaseline.TOP
      Orient.TOP -> TextBaseline.BOTTOM
      Orient.LEFT,
      Orient.RIGHT -> TextBaseline.MIDDLE
    }

  /**
   * Tick values and positions for a scale.
   *
   * A band or point scale ticks at every domain entry; a linear scale ticks at d3's chosen round
   * values. Band positions are the band centres shifted back half a pixel, which is what upstream
   * emits — verified by comparing against Vega's own axis items for band, point and linear scales.
   */
  private fun ticksFor(scale: VegaScale, spec: AxisSpec): List<Tick>? =
    when (scale) {
      is BandScale ->
        scale.domain.zip(scale.centers()).map { (label, centre) ->
          Tick(label, centre - AxisDefaults.CRISP_OFFSET)
        }
      is PointScale ->
        scale.domain.map { label ->
          Tick(label, scale.position(VegaValue.Str(label)))
        }
      is LinearScale -> {
        val count =
          numbers.resolveInt(spec.tickCount, spec.scale) ?: AxisDefaults.DEFAULT_TICK_COUNT
        scale.ticks(count).map { value ->
          Tick(scale.formatTick(value, count), scale.apply(value))
        }
      }
      else -> null
    }
}
