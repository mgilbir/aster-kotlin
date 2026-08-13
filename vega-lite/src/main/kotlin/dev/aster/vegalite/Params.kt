package dev.aster.vegalite

import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue

/**
 * `params` — the named values a chart reads, and the controls that set them.
 *
 * A **variable** parameter is a Vega signal and almost nothing else: a value, optionally an `expr`
 * computing it from the others, and optionally a `bind` describing the input widget that sets it.
 * `assembleParameterSignals` in `compile/parse.ts` is the whole translation, and it is a
 * translation rather than a compilation because Vega already has the construct.
 *
 * A **selection** parameter — one carrying `select` — is not: it stands for a set of rows the
 * reader picked, and compiles into a pile of signals, datasets and event streams that this engine
 * has no interaction loop to drive. Those are reported by name, individually, so a specification
 * mixing the two kinds still gets the variables it declared.
 */
internal object Params {

  fun signals(spec: VegaValue.Obj, diagnostics: DiagnosticCollector): List<VegaValue> {
    val declared = spec.array("params") ?: return emptyList()
    val out = mutableListOf<VegaValue>()
    declared.forEachIndexed { index, entry ->
      val param = entry as? VegaValue.Obj ?: return@forEachIndexed
      val name = param.string("name")
      if (name == null) {
        diagnostics.error(
          VegaLiteDiagnostics.UNSUPPORTED_PARAMETER,
          "A parameter needs a `name` to be read by; this one is dropped.",
          jsonPath = "$.params[$index]",
        )
        return@forEachIndexed
      }
      // A **selection** is compiled by `Selection`, into a store, its signals and the tests that
      // read them. It is not a variable and has no `value` to publish here.
      if (param.has("select")) return@forEachIndexed
      out += obj {
        put("name", name)
        // `expr` and `value` are alternatives: one is computed from the other parameters and
        // recomputed when they change, the other is simply held.
        param.fields["expr"]?.let { put("update", it) }
        if (!param.has("expr")) put("value", param.fields["value"])
        param.fields["bind"]?.let { put("bind", it) }
      }
    }
    return out
  }
}
