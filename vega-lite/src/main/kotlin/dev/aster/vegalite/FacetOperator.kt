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
   * The equivalent view with the facet channels in its encoding, or null where it cannot be made.
   *
   * `columns` beside a `row`/`column` facet is reported and dropped, as upstream's
   * `columnsNotSupportByRowCol` does: the grid's width is the number of columns the facet has, so a
   * second answer to that question can only disagree with the first.
   */
  fun normalize(spec: VegaValue.Obj, diagnostics: DiagnosticCollector): VegaValue.Obj? {
    val facet = spec.obj("facet") ?: return spec
    val template = spec.obj("spec")
    if (template == null) {
      diagnostics.fatal(
        VegaLiteDiagnostics.UNSUPPORTED_COMPOSITION,
        "`facet` needs a `spec` to grid; there is nothing to draw without one.",
        jsonPath = "$.spec",
      )
      return null
    }
    val mapping = facet.has("row") || facet.has("column")
    if (mapping && spec.has("columns")) {
      diagnostics.error(
        VegaLiteDiagnostics.UNSUPPORTED_COMPOSITION,
        "`columns` cannot be used when `facet` names `row` or `column`: the grid is as wide as " +
          "the column facet has values. It is ignored.",
        jsonPath = "$.columns",
      )
    }

    return obj {
      spec.fields.forEach { (key, value) ->
        // `columns` survives a *wrapped* facet, which is the only form that has anything to wrap.
        if (key != "facet" && key != "spec" && !(mapping && key == "columns")) put(key, value)
      }
      // The view's own properties win, as a child's do everywhere in this hierarchy — its `data`,
      // its size, its transforms — and only then does the facet arrive in the encoding.
      template.fields.forEach { (key, value) -> if (key != "encoding") put(key, value) }
      put(
        "encoding",
        obj {
          template.obj("encoding")?.let { putAll(it) }
          if (mapping) {
            for (channel in listOf("row", "column")) facet.fields[channel]?.let { put(channel, it) }
          } else {
            // A single field becomes the `facet` channel: one construct from here down.
            put("facet", facet)
          }
        },
      )
    }
  }
}
