package dev.aster.vega.runtime.scale

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Every expected value here was generated from the pinned d3-array in `oracle-js`, which is what
 * upstream Vega uses. These are reference vectors, not guesses: if one changes, either the port has
 * drifted or upstream did, and the difference has to be explained.
 */
class TicksTest {

  @ParameterizedTest
  @CsvSource(
    // start, stop, count, expected increment
    "0, 1, 10, -10",
    "0, 100, 10, 10",
    "19, 91, 10, 10",
    "0, 91, 10, 10",
    "-50, 50, 5, 20",
    "0, 1, 3, -2",
    "0, 7, 5, 1",
    "1, 1000000, 10, 100000",
    "0.1, 0.9, 4, -5",
    "-1, 1, 2, 1",
    "0, 0.0001, 10, -100000",
  )
  fun `tickIncrement matches d3`(start: Double, stop: Double, count: Int, expected: Double) {
    assertEquals(expected, Ticks.tickIncrement(start, stop, count))
  }

  @Test
  fun `tickIncrement reports a degenerate range`() {
    assertEquals(Double.NEGATIVE_INFINITY, Ticks.tickIncrement(1.0, 1.0, 10))
    assertEquals(Double.NEGATIVE_INFINITY, Ticks.tickIncrement(0.0, Double.NaN, 10))
  }

  @ParameterizedTest
  @CsvSource(
    delimiter = '|',
    value =
      [
        "0|1|10|0,0.1,0.2,0.3,0.4,0.5,0.6,0.7,0.8,0.9,1",
        "0|100|10|0,10,20,30,40,50,60,70,80,90,100",
        "19|91|10|20,30,40,50,60,70,80,90",
        "0|91|10|0,10,20,30,40,50,60,70,80,90",
        "-50|50|5|-40,-20,0,20,40",
        "0|1|3|0,0.5,1",
        "0|7|5|0,1,2,3,4,5,6,7",
        "1|1000000|10|100000,200000,300000,400000,500000,600000,700000,800000,900000,1000000",
        "0.1|0.9|4|0.2,0.4,0.6,0.8",
        "-1|1|2|-1,0,1",
      ],
  )
  fun `ticks match d3`(start: Double, stop: Double, count: Int, expected: String) {
    val actual = Ticks.ticks(start, stop, count)
    val wanted = expected.split(',').map { it.toDouble() }
    assertEquals(wanted.size, actual.size, "tick count for [$start, $stop] x $count: $actual")
    wanted.zip(actual).forEach { (e, a) -> assertEquals(e, a, 1e-9) }
  }

  @Test
  fun `ticks handle a fractional step without accumulating error`() {
    // The negative-increment path exists precisely so 0.1-steps stay exact.
    val values = Ticks.ticks(0.0, 1.0, 10)
    assertEquals(0.30000000000000004, 0.1 + 0.2, 1e-18) // the naive sum drifts
    assertEquals(0.3, values[3], 0.0) // the tick does not
  }

  @Test
  fun `ticks of a degenerate or empty range`() {
    assertEquals(listOf(1.0), Ticks.ticks(1.0, 1.0, 10))
    assertEquals(emptyList<Double>(), Ticks.ticks(0.0, 1.0, 0))
    assertEquals(emptyList<Double>(), Ticks.ticks(0.0, Double.NaN, 10))
  }

  @Test
  fun `ticks of a reversed range come back descending`() {
    assertEquals(listOf(100.0, 90.0, 80.0), Ticks.ticks(100.0, 80.0, 2))
  }

  @ParameterizedTest
  @CsvSource(
    delimiter = '|',
    value =
      [
        "19|91|10|10,100",
        "0|91|10|0,100",
        "0.1|0.9|4|0,1",
        "1|1000000|10|0,1000000",
        "-3|7|5|-4,8",
      ],
  )
  fun `nice matches d3 scaleLinear nice`(
    start: Double,
    stop: Double,
    count: Int,
    expected: String,
  ) {
    val wanted = expected.split(',').map { it.toDouble() }
    val actual = Ticks.nice(listOf(start, stop), count)
    assertEquals(wanted[0], actual[0], 1e-9)
    assertEquals(wanted[1], actual[1], 1e-9)
  }

  @Test
  fun `nice leaves a degenerate domain alone`() {
    assertEquals(listOf(0.0, 0.0), Ticks.nice(listOf(0.0, 0.0)))
    assertEquals(listOf(5.0), Ticks.nice(listOf(5.0)))
    assertEquals(emptyList<Double>(), Ticks.nice(emptyList()))
  }

  @Test
  fun `nice preserves a reversed domain`() {
    val niced = Ticks.nice(listOf(91.0, 19.0), 10)
    assertTrue(niced[0] > niced[1], "reversal should survive: $niced")
    assertEquals(100.0, niced[0], 1e-9)
    assertEquals(10.0, niced[1], 1e-9)
  }

  @Test
  fun `nice does not widen an already round domain`() {
    assertEquals(listOf(0.0, 100.0), Ticks.nice(listOf(0.0, 100.0), 10))
  }

  @Test
  fun `nice tolerates a non-finite domain`() {
    val domain = listOf(0.0, Double.POSITIVE_INFINITY)
    assertEquals(domain, Ticks.nice(domain))
  }

  @Test
  fun `precisionForStep drives default label formatting`() {
    assertEquals(0, Ticks.precisionForStep(10.0))
    assertEquals(0, Ticks.precisionForStep(1.0))
    assertEquals(1, Ticks.precisionForStep(0.1))
    assertEquals(2, Ticks.precisionForStep(0.01))
  }

  @Test
  fun `stepFrom undoes the negative-reciprocal convention`() {
    assertEquals(10.0, Ticks.stepFrom(10.0))
    assertEquals(0.1, Ticks.stepFrom(-10.0), 1e-15)
    assertEquals(0.5, Ticks.stepFrom(-2.0), 1e-15)
    assertTrue(Ticks.stepFrom(Double.NEGATIVE_INFINITY).isNaN())
    assertTrue(Ticks.stepFrom(0.0).isNaN())
  }
}
