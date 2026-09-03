package dev.aster.vega.dataflow.transform

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One word as the layout sees it: what to draw, how big, and where it ended up.
 *
 * `x0`/`y0`/`x1`/`y1` are the sprite's extent **relative to the word's own centre**, which is what
 * lets a placement be tested by adding `x`/`y` to them. `y0` and `y1` are narrowed by the sprite: a
 * rasteriser trims the blank rows above and below the glyphs, so a word of lower-case letters
 * occupies less height than its font size claims and can sit closer under the word above it.
 */
internal class CloudWord(
  val index: Int,
  val text: String,
  val font: String,
  val style: String,
  val weight: String,
  /** `~~(fontSize + 1e-14)`, upstream's truncation: the integer size the sprite is drawn at. */
  val size: Int,
  val rotate: Double,
  val padding: Double,
) {
  var x: Int = 0
  var y: Int = 0
  var width: Int = 0
  var height: Int = 0
  var x0: Int = 0
  var y0: Int = 0
  var x1: Int = 0
  var y1: Int = 0
  var hasText: Boolean = false
  var sprite: IntArray? = null
  var placed: Boolean = false
}

/**
 * The **one** thing a word cloud needs that no common Kotlin can do: turn a word into pixels.
 *
 * Upstream draws each word into a `<canvas>` and reads `getImageData` back, giving a bit per pixel
 * that says whether a glyph covers it. That mask is what lets `lower` nestle into the bowl of a `C`
 * above it, and it is the reason `wordcloud` was the last transform standing: there is no portable
 * glyph rasteriser here, and there should not be one — a font is the host's.
 *
 * So it is a seam rather than an obstacle. A host that can rasterise (Android, Core Text, Skia)
 * gives the same nestling upstream gives; [BoxSprites] gives a rectangle per word, which is a valid
 * cloud with more air in it. Which one was used is reported, because a chart that looks looser than
 * upstream's for a stated reason is a different thing from one that is wrong.
 *
 * **The measurement half is not optional.** [measure] decides a word's box, and through the box its
 * placement, so every implementation has to answer it the way upstream's `measureText` does — for
 * the string with an `m` appended, at font size `size + 1`. The width is then rounded **up to a
 * multiple of 32**, which is the one mercy here: a few tenths of a pixel of disagreement between
 * two text engines usually lands in the same bucket and changes nothing.
 */
internal interface CloudSprites {

  /**
   * The unrotated width of [text] followed by an `m`, at font size `size + 1`.
   *
   * The trailing `m` is upstream's own padding — `measureText(d.text + 'm').width` — and it is not
   * a mistake to reproduce: it is what stops two words touching, and dropping it would tighten
   * every cloud by roughly one character.
   */
  fun measure(word: CloudWord): Double

  /**
   * The glyph mask for [word], row-major, one bit per pixel, `width` rounded up to a multiple
   * of 32.
   *
   * Null means "I cannot rasterise", and the caller fills the box instead. Returning a mask for
   * some words and null for others is allowed and is what a host with a partial font stack does.
   *
   * The returned array is `height * (width shr 5)` ints, and bit `31 - (i % 32)` of word `(width
   * shr 5) * j + (i shr 5)` is pixel `(i, j)` — upstream's packing, kept so a recorded mask can be
   * handed straight in.
   */
  fun rasterise(word: CloudWord, width: Int, height: Int): IntArray? = null

  /**
   * Whether [rasterise] returns real glyph masks, as against filled boxes.
   *
   * It changes the **algorithm**, not just the quality, which is why it has to be asked rather than
   * inferred later. Upstream will only place a word where its rectangle *overlaps* the bounding box
   * of everything placed so far — a compaction rule that keeps a cloud from spraying outwards, and
   * one that works because a glyph mask is mostly holes, so a word can sit inside another's
   * rectangle without touching its ink.
   *
   * With filled boxes the two conditions are mutually exclusive: a rectangle cannot overlap the
   * bounds of a solid word without colliding with it. The first word is placed, the bounds become
   * exactly its own rectangle, and nothing can ever be placed again. Measured before this existed:
   * **one word of ten**. So the compaction rule is skipped where it cannot be satisfied, and the
   * spiral's own outward walk from the centre is what keeps the cloud tight instead.
   */
  val rasterises: Boolean
    get() = false
}

