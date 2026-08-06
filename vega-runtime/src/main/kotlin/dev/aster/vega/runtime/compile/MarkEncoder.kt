package dev.aster.vega.runtime.compile

import dev.aster.vega.expression.CachingExpressionCompiler
import dev.aster.vega.expression.ExpressionCompiler
import dev.aster.vega.expression.ExpressionEvaluationException
import dev.aster.vega.expression.ExpressionResult
import dev.aster.vega.expression.JsSemantics
import dev.aster.vega.expression.VegaExpressionCompiler
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.asString
import dev.aster.vega.model.field
import dev.aster.vega.model.spec.ChannelValue
import dev.aster.vega.model.spec.EncodeEntry
import dev.aster.vega.model.spec.MarkSpec
import dev.aster.vega.model.spec.MarkType
import dev.aster.vega.runtime.scale.PositionScale
import dev.aster.vega.runtime.scale.VegaScale
import dev.aster.vega.scene.AccessibilityDescriptor
import dev.aster.vega.scene.Fill
import dev.aster.vega.scene.NodeMetadata
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.SceneColor
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.SceneNodeIdAllocator
import dev.aster.vega.scene.ScenePaint
import dev.aster.vega.scene.Stroke

/**
 * Turns a mark specification plus its data into scene nodes.
 *
 * Only the `rect` mark is implemented. Every other type reports `VEGA_TRANSFORM_NOT_IMPLEMENTED`
 * and contributes no nodes, so a chart that needs one is visibly missing it rather than silently
 * short.
 *
 * Channel resolution follows Vega: `enter` provides the base and `update` overrides it, a scaled
 * channel maps its field through the named scale, and `band` adds a fraction of the scale's
 * bandwidth — which is how `{"scale": "x", "band": 1}` expresses a bar's full-band width.
 */
