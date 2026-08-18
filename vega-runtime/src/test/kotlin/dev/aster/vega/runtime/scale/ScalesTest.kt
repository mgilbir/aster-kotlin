package dev.aster.vega.runtime.scale

import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Expected values come from the pinned d3-scale and from upstream Vega running
 * `test-fixtures/specs/bar.vg.json`, so they are reference vectors rather than restatements of the
 * implementation.
 */
class ScalesTest {

  private val tolerance = 1e-12

  // ---- identity -------------------------------------------------------------

  /**
   * The value itself, and **nothing** where there is no number.
   *
   * d3's identity scale answers its `unknown` — `undefined` — for anything that does not coerce,
   * and a channel handed that is a channel left unset: the mark does not draw rather than drawing
   * at NaN. Verified against upstream, where `scale('i', 'x')` comes back undefined while
   * `scale('i', 42)` is
   * 42. The `[0, 1]` domain is d3's too and is never consulted; it exists so `domain('name')`
   *     answers something.
   */
  @Test
  fun `an identity scale maps the value itself`() {
    val scale = IdentityScale("i")
    assertEquals(VegaValue.Num(42.0), scale.scale(VegaValue.Num(42.0)))
    assertEquals(VegaValue.Num(-3.5), scale.scale(VegaValue.Num(-3.5)))
    // A numeric string coerces, exactly as `+value` coerces it.
    assertEquals(VegaValue.Num(7.0), scale.scale(VegaValue.Str("7")))
    assertEquals(VegaValue.Null, scale.scale(VegaValue.Str("not a number")))
    assertEquals(VegaValue.Null, scale.scale(VegaValue.Null))
    assertEquals(listOf(0.0, 1.0), scale.domain)
    assertEquals(listOf(0.0, 1.0), scale.range)
  }

  // ---- linear ---------------------------------------------------------------

  @Test
  fun `linear scale maps the fixture's y values exactly as upstream does`() {
    // From `view.scale("yscale")` on bar.vg.json: domain [0, 100], range [196, 0].
    val scale = LinearScale("yscale", listOf(0.0, 100.0), listOf(196.0, 0.0))
    assertEquals(196.0, scale.apply(0.0), tolerance)
    assertEquals(141.12, scale.apply(28.0), tolerance)
    assertEquals(17.639999999999993, scale.apply(91.0), 1e-9)
    assertEquals(0.0, scale.apply(100.0), tolerance)
  }

  @Test
  fun `linear scale extrapolates unless clamped`() {
    val open = LinearScale("s", listOf(0.0, 10.0), listOf(0.0, 100.0))
    assertEquals(-100.0, open.apply(-10.0), tolerance)
    assertEquals(200.0, open.apply(20.0), tolerance)

    val clamped = LinearScale("s", listOf(0.0, 10.0), listOf(0.0, 100.0), clamp = true)
    assertEquals(0.0, clamped.apply(-10.0), tolerance)
    assertEquals(100.0, clamped.apply(20.0), tolerance)
  }

  @Test
  fun `linear scale with a reversed domain still interpolates`() {
    val scale = LinearScale("s", listOf(10.0, 0.0), listOf(0.0, 100.0))
    assertEquals(0.0, scale.apply(10.0), tolerance)
    assertEquals(100.0, scale.apply(0.0), tolerance)
    assertEquals(50.0, scale.apply(5.0), tolerance)
  }

  @Test
  fun `zero-extent domain returns the range midpoint instead of dividing by zero`() {
    val scale = LinearScale("s", listOf(5.0, 5.0), listOf(0.0, 100.0))
    assertEquals(50.0, scale.apply(5.0), tolerance)
    assertEquals(50.0, scale.apply(999.0), tolerance)
  }

