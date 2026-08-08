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
 * The datasets visible at one point in a specification, and the tree each of them carries.
 *
 * A tree belongs to the **dataset** that built it, not to the pipeline run that built it. Vega's
 * own tree examples are written as two datasets — one that stratifies and lays out, and a second
 * that sources from it and turns it into links — and upstream connects them by hanging the tree off
 * the source array as `source.root`, which a dataset sourcing from it then reads. Keeping it per
 * pipeline instead left the second dataset with nothing, and `treelinks` reporting a missing tree
 * for a specification that plainly had one.
 *
 * Carried beside the rows rather than inside them because a tree is not data: nothing downstream of
 * a layout ever sees the structure, only the coordinates written back onto the rows.
 */
internal class ScopeData(
  val datasets: Map<String, List<VegaValue>>,
  val trees: Map<String, TreeSource> = emptyMap(),
) {
  /** A dataset bound from outside the resolver — a facet's own rows, which carry no tree. */
  fun withDataset(name: String, rows: List<VegaValue>): ScopeData =
    ScopeData(datasets + (name to rows), trees - name)

  companion object {
    val Empty: ScopeData = ScopeData(emptyMap())
  }
}

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
  /**
   * Signals the specification declares whose value is computed rather than written down.
   *
   * Signals resolve *after* the data, because a signal's `update` may read a dataset, so only the
   * plain constants can be seeded beforehand. A transform reaching for one of these therefore gets
   * nothing — and nothing is zero to arithmetic, which draws a chart rather than failing to. Naming
   * them is what turns that into a diagnostic; see [TransformScope].
   */
  private val deferredSignals: Set<String> = emptySet(),
) {

  /**
   * The address a `{"signal": ...}` url resolves to, or null with a diagnostic.
   *
   * An address that cannot be worked out is named rather than fetched blindly: an empty string
   * would be refused by the loader with a message about the wrong thing, and guessing would mean
   * this process fetching something nobody chose.
   */
  private fun urlFromSignal(
    spec: DataSpec,
    expression: String,
    signals: Map<String, VegaValue>,
  ): String? {
    val compiled = expressions.compile(expression)
    if (compiled !is dev.aster.vega.expression.ExpressionResult.Compiled) {
      diagnostics.error(
        DiagnosticCodes.EXPRESSION_PARSE_ERROR,
        "Dataset '${spec.name}' has a url expression that does not parse: '$expression'",
        operator = spec.name,
      )
      return null
    }
    val scope =
      object : ExpressionScope {
        override val datum: VegaValue = VegaValue.EmptyObject

        override fun signal(name: String): VegaValue = signals[name] ?: VegaValue.Null

        override fun dataset(name: String): List<VegaValue> = emptyList()
      }
    val resolved = runCatching {
      compiled.expression.evaluate(scope)
    }
      .getOrNull()
      ?.asString()
      ?.takeIf { it.isNotEmpty() }
    if (resolved == null) {
      diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "Dataset '${spec.name}' takes its url from '$expression', which resolved to nothing",
        operator = spec.name,
      )
    }
    return resolved
  }

  /**
   * Wraps a row that is not an object as `{"data": value}`, which is upstream's `ingest`.
   *
   * A dataset may be a bare array of numbers or strings — Vega's dot plot writes `"values":
   * [6.3, 2.1, ...]` — and every transform downstream reads *fields*, so there has to be a field to
   * read. Upstream names it `data`, which is why that example's `dotbin` says `"field": "data"`
   * over data that has no such column.
   *
   * Left alone otherwise, so an ordinary array of objects costs nothing.
   */
  private fun ingest(rows: List<VegaValue>): List<VegaValue> =
    if (rows.none { it !is VegaValue.Obj }) rows
    else
      rows.map { row ->
        if (row is VegaValue.Obj) row else VegaValue.Obj(linkedMapOf("data" to row))
      }

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
      is VegaValue.Arr -> ingest(rows.values)
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
    inherited: ScopeData = ScopeData.Empty,
  ): ScopeData {
    if (specs.isEmpty()) return inherited

    val result = LinkedHashMap(inherited.datasets)
    val trees = LinkedHashMap(inherited.trees)
    val pipeline = TransformPipeline()

    for (spec in specs) {
      var values = ingest(spec.values ?: emptyList())
      // A `url` may itself be a signal — how a control swaps the file a chart is reading. It is
      // resolved here rather than at parse time because only now are the signals known, and a
      // signal that cannot be worked out before the data is one this cannot use.
      val address = spec.url ?: spec.urlSignal?.let { urlFromSignal(spec, it, signals) }
      address?.let { values = loadUrl(spec, it) }
      // A dataset that sources from another starts with that one's tree as well as its rows, which
      // is what lets `treelinks` sit in a dataset of its own.
      var tree: TreeSource? = null
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
          tree = trees[spec.source]
        }
      }
      if (spec.parse.isNotEmpty()) values = values.map { parseFields(it, spec) }
      if (spec.transform.isNotEmpty()) {
        val context =
          TransformScope(
            diagnostics,
            expressions,
            signals,
            result,
            tree,
            deferredSignals,
            spec.name,
          )
        values = pipeline.run(values, spec.transform, context)
        tree = context.tree
      }
      result[spec.name] = values
      if (tree == null) trees.remove(spec.name) else trees[spec.name] = tree
    }
    return ScopeData(result, trees)
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
    /** Inherited from the dataset this one sources from, and null for a dataset of its own. */
    override var tree: TreeSource?,
    private val deferredSignals: Set<String>,
    private val dataset: String,
  ) : TransformContext {

    /** One diagnostic per signal per dataset; the expression runs once a row. */
    private val reported = mutableSetOf<String>()

    override val scope: ExpressionScope = scopeFor(VegaValue.Null)

    override fun setSignal(name: String, value: VegaValue) {
      signals[name] = value
    }

    override fun scopeFor(datum: VegaValue): ExpressionScope =
      DeferredSignalScope(SignalScope(signals, datasets, datum), ::reportDeferred)

    /**
     * A transform read a signal whose value is not known until after the data.
     *
     * The value comes back null, and null is zero to arithmetic — so the transform computes
     * something rather than failing, and the chart is drawn in the wrong place with nothing said.
     * Vega's own radial tree example is written exactly this way: `originX` has an `update`, and
     * every node's `x` is `originX + radius * cos(...)`, so the whole diagram collapsed onto the
     * origin. Reporting it is not the same as resolving it — the ordering that would resolve it
     * needs a real dataflow, which is a separate piece of work — but it is the difference between a
     * chart that is wrong and one that is wrong and says so.
     */
    private fun reportDeferred(name: String) {
      if (name in signals || name !in deferredSignals || !reported.add(name)) return
      diagnostics.warn(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "A transform on dataset '$dataset' read signal '$name', whose value is computed and so is " +
          "not known until after the data has been resolved; it read as null. Only a signal with " +
          "a plain 'value' is available to a transform",
        operator = dataset,
      )
    }
  }

  /** Delegates everything but the signal lookup, which it announces before answering. */
  private class DeferredSignalScope(
    private val inner: ExpressionScope,
    private val notify: (String) -> Unit,
  ) : ExpressionScope by inner {
    override fun signal(name: String): VegaValue {
      notify(name)
      return inner.signal(name)
    }
  }
}
