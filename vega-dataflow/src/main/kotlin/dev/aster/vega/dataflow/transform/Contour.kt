@file:OptIn(InternalAsterVegaApi::class)

package dev.aster.vega.dataflow.transform

import dev.aster.vega.expression.JsSemantics
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.InternalAsterVegaApi
import dev.aster.vega.model.VegaValue
import kotlin.math.floor
import kotlin.math.ln

/**
 * `contour`: contours of a **kernel density estimate** of point data, as GeoJSON `MultiPolygon`s.
 *
 * Upstream composes this out of two operators rather than writing an algorithm of its own —
 * `density2D` for the grid and `contours` for the level sets — and so does this: [Kde2dTransform]
 * already estimates the density and [MarchingSquares] already traces the isolines, both against
 * upstream's own vectors. What is left is the composition, and the composition is where the
 * interesting decisions are.
 *
 * **Two ways in.** With a `values` array the grid is *given*, `size` states its dimensions, and the
 * coordinates come out in grid space. Without one the grid is *estimated* from the rows in the
 * pulse, `size` is the view's own pixel dimensions, and the coordinates have to be mapped back out
 * of the padded grid the estimator built — which is what [postTransform] does.
 *
 * **`zero` follows which way in was taken**, and this is the one place a plausible simplification
 * would be wrong. Upstream passes `!!values`: fold zero into the extent for a given grid, do not
 * for an estimated one. A density estimate is already non-negative and comes arbitrarily close to
 * zero at its edges, so including zero would put the lowest contour at a height the surface only
 * touches asymptotically — a ring around the whole plot, or none at all.
 *
 * **Superseded upstream but not removed**, and that is why it is here: `isocontour` over `kde2d` is
 * the arrangement upstream now documents, and `contour` is the one every existing specification
 * written before that still uses. A specification is text somebody else wrote, and refusing it
 * because a newer spelling exists is refusing the corpus.
 */
