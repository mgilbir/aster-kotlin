package dev.aster.vega.runtime.compile

import dev.aster.vega.dataflow.transform.ProjectionDefinition
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.asString
import dev.aster.vega.model.field
import dev.aster.vega.model.spec.NumberList
import dev.aster.vega.model.spec.NumberValue
import dev.aster.vega.model.spec.ProjectionSpec

/**
 * Turns a `projections` block into the descriptions a `geoshape` transform can build from.
 *
 * All this does is resolve signals. It resolves them **here**, at compile time and in the scope
 * that declared them, rather than passing the specification down — a projection's `rotate` is three
 * separate signals in every map that lets a reader turn the globe, and the transform has no idea
 * what a signal is.
 */
internal class ProjectionResolver(
  private val numbers: NumberResolver,
  private val diagnostics: DiagnosticCollector,
) {

  fun resolve(specs: List<ProjectionSpec>): Map<String, ProjectionDefinition> = specs.associate {
    it.name to definition(it)
  }

  private fun definition(spec: ProjectionSpec): ProjectionDefinition {
    // Upstream's own default, applied when a projection names no type at all.
    val type =
      spec.typeSignal?.let { numbers.resolveText(it, spec.name) } ?: spec.type ?: "mercator"
    return ProjectionDefinition(
      name = spec.name,
      type = type,
      scale = number(spec.scale, spec.name),
      translate = numberList(spec.translate, spec.name),
      center = numberList(spec.center, spec.name),
      rotate = numberList(spec.rotate, spec.name),
      angle = number(spec.angle, spec.name),
      precision = number(spec.precision, spec.name),
      // Upstream reads these as booleans; a signal delivering 0 or 1 reads the same way.
      reflectX = number(spec.reflectX, spec.name)?.let { it != 0.0 } ?: false,
      reflectY = number(spec.reflectY, spec.name)?.let { it != 0.0 } ?: false,
      clipExtent = flatten(spec.clipExtent, spec.name),
      clipAngle = number(spec.clipAngle, spec.name),
      parallels = numberList(spec.parallels, spec.name),
      pointRadius = number(spec.pointRadius, spec.name),
      // `fit` is the one projection property that is *data*: `{"signal": "data('states')"}`, a
      // whole dataset of features. It is resolved here, in the scope that declared it, for the
      // same reason every other signal is — and it is why a fitted projection cannot be built
      // until the data it fits has loaded.
      fit = spec.fit?.let { geometry(it, spec.name) },
      fitExtent = flatten(spec.extent, spec.name),
      fitSize = numberList(spec.size, spec.name),
    )
  }

  /**
   * The GeoJSON a `fit` names, collected the way upstream's `collectGeoJSON` collects it.
   *
   * A single object is used as it stands; several are wrapped in a `FeatureCollection`, and a bare
   * geometry inside one is promoted to a `Feature` first. The distinction matters because a stream
   * reads a `FeatureCollection`'s `features` and a geometry's `coordinates`, and handing it the
   * wrong shape measures nothing rather than failing.
   */
  private fun geometry(value: VegaValue, owner: String): VegaValue? {
    val resolved =
      (value as? VegaValue.Obj)?.fields?.get("signal")?.asString()?.let {
        numbers.resolveValue(it, owner)
      } ?: value
    val items =
      when (resolved) {
        is VegaValue.Arr -> resolved.values
        is VegaValue.Null -> return null
        else -> listOf(resolved)
      }
    if (items.isEmpty()) return null
    if (items.size == 1) return items[0]
    val features = items.flatMap { item ->
      when (item.field("type").asString()) {
        "FeatureCollection" -> (item.field("features") as? VegaValue.Arr)?.values.orEmpty()
        "Feature" -> listOf(item)
        else ->
          listOf(VegaValue.Obj(linkedMapOf("type" to VegaValue.Str("Feature"), "geometry" to item)))
      }
    }
    return VegaValue.Obj(
      linkedMapOf(
        "type" to VegaValue.Str("FeatureCollection"),
        "features" to VegaValue.Arr(features),
      )
    )
  }

  private fun number(value: NumberValue?, owner: String): Double? =
    when (value) {
      null -> null
      is NumberValue.Constant -> value.value
      is NumberValue.Signal -> numbers.resolve(value, owner)
    }

  private fun numberList(values: NumberList, owner: String): List<Double> =
    when (values) {
      is NumberList.None -> emptyList()
      is NumberList.Items -> values.values.mapNotNull { number(it, owner) }
      // One signal for the whole list: it evaluates to an array, and each entry is read the same
      // way a written-out one would be.
      is NumberList.Signal ->
        (numbers.resolveValue(values.expression, owner) as? VegaValue.Arr)
          ?.values
          ?.map { it.asDouble() }
          ?.filterNot { it.isNaN() }
          .orEmpty()
    }

  private fun flatten(values: List<NumberList>, owner: String): List<Double> = values.flatMap {
    numberList(it, owner)
  }
}
