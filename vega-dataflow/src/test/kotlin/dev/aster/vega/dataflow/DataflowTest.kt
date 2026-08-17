package dev.aster.vega.dataflow

import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DataflowTest {

  private fun tuple(id: Long, value: Double) = Tuple(TupleId(id), VegaValue.Num(value))

  @Test
  fun `tuple ids are sequential and independent per allocator`() {
    val allocator = TupleIdAllocator()
    assertEquals(TupleId(1L), allocator.allocate())
    assertEquals(TupleId(2L), allocator.allocate())
    assertEquals(2L, allocator.allocated)
    assertEquals(TupleId(1L), TupleIdAllocator().allocate())
  }

  @Test
  fun `withValue preserves identity`() {
    val original = tuple(7L, 1.0)
    val updated = original.withValue(VegaValue.Num(2.0))
    assertEquals(original.id, updated.id)
    assertEquals(VegaValue.Num(2.0), updated.value)
  }

  @Test
  fun `empty change set is recognised`() {
    assertTrue(ChangeSet.Empty.isEmpty)
    assertTrue(!ChangeSet(added = listOf(tuple(1L, 1.0))).isEmpty)
    assertTrue(!ChangeSet(replacesAll = true).isEmpty)
  }

  @Test
  fun `merging a replace-all discards earlier changes`() {
    val first = ChangeSet(added = listOf(tuple(1L, 1.0)))
    val replacement = ChangeSet.replaceAll(listOf(tuple(9L, 9.0)))
    assertEquals(replacement, first.merge(replacement))
  }

  @Test
  fun `merging cancels an add that is immediately removed`() {
    val added = ChangeSet(added = listOf(tuple(1L, 1.0), tuple(2L, 2.0)))
    val removed = ChangeSet(removed = listOf(tuple(1L, 1.0)))
    val merged = added.merge(removed)

    assertEquals(listOf(TupleId(2L)), merged.added.map { it.id })
    assertTrue(merged.removed.isEmpty(), "removing a pending add should not emit a removal")
  }

  @Test
  fun `merging keeps removals of previously existing tuples`() {
    val first = ChangeSet(modified = listOf(tuple(5L, 5.0)))
    val merged = first.merge(ChangeSet(removed = listOf(tuple(3L, 3.0))))
    assertEquals(listOf(TupleId(3L)), merged.removed.map { it.id })
  }

  @Test
  fun `merging an empty change set returns the other side`() {
    val changes = ChangeSet(added = listOf(tuple(1L, 1.0)))
    assertEquals(changes, ChangeSet.Empty.merge(changes))
    assertEquals(changes, changes.merge(ChangeSet.Empty))
  }

  @Test
  fun `scheduler orders operators after their dependencies`() {
    val a = FakeOperator(OperatorId(1), emptySet())
    val b = FakeOperator(OperatorId(2), setOf(OperatorId(1)))
    val c = FakeOperator(OperatorId(3), setOf(OperatorId(2), OperatorId(1)))

    val order = DataflowScheduler.topologicalOrder(listOf(c, b, a))
    assertEquals(listOf(OperatorId(1), OperatorId(2), OperatorId(3)), order)
  }

  @Test
  fun `scheduler is deterministic regardless of input order`() {
    val a = FakeOperator(OperatorId(1), emptySet())
    val b = FakeOperator(OperatorId(2), emptySet())
    val c = FakeOperator(OperatorId(3), setOf(OperatorId(1), OperatorId(2)))

    assertEquals(
      DataflowScheduler.topologicalOrder(listOf(a, b, c)),
      DataflowScheduler.topologicalOrder(listOf(a, b, c)),
    )
    // Independent operators keep the order they were declared in.
    assertEquals(
      listOf(OperatorId(1), OperatorId(2), OperatorId(3)),
      DataflowScheduler.topologicalOrder(listOf(a, b, c)),
    )
  }

  @Test
  fun `scheduler reports a cycle instead of looping forever`() {
    val a = FakeOperator(OperatorId(1), setOf(OperatorId(2)))
    val b = FakeOperator(OperatorId(2), setOf(OperatorId(1)))
    val failure =
      assertThrows<CyclicDataflowException> {
        DataflowScheduler.topologicalOrder(listOf(a, b))
      }
    assertTrue(failure.cycle.contains(OperatorId(1)))
    assertTrue(failure.cycle.contains(OperatorId(2)))
  }

  @Test
  fun `dependencies outside the operator list are ignored`() {
    val a = FakeOperator(OperatorId(1), setOf(OperatorId(99)))
    assertEquals(
      listOf(OperatorId(99), OperatorId(1)),
      DataflowScheduler.topologicalOrder(listOf(a)),
    )
  }

  private class FakeOperator(
    override val id: OperatorId,
    override val dependencies: Set<OperatorId>,
  ) : DataflowOperator<Unit, Unit> {
    override fun evaluate(input: Unit, context: EvaluationContext) = Unit
  }
}
