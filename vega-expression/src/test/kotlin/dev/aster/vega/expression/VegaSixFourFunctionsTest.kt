package dev.aster.vega.expression

import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The expression vocabulary Vega 6.4.0 added, against upstream's own answers.
 *
 * `interpolateLinear`'s first eleven cases are vega-functions' own 6.4.0 tests as the recorder
 * captured them (`test-fixtures/upstream-vectors/vega-functions.json`); the rest were probed
 * against the installed 6.4.0 under `Europe/Amsterdam`, the zone the tests run in. The easing
 * curves are `UpstreamEaseVectorsTest`'s.
 */
class VegaSixFourFunctionsTest {

  private val compiler = VegaExpressionCompiler()

  private object EmptyScope : ExpressionScope {
    override val datum: VegaValue = VegaValue.EmptyObject

    override fun signal(name: String): VegaValue = VegaValue.Null

    override fun dataset(name: String): List<VegaValue> = emptyList()
  }

  private fun evaluate(source: String): VegaValue {
    val result = compiler.compile(source)
    assertTrue(result is ExpressionResult.Compiled, "failed to parse $source: $result")
    return (result as ExpressionResult.Compiled).expression.evaluate(EmptyScope)
  }

  private fun number(source: String): Double = (evaluate(source) as VegaValue.Num).value

  @Test
  fun `interpolateLinear reads a sampled curve, as vega-functions' own tests do`() {
    assertEquals(0.0, number("interpolateLinear([0, 10, 20], 0)"))
    assertEquals(5.0, number("interpolateLinear([0, 10, 20], 0.25)"))
    assertEquals(10.0, number("interpolateLinear([0, 10, 20], 0.5)"))
    assertEquals(15.0, number("interpolateLinear([0, 10, 20], 0.75)"))
    assertEquals(20.0, number("interpolateLinear([0, 10, 20], 1)"))
    assertEquals(100.0, number("interpolateLinear([0, 100, 10], 0.5)"))
    assertEquals(1.0, number("interpolateLinear([1, 2, 3], -1)"))
    assertEquals(3.0, number("interpolateLinear([1, 2, 3], 2)"))
    assertEquals(7.0, number("interpolateLinear([7], 0.5)"))
    assertEquals(VegaValue.Null, evaluate("interpolateLinear([], 0.5)"))
    assertEquals(VegaValue.Null, evaluate("interpolateLinear(null, 0.5)"))
  }

  @Test
  fun `interpolateLinear coerces the way JavaScript's arithmetic does`() {
    assertEquals(2.0, number("interpolateLinear([0, 10, 20], 0.1)"))
    // A NaN position is not above zero, so the first point comes back.
    assertEquals(0.0, number("interpolateLinear([0, 10], NaN)"))
    assertEquals(5.0, number("interpolateLinear([0, 10], '0.5')"))
    assertEquals(5.0, number("interpolateLinear([0, '10'], 0.5)"))
    assertEquals(2.0, number("interpolateLinear([null, 4], 0.5)"))
    assertEquals(3.0, number("interpolateLinear([3, 4])"))
    // `values[i] + t * (...)`: a string point concatenates.
    assertEquals(VegaValue.Str("52.5"), evaluate("interpolateLinear(['5', 10], 0.5)"))
    assertEquals(VegaValue.Str("aNaN"), evaluate("interpolateLinear(['a', 'b'], 0.5)"))
    assertEquals(VegaValue.Null, evaluate("interpolateLinear('abc', 0.5)"))
  }

  @Test
  fun `the ease functions coerce their argument`() {
    assertEquals(0.5, number("easeCubic('0.5')"))
    assertEquals(0.0, number("easeBounce(null)"))
  }

  @Test
  fun `isoweek numbers a week by the year holding its Thursday`() {
    // 29 December 2014 is a Monday, and the week it starts holds 1 January 2015.
    assertEquals(1.0, number("isoweek(datetime(2014, 11, 29))"))
    assertEquals(1.0, number("isoweek(datetime(2021, 0, 4))"))
    // 3 January 2021 is a Sunday, the last day of 2020's week 53.
    assertEquals(53.0, number("utcisoweek(utc(2021, 0, 3))"))
    assertTrue(number("isoweek('abc')").isNaN())
  }

  @Test
  fun `timeOffset steps an isoweek at a time`() {
    // 2021-01-11T00:00 in Amsterdam.
    assertEquals(1610319600000.0, number("time(timeOffset('isoweek', datetime(2021, 0, 4), 1))"))
  }

  @Test
  fun `an object key that shadows the prototype is refused, quoted or not`() {
    for (key in listOf("then", "'then'", "\"toString\"", "toString", "__proto__", "constructor")) {
      val result = compiler.compile("{$key: 1}")
      assertTrue(result !is ExpressionResult.Compiled, "{$key: 1} compiled: $result")
    }
    assertTrue(compiler.compile("{'a': 1, 2: 3, thenable: 4}") is ExpressionResult.Compiled)
  }
}
