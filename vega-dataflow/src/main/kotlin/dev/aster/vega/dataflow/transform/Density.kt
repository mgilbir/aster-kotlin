package dev.aster.vega.dataflow.transform

import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.field

/**
 * `kde`: the smooth curve a histogram is trying to be.
 *
 * A histogram's shape depends on where its bin edges happen to fall; a kernel density estimate puts
 * a Gaussian bump over each observation instead and adds them up, so the answer depends only on how
 * wide the bumps are. That width is `bandwidth`, and left unset it comes from Scott's rule — which
 * is a good default and still an arbitrary one, so a density that looks too smooth or too spiky is
 * usually saying something about the bandwidth rather than about the data.
 *
 * Three parameters change what the numbers mean rather than how they are sampled:
 * - `cumulative` gives the distribution function instead of the density, running 0 to 1;
 * - `counts` multiplies by the group's size, turning a probability into a smoothed count, which is
 *   what makes two groups of different sizes comparable by area rather than by shape;
 * - `resolve: "shared"` forces every group onto one domain and one **uniform** sample grid, since
 *   adaptive sampling would give each group its own x positions and leave nothing to stack.
 */
public object KdeTransform : Transform {
  override val type: String = "kde"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val field = params.string("field")
    if (field.isNullOrEmpty()) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "kde needs a 'field'",
        operator = type,
      )
      return input
    }
    val groupBy = params.stringList("groupby")
    val bandwidth = params.number("bandwidth") ?: 0.0
    val cumulative = params.boolean("cumulative") ?: false
    val counts = params.boolean("counts") ?: false
    val names = params.stringList("as")
    val valueName = names.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: "value"
    val densityName = names.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: "density"

    val steps = params.number("step")?.toInt() ?: params.number("steps")?.toInt()
    var minSteps = steps ?: params.number("minsteps")?.toInt() ?: 25
    var maxSteps = steps ?: params.number("maxsteps")?.toInt() ?: 200
    var domain = params.numberList("extent").takeIf { it.size >= 2 }

    if (params.string("resolve") == "shared") {
      if (domain == null) {
        val all = input.map { it.field(field).asDouble() }.filterNot { it.isNaN() }
        if (all.isNotEmpty()) domain = listOf(all.min(), all.max())
      }
      minSteps = steps ?: maxSteps
      maxSteps = minSteps
    }

    return groupTuples(input, groupBy).flatMap { (groupKey, rows) ->
      val support = rows.map { it.field(field).asDouble() }
      if (support.isEmpty()) return@flatMap emptyList()
      val distribution = Distributions.kde(support, bandwidth)
      val method: (Double) -> Double = if (cumulative) distribution::cdf else distribution::pdf
      val scale = if (counts) support.size.toDouble() else 1.0
      val low =
        domain?.get(0) ?: support.filterNot { it.isNaN() }.minOrNull() ?: return@flatMap emptyList()
      val high = domain?.get(1) ?: support.filterNot { it.isNaN() }.max()

      val prefix = LinkedHashMap<String, VegaValue>(groupBy.size)
      groupBy.forEachIndexed { index, path -> prefix[path] = groupKey[index] }
      CurveSampler.sample(method, low, high, minSteps, maxSteps).map { p ->
        VegaValue.Obj(
          prefix +
            mapOf(valueName to VegaValue.Num(p[0]), densityName to VegaValue.Num(p[1] * scale))
        )
      }
    }
  }
}

/**
 * `density`: samples a named distribution, with or without any data behind it.
 *
 * Where `kde` estimates a distribution from a column, this one is *told* which distribution to draw
 * — `normal`, `lognormal`, `uniform`, a `kde` over a field, or a weighted `mixture` of those. It is
 * how a specification puts a theoretical curve over a histogram, or draws a reference bell that no
 * row in the data produced.
 *
 * `extent` is required, because a distribution has no data to take one from — except a `kde`, which
 * does, and that is the one case where it may be omitted.
 */
public object DensityTransform : Transform {
  override val type: String = "density"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val definition = params.fields["distribution"] as? VegaValue.Obj
    if (definition == null) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "density needs a 'distribution'",
        operator = type,
      )
      return input
    }
    val method = params.string("method") ?: "pdf"
    if (method != "pdf" && method != "cdf") {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "density method '$method' is neither 'pdf' nor 'cdf'",
        operator = type,
      )
      return input
    }

    val support = mutableListOf<Double>()
    val distribution = parse(definition, input, context, support) ?: return input

    val names = params.stringList("as")
    val valueName = names.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: "value"
    val densityName = names.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: "density"
    val steps = params.number("steps")?.toInt()
    val minSteps = steps ?: params.number("minsteps")?.toInt() ?: 25
    val maxSteps = steps ?: params.number("maxsteps")?.toInt() ?: 200

    val extent = params.numberList("extent").takeIf { it.size >= 2 }
    val clean = support.filterNot { it.isNaN() }
    val low = extent?.get(0) ?: clean.minOrNull()
    val high = extent?.get(1) ?: clean.maxOrNull()
    if (low == null || high == null) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "density needs an 'extent'; only a kde distribution can take one from its own data",
        operator = type,
      )
      return input
    }

    val f: (Double) -> Double = if (method == "cdf") distribution::cdf else distribution::pdf
    return CurveSampler.sample(f, low, high, minSteps, maxSteps).map { p ->
      VegaValue.Obj(mapOf(valueName to VegaValue.Num(p[0]), densityName to VegaValue.Num(p[1])))
    }
  }

  /** @param support collects the sample points a `kde` was built over, for the default extent. */
  private fun parse(
    definition: VegaValue.Obj,
    input: List<VegaValue>,
    context: TransformContext,
    support: MutableList<Double>,
  ): Distributions.Distribution? {
    return when (val function = definition.string("function")) {
      "normal" ->
        Distributions.normal(definition.number("mean") ?: 0.0, definition.number("stdev") ?: 1.0)
      "lognormal" ->
        Distributions.logNormal(definition.number("mean") ?: 0.0, definition.number("stdev") ?: 1.0)
      "uniform" ->
        Distributions.uniform(definition.number("min") ?: 0.0, definition.number("max") ?: 1.0)
      "kde" -> {
        val field = definition.string("field")
        if (field.isNullOrEmpty()) {
          context.diagnostics.error(
            DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
            "a kde distribution needs a 'field'",
            operator = type,
          )
          return null
        }
        val rows = definition.string("from")?.let { context.scope.dataset(it) } ?: input
        val values = rows.map { it.field(field).asDouble() }
        support += values
        Distributions.kde(values, definition.number("bandwidth") ?: 0.0)
      }
      "mixture" -> {
        val parts =
          (definition.fields["distributions"] as? VegaValue.Arr)?.values.orEmpty().mapNotNull {
            (it as? VegaValue.Obj)?.let { part -> parse(part, input, context, support) }
          }
        if (parts.isEmpty()) {
          context.diagnostics.error(
            DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
            "a mixture needs at least one distribution",
            operator = type,
          )
          return null
        }
        Distributions.mixture(parts, definition.numberList("weights"))
      }
      else -> {
        context.diagnostics.error(
          DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
          "unknown distribution function '$function'",
          operator = type,
        )
        null
      }
    }
  }
}

