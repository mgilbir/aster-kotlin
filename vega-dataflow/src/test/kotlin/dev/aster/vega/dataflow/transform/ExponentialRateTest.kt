package dev.aster.vega.dataflow.transform

import dev.aster.vega.expression.CachingExpressionCompiler
import dev.aster.vega.expression.ExpressionCompiler
import dev.aster.vega.expression.ExpressionScope
import dev.aster.vega.expression.VegaExpressionCompiler
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.field
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `aggregate_params`: the decay rate the two exponential operations run at.
 *
 * Three findings, each of them a place where the obvious reading is wrong, and each replayed off
 * upstream over the same four rows — 1, 3, 7, 9:
 *
 * | specification                              | upstream |
 * |--------------------------------------------|----------|
 * | `exponential`, rate 0.5                    | 7.133…   |
 * | `exponential`, no rate                     | NaN      |
 * | `exponential`, rate 0                      | NaN      |
 * | `exponentialb`, rate 0.5                   | NaN      |
 * | `exponentialb` beside `exponential` at 0.5 | 6.6875   |
 * | `exponentialb` beside `exponential` at 0.9 | 1.8459   |
 *
 * A missing rate is not a rate of zero and a rate of zero is not a rate: `aggregate_params[i] ||
 * null` makes them the same thing, and that thing leaves `exp_r` **undefined**, so every piece of
 * arithmetic downstream is NaN. `exponentialb` has no accumulator of its own at all — it is
 * declared `req: ['exponential']` — so it reads whatever the transform's `exponential` measure set
 * up, and its own entry in `aggregate_params` is never looked at.
 *
 * And `window` declares the same parameter: the aggregate operations inside a window are compiled
 * by the very same code, so an `exponential` there needs its rate from `aggregate_params` too.
 * Nothing read it, so every windowed exponential was computed at a rate of zero.
 */
class ExponentialRateTest {

  private class Context : TransformContext {
    override var tree: TreeSource? = null
    override val diagnostics = DiagnosticCollector()
    override val expressions: ExpressionCompiler =
      CachingExpressionCompiler(VegaExpressionCompiler())
    override val scope: ExpressionScope = scopeFor(VegaValue.Null)

    override fun setSignal(name: String, value: VegaValue) = Unit

    override fun scopeFor(datum: VegaValue): ExpressionScope =
      object : ExpressionScope {
        override val datum: VegaValue = datum

        override fun signal(name: String): VegaValue = VegaValue.Null

        override fun dataset(name: String): List<VegaValue> = emptyList()
      }
  }

  private val rows =
    (VegaJson.parse("""[{"v": 1}, {"v": 3}, {"v": 7}, {"v": 9}]""")).let {
      (it as VegaValue.Arr).values
    }

  private fun run(transform: Transform, params: String): List<VegaValue> {
    val context = Context()
    val out = transform.apply(rows, VegaJson.parse(params) as VegaValue.Obj, context)
    assertEquals(emptyList<String>(), context.diagnostics.diagnostics.map { it.message })
    return out
  }

  private fun column(out: List<VegaValue>, name: String): List<Double> = out.map {
    (it.field(name) as? VegaValue.Num)?.value ?: Double.NaN
  }

  /**
   * Asserts a column, to within a last-bit tolerance.
   *
   * **Not exact, and the reason is the operation itself.** The normalisation divides by `1 - r^n`,
   * and `Math.pow` is not required to be correctly rounded — the JDK promises within one ulp and
   * `StrictMath` is the one that promises more. `0.9^4` therefore comes out a bit apart on macOS
   * and on Linux, which is where this first failed: the value was right on both hosts and the
   * `assertEquals` that compares doubles by identity was what was wrong. A rate read from the wrong
   * place is out by percent rather than by `1e-15`, so the tolerance costs the test nothing.
   *
   * Upstream has the same freedom in the other direction — JavaScript's `Math.pow` is equally
   * implementation-defined — which is why the differential harness compares at six digits.
   */
  private fun assertColumn(expected: List<Double>, actual: List<Double>, what: String) {
    assertEquals(expected.size, actual.size, "$what: column length")
    expected.zip(actual).forEachIndexed { index, (want, got) ->
      assertEquals(want, got, 1e-12, "$what[$index]")
    }
  }

