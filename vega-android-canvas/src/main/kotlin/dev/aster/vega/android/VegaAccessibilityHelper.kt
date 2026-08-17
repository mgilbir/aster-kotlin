package dev.aster.vega.android

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import dev.aster.vega.runtime.ChartInputEvent
import dev.aster.vega.scene.AccessibilityTree
import dev.aster.vega.scene.PointD
import dev.aster.vega.scene.RectD
import dev.aster.vega.scene.Scene
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.flatten
import kotlin.math.roundToInt

/**
 * Exposes the chart as virtual accessibility descendants of one View.
 *
 * [ExploreByTouchHelper] exists precisely for custom views that draw many logical items, so the
 * chart gets a semantic tree without a View per mark (PROJECT_BRIEF.md 4.3, 12).
 *
 * The semantic tree is separate from the drawing tree and is capped: a dense chart exposes a
 * summary plus extrema rather than an unbounded node list.
 */
internal class VegaAccessibilityHelper(private val view: VegaChartView) :
  ExploreByTouchHelper(view) {

  private data class VirtualNode(
    val id: Int,
    val label: String,
    val bounds: RectD,
    val node: SceneNode?,
    val selected: Boolean,
  )

  private var cachedRevision = Long.MIN_VALUE
  private var cachedNodes: List<VirtualNode> = emptyList()

  /** Drops the cached semantic tree; the next query rebuilds it from the current snapshot. */
  fun invalidateSemanticTree() {
    cachedRevision = Long.MIN_VALUE
    invalidateRoot()
  }

  private fun nodes(): List<VirtualNode> {
    val snapshot = view.controller.snapshot
    if (snapshot.revision == cachedRevision) return cachedNodes
    cachedNodes = buildNodes(snapshot.scene, snapshot.interactionState.selection.nodeIds)
    cachedRevision = snapshot.revision
    return cachedNodes
  }

  private fun buildNodes(
    scene: Scene,
    selectedIds: Set<dev.aster.vega.scene.SceneNodeId>,
  ): List<VirtualNode> {
    // The policy — which marks are worth announcing, in what order, and when a dense chart becomes
    // a
    // summary instead — is [AccessibilityTree] in `vega-scene`, shared with every other host. It
    // used to
    // live here, which is why iOS had no accessibility at all: a screen reader's experience of a
    // chart
    // was an Android detail. All that is left here is the translation into Android's own node type.
    val nodesById = scene.flatten().associateBy { it.node.id }
    return AccessibilityTree.elements(scene, selectedIds).mapIndexed { index, element ->
      VirtualNode(
        id = index,
        label = element.label,
        bounds = element.bounds,
        node = element.nodeId?.let { nodesById[it]?.node },
        selected = element.selected,
      )
    }
  }

  override fun getVirtualViewAt(x: Float, y: Float): Int {
    val point = toScene(x, y)
    // Reverse order so the topmost mark wins, matching the visual hit test.
    return nodes().lastOrNull { it.bounds.contains(point) }?.id ?: HOST_ID
  }

  override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
    nodes().forEach { virtualViewIds.add(it.id) }
  }

  // ExploreByTouchHelper's contract is defined in terms of parent-relative bounds, so
  // setBoundsInParent is the required call here even though AccessibilityNodeInfoCompat marks it
  // deprecated in favour of screen-relative bounds.
  @Suppress("DEPRECATION")
  override fun onPopulateNodeForVirtualView(
    virtualViewId: Int,
    node: AccessibilityNodeInfoCompat,
  ) {
    val virtual = nodes().firstOrNull { it.id == virtualViewId }
    if (virtual == null) {
      // ExploreByTouchHelper requires a populated node and non-empty bounds even for a stale id.
      node.contentDescription = ""
      node.setBoundsInParent(Rect(0, 0, 1, 1))
      return
    }

    node.contentDescription = virtual.label
    node.className = "android.widget.Button"
    node.isFocusable = true
    node.isSelected = virtual.selected
    node.setBoundsInParent(toViewRect(virtual.bounds))
    // Only the node's own actions belong here. ExploreByTouchHelper adds and removes the
    // accessibility-focus actions itself and rejects a callback that touches them.
    if (virtual.node != null) node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
  }

  override fun onPerformActionForVirtualView(
    virtualViewId: Int,
    action: Int,
    arguments: Bundle?,
  ): Boolean {
    if (action != AccessibilityNodeInfo.ACTION_CLICK) return false
    val virtual = nodes().firstOrNull { it.id == virtualViewId } ?: return false
    val bounds = virtual.bounds
    view.controller.dispatch(ChartInputEvent.Tap(PointD(bounds.centerX, bounds.centerY)))
    view.invalidateIfStale()
    return true
  }

  /**
   * Maps view coordinates to scene coordinates, undoing padding, fit scale and interactive
   * pan/zoom.
   */
  private fun toScene(x: Float, y: Float): PointD {
    val scale = viewToSceneScale()
    val interaction = view.controller.snapshot.interactionState
    return PointD(
      ((x - view.paddingLeft - interaction.viewportOffset.dx) / scale),
      ((y - view.paddingTop - interaction.viewportOffset.dy) / scale),
    )
  }

  private fun toViewRect(bounds: RectD): Rect {
    val scale = viewToSceneScale()
    val interaction = view.controller.snapshot.interactionState
    fun mapX(value: Double) =
      (value * scale + interaction.viewportOffset.dx + view.paddingLeft).roundToInt()

    fun mapY(value: Double) =
      (value * scale + interaction.viewportOffset.dy + view.paddingTop).roundToInt()

    val rect = Rect(mapX(bounds.left), mapY(bounds.top), mapX(bounds.right), mapY(bounds.bottom))
    // Accessibility bounds must be non-empty to be reachable.
    if (rect.width() < 1) rect.right = rect.left + 1
    if (rect.height() < 1) rect.bottom = rect.top + 1
    return rect
  }

  /**
   * Scene units to view pixels.
   *
   * Reuses the controller's `contentScale` — the same value hit testing inverts — so accessibility
   * bounds and touch exploration cannot land on different geometry than the visual hit test.
   */
  private fun viewToSceneScale(): Double {
    val snapshot = view.controller.snapshot
    return view.controller.contentScale * snapshot.interactionState.viewportScale
  }

  companion object {
    /**
     * Above this many focusable marks the tree collapses to a summary.
     *
     * Kept as an alias so existing callers and tests still read it, but the number itself belongs
     * with the policy in [AccessibilityTree] — two constants meaning the same thing is how the
     * summary threshold ends up differing between a chart's Android and iOS descriptions.
     */
    const val MAX_EXPOSED_MARKS: Int = AccessibilityTree.MAX_EXPOSED_MARKS
  }
}
