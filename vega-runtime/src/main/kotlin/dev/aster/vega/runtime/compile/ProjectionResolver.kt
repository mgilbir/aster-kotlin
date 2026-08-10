package dev.aster.vega.runtime.compile

import dev.aster.vega.dataflow.transform.ProjectionDefinition
import dev.aster.vega.model.DiagnosticCollector
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
      )
      .also { report(spec) }
  }

  /** What is parsed but not yet honoured, said out loud rather than dropped. */
  private fun report(spec: ProjectionSpec) {
    val unsupported = buildList {
      if (spec.clipAngle != null) add("clipAngle")
      if (spec.fit != null) add("fit")
      if (spec.extent.isNotEmpty()) add("extent")
      if (spec.size.isNotEmpty()) add("size")
    }
    if (unsupported.isEmpty()) return
    diagnostics.error(
      dev.aster.vega.model.DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED,
      "Projection '${spec.name}' uses ${unsupported.joinToString(", ")}, which is not " +
        "implemented; the projection was built without it, so the map is placed but not sized " +
        "or clipped the way the specification asked",
      operator = spec.name,
    )
  }

  private fun number(value: NumberValue?, owner: String): Double? =
    when (value) {
      null -> null
      is NumberValue.Constant -> value.value
      is NumberValue.Signal -> numbers.resolve(value, owner)
    }

  private fun numberList(values: List<NumberValue>, owner: String): List<Double> =
    values.mapNotNull {
      number(it, owner)
    }

  private fun flatten(values: List<List<NumberValue>>, owner: String): List<Double> =
    values.flatMap {
      numberList(it, owner)
    }
}
