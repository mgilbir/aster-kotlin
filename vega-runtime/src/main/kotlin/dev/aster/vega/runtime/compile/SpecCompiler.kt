package dev.aster.vega.runtime.compile

import dev.aster.vega.dataflow.transform.ProjectionDefinition
import dev.aster.vega.expression.CachingExpressionCompiler
import dev.aster.vega.expression.Clock
import dev.aster.vega.expression.Evaluator
import dev.aster.vega.expression.ExpressionCompiler
import dev.aster.vega.expression.Functions
import dev.aster.vega.expression.RandomStream
import dev.aster.vega.expression.VegaExpressionCompiler
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaDiagnostic
import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asNumberOrNull
import dev.aster.vega.model.asString
import dev.aster.vega.model.locale.VegaLocale
import dev.aster.vega.model.spec.AutosizeType
import dev.aster.vega.model.spec.ChannelValue
import dev.aster.vega.model.spec.EncodeSpec
import dev.aster.vega.model.spec.MarkSpec
import dev.aster.vega.model.spec.MarkType
import dev.aster.vega.model.spec.SpecParser
import dev.aster.vega.model.spec.VegaSpec
import dev.aster.vega.model.spec.mergeConfig
import dev.aster.vega.runtime.load.DataLoader
import dev.aster.vega.runtime.load.DenyLoader
import dev.aster.vega.runtime.scale.VegaScale
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.MarkAccessibility
import dev.aster.vega.scene.MetricTextEngine
import dev.aster.vega.scene.NodeMetadata
import dev.aster.vega.scene.RectD
import dev.aster.vega.scene.Scene
import dev.aster.vega.scene.SceneColor
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.SceneNodeId
import dev.aster.vega.scene.SceneNodeIdAllocator
import dev.aster.vega.scene.SizeD
import dev.aster.vega.scene.TextEngine
import dev.aster.vega.scene.Transform2D
import kotlin.math.ceil
import kotlinx.datetime.TimeZone

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
  /**
   * Each item's appearance under the pointer, by the id of the item it replaces.
   *
   * A mark's `hover` block is layered over its `update` block and the mark encoded a second time,
   * so responding to the pointer costs a node swap rather than a recompile. Empty for a
   * specification with no `hover` blocks, which is most of them.
   */
  val hoverVariants: Map<dev.aster.vega.scene.SceneNodeId, SceneNode> = emptyMap(),
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
/**
 * One item's re-encoding through a named block of its own mark, as a handler's `encode` left it.
 *
 * [fresh] is whether it was applied by the event being handled *now*. It decides whether the block
 * beats the mark's `update` or loses to it, which is upstream's behaviour and not a choice: the
 * pass that applies an overlay puts it last, and every pass after that re-runs `update`, which
 * takes back the channels it sets and leaves the others alone.
 */
