package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A bar is turned by a column that **arrived** bucketed, not by one it is about to bucket.
 *
 * ```js
 * if (isFieldDef(x) && (isBinned(x.bin) || (isFieldDef(y) && y.aggregate && !x.aggregate))) {
 *   return 'vertical';
 * }
 * ```
 *
 * `isBinned` is `bin === 'binned'` or `{binned: true}` — a pair of edges the data came with, which
 * the bar spans and which settles the orientation on its own. A bin this chart is *computing*
 * settles nothing yet, and the question falls through to the rules below; a plain histogram gets
 * the same answer from them anyway, its binned `x` being no measure and its `y` one.
 *
 * Read as any bin at all, a bar whose bucketed `x` was given a second position of its own never
 * reached the ranged rule. It was called vertical, and everything downstream followed: `y` became
 * the band the bar grows along, so the **stack** was drawn there, the `y` scale took a `zero` it
 * should not have had and lost its padding, and a marker five units tall came out a full column.
 *
 * Upstream's `isFieldDef(x)` is kept and not asserted: the only shape that tells it apart is a
 * `datum` carrying a bin, and upstream throws on that rather than compiling it, so there is no
 * expectation to hold it to. It is there because upstream's is.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class BinnedOrientTest {

  /** Which position channels the mark is given, and what one scale settled on. */
  private fun drawn(encoding: String, scaleName: String = "y"): String {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """{"data":{"values":[{"s":0,"e":2,"c":3,"k":"a","t":"2020-01-01"}]},"mark":"bar","encoding":{$encoding}}"""
            )
            .toJson()
        ) {
          "no output"
        }
      ) as VegaValue.Obj
    val positions =
      ((compiled.fields["marks"] as VegaValue.Arr).values[0] as VegaValue.Obj)
        .obj("encode")!!
        .obj("update")!!
        .fields
        .keys
        .filter { it in setOf("x", "x2", "xc", "y", "y2", "yc", "width", "height") }
        .joinToString(",")
    val scale =
      (compiled.fields["scales"] as VegaValue.Arr)
        .values
        .map { it as VegaValue.Obj }
        .first { it.string("name") == scaleName }
    return "$positions | zero=${scale.fields["zero"]?.let { VegaJson.write(it) }}" +
      " padding=${scale.fields["padding"]?.let { VegaJson.write(it) }}"
  }

  private val count = """"y":{"field":"c","type":"quantitative"}"""

  /**
   * The reported shape: a bin this chart computes, given a second position of its own. The ranged
   * rule answers, and it answers **horizontal** — `y` is no longer the band the bar grows along.
   */
  @Test
  fun `a computed bin with a second position is horizontal`() {
    assertEquals(
      "x2,x,yc,height | zero=false padding=5",
      drawn(
        """"x":{"field":"s","type":"quantitative","bin":{"step":2}},"x2":{"field":"e"},$count"""
      ),
    )
  }

  /** A bin the data **arrived** with turns the bar back: those edges are the bar's own band. */
  @Test
  fun `a pre-binned column with a second position is vertical`() {
    assertEquals(
      "x2,x,y,y2 | zero=true padding=null",
      drawn(""""x":{"field":"s","type":"quantitative","bin":"binned"},"x2":{"field":"e"},$count"""),
    )
  }

  /** Without a second position the rules below answer, and a histogram is vertical either way. */
  @Test
  fun `a computed bin on its own is vertical`() {
    assertEquals(
      "x2,x,y,y2 | zero=true padding=null",
      drawn(""""x":{"field":"s","type":"quantitative","bin":true},$count"""),
    )
  }

  /** The same read along the other axis: a computed bin on `y` with a `y2` is vertical. */
  @Test
  fun `a computed bin on y with a second position is vertical`() {
    assertEquals(
      "xc,width,y2,y | zero=false padding=null",
      drawn(
        """"y":{"field":"s","type":"quantitative","bin":{"step":2}},"y2":{"field":"e"},
           "x":{"field":"c","type":"quantitative"}"""
      ),
    )
  }

  /**
   * A pre-binned column turns the bar with **no** second position at all, which is the arm the
   * ranged rule below cannot stand in for. Read there and not here, such a bar has no orientation,
   * and its own axis then takes a `zero` — `defaultZero` keeping one back only from the channel a
   * bar grows along — so this arm has to answer *before* the ranged rule, and not only agree with
   * it. Against a **temporal** other axis the two disagree: the pre-binned column answers first and
   * says vertical, where the ranged rule, finding no quantitative measure to grow along, would say
   * horizontal.
   */
  @Test
  fun `a pre-binned column answers before the ranged rule`() {
    assertEquals(
      "x2,x,y,y2 | zero=null padding=null",
      drawn(
        """"x":{"field":"s","type":"quantitative","bin":"binned"},"x2":{"field":"e"},
           "y":{"field":"t","type":"temporal"}"""
      ),
    )
    // The same pair with a bin the chart computes falls through, and the ranged rule answers.
    assertEquals(
      "x2,x,yc,height | zero=null padding=5",
      drawn(
        """"x":{"field":"s","type":"quantitative","bin":{"step":2}},"x2":{"field":"e"},
           "y":{"field":"t","type":"temporal"}"""
      ),
    )
    // And the same read along the other axis.
    assertEquals(
      "x,x2,y2,y | zero=null padding=null",
      drawn(
        """"y":{"field":"s","type":"quantitative","bin":"binned"},"y2":{"field":"e"},
           "x":{"field":"t","type":"temporal"}""",
        scaleName = "x",
      ),
    )
  }

  /** And a pre-binned `y` with a `y2` is horizontal, which is that arm of the same rule. */
  @Test
  fun `a pre-binned y with a second position is horizontal`() {
    assertEquals(
      "x,x2,y2,y | zero=false padding=null",
      drawn(
        """"y":{"field":"s","type":"quantitative","bin":"binned"},"y2":{"field":"e"},
           "x":{"field":"c","type":"quantitative"}"""
      ),
    )
  }
}
