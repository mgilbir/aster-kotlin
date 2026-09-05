@file:OptIn(InternalAsterVegaApi::class)

package dev.aster.vega.runtime.interaction

import dev.aster.vega.expression.ExpressionCompiler
import dev.aster.vega.expression.ExpressionEvaluationException
import dev.aster.vega.expression.ExpressionResult
import dev.aster.vega.expression.ExpressionScope
import dev.aster.vega.expression.JsSemantics
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.InternalAsterVegaApi
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.isNullish
import dev.aster.vega.model.spec.EventConfig
import dev.aster.vega.model.spec.EventPermit
import dev.aster.vega.model.spec.EventStream
import dev.aster.vega.model.spec.SignalHandler
import dev.aster.vega.scene.SceneNodeId

/**
 * An input event, in terms the engine can reason about.
 *
 * Deliberately not an Android `MotionEvent` or anything like one: the core stays portable, and the
 * host translates whatever it has into this. [timestampMillis] is supplied rather than read from a
 * clock for the same reason — and because a throttle that consults the wall clock cannot be tested.
 *
 * [properties] carries whatever `event.something` a filter might ask for: `shiftKey`, `button`,
 * `key`, and so on. An absent one reads as null, which is falsy, so a filter on a modifier the host
 * does not report simply never passes rather than failing.
 */
public data class InputEvent(
  val type: String,
  val timestampMillis: Long,
  val source: String = EventStream.SOURCE_VIEW,
  /**
   * The scene node the event landed on, when it landed on one.
   *
   * What `encode(item(), 'select')` needs: a handler's `encode` overlays a block on **one** item,
   * and the id is how that item is found again after the scene has been rebuilt.
   */
  val itemId: SceneNodeId? = null,
  /** The mark instance under the pointer, if the event landed on one. */
  val markType: String? = null,
  val markName: String? = null,
  /** The datum of the mark under the pointer, for `event.item.datum`. */
  val datum: VegaValue = VegaValue.Null,
  val x: Double = 0.0,
  val y: Double = 0.0,
  /**
   * The same position in the **root frame's** own coordinates, which is what `x()`, `y()` and
   * `xy()` answer.
   *
   * Distinct from [x] and [y] on purpose: those are the raw pointer position as the platform
   * reported it, which is what upstream's `event.x` carries, while this has the chart's padding and
   * autosize origin taken off — upstream's `offset(view)` — so it is in the space the marks are
   * placed in. A brush written `[x(), x()]` reads this one, and reading the other would put it out
   * by the padding.
   */
  val rootX: Double = 0.0,
  val rootY: Double = 0.0,
  val properties: Map<String, VegaValue> = emptyMap(),
  /**
   * The group scopes this event is **in**, by the paths `CompiledSpec.groupNodes` uses.
   *
   * What a `scope`-sourced stream needs. A handler declared in a group listens on that group's own
   * item — upstream attaches the listener there — so `{"events": "mousedown"}` inside a group means
   * "a mousedown anywhere in this group".
   *
   * "In" is upstream's `inScope(event.item)`: the ancestors of the item that was hit, walked
   * upwards. Not containment of the group's rectangle, which answers differently in both directions
   * — a press on an unfilled group's background is inside the rectangle and hits no item, and a
   * mark overflowing an unclipped group is outside it and still the group's.
   *
   * Computed by the controller, which is the only place holding the scene and the point at once.
   * Empty for an event that hit nothing, and for one with no position at all, which is every key.
   */
  @InternalAsterVegaApi val scopes: Set<String> = emptySet(),
) {
  /** The `event` object a filter expression sees. */
  internal fun asValue(): VegaValue {
    val fields = LinkedHashMap<String, VegaValue>(properties.size + 4)
    fields.putAll(properties)
    fields["type"] = VegaValue.Str(type)
    fields["x"] = VegaValue.Num(x)
    fields["y"] = VegaValue.Num(y)
    fields["timeStamp"] = VegaValue.Num(timestampMillis.toDouble())
    if (markType != null || markName != null || !datum.isNullish) {
      val mark = LinkedHashMap<String, VegaValue>(2)
      markType?.let { mark["marktype"] = VegaValue.Str(it) }
      markName?.let { mark["name"] = VegaValue.Str(it) }
      fields["item"] = VegaValue.Obj(mapOf("mark" to VegaValue.Obj(mark), "datum" to datum))
    }
    return VegaValue.Obj(fields)
  }
}

