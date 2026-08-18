package dev.aster.vegalite

import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue

/**
 * A facet written *above* the view rather than inside its encoding.
 *
 * `{"facet": {"row": …, "column": …}, "spec": …}` and `{"row": …, "column": …}` in an encoding are
 * the same chart, and upstream compiles them through the same `FacetModel`: the operator form is
 * only a different place to write the two channels. Compiled side by side the two specifications
 * come out byte for byte identical, which is what this rewrite relies on — the facet channels move
 * down into the view's encoding and everything else about the chart is already handled.
 *
 * The **wrapped** form, `{"facet": {"field": …}, "columns": n}`, is not the same chart and is not
 * rewritten here: one field laid into a grid is a layout of its own, and it is [FacetWrap].
 */
internal object FacetOperator {

  /**
   * A facet, and the facets **inside** it — the grid's own levels, outermost first.
   *
   * A grid whose every cell is itself a grid is two levels of cell group, not one crossed grid, and
   * the levels have to be kept apart from here: folded into one encoding they would both want the
   * `row` channel and a single lift would read them as one. So only the **outermost** level moves
   * down into the encoding, which is what leaves the one-level case exactly as it was; the rest are
   * handed back for the compiler to lift in turn.
   */
  class Peeled(val spec: VegaValue.Obj, val inner: List<VegaValue.Obj>)

  /**
   * The equivalent view with the outermost facet's channels in its encoding, or null where it
   * cannot be made.
   *
   * `columns` beside a `row`/`column` facet is reported and dropped, as upstream's
   * `columnsNotSupportByRowCol` does: the grid's width is the number of columns the facet has, so a
   * second answer to that question can only disagree with the first.
   */
  fun normalize(spec: VegaValue.Obj, diagnostics: DiagnosticCollector): Peeled? {
    if (!spec.has("facet")) return Peeled(spec, emptyList())
    // Every level down to the view that is actually drawn, and that view with what it inherited.
    val levels = mutableListOf<VegaValue.Obj>()
    var node = spec
    // What the levels above contribute, innermost writer winning: a nested grid inherits its data,
    // its size and its transforms from whichever level last stated them, exactly as one level does.
    val inherited = linkedMapOf<String, VegaValue>()
    while (true) {
      val facet = node.obj("facet") ?: break
      val template = node.obj("spec")
      if (template == null) {
        diagnostics.fatal(
          VegaLiteDiagnostics.UNSUPPORTED_COMPOSITION,
          "`facet` needs a `spec` to grid; there is nothing to draw without one.",
          jsonPath = "$.spec",
        )
        return null
      }
      if ((facet.has("row") || facet.has("column")) && node.has("columns")) {
        diagnostics.error(
          VegaLiteDiagnostics.UNSUPPORTED_COMPOSITION,
          "`columns` cannot be used when `facet` names `row` or `column`: the grid is as wide as " +
            "the column facet has values. It is ignored.",
          jsonPath = "$.columns",
        )
      }
      levels += facet
      node.fields.forEach { (key, value) ->
        // `columns` belongs to a *wrapped* facet, which is the only form that has anything to wrap,
        // and never travels down past the level that wrote it.
        if (key != "facet" && key != "spec" && key != "columns") inherited[key] = value
      }
      node = template
    }
    val leaf = node
    val outermost = levels.first()
    val mapping = outermost.has("row") || outermost.has("column")

    return Peeled(
      obj {
        inherited.forEach { (key, value) -> if (key != "encoding") put(key, value) }
        // `columns` survives a **wrapped** facet, which is the only form that has anything to wrap,
        // and only the outermost level's — the grid it lays out is that level's.
        if (!mapping) spec.fields["columns"]?.let { put("columns", it) }
        // The view's own properties win, as a child's do everywhere in this hierarchy — its `data`,
        // its size, its transforms — and only then does the facet arrive in the encoding. A nested
        // grid's own `facet` and `spec` are not among them: they are this chart's levels, taken out
        // above, and copying them down is what left a `facet` in the result to be refused by name.
        leaf.fields.forEach { (key, value) ->
          if (key != "encoding" && key != "facet" && key != "spec") put(key, value)
        }
        put(
          "encoding",
          obj {
            leaf.obj("encoding")?.let { putAll(it) }
            if (mapping) {
              // In the order the specification **wrote** them: `forEachFieldDef` walks a model's
              // encoding as it stands, and a grid that names its column before its row writes that
              // column's sort index first.
              outermost.fields.forEach { (channel, def) ->
                if (channel == "row" || channel == "column") put(channel, def)
              }
            } else {
              // A single field becomes the `facet` channel: one construct from here down.
              put("facet", outermost)
            }
          },
        )
      },
      levels.drop(1),
    )
  }
}
