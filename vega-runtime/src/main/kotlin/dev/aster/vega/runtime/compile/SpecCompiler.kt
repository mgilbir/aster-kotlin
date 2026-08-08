package dev.aster.vega.runtime.compile

import dev.aster.vega.expression.CachingExpressionCompiler
import dev.aster.vega.expression.Clock
import dev.aster.vega.expression.ExpressionCompiler
import dev.aster.vega.expression.RandomStream
import dev.aster.vega.expression.VegaExpressionCompiler
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaDiagnostic
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.spec.AutosizeType
import dev.aster.vega.model.spec.ChannelValue
import dev.aster.vega.model.spec.EncodeSpec
import dev.aster.vega.model.spec.MarkSpec
import dev.aster.vega.model.spec.MarkType
import dev.aster.vega.model.spec.SpecParser
import dev.aster.vega.model.spec.VegaSpec
import dev.aster.vega.runtime.load.DataLoader
import dev.aster.vega.runtime.load.DenyLoader
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
import kotlin.math.ceil

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
 * The datasets, scales and signals are resolved in one dependency order rather than in three fixed
 * phases — see [DataflowOrder], which is this compiler's stand-in for upstream's dataflow ranking.
 * Mark encoding, axes and layout follow, in that order. It executes the subset the runtime supports
 * and reports the rest, which is what lets the differential harness compare against upstream on a
 * real specification instead of on hand-authored scenes.
 *
 * Nesting lives in [ScopeCompiler]: a group mark carries a whole scope of its own, and this class
 * only sets up the outermost one. Legends and titles are not implemented; the parser reports them.
 *
 * @param textEngine measures axis labels. Pass the Android engine to get the scene the device will
 *   draw, or the default deterministic engine for JVM comparisons.
 */
