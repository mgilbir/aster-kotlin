package dev.aster.vega.dataflow.transform

import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asBoolean
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.field
import kotlin.math.PI

/**
 * `pie`: turns a column of numbers into the angles of a pie chart.
 *
 * Each row gains a start and an end angle, so an arc mark can draw it, and a row's share of the
 * sweep is its value's share of the total. Without a `field` every row gets an equal share, which
 * is how a specification asks for a plain segmented ring rather than a proportional one.
 *
 * Three things here used to be improvements on upstream, and each of them was a divergence with a
 * comment presenting it as fidelity.
 *
 * **`sort` is read.** It is a boolean in upstream's own `Definition` and nothing here consulted it,
 * so a chart asking for its biggest slice first got its slices in data order and no diagnostic. It
 * sorts the *assignment* order and not the output: `index.sort((a, b) => values[a] - values[b])`
 * decides which row gets which sweep, and the rows come back where they started.
 *
 * **The value is not made absolute, and the total is not guarded.** A negative slice does run
 * backwards over its neighbour upstream — `[3, -1]` spans `[0, 9.42]` and `[9.42, 6.28]` — and a
 * group summing to zero divides by zero and produces NaN angles, which draw nothing. Both were
 * being corrected here, so a specification whose data had gone negative saw a plausible chart
 * instead of the broken one upstream draws and the reader would have asked about.
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
    val values = input.map { datum -> if (path == null) 1.0 else datum.field(path).asDouble() }
    // d3's `sum`, which is what upstream divides by: `if (value = +value) sum += value` skips a
    // NaN because it is falsey, and keeps an infinity because it is not.
    val total = values.sumOf { if (it.isNaN()) 0.0 else it }

    val start = (params.fields["startAngle"] as? VegaValue.Num)?.value ?: 0.0
    val end = (params.fields["endAngle"] as? VegaValue.Num)?.value ?: (2 * PI)
    val names = params.stringList("as")
    val startName = names.getOrNull(0) ?: "startAngle"
    val endName = names.getOrNull(1) ?: "endAngle"

    val scale = (end - start) / total

    // Ascending by value, and `values[a] - values[b]` is the comparator: a difference of NaN reads
    // as "equal" to `Array.prototype.sort`, which leaves the pair where it was. `sortedWith` is
    // stable, as upstream's sort is.
    val order = values.indices.toList()
    val assignment =
      if (params.fields["sort"]?.asBoolean() == true) {
        order.sortedWith { left, right ->
          val difference = values[left] - values[right]
          if (difference < 0) -1 else if (difference > 0) 1 else 0
        }
      } else {
        order
      }

    val low = DoubleArray(input.size)
    val high = DoubleArray(input.size)
    var angle = start
    for (position in assignment) {
      low[position] = angle
      angle += values[position] * scale
      high[position] = angle
    }

    return input.mapIndexed { index, datum ->
      datum.withFields(
        mapOf(startName to VegaValue.Num(low[index]), endName to VegaValue.Num(high[index]))
      )
    }
  }
}
