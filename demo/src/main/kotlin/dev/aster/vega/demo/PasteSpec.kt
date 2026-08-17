package dev.aster.vega.demo

import android.content.ClipboardManager
import android.content.Context
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.model.VegaDiagnostic
import dev.aster.vega.runtime.compile.CompiledSpec

/** The text on the clipboard, or `null` if there is nothing readable there. */
public fun clipboardText(context: Context): String? {
  val clipboard =
    context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
  val clip = clipboard.primaryClip ?: return null
  if (clip.itemCount == 0) return null
  return clip.getItemAt(0).coerceToText(context).toString().takeIf { it.isNotBlank() }
}

/**
 * What to tell someone about a specification they pasted.
 *
 * This is the one screen in the demo where the diagnostics are for a *user* rather than for us, and
 * it is worth getting the emphasis right. Three outcomes, and they are not the same:
 *
 * - **it did not compile** — nothing is drawn, and the previous chart is still on screen. Saying so
 *   matters, because otherwise the app looks like it ignored the paste.
 * - **it compiled, with things this engine cannot do** — a chart appears, and it is not the chart
 *   the specification asked for. This is the case the whole "nothing silently ignored" discipline
 *   exists for, and the only way a user learns which parts were dropped.
 * - **it compiled cleanly** — say how big it is, so there is some evidence it did anything.
 */
public data class PasteReport(val headline: String, val details: List<String>) {

  public companion object {

    public fun of(compiled: CompiledSpec): PasteReport {
      val errors = compiled.diagnostics.filter { it.severity >= DiagnosticSeverity.ERROR }
      val warnings = compiled.diagnostics.filter { it.severity < DiagnosticSeverity.ERROR }

      if (!compiled.isUsable) {
        return PasteReport(
          headline = "Did not compile — the previous chart is still shown.",
          details = compiled.diagnostics.map { it.readable() },
        )
      }

      val marks = compiled.scene?.nodeCount ?: 0
      // The counts are separate on purpose. An error means something the specification asked for is
      // missing from the picture; a warning means a property was ignored and the picture is very
      // nearly right. Rolling them into one number would flatten a distinction a reader needs.
      val headline =
        when {
          errors.isNotEmpty() ->
            "Rendered $marks nodes — but ${count(errors.size, "thing")} could not be drawn, " +
              "so this is not the chart the specification asked for" +
              if (warnings.isEmpty()) "."
              else
                ", and ${count(warnings.size, "property")} " +
                  "${if (warnings.size == 1) "was" else "were"} ignored."
          warnings.isNotEmpty() ->
            "Rendered $marks nodes. ${count(warnings.size, "property")} " +
              "${if (warnings.size == 1) "was" else "were"} ignored."
          else -> "Rendered $marks nodes with nothing unsupported."
        }
      // Errors first: a dropped mark matters more than an ignored corner radius.
      return PasteReport(headline, (errors + warnings).map { it.readable() })
    }

    /** "1 thing", "3 properties" — plural forms that read aloud, since TalkBack may. */
    private fun count(n: Int, noun: String): String =
      if (n == 1) "1 $noun"
      else "$n ${if (noun.endsWith("y")) noun.dropLast(1) + "ies" else noun + "s"}"

    /** A diagnostic as a line a non-contributor can act on: where, and what. */
    private fun VegaDiagnostic.readable(): String {
      val where = jsonPath ?: operator
      return if (where.isNullOrBlank()) message else "$where — $message"
    }
  }
}
