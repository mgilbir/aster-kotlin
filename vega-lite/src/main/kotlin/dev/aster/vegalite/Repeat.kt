package dev.aster.vegalite

import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue

/**
 * One template drawn once per column it is given.
 *
 * `repeat` is not a compiler construct at all: upstream normalizes it away before anything is
 * compiled (`CoreNormalizer.mapNonLayerRepeat`), into a concatenation of the same view with
 * `{"repeat": …}` replaced by a real column name in each copy. So this is a rewrite and nothing
 * more, which is also why it costs so little now that a concatenation compiles.
 *
 * The three forms differ only in what they cross:
 * - a **list** repeats over one variable, `{"repeat": "repeat"}`, and lays the copies out under
 *   whatever `columns` says;
 * - **`row`/`column`** cross two lists into a grid, addressed as `{"repeat": "row"}` and
 *   `{"repeat": "column"}`, and the grid's width is the number of columns rather than `columns`,
 *   which upstream reports as unsupported there;
 * - **`layer`** stacks the copies in one plot instead of placing them side by side.
 *
 * Each copy is *named* — `child__b`, or `child__row_a_column_b` — and that name replaces the
 * `concat_0` a concatenation would otherwise give it, which is why a repeated chart's scales read
 * `child__b_x`. The grid takes `align: "all"` rather than a concatenation's `each`: the copies are
 * one view drawn several times, so their rows do line up.
 */
internal object Repeat {

  /**
   * How many cells one `repeat` may lay out.
   *
   * Two hundred and fifty-six is a 16×16 grid, which is already past what anyone reads; the number
   * exists because the cost is a *whole compiled view* per cell and nothing else was bounding it.
   */
  const val MAX_REPEAT_CELLS: Long = 256

  /** The concatenation (or layer) this repetition is, or null where the spec is not one. */
  fun normalize(spec: VegaValue.Obj, diagnostics: DiagnosticCollector): VegaValue.Obj? {
    val repeat = spec.fields["repeat"] ?: return null
    val template = spec.obj("spec")
    if (template == null) {
      diagnostics.fatal(
        VegaLiteDiagnostics.UNSUPPORTED_COMPOSITION,
        "`repeat` needs a `spec` to repeat; there is nothing to draw without one.",
        jsonPath = "$.spec",
      )
      return null
    }

    val rest = spec.fields.filterKeys { it != "repeat" && it != "spec" && it != "data" }
    val layer = (repeat as? VegaValue.Obj)?.strings("layer")
    val rows = (repeat as? VegaValue.Obj)?.strings("row")
    val columns = (repeat as? VegaValue.Obj)?.strings("column")
    val values = (repeat as? VegaValue.Arr)?.values?.mapNotNull { (it as? VegaValue.Str)?.value }

    if (repeat !is VegaValue.Arr && layer == null && rows == null && columns == null) {
      diagnostics.fatal(
        VegaLiteDiagnostics.UNSUPPORTED_COMPOSITION,
        "`repeat` must be a list of column names or an object naming `row`, `column` or `layer`.",
        jsonPath = "$.repeat",
      )
      return null
    }

    // A layer repetition draws the copies over one another rather than beside one another, so it
    // stays a layer and never becomes a concatenation.
    if (layer != null && rows == null && columns == null) {
      return obj {
        put("data", template.fields["data"] ?: spec.fields["data"])
        rest.forEach { (key, value) -> put(key, value) }
        put(
          "layer",
          arr(
            layer.map { value ->
              copy(template, mapOf("layer" to value), "child__layer_${Fields.varName(value)}")
            }
          ),
        )
      }
    }

    // A repeat grid is a **cross product**, and each cell is a fully compiled copy of the template:
    // its own scales, its own axes, its own datasets. `{"row": [10 fields], "column": [10 fields]}`
    // is a hundred of those, which is a real chart; a hundred by a hundred is ten thousand, which
    // is not a chart at all but does compile, slowly, until it does not. Reported rather than
    // truncated, because half a grid is a wrong chart where no grid is a clear one.
    val cells =
      (values?.size ?: 1).toLong() * (rows?.size ?: 1).toLong() * (columns?.size ?: 1).toLong()
    if (cells > MAX_REPEAT_CELLS) {
      diagnostics.fatal(
        VegaLiteDiagnostics.LIMIT_EXCEEDED,
        "This `repeat` asks for $cells cells, and each is a whole compiled view. The limit is " +
          "$MAX_REPEAT_CELLS. A grid this size is not readable at any size a screen has, so the " +
          "shape to reach for is a smaller repeat or a `facet` over one field.",
        jsonPath = "$.repeat",
      )
      return null
    }

    val children = mutableListOf<VegaValue>()
    for (value in values ?: listOf(null)) {
      for (row in rows ?: listOf(null)) {
        for (column in columns ?: listOf(null)) {
          val bound =
            buildMap<String, String> {
              value?.let { put("repeat", it) }
              row?.let { put("row", it) }
              column?.let { put("column", it) }
              layer?.firstOrNull()?.let { put("layer", it) }
            }
          val name =
            if (values != null) "child__${Fields.varName(value!!)}"
            else
              "child__" +
                (row?.let { "row_${Fields.varName(it)}" } ?: "") +
                (column?.let { "column_${Fields.varName(it)}" } ?: "")
          children += copy(template, bound, name)
        }
      }
    }

    return obj {
      // The child's own data wins over the chart's, then the copies are stripped of it: a repeated
      // view is one dataset drawn several ways, and the concatenation holds it once.
      put("data", template.fields["data"] ?: spec.fields["data"])
      put("align", "all")
      rest.forEach { (key, value) -> if (key != "columns") put(key, value) }
      put(
        "columns",
        when {
          values != null -> spec.fields["columns"]
          columns != null -> num(columns.size.toDouble())
          else -> num(1.0)
        },
      )
      put("concat", arr(children))
    }
  }

