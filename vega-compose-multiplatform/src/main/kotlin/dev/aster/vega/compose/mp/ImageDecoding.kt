package dev.aster.vega.compose.mp

import androidx.compose.ui.graphics.ImageBitmap
import dev.aster.vega.scene.RasterImage

/**
 * Turns encoded image bytes into something Compose can draw.
 *
 * The one thing Compose Multiplatform has no common answer for. Android decodes with
 * `BitmapFactory`; the Skia-backed targets — the desktop and both iOS ones — use
 * `org.jetbrains.skia.Image`. There is no shared API over the two, so this is an `expect` rather
 * than a renderer that quietly draws no images.
 *
 * Returns null for bytes that are not an image this platform can read, which a caller reports
 * rather than swallows.
 */
internal expect fun decodeImageBytes(bytes: ByteArray): ImageBitmap?

/**
 * A raster the engine produced, as an image.
 *
 * Separate from [decodeImageBytes] because the platforms want different things: Android builds a
 * `Bitmap` straight from the `IntArray` — `0xAARRGGBB` is already `ARGB_8888`, so it is a copy —
 * while Skia takes encoded bytes, so that side goes through the engine's own PNG encoder. Encoding
 * a raster only to decode it again would be silly on Android and is the shortest correct path on
 * Skia.
 */
internal expect fun decodeRaster(raster: RasterImage): ImageBitmap?