public class MarkEncoder(
  private val scales: Map<String, VegaScale>,
  private val ids: SceneNodeIdAllocator,
  private val diagnostics: DiagnosticCollector,
  /** Signals and datasets expressions can read. */
  private val scope: SignalScope = SignalScope(emptyMap(), emptyMap()),
  private val expressions: ExpressionCompiler = CachingExpressionCompiler(VegaExpressionCompiler()),
) {

  public fun encode(spec: MarkSpec, data: List<VegaValue>): List<SceneNode> =
    when (spec.type) {
      MarkType.RECT -> data.mapIndexedNotNull { index, datum -> rect(spec, datum, index) }
      else -> {
        diagnostics.error(
          DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED,
          "The '${spec.type.name.lowercase()}' mark encoder is not implemented; " +
            "${data.size} data values produced no marks",
          operator = spec.name ?: spec.type.name.lowercase(),
        )
        emptyList()
      }
    }

  private fun rect(spec: MarkSpec, datum: VegaValue, index: Int): SceneNode? {
    val channels = spec.encode.effective
    if (channels.isEmpty()) return null

    // Vega accepts x/x2, x/width, or x2/width for each axis; resolve whichever pair is present.
    val horizontal = resolveSpan(channels, datum, "x", "x2", "width", index, spec)
    val vertical = resolveSpan(channels, datum, "y", "y2", "height", index, spec)
    if (horizontal == null || vertical == null) return null

    val fill = paint(channels["fill"], datum, "fill", spec)
    val fillOpacity = number(channels["fillOpacity"], datum) ?: 1.0
    val strokeColor = paint(channels["stroke"], datum, "stroke", spec)
    val strokeWidth = number(channels["strokeWidth"], datum) ?: 1.0
    val opacity = number(channels["opacity"], datum) ?: 1.0
    val cornerRadius = number(channels["cornerRadius"], datum) ?: 0.0

    return RectNode(
      id = ids.allocate(),
      x = horizontal.start,
      y = vertical.start,
      width = horizontal.extent,
      height = vertical.extent,
      cornerRadius = cornerRadius,
      fill = fill?.let { Fill(ScenePaint.Solid(it), fillOpacity) },
      stroke = strokeColor?.let { Stroke(paint = ScenePaint.Solid(it), width = strokeWidth) },
      opacity = opacity,
      metadata =
        NodeMetadata(
          markName = spec.name,
          role = "mark",
          datumIndex = index,
          interactive = spec.interactive,
          tooltip = datum,
          accessibility = describe(datum, channels),
        ),
    )
  }

  /** A resolved position and extent along one axis. */
  private data class Span(val start: Double, val extent: Double)

  /**
   * Resolves one axis of a rect from whichever channel pair the specification used.
   *
   * Vega allows `x` + `x2`, `x` + `width`, or `x2` + `width`; a lone `x` with a band scale takes
   * its extent from the band. Anything else cannot be positioned and is reported.
   */
  private fun resolveSpan(
    channels: EncodeEntry,
    datum: VegaValue,
    startChannel: String,
    endChannel: String,
    sizeChannel: String,
    index: Int,
    spec: MarkSpec,
  ): Span? {
    val start = position(channels[startChannel], datum)
    val end = position(channels[endChannel], datum)
    val size = position(channels[sizeChannel], datum)

    return when {
      start != null && end != null -> Span(minOf(start, end), kotlin.math.abs(end - start))
      start != null && size != null -> Span(start, size)
      end != null && size != null -> Span(end - size, size)
      start != null -> Span(start, 0.0)
      end != null -> Span(end, 0.0)
      else -> {
        diagnostics.error(
          DiagnosticCodes.PARSE_MISSING_PROPERTY,
          "Rect mark '${spec.name ?: "(unnamed)"}' datum $index has no " +
            "$startChannel, $endChannel or $sizeChannel channel",
          operator = spec.name,
        )
        null
      }
    }
  }

  /** Resolves a positional channel to a number, applying its scale and band offset. */
  private fun position(channel: ChannelValue?, datum: VegaValue): Double? =
    when (channel) {
      null -> null
      is ChannelValue.Constant -> channel.value.asDouble().takeIf { !it.isNaN() }
      is ChannelValue.Field -> datum.field(channel.path).asDouble().takeIf { !it.isNaN() }
      is ChannelValue.Signal ->
        evaluateExpression(channel.expression, datum)?.asDouble()?.takeIf { !it.isNaN() }
      is ChannelValue.Conditional -> position(selectRule(channel, datum), datum)
      is ChannelValue.Scaled -> scaledPosition(channel, datum)
    }

  /**
   * Evaluates an expression against a datum, reporting a failure once rather than per datum.
   *
   * Returns `null` on failure so the caller can leave the channel unset, which is what Vega does
   * with an expression that throws.
   */
  private fun evaluateExpression(source: String, datum: VegaValue): VegaValue? =
    when (val compiled = expressions.compile(source)) {
      is ExpressionResult.Failed -> {
        reportOnce(source, compiled.diagnostic)
        null
      }
      is ExpressionResult.Compiled ->
        try {
          compiled.expression.evaluate(scope.withDatum(datum))
        } catch (e: ExpressionEvaluationException) {
          reportOnce(source, e.diagnostic)
          null
        }
    }

  /**
   * An expression that fails fails for every datum, so report it once.
   *
   * Without this a 10,000-row dataset would produce 10,000 identical diagnostics and bury
   * everything else.
   */
  private fun reportOnce(source: String, diagnostic: dev.aster.vega.model.VegaDiagnostic) {
    if (reported.add(source)) diagnostics.add(diagnostic)
  }

  private val reported = mutableSetOf<String>()

  /**
   * Picks the first production rule whose `test` passes.
   *
   * An untested rule always passes, so a trailing one is the default. If every test fails and there
   * is no default, the channel is left unset — as upstream does.
   */
  private fun selectRule(channel: ChannelValue.Conditional, datum: VegaValue): ChannelValue? {
    for (rule in channel.rules) {
      val test = rule.test ?: return rule.production
      val result = evaluateExpression(test, datum) ?: continue
      if (JsSemantics.truthy(result)) return rule.production
    }
    return null
  }

  private fun scaledPosition(channel: ChannelValue.Scaled, datum: VegaValue): Double? {
    val scale = scales[channel.scale]
    if (scale == null) {
      diagnostics.error(
        DiagnosticCodes.SCALE_UNSUPPORTED_TYPE,
        "Encoding refers to scale '${channel.scale}', which was not built",
        operator = channel.scale,
      )
      return null
    }
    if (scale !is PositionScale) {
      diagnostics.error(
        DiagnosticCodes.SCALE_UNSUPPORTED_TYPE,
        "Scale '${channel.scale}' has no numeric range and cannot position a mark",
        operator = channel.scale,
      )
      return null
    }

    // `{"scale": "x", "band": 1}` with no field means "a whole band", i.e. the bar's width.
    val band = channel.band
    if (channel.field == null && channel.value == null && band != null) {
      return scale.bandwidth * band + (channel.offset ?: 0.0)
    }

    val fieldPath = channel.field
    val constant = channel.value
    val input =
      when {
        fieldPath != null -> datum.field(fieldPath)
        constant != null -> constant
        else -> return null
      }
    val base = scale.position(input)
    if (base.isNaN()) return null
    val bandOffset = if (band != null) scale.bandwidth * band else 0.0
    return base + bandOffset + (channel.offset ?: 0.0)
  }

  private fun number(channel: ChannelValue?, datum: VegaValue): Double? =
    when (channel) {
      null -> null
      is ChannelValue.Constant -> channel.value.asDouble().takeIf { !it.isNaN() }
      is ChannelValue.Field -> datum.field(channel.path).asDouble().takeIf { !it.isNaN() }
      is ChannelValue.Scaled -> scaledPosition(channel, datum)
      is ChannelValue.Signal ->
        evaluateExpression(channel.expression, datum)?.asDouble()?.takeIf { !it.isNaN() }
      is ChannelValue.Conditional -> number(selectRule(channel, datum), datum)
    }

  private fun paint(
    channel: ChannelValue?,
    datum: VegaValue,
    channelName: String,
    spec: MarkSpec,
  ): SceneColor? {
    val text =
      when (channel) {
        null -> return null
        is ChannelValue.Constant -> channel.value.asString()
        is ChannelValue.Field -> datum.field(channel.path).asString()
        is ChannelValue.Scaled -> {
          val scale = scales[channel.scale]
          if (scale == null) {
            diagnostics.error(
              DiagnosticCodes.SCALE_UNSUPPORTED_TYPE,
              "Colour channel '$channelName' refers to scale '${channel.scale}', which was not built",
              operator = channel.scale,
            )
            return null
          }
          val input = channel.field?.let { datum.field(it) } ?: channel.value ?: return null
          scale.scale(input).asString()
        }
        is ChannelValue.Signal ->
          evaluateExpression(channel.expression, datum)?.asString() ?: return null
        is ChannelValue.Conditional -> {
          val selected = selectRule(channel, datum) ?: return null
          return paint(selected, datum, channelName, spec)
        }
      }
    val colour = SceneColor.parse(text)
    if (colour == null) {
      diagnostics.warn(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "Could not parse colour '$text' for channel '$channelName'",
        operator = spec.name,
      )
    }
    return colour
  }

  /**
   * Builds an accessibility label from the encoded channels.
   *
   * Uses the fields the mark actually encodes, so the description follows the chart rather than
   * guessing at which datum properties matter.
   */
  private fun describe(datum: VegaValue, channels: EncodeEntry): AccessibilityDescriptor? {
    val labelField =
      channels.values.filterIsInstance<ChannelValue.Scaled>().firstNotNullOfOrNull { it.field }
    val valueField =
      channels.values.filterIsInstance<ChannelValue.Scaled>().mapNotNull { it.field }.lastOrNull()
    if (labelField == null) return null
    val label = datum.field(labelField).asString()
    val value = valueField?.takeIf { it != labelField }?.let { datum.field(it).asString() }
    return AccessibilityDescriptor(
      label = label,
      value = value,
      role = "graphics-symbol",
      focusable = true,
    )
  }
}
