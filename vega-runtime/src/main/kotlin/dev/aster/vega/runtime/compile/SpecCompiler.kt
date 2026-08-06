package dev.aster.vega.runtime.compile

import dev.aster.vega.dataflow.transform.TransformContext
import dev.aster.vega.dataflow.transform.TransformPipeline
import dev.aster.vega.expression.CachingExpressionCompiler
import dev.aster.vega.expression.ExpressionCompiler
import dev.aster.vega.expression.ExpressionScope
import dev.aster.vega.expression.VegaExpressionCompiler
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaDiagnostic
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.spec.AutosizeType
import dev.aster.vega.model.spec.DataSpec
import dev.aster.vega.model.spec.MarkSpec
import dev.aster.vega.model.spec.SpecParser
import dev.aster.vega.model.spec.VegaSpec
import dev.aster.vega.runtime.scale.VegaScale
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.MetricTextEngine
import dev.aster.vega.scene.NodeMetadata
import dev.aster.vega.scene.RectD
import dev.aster.vega.scene.Scene
import dev.aster.vega.scene.SceneColor
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.SceneNodeIdAllocator
import dev.aster.vega.scene.TextEngine
import dev.aster.vega.scene.Transform2D

/** A compiled specification: the scene, the scales it built, and everything it could not honour. */
public data class CompiledSpec(
  val scene: Scene?,
  val scales: Map<String, VegaScale>,
  /** Resolved signal values, including the implicit `width`, `height` and `padding`. */
  val signals: SignalScope,
  val diagnostics: List<VegaDiagnostic>,
) {
  public val isUsable: Boolean
    get() = scene != null
}

/**
 * Compiles a parsed Vega specification into a scene.
 *
 * This is the thin vertical slice through the pipeline: data, scales, mark encoding, axes and
 * layout. It executes the subset the runtime supports and reports the rest, which is what lets the
 * differential harness compare against upstream on a real specification instead of on hand-authored
 * scenes.
 *
 * Not implemented, each reported rather than approximated: signals and expressions, data
 * transforms, legends, titles, faceting, and every mark type except `rect`.
 *
 * @param textEngine measures axis labels. Pass the Android engine to get the scene the device will
 *   draw, or the default deterministic engine for JVM comparisons.
 */
public class SpecCompiler(private val textEngine: TextEngine = MetricTextEngine()) {

  public fun compileJson(json: String): CompiledSpec {
    val parsed = SpecParser().parseJson(json)
    val spec =
      parsed.spec ?: return CompiledSpec(null, emptyMap(), EMPTY_SIGNALS, parsed.diagnostics)
    val compiled = compile(spec)
    // Parse diagnostics come first so a reader sees problems in specification order.
    return compiled.copy(diagnostics = parsed.diagnostics + compiled.diagnostics)
  }