  /**
   * One copy of the template, with every `{"repeat": …}` reference resolved and a name of its own.
   */
  private fun copy(
    template: VegaValue.Obj,
    bound: Map<String, String>,
    /**
     * The name of the copy — the **outermost** spec only.
     *
     * A copy that is itself a layer keeps its own numbering inside: upstream names the copy
     * `child__layer_AAPL` and its members `child__layer_AAPL_layer_0` and `…_layer_1`. Naming every
     * member after the copy gave a chart's halo and its line the same name, which is one mark's
     * name for two marks.
     */
    name: String?,
  ): VegaValue.Obj = obj {
    template.fields.forEach { (key, value) ->
      when (key) {
        // The copies do not carry the data; the concatenation above them does.
        "data" -> {}
        "encoding" -> put("encoding", resolve(value as? VegaValue.Obj ?: return@forEach, bound))
        "layer" ->
          put(
            "layer",
            arr(
              (value as? VegaValue.Arr)?.values.orEmpty().map { child ->
                copy(child as? VegaValue.Obj ?: return@map child, bound, name = null)
              }
            ),
          )
        else -> put(key, value)
      }
    }
    name?.let { put("name", it) }
  }

  /** `replaceRepeaterInMapping`: every channel of an encoding, resolved one at a time. */
  private fun resolve(encoding: VegaValue.Obj, bound: Map<String, String>): VegaValue.Obj = obj {
    encoding.fields.forEach { (channel, def) ->
      put(channel, resolveChannel(def, bound))
    }
  }

  private fun resolveChannel(def: VegaValue, bound: Map<String, String>): VegaValue {
    val obj = def as? VegaValue.Obj ?: return def
    return obj {
      obj.fields.forEach { (key, value) ->
        when {
          // `{"field": {"repeat": "repeat"}}` and the `sort` beside it are the two places a
          // repetition variable can stand — `replaceRepeaterInFieldDef`.
          key == "field" || key == "datum" -> put(key, substitute(value, bound) ?: value)
          key == "sort" || key == "condition" -> put(key, resolveChannel(value, bound))
          else -> put(key, value)
        }
      }
    }
  }

  private fun substitute(value: VegaValue, bound: Map<String, String>): VegaValue? {
    val reference = (value as? VegaValue.Obj)?.string("repeat") ?: return null
    return bound[reference]?.let { str(it) }
  }

  private fun VegaValue.Obj.strings(key: String): List<String>? =
    (fields[key] as? VegaValue.Arr)?.values?.mapNotNull { (it as? VegaValue.Str)?.value }
}
