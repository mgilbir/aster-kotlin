package dev.aster.vega.expression

import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `String(date)`: ECMA-262's form, minus the one part that needs CLDR.
 *
 * `SUPPORTED_FEATURES.md` files this as a `Known difference`, and the difference is exactly one
 * suffix. ECMA-262 21.4.4.41 specifies `www mmm dd yyyy hh:mm:ss GMT±hhmm` and then **permits** an
 * implementation to append a parenthesised zone name — `(Central European Standard Time)` — which
 * V8 does. Producing that name needs CLDR data that is not available on every Kotlin/Native target,
 * so it is omitted, and everything before it agrees character for character.
 *
 * The claim is therefore narrow and checkable in both directions: the specified part is produced,
 * and the optional part is not. If the zone name is ever added the second half goes red, which is
 * when the row stops being a known difference.
 */
class DateToStringLimitTest {

  private val nothing =
    object : ExpressionScope {
      override val datum: VegaValue = VegaValue.Null

      override fun signal(name: String): VegaValue = VegaValue.Null

      override fun dataset(name: String): List<VegaValue> = emptyList()
    }

  private fun evaluate(source: String): String {
    val compiled = VegaExpressionCompiler().compile(source)
    check(compiled is ExpressionResult.Compiled) { "did not compile: $source" }
    return (compiled.expression.evaluate(nothing) as VegaValue.Str).value
  }

  /** The epoch, and a date in the other half of the year, so a DST offset is exercised too. */
  private val samples = listOf("datetime(0) + ''", "datetime(2026, 6, 15, 12, 30, 0) + ''")

  @Test
  fun `the specified part is produced in full`() {
    for (source in samples) {
      val printed = evaluate(source)
      // `www mmm dd yyyy hh:mm:ss GMT±hhmm`, English, fixed widths. Written as a shape rather than
      // as a literal because the offset is the *host's* zone, which is what a browser prints and
      // what makes this comparable at all.
      assertTrue(
        Regex("^[A-Z][a-z]{2} [A-Z][a-z]{2} \\d{2} \\d{4} " + "\\d{2}:\\d{2}:\\d{2} GMT[+-]\\d{4}")
          .containsMatchIn(printed),
        "`$source` gave `$printed`, which is not ECMA-262 21.4.4.41's form",
      )
    }
  }

  /**
   * And **not** the optional zone name, which is the difference this row records.
   *
   * Asserted on the whole string rather than on a suffix: a parenthesis anywhere in the output
   * would mean the name is being produced somewhere, and the row would need rewriting either way.
   */
  @Test
  fun `the optional parenthesised zone name is omitted`() {
    for (source in samples) {
      val printed = evaluate(source)
      assertTrue(
        "(" !in printed && ")" !in printed,
        "`$source` gave `$printed`, which carries a parenthesised zone name — the known difference " +
          "this row records has been closed, so the row needs to say so",
      )
    }
  }

  /**
   * The English names are fixed, not the host locale's.
   *
   * ECMA-262 specifies English weekday and month abbreviations whatever the platform's locale is,
   * so a device in French must still print `Thu Jan 01`. Without this the shape assertion above
   * would pass for `jeu. janv.` on a JVM with a French default locale, and the difference between
   * "we omit the zone name" and "we print a different language" is not one a row should blur.
   */
  @Test
  fun `the weekday and month are English whatever the platform locale is`() {
    val previous = java.util.Locale.getDefault()
    try {
      java.util.Locale.setDefault(java.util.Locale.FRANCE)
      val printed = evaluate("datetime(2026, 0, 1, 0, 0, 0) + ''")
      assertTrue(
        Regex("^(Mon|Tue|Wed|Thu|Fri|Sat|Sun) Jan ").containsMatchIn(printed),
        "under a French default locale `$printed` is not ECMA-262's English form",
      )
    } finally {
      java.util.Locale.setDefault(previous)
    }
  }

  /** An unreadable date prints `Invalid Date`, which is ECMA-262's own answer. */
  @Test
  fun `an invalid date prints the specified words`() {
    assertEquals("Invalid Date", evaluate("datetime(NaN) + ''"))
  }
}
