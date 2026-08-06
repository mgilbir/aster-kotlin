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
