package dev.aster.vegalite

import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.model.VegaDiagnostic
import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.locale.VegaLocale
import kotlinx.datetime.TimeZone

/**
 * A specification a host was handed, and the Vega it turned out to be.
 *
 * [vegaJson] is what to give the runtime. It is the input unchanged when the input was already
 * Vega, and the compiled result when it was Vega-Lite; `null` only when Vega-Lite compilation
 * produced nothing at all.
 */
public data class VegaLiteConversion(
  val vegaJson: String?,
  /** True when the input was recognised as Vega-Lite and compiled. */
  val wasVegaLite: Boolean,
  /** What the *Vega-Lite* compilation reported. Empty when the input was already Vega. */
  val diagnostics: List<VegaDiagnostic>,
)

/**
 * Accepts either grammar and hands back Vega.
 *
 * A host that shows a chart from text a user supplied cannot ask which grammar the text is in — the
 * user pasted a chart, not a dialect — so this decides, and says which it decided. The decision is
 * made the way the Vega ecosystem's own embed layer makes it: the `$schema` if there is one, and
 * otherwise the shape of the specification, since only Vega-Lite has `mark` and `encoding` at the
 * top level and only Vega has `marks`.
 *
 * It is deliberately not a guess dressed up as a fact: [VegaLiteConversion.wasVegaLite] says which
 * way it went, so a host can put that in front of a reader alongside whatever the compilation
 * reported.
 */
public object VegaLiteInput {

  /**
   * @param hostConfig a `config` block the host supplies, which the specification's own beats key
   *   by key. Only meaningful for a Vega-Lite specification; a Vega one passes through untouched
   *   here and the host's configuration reaches it through `SpecCompiler` instead.
   */
  public fun toVega(
    json: String,
    hostConfig: VegaValue? = null,
    /**
     * What **local** time means, or null for the device's own zone; see `SpecCompiler.timeZone`.
     *
     * Passed here as well as to the compiler that draws the result, because one thing is settled on
     * this side: a selection whose `init` is a written date becomes a millisecond during
     * compilation. A host that supplies a zone to one and not the other gets a brush on a different
     * clock from its own axis.
     */
    timeZone: TimeZone? = null,
    /**
     * The host's language, for the one thing the Vega-Lite compiler **writes** rather than
     * resolves.
     *
     * Passed here as well as to the runtime, for the same reason [timeZone] is. A month name comes
     * from the runtime's locale, so `%b` is enough and always was; the *pattern* a bucketed axis is
     * formatted with is written on this side, and the order of a date's fields is a property of a
     * language. A host that supplies a locale to one and not the other gets an axis whose field
     * order and whose month names come from different places.
     */
    locale: VegaLocale = VegaLocale.EnglishUS,
  ): VegaLiteConversion {
    val diagnostics = DiagnosticCollector()
    // Guarded here too, and not only in the compiler below: this reads the text itself, and JSON
    // deep enough to overflow the parser would have taken the host down before the compiler — which
    // has the catch-all — ever saw it. See `VegaLiteCompiler.guarded` for what is rethrown.
    val parsed =
      try {
        VegaJson.parseOrNull(json, diagnostics)
      } catch (cancellation: kotlin.coroutines.cancellation.CancellationException) {
        throw cancellation
      } catch (failure: Exception) {
        return VegaLiteConversion(
          vegaJson = null,
          wasVegaLite = false,
          diagnostics =
            listOf(
              VegaDiagnostic(
                severity = DiagnosticSeverity.FATAL,
                code = VegaLiteDiagnostics.COMPILE_FAILED,
                message =
                  "This text could not be read as JSON: ${failure::class.simpleName}: " +
                    "${failure.message}.",
                cause = failure,
              )
            ),
        )
      }
    // Not JSON at all: hand the text on unchanged and let the Vega parser produce the one
    // diagnostic a reader needs, rather than producing a second one that says the same thing.
    if (parsed !is VegaValue.Obj) return VegaLiteConversion(json, false, emptyList())

    if (!isVegaLite(parsed)) return VegaLiteConversion(json, false, emptyList())

    val compiled = VegaLiteCompiler(hostConfig, timeZone, locale).compile(parsed)
    return VegaLiteConversion(compiled.toJson(), true, compiled.diagnostics)
  }

  /** Whether a parsed specification is Vega-Lite rather than Vega. */
  public fun isVegaLite(spec: VegaValue): Boolean {
    if (spec !is VegaValue.Obj) return false
    val schema = (spec.fields["\$schema"] as? VegaValue.Str)?.value
    if (schema != null) return schema.contains("vega-lite")
    // No schema: go by shape. Vega's marks are a list under `marks`; Vega-Lite's single mark is
    // under `mark`, and its compositions name themselves.
    if (spec.fields.containsKey("marks")) return false
    return VEGA_LITE_ONLY.any { spec.fields.containsKey(it) }
  }

  private val VEGA_LITE_ONLY =
    listOf("mark", "encoding", "layer", "facet", "hconcat", "vconcat", "concat", "repeat")
}
