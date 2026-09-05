package dev.aster.vega.android

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import dev.aster.vega.runtime.ChartInputEvent
import dev.aster.vega.scene.AccessibilityTree
import dev.aster.vega.scene.ChartActionKind
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
 * chart gets a semantic tree without a View per mark (ADR 0009).
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
    /** Whether activating it does anything — whether it is a mark. See `AccessibleElement`. */
    val activatable: Boolean,
    /**
     * `aria-roledescription`, which TalkBack reads after the label as the kind of thing this is.
     */
    val roleDescription: String?,
    /**
     * The scale this element lets a reader adjust, when it is an adjustable axis.
     *
     * Android has no `Adjustable` trait, so this becomes the pair of **scroll** actions, which is
     * how the platform's own sliders and pickers are driven from TalkBack: swipe up and down while
     * the element has focus. Forward narrows, matching the direction that reveals more detail.
     */
    val adjustableScale: String?,
  )

  private var cachedRevision = Long.MIN_VALUE
  private var cachedNodes: List<VirtualNode> = emptyList()

  /**
   * What the tree *says*, with no geometry in it, so a caller can tell a real change from a pan.
   *
   * `invalidateSemanticTree` makes TalkBack re-read the chart, and the view called it on every
   * published snapshot — which during a pan is once a frame, on a tree whose marks, labels and
   * order have not changed at all. A screen reader then talks over itself for the length of the
   * gesture. Bounds are deliberately **not** part of this: `ExploreByTouchHelper` re-reads a node's
   * frame when it draws focus, so a mark that only moved needs no invalidation.
   */
  fun semanticIdentity(): List<Any?> =
    nodes().map {
      listOf(it.id, it.label, it.selected, it.activatable, it.roleDescription, it.adjustableScale)
    }

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
    return AccessibilityTree.elements(
        scene,
        selectedIds,
        maxExposedMarks = view.accessibilityMaxExposedMarks,
      )
      .mapIndexed { index, element ->
        VirtualNode(
          id = index,
          label = element.label,
          bounds = element.bounds,
          node = element.nodeId?.let { nodesById[it]?.node },
          selected = element.selected,
          activatable = element.activatable,
          roleDescription = element.roleDescription,
          adjustableScale = element.adjustableScale,
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
    // A **button only where activating it does something.** Every element used to be announced as
    // one, so TalkBack told a reader they could activate an axis caption and then nothing happened
    // when they did. `AccessibleElement.activatable` is the engine's own answer to which elements
    // are
    // marks; a guide is announced as plain text, which is what it is.
    node.className = if (virtual.activatable) "android.widget.Button" else "android.widget.TextView"
    // What kind of thing it is, in words, and in the chart's own language — the engine writes this
    // through the locale's captions, so a Dutch chart says "lijn-markering" rather than "line
    // mark".
    virtual.roleDescription?.let { node.roleDescription = it }
    node.isFocusable = true
    node.isSelected = virtual.selected
    node.setBoundsInParent(toViewRect(virtual.bounds))
    // Only the node's own actions belong here. ExploreByTouchHelper adds and removes the
    // accessibility-focus actions itself and rejects a callback that touches them.
    if (virtual.activatable && virtual.node != null) {
      node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
    }
    // An **adjustable axis**: swipe up and down to narrow and widen the interval it draws its data
    // against. Android has no `Adjustable` trait, so this is the pair of scroll actions, which is
    // what TalkBack maps those swipes onto and how the platform's own sliders are driven.
    //
    // Offered on the element whatever the axis's current state, and refused by the controller at
    // the end of the range — a node's actions are read once when focus lands on it, so withdrawing
    // one at the limit would need the tree re-read to put it back, and a reader would hear the
    // chart re-announce itself on every step.
    if (virtual.adjustableScale != null) {
      node.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD)
      node.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD)
    }
  }

  /**
   * The chart's **own** actions, on the host view's node rather than on any virtual one.
   *
   * `VegaChartController.accessibilityActions` offers zooming, resetting the view and putting an
   * adjusted axis back, each with a label in the chart's own locale — and until this, **no host
   * wired them**. The feature was built, tested and documented against
   * `AccessibilityNodeInfo.addAction`, and the call was never written, so a reader could reach
   * every bar in a chart and never the view they were drawn in (#226).
   *
   * They belong to the whole chart rather than to a mark, which is why they go here:
   * `ExploreByTouchHelper` hands out virtual nodes for the marks and this is the one node that
   * stands for the chart.
   *
   * Offered only when the controller offers them, which is its own rule — an action is in that list
   * only when invoking it would do something, so zooming is withdrawn at each limit and a reset
   * appears only once there is something to undo. A node's actions are re-read whenever
   * accessibility focus lands on it, so the list a reader hears is the current one.
   */
  override fun onInitializeAccessibilityNodeInfo(
    host: android.view.View,
    info: AccessibilityNodeInfoCompat,
  ) {
    super.onInitializeAccessibilityNodeInfo(host, info)
    for (action in view.controller.accessibilityActions) {
      info.addAction(
        AccessibilityNodeInfoCompat.AccessibilityActionCompat(idOf(action.kind), action.label)
      )
    }
  }

  /**
   * Performs one, and reports whether it did anything.
   *
   * `false` is not a formality: the controller returns it for an action that is not currently
   * offered, and TalkBack uses the answer to decide whether to announce a change. Announcing one
   * that did not happen is how a reader loses track of where they are.
   */
  override fun performAccessibilityAction(
    host: android.view.View,
    action: Int,
    args: Bundle?,
  ): Boolean {
    val kind = ChartActionKind.entries.firstOrNull { idOf(it) == action }
    if (kind != null) {
      if (!view.controller.perform(kind)) return false
      view.invalidateIfStale()
      // The domain actions change the scales, so the ticks and the labels a reader explores are a
      // different tree now.
      invalidateSemanticTree()
      return true
    }
    return super.performAccessibilityAction(host, action, args)
  }

  /**
   * The resource id for a kind, spelled out rather than derived from `ordinal`.
   *
   * An exhaustive `when` over the enum, so a kind added to `ChartActionKind` without an id here is
   * a build error rather than an action that silently collides with another one's id.
   */
  private fun idOf(kind: ChartActionKind): Int =
    when (kind) {
      ChartActionKind.ZOOM_IN -> R.id.aster_vega_action_zoom_in
      ChartActionKind.ZOOM_OUT -> R.id.aster_vega_action_zoom_out
      ChartActionKind.RESET_ZOOM -> R.id.aster_vega_action_reset_zoom
      ChartActionKind.RESET_DOMAINS -> R.id.aster_vega_action_reset_domains
    }

  override fun onPerformActionForVirtualView(
    virtualViewId: Int,
    action: Int,
    arguments: Bundle?,
  ): Boolean {
    val virtual = nodes().firstOrNull { it.id == virtualViewId } ?: return false
    val scale = virtual.adjustableScale
    if (
      scale != null &&
        (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ||
          action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    ) {
      // The controller answers whether anything moved, and `false` here is what stops TalkBack
      // announcing a change that did not happen — which is how a reader loses track of where they
      // are.
      if (
        !view.controller.adjustScaleDomain(
          scale,
          narrow = action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
        )
      ) {
        return false
      }
      view.invalidateIfStale()
      // The domain changed, so the ticks and the labels did: the tree a reader explores is a
      // different tree now and has to be re-read.
      invalidateSemanticTree()
      return true
    }
    if (action != AccessibilityNodeInfo.ACTION_CLICK) return false
    val bounds = virtual.bounds
    // **Out of scene space first.** `virtual.bounds` is in scene coordinates, and
    // `VegaChartController.dispatch` expects the space a finger arrives in: placement-relative
    // view pixels, which it then divides by `contentScale * viewportScale` and shifts by the pan.
    // Handing it a scene point meant the two spaces agreed only while the fit scale was exactly 1
    // and nothing had been panned — so a TalkBack double-tap on a mark activated whichever mark
    // happened to sit at the scene coordinate read as a view coordinate. That is the same class of
    // defect as a finger landing beside the mark it hit, except that a screen-reader user has no
    // way to see it happen.
    //
    // The inverse of `toScene`, minus the placement's origin, because that is the one part
    // `dispatch` does not undo — `toPointD` takes it off before a touch is dispatched too.
    view.controller.dispatch(ChartInputEvent.Tap(toControllerSpace(bounds.centerX, bounds.centerY)))
    view.invalidateIfStale()
    return true
  }

  /**
   * A scene point in the space [VegaChartController.dispatch] reads: view pixels with the
   * placement's origin already taken off.
   *
   * The exact inverse of the controller's own `toSceneSpace`, and it has to stay that way — see
   * `onPerformActionForVirtualView` for what happened when it was skipped.
   */
  private fun toControllerSpace(sceneX: Double, sceneY: Double): PointD {
    val scale = viewToSceneScale()
    val interaction = view.controller.snapshot.interactionState
    return PointD(
      sceneX * scale + interaction.viewportOffset.dx,
      sceneY * scale + interaction.viewportOffset.dy,
    )
  }

  /**
   * Maps view coordinates to scene coordinates, undoing padding, fit scale and interactive
   * pan/zoom.
   */
  private fun toScene(x: Float, y: Float): PointD {
    val scale = viewToSceneScale()
    val interaction = view.controller.snapshot.interactionState
    // The view's own placement, not `paddingLeft` written out a third time: the draw, a touch and
    // these frames have to agree about where the chart is, and three copies of the origin is how
    // they stop agreeing.
    val placed = view.placement()
    return PointD(
      ((x - placed.left - interaction.viewportOffset.dx) / scale),
      ((y - placed.top - interaction.viewportOffset.dy) / scale),
    )
  }

  private fun toViewRect(bounds: RectD): Rect {
    val scale = viewToSceneScale()
    val interaction = view.controller.snapshot.interactionState
    val placed = view.placement()
    fun mapX(value: Double) =
      (value * scale + interaction.viewportOffset.dx + placed.left).roundToInt()

    fun mapY(value: Double) =
      (value * scale + interaction.viewportOffset.dy + placed.top).roundToInt()

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