  public fun compile(spec: VegaSpec): CompiledSpec {
    val diagnostics = DiagnosticCollector()
    val ids = SceneNodeIdAllocator()

    val width = spec.width ?: DEFAULT_WIDTH
    val height = spec.height ?: DEFAULT_HEIGHT
    if (spec.width == null || spec.height == null) {
      diagnostics.warn(
        DiagnosticCodes.PARSE_MISSING_PROPERTY,
        "Specification has no width or height; using ${DEFAULT_WIDTH}x$DEFAULT_HEIGHT",
      )
    }
    val plot = PlotSize(width, height)

    // Vega exposes width, height and padding as implicit signals, so expressions can size things
    // relative to the chart. Verified: a signal with `update: "width/2"` resolves without declaring
    // width itself.
    val implicitSignals =
      mapOf(
        "width" to VegaValue.Num(width),
        "height" to VegaValue.Num(height),
        "padding" to
          VegaValue.Obj(
            linkedMapOf(
              "left" to VegaValue.Num(spec.padding.left),
              "top" to VegaValue.Num(spec.padding.top),
              "right" to VegaValue.Num(spec.padding.right),
              "bottom" to VegaValue.Num(spec.padding.bottom),
            )
          ),
      )
    val expressions = CachingExpressionCompiler(VegaExpressionCompiler())

    // Data first, because a signal may read a dataset and a transform may publish a signal. A
    // single
    // ordered pass is enough for a static compile: transforms see the signals resolved before them,
    // and
    // signals see the datasets resolved before them. A specification that crosses those in both
    // directions needs the full dataflow, and is reported rather than silently mis-ordered.
    val transformSignals = LinkedHashMap<String, VegaValue>(implicitSignals)
    val datasets = resolveData(spec.data, diagnostics, expressions, transformSignals)
    val signals =
      SignalResolver(diagnostics, expressions).resolve(spec.signals, datasets, transformSignals)

    val numbers = NumberResolver(expressions, signals, diagnostics)
    val scales = ScaleResolver(datasets, plot, diagnostics, numbers).resolve(spec.scales)

    val axisBuilder = AxisBuilder(scales, ids, textEngine, diagnostics, numbers)
    val markEncoder = MarkEncoder(scales, ids, diagnostics, signals, expressions)

    val children = mutableListOf<SceneNode>()
    // Vega draws axes below marks unless an axis opts into a higher zindex.
    val (underlayAxes, overlayAxes) = spec.axes.partition { it.zindex <= 0 }
    underlayAxes.forEach { axis -> axisBuilder.build(axis, plot)?.let { children += it } }
    for (mark in spec.marks) {
      children += encodeMark(mark, datasets, markEncoder, diagnostics)
    }
    overlayAxes.forEach { axis -> axisBuilder.build(axis, plot)?.let { children += it } }

    val content =
      GroupNode(
        id = ids.allocate(),
        children = children,
        metadata = NodeMetadata(role = "frame", markName = "root"),
      )

    val scene = layout(spec, content, plot, ids, diagnostics)
    return CompiledSpec(scene, scales, signals, diagnostics.diagnostics)
  }

  private fun encodeMark(
    mark: MarkSpec,
    datasets: Map<String, List<VegaValue>>,
    encoder: MarkEncoder,
    diagnostics: DiagnosticCollector,
  ): List<SceneNode> {
    val dataName = mark.from?.data
    val data =
      when {
        dataName == null -> listOf(VegaValue.EmptyObject) // a mark with no data draws once
        datasets.containsKey(dataName) -> datasets.getValue(dataName)
        else -> {
          diagnostics.error(
            DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
            "Mark refers to unknown dataset '$dataName'",
            operator = mark.name,
          )
          emptyList()
        }
      }
    return encoder.encode(mark, data)
  }

  /**
   * Resolves datasets to plain value lists.
   *
   * Transforms are not implemented, so a dataset that declares any is passed through unchanged with
   * a diagnostic naming the operators that were skipped — the chart then shows untransformed data,
   * which is wrong, but visibly and explicably so.
   */
  private fun resolveData(
    specs: List<DataSpec>,
    diagnostics: DiagnosticCollector,
    expressions: ExpressionCompiler,
    signals: MutableMap<String, VegaValue>,
  ): Map<String, List<VegaValue>> {
    val result = LinkedHashMap<String, List<VegaValue>>(specs.size)
    val pipeline = TransformPipeline()

    for (spec in specs) {
      var values = spec.values ?: emptyList()
      if (spec.source != null) {
        val upstream = result[spec.source]
        if (upstream == null) {
          diagnostics.error(
            DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
            "Dataset '${spec.name}' sources from unknown dataset '${spec.source}'",
            operator = spec.name,
          )
        } else {
          values = upstream
        }
      }
      if (spec.transform.isNotEmpty()) {
        val context = CompileTransformContext(diagnostics, expressions, signals, result, spec.name)
        values = pipeline.run(values, spec.transform, context)
      }
      result[spec.name] = values
    }
    return result
  }

