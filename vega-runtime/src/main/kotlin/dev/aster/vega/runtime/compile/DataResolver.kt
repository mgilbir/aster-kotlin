package dev.aster.vega.runtime.compile

import dev.aster.vega.dataflow.transform.ProjectionDefinition
import dev.aster.vega.dataflow.transform.TransformContext
import dev.aster.vega.dataflow.transform.TransformPipeline
import dev.aster.vega.dataflow.transform.TreeSource
import dev.aster.vega.expression.Clock
import dev.aster.vega.expression.ExpressionCompiler
import dev.aster.vega.expression.ExpressionScope
import dev.aster.vega.expression.JsSemantics
import dev.aster.vega.expression.RandomStream
import dev.aster.vega.model.DelimitedText
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.TopoJson
import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.asString
import dev.aster.vega.model.isMissing
import dev.aster.vega.model.spec.DataSpec
import dev.aster.vega.model.time.DateValues
import dev.aster.vega.runtime.load.DataLoader
import dev.aster.vega.runtime.load.DenyLoader
import dev.aster.vega.runtime.load.LoadDeniedException
import dev.aster.vega.runtime.scale.VegaScale

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
   * The chart's one random stream and its clock, shared with every other scope in the compile.
   *
   * A transform's expression is evaluated once per row, so a scope built fresh for each row with a
   * stream of its own hands every row the *same* first draw — twelve identical bars where upstream
   * has twelve different ones. The stream has to outlive the row.
   */
  private val random: RandomStream = RandomStream(),
  private val clock: Clock = Clock.Fixed,
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
      "topojson" -> readTopoJson(spec, text)
      else -> {
        // Reported by the parser already; nothing further to add here.
        emptyList()
      }
    }
  }

  /**
   * A TopoJSON document, decoded into the features or the mesh a map mark draws.
   *
   * `format.feature` and `format.mesh` are alternatives and one of them is required — a TopoJSON
   * file holds several named objects and nothing in it says which one this dataset wants.
   */
  private fun readTopoJson(spec: DataSpec, text: String): List<VegaValue> {
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
    val name = spec.feature ?: spec.mesh
    if (name == null) {
      diagnostics.error(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "Dataset '${spec.name}' is TopoJSON but names neither 'format.feature' nor " +
          "'format.mesh'; a TopoJSON file holds several objects and nothing says which",
        operator = spec.name,
      )
      return emptyList()
    }
    val filter =
      when (spec.meshFilter) {
        "interior" -> TopoJson.MeshFilter.INTERIOR
        "exterior" -> TopoJson.MeshFilter.EXTERIOR
        null -> TopoJson.MeshFilter.ALL
        else -> {
          diagnostics.error(
            DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
            "Dataset '${spec.name}' has 'format.filter: ${spec.meshFilter}'; " +
              "the only filters are 'interior' and 'exterior'",
            operator = spec.name,
          )
          TopoJson.MeshFilter.ALL
        }
      }
    val decoded =
      if (spec.feature != null) TopoJson.feature(document, name)
      else TopoJson.mesh(document, name, filter)
    if (decoded == null) {
      diagnostics.error(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "Dataset '${spec.name}' names TopoJSON object '$name', which the file does not contain",
        operator = spec.name,
      )
      return emptyList()
    }
    return ingest(decoded)
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
    /**
     * Signals this scope declares that have no value *yet*.
     *
     * A transform reaching for one gets nothing — and nothing is zero to arithmetic, which draws a
     * chart rather than failing to. Naming them is what turns that into a diagnostic; see
     * [TransformScope]. The set shrinks as a compile proceeds, because a dataset resolved late in
     * the dependency order can see signals an earlier one could not.
     */
    deferredSignals: Set<String> = emptySet(),
    /**
     * The scales built so far, for a transform parameter that reads one.
     *
     * `{"extent": {"signal": "domain('xscale')"}}` is how a density is computed over exactly the
     * span an axis will show, and it needs the scale to exist by the time the dataset runs — which
     * is what [DataflowOrder] arranges. Empty at a group scope, where the enclosing scales are
     * passed instead.
     */
    scales: Map<String, VegaScale> = emptyMap(),
    /** The scope's projections, which a `geoCentroid()` in a `formula` reaches for by name. */
    projections: Map<String, ProjectionDefinition> = emptyMap(),
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
      if (spec.sources.isNotEmpty()) {
        // Several sources concatenate, in the order written; one is just the short case of that.
        val rows = mutableListOf<VegaValue>()
        var found = false
        for (name in spec.sources) {
          val upstream = result[name]
          if (upstream == null) {
            diagnostics.error(
              DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
              "Dataset '${spec.name}' sources from unknown dataset '$name'",
              operator = spec.name,
            )
            continue
          }
          found = true
          rows.addAll(upstream)
          // A tree belongs to one dataset, so only a single source can pass one on; concatenating
          // two would leave the structure describing rows that are no longer all there.
          if (spec.sources.size == 1) tree = trees[name]
        }
        if (found) values = rows
      }
      if (spec.parseAuto) values = inferred(values)
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
            scales,
            trees,
            random,
            clock,
            projections,
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
   * `format.parse: "auto"` — each column read as the narrowest type that fits every one of its
   * values.
   *
   * Upstream tries boolean, then integer, then number, then date, in that order, and keeps the
   * first that holds throughout; a column where none does stays as it came. The order matters:
   * `"1"` is an integer before it is a number, and a column of `"true"`/`"false"` is boolean before
   * either.
   *
   * A missing value votes for nothing, so a column of numbers with a gap is still numeric.
   */
  private fun inferred(rows: List<VegaValue>): List<VegaValue> {
    val objects = rows.filterIsInstance<VegaValue.Obj>()
    if (objects.isEmpty()) return rows
    val columns = LinkedHashSet<String>().apply { objects.forEach { addAll(it.fields.keys) } }

    val kinds = LinkedHashMap<String, String>()
    for (column in columns) {
      var boolean = true
      var integer = true
      var number = true
      var date = true
      var seen = false
      for (row in objects) {
        val value = row.fields[column] ?: continue
        if (value.isMissing) continue
        seen = true
        val text = value.asString()
        if (boolean && !(text == "true" || text == "false")) boolean = false
        val numeric = value as? VegaValue.Num ?: text.toDoubleOrNull()?.let { VegaValue.Num(it) }
        if (number && numeric == null) number = false
        if (integer && (numeric == null || numeric.value != kotlin.math.floor(numeric.value))) {
          integer = false
        }
        if (date && DateValues.parse(value) == null) date = false
        if (!boolean && !integer && !number && !date) break
      }
      if (!seen) continue
      when {
        boolean -> kinds[column] = "boolean"
        integer -> kinds[column] = "number"
        number -> kinds[column] = "number"
        date -> kinds[column] = "date"
      }
    }
    if (kinds.isEmpty()) return rows

    return rows.map { row ->
      val obj = row as? VegaValue.Obj ?: return@map row
      val fields = LinkedHashMap(obj.fields)
      for ((column, kind) in kinds) {
        val raw = fields[column] ?: continue
        if (raw.isMissing) continue
        fields[column] =
          when (kind) {
            "boolean" -> VegaValue.Bool(JsSemantics.truthy(raw) && raw.asString() != "false")
            "number" -> VegaValue.Num(raw.asDouble())
            else -> DateValues.parse(raw) ?: raw
          }
      }
      VegaValue.Obj(fields)
    }
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
    private val scales: Map<String, VegaScale>,
    /** Every stratified dataset's hierarchy, for a `treePath` inside a transform expression. */
    private val trees: Map<String, TreeSource>,
    private val random: RandomStream,
    private val clock: Clock,
    /** The scope's projections, for a `geoshape` transform or a `geoCentroid()` expression. */
    private val projections: Map<String, ProjectionDefinition>,
  ) : TransformContext {

    /** One diagnostic per signal per dataset; the expression runs once a row. */
    private val reported = mutableSetOf<String>()

    override val scope: ExpressionScope = scopeFor(VegaValue.Null)

    override fun setSignal(name: String, value: VegaValue) {
      signals[name] = value
    }

    override fun scopeFor(datum: VegaValue): ExpressionScope =
      DeferredSignalScope(
        SignalScope(
            signals,
            datasets,
            datum,
            scales,
            diagnostics,
            trees = trees,
            random = random,
            clock = clock,
          )
          .withProjections(projections),
        ::reportDeferred,
      )

    override fun projection(name: String): ProjectionDefinition? = projections[name]

    /**
     * A transform read a signal whose value is not known until after the data.
     *
     * The value comes back null, and null is zero to arithmetic — so the transform computes
     * something rather than failing, and the chart is drawn in the wrong place with nothing said.
     * Vega's own radial tree example is written exactly this way: `originX` has an `update`, and
     * every node's `x` is `originX + radius * cos(...)`, so the whole diagram collapsed onto the
     * origin.
     *
     * What is left after [DataflowOrder] is the case it cannot see: a signal read through a
     * transform's *expression* parameter — `filter`'s `expr`, `formula`'s `expr` — rather than
     * through a `{"signal": "..."}` reference. Those are not in the graph, so a dataset carrying
     * one is not held back for the signal, and the report is still the only warning a reader gets.
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
