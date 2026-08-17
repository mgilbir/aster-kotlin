package dev.aster.vega.runtime.compile

import dev.aster.vega.scene.RectD
import dev.aster.vega.scene.TextNode
import dev.aster.vega.scene.transformedBounds

/**
 * Hides labels that collide, which is what keeps a dense axis from printing every label on the
 * last.
 *
 * Upstream's `Overlap` transform, reproduced step for step. Two things about it are worth knowing
 * before reading the code:
 *
 * - A hidden label is **not removed**. It stays in the scene at zero opacity, so the mark count is
 *   the same whether or not anything collided, and a chart's structure does not change with its
 *   width. Only the guide's measured extent shrinks, because upstream recomputes the label mark's
 *   bounds from the survivors.
 * - It is a *default* on a gradient legend and opt-in on an axis. That asymmetry is not in the
 *   documentation; it is in `config.js`, where `labelOverlap: true` sits in the `legend` block and
 *   the `axis` block has no entry at all. An axis therefore prints every label however crowded,
 *   which is upstream's behaviour and looks like a bug until you find the config.
 */
internal object LabelOverlap {

  enum class Method {
    /** Hide every other label until the collisions stop. */
    PARITY,
    /** Scan in order, hiding anything that touches the last one kept. */
    GREEDY;

    companion object {
      /**
       * `true` means parity, which is upstream's fallback for any method it does not recognize.
       * `false` and an absent value mean no removal at all.
       */
      fun fromValue(value: String?): Method? =
        when (value?.lowercase()) {
          null,
          "false" -> null
          "greedy" -> GREEDY
          else -> PARITY
        }
    }
  }

  /**
   * Returns the labels that survive, in input order.
   *
   * Labels smaller than a pixel each way take no part: upstream drops them before testing, so an
   * empty label neither hides a neighbour nor is hidden by one.
   */
  fun visible(labels: List<TextNode>, method: Method, separation: Double): List<TextNode> {
    val source = labels.filter { measurable(it.transformedBounds) }
    if (source.size < 3) return labels
    if (!overlaps(source, separation)) return labels

    var kept = source
    while (kept.size >= 3 && overlaps(kept, separation)) {
      kept = if (method == Method.PARITY) parity(kept) else greedy(kept, separation)
    }

    // Upstream's final correction: if the halving ran the list down below three and dropped the
    // last label on the way, the last one is put back and the runner-up hidden in its place — so an
    // axis always keeps both of its ends, whatever the collisions did in between.
    val survivors = kept.toMutableList()
    if (survivors.size < 3 && source.last() !in survivors) {
      if (survivors.size > 1) survivors.removeAt(survivors.size - 1)
      survivors.add(source.last())
    }

    val visible = survivors.toSet()
    return labels.filter { it !in source || it in visible }
  }

  private fun measurable(bounds: RectD): Boolean = bounds.width > 1.0 && bounds.height > 1.0

  private fun parity(items: List<TextNode>): List<TextNode> = items.filterIndexed { index, _ ->
    index % 2 == 0
  }

  private fun greedy(items: List<TextNode>, separation: Double): List<TextNode> {
    val kept = mutableListOf<TextNode>()
    var last: RectD? = null
    for (item in items) {
      val bounds = item.transformedBounds
      if (last == null || !intersects(last, bounds, separation)) {
        kept += item
        last = bounds
      }
    }
    return kept
  }

  /** Adjacent pairs only, which is all upstream tests — the list is already in position order. */
  private fun overlaps(items: List<TextNode>, separation: Double): Boolean =
    items.zipWithNext().any { (a, b) ->
      intersects(a.transformedBounds, b.transformedBounds, separation)
    }

  /**
   * Upstream's test: the widest gap between the two boxes, on either axis, is less than the
   * separation.
   *
   * With the default separation of zero this makes touching boxes *not* overlap, so two labels that
   * meet exactly at a pixel are both kept.
   */
  private fun intersects(a: RectD, b: RectD, separation: Double): Boolean {
    val gap = maxOf(b.left - a.right, a.left - b.right, b.top - a.bottom, a.top - b.bottom)
    return separation > gap
  }
}