  @Test
  fun `a value that is not a number scales to one that is not either`() {
    // Not to *nothing*: JavaScript reads a null as zero in arithmetic and propagates a NaN, and
    // Vega-Lite decides whether a bar is too thin to see with `abs(scale(x, a) - scale(x, b))`.
    // Answering zero there says the bar has no width; answering NaN says the question does not
    // apply, which is what upstream answers.
    val scale = LinearScale("s", listOf(0.0, 1.0), listOf(0.0, 1.0))
    assertTrue(scale.apply(Double.NaN).isNaN())
    val scaled = scale.scale(VegaValue.Str("not a number"))
    assertTrue(scaled is VegaValue.Num && scaled.value.isNaN(), scaled.toString())
  }

  @Test
  fun `piecewise domain interpolates within the containing segment`() {
    val scale = LinearScale("s", listOf(0.0, 50.0, 100.0), listOf(0.0, 10.0, 1000.0))
    assertEquals(0.0, scale.apply(0.0), tolerance)
    assertEquals(5.0, scale.apply(25.0), tolerance)
    assertEquals(10.0, scale.apply(50.0), tolerance)
    assertEquals(505.0, scale.apply(75.0), tolerance)
    assertEquals(1000.0, scale.apply(100.0), tolerance)
  }

  @Test
  fun `invert round-trips`() {
    val scale = LinearScale("s", listOf(0.0, 100.0), listOf(196.0, 0.0))
    assertEquals(28.0, scale.invert(scale.apply(28.0)), 1e-9)
  }

  @Test
  fun `too few domain or range values is rejected`() {
    assertThrows<IllegalArgumentException> { LinearScale("s", listOf(1.0), listOf(0.0, 1.0)) }
    assertThrows<IllegalArgumentException> { LinearScale("s", listOf(0.0, 1.0), listOf(0.0)) }
  }

  @Test
  fun `fromExtent applies zero before nice, matching upstream`() {
    // Verified against upstream Vega: {} -> [0,91], {nice} -> [0,100], {zero:false} -> [19,91],
    // {nice, zero:false} -> [10,100].
    val plain = LinearScale.fromExtent("s", 19.0..91.0, listOf(0.0, 1.0))
    assertEquals(listOf(0.0, 91.0), plain.domain)

    val niced = LinearScale.fromExtent("s", 19.0..91.0, listOf(0.0, 1.0), nice = true)
    assertEquals(listOf(0.0, 100.0), niced.domain)

    val noZero = LinearScale.fromExtent("s", 19.0..91.0, listOf(0.0, 1.0), zero = false)
    assertEquals(listOf(19.0, 91.0), noZero.domain)

    val noZeroNiced =
      LinearScale.fromExtent("s", 19.0..91.0, listOf(0.0, 1.0), zero = false, nice = true)
    assertEquals(listOf(10.0, 100.0), noZeroNiced.domain)
  }

  @Test
  fun `fromExtent falls back to a unit domain for missing or non-finite data`() {
    assertEquals(listOf(0.0, 1.0), LinearScale.fromExtent("s", null, listOf(0.0, 1.0)).domain)
    assertEquals(
      listOf(0.0, 1.0),
      LinearScale.fromExtent("s", Double.NaN..Double.NaN, listOf(0.0, 1.0)).domain,
    )
  }

  @Test
  fun `fromExtent widens a negative extent to include zero`() {
    assertEquals(
      listOf(-50.0, 0.0),
      LinearScale.fromExtent("s", -50.0..-10.0, listOf(0.0, 1.0)).domain,
    )
  }

  @Test
  fun `tick labels use the digits the step requires`() {
    val whole = LinearScale("s", listOf(0.0, 100.0), listOf(0.0, 1.0))
    assertEquals("0", whole.formatTick(0.0))
    assertEquals("10", whole.formatTick(10.0))
    assertEquals("100", whole.formatTick(100.0))

    val fractional = LinearScale("s", listOf(0.0, 1.0), listOf(0.0, 1.0))
    assertEquals("0.1", fractional.formatTick(0.1))
  }

  // ---- band -----------------------------------------------------------------