public class SpecCompiler(
  private val textEngine: TextEngine = MetricTextEngine(),
  /**
   * How a specification's `url` data is fetched. Refuses everything unless a host opts in.
   *
   * See [DataLoader]: a URL in a specification is a request that this process fetch an address the
   * specification chose, so it is the host's decision and not the specification's.
   */
  private val loader: DataLoader = DenyLoader,
  /**
   * The seed `random()` draws from, and the instant `now()` reports.
   *
   * Both default to a constant, which is the whole point: a chart built on either has to be a pure
   * function of its specification or nothing can be compared with it — not upstream, not a previous
   * run, not itself. A host that wants a genuinely stochastic chart passes a seed of its own, and a
   * host that wants a live clock passes one; nothing else in the engine reads either.
   */
  private val randomSeed: Long = RandomStream.DEFAULT_SEED,
  private val clock: Clock = Clock.Fixed,
) {

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
    // `fit` shrinks the plotting area so the *whole drawing* comes out the declared size, which
    // cannot be known until the drawing has been measured. Upstream measures, sets the `width` and
    // `height` signals to what is left, and re-runs its dataflow; this compiler is a pure function
    // of the specification, so it does the same thing by compiling twice. The first pass exists
    // only
    // to be measured — its diagnostics are thrown away, because the second pass reports the same
    // ones against the size that is actually drawn.
    val fit =
      if (spec.autosize.type.isFit) {
        measure(compileOnce(spec, signalOverrides, DiagnosticCollector(), null))
      } else {
        null
      }
    return compileOnce(spec, signalOverrides, DiagnosticCollector(), fit).compiled
  }

  /** One compile, with what a later pass needs to measure it. */
  private class Pass(val compiled: CompiledSpec, val reach: RectD, val plot: PlotSize)

  /**
   * How far a first pass reached past its plotting area, on each side.
   *
   * Upstream's `viewSizeLayout` rounds each of these **outward** before subtracting, so a label
   * hanging 30.5 units to the left costs the plotting area 31.
   */
  private data class Overflow(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
  )

  private fun measure(pass: Pass): Overflow {
    val reach =
      if (pass.reach.isEmpty) RectD(0.0, 0.0, pass.plot.width, pass.plot.height) else pass.reach
    return Overflow(
      left = maxOf(0.0, ceil(-reach.left)),
      top = maxOf(0.0, ceil(-reach.top)),
      right = maxOf(0.0, ceil(reach.right - pass.plot.width)),
      bottom = maxOf(0.0, ceil(reach.bottom - pass.plot.height)),
    )
  }

  private fun compileOnce(
    spec: VegaSpec,
    signalOverrides: Map<String, VegaValue>,
    diagnostics: DiagnosticCollector,
    /** What the first pass measured, or null when this *is* the first pass. */
    fit: Overflow?,
  ): Pass {
    val ids = SceneNodeIdAllocator()

    val declaredWidth = spec.width ?: DEFAULT_WIDTH
    val declaredHeight = spec.height ?: DEFAULT_HEIGHT
    // `width` and `height` are signals as well as properties, and a specification may declare
    // either as a signal instead — a trellis whose height is `6 * (offset + cellHeight)` has no
    // sensible number to write down. Upstream merges such a declaration into the built-in signal,
    // so the property is only a seed and the signal is the answer; the size is therefore settled
    // below, once the signals have resolved.
    val sized = spec.signals.mapTo(mutableSetOf()) { it.name }
    if ((spec.width == null && "width" !in sized) || (spec.height == null && "height" !in sized)) {
      diagnostics.warn(
        DiagnosticCodes.PARSE_MISSING_PROPERTY,
        "Specification has no width or height; using ${DEFAULT_WIDTH}x$DEFAULT_HEIGHT",
      )
    }
    // `autosize: {"contains": "padding"}` measures the declared size to the *outside* of the
    // padding, so the plotting area is what is left after it — and the `width` signal shrinks with
    // it, which is how a 400-wide radar chart with 40 padding ends up with a radius of 160 rather
    // than 200. Upstream subtracts it in `viewSizeLayout` and then overwrites the result for `pad`,
    // where the surface grows to fit anyway, so the setting only bites on the other types.
    val containsPadding =
      spec.autosize.contains.equals("padding", ignoreCase = true) &&
        spec.autosize.type != AutosizeType.PAD
    // What the surface is measured against, before any fitting: upstream's
    // `viewWidth`/`viewHeight`.
    val viewWidth =
      if (containsPadding) declaredWidth - spec.padding.left - spec.padding.right else declaredWidth
    val viewHeight =
      if (containsPadding) declaredHeight - spec.padding.top - spec.padding.bottom
      else declaredHeight

    // A `fit` chart's plotting area is what is left of the declared size once the drawing's
    // overhang
    // has been taken out of it — `fit-x` and `fit-y` do that on one axis and let the other grow the
    // way `pad` does. This is the whole of the second pass: seed `width` and `height` with the
    // fitted numbers and everything downstream follows, because a scale range, an axis extent and a
    // mark position are all measured against them.
    val width =
      if (fit != null && spec.autosize.type != AutosizeType.FIT_Y) {
        maxOf(0.0, viewWidth - fit.left - fit.right)
      } else {
        viewWidth
      }
    val height =
      if (fit != null && spec.autosize.type != AutosizeType.FIT_X) {
        maxOf(0.0, viewHeight - fit.top - fit.bottom)
      } else {
        viewHeight
      }

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

    // Datasets, scales and signals are resolved in **one dependency order**, not in three phases.
    //
    // Phases cannot express what real specifications ask for. A transform parameter may be a signal
    // — `"ops": [{"signal": "op"}]` is how a chart lets a control choose an aggregate — so signals
    // have to come before data; a scale domain may name a dataset, so data has to come before
    // scales; and a transform parameter may be `{"signal": "domain('xscale')"}`, so a scale has to
    // come before data. `probability-density` needs all three at once and no fixed order supplies
    // it. Upstream never had the problem: `vega-parser` puts every dataset, scale and signal into
    // one dataflow and the topological ranking decides. [DataflowOrder] is that ranking.
    val order = DataflowOrder.of(spec.data, spec.scales, spec.signals, expressions, diagnostics)

    // One stream for the whole compile, seeded the same way every time. Every scope built below
    // shares it, so the draws form a single sequence the way upstream's module-level generator
    // does. A `fit` chart compiles twice and each pass starts the sequence again, which is what
    // makes the second pass draw the chart the first one measured.
    val stream = RandomStream(randomSeed)

    // The state the order fills in, and the reason each piece is shared rather than copied:
    // `signalValues` because a transform may *publish* a signal, and everything after it must see
    // that; the datasets and scales because whatever resolves next may read either.
    val signalValues = LinkedHashMap<String, VegaValue>(implicitSignals)
    // A handler's value is the current one, and everything downstream — a transform included —
    // should read that rather than the declared value it is replacing.
    val scales = LinkedHashMap<String, VegaScale>()
    var resolved = ScopeData.Empty
    // `setdata` in a signal's expression replaces a dataset outright, so the write has to land
    // where
    // everything after it will read: the accumulated scope data, not a copy of it. The ordering
    // puts
    // every reader of that dataset behind the signal.
    val session =
      SignalResolver(diagnostics, expressions, stream, clock).session(
        spec.signals,
        signalValues,
        signalOverrides,
      ) { name, rows ->
        resolved = resolved.withDataset(name, rows)
      }

    val dataSpecs = spec.data.associateBy { it.name }
    val scaleSpecs = spec.scales.associateBy { it.name }
    // What is *still* waiting, so a premature read can be named as premature. Both shrink as the
    // order is walked, which is what makes each report specific to where it happened.
    val unresolvedSignals = spec.signals.mapTo(mutableSetOf()) { it.name }
    val unbuiltScales = spec.scales.mapTo(mutableSetOf()) { it.name }

    val data = DataResolver(diagnostics, expressions, loader, stream, clock)
    for (operator in order.order) {
      when (operator) {
        is Operator.Signal -> {
          session.resolve(operator.name, resolved.datasets, scales, unbuiltScales)
          unresolvedSignals.remove(operator.name)
        }
        is Operator.Data ->
          dataSpecs[operator.name]?.let {
            resolved = data.resolve(listOf(it), signalValues, resolved, unresolvedSignals, scales)
          }
        is Operator.Scale ->
          scaleSpecs[operator.name]?.let {
            // The scales built so far are in scope, so a domain written as `{"signal":
            // "domain('other')"}` reads a real one rather than nothing.
            val scope =
              SignalScope(
                signalValues,
                resolved.datasets,
                scales = scales,
                diagnostics = diagnostics,
              )
            scales.putAll(
              ScaleResolver(
                  resolved.datasets,
                  plotSize(signalValues, width, height),
                  diagnostics,
                  NumberResolver(expressions, scope, diagnostics),
                )
                .resolve(listOf(it))
            )
            unbuiltScales.remove(operator.name)
          }
      }
    }

    val datasets = resolved.datasets
    val signals = session.scope(datasets, scales)
    // The plotting area, now that a declared `width` or `height` signal has had its say. Everything
    // downstream measures against this: an axis's extent, the surface.
    val plot = plotSize(signalValues, width, height)

    val root =
      CompileScope(resolved, signals, scales, plot, spec.scales.associate { it.name to it.type })
    val scope =
      ScopeCompiler(ids, textEngine, diagnostics, expressions, data, stream, clock)
        .compile(spec.marks, spec.axes, spec.legends, spec.title, spec.layout, root, plot)

    val content = frame(spec, scope.nodes, plot, root, ids, diagnostics, expressions)

    val scene = layout(spec, scope.bounds, content, plot, ids, diagnostics, fit)
    return Pass(
      CompiledSpec(scene, scales, signals, diagnostics.diagnostics, spec),
      scope.bounds,
      plot,
    )
  }

  /**
   * The chart's own group item: the frame every mark hangs inside.
   *
   * Upstream wraps a specification in a root group and encodes it like any other group mark, from
   * the top-level `encode` block over two defaults of its own — an origin at (0,0) and an extent of
   * the plotting area. A specification that overrides the origin moves every coordinate in the
   * chart with it, which is how a polar plot puts its centre in the middle of the surface instead
   * of in the top-left corner.
   */
  private fun frame(
    spec: VegaSpec,
    children: List<SceneNode>,
    plot: PlotSize,
    scope: CompileScope,
    ids: SceneNodeIdAllocator,
    diagnostics: DiagnosticCollector,
    expressions: ExpressionCompiler,
  ): GroupNode {
    val encoder =
      MarkEncoder(
        scope.scales,
        ids,
        diagnostics,
        scope.signals.withScales(scope.scales, diagnostics),
        expressions,
        textEngine,
      )
    val encoded =
      encoder.encodeGroup(
        MarkSpec(type = MarkType.GROUP, name = "root", encode = rootEncode(spec, plot)),
        listOf(VegaValue.EmptyObject),
      ) { _, _, _ ->
        children
      }
    val node = encoded.single() as GroupNode
    // `encodeGroup` labels every group "scope", which is what upstream calls a group *mark*. The
    // chart's own group is a frame, and the differential harness finds it by that name.
    return node.copy(metadata = node.metadata.copy(role = "frame", markName = "root"))
  }

  /**
   * The specification's `encode` over the two channels upstream always supplies.
   *
   * `enter` is defaulted rather than forced, so a specification's own `x` wins; `width` and
   * `height` come from `update`, which is where upstream puts them, and are the plotting area
   * rather than the declared size — the two differ under `autosize.contains: "padding"`.
   */
  private fun rootEncode(spec: VegaSpec, plot: PlotSize): EncodeSpec =
    EncodeSpec(
      enter =
        mapOf(
          "x" to ChannelValue.Constant(VegaValue.Num(0.0)),
          "y" to ChannelValue.Constant(VegaValue.Num(0.0)),
        ) + spec.encode.enter,
      update =
        mapOf(
          "width" to ChannelValue.Constant(VegaValue.Num(plot.width)),
          "height" to ChannelValue.Constant(VegaValue.Num(plot.height)),
        ) + spec.encode.update,
      exit = spec.encode.exit,
      hover = spec.encode.hover,
    )

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
    /** The first pass's overhang, for a `fit` chart. Null for every other type. */
    fit: Overflow?,
  ): Scene {
    val padding = spec.padding
    val background = spec.background?.let { SceneColor.parse(it) }
    if (spec.background != null && background == null) {
      diagnostics.warn(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "Could not parse background colour '${spec.background}'",
      )
    }

    // How far this pass hangs outside its own plotting area. **Not** rounded, unlike the overhang
    // that decides a `fit`: `viewSizeLayout` rounds outward because it is sizing a canvas, and a
    // canvas is whole pixels, but the surface compared against upstream is the frame's own bounds
    // plus the padding — which is fractional whenever a label ends on a fraction, and upstream's
    // references have the fractions in them to prove it.
    val bounds = if (reach.isEmpty) RectD(0.0, 0.0, plot.width, plot.height) else reach
    val over =
      Overflow(
        left = maxOf(0.0, -bounds.left),
        top = maxOf(0.0, -bounds.top),
        right = maxOf(0.0, bounds.right - plot.width),
        bottom = maxOf(0.0, bounds.bottom - plot.height),
      )
    // A `fit` chart's origin is the **first** pass's rounded overhang, because that is what
    // upstream
    // hands to `resizeView`: it measures once, sets the size signals, and re-runs the dataflow with
    // the layout step short-circuited, so the second pass never re-measures.
    val origin = fit ?: over

    fun scene(width: Double, height: Double): Scene =
      Scene(
        width = padding.left + width + padding.right,
        height = padding.top + height + padding.bottom,
        background = background,
        root =
          GroupNode(
            id = ids.allocate(),
            children = listOf(content),
            transform = Transform2D.translate(padding.left + origin.left, padding.top + origin.top),
            metadata = NodeMetadata(role = "root"),
          ),
        revision = 1L,
      )

    return when (spec.autosize.type) {
      // `pad` grows the surface so the content plus its overhang fits inside the padding: axis
      // labels hang outside the plotting area, so the surface ends up larger than `width`/`height`.
      // Verified against upstream, which renders the 344x196 bar fixture into a 385x228 surface
      // because the y-axis labels extend 31 units to the left.
      AutosizeType.PAD ->
        scene(over.left + plot.width + over.right, over.top + plot.height + over.bottom)
      // `none` takes the plotting area as given and lets anything outside it overflow. The padding
      // is still there: upstream sizes the surface as the view plus its padding and translates the
      // content into it, so a `none` chart with padding is inset exactly like a `pad` one — it
      // simply never grows to make room for what hangs out. Its origin is the padding alone, with
      // no room made for the overhang, which is the whole difference from `pad`.
      AutosizeType.NONE ->
        Scene(
          width = padding.left + plot.width + padding.right,
          height = padding.top + plot.height + padding.bottom,
          background = background,
          root =
            GroupNode(
              id = ids.allocate(),
              children = listOf(content),
              transform = Transform2D.translate(padding.left, padding.top),
              metadata = NodeMetadata(role = "root"),
            ),
          revision = 1L,
        )
      // A fitted chart is measured exactly like a padded one, because the fitting already happened:
      // the plotting area was shrunk *before* anything was drawn, so what is left to do is add on
      // whatever still hangs outside it. The result is close to the declared size but not equal to
      // it — the shrink reserves a whole unit for an overhang of 30.5, so the drawing comes back a
      // fraction smaller than the room made for it, and upstream's own references carry that
      // fraction.
      AutosizeType.FIT,
      AutosizeType.FIT_X,
      AutosizeType.FIT_Y ->
        scene(over.left + plot.width + over.right, over.top + plot.height + over.bottom)
    }
  }

  /**
   * The plotting area as the `width` and `height` signals currently have it.
   *
   * Read from the live values rather than settled once, because a scale with a `"width"` range is
   * built at whatever point the order reaches it — after the `width` signal, which is the edge
   * [DataflowOrder] adds for exactly this.
   */
  private fun plotSize(
    signals: Map<String, VegaValue>,
    declaredWidth: Double,
    declaredHeight: Double,
  ): PlotSize =
    PlotSize(
      numberSignal(signals, "width") ?: declaredWidth,
      numberSignal(signals, "height") ?: declaredHeight,
    )

  /** A signal's value as a usable number, or null if it is not one. */
  private fun numberSignal(signals: Map<String, VegaValue>, name: String): Double? =
    (signals[name] as? VegaValue.Num)?.value?.takeIf { it.isFinite() }

  public companion object {
    private val EMPTY_SIGNALS = SignalScope(emptyMap(), emptyMap())

    /** A stand-in where only the reach and the plot size of a [Pass] are wanted. */
    private val EMPTY_COMPILED = CompiledSpec(null, emptyMap(), EMPTY_SIGNALS, emptyList())

    public const val DEFAULT_WIDTH: Double = 200.0
    public const val DEFAULT_HEIGHT: Double = 200.0
  }
}