/**
 * Every word a filled rectangle: no rasteriser, no nestling.
 *
 * The honest fallback, and the default. A cloud built this way has the same words at the same sizes
 * in the same order and more space between them, because a rectangle claims the whitespace inside a
 * `W` that upstream's mask gives away. It never overlaps and never mis-drops, which are the two
 * things that would make it wrong rather than merely looser.
 */
internal class BoxSprites(private val measurer: (CloudWord) -> Double) : CloudSprites {
  override fun measure(word: CloudWord): Double = measurer(word)
}

/**
 * Jason Davies' word-cloud layout, ported from `vega-wordcloud/src/CloudLayout.js`.
 *
 * Words are placed largest first. Each starts at a random point near the middle and walks outwards
 * along a spiral until it finds somewhere it does not collide, and is dropped if it never does. The
 * board is a bitmap — one bit per pixel of the whole canvas, packed 32 to an int — so a collision
 * test is a run of `and`s over a couple of dozen words rather than a comparison against every word
 * already placed.
 *
 * **Ported rather than reimplemented**, because almost every line is bit arithmetic whose intent is
 * not recoverable from what it computes. `lx = tag.x - (w << 4)` is "left edge, given the width is
 * a whole number of 32-bit words and the anchor is the centre"; `sx = lx and 0x7f` is the shift
 * within the word *and* — through the `0x7f` rather than `0x1f` — the reason a negative `lx` still
 * indexes correctly. Rewriting any of it more clearly would be rewriting it differently.
 *
 * **The random draws are part of the answer.** Two per word for the starting point and one per
 * placement attempt for the spiral's direction, in that order. Upstream takes them from
 * `vega-statistics`'s generator, which a chart seeds; drawing a different number of values, or the
 * same number in a different order, gives a different cloud from the same seed and there is no
 * partial credit — every word after the divergence moves.
 */
