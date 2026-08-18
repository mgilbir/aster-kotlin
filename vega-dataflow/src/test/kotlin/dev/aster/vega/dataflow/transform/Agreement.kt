package dev.aster.vega.dataflow.transform

import dev.aster.vega.model.VegaValue
import kotlin.math.abs
import kotlin.math.ulp

/**
 * Numeric agreement for the replays that compare this engine against vectors recorded from
 * upstream's own test suites.
 *
 * Those replays used to compare doubles by their full decimal expansion, which asserts something
 * stronger than "we compute the same function": it asserts we reach it through the same `libm`.
 * Nothing can satisfy that. V8's `Math.log` and the JVM's differ in the last bit for some arguments
 * on glibc, so `cumulativeLogNormal` and `kde` — which is built on the normal density — diverged on
 * Linux at a relative 1.8e-16, under one double epsilon. The same vectors agreed exactly on macOS,
 * where the two happen to round alike. A comparison that passes on one host and fails on another
 * for a difference in the seventeenth significant digit is measuring the host, not the port.
 *
 * So numbers agree within a few ulps and everything else stays exact — the shape, the keys, the
 * strings, the nulls, the types. The tolerance is deliberately far too tight to hide a real defect:
 * the divergences this harness has actually caught were wrong branches and wrong formulae, showing
 * up at 1e-3 and larger, twelve orders of magnitude above this.
 */
internal object Agreement {
  /**
   * Eight ulps. One would cover the raw `libm` difference; the slack is for it propagating through
   * a transform that accumulates, as `kde` does when it sums a kernel over every sample.
   */
  private const val ULPS = 8

  fun agree(a: Double, b: Double): Boolean {
    if (a == b) return true
    if (a.isNaN() || b.isNaN()) return a.isNaN() && b.isNaN()
    // An infinity has no meaningful neighbourhood, so it has to be matched exactly, which `==`
    // above already did.
    if (!a.isFinite() || !b.isFinite()) return false
    return abs(a - b) <= ULPS * maxOf(abs(a), abs(b)).ulp
  }

  /**
   * A timestamp read as a number and a timestamp read as a timestamp are the same instant. The
   * recorded vectors are JSON, which has no timestamp type, so upstream's `timeunit` boundaries
   * arrive as numbers while ours come back as [VegaValue.Timestamp]; the rendered-string comparison
   * this replaced could not tell the two apart and so never had to say. Comparing the instants is
   * the intent — insisting on the wrapper would fail every `timeunit` vector.
   */
  private fun instantOrNumber(value: VegaValue): Double? =
    when (value) {
      is VegaValue.Num -> value.value
      is VegaValue.Timestamp -> value.epochMillis
      else -> null
    }

  fun agree(a: VegaValue, b: VegaValue): Boolean =
    when {
      instantOrNumber(a) != null && instantOrNumber(b) != null ->
        agree(instantOrNumber(a)!!, instantOrNumber(b)!!)
      a is VegaValue.Arr && b is VegaValue.Arr ->
        a.values.size == b.values.size && a.values.indices.all { agree(a.values[it], b.values[it]) }
      a is VegaValue.Obj && b is VegaValue.Obj ->
        a.fields.keys == b.fields.keys &&
          a.fields.all { (key, value) -> agree(value, b.fields.getValue(key)) }
      else -> a == b
    }
}
