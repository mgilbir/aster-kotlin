package dev.aster.vega.scene

/**
 * A [SceneNodeId] read, built and compared from a **foreign host**.
 *
 * The same boundary problem [ForeignPaint] exists for, in the type a host meets most often.
 * [SceneNodeId] is a `value class`, so it has no Obj-C representation and is **absent from the
 * generated header entirely**: a Swift caller can hold one and can compare two, because the boxes
 * compare, and cannot read the `Long` inside, build one, or reach [SceneNodeId.None].
 *
 * It is reachable across most of the public surface — every `SceneNode.id`, both of
 * `InteractionState`'s node fields, `ChartSelection.nodeIds`, `AccessibleElement.nodeId`, three
 * `ChartEvent` variants and `CompiledSpec.hoverVariants` — so a host that cannot read one cannot
 * key a dictionary on a mark, persist a selection, or correlate an accessibility element with
 * anything it already knows.
 *
 * This repository's own Swift renderer had worked around it twice before an adopter reported it
 * (#120): `ChartSession.selectedNodeIds` is a `Set<AnyHashable>` with a comment explaining that the
 * ids cross as opaque boxes, and the accessibility overlay can do nothing with `element.nodeId`
 * except test it against nil.
 *
 * As with [ForeignPaint], the answer is not to reshape the scene. `SceneNodeId` stays a value
 * class, because one is allocated per node per scene and that is exactly where the allocation would
 * land.
 */
public object ForeignNodeId {

  /**
   * The number inside an id that **may be absent**, or null where it is.
   *
   * This is the whole of the gap, and it is narrower than it looks. Kotlin/Native unwraps a value
   * class at the boundary wherever it can, so a *non-null* `SceneNodeId` already crosses as an
   * `int64_t`: `SceneNode.id` and `AccessibleElement.nodeId` are readable from Swift today and
   * always were. It is the **nullable** and **collected** positions that box — `InteractionState`'s
   * `hoveredNodeId` and `focusedNodeId` arrive as an opaque `id`, and `ChartSelection.nodeIds` as
   * an `NSSet` of them — because a box is the only representation an optional or an element has.
   *
   * So there is nothing to unwrap for `SceneNode.id` and no function here for it: one that took a
   * non-null id would be `Int64` to `Int64`, an identity that reads as a fix and is not.
   */
  public fun valueOrNull(id: SceneNodeId?): Long? = id?.value

  /** The numbers inside a set of ids, which is how a host reads a selection or a hover. */
  public fun values(ids: Set<SceneNodeId>): List<Long> = ids.map { it.value }

  /**
   * A set of ids from numbers, for handing one back — `ChartSelection`, or the accessibility tree's
   * `selectedNodeIds`.
   *
   * `ChartSession.selectedNodeIds` is a `Set<AnyHashable>` today for exactly this reason: the boxes
   * were opaque, so the set could only be passed through untouched.
   */
  public fun setOf(values: List<Long>): Set<SceneNodeId> = values.map { SceneNodeId(it) }.toSet()

  /** The id that names no node, as a number, so a host can compare against it. */
  public fun noneValue(): Long = SceneNodeId.None.value
}