/** A handler that fired, and the event that fired it. */
public data class FiredHandler(
  val signalName: String,
  val handler: SignalHandler,
  /**
   * The event that fired it, or null when nothing did.
   *
   * A handler whose source is another **signal** — `{"events": {"signal": "width"}}` — is fired by
   * that signal changing, so there is no event to read and `event` is absent from its update
   * expression, exactly as upstream leaves it undefined.
   */
  val event: InputEvent? = null,
  /**
   * How long to wait before applying this, when the stream carries a `debounce`.
   *
   * Null for everything else, which is almost everything. The dispatcher decides *what* matched and
   * says how long it should be held for; the waiting itself belongs to whoever has a clock, which
   * is the controller and its [Scheduler]. Keeping the two apart is what leaves this class testable
   * without one.
   */
  val deferByMillis: Double? = null,
  /**
   * The group this handler was declared in, as a path, or `""` for the specification's top level.
   *
   * Carried rather than derived because the two namespaces are separate: a group may declare a
   * `brush` while the chart declares another, and they are different signals with different values.
   * The paths are `CompiledSpec.groupScopes`' paths.
   */
  @InternalAsterVegaApi val scopePath: String = "",
  /**
   * The scope whose signal this handler **writes**, when that is not the one it reads.
   *
   * Only a `push: "outer"` definition differs: it reads the group's own scope and writes the
   * enclosing one. Null everywhere else, meaning "the scope it was declared in".
   */
  @InternalAsterVegaApi val writePath: String? = null,
)

/** One `on` handler, bound to the signal it sets. */
public data class HandlerBinding(
  val signalName: String,
  val handler: SignalHandler,
  /**
   * The group this handler was declared in, as a path, or `""` for the specification's top level.
   *
   * Carried rather than derived because the two namespaces are separate: a group may declare a
   * `brush` while the chart declares another, and they are different signals with different values.
   * The paths are `CompiledSpec.groupScopes`' paths.
   */
  @InternalAsterVegaApi val scopePath: String = "",
  /**
   * The scope whose signal this handler **writes**, when that is not the one it reads.
   *
   * Only a `push: "outer"` definition differs: it reads the group's own scope and writes the
   * enclosing one. Null everywhere else, meaning "the scope it was declared in".
   */
  @InternalAsterVegaApi val writePath: String? = null,
)

/**
 * Matches arriving events against the streams a specification registered.
 *
 * This is the half of the interaction system that can be built and tested away from a device: given
 * an event, decide which handlers fire. What it deliberately does **not** do is apply the update —
 * that needs the signal graph re-run, which is the next piece.
 *
 * Three behaviours are worth knowing, all matched to upstream:
 *
 * **`between` is a latch, not a queue.** `[mousedown, mouseup] > mousemove` is implemented as a
 * boolean turned on by the first stream and off by the second; the gated stream fires while it is
 * on. So a `mouseup` that never arrives leaves the gate open indefinitely, which is what makes a
 * drag survive the pointer leaving the chart — and also why a lost `mouseup` leaves it stuck.
 *
 * **Gates are updated before gated streams are tested.** When one event could both close a gate and
 * fire the stream it gates, the gate wins. Upstream's ordering here follows from the order streams
 * were registered and is not stated anywhere; this fixes it, because leaving it to registration
 * order would make the behaviour depend on how the specification was written.
 *
 * **A consumed event stops there.** A stream marked `!` fires and no later stream sees the event,
 * which is how a mark handler stops the view-level one behind it from also firing.
 */