  @Test
  fun `band scale reproduces the fixture's step, bandwidth and positions`() {
    // From `view.scale("xscale")` on bar.vg.json.
    val scale =
      BandScale(
        name = "xscale",
        domain = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug"),
        range = listOf(0.0, 344.0),
        paddingInner = 0.05,
        paddingOuter = 0.05,
      )
    assertEquals(42.732919254658384, scale.step, 1e-12)
    assertEquals(40.59627329192546, scale.bandwidth, 1e-12)
    assertEquals(2.1366459627329277, scale.position(VegaValue.Str("Jan")), 1e-12)
    assertEquals(44.86956521739131, scale.position(VegaValue.Str("Feb")), 1e-12)
  }

  @Test
  fun `band scale without padding fills the range exactly`() {
    val scale = BandScale("s", listOf("a", "b", "c", "d"), listOf(0.0, 100.0))
    assertEquals(25.0, scale.step, tolerance)
    assertEquals(25.0, scale.bandwidth, tolerance)
    assertEquals(0.0, scale.position(VegaValue.Str("a")), tolerance)
    assertEquals(75.0, scale.position(VegaValue.Str("d")), tolerance)
  }

  @Test
  fun `band scale reverses when the range is descending`() {
    val scale = BandScale("s", listOf("a", "b"), listOf(100.0, 0.0))
    assertEquals(50.0, scale.position(VegaValue.Str("a")), tolerance)
    assertEquals(0.0, scale.position(VegaValue.Str("b")), tolerance)
  }

  @Test
  fun `band align moves the leftover space`() {
    val left = BandScale("s", listOf("a"), listOf(0.0, 100.0), paddingOuter = 0.5, align = 0.0)
    val right = BandScale("s", listOf("a"), listOf(0.0, 100.0), paddingOuter = 0.5, align = 1.0)
    assertTrue(left.position(VegaValue.Str("a")) < right.position(VegaValue.Str("a")))
  }

  @Test
  fun `band round snaps step and bandwidth to whole pixels`() {
    val scale = BandScale("s", listOf("a", "b", "c"), listOf(0.0, 100.0), round = true)
    assertEquals(33.0, scale.step, tolerance)
    assertEquals(33.0, scale.bandwidth, tolerance)
  }

  @Test
  fun `band scale returns NaN outside its domain`() {
    val scale = BandScale("s", listOf("a"), listOf(0.0, 10.0))
    assertTrue(scale.position(VegaValue.Str("zzz")).isNaN())
    assertEquals(VegaValue.Null, scale.scale(VegaValue.Str("zzz")))
  }

  @Test
  fun `empty band domain does not divide by zero`() {
    val scale = BandScale("s", emptyList(), listOf(0.0, 100.0))
    assertEquals(100.0, scale.step, tolerance)
    assertTrue(scale.bandwidth.isFinite())
  }

  @Test
  fun `duplicate band categories collapse to the last position`() {
    // Vega's band scale is a map from value to position, so a repeated category is not a new slot.
    val scale = BandScale("s", listOf("a", "a", "b"), listOf(0.0, 30.0))
    assertEquals(10.0, scale.position(VegaValue.Str("a")), tolerance)
    assertEquals(20.0, scale.position(VegaValue.Str("b")), tolerance)
  }

  @Test
  fun `band centers sit half a bandwidth past each position`() {
    val scale = BandScale("s", listOf("a", "b"), listOf(0.0, 100.0))
    assertEquals(listOf(25.0, 75.0), scale.centers())
  }

  // ---- point ----------------------------------------------------------------

  @Test
  fun `point scale has zero bandwidth and lands on boundaries`() {
    val scale = PointScale("s", listOf("a", "b", "c"), listOf(0.0, 100.0))
    assertEquals(0.0, scale.bandwidth)
    assertEquals(0.0, scale.position(VegaValue.Str("a")), tolerance)
    assertEquals(50.0, scale.position(VegaValue.Str("b")), tolerance)
    assertEquals(100.0, scale.position(VegaValue.Str("c")), tolerance)
  }

