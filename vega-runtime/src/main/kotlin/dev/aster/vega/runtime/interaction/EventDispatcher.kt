package dev.aster.vega.runtime.interaction

import dev.aster.vega.expression.ExpressionCompiler
import dev.aster.vega.expression.ExpressionEvaluationException
import dev.aster.vega.expression.ExpressionResult
import dev.aster.vega.expression.ExpressionScope
import dev.aster.vega.expression.JsSemantics
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue
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
) {
  /** The `event` object a filter expression sees. */
  internal fun asValue(): VegaValue {
    val fields = LinkedHashMap<String, VegaValue>(properties.size + 4)
    fields.putAll(properties)
    fields["type"] = VegaValue.Str(type)
    fields["x"] = VegaValue.Num(x)
    fields["y"] = VegaValue.Num(y)
    fields["timeStamp"] = VegaValue.Num(timestampMillis.toDouble())
    if (markType != null || markName != null || datum !is VegaValue.Null) {
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
)

/** One `on` handler, bound to the signal it sets. */
public data class HandlerBinding(val signalName: String, val handler: SignalHandler)

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
) {

  /** One stream being watched, with whatever state it needs between events. */
  private class Watch(
    val stream: EventStream,
    val binding: HandlerBinding?,
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

  /** The gate a stream must pass, or null if it has none. */
  private val gateOf = HashMap<Watch, Gate>()

  private val watches = mutableListOf<Watch>()

  /** Gate-opening and gate-closing streams, tested before anything they gate. */
  private val gateWatches = mutableListOf<Watch>()

  init {
    for (binding in bindings) {
      for (stream in binding.handler.streams) register(stream, binding)
    }
  }

  private fun register(stream: EventStream, binding: HandlerBinding) {
    if (!permitted(stream, binding)) return
    if (stream.nested != null) {
      // A pair wrapping a pair. Honouring it means gating a gate, which the latch model can do,
      // but nothing in the corpus uses it and guessing at the ordering would be worse than saying
      // so.
      diagnostics.warn(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "A 'between' selector wrapping another 'between' is not dispatched; signal " +
          "'${binding.signalName}' will not update from it",
        operator = binding.signalName,
      )
      return
    }
    val watch = Watch(stream, binding)
    if (stream.between.size == 2) {
      val gate = Gate()
      gateOf[watch] = gate
      gateWatches += Watch(stream.between[0], null, opens = mutableListOf(gate))
      gateWatches += Watch(stream.between[1], null, closes = mutableListOf(gate))
    }
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
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "A timer stream needs a clock to fire it; signal '${binding.signalName}' will keep its " +
          "initial value",
        operator = binding.signalName,
      )
      return
    }
    if (stream.debounce != null && !deferrable) {
      // A debounce fires *after* a quiet period, so honouring it needs something that can wake up
      // later. Nothing here schedules, and silently treating it as a throttle would fire on the
      // leading edge instead of the trailing one — the opposite behaviour.
      diagnostics.warn(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "A debounce needs a scheduler to fire after the quiet period; signal " +
          "'${binding.signalName}' will fire on every matching event instead",
        operator = binding.signalName,
      )
    }
    watches += watch
  }

  /** @return the handlers this event fired, in registration order. */
  public fun dispatch(event: InputEvent): List<FiredHandler> {
    for (gateWatch in gateWatches) {
      if (!matches(gateWatch.stream, event)) continue
      for (gate in gateWatch.opens) gate.open = true
      for (gate in gateWatch.closes) gate.open = false
    }

    val fired = mutableListOf<FiredHandler>()
    for (watch in watches) {
      val binding = watch.binding ?: continue
      if (!matches(watch.stream, event)) continue
      if (gateOf[watch]?.open == false) continue
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
      DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
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

  private fun matches(stream: EventStream, event: InputEvent): Boolean {
    if (stream.type != null && stream.type != event.type) return false
    if (stream.source != event.source) return false
    // `*` matches any mark, but still requires that the event landed on one.
    if (stream.markType != null) {
      if (event.markType == null) return false
      if (stream.markType != "*" && stream.markType != event.markType) return false
    }
    if (stream.markName != null && stream.markName != event.markName) return false
    return stream.filters.all { passes(it, event) }
  }

  /**
   * A filter that cannot be read or cannot be evaluated **suppresses** the event.
   *
   * The alternative — treating a broken filter as absent — would fire the handler on every event of
   * that type, which is the loudest possible failure. Not firing is quieter and is reported.
   */
  private fun passes(filter: String, event: InputEvent): Boolean {
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
    ExpressionScope {
    override val datum: VegaValue
      get() = delegate.datum

    override fun signal(name: String): VegaValue =
      if (name == "event") event else delegate.signal(name)

    override fun dataset(name: String): List<VegaValue> = delegate.dataset(name)
  }
}
