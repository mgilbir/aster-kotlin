package dev.aster.vega.dataflow.transform

import dev.aster.vega.dataflow.voronoi.Delaunay
import dev.aster.vega.dataflow.voronoi.VoronoiDiagram
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.field

/**
 * `voronoi`: the region of the plane nearest to each row's point, as an outline.
 *
 * Almost always invisible. A scatter plot of six hundred airports cannot be hovered reliably by its
 * dots, so the specification draws a transparent Voronoi cell over each one and lets the pointer
 * hit *that* — the nearest airport wins wherever the pointer is. The cells are still marks, so they
 * are still compared.
 *
 * The diagram is in `dev.aster.vega.dataflow.voronoi`: an incremental Delaunay triangulation over
 * an exact orientation predicate, then the cells around each point clipped to an extent.
 */
public object VoronoiTransform : Transform {
  override val type: String = "voronoi"

  /** Upstream's default: a box big enough that nothing is clipped unless a size is given. */
  private val DEFAULT_EXTENT = doubleArrayOf(-1e5, -1e5, 1e5, 1e5)

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    if (input.isEmpty()) return input
    val as0 = params.string("as") ?: "path"
    val xField = params.string("x")
    val yField = params.string("y")
    if (xField == null || yField == null) {
      context.diagnostics.error(
        dev.aster.vega.model.DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "voronoi needs 'x' and 'y' naming the fields its points are in",
        operator = type,
      )
      return input
    }

    val size = params.numberList("size")
    val extent = params.fields["extent"] as? VegaValue.Arr
    val bounds =
      when {
        size.size >= 2 -> doubleArrayOf(0.0, 0.0, size[0], size[1])
        extent != null && extent.values.size >= 2 -> {
          val lo = (extent.values[0] as? VegaValue.Arr)?.values
          val hi = (extent.values[1] as? VegaValue.Arr)?.values
          if (lo == null || hi == null || lo.size < 2 || hi.size < 2) DEFAULT_EXTENT
          else doubleArrayOf(lo[0].asDouble(), lo[1].asDouble(), hi[0].asDouble(), hi[1].asDouble())
        }
        else -> DEFAULT_EXTENT
      }

    val coords = DoubleArray(input.size * 2)
    for (index in input.indices) {
      coords[2 * index] = input[index].field(xField).asDouble()
      coords[2 * index + 1] = input[index].field(yField).asDouble()
    }

    val diagram = VoronoiDiagram(Delaunay(coords), bounds[0], bounds[1], bounds[2], bounds[3])

    return input.mapIndexed { index, row ->
      val polygon = diagram.cellPolygon(index)
      val path = if (polygon == null || isPoint(polygon)) null else outline(polygon)
      row.withField(as0, path?.let { VegaValue.Str(it) } ?: VegaValue.Null)
    }
  }

  /** A cell that collapsed to a single point draws nothing rather than a zero-area sliver. */
  private fun isPoint(p: DoubleArray): Boolean = p.size == 4 && p[0] == p[2] && p[1] == p[3]

  /**
   * `M x,y L x,y … Z`, with the repeated closing vertices dropped.
   *
   * Upstream walks back from the end while the last point equals the first, because `closePath`
   * appended one and the cell may already have closed on itself. `Z` says the same thing, and the
   * extra `L` would show as a join on a stroked cell.
   */
  private fun outline(p: DoubleArray): String {
    val x = p[0]
    val y = p[1]
    var n = p.size / 2 - 1
    while (n > 0 && p[2 * n] == x && p[2 * n + 1] == y) n--
    val out = StringBuilder("M")
    for (index in 0..n) {
      if (index > 0) out.append('L')
      out.append(number(p[2 * index]))
      out.append(',')
      out.append(number(p[2 * index + 1]))
    }
    out.append('Z')
    return out.toString()
  }

  /**
   * A coordinate the way JavaScript writes one.
   *
   * Upstream builds the path with `Array.prototype.join`, so each point is `String([x, y])` — a
   * comma-separated pair in JavaScript's own number formatting. A whole number therefore has no
   * decimal point, and a path string is compared as text.
   */
  private fun number(value: Double): String =
    if (value == kotlin.math.floor(value) && value.isFinite() && kotlin.math.abs(value) < 1e21) {
      value.toLong().toString()
    } else {
      value.toString()
    }
}