  @Test
  fun `point padding insets the first and last point`() {
    val scale = PointScale("s", listOf("a", "b"), listOf(0.0, 100.0), padding = 0.5)
    assertEquals(25.0, scale.position(VegaValue.Str("a")), tolerance)
    assertEquals(75.0, scale.position(VegaValue.Str("b")), tolerance)
  }

  // ---- ordinal --------------------------------------------------------------

  @Test
  fun `ordinal scale maps by index and cycles a short range`() {
    val scale =
      OrdinalScale(
        "colour",
        listOf("a", "b", "c"),
        listOf(VegaValue.Str("red"), VegaValue.Str("green")),
      )
    assertEquals(VegaValue.Str("red"), scale.scale(VegaValue.Str("a")))
    assertEquals(VegaValue.Str("green"), scale.scale(VegaValue.Str("b")))
    assertEquals(VegaValue.Str("red"), scale.scale(VegaValue.Str("c")))
  }

  @Test
  fun `ordinal scale returns the unknown value outside its domain`() {
    val scale =
      OrdinalScale("c", listOf("a"), listOf(VegaValue.Str("red")), unknown = VegaValue.Str("#ccc"))
    assertEquals(VegaValue.Str("#ccc"), scale.scale(VegaValue.Str("zzz")))

    val withoutUnknown = OrdinalScale("c", listOf("a"), listOf(VegaValue.Str("red")))
    assertEquals(VegaValue.Null, withoutUnknown.scale(VegaValue.Str("zzz")))
  }

  @Test
  fun `ordinal scale with an empty range yields null`() {
    assertEquals(
      VegaValue.Null,
      OrdinalScale("c", listOf("a"), emptyList()).scale(VegaValue.Str("a")),
    )
  }

  // ---- formatting -----------------------------------------------------------

  @Test
  fun `formatNumber trims and normalizes`() {
    assertEquals("0", formatNumber(0.0, 0))
    assertEquals("0", formatNumber(-0.0, 0))
    assertEquals("10", formatNumber(10.0, 0))
    assertEquals("10", formatNumber(10.4, 0))
    assertEquals("0.10", formatNumber(0.1, 2))
    assertEquals("0.0", formatNumber(-0.0001, 1))
    assertEquals("NaN", formatNumber(Double.NaN, 0))
  }

  /**
   * A continuous axis labels through d3-format, which signs a negative with U+2212 MINUS SIGN. A
   * *discrete* axis does not — its labels are the domain's own strings, which JavaScript spells
   * with a hyphen — so upstream draws two different characters depending on the scale type. Both
   * forms were read out of the pinned upstream; a band axis over `[-5, 12, -1200]` labels `-5`,
   * ungrouped, while a linear one over the same values labels `−1,200`.
   */
  @Test
  fun `a continuous tick label is signed with a typographic minus`() {
    assertEquals("−5", formatTickLabel(-5.0, 0))
    assertEquals("−1,200", formatTickLabel(-1200.0, 0))
    assertEquals("−0.50", formatTickLabel(-0.5, 2))
    assertEquals("0", formatTickLabel(-0.0, 0))
    assertEquals("5", formatTickLabel(5.0, 0))
    assertEquals(
      listOf("−10", "−5", "0", "5"),
      LinearScale("s", listOf(-12.0, 8.0), listOf(0.0, 200.0)).let { scale ->
        scale.ticks(5).map { scale.formatTick(it, 5) }
      },
    )
    assertEquals(
      listOf("-5", "12", "-1200"),
      BandScale("s", listOf("-5", "12", "-1200"), listOf(0.0, 200.0)).domain,
    )
  }

  /** d3-format spells these the way JavaScript does, rather than with the mathematical symbol. */
  @Test
  fun `a non-finite tick label reads as upstream writes it`() {
    assertEquals("Infinity", formatTickLabel(Double.POSITIVE_INFINITY, 0))
    assertEquals("−Infinity", formatTickLabel(Double.NEGATIVE_INFINITY, 0))
  }
}
