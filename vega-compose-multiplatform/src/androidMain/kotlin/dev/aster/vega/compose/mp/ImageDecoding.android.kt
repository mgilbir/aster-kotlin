package dev.aster.vega.compose.mp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.aster.vega.scene.RasterImage

internal actual fun decodeImageBytes(bytes: ByteArray): ImageBitmap? =
  BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()

/**
 * Built straight from the pixels, with no PNG in between.
 *
 * The engine's raster is `0xAARRGGBB` per pixel, which is exactly what `ARGB_8888` wants — so this
 * is a copy rather than an encode and a decode. `AndroidCanvasSceneRenderer` does the same thing
 * for the same reason.
 */
internal actual fun decodeRaster(raster: RasterImage): ImageBitmap? {
  if (raster.width <= 0 || raster.height <= 0) return null
  return Bitmap.createBitmap(raster.pixels, raster.width, raster.height, Bitmap.Config.ARGB_8888)
    .asImageBitmap()
}
