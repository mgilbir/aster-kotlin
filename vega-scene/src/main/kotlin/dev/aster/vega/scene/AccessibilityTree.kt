package dev.aster.vega.scene

/**
 * One element a screen reader can land on: what to say, and where it is.
 *
 * Deliberately not a platform's node type. Android wants an `AccessibilityNodeInfo`, iOS wants a
 * `UIAccessibilityElement`, and both want the same three facts — a label, a rectangle, and whether
 * the thing is selected. So the facts are computed once, here, and each host does its own
 * translation.
 */
public data class AccessibleElement(
  /**
   * What a screen reader reads out. `label` and `value` already joined, because both hosts join
   * them.
   */
  public val label: String,
  /** Where it is, in scene coordinates. A host scales this the same way it scales the drawing. */
  public val bounds: RectD,
  /** The node it came from, so a host can select it when the reader activates the element. */
  public val nodeId: SceneNodeId?,
  public val selected: Boolean,
  /**
   * True when this element stands in for a whole chart rather than one mark.
   *
   * A host may want to say so differently — an "adjustable" trait, a different role — and cannot
   * tell from the label alone.
   */
  public val isSummary: Boolean = false,
)

/**
 * The semantic tree a screen reader explores, built from the scene and **shared by every host**.
 *
 * This is not the drawing tree. A chart's marks are not all worth announcing, they are not
 * announced in drawing order, and a dense chart must not become an unbounded list of elements — so
 * there is a policy, and the policy is the part worth having in one place:
 *
 * - only nodes whose [AccessibilityDescriptor.focusable] is set, which is the engine's own decision
 *   about what is worth announcing;
 * - ordered by [AccessibilityDescriptor.traversalIndex] rather than by paint order, because a
 *   reader moves through a chart the way it reads rather than the way it was drawn;
 * - and past [MAX_EXPOSED_MARKS], a single summary instead. A scatter plot of four thousand points
 *   is not explorable point by point, and pretending otherwise produces a control a reader cannot
 *   escape.
 *
 * It lived in `VegaAccessibilityHelper` on Android and nowhere else, which meant iOS had no
 * accessibility at all and a second host would have needed a second copy of these rules. A screen
 * reader's experience of a chart is not a platform detail.
 */
public object AccessibilityTree {

  /**
   * How many marks may be exposed individually before a chart is summarised instead.
   *
   * Upstream has no equivalent — a browser exposes whatever the SVG contains — so this is this
   * engine's own judgement, and it is a judgement about screen readers rather than about charts: a
   * list of thousands of elements is worse than useless to someone moving through it one swipe at a
   * time.
   */
  public const val MAX_EXPOSED_MARKS: Int = 120

  /**
   * The elements for [scene], with [selectedNodeIds] marked as selected.
   *
   * Cheap enough to call per query but not free — it flattens the scene — so a host caches it
   * against the snapshot's revision, which is what both of them do.
   */
  public fun elements(
    scene: Scene,
    selectedNodeIds: Set<SceneNodeId> = emptySet(),
  ): List<AccessibleElement> {
    val describable =
      scene
        .flatten()
        .filter { placed ->
          val descriptor = placed.node.metadata.accessibility
          placed.node.visible && descriptor != null && descriptor.focusable
        }
        .sortedBy { it.node.metadata.accessibility?.traversalIndex ?: 0 }

    if (describable.size > MAX_EXPOSED_MARKS) {
      return listOf(
        AccessibleElement(
          label = "Chart with ${describable.size} marks. Too dense to explore individually.",
          bounds = scene.viewport,
          nodeId = null,
          selected = false,
          isSummary = true,
        )
      )
    }

    return describable.map { placed ->
      val descriptor = requireNotNull(placed.node.metadata.accessibility)
      AccessibleElement(
        // "label: value" — a mark's label alone rarely says what it is worth: "Sales" is a column,
        // and
        // "Sales: 42" is the datum a reader wanted.
        label = descriptor.value?.let { "${descriptor.label}: $it" } ?: descriptor.label,
        bounds = placed.worldBounds,
        nodeId = placed.node.id,
        selected = placed.node.id in selectedNodeIds,
      )
    }
  }
}
