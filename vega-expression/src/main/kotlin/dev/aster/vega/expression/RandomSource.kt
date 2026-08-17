package dev.aster.vega.expression

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The stream `random()` and the `sample*` family draw from.
 *
 * A stream rather than a function because upstream's is one: `vega-functions` calls a module-level
 * `random` binding that every expression in a view shares, so the *order* of the draws is part of
 * the answer. Two charts that ask for the same count of numbers in a different order get different
 * pictures, and reproducing upstream means reproducing the order as well as the generator.
 *
 * `sampleNormal`'s spare value lives here for the same reason. Box–Muller produces two normals per
 * pair of uniforms and upstream keeps the second in a module-level `nextSample` until the next
 * call, so a chart that draws an odd number of normals leaves one behind for whatever asks next.
 * Holding that in the stream is what keeps the sequence identical.
 */
public class RandomStream(seed: Long = DEFAULT_SEED) {

  private var state: Double = seed.toDouble()

  /** Box–Muller's second value, kept between calls exactly as upstream keeps it. */
  private var spare: Double = Double.NaN

  /**
   * Upstream's `randomLCG`, arithmetic included.
   *
   * A linear congruential generator with glibc's constants, and the multiplication is deliberately
   * left in **doubles**: `1103515245 * seed` reaches 2.4e18 for a seed near 2^31, which is past
   * 2^53, so JavaScript loses low bits and everything that follows is a property of that loss.
   * Computing it exactly — in a `Long`, say — would give a different and arguably better sequence,
   * and would not be upstream's.
   */
  public fun next(): Double {
    state = (1103515245.0 * state + 12345.0) % 2147483647.0
    return state / 2147483647.0
  }

  /** `sampleUniform(min, max)`, with upstream's one-argument shorthand for `[0, min)`. */
  public fun sampleUniform(min: Double?, max: Double?): Double {
    val (low, high) = if (max == null) 0.0 to (min ?: 1.0) else (min ?: 0.0) to max
    return low + (high - low) * next()
  }

  /**
   * `sampleNormal(mean, stdev)` — Box–Muller, rejecting draws outside the unit circle.
   *
   * The rejection loop is what makes the *count* of uniforms consumed depend on the values drawn,
   * so this cannot be reproduced by counting calls; it has to be run.
   */
  public fun sampleNormal(mean: Double?, stdev: Double?): Double {
    val mu = mean ?: 0.0
    val sigma = stdev ?: 1.0
    var x: Double
    if (!spare.isNaN()) {
      x = spare
      spare = Double.NaN
    } else {
      var y: Double
      var radius: Double
      do {
        x = next() * 2 - 1
        y = next() * 2 - 1
        radius = x * x + y * y
      } while (radius == 0.0 || radius > 1)
      val c = sqrt(-2 * ln(radius) / radius)
      x *= c
      spare = y * c
    }
    return mu + x * sigma
  }

  /** `sampleLogNormal(mean, stdev)` — upstream draws a *standard* normal and scales after. */
  public fun sampleLogNormal(mean: Double?, stdev: Double?): Double =
    exp((mean ?: 0.0) + sampleNormal(null, null) * (stdev ?: 1.0))

  /**
   * The bootstrap behind an aggregate's `ci0`/`ci1`.
   *
   * [samples] resamples of the mean, each drawing `n` values with replacement, then the two
   * quantiles at `alpha/2` and `1 - alpha/2` of the sorted means. `~~(random() * n)` truncates
   * toward zero, which for a non-negative product is a floor.
   */
  public fun bootstrapConfidence(
    values: List<Double>,
    samples: Int = 1000,
    alpha: Double = 0.05,
  ): Pair<Double, Double>? {
    if (values.isEmpty()) return null
    val n = values.size
    val means = DoubleArray(samples)
    for (draw in 0 until samples) {
      var total = 0.0
      for (i in 0 until n) total += values[(next() * n).toInt()]
      means[draw] = total / n
    }
    means.sort()
    return quantile(means, alpha / 2) to quantile(means, 1 - alpha / 2)
  }

  /** d3-array's `quantile`: linear interpolation between the two neighbouring order statistics. */
  private fun quantile(sorted: DoubleArray, p: Double): Double {
    val n = sorted.size
    if (n == 0) return Double.NaN
    if (n < 2 || p <= 0) return sorted[0]
    if (p >= 1) return sorted[n - 1]
    val position = (n - 1) * p
    val low = position.toInt()
    val fraction = position - low
    return sorted[low] + (sorted[low + 1] - sorted[low]) * fraction
  }

  public companion object {
    /**
     * The seed a chart uses unless a host says otherwise.
     *
     * There has to be a default, and it has to be a constant: a chart that draws a different
     * picture on every compile cannot be compared with anything, including itself. 42 is arbitrary
     * and is written into `oracle-js/src/seed.mjs` as well, because the two have to agree —
     * `vega.setRandom(vega.randomLCG(42))` puts this exact generator into upstream, which is what
     * lets a chart built on `random()` have a differential reference at all.
     */
    public const val DEFAULT_SEED: Long = 42L
  }
}

/**
 * The instant `now()` reports.
 *
 * A seam for the same reason [RandomStream] is one: a specification whose scale domain is derived
 * from the current time draws a different chart every day, so a *test* has to be able to stand
 * still. A host may supply the real clock; the fixture harness and the oracle both use [PINNED].
 */
public fun interface Clock {
  /** Epoch milliseconds, as `Date.now()` reports them. */
  public fun now(): Double

  public companion object {
    /**
     * 2026-01-01T00:00:00Z, which is what `oracle-js` stubs `Date.now` to.
     *
     * Chosen for being a round instant in no daylight-saving transition; the value carries no
     * meaning beyond both sides agreeing on it.
     */
    public const val PINNED: Double = 1_767_225_600_000.0

    /** The pinned clock, which is also the default: a compile is a pure function or it is not. */
    public val Fixed: Clock = Clock { PINNED }
  }
}
