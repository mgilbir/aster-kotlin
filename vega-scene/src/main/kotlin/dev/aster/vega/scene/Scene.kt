package dev.aster.vega.scene

/**
 * An immutable, fully compiled chart ready to be drawn, serialized or hit tested.
 *
 * Nothing in the drawing path may mutate a scene, and a scene must be complete before it is
 * published to the UI (PROJECT_BRIEF.md 4.5 and 10.2).
 *
 * @param revision monotonically increasing per controller; renderers compare it to decide whether
 *   to invalidate.
 */
public data class Scene(
  val width: Double,
  val height: Double,
  val background: SceneColor?,
  val root: GroupNode,
  val revision: Long = 0L,
) {
  /** The chart's viewport, independent of where marks actually landed. */
  public val viewport: RectD
    get() = RectD(0.0, 0.0, width, height)

  /** Union of every visible node's bounds; can exceed [viewport] when marks overflow. */
  public val contentBounds: RectD
    get() = root.transformedBounds

  public val nodeCount: Int by lazy(LazyThreadSafetyMode.NONE) { countNodes(root) }

  private fun countNodes(node: SceneNode): Int =
    if (node is GroupNode) 1 + node.children.sumOf { countNodes(it) } else 1

  /**
   * The same scene with some nodes swapped for others of the same id.
   *
   * What a hover does: the item under the pointer is redrawn from its mark's `hover` block, and
   * everything else is the scene that was already there. Only the groups on the path to a
   * replacement are rebuilt, so hovering one point in a ten-thousand-point scatter copies a handful
   * of objects rather than the scene.
   *
   * Returns `this` unchanged when nothing matches, so a caller can publish the result without
   * checking whether anything happened.
   */
  public fun replacing(replacements: Map<SceneNodeId, SceneNode>): Scene {
    if (replacements.isEmpty()) return this
    val replaced = replaceIn(root, replacements) ?: return this
    return copy(root = replaced as GroupNode)
  }

  /** Null when nothing under [node] was replaced, which is what lets the rest be shared. */
  private fun replaceIn(node: SceneNode, replacements: Map<SceneNodeId, SceneNode>): SceneNode? {
    replacements[node.id]?.let {
      return it
    }
    if (node !is GroupNode) return null
    var changed = false
    val swappedHere = mutableListOf<Int>()
    val children =
      node.children.mapIndexed { index, child ->
        val swapped = replaceIn(child, replacements)
        if (swapped != null) {
          changed = true
          if (replacements.containsKey(child.id)) swappedHere += index
        }
        swapped ?: child
      }
    if (!changed) return null
    return node.copy(children = raiseSwapped(children, swappedHere))
  }

  /**
   * Re-sorts the marks around a swapped item by `zindex`, which is what raises a hovered one.
   *
   * `zindex` on an *item* is paint order **within its own mark**, so the sort is confined to the
   * run of children that came from the same mark — a raised bar must draw over its neighbours and
   * still under the axis. A mark's items are contiguous in the group, so the run is found by
   * walking out from the swap while the mark's name and kind stay the same.
   *
   * Stable, so items sharing a `zindex` keep the order the data gave them.
   */
  private fun raiseSwapped(children: List<SceneNode>, swapped: List<Int>): List<SceneNode> {
    if (swapped.isEmpty()) return children
    if (children.none { it.metadata.zindex != 0 }) return children
    val out = children.toMutableList()
    val done = mutableSetOf<Int>()
    for (index in swapped) {
      if (index in done) continue
      val name = out[index].metadata.markName
      val kind = out[index].metadata.markKind
      fun sameMark(node: SceneNode) =
        node.metadata.role == "mark" &&
          node.metadata.markName == name &&
          node.metadata.markKind == kind
      if (!sameMark(out[index])) continue
      var from = index
      while (from > 0 && sameMark(out[from - 1])) from--
      var to = index
      while (to < out.size - 1 && sameMark(out[to + 1])) to++
      for (i in from..to) done += i
      val run = out.subList(from, to + 1)
      val sorted = run.sortedBy { it.metadata.zindex }
      for (i in sorted.indices) out[from + i] = sorted[i]
    }
    return out
  }

  public companion object {
    public fun empty(width: Double = 0.0, height: Double = 0.0): Scene =
      Scene(
        width = width,
        height = height,
        background = null,
        root = GroupNode(id = SceneNodeId.None),
        revision = 0L,
      )
  }
}

