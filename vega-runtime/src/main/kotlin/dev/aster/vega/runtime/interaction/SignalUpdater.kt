@file:OptIn(InternalAsterVegaApi::class)

package dev.aster.vega.runtime.interaction

import dev.aster.vega.expression.ExpressionCompiler
import dev.aster.vega.expression.ExpressionEvaluationException
import dev.aster.vega.expression.ExpressionResult
import dev.aster.vega.expression.ExpressionScope
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.InternalAsterVegaApi
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.spec.SignalUpdate
import dev.aster.vega.runtime.compile.ItemEncode
import dev.aster.vega.scene.SceneNodeId

/**
 * Applies fired handlers to the signals they set.
 *
 * The last step of the interaction chain, and the smallest, because measuring removed the hard part
 * of it: a full recompile of the heaviest fixture costs well under a frame, so a changed signal
 * simply means compiling the specification again with that signal pinned. No incremental dataflow.
 *
 * The values accumulate here rather than in the compiler, because
 * [dev.aster.vega.runtime.compile.SignalResolver] is deliberately stateless between calls — the
 * state of an interaction belongs to the thing being interacted with, not to the resolver.
 */
public class SignalUpdater(
  private val expressions: ExpressionCompiler,
  private val diagnostics: DiagnosticCollector,
) {

  private val values = LinkedHashMap<String, VegaValue>()

  /**
   * The same, for signals declared inside a group mark, by that group's path.
   *
   * Separate maps rather than one keyed by a qualified name, because the namespaces are genuinely
   * separate: a group may declare a `brush` while the chart declares another, and upstream gives
   * each its own value. Flattening them is how a group's brush would come to move the chart's.
   */
  private val scopedValues = LinkedHashMap<String, LinkedHashMap<String, VegaValue>>()

  private val encodes = LinkedHashMap<SceneNodeId, ItemEncode>()

  /**
   * Which items a handler has re-encoded, and through which of their mark's named blocks.
   *
   * Handed to the next compile beside [overrides], because the scene is rebuilt from the
   * specification each time and an overlay that was not handed back would last exactly one frame.
   * Everything already here is marked stale as the batch ends: the pass that *applies* an overlay
   * puts it after the mark's `update` and every pass after that puts it before, which is upstream's
   * behaviour — see [ItemEncode].
   */
  public val itemEncodes: Map<SceneNodeId, ItemEncode>
    get() = encodes

  /** Called once the compile that applied them has happened. */
  public fun ageItemEncodes() {
    for ((id, state) in encodes.entries.toList()) {
      if (state.fresh) encodes[id] = state.copy(fresh = false)
    }
  }

  /** Everything a handler has set so far, to be passed to the next compile as overrides. */
  public val overrides: Map<String, VegaValue>
    get() = values

  /** The same for signals inside a group mark, by path — see [overrides]. */
  @InternalAsterVegaApi
  public val scopedOverrides: Map<String, Map<String, VegaValue>>
    get() = scopedValues

  /**
   * Pins a signal to a value, as a **control** does rather than as a handler does.
   *
   * The same store a fired handler writes to, so everything downstream — the cascade, the next
   * compile's overrides — behaves identically whether a reader moved a slider or a tap fired a
   * handler. Which is the point: a binding is not a second way of changing a chart.
   */
  public fun set(name: String, value: VegaValue) {
    values[name] = value
  }

  public fun reset() {
    values.clear()
    scopedValues.clear()
    encodes.clear()
  }

  /**
   * @param scope the current signal values and datasets, which an update expression may read.
   * @return the signals whose value actually changed, so the caller can skip a recompile when
   *   nothing did. A handler marked `force` reports its signal as changed either way.
   */
  public fun apply(
    fired: List<FiredHandler>,
    scope: ExpressionScope,
    /**
     * The scope inside each group mark, for a handler declared in one.
     *
     * A handler in a group is evaluated against its group's signals *and scales* —
     * `invert('xOverview', brush)` is both at once — so handing it the chart's scope would read its
     * own signals as null and its own scale as missing.
     */
    scopes: Map<String, ExpressionScope> = emptyMap(),
  ): Set<String> {
    val changed = LinkedHashSet<String>()
    for (entry in fired) {
      val handler = entry.handler
      // `encode` needs no case of its own here: the parser rewrites it into
      // `encode(item(), '<set>')`, which is upstream's own desugaring, so it arrives as an ordinary
      // update expression whose side effect is recorded by [HandlerScope.encodeItem].
      val update = handler.update ?: continue
      // **Read** in the scope it was declared in; **write** in the scope it targets. The two are
      // the same except for a `push: "outer"` definition, which is how a group hands a value back
      // out — and conflating them is subtle rather than obvious: the update reads its own scope's
      // pending values, so a pushed handler that read the target's store would look for the
      // group's `brushed` among the chart's signals and find nothing.
      val within = if (entry.scopePath.isEmpty()) scope else scopes[entry.scopePath] ?: scope
      val target = entry.writePath ?: entry.scopePath
      val readStore = storeFor(entry.scopePath)
      val writeStore = storeFor(target)
      val next = evaluate(update, entry, within, readStore) ?: continue
      // The previous value comes from the scope being *written*: a pushed signal compares against
      // the outer value it is about to replace, not against anything of the group's.
      val outward = if (target.isEmpty()) scope else scopes[target] ?: scope
      val previous = writeStore[entry.signalName] ?: outward.signal(entry.signalName)
      // `force` re-runs everything downstream even when the value is unchanged — needed when the
      // value is an object mutated in place, where equality would say nothing had happened.
      if (handler.force || next != previous) {
        writeStore[entry.signalName] = next
        // Qualified, so a group's `brush` and the chart's are not reported as one signal changing.
        changed += if (target.isEmpty()) entry.signalName else "$target/${entry.signalName}"
      }
    }
    return changed
  }

  /** The accumulated values for one scope: the chart's own, or a group's by path. */
  private fun storeFor(path: String): LinkedHashMap<String, VegaValue> =
    if (path.isEmpty()) values else scopedValues.getOrPut(path) { LinkedHashMap() }

  private fun evaluate(
    update: SignalUpdate,
    entry: FiredHandler,
    scope: ExpressionScope,
    /** The store this handler's scope reads first — its group's, or the chart's. */
    store: Map<String, VegaValue>,
  ): VegaValue? =
    when (update) {
      is SignalUpdate.Constant -> update.value
      is SignalUpdate.Reference -> store[update.name] ?: scope.signal(update.name)
      is SignalUpdate.Expression -> {
        when (val compiled = expressions.compile(update.expr)) {
          is ExpressionResult.Failed -> {
            diagnostics.add(compiled.diagnostic.copy(operator = entry.signalName))
            null
          }
          is ExpressionResult.Compiled ->
            try {
              // `store`, not `values`: what an earlier handler in this batch set is only visible
              // to a handler in the *same* scope. `brush` set by the press must be readable by the
              // drag that clamps against it, and must not be readable as the chart's `brush`.
              compiled.expression.evaluate(HandlerScope(scope, store, entry))
            } catch (failure: ExpressionEvaluationException) {
              diagnostics.add(failure.diagnostic.copy(operator = entry.signalName))
              null
            }
        }
      }
    }

  /**
   * What an update expression can see: the specification's own scope, plus `event`, plus anything
   * an earlier handler in the same batch already set.
   *
   * `datum` is the datum of the mark the event landed on, which is what makes `{"events":
   * "rect:click", "update": "datum.category"}` — the commonest handler there is — work.
   */
  private inner class HandlerScope(
    private val delegate: ExpressionScope,
    private val pending: Map<String, VegaValue>,
    private val entry: FiredHandler,
  ) : ExpressionScope by delegate {

    private val event = entry.event?.asValue() ?: VegaValue.Null

    override val datum: VegaValue
      get() = entry.event?.datum ?: VegaValue.Null

    override fun signal(name: String): VegaValue =
      when (name) {
        "event" -> event
        else -> pending[name] ?: delegate.signal(name)
      }

    override fun dataset(name: String): List<VegaValue> = delegate.dataset(name)

    /**
     * `encode(item, set)` — the item is overlaid with one of its mark's named blocks.
     *
     * Recorded rather than applied: this class produces values, and the overlay is a property of a
     * scene node the compiler owns. The **event's** item is the one meant, which is why the id
     * comes off the event rather than out of the value handed in — upstream's `item()` is the only
     * way a handler expression can name an item at all.
     */
    override fun encodeItem(item: VegaValue, set: String) {
      val id = entry.event?.itemId ?: return
      encodes[id] = ItemEncode(set, fresh = true)
    }

    /** `item()` — upstream's `item || {}`, so a handler firing off a mark still has an object. */
    override fun activeItem(): VegaValue =
      (event as? VegaValue.Obj)?.fields?.get("item") ?: VegaValue.EmptyObject

    /** `xy()`, and through it `x()` and `y()`; empty for a handler no event fired. */
    override fun eventPoint(): VegaValue =
      entry.event?.let {
        VegaValue.Arr(listOf(VegaValue.Num(it.rootX), VegaValue.Num(it.rootY)))
      } ?: VegaValue.Null
  }
}