/**
 * `dotbin`: where each dot of a dot plot sits, once they have been stacked.
 *
 * Wilkinson's algorithm, and the thing to understand about it is that a dot plot is **not** a
 * histogram with round marks. There are no fixed bin edges: a stack starts at the first value that
 * does not fit within `step` of the one that opened the current stack, and every dot in a stack is
 * placed at the *mean* of the values in it. So the stacks are where the data is dense rather than
 * where a grid says, and their positions move as data is added.
 *
 * `smooth` runs Wilkinson's variance-reduction pass afterwards, swapping dots between neighbouring
 * stacks that sit within a quarter step of each other so the outline is less jagged. It moves dots
 * away from their own value, which is the trade being made.
 *
 * Unlike the other statistical transforms this one **adds a field to the existing rows** rather
 * than replacing them, so the rows come back in their original order with `bin` alongside
 * everything they already carried.
 */
public object DotBinTransform : Transform {
  override val type: String = "dotbin"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val field = params.string("field")
    if (field.isNullOrEmpty()) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "dotbin needs a 'field'",
        operator = type,
      )
      return input
    }
    val groupBy = params.stringList("groupby")
    val smooth = params.boolean("smooth") ?: false
    val name = params.string("as")?.takeIf { it.isNotEmpty() } ?: "bin"

    val all = input.map { it.field(field).asDouble() }.filterNot { it.isNaN() }
    if (all.isEmpty()) return input
    // The default step is a thirtieth of the whole field's extent — the whole field's, not the
    // group's, so faceted dot plots stack at the same resolution and stay comparable.
    val step = params.number("step")?.takeIf { it > 0.0 } ?: ((all.max() - all.min()) / 30.0)

    // Positions are computed per group over the group's sorted values, then written back to the
    // rows where they came from, so the output keeps the input's order.
    val placed = HashMap<Int, Double>(input.size)
    val indexed = input.withIndex().toList()
    val groups =
      if (groupBy.isEmpty()) {
        listOf(indexed)
      } else {
        indexed
          .groupBy { (_, row) -> groupBy.map { row.field(it).asComparableKey() } }
          .values
          .toList()
      }
    for (group in groups) {
      val sorted = group.sortedBy { (_, row) -> row.field(field).asDouble() }
      val bins = stack(sorted.map { (_, row) -> row.field(field).asDouble() }, step, smooth)
      sorted.forEachIndexed { position, (index, _) -> placed[index] = bins[position] }
    }

    return input.mapIndexed { index, row ->
      val bin = placed[index]
      if (bin == null) row else row.withFields(mapOf(name to VegaValue.Num(bin)))
    }
  }

  /** One pass over the sorted values, closing a stack whenever the next one is out of reach. */
  private fun stack(sorted: List<Double>, step: Double, smooth: Boolean): DoubleArray {
    val n = sorted.size
    val v = DoubleArray(n)
    if (n == 0) return v
    var i = 0
    var a = sorted[0]
    var b = a
    var w = a + step
    var j = 1
    while (j < n) {
      val x = sorted[j]
      if (x >= w) {
        // The stack is closed: every dot in it takes the midpoint of its first and last value.
        b = (a + b) / 2
        while (i < j) v[i++] = b
        w = x + step
        a = x
      }
      b = x
      j++
    }
    b = (a + b) / 2
    while (i < j) v[i++] = b
    return if (smooth) smoothed(v, step + step / 4) else v
  }

  /**
   * Wilkinson's smoothing: even out two adjacent stacks by moving dots from the taller to the
   * shorter, where "adjacent" means within a quarter step.
   */
  private fun smoothed(v: DoubleArray, threshold: Double): DoubleArray {
    val n = v.size
    var a = 0
    var b = 1
    while (b < n && v[a] == v[b]) b++
    while (b < n) {
      var c = b + 1
      while (c < n && v[b] == v[c]) c++
      if (v[b] - v[b - 1] < threshold) {
        var d = b + ((a + c - b - b) shr 1)
        while (d < b) v[d++] = v[b]
        while (d > b) v[d--] = v[a]
      }
      a = b
      b = c
    }
    return v
  }
}
