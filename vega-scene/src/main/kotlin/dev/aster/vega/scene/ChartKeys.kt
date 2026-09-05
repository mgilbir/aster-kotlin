package dev.aster.vega.scene

/*
 * The keyboard vocabulary a chart reacts to.
 *
 * **Here rather than in `vega-runtime`**, which is where it was, for the same reason
 * `ChartAction` moved: `vega-compose-multiplatform` depends on `vega-scene` alone — it paints a
 * `Scene` and reports rather than dispatching, so a host that only draws needs no engine at all —
 * and a type in the runtime is one it cannot name. That renderer had **no keyboard path of any
 * kind** because of it, so a specification's `keydown` handlers and the engine's own traversal were
 * both unreachable there, on the surface most likely to be running on a desktop.
 *
 * The alternative was for each host to translate its platform's keys into its own shape, which puts
 * the same rule in three places and none of them shared. A screen reader's experience of a chart is
 * not a platform detail, and neither is a keyboard's.
 */

/** Keys the chart reacts to. Anything else is left to the host view. */
public enum class ChartKey {
  ARROW_LEFT,
  ARROW_RIGHT,
  ARROW_UP,
  ARROW_DOWN,
  ENTER,
  SPACE,
  ESCAPE,
  TAB,
  HOME,
  END,
}

public data class Modifiers(
  val shift: Boolean = false,
  val control: Boolean = false,
  val alt: Boolean = false,
  val meta: Boolean = false,
) {
  public companion object {
    public val None: Modifiers = Modifiers()
  }
}
