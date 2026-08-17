package dev.aster.vega.dataflow.transform

import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.field
import kotlin.math.PI
import kotlin.math.abs

/**
 * `pie`: turns a column of numbers into the angles of a pie chart.
 *
 * Each row gains a start and an end angle, so an arc mark can draw it. Angles run clockwise from
 * twelve o'clock, and a row's share of the sweep is its value's share of the total — with the
 * absolute value taken, because a negative slice would run backwards over its neighbour rather than
 * shrinking.
 *
 * Without a `field` every row gets an equal share, which is how a specification asks for a plain
 * segmented ring rather than a proportional one.
 */
public object PieTransform : Transform {
  override val type: String = "pie"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    if (input.isEmpty()) return input
    val path = (params.fields["field"] as? VegaValue.Str)?.value
    val values = input.map { datum ->
      if (path == null) 1.0 else abs(datum.field(path).asDouble()).takeIf { it.isFinite() } ?: 0.0
    }
    val total = values.sum()

    val start = (params.fields["startAngle"] as? VegaValue.Num)?.value ?: 0.0
    val end = (params.fields["endAngle"] as? VegaValue.Num)?.value ?: (2 * PI)
    val names = params.stringList("as")
    val startName = names.getOrNull(0) ?: "startAngle"
    val endName = names.getOrNull(1) ?: "endAngle"

    // Everything at zero would divide by zero; an empty ring is the honest answer.
    val scale = if (total > 0.0) (end - start) / total else 0.0

    var angle = start
    return input.mapIndexed { index, datum ->
      val from = angle
      angle += values[index] * scale
      datum.withFields(mapOf(startName to VegaValue.Num(from), endName to VegaValue.Num(angle)))
    }
  }
}
