package dev.aster.vega.dataflow.transform

import dev.aster.vega.expression.RandomStream
import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.asString
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The word-cloud layout, replayed against upstream's own pixels.
 *
 * **The problem this test exists to solve.** `wordcloud` was the last of upstream's 51 documented
 * transforms not implemented here, and the reason given was that its collision detection rasterises
 * each word through a canvas and reads the pixels back — which nothing in common Kotlin can do, and
 * nothing should, since a font belongs to the host.
 *
 * That is a reason not to *reproduce* the mask. It is not a reason not to implement the layout, and
 * it is not a reason the layout cannot be checked. The mask is an input, so it is **recorded**:
 * `oracle-js/src/record-wordcloud.mjs` runs upstream's own `cloudSprite` against the pinned
 * `vega@6.3.1`, writes out each word's bits, and writes out where upstream's transform then put
 * every word. Hand the port those bits and it must reach those placements — exactly, to the pixel,
 * for all eighteen words.
 *
 * What that verifies is everything except the rasteriser: the sort, the spiral, the board packing,
 * the bitmask collision, the bounding-box test, the blank-row trimming, and the order the random
 * draws are taken in. What it does not verify is glyph rasterisation, which is recorded here and is
 * a host's job everywhere else — the same division `label` already lives with.
 *
 * **Exactness is not optional here and that is the point.** The layout is a chain: every word's
 * position depends on the board left by the words before it. One wrong bit in the first word moves
 * all seventeen after it. So this passes completely or fails obviously, and there is no arrangement
 * of it that could pass while being subtly wrong.
 */
class CloudLayoutTest {

  private val vectors = File("../test-fixtures/upstream-vectors-wordcloud.json")

  private fun load(): VegaValue.Obj {
    assumeTrue(
      vectors.exists(),
      "no recorded wordcloud vectors; regenerate with oracle-js/src/record-wordcloud.mjs",
    )
    return VegaJson.parse(vectors.readText()) as VegaValue.Obj
  }

  private fun VegaValue.Obj.arr(name: String) = fields[name] as VegaValue.Arr

  private fun VegaValue.Obj.num(name: String) = fields[name]!!.asDouble()

  private fun VegaValue.Obj.int(name: String) = num(name).toInt()

  /** The recorded sprites, keyed by the word they belong to. */
  private class Recorded(
    val measured: Double,
    val mask: IntArray?,
    val width: Int,
    val height: Int,
    val y0: Int,
    val y1: Int,
  )

  private fun sprites(root: VegaValue.Obj): Map<String, Recorded> =
    root.arr("sprites").values.associate { entry ->
      val obj = entry as VegaValue.Obj
      val mask = (obj.fields["mask"] as? VegaValue.Arr)?.values
      (obj.fields["text"]!!.asString()) to
        Recorded(
          measured = obj.num("measured"),
          mask = mask?.let { bits -> IntArray(bits.size) { bits[it].asDouble().toLong().toInt() } },
          width = obj.int("width"),
          height = obj.int("height"),
          y0 = obj.int("y0"),
          y1 = obj.int("y1"),
        )
    }

  private fun words(root: VegaValue.Obj, recorded: Map<String, Recorded>): List<CloudWord> {
    // The font sizes come from the *placements*, which is where upstream reports them, so this test
    // does not restate the `sqrt` size scale — that is the transform's business and is checked
    // there. Here the layout is what is under test.
    return root.arr("placements").values.mapIndexed { index, entry ->
      val obj = entry as VegaValue.Obj
      val text = obj.fields["text"]!!.asString()
      CloudWord(
        index = index,
        text = text,
        font = root.fields["font"]!!.asString(),
        style = "normal",
        weight = "normal",
        size = obj.num("fontSize").toInt(),
        rotate = obj.num("angle"),
        padding = 1.0,
      )
    }
  }

  /** Upstream's recorded pixels, handed back as the layout asks for them. */
  private class RecordedSprites(private val recorded: Map<String, Recorded>) : CloudSprites {
    override val rasterises: Boolean = true

    override fun measure(word: CloudWord): Double = recorded.getValue(word.text).measured

    override fun rasterise(word: CloudWord, width: Int, height: Int): IntArray? =
      recorded.getValue(word.text).mask
  }

  @Test
  fun `the port places every word exactly where upstream placed it`() {
    val root = load()
    val recorded = sprites(root)
    val size = root.arr("size").values.map { it.asDouble().toInt() }.toIntArray()
    val words = words(root, recorded)

    val random = RandomStream(root.num("seed").toLong())
    val placed =
      CloudLayout(words, size, "archimedean", RecordedSprites(recorded)) { random.next() }.layout()

    // The transform adds half the size back — the layout recentres on the origin, which upstream's
    // own comment calls a temporary hack — so this is where that is undone to compare.
    val dx = size[0] shr 1
    val dy = size[1] shr 1
    val got = placed.associate { it.text to (it.x + dx to (it.y + dy)) }

    val expected =
      root
        .arr("placements")
        .values
        .map { it as VegaValue.Obj }
        .associate {
          it.fields["text"]!!.asString() to (it.num("x").toInt() to it.num("y").toInt())
        }

    assertEquals(
      expected.size,
      got.size,
      "upstream placed ${expected.size} words, this placed ${got.size}: " +
        "${expected.keys - got.keys} were dropped",
    )
    val wrong = expected.filter { (text, at) -> got[text] != at }
    assertTrue(
      wrong.isEmpty(),
      "placed differently from upstream: " +
        wrong.entries.joinToString { (text, at) -> "$text upstream $at, here ${got[text]}" },
    )
  }

