package dev.aster.vega.runtime.scale

/**
 * A **banded** scale's legend arithmetic, reachable from a foreign host.
 *
 * The numbers a legend is drawn from — where the buckets cut, what labels them, and where a value
 * sits along the bar — live on [BinnedScale] as members with **default bodies**. An Obj-C protocol
 * cannot carry a default, so Kotlin/Native leaves them out of the generated header: from Swift a
 * `QuantizeScale` arrives with `domain`, `name` and `invertExtent` and none of the rest.
 *
 * The engine draws its own legends, so nothing inside needed them. A host drawing one of its own
 * does, which is the case this exists for.
 *
 * The same shape as `ForeignPaint` and `ForeignValue`: the questions get plain functions, which the
 * boundary understands, and the interface keeps its defaults for Kotlin callers.
 */
public object ForeignScale {

  /** Whether a scale is one of the four with buckets, and so has anything below to answer. */
  public fun isBanded(scale: VegaScale): Boolean = scale is BinnedScale

  /** The cut points between buckets. Empty for a scale that has none. */
  public fun thresholds(scale: VegaScale): List<Double> =
    (scale as? BinnedScale)?.thresholds.orEmpty()

  /**
   * The boundaries a specification's `bins` named, or null where it named none.
   *
   * On [VegaScale] rather than [BinnedScale]: any scale may carry them, and a host asking a linear
   * scale gets null rather than an error.
   */
  public fun bins(scale: VegaScale): List<Double>? = scale.bins

  /** One value per bucket, at its lower edge — what a banded legend labels its swatches with. */
  public fun legendValues(scale: VegaScale): List<Double> =
    (scale as? BinnedScale)?.legendValues.orEmpty()

  /**
   * What bounds the last bucket from above.
   *
   * Infinite for three of the four, because nothing bounds them; a bin scale's bins have a last
   * edge. A host labelling the top swatch writes "≥ 75" for the infinite case.
   */
  public fun legendMax(scale: VegaScale): Double =
    (scale as? BinnedScale)?.legendMax ?: Double.POSITIVE_INFINITY

  /** A representative value inside each bucket, for a swatch that has to be *coloured*. */
  public fun bucketRepresentatives(scale: VegaScale): List<Double> =
    (scale as? BinnedScale)?.bucketRepresentatives.orEmpty()

  /** Where a value sits along the legend's bar, in `0..1`, or 0 for a scale with no bands. */
  public fun legendFraction(scale: VegaScale, value: Double): Double =
    (scale as? BinnedScale)?.legendFraction(value) ?: 0.0

  /**
   * The extent of the scale's input, as the two numbers a bar is measured against.
   *
   * `legendExtent` is a `Pair`, which crosses as an opaque `KotlinPair` whose halves are `id`. Two
   * functions rather than one is the honest shape at this boundary.
   */
  public fun legendExtentLow(scale: VegaScale): Double? =
    (scale as? BinnedScale)?.legendExtent?.first

  /** As [legendExtentLow], for the upper end. */
  public fun legendExtentHigh(scale: VegaScale): Double? =
    (scale as? BinnedScale)?.legendExtent?.second

  /**
   * The lower cut of the bucket at [index], or null where nothing bounds it.
   *
   * A threshold scale's outermost buckets are unbounded: a cut point at 10 says nothing about how
   * far below it the first bucket reaches, and null is that fact rather than a missing value.
   */
  public fun bucketLow(scale: VegaScale, index: Int): Double? {
    val binned = scale as? BinnedScale ?: return null
    return if (index > 0) binned.thresholds.getOrNull(index - 1) else null
  }

  /** As [bucketLow], for the upper cut. */
  public fun bucketHigh(scale: VegaScale, index: Int): Double? {
    val binned = scale as? BinnedScale ?: return null
    return binned.thresholds.getOrNull(index)
  }
}
