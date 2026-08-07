package dev.aster.vega.runtime.compile

import dev.aster.vega.dataflow.transform.TransformContext
import dev.aster.vega.dataflow.transform.TransformPipeline
import dev.aster.vega.dataflow.transform.TreeSource
import dev.aster.vega.expression.ExpressionCompiler
import dev.aster.vega.expression.ExpressionScope
import dev.aster.vega.expression.JsSemantics
import dev.aster.vega.model.DelimitedText
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.asString
import dev.aster.vega.model.spec.DataSpec
import dev.aster.vega.model.time.DateValues
import dev.aster.vega.runtime.load.DataLoader
import dev.aster.vega.runtime.load.DenyLoader
import dev.aster.vega.runtime.load.LoadDeniedException

/**
 * Resolves dataset definitions to plain value lists, running their transform pipelines.
 *
 * Separate from [SpecCompiler] because a group mark declares datasets of its own, and those resolve
 * the same way as the top-level ones except that they can also source from the enclosing scope.
 */
internal class DataResolver(
  private val diagnostics: DiagnosticCollector,
  private val expressions: ExpressionCompiler,
  /** Refuses everything unless the host opted in; see [DataLoader]. */
  private val loader: DataLoader = DenyLoader,
) {

  /**
   * Fetches and reads one dataset's `url`.
   *
   * A refusal and a failure are reported the same way — as an error naming the dataset — because
   * they have the same consequence for the chart, and telling them apart is what the message text
   * is for. Either way the dataset is empty rather than absent, so everything downstream reports
   * against real data rather than falling over.
   */
  private fun loadUrl(spec: DataSpec, url: String): List<VegaValue> {
    val text =
      try {
        loader.load(loader.sanitize(url))
      } catch (denied: LoadDeniedException) {
        diagnostics.error(
          DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
          "Dataset '${spec.name}' was not loaded. ${denied.message}",
          operator = spec.name,
        )
        return emptyList()
      } catch (failure: Exception) {
        diagnostics.error(
          DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
          "Dataset '${spec.name}' could not be loaded from '$url': ${failure.message}",
          operator = spec.name,
        )
        return emptyList()
      }

    val type = spec.formatType ?: inferFormat(url)
    return when (type) {
      "csv" -> DelimitedText.parse(text, ',')
      "tsv" -> DelimitedText.parse(text, '\t')
      "dsv" -> {
        val delimiter = spec.delimiter
        if (delimiter.isNullOrEmpty()) {
          diagnostics.error(
            DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
            "Dataset '${spec.name}' is 'dsv' but names no 'format.delimiter'",
            operator = spec.name,
          )
          emptyList()
        } else {
          DelimitedText.parse(text, delimiter.first())
        }
      }
      "json" -> readJson(spec, text)
      else -> {
        // Reported by the parser already; nothing further to add here.
        emptyList()
      }
    }
  }

  private fun readJson(spec: DataSpec, text: String): List<VegaValue> {
    val document =
      try {
        VegaJson.parse(text)
      } catch (failure: Exception) {
        diagnostics.error(
          DiagnosticCodes.PARSE_INVALID_JSON,
          "Dataset '${spec.name}' is not valid JSON: ${failure.message}",
          operator = spec.name,
        )
        return emptyList()
      }
    // `format.property` names the field inside the document that holds the rows, which is how a
    // specification reaches into an API response rather than a bare array.
    val rows = spec.property?.let { (document as? VegaValue.Obj)?.fields?.get(it) } ?: document
    return when (rows) {
      is VegaValue.Arr -> rows.values
      // A single object is one row; upstream accepts that and several examples rely on it.
      is VegaValue.Obj -> listOf(rows)
      else -> {
        diagnostics.error(
          DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
          "Dataset '${spec.name}' did not contain an array of rows" +
            (spec.property?.let { " at property '$it'" } ?: ""),
          operator = spec.name,
        )
        emptyList()
      }
    }
  }

  /** Upstream infers the format from the file extension when `format.type` is absent. */
  private fun inferFormat(url: String): String {
    val path = url.substringBefore('?').substringBefore('#')
    return when {
      path.endsWith(".csv", ignoreCase = true) -> "csv"
      path.endsWith(".tsv", ignoreCase = true) -> "tsv"
      else -> "json"
    }
  }

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
      spec.url?.let { values = loadUrl(spec, it) }
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
    override var tree: TreeSource? = null

    override val scope: ExpressionScope = scopeFor(VegaValue.Null)

    override fun setSignal(name: String, value: VegaValue) {
      signals[name] = value
    }

    override fun scopeFor(datum: VegaValue): ExpressionScope = SignalScope(signals, datasets, datum)
  }
}
