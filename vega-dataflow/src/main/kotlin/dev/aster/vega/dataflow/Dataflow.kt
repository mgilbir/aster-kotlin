package dev.aster.vega.dataflow

import dev.aster.vega.model.InternalAsterVegaApi
import dev.aster.vega.model.VegaValue
import kotlin.jvm.JvmInline

/**
 * Dataflow API surface — **a contract with nothing behind it**, marked so it stops advertising
 * itself.
 *
 * Milestone 0 defined these shapes so the runtime and transforms could be written against a stable
 * one, and the operators were to arrive in Milestone 4 (PROJECT_BRIEF.md 20). They have not. There
 * is no incremental engine in this repository: every transform recomputes a whole dataset, and
 * every interaction recompiles the whole specification. Nothing outside this file's own tests
 * consumes a single declaration in it.
 *
 * That would be a harmless placeholder except for what [TupleId] says: that a tuple's identity is
 * "preserved across incremental updates so scene nodes, selections and accessibility focus survive
 * a data change". No code implements that, and an SDK consumer reading this file would have found a
 * promise rather than a plan. `@InternalAsterVegaApi` is the smaller of the audit's two suggestions
 * — the other was deletion — because the design is still the one Milestone 4 intends and the tests
 * still pin it; what it stops is the advertisement.
 */
@InternalAsterVegaApi
@JvmInline
public value class OperatorId(public val value: Int) {
  override fun toString(): String = "op$value"
}

/**
 * Stable identity for a data tuple, preserved across incremental updates so scene nodes, selections
 * and accessibility focus survive a data change (PROJECT_BRIEF.md 21).
 */
@InternalAsterVegaApi
@JvmInline
public value class TupleId(public val value: Long) {
  public companion object {
    public val None: TupleId = TupleId(0L)
  }
}

/** A datum plus its stable identity. */
@InternalAsterVegaApi
public data class Tuple(val id: TupleId, val value: VegaValue) {
  public fun withValue(newValue: VegaValue): Tuple = Tuple(id, newValue)
}

/**
 * Allocates tuple ids for one dataflow instance.
 *
 * Ids are sequential rather than hash-based so a rebuild of the same data produces the same ids and
 * snapshots stay comparable.
 */
@InternalAsterVegaApi
public class TupleIdAllocator(private var next: Long = 1L) {
  public fun allocate(): TupleId = TupleId(next++)

  /** Number of ids handed out so far; useful for asserting identity reuse in tests. */
  public val allocated: Long
    get() = next - 1L
}

/**
 * One propagation step's changes to a dataset.
 *
 * Modelling add/remove/modify explicitly — rather than passing whole lists around — is what lets a
 * later incremental implementation avoid recomputing everything, even though the first operators
 * may choose to.
 */
@InternalAsterVegaApi
public data class ChangeSet(
  val added: List<Tuple> = emptyList(),
  val removed: List<Tuple> = emptyList(),
  val modified: List<Tuple> = emptyList(),
  /** `true` when the receiver should discard prior state and treat [added] as the whole dataset. */
  val replacesAll: Boolean = false,
) {
  public val isEmpty: Boolean
    get() = added.isEmpty() && removed.isEmpty() && modified.isEmpty() && !replacesAll

  public fun merge(next: ChangeSet): ChangeSet {
    if (next.replacesAll) return next
    if (isEmpty) return next
    val removedIds = next.removed.map { it.id }.toSet()
    return ChangeSet(
      added = added.filterNot { it.id in removedIds } + next.added,
      removed =
        removed + next.removed.filterNot { removedTuple -> added.any { it.id == removedTuple.id } },
      modified = modified.filterNot { it.id in removedIds } + next.modified,
      replacesAll = replacesAll,
    )
  }

  public companion object {
    public val Empty: ChangeSet = ChangeSet()

    /** A change set that replaces the whole dataset. */
    public fun replaceAll(tuples: List<Tuple>): ChangeSet =
      ChangeSet(added = tuples, replacesAll = true)
  }
}

/**
 * Everything an operator may read while evaluating: signal values, named datasets and a place to
 * report problems.
 *
 * Passed in rather than reachable from global state, so an evaluation's inputs are always visible
 * at its call site.
 */
@InternalAsterVegaApi
public interface EvaluationContext {
  public fun signal(name: String): VegaValue

  public fun dataset(name: String): List<Tuple>

  public val tupleIds: TupleIdAllocator

  public val diagnostics: dev.aster.vega.model.DiagnosticCollector
}

/**
 * A single node in the dataflow graph.
 *
 * The initial implementation may recompute an operator's entire output, but the signature keeps the
 * door open for incremental evaluation (PROJECT_BRIEF.md 10.1).
 */
@InternalAsterVegaApi
public interface DataflowOperator<I, O> {
  public val id: OperatorId

  public val dependencies: Set<OperatorId>

  public fun evaluate(input: I, context: EvaluationContext): O
}

/**
 * Orders operators so every operator runs after its dependencies.
 *
 * Deterministic: operators with no ordering constraint between them keep their insertion order, so
 * two runs of the same graph evaluate in the same sequence.
 */
@InternalAsterVegaApi
public object DataflowScheduler {

  /**
   * @throws CyclicDataflowException when the dependency graph contains a cycle, naming the
   *   operators involved so the diagnostic can point at the offending signals.
   */
  public fun topologicalOrder(operators: List<DataflowOperator<*, *>>): List<OperatorId> {
    val byId = operators.associateBy { it.id }
    val visited = mutableSetOf<OperatorId>()
    val onStack = mutableSetOf<OperatorId>()
    val order = mutableListOf<OperatorId>()

    fun visit(id: OperatorId, path: List<OperatorId>) {
      if (id in visited) return
      if (!onStack.add(id)) throw CyclicDataflowException(path + id)
      // Dependencies on operators outside this list are treated as already-satisfied inputs.
      byId[id]?.dependencies?.sortedBy { it.value }?.forEach { visit(it, path + id) }
      onStack.remove(id)
      visited.add(id)
      order.add(id)
    }

    for (operator in operators) visit(operator.id, emptyList())
    return order
  }
}

@InternalAsterVegaApi
public class CyclicDataflowException(public val cycle: List<OperatorId>) :
  Exception("Dataflow cycle: ${cycle.joinToString(" -> ")}")