public object ContourTransform : Transform {
  override val type: String = "contour"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val size = (params.fields["size"] as? VegaValue.Arr)?.values
    if (size == null || size.size < 2) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "contour needs a 'size' of [width, height]",
        operator = type,
      )
      return input
    }
    val dx = JsSemantics.toNumber(size[0])
    val dy = JsSemantics.toNumber(size[1])
    if (!(dx >= 0) || !(dy >= 0)) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "contour's 'size' must be two non-negative numbers",
        operator = type,
      )
      return input
    }

    val given = (params.fields["values"] as? VegaValue.Arr)?.values
    val smooth = params.boolean("smooth") ?: true
    val count = params.number("count")?.toInt() ?: 10
    val nice = params.boolean("nice") ?: false
    val explicit =
      (params.fields["thresholds"] as? VegaValue.Arr)?.values?.map { JsSemantics.toNumber(it) }

    val grid: Grid
    val post: ((DoubleArray) -> DoubleArray)?
    if (given != null) {
      val width = dx.toInt()
      val height = dy.toInt()
      if (width <= 0 || height <= 0 || given.size < width * height) {
        context.diagnostics.error(
          DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
          "contour's 'values' holds ${given.size} numbers, which is fewer than the " +
            "${width * height} a ${width} x ${height} grid needs",
          operator = type,
        )
        return input
      }
      grid = Grid(width, height, DoubleArray(given.size) { JsSemantics.toNumber(given[it]) })
      post = null
    } else {
      val estimated = estimate(input, params, dx, dy, context) ?: return input
      grid = estimated
      // A **negative** scale would flip the winding, which the hole classification depends on; the
      // estimator's scale is `1 << k` and cannot be, so [postTransform] is only ever a translation
      // and a positive stretch. Written as the general form anyway, because that is what upstream
      // hands to `transform()` and a reader comparing the two should find the same shape.
      post = postTransform(grid)
    }

    // A grid nothing landed in has no extent to cut, so there is nothing to draw and nothing wrong.
    val thresholds = explicit ?: quantizeLevels(grid.values.asList(), count, nice, given != null)

    return thresholds.map { value ->
      val polygons = MarchingSquares.contour(grid.values, grid.width, grid.height, value, smooth)
      VegaValue.Obj(
        linkedMapOf(
          "type" to VegaValue.Str("MultiPolygon"),
          "value" to VegaValue.Num(value),
          "coordinates" to coordinates(polygons, post),
        )
      )
    }
  }

  /**
   * The density grid, from the same six parameters upstream copies across.
   *
   * `PARAMS = ['x', 'y', 'weight', 'size', 'cellSize', 'bandwidth']` in `KDE2D.js`, applied to a
   * fresh `density2D`. So this reads exactly what `kde2d` reads, from one implementation, and a
   * `contour` and an `isocontour`-over-`kde2d` written with the same parameters estimate the same
   * surface — which they must, or the newer spelling would not be a replacement for this one.
   *
   * `counts` is **not** among them, and it is not left at its default either: `contour` calls
   * `density2D()(values, true)` — the second positional argument *is* `counts`, so the grid is
   * points per square pixel and not a probability density summing to one. `kde2d` defaults it to
   * false, which is the opposite, so the one parameter the two operators disagree about is the one
   * that is never written down.
   *
   * Worth knowing how nearly this escaped. The factor is a **constant** over the whole grid —
   * `2^(-2k)` against `1 / sum` — and the thresholds are derived from that same grid's extent, so
   * every contour comes out at exactly the same place either way. Only the `value` each one carries
   * changes, by 2.5x on the probe below. The `contour-legacy` fixture compares 450 marks and passes
   * with this wrong, because a scale mapping `value` to a colour takes its domain from the same
   * data and lands on the same colour. It took comparing the numbers themselves.
   */
  private fun estimate(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    dx: Double,
    dy: Double,
    context: TransformContext,
  ): Grid? {
    val xs =
      Kde2dTransform.accessor(params.fields["x"], context)
        ?: run {
          Kde2dTransform.reportMissing(context, "x", input)
          return null
        }
    val ys =
      Kde2dTransform.accessor(params.fields["y"], context)
        ?: run {
          Kde2dTransform.reportMissing(context, "y", input)
          return null
        }
    val weights = Kde2dTransform.accessor(params.fields["weight"], context)

    // `cellSize` is a power of two however it is written, because upstream keeps `log2(cellSize)`
    // and shifts by it: 3 and 4 both mean 4.
    val cellSize = params.number("cellSize") ?: 4.0
    if (cellSize < 1) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "contour's 'cellSize' must be at least 1",
        operator = type,
      )
      return null
    }
    val k = floor(ln(cellSize) / ln(2.0)).toInt()

    val bandwidth =
      when (val declared = params.fields["bandwidth"]) {
        is VegaValue.Arr ->
          declared.values
            .map { JsSemantics.toNumber(it) }
            .let {
              if (it.size == 1) listOf(it[0], it[0]) else it
            }
        null -> listOf(-1.0, -1.0)
        else -> JsSemantics.toNumber(declared).let { listOf(it, it) }
      }
    if (bandwidth.size != 2) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "contour's 'bandwidth' is one number or two",
        operator = type,
      )
      return null
    }

    val estimated =
      Kde2dTransform.density(input, xs, ys, weights, dx, dy, k, bandwidth, counts = true)
    return Grid.from(estimated)
  }

  /**
   * Grid coordinates mapped back into the space the chart draws in.
   *
   * The estimator pads its grid by the blur radius on every side and works at one cell per `1 << k`
   * pixels, so a contour traced on it sits at neither the origin nor the scale a mark is placed at.
   * Upstream's `transform(grid, scale, scale, 0, 0)` subtracts the padding — `x1`, `y1` — and
   * multiplies by the cell size, and the translation is **zero**: `contour` puts the contour back
   * in the view's own pixels, where `isocontour` lets a specification say where to put it.
   */
  private fun postTransform(grid: Grid): (DoubleArray) -> DoubleArray {
    val scale = grid.scale ?: 1.0
    return { point ->
      doubleArrayOf((point[0] - grid.x1) * scale, (point[1] - grid.y1) * scale)
    }
  }

  private fun coordinates(
    polygons: List<List<List<DoubleArray>>>,
    post: ((DoubleArray) -> DoubleArray)?,
  ): VegaValue.Arr =
    VegaValue.Arr(
      polygons.map { polygon ->
        VegaValue.Arr(
          polygon.map { ring ->
            VegaValue.Arr(
              ring.map { point ->
                val placed = post?.invoke(point) ?: point
                VegaValue.Arr(listOf(VegaValue.Num(placed[0]), VegaValue.Num(placed[1])))
              }
            )
          }
        )
      }
    )
}