public class EventDispatcher(
  bindings: List<HandlerBinding>,
  private val expressions: ExpressionCompiler,
  private val diagnostics: DiagnosticCollector,
  /** Signals and datasets a filter expression may read, alongside `event`. */
  private val scope: ExpressionScope,
  /**
   * `config.events` — the embedder's policy on which listeners this view may attach.
   *
   * Enforced *here* because this is the only place a listener comes into being. A specification
   * asking for `window:mousemove` is asking to watch the pointer across the whole page, and a host
   * that says no needs the request refused rather than honoured quietly.
   */
  private val events: EventConfig = EventConfig(),
  /**
   * Whether the caller can wait: whether it holds a [Scheduler].
   *
   * Only changes what is *reported*. A debounce and a timer stream are honoured by whoever has the
   * clock, so with one in hand there is nothing to warn about; with none, both still say what they
   * cannot do.
   */
  private val deferrable: Boolean = false,
  /**
   * The scope inside each group mark, by path, for a handler declared in one.
   *
   * Only the *filters* are evaluated here — the update expression is applied by `SignalUpdater`,
   * which is handed the same scope — but a filter is where a suppressed event would be hardest to
   * notice, since nothing fires and nothing is reported.
   */
  private val scopes: Map<String, ExpressionScope> = emptyMap(),
) {

  /** One stream being watched, with whatever state it needs between events. */
  private class Watch(
    val stream: EventStream,
    val binding: HandlerBinding?,
    /**
     * The scope whose signals this stream's **filters** read.
     *
     * Carried on the watch rather than taken from [binding], because a gate watch has no binding
     * and still belongs to one: `[@overview:pointerdown, window:pointerup]` is registered from a
     * handler declared inside the overview group, and a filter on either half reads that group's
     * signals. Leaving it at the top level would read a group's own signal as null and suppress the
     * event, which is the quiet failure this whole change is about.
     */
    val scopePath: String = "",
    /** Streams whose gate this one opens, and those whose gate it closes. */
    val opens: MutableList<Gate> = mutableListOf(),
    val closes: MutableList<Gate> = mutableListOf(),
  ) {
    /** Null until the stream has fired once. A sentinel would overflow the subtraction. */
    var lastFired: Long? = null
  }

  private class Gate {
    var open: Boolean = false
  }

  /**
   * The gates a stream must pass, all of them open, or absent if it has none.
   *
   * A **list** because a `between` composes: `[a, b] > [c, d] > mousemove` is upstream's
   * `stream.between(c, d).between(a, b)`, and `between` is a latch plus a filter — so an event
   * fires only where every latch in the chain is open. There is no ordering between them to get
   * wrong, which is what makes the chain expressible at all.
   */
  private val gateOf = HashMap<Watch, List<Gate>>()

  private val watches = mutableListOf<Watch>()

  /** Gate-opening and gate-closing streams, tested before anything they gate. */
  private val gateWatches = mutableListOf<Watch>()

  init {
    for (binding in bindings) {
      for (stream in binding.handler.streams) register(stream, binding)
    }
  }

  private fun register(declared: EventStream, binding: HandlerBinding) {
    // **The chain, resolved first**, because everything below asks what this stream *listens to*
    // and a wrapper does not know. A `between` composes: `[a, b] > [c, d] > mousemove` arrives as
    // two wrappers around one ordinary stream, and only the innermost carries a source, a type and
    // a mark. Asking the outer one whether it is a timer, a `window:` stream or a `keyup` gets the
    // defaults every time.
    //
    // What each wrapper adds is a gate the event has to pass. Upstream composes these as
    // `stream.between(c, d).between(a, b)`, and `between` is a latch plus a filter — so "gating a
    // gate" is just "every latch open". This was refused by name until the composition was read
    // rather than guessed at: the diagnostic said the ordering could not be known, and there is no
    // ordering, since each latch is opened and closed by its own pair independently of the others.
    val chain = generateSequence(declared) { it.nested }.toList()
    val listened = chain.last()
    // A wrapper's own filter, throttle, debounce and `consume` apply to the composed stream, so
    // they are merged onto the one that does the matching. Two throttles in a chain would be two
    // limits in series and are **not** modelled: the outermost wins, which is upstream's
    // last-applied.
    val stream =
      if (chain.size == 1) {
        declared
      } else {
        listened.copy(
          filters = chain.flatMap { it.filters },
          throttle = chain.firstNotNullOfOrNull { it.throttle },
          debounce = chain.firstNotNullOfOrNull { it.debounce },
          consume = chain.any { it.consume },
        )
      }
    if (!permitted(stream, binding)) return
    // `keyup` and `keypress` are events upstream's handler binds and this engine cannot produce: a
    // host reports one `ChartInputEvent.Key` per press, with no phase, so there is nothing to tell
    // a release from a repeat. Refused by name rather than left to never match, because a signal
    // that never updates looks exactly like one whose expression is wrong.
    if (stream.type == "keyup" || stream.type == "keypress") {
      diagnostics.warn(
        DiagnosticCodes.INTERACTION_UNSUPPORTED,
        "Signal '${binding.signalName}' listens to '${stream.type}', which this engine does not " +
          "produce: a host reports one key event per press and nothing distinguishes a release " +
          "from a repeat. Use 'keydown', which is dispatched",
        operator = binding.signalName,
      )
      return
    }
    val watch = Watch(stream, binding, binding.scopePath)
    val gates =
      chain
        .filter { it.between.size == 2 }
        .map { link ->
          val gate = Gate()
          gateWatches +=
            Watch(link.between[0], null, binding.scopePath, opens = mutableListOf(gate))
          gateWatches +=
            Watch(link.between[1], null, binding.scopePath, closes = mutableListOf(gate))
          gate
        }
    if (gates.isNotEmpty()) gateOf[watch] = gates
    if (stream.source == EventStream.SOURCE_TIMER && deferrable) {
      // Someone else has the clock; the controller starts it and this stream is not dispatched from
      // an input event at all.
      return
    }
    if (stream.source == EventStream.SOURCE_TIMER) {
      // A timer fires on its own rather than in response to anything, so honouring it needs a clock
      // this class does not have — `dispatch` is only ever called with an event that already
      // happened. Reported rather than dropped, because a signal driven by a timer is one that
      // never changes here and the reason is not visible from the drawing.
      diagnostics.warn(
        DiagnosticCodes.INTERACTION_UNSUPPORTED,
        "A timer stream needs a clock to fire it; signal '${binding.signalName}' will keep its " +
          "initial value",
        operator = binding.signalName,
      )
      return
    }
    if (stream.source == EventStream.SOURCE_WINDOW) {
      // A `window:` stream listens to the **page**, and a chart drawn on a canvas has none. The
      // watch is still registered — this class will dispatch one, and `EventDispatcherTest` proves
      // it, so a host driving the dispatcher directly can deliver a window event itself — but
      // nothing in `VegaChartController` ever *produces* one. So a specification that uses the
      // commonest idiom for it, `window:mousemove` for a drag that continues outside the chart,
      // gets a signal that never changes, and said nothing: exactly the shape of failure this
      // class reports three times in the twenty lines below.
      diagnostics.warn(
        DiagnosticCodes.INTERACTION_UNSUPPORTED,
        "A 'window:' stream listens to the page, and this engine draws on a canvas rather than in " +
          "one; nothing dispatches a window-sourced event, so signal '${binding.signalName}' will " +
          "keep its initial value unless the host dispatches one itself",
        operator = binding.signalName,
      )
    }
    if (stream.debounce != null && !deferrable) {
      // A debounce fires *after* a quiet period, so honouring it needs something that can wake up
      // later. Nothing here schedules, and silently treating it as a throttle would fire on the
      // leading edge instead of the trailing one — the opposite behaviour.
      diagnostics.warn(
        DiagnosticCodes.INTERACTION_UNSUPPORTED,
        "A debounce needs a scheduler to fire after the quiet period; signal " +
          "'${binding.signalName}' will fire on every matching event instead",
        operator = binding.signalName,
      )
    }
    watches += watch
  }

  /**
   * A `scope` stream's source for matching: `view`.
   *
   * Upstream's own `eventSource` does exactly this rewrite before binding the listener — a scope
   * stream *is* a view listener, and what makes it the group's is the `inScope` filter it also
   * carries. [matches] applies that filter, so nothing is widened by this.
   */
  private fun sourceOf(stream: EventStream): String =
    if (stream.source == EventStream.SOURCE_SCOPE) EventStream.SOURCE_VIEW else stream.source

  /** The scope a stream's filters read: its group's, or the chart's for a top-level handler. */
  private fun scopeFor(path: String): ExpressionScope =
    if (path.isEmpty()) scope else scopes[path] ?: scope

  public fun dispatch(event: InputEvent): List<FiredHandler> {
    for (gateWatch in gateWatches) {
      if (!matches(gateWatch.stream, event, scopeFor(gateWatch.scopePath), gateWatch.scopePath)) {
        continue
      }
      for (gate in gateWatch.opens) gate.open = true
      for (gate in gateWatch.closes) gate.open = false
    }

    val fired = mutableListOf<FiredHandler>()
    for (watch in watches) {
      val binding = watch.binding ?: continue
      if (!matches(watch.stream, event, scopeFor(watch.scopePath), watch.scopePath)) continue
      if (gateOf[watch]?.any { !it.open } == true) continue
      val throttle = watch.stream.throttle
      val since = watch.lastFired
      if (throttle != null && since != null && event.timestampMillis - since < throttle) continue
      watch.lastFired = event.timestampMillis
      fired +=
        FiredHandler(
          binding.signalName,
          binding.handler,
          event,
          // Held rather than applied when the stream asked to be, and the caller can wait.
          deferByMillis = if (deferrable) watch.stream.debounce else null,
          // Carried through so the value lands in the scope that declared the signal rather than
          // in the chart's, which for a same-named signal is a different one.
          scopePath = binding.scopePath,
          writePath = binding.writePath,
        )
      // `!` on the type: this stream consumed the event, so nothing after it sees it.
      if (watch.stream.consume) break
    }
    return fired
  }

  /**
   * Whether the policy allows a listener on this stream's source, upstream's `permit`.
   *
   * A rule of `false` blocks the source outright; a **list** is an allow-list, so a type it does
   * not name is refused. Upstream warns and carries on rather than failing the chart, which is the
   * right shape for a policy: the rest of the specification still draws.
   *
   * A stream nested inside a `between` pair is checked too — the gate needs a listener of its own,
   * and a policy that blocked the outer source while the gate watched it anyway would be no policy
   * at all.
   */
  private fun permitted(stream: EventStream, binding: HandlerBinding): Boolean {
    val key = permitKey(stream.source)
    val rule =
      when (key) {
        EventStream.SOURCE_WINDOW -> events.window
        EventStream.SOURCE_VIEW -> events.view
        EventStream.SOURCE_TIMER -> events.timer
        else -> events.selector
      }
    val type = stream.type ?: return true
    val blocked =
      when (rule) {
        is EventPermit.Unrestricted -> false
        is EventPermit.All -> !rule.value
        is EventPermit.Types -> type !in rule.types
      }
    if (!blocked) return true
    diagnostics.warn(
      DiagnosticCodes.INTERACTION_UNSUPPORTED,
      "Blocked $key $type event listener; 'config.events' does not permit it, so signal " +
        "'${binding.signalName}' will not update from it",
      operator = binding.signalName,
    )
    return false
  }

  /**
   * Which of the four policy keys a source is governed by.
   *
   * A `scope` stream listens on the view and filters down to one group, so it is a `view` listener
   * as far as the policy is concerned — upstream's `eventSource` rewrites it to `view` before the
   * permit is tested. A mark type or `@name` is the same: those are view listeners with a target.
   * Anything else is a CSS selector naming an element outside the chart, which is the `selector`
   * key.
   */
  private fun permitKey(source: String): String =
    when (source) {
      EventStream.SOURCE_WINDOW -> EventStream.SOURCE_WINDOW
      EventStream.SOURCE_TIMER -> EventStream.SOURCE_TIMER
      EventStream.SOURCE_SCOPE -> EventStream.SOURCE_VIEW
      EventStream.SOURCE_VIEW -> EventStream.SOURCE_VIEW
      // Anything left is a CSS selector naming an element outside the chart. A mark selector never
      // arrives here: `rect:click` and `@bars:mousedown` keep the view as their source and carry
      // the
      // mark as a target.
      else -> "selector"
    }

  private fun matches(
    stream: EventStream,
    event: InputEvent,
    scope: ExpressionScope = this.scope,
    /** The group this stream belongs to, for a `scope` stream to test containment against. */
    scopePath: String = "",
  ): Boolean {
    if (stream.type != null && stream.type != event.type) return false
    // A stream declared inside a group has source `scope` rather than `view`: upstream attaches the
    // listener to that group's own item rather than to the whole view. The listener itself is on
    // the
    // view either way — upstream's `eventSource` rewrites `scope` to `view` before binding — and
    // what makes it a group's listener is the filter below, so the source is compared as `view`.
    if (sourceOf(stream) != event.source) return false
    // `*` matches any mark, but still requires that the event landed on one.
    if (stream.markType != null) {
      if (event.markType == null) return false
      if (stream.markType != "*" && stream.markType != event.markType) return false
    }
    if (stream.markName != null && stream.markName != event.markName) return false
    // A `scope` stream fires only for an event **inside its own group**, which is what upstream's
    // `inScope(event.item)` says: `parseStream` appends that filter to every stream whose source is
    // `scope`, and `parseSelector` gives that source to every selector written inside a subscope.
    // So this applies to `@box:mousedown` exactly as much as to a bare `mousedown` — naming a mark
    // narrows *which* item, not *which group's copy of it*, and a faceted group has one copy per
    // cell with the same name in each.
    //
    // These used to be refused by name, because "which group did this land in" needed the scene and
    // the dispatcher has neither the scene nor the point in world space. The controller answers it
    // and hands the scopes over on the event, so the test here is a set membership.
    //
    // Never widened to the whole view: an event that landed in no group is in no scope, so a
    // group's
    // handler cannot fire on an event outside it.
    if (stream.source == EventStream.SOURCE_SCOPE && scopePath.isNotEmpty()) {
      if (scopePath !in event.scopes) return false
    }
    return stream.filters.all { passes(it, event, scope) }
  }

  /**
   * A filter that cannot be read or cannot be evaluated **suppresses** the event.
   *
   * The alternative — treating a broken filter as absent — would fire the handler on every event of
   * that type, which is the loudest possible failure. Not firing is quieter and is reported.
   */
  private fun passes(filter: String, event: InputEvent, scope: ExpressionScope): Boolean {
    val compiled = expressions.compile(filter)
    if (compiled !is ExpressionResult.Compiled) {
      diagnostics.error(
        DiagnosticCodes.EXPRESSION_PARSE_ERROR,
        "Could not read the event filter '$filter'; no event will pass it",
        operator = "event filter",
      )
      return false
    }
    return try {
      JsSemantics.truthy(compiled.expression.evaluate(EventScope(scope, event.asValue())))
    } catch (failure: ExpressionEvaluationException) {
      diagnostics.error(
        DiagnosticCodes.EXPRESSION_PARSE_ERROR,
        "The event filter '$filter' could not be evaluated (${failure.message}); no event " +
          "passed it",
        operator = "event filter",
      )
      false
    }
  }

  /** The specification's own scope, with `event` added on top. */
  private class EventScope(private val delegate: ExpressionScope, private val event: VegaValue) :
    ExpressionScope by delegate {
    override val datum: VegaValue
      get() = delegate.datum

    override fun signal(name: String): VegaValue =
      if (name == "event") event else delegate.signal(name)

    override fun dataset(name: String): List<VegaValue> = delegate.dataset(name)
  }
}
