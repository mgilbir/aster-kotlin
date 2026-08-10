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
   * How a grid lines its cells up, per axis.
   *
   * The names are upstream's and the difference between them only shows once the cells differ in
   * size: `each` gives every column the same lead-in — the widest overhang in that column — so the
   * columns line up with each other; `all` uses the widest cell anywhere, so every column is the
   * same width; and `none` lets each cell keep its own overhang, so nothing lines up and no space
   * is wasted.
   */
  enum class Align {
    EACH,
    ALL,
    NONE;

    companion object {
      fun fromName(name: String?): Align =
        when (name?.lowercase()) {
          null -> EACH
          "all" -> ALL
          "none" -> NONE
          else -> EACH
        }
    }
  }

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
    val alignColumn: Align = Align.EACH,
    val alignRow: Align = Align.EACH,
  )

  /**
   * The offset each cell should be moved to, given where each currently reaches.
   *
   * [cells] are the boxes the grid measures the cells by. Which box that is, is `layout.bounds`'s
   * decision and the caller's to make: `full` passes the cell's own bounds, so an axis label
   * hanging to its left is made room for, and `flush` passes its declared extent, so it is not.
   *
   * Ported from upstream's `gridLayout` rather than derived, because the three alignments differ
   * only in how the per-cell lead-in is pooled and that is easy to get subtly wrong: `each` takes
   * the widest lead in each column, `all` the widest anywhere, and `none` leaves each cell its own.
   */
  fun place(cells: List<RectD>, options: Options): List<PointD> {
    if (cells.isEmpty()) return emptyList()
    val columns = if (options.columns >= 1) options.columns else cells.size
    val rows = ceil(cells.size / columns.toDouble()).toInt().coerceAtLeast(1)
    val n = cells.size

    // How far each column and row reaches, rounded outwards, so a grid stays on whole units.
    val columnExtent = DoubleArray(columns)
    val rowExtent = DoubleArray(rows)
    var widest = 0.0
    var tallest = 0.0
    for ((index, box) in cells.withIndex()) {
      val c = index % columns
      val r = index / columns
      val right = ceil(box.right)
      val bottom = ceil(box.bottom)
      widest = maxOf(widest, right)
      tallest = maxOf(tallest, bottom)
      columnExtent[c] = maxOf(columnExtent[c], right)
      rowExtent[r] = maxOf(rowExtent[r], bottom)
    }

    // Each cell's own lead-in: the padding plus whatever it overhangs backwards. The first column
    // and the first row have no predecessor to clear, so they start flush.
    val columnLead = DoubleArray(n)
    val rowLead = DoubleArray(n)
    for ((index, box) in cells.withIndex()) {
      columnLead[index] = options.columnPadding + overhang(box.left)
      rowLead[index] = options.rowPadding + overhang(box.top)
    }
    for (index in 0 until n) {
      if (index % columns == 0) columnLead[index] = 0.0
      if (index < columns) rowLead[index] = 0.0
    }

    when (options.alignColumn) {
      Align.EACH ->
        for (c in 1 until columns) {
          var offset = 0.0
          var i = c
          while (i < n) {
            offset = maxOf(offset, columnLead[i])
            i += columns
          }
          i = c
          while (i < n) {
            columnLead[i] = offset + columnExtent[c - 1]
            i += columns
          }
        }
      Align.ALL -> {
        var offset = 0.0
        for (i in 0 until n) if (i % columns != 0) offset = maxOf(offset, columnLead[i])
        for (i in 0 until n) if (i % columns != 0) columnLead[i] = offset + widest
      }
      Align.NONE ->
        for (c in 1 until columns) {
          var i = c
          while (i < n) {
            columnLead[i] += columnExtent[c - 1]
            i += columns
          }
        }
    }

    when (options.alignRow) {
      Align.EACH ->
        for (r in 1 until rows) {
          var offset = 0.0
          for (i in r * columns until minOf(n, r * columns + columns)) {
            offset = maxOf(offset, rowLead[i])
          }
          for (i in r * columns until minOf(n, r * columns + columns)) {
            rowLead[i] = offset + rowExtent[r - 1]
          }
        }
      Align.ALL -> {
        var offset = 0.0
        for (i in columns until n) offset = maxOf(offset, rowLead[i])
        for (i in columns until n) rowLead[i] = offset + tallest
      }
      Align.NONE ->
        for (r in 1 until rows) {
          for (i in r * columns until minOf(n, r * columns + columns)) {
            rowLead[i] += rowExtent[r - 1]
          }
        }
    }

    // The leads accumulate along each axis independently: across a row for x, down a column for y.
    val xs = DoubleArray(n)
    var running = 0.0
    for (i in 0 until n) {
      running = columnLead[i] + (if (i % columns != 0) running else 0.0)
      xs[i] = running
    }
    val ys = DoubleArray(n)
    for (c in 0 until columns) {
      var down = 0.0
      var i = c
      while (i < n) {
        down += rowLead[i]
        ys[i] = down
        i += columns
      }
    }

    return cells.indices.map { PointD(xs[it], ys[it]) }
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
  /**
   * The far side of the grid, where a *shared* axis is drawn.
   *
   * Vega-Lite puts a trellis's labelled x axis in a column footer and its y axis in a row header,
   * so the tick labels appear once for the whole grid rather than under every cell. Without this
   * role the footer is taken for a cell and joins the grid, which shifts every real cell along and
   * widens the chart by a whole column.
   */
  ROW_FOOTER,
  COLUMN_FOOTER,
  ROW_TITLE,
  COLUMN_TITLE;

  companion object {
    fun of(role: String?): TrellisRole =
      when (role) {
        "row-header" -> ROW_HEADER
        "column-header" -> COLUMN_HEADER
        "row-footer" -> ROW_FOOTER
        "column-footer" -> COLUMN_FOOTER
        "row-title" -> ROW_TITLE
        "column-title" -> COLUMN_TITLE
        else -> CELL
      }

    /** Roles a `layout` positions but does not grid. */
    fun isGuide(role: String?): Boolean = of(role) != CELL
  }
}
