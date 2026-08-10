package dev.aster.vega.scene

/**
 * Pixels a mark draws directly, rather than an address a renderer has to fetch.
 *
 * The `heatmap` transform produces one: a raster grid coloured cell by cell. Upstream keeps a
 * `<canvas>` on the scene item and hands it to its renderer; there is no canvas here and a scene
 * node holds numbers a renderer can use, so the pixels themselves are the payload.
 *
 * Packed `0xAARRGGBB` per pixel, row by row from the top left — Android's `ARGB_8888` order, which
 * is the one platform that can take them without rearrangement.
 */
public class RasterImage(
  public val width: Int,
  public val height: Int,
  public val pixels: IntArray,
) {
  init {
    require(width >= 0 && height >= 0) {
      "A raster needs a non-negative size, got $width x $height"
    }
    require(pixels.size == width * height) {
      "A ${width}x$height raster needs ${width * height} pixels, got ${pixels.size}"
    }
  }

  /**
   * A cheap, order-sensitive digest of every pixel, for comparing two rasters without holding both.
   *
   * FNV-1a over the packed values. Used by the differential harness, which needs to say "these are
   * the same pixels" about an image it cannot otherwise see: an image mark is compared by its box,
   * and a blank raster in the right box would otherwise pass.
   */
  public val digest: Long
    get() {
      var hash = -0x340d631b7bdddcdbL
      for (pixel in pixels) {
        var value = pixel.toLong() and 0xFFFFFFFFL
        repeat(4) {
          hash = hash xor (value and 0xFF)
          hash *= 0x100000001b3L
          value = value shr 8
        }
      }
      return hash
    }
}
