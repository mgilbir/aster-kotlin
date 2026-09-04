package dev.aster.vega.runtime

import dev.aster.vega.model.VegaValue
import dev.aster.vega.runtime.compile.SpecCompiler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `invert('name', [lo, hi])` — the **range** form, which answered null for every scale.
 *
 * `invertScale` read its argument with `asDouble()`, so an array came back NaN and the whole call
 * answered null. Every brush in Vega's gallery is written `invert('x', brush)` with a pair, so the
 * idiom produced nothing at all — and null reads as zero to the arithmetic downstream, which is why
 * a brushed detail panel showed the domain `[0, 0]` rather than staying still.
 *
 * The band and point scales already had `invertRange`, matched to upstream and tested through
 * `invert(position)`. Nothing ever called it with two ends.
 *
 * Every expectation here is upstream's own answer, read from vega 6.3.1 rather than derived:
 * ```
 * invert('lin', [20, 120])  -> [1, 6]
 * invert('lin', [120, 20])  -> [1, 6]
 * invert('lin', [-50, 400]) -> [-2.5, 20]
 * invert('lin', [40])       -> [2, null]
 * invert('band', [10, 150]) -> ["a", "b"]
 * invert('pt', [10, 150])   -> ["b"]
 * invert('bandRev', [10, 150]) -> ["b", "c"]     (a band scale whose range runs backwards)
 * invert('log', [0, 100])   -> [1, 10.000000000000002]
 * ```
 */
class InvertRangeTest {

  private fun signals(vararg updates: Pair<String, String>): Map<String, VegaValue> {
    val declared =
      updates.joinToString(", ") { (name, expr) -> """{"name": "$name", "update": "$expr"}""" }
    val compiled =
      SpecCompiler()
        .compileJson(
          """
          {
            "width": 200, "height": 100, "padding": 0, "autosize": "none",
            "data": [{"name": "t", "values": [{"v": 1}]}],
            "scales": [
              {"name": "lin", "type": "linear", "domain": [0, 10], "range": [0, 200]},
              {"name": "rev", "type": "linear", "domain": [0, 10], "range": [200, 0]},
              {"name": "log", "type": "log", "domain": [1, 100], "range": [0, 200]},
              {"name": "band", "type": "band", "domain": ["a", "b", "c"], "range": [0, 300]},
              {"name": "bandRev", "type": "band", "domain": ["a", "b", "c"], "range": [300, 0]},
              {"name": "pt", "type": "point", "domain": ["a", "b", "c"], "range": [0, 300]}
            ],
            "signals": [$declared],
            "marks": []
          }
          """
            .trimIndent()
        )
    return compiled.signals.values
  }

  private fun numbers(value: VegaValue?): List<Double?> =
    (value as VegaValue.Arr).values.map { (it as? VegaValue.Num)?.value }

  private fun strings(value: VegaValue?): List<String> =
    (value as VegaValue.Arr).values.map { (it as VegaValue.Str).value }

  /** A continuous scale answers the pair, and the *range* inputs are ordered, not the answers. */
  @Test
  fun `a continuous scale inverts a pair, whichever way round it is written`() {
    val values =
      signals(
        "forwards" to "invert('lin', [20, 120])",
        "backwards" to "invert('lin', [120, 20])",
      )
    assertEquals(listOf(1.0, 6.0), numbers(values["forwards"]))
    assertEquals(
      listOf(1.0, 6.0),
      numbers(values["backwards"]),
      "a pair written high-to-low gave a different answer; upstream orders the range inputs",
    )
  }

  /**
   * On a **reversed** scale the answer descends, which is what says the inputs are what get sorted.
   *
   * The case that tells the two implementations apart: sorting the *answers* would give an
   * ascending pair here and agree with upstream everywhere else.
   */
  @Test
  fun `a reversed scale answers a descending pair`() {
    val values = signals("back" to "invert('rev', [20, 120])")
    val pair = numbers(values["back"])
    assertTrue(
      pair[0]!! > pair[1]!!,
      "a reversed scale answered $pair; the answers were sorted rather than the range inputs",
    )
    assertEquals(listOf(9.0, 4.0), pair)
  }

  /** A range reaching outside the scale extrapolates rather than clamping, as upstream does. */
  @Test
  fun `a range outside the scale extrapolates`() {
    assertEquals(listOf(-2.5, 20.0), numbers(signals("out" to "invert('lin', [-50, 400])")["out"]))
  }

  /**
   * A one-element array answers a pair with a hole in it rather than refusing.
   *
   * Upstream reads the missing end as `undefined`, inverts it to NaN and hands the pair over; the
   * shape of the answer stays a pair, which is what an expression reading `[0]` off it relies on.
   */
  @Test
  fun `a short array answers a pair with a hole`() {
    val pair = numbers(signals("short" to "invert('lin', [40])")["short"])
    assertEquals(2.0, pair[0])
    assertEquals(null, pair[1], "the missing end should be null rather than a number")
  }

  /** A band answers the values whose bands the stretch covers — several of them. */
  @Test
  fun `a band scale answers the values it covers`() {
    assertEquals(listOf("a", "b"), strings(signals("cov" to "invert('band', [10, 150])")["cov"]))
  }

  /**
   * A band whose **range runs backwards** answers the values at the other end.
   *
   * The reverse branch of `invertRange`, which maps the range indices back into domain order. Read
   * from upstream rather than reasoned about, because it is the branch a hand-derived expectation
   * would most easily get backwards.
   */
  @Test
  fun `a reversed band scale answers the values at the other end`() {
    assertEquals(
      listOf("b", "c"),
      strings(signals("cov" to "invert('bandRev', [10, 150])")["cov"]),
    )
  }

  /** A point scale answers only the points inside the stretch, which here is one. */
  @Test
  fun `a point scale answers the points inside the stretch`() {
    assertEquals(listOf("b"), strings(signals("cov" to "invert('pt', [10, 150])")["cov"]))
  }

  /** A log scale, to say the pair form is not special-cased to linear. */
  @Test
  fun `a log scale inverts a pair`() {
    val pair = numbers(signals("lg" to "invert('log', [0, 100])")["lg"])
    assertEquals(1.0, pair[0]!!, 1e-9)
    assertEquals(10.0, pair[1]!!, 1e-9)
  }

  /** And the scalar form is untouched, which is what says nothing was traded for this. */
  @Test
  fun `the scalar form still answers a number`() {
    assertEquals(VegaValue.Num(1.0), signals("one" to "invert('lin', 20)")["one"])
    assertEquals(VegaValue.Str("a"), signals("b" to "invert('band', 10)")["b"])
  }
}
