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
    name: String,
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
                copy(child as? VegaValue.Obj ?: return@map child, bound, name)
              }
            ),
          )
        else -> put(key, value)
      }
    }
    put("name", name)
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
