package dev.aster.vega.runtime.compile

import dev.aster.vega.dataflow.transform.TransformContext
import dev.aster.vega.dataflow.transform.TransformPipeline
import dev.aster.vega.expression.ExpressionCompiler
import dev.aster.vega.expression.ExpressionScope
import dev.aster.vega.expression.JsSemantics
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.asString
import dev.aster.vega.model.spec.DataSpec
import dev.aster.vega.model.time.DateValues

/**
 * Resolves dataset definitions to plain value lists, running their transform pipelines.
 *
 * Separate from [SpecCompiler] because a group mark declares datasets of its own, and those resolve
 * the same way as the top-level ones except that they can also source from the enclosing scope.
 */
internal class DataResolver(
  private val diagnostics: DiagnosticCollector,
  private val expressions: ExpressionCompiler,
) {

  /**
   * Resolves [specs], layered over the datasets already visible from an enclosing scope.
   *
   * @param signals mutable, because a transform may publish one — `extent` writes its result to a
   *   signal — and anything resolved afterwards has to see it.
   * @param inherited datasets from the enclosing scope. A definition here may source from one of
   *   them, and a same-named definition shadows it.
   * @return [inherited] with the resolved definitions layered on top, so the result is everything
   *   visible in this scope.
   */
  fun resolve(
    specs: List<DataSpec>,
    signals: MutableMap<String, VegaValue>,
    inherited: Map<String, List<VegaValue>> = emptyMap(),
  ): Map<String, List<VegaValue>> {
    if (specs.isEmpty()) return inherited

    val result = LinkedHashMap(inherited)
    val pipeline = TransformPipeline()

    for (spec in specs) {
      var values = spec.values ?: emptyList()
      if (spec.source != null) {
        val upstream = result[spec.source]
        if (upstream == null) {
          diagnostics.error(
            DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
            "Dataset '${spec.name}' sources from unknown dataset '${spec.source}'",
            operator = spec.name,
          )
        } else {
          values = upstream
        }
      }
      if (spec.parse.isNotEmpty()) values = values.map { parseFields(it, spec) }
      if (spec.transform.isNotEmpty()) {
        val context = TransformScope(diagnostics, expressions, signals, result)
        values = pipeline.run(values, spec.transform, context)
      }
      result[spec.name] = values
    }
    return result
  }

  /**
   * Applies `format.parse` to one row.
   *
   * JSON has no date type, so a specification has to say which fields hold one. Everything
   * downstream then works in epoch milliseconds, which is what makes a date an ordinary number to a
   * scale.
   */
  private fun parseFields(datum: VegaValue, spec: DataSpec): VegaValue {
    val obj = datum as? VegaValue.Obj ?: return datum
    val fields = LinkedHashMap(obj.fields)
    for ((field, kind) in spec.parse) {
      val raw = fields[field] ?: continue
      val converted =
        when (kind.lowercase()) {
          "date" -> DateValues.parse(raw)
          "number" -> VegaValue.Num(raw.asDouble())
          "string" -> VegaValue.Str(raw.asString())
          "boolean" -> VegaValue.Bool(JsSemantics.truthy(raw))
          else -> {
            diagnostics.warn(
              DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
              "Cannot read field '$field' as '$kind'; it was left as it came",
              operator = spec.name,
            )
            raw
          }
        }
      if (converted == null) {
        diagnostics.warn(
          DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
          "Could not read '${raw.asString()}' as a date in field '$field' of '${spec.name}'",
          operator = spec.name,
        )
      }
      fields[field] = converted ?: VegaValue.Null
    }
    return VegaValue.Obj(fields)
  }

  /**
   * Transform context for one dataset.
   *
   * Signals written by a transform go into the shared map, so a later dataset or signal definition
   * can read them.
   */
  private class TransformScope(
    override val diagnostics: DiagnosticCollector,
    override val expressions: ExpressionCompiler,
    private val signals: MutableMap<String, VegaValue>,
    private val datasets: Map<String, List<VegaValue>>,
  ) : TransformContext {

    override val scope: ExpressionScope = scopeFor(VegaValue.Null)

    override fun setSignal(name: String, value: VegaValue) {
      signals[name] = value
    }

    override fun scopeFor(datum: VegaValue): ExpressionScope = SignalScope(signals, datasets, datum)
  }
}