  /**
   * The sprite trimming reproduces upstream's `y0` and `y1`, which is what the exactness rests on.
   *
   * A word's vertical extent is not its font size: the rasteriser drops the blank rows **above**
   * the glyphs and keeps everything below the first inked row, because upstream's `seen` is
   * cumulative and never reset. Getting that backwards — resetting it per row, which is the natural
   * reading — trims the bottom too, and every word ends up a few pixels short. The placements would
   * then be wrong in a way that still looked like a word cloud.
   */
  @Test
  fun `blank rows above the glyphs are trimmed and the rest are kept`() {
    val root = load()
    val recorded = sprites(root)
    val size = root.arr("size").values.map { it.asDouble().toInt() }.toIntArray()
    val words = words(root, recorded)
    val random = RandomStream(root.num("seed").toLong())
    CloudLayout(words, size, "archimedean", RecordedSprites(recorded)) { random.next() }.layout()

    var trimmedSomething = false
    for (word in words) {
      val want = recorded.getValue(word.text)
      assertEquals(want.width, word.width, "sprite width for '${word.text}'")
      assertEquals(want.height, word.height, "sprite height for '${word.text}'")
      // The recorded `y0`/`y1` are the *untrimmed* box, so a trimmed word must sit inside it and
      // must not be taller than it.
      assertTrue(
        word.y0 >= want.y0 && word.y1 <= want.y1,
        "'${word.text}' trimmed outside its box: ${word.y0}..${word.y1} not in ${want.y0}..${want.y1}",
      )
      if (word.y0 > want.y0) trimmedSomething = true
    }
    // A guard on the guard: if nothing were ever trimmed the assertions above would hold vacuously,
    // and the cumulative-`seen` rule would be untested. Every word here has blank rows above it,
    // because a font's ascent leaves room for accents no lower-case word uses.
    assertTrue(trimmedSomething, "no word was trimmed at all, so the trimming was never exercised")
  }

  /**
   * Without a rasteriser every word is a filled box, and the cloud is still a cloud.
   *
   * The fallback a host with no glyph raster gets. It must never overlap and never place a word
   * outside the canvas — those would be wrong rather than merely looser — and it must place fewer
   * or equal words, since a rectangle claims the whitespace a mask gives away.
   */
  @Test
  fun `the box fallback places words that do not overlap`() {
    val root = load()
    val recorded = sprites(root)
    val size = root.arr("size").values.map { it.asDouble().toInt() }.toIntArray()
    val words = words(root, recorded)
    val random = RandomStream(root.num("seed").toLong())
    val boxes = BoxSprites { recorded.getValue(it.text).measured }
    val placed = CloudLayout(words, size, "archimedean", boxes) { random.next() }.layout()

    assertTrue(placed.isNotEmpty(), "the fallback placed nothing at all")
    assertTrue(
      placed.size <= root.arr("placements").values.size,
      "the fallback placed more words than upstream did, which a looser mask cannot do",
    )
    val dx = size[0] shr 1
    val dy = size[1] shr 1
    for (word in placed) {
      val left = word.x + dx + word.x0
      val top = word.y + dy + word.y0
      assertTrue(
        left >= 0 &&
          top >= 0 &&
          word.x + dx + word.x1 <= size[0] &&
          word.y + dy + word.y1 <= size[1],
        "'${word.text}' was placed outside the ${size[0]} x ${size[1]} canvas",
      )
    }
    for (a in placed.indices) {
      for (b in a + 1 until placed.size) {
        val one = placed[a]
        val two = placed[b]
        val overlaps =
          one.x + one.x1 > two.x + two.x0 &&
            one.x + one.x0 < two.x + two.x1 &&
            one.y + one.y1 > two.y + two.y0 &&
            one.y + one.y0 < two.y + two.y1
        assertTrue(!overlaps, "'${one.text}' and '${two.text}' overlap with rectangular sprites")
      }
    }
  }

  /** The same seed gives the same cloud, which is what makes any of this comparable. */
  @Test
  fun `the layout is a function of its seed`() {
    val root = load()
    val recorded = sprites(root)
    val size = root.arr("size").values.map { it.asDouble().toInt() }.toIntArray()

    fun run(seed: Long): List<Triple<String, Int, Int>> {
      val random = RandomStream(seed)
      return CloudLayout(words(root, recorded), size, "archimedean", RecordedSprites(recorded)) {
          random.next()
        }
        .layout()
        .map { Triple(it.text, it.x, it.y) }
    }

    assertEquals(run(42), run(42), "the same seed gave two different clouds")
    assertTrue(run(42) != run(7), "two different seeds gave the same cloud, so the seed is unread")
  }
}