/** Depth-first walk in paint order (parents before children, children in declaration order). */
/**
 * A group's children in the order they are **painted**, which `zindex` decides.
 *
 * Not a sort, and the difference is visible. Upstream's `visit` paints every item whose `zindex` is
 * **zero** first, in data order, and only then the ones that have a `zindex`, ordered by it and by
 * their original position. So a `zindex: -1` item draws *on top* of the untouched ones rather than
 * beneath them — counter-intuitive, and exactly what `zorder` does: negatives are still non-zero,
 * so they join the second pass.
 *
 * `zindex` is paint order **within one mark**, so the reordering is confined to each contiguous run
 * of children that came from the same mark. A raised bar draws over its neighbours and still under
 * the axis.
 *
 * Returns the list unchanged when nothing carries a `zindex`, which is almost every group.
 */
public fun paintOrder(children: List<SceneNode>): List<SceneNode> {
  if (children.none { it.metadata.zindex != 0 }) return children
  val out = mutableListOf<SceneNode>()
  var index = 0
  while (index < children.size) {
    val node = children[index]
    val name = node.metadata.markName
    val kind = node.metadata.markKind
    // A run is one mark's items. Anything that is not a mark — a guide, a nested group — stands
    // alone,
    // so a `zindex` on one of those cannot reorder it against its siblings, which is upstream's
    // rule
    // too: a mark's `zindex` and an *item's* are different things.
    if (node.metadata.role != "mark") {
      out += node
      index++
      continue
    }
    val ordinal = node.metadata.markOrdinal
    var end = index
    while (end + 1 < children.size && sameMark(children[end + 1], name, kind, ordinal)) {
      end++
    }
    val run = children.subList(index, end + 1)
    if (run.none { it.metadata.zindex != 0 }) {
      out += run
    } else {
      out += run.filter { it.metadata.zindex == 0 }
      out += run.filter { it.metadata.zindex != 0 }.sortedBy { it.metadata.zindex }
    }
    index = end + 1
  }
  return out
}

/**
 * Whether this node belongs to the same mark as the run being collected.
 *
 * The ordinal is what tells two unnamed marks of the same type apart. Before it was carried, two
 * `rect` marks declared side by side read as one run, and an item `zindex` in the second could be
 * painted among the first's items — upstream paints each mark's run whole.
 */
private fun sameMark(node: SceneNode, name: String?, kind: String?, ordinal: Int?): Boolean =
  node.metadata.role == "mark" &&
    node.metadata.markName == name &&
    node.metadata.markKind == kind &&
    node.metadata.markOrdinal == ordinal

public fun SceneNode.walk(visit: (node: SceneNode, parentTransform: Transform2D) -> Unit) {
  walkInternal(Transform2D.Identity, visit)
}

private fun SceneNode.walkInternal(
  parentTransform: Transform2D,
  visit: (SceneNode, Transform2D) -> Unit,
) {
  visit(this, parentTransform)
  if (this is GroupNode) {
    val childTransform = parentTransform.concat(transform)
    for (child in children) child.walkInternal(childTransform, visit)
  }
}

/** Every node in paint order, paired with the accumulated transform of its ancestors. */
public fun Scene.flatten(): List<PlacedNode> {
  val result = mutableListOf<PlacedNode>()
  root.walk { node, parentTransform ->
    result.add(PlacedNode(node, parentTransform.concat(node.transform)))
  }
  return result
}

/**
 * A node plus the absolute transform that maps its local coordinates to scene coordinates.
 *
 * `worldTransform` already includes the node's own transform, so `worldTransform.mapBounds(bounds)`
 * gives scene-space bounds directly.
 */
public data class PlacedNode(val node: SceneNode, val worldTransform: Transform2D) {
  public val worldBounds: RectD
    get() = worldTransform.mapBounds(node.bounds)
}

/** Finds a node by id, or `null`. Linear; callers that need this often should build a map. */
public fun Scene.findNode(id: SceneNodeId): SceneNode? {
  var found: SceneNode? = null
  root.walk { node, _ -> if (found == null && node.id == id) found = node }
  return found
}