  @Test
  fun `an aggregate exponential uses the rate it was given`() {
    val out =
      run(
        AggregateTransform,
        """{"ops": ["exponential"], "fields": ["v"], "aggregate_params": [0.5], "as": ["e"]}""",
      )
    assertColumn(listOf(7.133333333333334), column(out, "e"), "e")
  }

  @Test
  fun `an aggregate exponential with no rate answers nothing`() {
    // Not "the last value in the group", which is what a rate of zero computes: upstream never
    // initialised the accumulator, so the answer is NaN.
    val out = run(AggregateTransform, """{"ops": ["exponential"], "fields": ["v"], "as": ["e"]}""")
    assertTrue(column(out, "e").single().isNaN(), "expected NaN, got ${column(out, "e")}")
  }

  @Test
  fun `a rate of zero is no rate at all`() {
    val out =
      run(
        AggregateTransform,
        """{"ops": ["exponential"], "fields": ["v"], "aggregate_params": [0], "as": ["e"]}""",
      )
    assertTrue(column(out, "e").single().isNaN(), "expected NaN, got ${column(out, "e")}")
  }

  @Test
  fun `exponentialb alone answers nothing, whatever rate it names`() {
    val out =
      run(
        AggregateTransform,
        """{"ops": ["exponentialb"], "fields": ["v"], "aggregate_params": [0.5], "as": ["e"]}""",
      )
    assertTrue(column(out, "e").single().isNaN(), "expected NaN, got ${column(out, "e")}")
  }

  @Test
  fun `exponentialb reads the exponential measure's rate, not its own`() {
    val both =
      run(
        AggregateTransform,
        """{"ops": ["exponential", "exponentialb"], "fields": ["v", "v"],
            "aggregate_params": [0.5, 0.5], "as": ["e", "eb"]}""",
      )
    assertColumn(listOf(7.133333333333334), column(both, "e"), "e")
    assertColumn(listOf(6.6875), column(both, "eb"), "eb")

    // The same pair with the rates disagreeing: both operations run at the **exponential** one's.
    val mixed =
      run(
        AggregateTransform,
        """{"ops": ["exponentialb", "exponential"], "fields": ["v", "v"],
            "aggregate_params": [0.5, 0.9], "as": ["eb", "e"]}""",
      )
    assertColumn(listOf(5.367548706019193), column(mixed, "e"), "e")
    assertColumn(listOf(1.8458999999999997), column(mixed, "eb"), "eb")
  }

  @Test
  fun `two exponentials over one field share the last rate declared`() {
    // `resolve()` keys its accumulator map by the operation's name, so the second declaration
    // replaces the first and both outputs report the same number.
    val out =
      run(
        AggregateTransform,
        """{"ops": ["exponential", "exponential"], "fields": ["v", "v"],
            "aggregate_params": [0.5, 0.9], "as": ["a", "b"]}""",
      )
    assertColumn(listOf(5.367548706019193), column(out, "a"), "a")
    assertColumn(listOf(5.367548706019193), column(out, "b"), "b")
  }

  @Test
  fun `a window exponential reads aggregate_params`() {
    val out =
      run(
        WindowTransform,
        """{"ops": ["exponential"], "fields": ["v"], "aggregate_params": [0.5], "as": ["e"]}""",
      )
    // The default frame is `[null, 0]`, so each row's answer is the exponential mean of the rows up
    // to and including it.
    assertColumn(listOf(1.0, 2.3333333333333335, 5.0, 7.133333333333334), column(out, "e"), "e")
  }

  @Test
  fun `a window exponential with no rate answers nothing`() {
    val out = run(WindowTransform, """{"ops": ["exponential"], "fields": ["v"], "as": ["e"]}""")
    assertTrue(column(out, "e").all { it.isNaN() }, "expected NaN, got ${column(out, "e")}")
  }

  @Test
  fun `a window exponentialb follows the window's exponential`() {
    val out =
      run(
        WindowTransform,
        """{"ops": ["exponential", "exponentialb"], "fields": ["v", "v"],
            "aggregate_params": [0.5, 0.5], "as": ["e", "eb"]}""",
      )
    assertColumn(listOf(0.5, 1.75, 4.375, 6.6875), column(out, "eb"), "eb")
  }
}
