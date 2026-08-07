package dev.aster.vega.runtime.compile

import dev.aster.vega.expression.CachingExpressionCompiler
import dev.aster.vega.expression.VegaExpressionCompiler
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaDiagnostic
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.spec.AutosizeType
import dev.aster.vega.model.spec.SpecParser
import dev.aster.vega.model.spec.VegaSpec
import dev.aster.vega.runtime.scale.VegaScale
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.MetricTextEngine
import dev.aster.vega.scene.NodeMetadata
import dev.aster.vega.scene.RectD
import dev.aster.vega.scene.Scene
import dev.aster.vega.scene.SceneColor
import dev.aster.vega.scene.SceneNodeIdAllocator
import dev.aster.vega.scene.TextEngine
import dev.aster.vega.scene.Transform2D

/** A compiled specification: the scene, the scales it built, and everything it could not honour. */
public data class CompiledSpec(
  val scene: Scene?,
  /**
   * The top-level scales.
   *
   * Scales declared inside a group mark are absent by design: a faceted group resolves its scales
   * once per cell against that cell's data, so there is no single scale of that name to report.
   */
  val scales: Map<String, VegaScale>,
  /** Resolved signal values, including the implicit `width`, `height` and `padding`. */
  val signals: SignalScope,
  val diagnostics: List<VegaDiagnostic>,
  /**
   * The parsed specification, so a caller can read what was *asked for* rather than what came out.
   *
   * The interaction layer needs it: a signal's `on` handlers are part of the specification and
   * survive a recompile, while everything else in here is rebuilt each time.
   */
  val spec: VegaSpec? = null,
) {
  public val isUsable: Boolean
    get() = scene != null
}

/**
 * Compiles a parsed Vega specification into a scene.
 *
 * This walks the pipeline in the order the stages depend on each other: data, signals, scales, mark
 * encoding, axes and layout. It executes the subset the runtime supports and reports the rest,
 * which is what lets the differential harness compare against upstream on a real specification
 * instead of on hand-authored scenes.
 *
 * Nesting lives in [ScopeCompiler]: a group mark carries a whole scope of its own, and this class
 * only sets up the outermost one. Legends and titles are not implemented; the parser reports them.
 *
 * @param textEngine measures axis labels. Pass the Android engine to get the scene the device will
 *   draw, or the default deterministic engine for JVM comparisons.
 */
public class SpecCompiler(private val textEngine: TextEngine = MetricTextEngine()) {

  public fun compileJson(
    json: String,
    signalOverrides: Map<String, VegaValue> = emptyMap(),
  ): CompiledSpec {
    val parsed = SpecParser().parseJson(json)
    val spec =
      parsed.spec ?: return CompiledSpec(null, emptyMap(), EMPTY_SIGNALS, parsed.diagnostics)
    val compiled = compile(spec, signalOverrides)
    // Parse diagnostics come first so a reader sees problems in specification order.
    return compiled.copy(diagnostics = parsed.diagnostics + compiled.diagnostics)
  }

  /**
   * @param signalOverrides signals an event handler has set, which keep their value through this
   *   compile rather than being recomputed. This is how interaction works without an incremental
   *   dataflow: a fired handler sets a signal and the whole specification is compiled again, which
   *   measurement showed costs well under a frame (STATUS.md, Performance observations).
   */
  public fun compile(
    spec: VegaSpec,
    signalOverrides: Map<String, VegaValue> = emptyMap(),
  ): CompiledSpec {
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
    val data = DataResolver(diagnostics, expressions)
    val datasets = data.resolve(spec.data, transformSignals)
    val signals =
      SignalResolver(diagnostics, expressions)
        .resolve(spec.signals, datasets, transformSignals, signalOverrides)

    val numbers = NumberResolver(expressions, signals, diagnostics)
    val scales = ScaleResolver(datasets, plot, diagnostics, numbers).resolve(spec.scales)

    val root =
      CompileScope(datasets, signals, scales, plot, spec.scales.associate { it.name to it.type })
    val scope =
      ScopeCompiler(ids, textEngine, diagnostics, expressions, data)
        .compile(spec.marks, spec.axes, spec.legends, spec.title, spec.layout, root, plot)

    val content =
      GroupNode(
        id = ids.allocate(),
        children = scope.nodes,
        metadata = NodeMetadata(role = "frame", markName = "root"),
      )

    val scene = layout(spec, scope.bounds, content, plot, ids, diagnostics)
    return CompiledSpec(scene, scales, signals, diagnostics.diagnostics, spec)
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
    /**
     * How far the drawing reaches, measured the way upstream measures it — from each axis's extent
     * rather than from the items it happens to have drawn. Not the same as `content.bounds`.
     */
    reach: RectD,
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
        val bounds = if (reach.isEmpty) RectD(0.0, 0.0, plot.width, plot.height) else reach
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
          reach,
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