internal class CloudLayout(
  private val words: List<CloudWord>,
  private val size: IntArray,
  private val spiral: String,
  private val sprites: CloudSprites,
  private val random: () -> Double,
) {

  /** The scratch canvas upstream rasterises into: `1 << 11` wide in bits, `1 << 11` tall. */
  private val cw = (1 shl 11) shr 5
  private val ch = 1 shl 11

  private val board = IntArray((size[0] shr 5) * size[1])
  private var bounds: IntArray? = null

  /** The words that found a place, largest first, with `x`/`y` set. */
  fun layout(): List<CloudWord> {
    val data = words.sortedByDescending { it.size }
    val placed = mutableListOf<CloudWord>()
    for (i in data.indices) {
      val word = data[i]
      word.x = (size[0] * (random() + 0.5)).toInt() shr 1
      word.y = (size[1] * (random() + 0.5)).toInt() shr 1
      cloudSprite(data, i)
      if (word.hasText && place(word)) {
        placed.add(word)
        word.placed = true
        val current = bounds
        if (current != null) {
          cloudBounds(current, word)
        } else {
          bounds =
            intArrayOf(word.x + word.x0, word.y + word.y0, word.x + word.x1, word.y + word.y1)
        }
        // Upstream's own comment calls this a temporary hack, and it is the reason the transform
        // adds half the size back afterwards: the placement is recentred on the origin here.
        word.x -= size[0] shr 1
        word.y -= size[1] shr 1
      }
    }
    return placed
  }

  /**
   * Fills in the sprite for every word from [from] onwards, packing them into the scratch canvas.
   *
   * Called once per word rather than once in total, which is upstream's shape and not an
   * optimisation anybody would arrive at — it re-does the same work for every remaining word each
   * time round. It is kept because of what it decides: a word only gets `hasText` if it **fitted**
   * in the scratch canvas, and which words fit depends on which are still unplaced. Doing this once
   * up front would place a word upstream drops.
   */
  private fun cloudSprite(data: List<CloudWord>, from: Int) {
    var x = 0
    var y = 0
    var maxh = 0
    for (index in from until data.size) {
      val word = data[index]
      var w = sprites.measure(word)
      var h = word.size shl 1
      if (word.rotate != 0.0) {
        val radians = word.rotate * RADIANS
        val sr = sin(radians)
        val cr = cos(radians)
        val wcr = w * cr
        val wsr = w * sr
        val hcr = h * cr
        val hsr = h * sr
        w = (((maxOf(abs(wcr + hsr), abs(wcr - hsr)) + 0x1f).toInt() shr 5) shl 5).toDouble()
        h = maxOf(abs(wsr + hcr), abs(wsr - hcr)).toInt()
      } else {
        w = (((w + 0x1f).toInt() shr 5) shl 5).toDouble()
      }
      if (h > maxh) maxh = h
      if (x + w >= (cw shl 5)) {
        x = 0
        y += maxh
        maxh = 0
      }
      // The scratch canvas is full. Every word from here on keeps `hasText = false` and is dropped,
      // which is upstream's `break` and a real behaviour rather than a guard: a cloud of very many
      // very large words loses its tail.
      if (y + h >= ch) break
      word.width = w.toInt()
      word.height = h
      word.x1 = word.width shr 1
      word.y1 = h shr 1
      word.x0 = -word.x1
      word.y0 = -word.y1
      word.hasText = true
      x += w.toInt()
    }

    for (index in data.indices.reversed()) {
      if (index < from) break
      val word = data[index]
      if (!word.hasText) continue
      val w = word.width
      val w32 = w shr 5
      val rows = word.y1 - word.y0
      val mask = sprites.rasterise(word, w, rows)
      val sprite = IntArray(maxOf(0, rows * w32))
      if (mask == null) {
        // No rasteriser: the whole box is occupied, and there are no blank rows to trim.
        sprite.fill(-1)
        word.sprite = sprite
        continue
      }
      // Upstream's `seen` is **cumulative and never reset**, which is not a slip: it means only the
      // blank rows *above* the glyphs are trimmed. Once any row has ink, every row after it counts
      // as part of the word, so a descender's blank rows and the gap under a row of capitals stay
      // in. Resetting it per row — the obvious reading — trims the bottom too, and every word then
      // sits a few pixels higher than upstream puts it.
      var seen = 0
      var seenRow = -1
      var h = rows
      var j = 0
      var source = 0
      while (j < h) {
        var rowInk = 0
        for (i in 0 until w) {
          val bit = 1 shl (31 - (i % 32))
          val at = w32 * source + (i shr 5)
          val lit = source < rows && at < mask.size && (mask[at] and bit) != 0
          if (lit) {
            sprite[w32 * j + (i shr 5)] = sprite[w32 * j + (i shr 5)] or bit
            rowInk = rowInk or bit
          }
        }
        seen = seen or rowInk
        if (seen != 0) {
          seenRow = j
          j++
        } else {
          // A blank row above the glyphs is not part of the word: it is dropped and `y0` moves
          // down, which is how a line of lower-case letters ends up shorter than its font size and
          // can tuck under the word above.
          word.y0++
          h--
        }
        source++
      }
      word.y1 = word.y0 + seenRow
      word.sprite = sprite.copyOf(maxOf(0, (word.y1 - word.y0) * w32))
    }
  }

  /**
   * Walks [word] out along the spiral until it fits, or until the spiral leaves the canvas.
   *
   * The two tests are not the same and both are needed: `cloudCollide` asks whether this word's
   * pixels meet any already-set pixel, and `collideRects` asks whether it meets the *bounding box*
   * of everything placed so far. Upstream checks the cheap box test only when the bitmap test
   * passes, and only once anything has been placed at all.
   */
  private fun place(word: CloudWord): Boolean {
    val startX = word.x
    val startY = word.y
    val maxDelta = hypot(size[0].toDouble(), size[1].toDouble())
    val s = spiralOf(spiral, size)
    val dt = if (random() < 0.5) 1 else -1
    var t = -dt

    while (true) {
      t += dt
      val dxdy = s(t.toDouble()) ?: return false
      val dx = dxdy[0].toInt()
      val dy = dxdy[1].toInt()
      if (minOf(abs(dx), abs(dy)) >= maxDelta) return false

      word.x = startX + dx
      word.y = startY + dy
      if (
        word.x + word.x0 < 0 ||
          word.y + word.y0 < 0 ||
          word.x + word.x1 > size[0] ||
          word.y + word.y1 > size[1]
      ) {
        continue
      }
      val current = bounds
      if (current == null || !cloudCollide(word)) {
        // See [CloudSprites.rasterises]: the overlap requirement is upstream's compaction rule and
        // it is only satisfiable when a sprite has holes in it.
        if (current == null || !sprites.rasterises || collideRects(word, current)) {
          val sprite = word.sprite ?: return false
          val w = word.width shr 5
          val sw = size[0] shr 5
          val lx = word.x - (w shl 4)
          val sx = lx and 0x7f
          val msx = 32 - sx
          val h = word.y1 - word.y0
          var x = (word.y + word.y0) * sw + (lx shr 5)
          for (j in 0 until h) {
            var last = 0
            for (i in 0..w) {
              // `(last << msx) | ((last = sprite[...]) >>> sx)` upstream, and the order is the
              // whole of it: the left half reads the **previous** word's low bits before the
              // assignment in the right half replaces it. That is how a word straddling a 32-bit
              // boundary contributes to both sides. Assigning first — which reads far more
              // naturally in Kotlin — shifts every sprite left by one word.
              val previous = last
              val current = if (i < w) sprite[j * w + i] else 0
              if (i < w) last = current
              val contribution = (previous shl msx) or (if (i < w) current ushr sx else 0)
              val at = x + i
              if (at in board.indices) board[at] = board[at] or contribution
            }
            x += sw
          }
          word.sprite = null
          return true
        }
      }
    }
  }

  /** Whether [word]'s mask meets anything already on the board. */
  private fun cloudCollide(word: CloudWord): Boolean {
    val sprite = word.sprite ?: return false
    val sw = size[0] shr 5
    val w = word.width shr 5
    val lx = word.x - (w shl 4)
    val sx = lx and 0x7f
    val msx = 32 - sx
    val h = word.y1 - word.y0
    var x = (word.y + word.y0) * sw + (lx shr 5)
    for (j in 0 until h) {
      var last = 0
      for (i in 0..w) {
        // The previous word's low bits, then this word's high bits. See the note in [place]: the
        // read has to happen before the assignment or the mask is tested one word to the left.
        val previous = last
        val current = if (i < w) sprite[j * w + i] else 0
        if (i < w) last = current
        val bits = (previous shl msx) or (if (i < w) current ushr sx else 0)
        val at = x + i
        if (at in board.indices && (bits and board[at]) != 0) return true
      }
      x += sw
    }
    return false
  }

  private fun cloudBounds(b: IntArray, word: CloudWord) {
    if (word.x + word.x0 < b[0]) b[0] = word.x + word.x0
    if (word.y + word.y0 < b[1]) b[1] = word.y + word.y0
    if (word.x + word.x1 > b[2]) b[2] = word.x + word.x1
    if (word.y + word.y1 > b[3]) b[3] = word.y + word.y1
  }

  private fun collideRects(word: CloudWord, b: IntArray): Boolean =
    word.x + word.x1 > b[0] &&
      word.x + word.x0 < b[2] &&
      word.y + word.y1 > b[1] &&
      word.y + word.y0 < b[3]

  private companion object {
    const val RADIANS = kotlin.math.PI / 180.0

    /**
     * The path a word walks while looking for room.
     *
     * `archimedean` widens smoothly and gives the round cloud everybody pictures; `rectangular`
     * steps in squares and packs into a block. Both are upstream's, arithmetic included — the
     * rectangular one's `(sqrt(1 + 4 * sign * t) - sign) & 3` is a triangular-number trick for
     * "which of the four sides am I on", and the `& 3` is doing the work of a modulo on a value
     * that may be negative.
     */
    fun spiralOf(name: String, size: IntArray): (Double) -> DoubleArray? =
      when (name) {
        "rectangular" -> {
          val dy = 4.0
          val dx = dy * size[0] / size[1]
          var x = 0.0
          var y = 0.0
          ({ t ->
            val sign = if (t < 0) -1.0 else 1.0
            when ((sqrt(1 + 4 * sign * t) - sign).toInt() and 3) {
              0 -> x += dx
              1 -> y += dy
              2 -> x -= dx
              else -> y -= dy
            }
            doubleArrayOf(x, y)
          })
        }
        else -> {
          val e = size[0].toDouble() / size[1]
          ({ t ->
            val scaled = t * 0.1
            doubleArrayOf(e * scaled * cos(scaled), scaled * sin(scaled))
          })
        }
      }
  }
}
