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
import dev.aster.vega.model.time.TimeParse
import dev.aster.vega.runtime.load.DataLoader
import dev.aster.vega.runtime.load.DenyLoader
import dev.aster.vega.runtime.load.LoadDeniedException
import dev.aster.vega.runtime.scale.VegaScale
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonPrimitive

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
  /**
   * What **local** time means while reading this specification's data; null is the device's zone.
   *
   * `format.parse` is where it bites: `{"parse": {"t": "date"}}` on `2026-05-20T00:30` has to pick
   * a zone, and `Date.parse` picks the local one. A `utc:` pattern is unaffected, as upstream's is.
   */
  private val timeZone: TimeZone? = null,
  /**
   * Tables the **host** supplies, by the name the specification gives the dataset.
   *
   * Upstream's `view.data(name, rows)`: a specification declares `{"name": "diary"}` with no
   * values, no url and no source, and whoever runs it fills that table. It is how a chart is drawn
   * from data the app already holds — a local store, a query, rows assembled from a device sensor —
   * rather than from data a payload carried.
   *
   * The rows are used exactly as inline `values` would be, before anything else looks at the
   * dataset, so `format.parse` and every transform run over them unchanged. That is the point: a
   * host should not have to reimplement a parse rule to get its own table through.
   */
  private val hostData: Map<String, List<VegaValue>>? = null,
) {

  /**
   * Which host tables were actually claimed by a dataset, so the rest can be reported.
   *
   * Mutable and read after the whole compile because datasets are not all in one place: a group
   * mark declares its own, and this resolver is shared with [ScopeCompiler] so that one is resolved
   * through the same path. A name nobody claimed is a chart drawn without the data it was given,
   * which is exactly the silence this engine refuses everywhere else.
   */
  internal val claimedHostData: MutableSet<String> = mutableSetOf()

  /** The zone a naive date in this specification's data is read in. */
  private val local: TimeZone
    get() = timeZone ?: TimeZone.currentSystemDefault()

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
   * The host's rows for this dataset, or null where it has none — with everything it displaced
   * said.
   *
   * Three refusals, and each is a diagnostic rather than a silence:
   * - a **derived** dataset, one with a `source`, is not an input. Filling it would mean discarding
   *   the transforms it exists for, and upstream's own `view.data` on one is undone by the next
   *   pulse. Reported and ignored, and marked claimed so it is not reported twice.
   * - a `url` the host has pre-empted is **not fetched**. Said out loud because it is a request
   *   that did not happen, which is the kind of thing somebody debugs for an hour.
   * - inline `values` a host replaced are noted at `info`: it is usually deliberate — a fixture in
   *   the payload, real rows from the app — and worth being able to see.
   */
  private fun suppliedRows(spec: DataSpec): List<VegaValue>? {
    val rows = hostData?.get(spec.name) ?: return null
    if (spec.sources.isNotEmpty()) {
      claimedHostData.add(spec.name)
      diagnostics.warn(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "Dataset '${spec.name}' derives from ${spec.sources.joinToString(", ")}, so the rows " +
          "supplied for it are ignored: filling a derived dataset would discard the transforms it " +
          "is there for. Supply the rows for the dataset it sources from instead.",
        operator = spec.name,
      )
      return null
    }
    claimedHostData.add(spec.name)
    if (spec.url != null || spec.urlSignal != null) {
      diagnostics.warn(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "Dataset '${spec.name}' was supplied by the host, so its `url` was not fetched.",
        operator = spec.name,
      )
    } else if (!spec.values.isNullOrEmpty() || spec.document != null) {
      diagnostics.info(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "Dataset '${spec.name}' was supplied by the host, replacing the values in the " +
          "specification.",
        operator = spec.name,
      )
    }
    // Through the same normalisation inline values get: a row that is not an object becomes one, so
    // a table of bare numbers reads as `datum.data` exactly as upstream's does.
    return ingest(rows)
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
      "csv" -> DelimitedText.parse(headed(text, spec, ','), ',')
      "tsv" -> DelimitedText.parse(headed(text, spec, '\t'), '\t')
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
          DelimitedText.parse(headed(text, spec, delimiter.first()), delimiter.first())
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
   * `format.header`, applied the way upstream applies it: as a header **row** prepended to the
   * text.
   *
   * Not as a list of names handed to the parser, and the difference is visible: upstream prepends a
   * *text* row and lets the parser read it back, so the names go through quoting on the way out and
   * unquoting on the way in.
   *
   * The quoting is **`JSON.stringify`**, not the delimited format's own — `vega-util`'s
   * `stringValue`, always quoted, with backslash escapes. The two are not interchangeable and this
   * file used to assume they were. A name is JSON-escaped and then read as delimited text, which
   * round-trips `a,b` but deliberately does not round-trip anything else: `a\b` comes back as
   * `a\\b`, a tab comes back as the two characters `\t`, and a name containing a quote breaks the
   * row so thoroughly that upstream yields no columns at all. Verified end to end against
   * `read(..., {header: [name]})`; matching it matters more than improving it, because a
   * specification that names its own header is relying on what upstream does with it.
   */
  private fun headed(text: String, spec: DataSpec, delimiter: Char): String {
    if (spec.header.isEmpty()) return text
    val row = spec.header.joinToString(delimiter.toString()) { stringValue(it) }
    return "$row\n$text"
  }

  /**
   * `vega-util`'s `stringValue`: the JSON form of the string.
   *
   * The line-separator replacements are upstream's, and so is their scope — JavaScript's
   * `String.replace` with a string pattern rewrites only the **first** occurrence, so a name with
   * two of them keeps the second. Reproduced rather than corrected.
   */
  private fun stringValue(value: String): String {
    val json = JsonPrimitive(value).toString()
    return json.replaceFirst("\u2028", "\\u2028").replaceFirst("\u2029", "\\u2029")
  }

  /**
   * A TopoJSON document, decoded into the features or the mesh a map mark draws.
   *
   * `format.feature` and `format.mesh` are alternatives and one of them is required — a TopoJSON
   * file holds several named objects and nothing in it says which one this dataset wants.
   */
  private fun readTopoJson(spec: DataSpec, text: String): List<VegaValue> =
    readTopoJson(spec, parseJson(spec, text) ?: return emptyList())

  private fun readTopoJson(spec: DataSpec, document: VegaValue): List<VegaValue> {
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

  /** Parses a document, reporting a syntax error rather than throwing out of the compile. */
  private fun parseJson(spec: DataSpec, text: String): VegaValue? =
    try {
      VegaJson.parse(text)
    } catch (failure: Exception) {
      diagnostics.error(
        DiagnosticCodes.PARSE_INVALID_JSON,
        "Dataset '${spec.name}' is not valid JSON: ${failure.message}",
        operator = spec.name,
      )
      null
    }

  private fun readJson(spec: DataSpec, text: String): List<VegaValue> =
    readJson(spec, parseJson(spec, text) ?: return emptyList())

  private fun readJson(spec: DataSpec, document: VegaValue): List<VegaValue> {
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
    /**
     * Rebuilds the projections from the signals as they stand, for a fit that a transform supplies.
     *
     * A `geojson` transform gathers a table of coordinates into a `FeatureCollection` and publishes
     * it, and a projection fitted to that signal cannot be built until it has run — which may be
     * one transform earlier in the *same* list. Upstream has no difficulty: each transform is its
     * own operator and the projection sits between them in topological order. Here the projections
     * are resolved once per dataset, so without this a `geopoint` two lines below a `geojson`
     * placed every row through the family's unfitted default.
     *
     * Null where there is nothing to rebuild from, which is every caller that has no projections.
     */
    refreshProjections: ((Map<String, VegaValue>) -> Map<String, ProjectionDefinition>)? = null,
  ): ScopeData {
    if (specs.isEmpty()) return inherited

    val result = LinkedHashMap(inherited.datasets)
    val trees = LinkedHashMap(inherited.trees)
    val pipeline = TransformPipeline()

    for (spec in specs) {
      // Inline values are usually an array of rows, but a specification may also write the whole
      // *document* there — a GeoJSON `FeatureCollection`, or a TopoJSON topology — and reach the
      // rows
      // through the same `format` it would use for a url. Upstream applies the format to inline
      // values and to a loaded file alike; reading only the array form dropped every such dataset
      // without a word, which for a map means every feature.
      // A table the host handed in for this name, which is upstream's `view.data(name, rows)`.
      // Taken **before** the url is looked at, so a dataset a host has filled is not also fetched:
      // that is one fewer request, and a specification's `url` is an address the specification
      // chose.
      val supplied = suppliedRows(spec)
      var values =
        supplied
          ?: spec.document?.let { document ->
            when (spec.formatType) {
              "topojson" -> readTopoJson(spec, document)
              else -> readJson(spec, document)
            }
          }
          ?: ingest(spec.values ?: emptyList())
      // A `url` may itself be a signal — how a control swaps the file a chart is reading. It is
      // resolved here rather than at parse time because only now are the signals known, and a
      // signal that cannot be worked out before the data is one this cannot use.
      if (supplied == null) {
        val address = spec.url ?: spec.urlSignal?.let { urlFromSignal(spec, it, signals) }
        address?.let { values = loadUrl(spec, it) }
      }
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
            refreshProjections,
            timeZone,
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
        if (date && DateValues.parse(value, local) == null) date = false
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
            else -> DateValues.parse(raw, local) ?: raw
          }
      }
      VegaValue.Obj(fields)
    }
  }

  /**
   * A date read with a **stated** format, upstream's `date:` and `utc:` parse types.
   *
   * The specifier is everything after the first colon, unquoted if it was quoted — upstream's
   * `split(/:(.+)?/, 2)` and its quote strip, both of which matter: a time pattern contains colons
   * of its own, and a specification written by a human usually quotes it.
   */
  private fun patternedDate(raw: VegaValue, kind: String): VegaValue? {
    val utc = kind.startsWith("utc:")
    var pattern = kind.substringAfter(':')
    if (pattern.length >= 2 && (pattern.first() == '\'' || pattern.first() == '"')) {
      if (pattern.last() == pattern.first()) pattern = pattern.substring(1, pattern.length - 1)
    }
    if (pattern.isEmpty()) return null
    val zone = if (utc) TimeZone.UTC else local
    val millis = TimeParse.parse(raw.asString(), pattern, zone, utc) ?: return null
    return VegaValue.Timestamp(millis)
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
        when {
          // `date:%d/%m/%Y` and `utc:%Y-%m-%d` — a column whose dates are written in a format
          // `Date.parse` cannot read, which is most of the world's. Upstream splits on the
          // **first**
          // colon so a pattern may contain one, strips a quoted pattern's quotes, and hands the
          // rest
          // to `timeParse` or `utcParse`. Without it a whole column stayed text: 406 rows of one
          // published example, whose year axis was drawn from strings.
          kind.startsWith("date:") || kind.startsWith("utc:") -> patternedDate(raw, kind)
          kind.equals("date", ignoreCase = true) -> DateValues.parse(raw, local)
          kind.equals("number", ignoreCase = true) -> VegaValue.Num(raw.asDouble())
          kind.equals("string", ignoreCase = true) -> VegaValue.Str(raw.asString())
          kind.equals("boolean", ignoreCase = true) -> VegaValue.Bool(JsSemantics.truthy(raw))
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
    projections: Map<String, ProjectionDefinition>,
    /** See [resolve]: rebuilds them when a transform has published something a fit reads. */
    private val refreshProjections:
      ((Map<String, VegaValue>) -> Map<String, ProjectionDefinition>)?,
    /** What `timeunit: "local"` means in this compile; null is the device's own zone. */
    override val timeZone: TimeZone?,
  ) : TransformContext {

    /**
     * The projections as they stand, rebuilt at most once per signal a transform writes.
     *
     * Rebuilt lazily rather than on the write: `scopeFor` is called once a row, and resolving every
     * projection per row of a formula would cost more than the fit it is there to notice.
     */
    private var currentProjections = projections
    private var projectionsStale = false

    private fun projectionsNow(): Map<String, ProjectionDefinition> {
      val refresh = refreshProjections
      if (projectionsStale && refresh != null) {
        currentProjections = refresh(signals)
        projectionsStale = false
      }
      return currentProjections
    }

    /** One diagnostic per signal per dataset; the expression runs once a row. */
    private val reported = mutableSetOf<String>()

    override val scope: ExpressionScope = scopeFor(VegaValue.Null)

    override fun setSignal(name: String, value: VegaValue) {
      signals[name] = value
      projectionsStale = true
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
          .withProjections(projectionsNow()),
        ::reportDeferred,
      )

    override fun projection(name: String): ProjectionDefinition? = projectionsNow()[name]

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
