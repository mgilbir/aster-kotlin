package dev.aster.vega.scene

/*
 * The chart's own accessibility actions.
 *
 * **Here rather than in `vega-runtime`**, which is where they were, because this is the layer every
 * renderer can name. `vega-compose-multiplatform` depends on `vega-scene` alone — it paints a
 * `Scene` and owns its semantics tree — so a type in the runtime is one it cannot mention, and the
 * chart actions were unreachable from it for exactly that reason. `AccessibleElement`,
 * `AccessibilityTree` and `SceneNodeId` are already here for the same reason: they are the
 * vocabulary a host speaks, and `VegaChart`'s `onActivate` already answers in it.
 *
 * The alternative was a second definition beside the first and a string bridging them, which is the
 * shape `scripts/host-conformance.py` exists to prevent.
 */

/**
 * A chart-level action assistive technology can offer, beyond activating a mark.
 *
 * A chart that pans and zooms had no accessible way to do either: the accessibility tree offers an
 * activation per element and nothing else, so a reader could reach every bar and not the view they
 * were drawn in. These are the actions that belong to the **chart** rather than to any one mark,
 * which is why they are not on [AccessibleElement]: a host attaches them to the chart's own node —
 * `AccessibilityNodeInfo.addAction` on Android, `UIAccessibilityCustomAction` on Apple — and asks
 * the controller to perform them.
 *
 * Only the actions that would **do** something are offered. Zooming in at the limit and resetting a
 * view already at rest are both absent from the list rather than present and inert, because an
 * action a reader invokes to no effect is worse than one that was never offered.
 */
public enum class ChartActionKind {
  ZOOM_IN,
  ZOOM_OUT,
  RESET_ZOOM,
  /**
   * Puts every axis a reader adjusted back to the domain the specification computed.
   *
   * A chart action rather than a per-axis one, and there is no `NARROW`/`WIDEN` beside it, because
   * adjusting an axis is **not** an action: it is the increment and decrement of an adjustable
   * element, reached from the axis itself through [AccessibleElement.adjustableScale]. Making them
   * actions would put one pair per axis in this list — eight entries on a two-axis chart — and a
   * reader rotoring through eight custom actions on every chart is worse served than one who swipes
   * on the axis they are standing on.
   *
   * Undoing is the part that has nowhere else to live: a reader who has narrowed two axes is not
   * standing on either of them any more, so the way back belongs to the chart. Separate from
   * [RESET_ZOOM] because the two are different work — one magnifies the drawing, the other changes
   * the interval the data is drawn against — and a single reset would undo work nobody asked to
   * lose.
   */
  RESET_DOMAINS,
}

/**
 * One offered action: what it does, and what to call it in the chart's own language.
 *
 * The label travels with the action because a host has nowhere else to get it. Leaving hosts to
 * write their own would put the chart's wording in three places and none of them in the chart's
 * locale.
 */
public data class ChartAction(val kind: ChartActionKind, val label: String)
