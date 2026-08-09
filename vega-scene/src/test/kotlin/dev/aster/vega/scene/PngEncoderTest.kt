package dev.aster.vega.scene

import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The PNG encoder, checked by **decoding** what it writes.
 *
 * A hand-written encoder is worth exactly as much as a decoder's willingness to read it, and every
 * part of it is a place to be wrong quietly: a CRC that no viewer checks until one does, an
 * Adler-32 over the wrong bytes, a stored block whose complement length disagrees, a channel order
 * that only shows up as a blue heatmap. `ImageIO` is a decoder nobody here wrote, so it is the one
 * that can say so. It lives in the test rather than the encoder for the reason the encoder exists
 * at all — the core has to stay KMP-portable, and `javax.imageio` is not.
 */
class PngEncoderTest {

  private fun decode(image: RasterImage): java.awt.image.BufferedImage {
    val decoded = ImageIO.read(ByteArrayInputStream(PngEncoder.encode(image)))
    assertTrue(decoded != null, "ImageIO refused the PNG this encoder wrote")
    return decoded!!
  }

  @Test
  fun `every pixel survives the round trip, alpha included`() {
    val pixels =
      intArrayOf(
        0xFF_FF_00_00.toInt(), // opaque red
        0xFF_00_FF_00.toInt(), // opaque green
        0xFF_00_00_FF.toInt(), // opaque blue
        0x80_11_22_33.toInt(), // half transparent, all three channels distinct
        0x00_00_00_00, // fully transparent
        0xFF_FF_FF_FF.toInt(), // opaque white
      )
    val decoded = decode(RasterImage(width = 3, height = 2, pixels = pixels))

    assertEquals(3, decoded.width)
    assertEquals(2, decoded.height)
    // Read back as ARGB, which is the packing the raster uses, so a channel that moved shows up.
    val read = IntArray(6) { decoded.getRGB(it % 3, it / 3) }
    assertEquals(pixels.toList(), read.toList())
  }

  @Test
  fun `a raster larger than one stored block still decodes`() {
    // A stored deflate block holds at most 65,535 bytes and a scanline costs `width * 4 + 1`, so
    // this image spans four of them — which is the only way the block chaining is exercised.
    val width = 120
    val height = 120
    val pixels = IntArray(width * height) { (0xFF shl 24) or (it and 0xFFFFFF) }
    val decoded = decode(RasterImage(width = width, height = height, pixels = pixels))

    assertEquals(width, decoded.width)
    assertEquals(height, decoded.height)
    for (index in pixels.indices step 997) {
      assertEquals(pixels[index], decoded.getRGB(index % width, index / width), "pixel $index")
    }
  }

  @Test
  fun `a one pixel raster decodes, and so does a zero width one`() {
    val single =
      decode(RasterImage(width = 1, height = 1, pixels = intArrayOf(0xFF_12_34_56.toInt())))
    assertEquals(0xFF_12_34_56.toInt(), single.getRGB(0, 0))

    // No pixels at all still has to be a *valid* file: the zlib stream needs its final block even
    // when there is nothing to put in it, and the loop that writes blocks is not entered by length.
    val empty = PngEncoder.encode(RasterImage(width = 0, height = 0, pixels = IntArray(0)))
    assertTrue(empty.size > 8, "a zero-sized raster produced no file at all")
    assertEquals(
      listOf<Byte>(-119, 80, 78, 71, 13, 10, 26, 10),
      empty.take(8),
      "the PNG signature is wrong",
    )
  }

  @Test
  fun `the data url is the encoded bytes, base64 and labelled`() {
    val image = RasterImage(width = 2, height = 1, pixels = intArrayOf(-1, 0x7F_00_80_40))
    val url = PngEncoder.dataUrl(image)

    val prefix = "data:image/png;base64,"
    assertTrue(url.startsWith(prefix), "a data url has to say what it carries: $url")
    // Against the JDK's own base64 rather than against a recorded string, so the assertion is about
    // the encoding and not about a constant somebody pasted.
    val expected: String = Base64.getEncoder().encodeToString(PngEncoder.encode(image))
    assertEquals(expected, url.removePrefix(prefix), "the payload is not the encoded file")
  }
}
