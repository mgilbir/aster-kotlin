package dev.aster.vega.dataflow.transform

import dev.aster.vega.expression.JsSemantics
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asString
import dev.aster.vega.model.field
import dev.aster.vega.model.isNullish
import dev.aster.vega.scene.SceneColor

/**
 * The keys upstream adds to the row it hands a `heatmap` colour or opacity expression.
 *
 * They are ordinary property names with a `$` in them, not syntax: `datum.$value / datum.$max` is
 * what a specification writes. Whether an expression mentions any of them is also what decides
 * whether it is evaluated once or once per pixel, which is the difference between a heatmap that
 * compiles instantly and one that runs four thousand expression evaluations.
 */
private val PIXEL_KEYS = listOf("\$x", "\$y", "\$value", "\$max")

/**
 * `heatmap`: a raster grid rendered to an image, one pixel per grid cell.
 *
 * The image is carried as `{width, height, pixels}` with each pixel a packed `0xAARRGGBB` — a scene
 * node needs numbers it can draw rather than a canvas it has to ask, and this engine has no canvas
 * to ask. `ImageNode` takes it as a raster and the SVG renderer encodes it as a PNG data URL.
 *
 * The default opacity is the one thing here that is not obvious: with no `opacity` given upstream
 * uses `$value / $max`, so an empty grid is transparent and the densest cell is opaque, and the
 * colour alone says nothing. `color` defaults to mid-grey.
 */
public object HeatmapTransform : Transform {
  override val type: String = "heatmap"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val path = params.string("field")
    val as0 = params.string("as") ?: "image"
    val shared = params.string("resolve") == "shared"

    val grids = input.map { datum ->
      // An accessor path: `"datum.grid"` on a mark transform, where the row is the scene item's
      // own `datum`, and a plain column name on a dataset transform.
      Grid.from(if (path == null) datum else datum.field(path))
    }
    if (grids.any { it == null }) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "heatmap needs a raster grid — an object with 'width', 'height' and 'values' — " +
          if (path == null) "as the row itself" else "in field '$path'",
        operator = type,
      )
      return input
    }

    val globalMax = grids.filterNotNull().maxOfOrNull { it.values.maxOrNull() ?: 0.0 } ?: 0.0
    val colour = PixelExpression(params.fields["color"], context, type)
    val opacity = PixelExpression(params.fields["opacity"], context, type)

    return input.mapIndexed { index, datum ->
      val grid = grids[index]!!
      val max = if (shared) globalMax else (grid.values.maxOrNull() ?: 0.0)
      // Each row's colour and opacity are resolved afresh. A constant *per grid* is still a
      // function of that grid's row — `scale('color', datum.datum.Origin)` gives each series its
      // own colour — so a cache that outlived the row would paint every grid the first one's.
      colour.forget()
      opacity.forget()
      datum.withField(as0, raster(grid, datum, max, colour, opacity))
    }
  }

  private fun raster(
    grid: Grid,
    datum: VegaValue,
    max: Double,
    colour: PixelExpression,
    opacity: PixelExpression,
  ): VegaValue {
    val x1 = grid.x1.toInt()
    val y1 = grid.y1.toInt()
    val x2 = grid.x2?.toInt() ?: grid.width
    val y2 = grid.y2?.toInt() ?: grid.height
    val width = maxOf(0, x2 - x1)
    val height = maxOf(0, y2 - y1)
    val pixels = IntArray(width * height)

    val base = (datum as? VegaValue.Obj)?.fields ?: emptyMap()
    var k = 0
    for (j in y1 until y2) {
      for (i in x1 until x2) {
        val value = grid.values.getOrElse(i + j * grid.width) { 0.0 }
        // The proxy upstream hands the expressions: the row's own columns plus the pixel's. Built
        // per pixel because that is what an expression reading `$value` needs; an expression that
        // reads none of the four is evaluated once against the first one and reused.
        val proxy =
          VegaValue.Obj(
            LinkedHashMap(base).apply {
              put("\$x", VegaValue.Num((i - x1).toDouble()))
              put("\$y", VegaValue.Num((j - y1).toDouble()))
              put("\$value", VegaValue.Num(value))
              put("\$max", VegaValue.Num(max))
            }
          )
        val rgb = colour.colour(proxy)
        val alpha = opacity.alpha(proxy, value, max)
        pixels[k++] =
          (channel(alpha * 255.0) shl 24) or
            (channel(rgb.red * 255.0) shl 16) or
            (channel(rgb.green * 255.0) shl 8) or
            channel(rgb.blue * 255.0)
      }
    }

    return VegaValue.Obj(
      linkedMapOf(
        "width" to VegaValue.Num(width.toDouble()),
        "height" to VegaValue.Num(height.toDouble()),
        "pixels" to VegaValue.Arr(pixels.map { VegaValue.Num(it.toDouble()) }),
      )
    )
  }

  /**
   * A colour channel as a byte, truncated rather than rounded.
   *
   * Upstream writes `~~(255 * opacity)` for the alpha, which truncates toward zero, and d3-color's
   * own channels are already whole numbers by the time they are read. Rounding instead moves half
   * the pixels of a gradient by one level.
   */
  private fun channel(value: Double): Int {
    if (value.isNaN()) return 0
    return value.toInt().coerceIn(0, 255)
  }
}

