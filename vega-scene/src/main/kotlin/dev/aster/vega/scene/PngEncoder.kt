package dev.aster.vega.scene

/**
 * A [RasterImage] as a PNG data URL.
 *
 * Written here rather than taken from a platform because the core has to stay KMP-portable — there
 * is no `ImageIO` and no `Bitmap.compress` to reach for — and because a heatmap's pixels have to
 * survive into an SVG export unchanged.
 *
 * The compression is deliberately **none**: zlib's *stored* block type carries the bytes verbatim,
 * which a decoder must accept and which keeps this to arithmetic anyone can check. A heatmap grid
 * is a few thousand pixels, so the size that costs is not worth a deflate implementation and the
 * bugs that come with one.
 */
public object PngEncoder {

  /** `data:image/png;base64,…`, ready for an SVG `href`. */
  public fun dataUrl(image: RasterImage): String = "data:image/png;base64," + base64(encode(image))

  /** The PNG file: signature, `IHDR`, one `IDAT` of stored deflate blocks, `IEND`. */
  public fun encode(image: RasterImage): ByteArray {
    val out = ByteWriter()
    out.bytes(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))

    val header = ByteWriter()
    header.int(image.width)
    header.int(image.height)
    header.byte(8) // eight bits per channel
    header.byte(6) // truecolour with alpha
    header.byte(0) // deflate
    header.byte(0) // no filter beyond the per-scanline one
    header.byte(0) // no interlacing
    chunk(out, "IHDR", header.toByteArray())

    chunk(out, "IDAT", zlib(scanlines(image)))
    chunk(out, "IEND", ByteArray(0))
    return out.toByteArray()
  }

  /**
   * The raw image data: each row prefixed by its filter type, which here is always `0`, none.
   *
   * PNG is RGBA in that order; the raster is packed ARGB, so the alpha moves from the front to the
   * back of every pixel.
   */
  private fun scanlines(image: RasterImage): ByteArray {
    val stride = image.width * 4
    val data = ByteArray(image.height * (stride + 1))
    var at = 0
    for (row in 0 until image.height) {
      data[at++] = 0
      for (column in 0 until image.width) {
        val pixel = image.pixels[row * image.width + column]
        data[at++] = ((pixel shr 16) and 0xFF).toByte()
        data[at++] = ((pixel shr 8) and 0xFF).toByte()
        data[at++] = (pixel and 0xFF).toByte()
        data[at++] = ((pixel shr 24) and 0xFF).toByte()
      }
    }
    return data
  }

  /** A zlib stream of stored blocks: a two-byte header, the data, and an Adler-32 of it. */
  private fun zlib(data: ByteArray): ByteArray {
    val out = ByteWriter()
    out.byte(0x78) // deflate, 32K window
    out.byte(0x01) // no preset dictionary, fastest compression level
    var offset = 0
    while (offset < data.size || data.isEmpty()) {
      val length = minOf(MAX_STORED_BLOCK, data.size - offset)
      val last = offset + length >= data.size
      out.byte(if (last) 1 else 0)
      // A stored block's length is little-endian, and is followed by its own complement.
      out.byte(length and 0xFF)
      out.byte((length shr 8) and 0xFF)
      out.byte(length.inv() and 0xFF)
      out.byte((length.inv() shr 8) and 0xFF)
      out.bytes(data, offset, length)
      offset += length
      if (last) break
    }
    out.int(adler32(data))
    return out.toByteArray()
  }

  private fun chunk(out: ByteWriter, name: String, body: ByteArray) {
    out.int(body.size)
    val typed = ByteWriter()
    for (character in name) typed.byte(character.code)
    typed.bytes(body)
    val payload = typed.toByteArray()
    out.bytes(payload)
    out.int(crc32(payload))
  }

  private fun adler32(data: ByteArray): Int {
    var a = 1L
    var b = 0L
    for (byte in data) {
      a = (a + (byte.toInt() and 0xFF)) % 65521
      b = (b + a) % 65521
    }
    return ((b shl 16) or a).toInt()
  }

  private val CRC_TABLE =
    IntArray(256) { index ->
      var c = index
      repeat(8) { c = if (c and 1 != 0) (0xEDB88320.toInt() xor (c ushr 1)) else (c ushr 1) }
      c
    }

  private fun crc32(data: ByteArray): Int {
    var c = -1
    for (byte in data) {
      c = CRC_TABLE[(c xor byte.toInt()) and 0xFF] xor (c ushr 8)
    }
    return c.inv()
  }

  private const val MAX_STORED_BLOCK = 65535

  private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

  private fun base64(data: ByteArray): String {
    val out = StringBuilder((data.size + 2) / 3 * 4)
    var index = 0
    while (index + 2 < data.size) {
      val triple =
        ((data[index].toInt() and 0xFF) shl 16) or
          ((data[index + 1].toInt() and 0xFF) shl 8) or
          (data[index + 2].toInt() and 0xFF)
      out.append(ALPHABET[(triple shr 18) and 0x3F])
      out.append(ALPHABET[(triple shr 12) and 0x3F])
      out.append(ALPHABET[(triple shr 6) and 0x3F])
      out.append(ALPHABET[triple and 0x3F])
      index += 3
    }
    when (data.size - index) {
      1 -> {
        val triple = (data[index].toInt() and 0xFF) shl 16
        out.append(ALPHABET[(triple shr 18) and 0x3F])
        out.append(ALPHABET[(triple shr 12) and 0x3F])
        out.append("==")
      }
      2 -> {
        val triple =
          ((data[index].toInt() and 0xFF) shl 16) or ((data[index + 1].toInt() and 0xFF) shl 8)
        out.append(ALPHABET[(triple shr 18) and 0x3F])
        out.append(ALPHABET[(triple shr 12) and 0x3F])
        out.append(ALPHABET[(triple shr 6) and 0x3F])
        out.append('=')
      }
    }
    return out.toString()
  }

  /** A growable byte buffer; `kotlin.io` has none that is portable. */
  private class ByteWriter {
    private var buffer = ByteArray(1024)
    private var size = 0

    fun byte(value: Int) {
      ensure(1)
      buffer[size++] = value.toByte()
    }

    fun int(value: Int) {
      byte(value shr 24)
      byte(value shr 16)
      byte(value shr 8)
      byte(value)
    }

    fun bytes(data: ByteArray, offset: Int = 0, length: Int = data.size) {
      ensure(length)
      data.copyInto(buffer, size, offset, offset + length)
      size += length
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)

    private fun ensure(extra: Int) {
      if (size + extra <= buffer.size) return
      var capacity = buffer.size
      while (capacity < size + extra) capacity *= 2
      buffer = buffer.copyOf(capacity)
    }
  }
}