  /**
   * Transform context for one dataset.
   *
   * Signals written by a transform — `extent` publishing a range, for instance — go into the shared
   * map, so a later dataset or a signal definition can read them.
   */
  private class CompileTransformContext(
    override val diagnostics: DiagnosticCollector,
    override val expressions: ExpressionCompiler,
    private val signals: MutableMap<String, VegaValue>,
    private val datasets: Map<String, List<VegaValue>>,
    private val datasetName: String,
  ) : TransformContext {

    override val scope: ExpressionScope = scopeFor(VegaValue.Null)

    override fun setSignal(name: String, value: VegaValue) {
      signals[name] = value
    }

    override fun scopeFor(datum: VegaValue): ExpressionScope = SignalScope(signals, datasets, datum)
  }

  /**
   * Places the content group and sizes the scene, implementing `autosize`.
   *
   * `pad`, Vega's default, grows the surface so the content plus its overflow fits inside the
   * padding: axis labels hang outside the plotting area, so the surface ends up larger than
   * `width`/`height`. Verified against upstream, which renders the 344x196 bar fixture into a
   * 385x228 surface because the y-axis labels extend 31 units to the left.
   */
  private fun layout(
    spec: VegaSpec,
    content: GroupNode,
    plot: PlotSize,
    ids: SceneNodeIdAllocator,
    diagnostics: DiagnosticCollector,
  ): Scene {
    val padding = spec.padding
    val background = spec.background?.let { SceneColor.parse(it) }
    if (spec.background != null && background == null) {
      diagnostics.warn(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "Could not parse background colour '${spec.background}'",
      )
    }

    return when (spec.autosize.type) {
      AutosizeType.PAD -> {
        // Content bounds include everything drawn, so overflow to the left or above shows up as a
        // negative edge and becomes extra translation.
        val bounds =
          if (content.bounds.isEmpty) RectD(0.0, 0.0, plot.width, plot.height) else content.bounds
        val overflowLeft = maxOf(0.0, -bounds.left)
        val overflowTop = maxOf(0.0, -bounds.top)
        val overflowRight = maxOf(0.0, bounds.right - plot.width)
        val overflowBottom = maxOf(0.0, bounds.bottom - plot.height)
        Scene(
          width = padding.left + overflowLeft + plot.width + overflowRight + padding.right,
          height = padding.top + overflowTop + plot.height + overflowBottom + padding.bottom,
          background = background,
          root =
            GroupNode(
              id = ids.allocate(),
              children = listOf(content),
              transform =
                Transform2D.translate(padding.left + overflowLeft, padding.top + overflowTop),
              metadata = NodeMetadata(role = "root"),
            ),
          revision = 1L,
        )
      }
      AutosizeType.NONE ->
        Scene(
          width = plot.width,
          height = plot.height,
          background = background,
          root =
            GroupNode(
              id = ids.allocate(),
              children = listOf(content),
              metadata = NodeMetadata(role = "root"),
            ),
          revision = 1L,
        )
      AutosizeType.FIT,
      AutosizeType.FIT_X,
      AutosizeType.FIT_Y -> {
        // `fit` shrinks the plotting area so the total matches the declared size, which means
        // recomputing scale ranges and re-encoding. That needs a second layout pass the compiler
        // does
        // not have yet, so it falls back to `pad` and says so.
        diagnostics.warn(
          DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
          "autosize '${spec.autosize.type.name.lowercase()}' needs a second layout pass and is not " +
            "implemented; laid out as 'pad', so the surface may exceed the declared size",
        )
        layout(
          spec.copy(autosize = spec.autosize.copy(type = AutosizeType.PAD)),
          content,
          plot,
          ids,
          diagnostics,
        )
      }
    }
  }

  public companion object {
    private val EMPTY_SIGNALS = SignalScope(emptyMap(), emptyMap())

    public const val DEFAULT_WIDTH: Double = 200.0
    public const val DEFAULT_HEIGHT: Double = 200.0
  }
}