/**
 * A `heatmap` colour or opacity: a constant, or an expression evaluated against the pixel.
 *
 * [perPixel] is upstream's own optimisation and its own test: an expression that mentions none of
 * `$x`, `$y`, `$value` or `$max` cannot vary across the grid, so it is evaluated once. Upstream
 * inspects the compiled accessor's field list; this reads the source text, which is coarser in
 * principle — a specification could write `datum['$value']` — and identical in practice.
 */
private class PixelExpression(
  declared: VegaValue?,
  context: TransformContext,
  operator: String,
) {
  private val source: String? =
    (declared as? VegaValue.Obj)?.fields?.get("expr")?.asString()?.takeIf { it.isNotEmpty() }

  private val constant: VegaValue? = if (source == null) declared else null

  private val expression =
    source?.let { TupleExpression(it, context, operator) }?.takeIf { it.isUsable }

  val perPixel: Boolean = source != null && PIXEL_KEYS.any { source.contains(it) }

  /** Evaluated once per row when it cannot vary, so a constant colour costs one, not 60,000. */
  private var cached: VegaValue? = null

  /** Drops the cache at a row boundary; see the call site for why it is per row, not per chart. */
  fun forget() {
    cached = null
  }

  private fun value(proxy: VegaValue): VegaValue? {
    if (expression == null) return constant
    if (!perPixel) {
      cached?.let {
        return it
      }
      // Against the proxy, not against nothing: an expression that varies with no pixel may still
      // read the row's own columns, which the proxy carries alongside the four pixel keys.
      val once = expression.evaluate(proxy) ?: VegaValue.Null
      cached = once
      return once
    }
    return expression.evaluate(proxy)
  }

  fun colour(proxy: VegaValue): SceneColor {
    val resolved = value(proxy)
    val text = resolved?.takeIf { !it.isNullish }?.asString()
    if (text.isNullOrEmpty()) return MID_GREY
    return SceneColor.parse(text) ?: MID_GREY
  }

  /** With nothing given, the density itself: an empty cell transparent and the densest opaque. */
  fun alpha(proxy: VegaValue, pixel: Double, max: Double): Double {
    if (expression == null && constant == null) {
      val ratio = pixel / max
      return if (ratio.isFinite()) ratio else 0.0
    }
    val resolved = value(proxy) ?: return 0.0
    val number = JsSemantics.toNumber(resolved)
    return if (number.isFinite()) number else 0.0
  }

  private companion object {
    val MID_GREY: SceneColor = SceneColor.parse("#888")!!
  }
}