public data class ItemEncode(val set: String, val fresh: Boolean)

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
  /**
   * The language everything the engine **generates** is written in: a month name on a time axis, a
   * thousands separator, a spoken caption.
   *
   * A data holder rather than a language tag, and supplied by the host for the same reason
   * [textEngine] is — the platform knows this and common Kotlin cannot reach it. See `VegaLocale`,
   * whose fields are d3's own locale definitions so a host can copy one across. Defaults to d3's
   * `en-US`, which is what upstream produces and what every differential fixture is compared
   * against.
   *
   * It does not touch **parsing**: a specification writing `"Jan 5 2026"` in its data means January
   * in every language, because d3's parsing is part of the wire format.
   */
  private val locale: VegaLocale = VegaLocale.EnglishUS,
  /**
   * A `config` block the **host** supplies, which the specification's own beats key by key.
   *
   * How an app themes a chart it did not write: a specification carries the colours whoever
   * produced it chose, and an app drawing it on a dark surface has to be able to say otherwise.
   * Merged by [mergeConfig] — `vega-util`'s own rules — with the specification as the later and
   * therefore winning source, because a theme is a default and a stated value overrides it.
   *
   * Applied where a **specification enters** the engine, which is [compileJson] and
   * [VegaLiteCompiler]. The [compile] overload takes a specification a caller has already parsed
   * and cannot merge JSON into it; such a caller merges with `mergeConfig` themselves, which is the
   * same function this uses.
   */
  private val hostConfig: VegaValue? = null,
  /**
   * The size of the surface the chart is drawn in, which `width: "container"` asks for.
   *
   * A responsive width cannot come from the specification — `"container"` says "ask the page", and
   * there is no page — so it comes from the host, the one party that knows it. Vega-Lite compiles
   * `"container"` into a signal reading `containerSize()`, so this is what that function answers
   * with; without it a chart falls back to `config.view.continuousWidth`, which is 300 and is what
   * upstream falls back to outside a browser.
   *
   * Usually only the width is known — a chart in a scrolling list has as much height as it asks for
   * — and a zero or absent dimension leaves that dimension to the fallback, so a host can answer
   * the half it knows.
   */
  private val containerSize: SizeD? = null,
  /**
   * Tables the **host** supplies, keyed by the dataset name the specification uses.
   *
   * Upstream's `view.data(name, rows)`, which is how a chart is drawn from data the *app* holds
   * rather than data a payload carried: a diary in a local store, a query's result, rows assembled
   * from a sensor. A specification declares `{"name": "diary"}` — no values, no url, no source —
   * and this fills it. In Vega-Lite that is `{"data": {"name": "diary"}}`, and the name survives
   * compilation, so a host uses the name it wrote.
   *
   * The rows arrive **as inline values would**, before anything else reads the dataset, so
   * `format.parse` and every transform run over them unchanged — a host does not have to
   * reimplement a parse rule to get its own table through. Rows are `VegaValue.Obj`; a row that is
   * not an object is wrapped as `{"data": …}`, which is upstream's own normalisation.
   *
   * Three things are refused rather than guessed, each with a diagnostic: a name no dataset claims,
   * a **derived** dataset (filling one would discard the transforms it exists for), and a `url` —
   * which is *not fetched* when a host has supplied the table, so this is also the way to draw a
   * chart whose payload names an address the host would rather not open.
   *
   * Named for the same half of the boundary [hostConfig] is: a compile has a local `datasets` of
   * its own — the ones it resolved — and two things called that in one function is a bug waiting to
   * be written.
   */
  private val hostData: Map<String, List<VegaValue>>? = null,
  /**
   * What **local** time means, or null for the device's own zone.
   *
   * Every date in a specification is an instant, and which day one falls on has an answer only in
   * some zone. Upstream has two — the browser's for `time` scales, `timeunit: "local"` and the
   * local expression functions, and UTC for the `utc` forms — and it needs no more, because a
   * browser is always on the device it draws for. An app is not: the zone a reader lives in can
   * differ from the zone the device is set to, and a chart of days then disagrees with the rest of
   * the app about which day a measurement was on. A diary bucketed by day, or into morning and
   * evening, is the same case; it has no Vega-Lite time unit at all and has to be binned against a
   * stated zone.
   *
   * So a host may say which zone local is. Null keeps upstream's behaviour exactly, and keeps
   * reading the zone when it is needed rather than capturing it, so a long-lived process follows a
   * change of the system zone.
   *
   * It reaches four things: a `time` scale's ticks and labels, `timeunit`'s buckets, the local
   * expression functions, and `format.parse`. The last is not an oversight — a naive timestamp in
   * the data is read in local time because that is what `Date.parse` does, so this settles what
   * local *is* rather than whether it applies. `utc` scales, `utc:` parse patterns and the `utc*`
   * functions are unaffected. See `VegaTimeZones`, whose `of` answers null rather than throwing on
   * an identifier that came from a server.
   */
  private val timeZone: TimeZone? = null,
) {

  public fun compileJson(
    json: String,
    signalOverrides: Map<String, VegaValue> = emptyMap(),
    itemEncodes: Map<SceneNodeId, ItemEncode> = emptyMap(),
  ): CompiledSpec {
    val parser = SpecParser()
    val parsed =
      if (hostConfig == null) {
        parser.parseJson(json)
      } else {
        // Merged before parsing, because the parser reads `config` on the way past and every guide
        // resolves its own properties against what it found there.
        val diagnostics = DiagnosticCollector()
        val root = VegaJson.parseOrNull(json, diagnostics)
        if (root !is VegaValue.Obj) {
          parser.parseJson(json)
        } else {
          val merged = mergeConfig(hostConfig, root.fields["config"])
          parser.parse(
            if (merged == null) root
            else VegaValue.Obj(LinkedHashMap(root.fields).apply { put("config", merged) })
          )
        }
      }
    val spec =
      parsed.spec ?: return CompiledSpec(null, emptyMap(), EMPTY_SIGNALS, parsed.diagnostics)
    val compiled = compile(spec, signalOverrides, itemEncodes)
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
    itemEncodes: Map<SceneNodeId, ItemEncode> = emptyMap(),
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
        measure(compileOnce(spec, signalOverrides, DiagnosticCollector(), null, itemEncodes))
      } else {
        null
      }
    return compileOnce(spec, signalOverrides, DiagnosticCollector(), fit, itemEncodes).compiled
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
    itemEncodes: Map<SceneNodeId, ItemEncode> = emptyMap(),
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
      // Not a warning: a chart measured by its own contents is a written form, not an omission, and
      // a faceted Vega-Lite chart is always written that way. It is worth saying once, at the level
      // a host shows only when somebody is looking for it.
      diagnostics.info(
        DiagnosticCodes.PARSE_MISSING_PROPERTY,
        "Specification declares no width or height; the surface is measured from its contents",
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
    // The expression functions carry the locale too: `monthFormat`, `timeFormat` and `format` are
    // seven of the 119 whose answer depends on it, and a `calculate` transform writing
    // `timeFormat(datum.t, '%b %Y')` is how most charts label a derived column.
    // The container size goes the same way: `width: "container"` compiles to a signal that reads
    // `containerSize()`, so the host's answer arrives through the function table like the locale's.
    val expressions =
      CachingExpressionCompiler(
        VegaExpressionCompiler(
          Evaluator(
            Functions.functionsFor(
              locale,
              containerWidth = containerSize?.width?.takeIf { it > 0.0 },
              containerHeight = containerSize?.height?.takeIf { it > 0.0 },
              timeZone = timeZone,
            )
          )
        )
      )

    // Datasets, scales and signals are resolved in **one dependency order**, not in three phases.
    //
    // Phases cannot express what real specifications ask for. A transform parameter may be a signal
    // — `"ops": [{"signal": "op"}]` is how a chart lets a control choose an aggregate — so signals
    // have to come before data; a scale domain may name a dataset, so data has to come before
    // scales; and a transform parameter may be `{"signal": "domain('xscale')"}`, so a scale has to
    // come before data. `probability-density` needs all three at once and no fixed order supplies
    // it. Upstream never had the problem: `vega-parser` puts every dataset, scale and signal into
    // one dataflow and the topological ranking decides. [DataflowOrder] is that ranking.
    val order =
      DataflowOrder.of(
        spec.data,
        spec.scales,
        spec.signals,
        expressions,
        diagnostics,
        spec.projections,
      )

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

    val data = DataResolver(diagnostics, expressions, loader, stream, clock, timeZone, hostData)
    for (operator in order.order) {
      when (operator) {
        is Operator.Signal -> {
          // A projection is made of signals, so it is rebuilt from the ones that have settled at
          // this point in the order — exactly as it is for each dataset below. Without it a signal
          // calling `geoScale('p')` was told the projection did not exist, however late it ran.
          session.resolve(
            operator.name,
            resolved.datasets,
            scales,
            unbuiltScales,
            projectionsSoFar(spec, expressions, signalValues, resolved, scales),
          )
          unresolvedSignals.remove(operator.name)
        }
        is Operator.Data ->
          dataSpecs[operator.name]?.let {
            // Resolved here rather than once up front: a projection is made of signals, and the
            // signals are still settling as the dataflow order is walked. It is a handful of
            // arithmetic per dataset and it is what lets a `formula` call `geoCentroid()`.
            val scope =
              SignalScope(
                signalValues,
                resolved.datasets,
                scales = scales,
                diagnostics = diagnostics,
              )
            // Into a collector nobody reads: this runs once per dataset and the same projection
            // would report the same unimplemented property once for each, where the scope built
            // after the loop reports it exactly once.
            val projections = projectionsSoFar(spec, expressions, signalValues, resolved, scales)
            resolved =
              data.resolve(
                listOf(it),
                signalValues,
                resolved,
                unresolvedSignals,
                scales,
                projections,
                refreshProjections = { signals ->
                  projectionsSoFar(spec, expressions, signals, resolved, scales)
                },
              )
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
                  timeZone,
                )
                .resolve(listOf(it))
            )
            unbuiltScales.remove(operator.name)
          }
      }
    }

    // ---- the second pass a shared fit needs ---------------------------------------------------
    //
    // A projection fitted to feature collections from **more than one** dataset cannot be got right
    // in a single ordered walk. Each publisher has to publish before *any* reader runs, and a walk
    // that resolves a dataset once has to pick one of them to go first: whichever reader runs
    // earliest sees a fit built from part of the geometry. On the two-publisher fixture the first
    // layer landed at x=0 where upstream has 80.
    //
    // Upstream has no ordering problem because each transform is its own operator — `geojson`
    // publishes, the projection waits for every signal it names, and `geopoint` follows. Reaching
    // that here would mean splitting a dataset's pipeline into per-transform nodes, which nothing
    // else has needed.
    //
    // So the datasets that read such a projection are resolved **again**, once every publisher has
    // had its say. The first pass exists to collect the signals; the second is the one whose rows
    // are kept. Only datasets that actually read a shared fit are re-run, so nothing else pays for
    // it — and re-running is what upstream does too, by re-pulsing until the fit settles.
    val shared = sharedFitReaders(spec, expressions)
    if (shared.isNotEmpty()) {
      for (name in order.order.filterIsInstance<Operator.Data>().map { it.name }) {
        if (name !in shared) continue
        dataSpecs[name]?.let { again ->
          resolved =
            data.resolve(
              listOf(again),
              signalValues,
              resolved,
              unresolvedSignals,
              scales,
              projectionsSoFar(spec, expressions, signalValues, resolved, scales),
              refreshProjections = { signals ->
                projectionsSoFar(spec, expressions, signals, resolved, scales)
              },
            )
        }
      }
    }

    val datasets = resolved.datasets
    val signals = session.scope(datasets, scales)
    // The plotting area, now that a declared `width` or `height` signal has had its say. Everything
    // downstream measures against this: an axis's extent, the surface.
    val plot = plotSize(signalValues, width, height)

    val root =
      CompileScope(
        resolved,
        signals,
        scales,
        plot,
        spec.scales.associate { it.name to it.type },
        ProjectionResolver(NumberResolver(expressions, signals, diagnostics), diagnostics)
          .resolve(spec.projections),
      )
    val scopeCompiler =
      ScopeCompiler(
        ids,
        textEngine,
        diagnostics,
        expressions,
        data,
        stream,
        clock,
        itemEncodes,
        locale,
        timeZone,
      )
    val scope =
      scopeCompiler.compile(
        spec.marks,
        spec.axes,
        spec.legends,
        spec.title,
        spec.layout,
        root,
        plot,
      )

    // A table nobody claimed. Reported here rather than where the datasets resolve, because a group
    // mark declares datasets of its own and they are resolved through the same [DataResolver]
    // during
    // the scope compile above — so this is the first point at which "no dataset has this name"
    // is a fact rather than a guess. A chart drawn without the data it was handed is the silence
    // this
    // engine refuses everywhere else.
    hostData?.keys.orEmpty().forEach { name ->
      if (name !in data.claimedHostData) {
        diagnostics.warn(
          DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
          "The host supplied a table named '$name', which no dataset in this specification is " +
            "called. Vega-Lite writes the name from `data: {\"name\": …}` through to the compiled " +
            "specification, so it is the name to use.",
          operator = name,
        )
      }
    }

    val content = frame(spec, scope.nodes, plot, root, ids, diagnostics, expressions)

    val scene = layout(spec, scope.bounds, content, plot, ids, diagnostics, fit)
    return Pass(
      CompiledSpec(
        scene,
        scales,
        signals,
        diagnostics.diagnostics,
        spec,
        scopeCompiler.hoverVariants.toMap(),
      ),
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
        locale = locale,
      )
    val encoded =
      encoder.encodeGroup(
        MarkSpec(
          type = MarkType.GROUP,
          name = "root",
          encode = rootEncode(spec, plot),
          // Two blocks reach the chart's own frame and neither is a mark's. `config.group` is the
          // frame's own paint — upstream's comment says "top-level group marks" and means the root
          // rectangle — and the `config.style` blocks the specification named are what a Vega-Lite
          // chart's plotting area gets its border from. The named styles are the more specific of
          // the two, so they are applied over it.
          configAboveDefaults = spec.frameConfig + spec.styleAboveDefaults,
        ),
        listOf(VegaValue.EmptyObject),
      ) { _, _, _, _ ->
        children
      }
    val node = encoded.single() as GroupNode
    // `encodeGroup` labels every group "scope", which is what upstream calls a group *mark*. The
    // chart's own group is a frame, and the differential harness finds it by that name.
    return node.copy(
      metadata =
        node.metadata.copy(
          role = "frame",
          markName = "root",
          // Upstream announces the frame like any other group mark — "group mark container" — which
          // is what a reader meets first, before any axis or bar. Deliberately unlabelled: a
          // chart-level `description` belongs to the *view*, and upstream puts it on the element
          // the
          // chart is embedded in rather than on the frame. Checked against every described fixture
          // in
          // the corpus, none of which labels its frame.
          markAccessibility =
            MarkAccessibility(
              role = "graphics-object",
              roleDescription = locale.captions.markContainerRole("group"),
            ),
        )
    )
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
   * Grows the measured reach to take in the chart frame's own outline.
   *
   * A stroke straddles the edge it is drawn on, so a framed plotting area reaches half a stroke
   * width beyond its own corner on every side. Upstream measures the root group's painted bounds
   * and sees that; measuring only what is *inside* the frame does not, and the surface comes out
   * exactly one unit narrower and shorter than upstream's for every Vega-Lite chart — all of which
   * carry a `cell` style with a one-unit border.
   */
  private fun strokedFrame(reach: RectD, content: GroupNode, plot: PlotSize): RectD {
    val stroke = content.stroke ?: return reach
    if (content.size == null) return reach
    val half = stroke.width / 2.0
    return RectD(
      left = minOf(reach.left, -half),
      top = minOf(reach.top, -half),
      right = maxOf(reach.right, plot.width + half),
      bottom = maxOf(reach.bottom, plot.height + half),
    )
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
    val bounds =
      strokedFrame(
        if (reach.isEmpty) RectD(0.0, 0.0, plot.width, plot.height) else reach,
        content,
        plot,
      )
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
  /**
   * The projections buildable from the signals that have settled so far.
   *
   * Rebuilt at each step of the dataflow order rather than once, because a projection is *made of*
   * signals — `rotate: [{signal: "lon"}, 0]` — and the answer therefore changes as the order is
   * walked. Into a collector nobody reads: this runs many times over and the same projection would
   * report the same unimplemented property once per call, where the scope built after the loop
   * reports it exactly once.
   */
  /**
   * The datasets that read a projection whose `fit` draws on a dataset **other than themselves**.
   *
   * That is the condition for needing a second pass: a projection fitted only to the table that
   * reads it back is already handled, because `DataResolver` rebuilds the projections when a
   * transform publishes a signal and a `geopoint` a line later picks the rebuilt one up. The
   * problem is a fit that names two publishers, where no single ordering of whole datasets can put
   * both publications before both readings.
   *
   * Returns an empty set for every specification that has no such projection, which is nearly all
   * of them.
   */
  private fun sharedFitReaders(spec: VegaSpec, expressions: ExpressionCompiler): Set<String> {
    if (spec.projections.isEmpty()) return emptySet()
    // Which datasets publish each projection's fit, read off the signal names the fit mentions.
    val publishers = LinkedHashMap<String, Set<String>>()
    for (projection in spec.projections) {
      val source = (projection.fit as? VegaValue.Obj)?.fields?.get("signal")?.asString() ?: continue
      val names = mutableSetOf<String>()
      for (dataset in spec.data) {
        for (transform in dataset.transform) {
          val published =
            ((transform as? VegaValue.Obj)?.fields?.get("signal") as? VegaValue.Str)?.value
          if (
            published != null && Regex("\\b${Regex.escape(published)}\\b").containsMatchIn(source)
          ) {
            names += dataset.name
          }
        }
      }
      if (names.size > 1) publishers[projection.name] = names
    }
    if (publishers.isEmpty()) return emptySet()
    // Which datasets read one of those projections through a transform.
    val readers = mutableSetOf<String>()
    for (dataset in spec.data) {
      for (transform in dataset.transform) {
        val named =
          ((transform as? VegaValue.Obj)?.fields?.get("projection") as? VegaValue.Str)?.value
        if (named != null && publishers.containsKey(named)) readers += dataset.name
      }
    }
    return readers
  }

  private fun projectionsSoFar(
    spec: VegaSpec,
    expressions: ExpressionCompiler,
    signalValues: Map<String, VegaValue>,
    resolved: ScopeData,
    scales: Map<String, VegaScale>,
  ): Map<String, ProjectionDefinition> {
    if (spec.projections.isEmpty()) return emptyMap()
    val scope =
      SignalScope(
        signalValues,
        resolved.datasets,
        scales = scales,
        diagnostics = DiagnosticCollector(),
      )
    return ProjectionResolver(
        NumberResolver(expressions, scope, DiagnosticCollector()),
        DiagnosticCollector(),
      )
      .resolve(spec.projections)
  }

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
    signals[name]?.asNumberOrNull()?.takeIf { it.isFinite() }

  public companion object {
    private val EMPTY_SIGNALS = SignalScope(emptyMap(), emptyMap())

    /** A stand-in where only the reach and the plot size of a [Pass] are wanted. */
    private val EMPTY_COMPILED = CompiledSpec(null, emptyMap(), EMPTY_SIGNALS, emptyList())

    /**
     * The plotting area a specification that declares none asks for: none at all.
     *
     * Upstream's `parseView` seeds the `width` and `height` signals with `spec.width || 0`, so a
     * specification with no size is not an incomplete one — it is a chart measured entirely by what
     * it draws, which is how every faceted Vega-Lite chart is written. The cells carry their own
     * `child_width`, and a default plotting area behind them would be a whole phantom chart's worth
     * of surface: two hundred units of it, on the `faceted` fixture, past the last cell.
     *
     * Verified against upstream rather than assumed: a specification with one rect at (10, 10) and
     * no width renders 30 by 20 plus its padding, not 200 by 200.
     */
    public const val DEFAULT_WIDTH: Double = 0.0
    public const val DEFAULT_HEIGHT: Double = 0.0
  }
}
