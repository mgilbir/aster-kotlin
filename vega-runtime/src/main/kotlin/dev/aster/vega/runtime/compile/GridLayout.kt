package dev.aster.vega.runtime.compile

import dev.aster.vega.scene.PointD
import dev.aster.vega.scene.RectD
import kotlin.math.ceil

/**
 * Arranges cells into a grid, ported from upstream's own layout.
 *
 * Two features share this and are the same problem seen twice: a group mark's `layout`, which is
 * how small multiples are placed, and a legend's `columns`, which is how its entries wrap.
 * Implementing it once is the reason they were done together.
 *
 * The arithmetic is not "advance by the cell's width". Upstream rounds the previous column's far
 * edge *up*, and separately adds however far the next cell overhangs *backwards*, also rounded up,
 * before the padding. With cells of one size any rule agrees; with cells that differ — a legend
 * whose swatches grow, a trellis whose axes stick out to the left — they diverge by a unit or two
 * per column and the grid drifts.
 */
internal object GridLayout {

  /**
   * @param columns how many cells per row. One means a single column; anything less than one puts
   *   every cell in one row.
   * @param rowPadding vertical gap between rows; [columnPadding] the horizontal gap between
   *   columns.
   */
  data class Options(
    val columns: Int,
    val rowPadding: Double = 0.0,
    val columnPadding: Double = 0.0,
  )

  /**
   * The offset each cell should be moved to, given where each currently reaches.
   *
   * [cells] are the cells' own bounds in their own coordinates, so a cell that overhangs its origin
   * — a symbol drawn around its centre, an axis label to the left of its axis — has a negative
   * edge, and that is exactly what the grid has to make room for.
   */
  fun place(cells: List<RectD>, options: Options): List<PointD> {
    if (cells.isEmpty()) return emptyList()
    val columns = if (options.columns >= 1) options.columns else cells.size
    val rows = ceil(cells.size / columns.toDouble()).toInt().coerceAtLeast(1)

    // How far each column and row reaches, rounded outwards, so a grid stays on whole units.
    val columnExtent = DoubleArray(columns)
    val rowExtent = DoubleArray(rows)
    for ((index, box) in cells.withIndex()) {
      val c = index % columns
      val r = index / columns
      columnExtent[c] = maxOf(columnExtent[c], ceil(box.right))
      rowExtent[r] = maxOf(rowExtent[r], ceil(box.bottom))
    }

    // Each cell's own lead-in: the padding plus whatever it overhangs backwards. The first column
    // and
    // the first row have no predecessor to clear, so they start flush.
    val columnLead = DoubleArray(columns)
    val rowLead = DoubleArray(rows)
    for ((index, box) in cells.withIndex()) {
      val c = index % columns
      val r = index / columns
      if (c > 0) columnLead[c] = maxOf(columnLead[c], options.columnPadding + overhang(box.left))
      if (r > 0) rowLead[r] = maxOf(rowLead[r], options.rowPadding + overhang(box.top))
    }

    val columnOrigin = DoubleArray(columns)
    for (c in 1 until columns) {
      columnOrigin[c] = columnOrigin[c - 1] + columnExtent[c - 1] + columnLead[c]
    }
    val rowOrigin = DoubleArray(rows)
    for (r in 1 until rows) {
      rowOrigin[r] = rowOrigin[r - 1] + rowExtent[r - 1] + rowLead[r]
    }

    return cells.indices.map { index ->
      PointD(columnOrigin[index % columns], rowOrigin[index / columns])
    }
  }

  /**
   * The order a vertical grid fills in.
   *
   * Down each column and then across, which is how a reader scans a list — so a five-entry legend
   * in two columns reads 1, 2, 3 down the left and 4, 5 down the right. The result is in row-major
   * order, because that is the order the cells are laid out in.
   */
  fun columnMajorOrder(count: Int, columns: Int): List<Int> {
    if (columns <= 1 || count <= 1) return (0 until count).toList()
    val rows = ceil(count / columns.toDouble()).toInt()
    return (0 until count)
      .map { index -> Triple(index % rows, index / rows, index) }
      .sortedWith(compareBy({ it.first }, { it.third }))
      .map { it.third }
  }

  /** How far a cell reaches back past its own origin, rounded up. Zero when it does not. */
  private fun overhang(edge: Double): Double = if (edge < 0.0) ceil(-edge) else 0.0
}

/**
 * Where a group mark sits in a trellis.
 *
 * A grid is not only its cells: a specification supplies the row and column labels as ordinary
 * group marks tagged with a role, and the layout arranges them around the cells rather than among
 * them.
 */
internal enum class TrellisRole {
  /** A cell of the grid itself. Anything without a recognised role is one. */
  CELL,
  ROW_HEADER,
  COLUMN_HEADER,
  ROW_TITLE,
  COLUMN_TITLE;

  companion object {
    fun of(role: String?): TrellisRole =
      when (role) {
        "row-header" -> ROW_HEADER
        "column-header" -> COLUMN_HEADER
        "row-title" -> ROW_TITLE
        "column-title" -> COLUMN_TITLE
        else -> CELL
      }

    /** Roles a `layout` positions but does not grid. */
    fun isGuide(role: String?): Boolean = of(role) != CELL
  }
}
