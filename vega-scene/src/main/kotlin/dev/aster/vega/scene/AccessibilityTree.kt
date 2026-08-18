package dev.aster.vega.scene

import dev.aster.vega.model.locale.VegaCaptions

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
  /**
   * The ARIA role this element came from: `graphics-symbol`, `graphics-object`, `axis`, `legend`.
   *
   * Carried because a host cannot otherwise tell an axis from a data point, and both hosts that
   * consume this tree worked around its absence by announcing **everything** as a button —
   * `className = "android.widget.Button"` on Android and `.isButton` on iOS. Announcing an axis
   * caption as a button is wrong: a reader is told they can activate a thing that does nothing.
   */
  public val role: String? = null,
  /**
   * `aria-roledescription` — what kind of thing this is, in words, and in the chart's own language.
   *
   * The pair a reader actually hears: role `graphics-symbol` with "line mark" is heard as "line
   * mark", where the role alone is heard as nothing useful.
   */
  public val roleDescription: String? = null,
  /**
   * Whether activating this element does anything — whether it stands for a **mark**.
   *
   * The other half of the same gap [role] closes. A host has to decide two things about an element:
   * what to call it, and whether to offer an activation. Both hosts were guessing at the second by
   * announcing everything as a button, so a reader was told they could activate an axis caption,
   * and activating it did nothing. This is the engine's own answer: the descriptor sits on a node
   * whose `NodeMetadata.role` is `mark`, so a tap on it reaches the dataflow.
   */
  public val activatable: Boolean = false,
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
    /**
     * The language the one sentence this object writes itself is in — the dense-chart summary.
     *
     * Everything else here is a label the compiler already produced in the chart's own locale.
     */
    captions: VegaCaptions = VegaCaptions.English,
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
          label = captions.denseChartSummary(describable.size),
          bounds = scene.viewport,
          nodeId = null,
          selected = false,
          isSummary = true,
          // A summary stands for the whole drawing, which is upstream's `graphics-document`.
          role = "graphics-document",
          roleDescription = null,
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
        role = descriptor.role,
        roleDescription = descriptor.roleDescription,
        activatable = placed.node.metadata.role == "mark",
      )
    }
  }
}
