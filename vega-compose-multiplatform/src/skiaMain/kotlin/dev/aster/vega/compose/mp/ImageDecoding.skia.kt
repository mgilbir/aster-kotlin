package dev.aster.vega.compose.mp

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.aster.vega.scene.PngEncoder
import dev.aster.vega.scene.RasterImage
import org.jetbrains.skia.Image

internal actual fun decodeImageBytes(bytes: ByteArray): ImageBitmap? = runCatching {
  Image.makeFromEncoded(bytes).toComposeImageBitmap()
}
  .getOrNull()

/**
 * Encoded first, because Skia reads images rather than pixel arrays here.
 *
 * `PngEncoder` is the engine's own — the same one the SVG renderer uses for data URLs — so a raster
 * drawn on the desktop and one embedded in an exported SVG come from identical bytes. Its deflate
 * is *stored*, so the encode is a copy with a header rather than a compression pass.
 */
internal actual fun decodeRaster(raster: RasterImage): ImageBitmap? {
  if (raster.width <= 0 || raster.height <= 0) return null
  return decodeImageBytes(PngEncoder.encode(raster))
}
