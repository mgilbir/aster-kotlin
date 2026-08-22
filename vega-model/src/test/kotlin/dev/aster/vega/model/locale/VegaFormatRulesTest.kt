package dev.aster.vega.model.locale

import dev.aster.vega.model.time.TimeFormat
import kotlinx.datetime.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The host's own **rules**, and the precedence they sit under.
 *
 * Everything else about a locale is data, and the two cases below are the ones data provably cannot
 * answer: a name whose form depends on the rest of the format, and a numbering system — the engine
 * writes `value.toString()`, which is ASCII always.
 *
 * The rule these tests are mostly about is the precedence. **A specification's format decides the
 * shape and a host decides the details inside it.** A document writing `%b %d, %Y` gets an
 * abbreviated month, a day and a four-digit year in that order whatever a host supplies, and what a
 * host supplies is what `%b` says and what a digit looks like. So the assertions come in pairs: the
 * detail changed, and the shape did not.
 */
class VegaFormatRulesTest {

  /** 21 May 2026, a Thursday, at 14:07. */
  private val at = LocalDateTime(2026, 5, 21, 14, 7, 9)

  private val polish =
    VegaLocale.EnglishUS.copy(
      months =
        listOf(
          "styczeń",
          "luty",
          "marzec",
          "kwiecień",
          "maj",
          "czerwiec",
          "lipiec",
          "sierpień",
          "wrzesień",
          "październik",
          "listopad",
          "grudzień",
        )
    )

  /**
   * Polish writes the month in the genitive beside a day number and the nominative alone.
   *
   * The case no arrangement of lists can hold, because the choice is a function of the *format*
   * rather than of the date: `21 maja` and `maj` are the same month.
   */
  private object PolishForms : VegaFormatRules {
    private val genitive =
      listOf(
        "stycznia",
        "lutego",
        "marca",
        "kwietnia",
        "maja",
        "czerwca",
        "lipca",
        "sierpnia",
        "września",
        "października",
        "listopada",
        "grudnia",
      )

    override fun name(
      field: DateName,
      index: Int,
      context: DateNameContext,
      locale: VegaLocale,
    ): String? =
      // `hasDayOfMonth` rather than a search of the pattern: the first draft of this rule tested
      // `pattern.contains("%d")` and was wrong, because the pattern was `%-d`.
      if (field == DateName.MONTH && context.hasDayOfMonth) genitive[index] else null

    override fun digits(number: String): String? = null
  }

  /** Arabic-Indic digits, which no `VegaLocale` field could reach. */
  private object EasternArabicDigits : VegaFormatRules {
    override fun name(
      field: DateName,
      index: Int,
      context: DateNameContext,
      locale: VegaLocale,
    ): String? = null

    override fun digits(number: String): String? =
      number.map { if (it in '0'..'9') ARABIC[it - '0'] else it }.joinToString("")

    private val ARABIC = listOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')
  }

  @Test
  fun `a name may depend on the rest of the format, which no list can express`() {
    val plain = polish
    val ruled = polish.copy(rules = PolishForms)

    // Without rules, one list has to serve both, so a day-and-month reads wrongly.
    assertEquals("21 maj", TimeFormat.format(at, "%-d %B", plain))
    assertEquals("maj", TimeFormat.format(at, "%B", plain))

    // With them, each reads as Polish writes it — from the same list index, asked rather than
    // tabulated.
    assertEquals("21 maja", TimeFormat.format(at, "%-d %B", ruled))
    assertEquals("maj", TimeFormat.format(at, "%B", ruled))
  }

  @Test
  fun `digits reach every number the engine writes, in a time format and in a number`() {
    val eastern = VegaLocale.EnglishUS.copy(rules = EasternArabicDigits)

    assertEquals("٢١/٠٥/٢٠٢٦", TimeFormat.format(at, "%d/%m/%Y", eastern))
    // Padding, the twelve-hour clock and the half-day marker all go through it or past it
    // correctly:
    // the digits move and `PM` is not a digit.
    assertEquals("٠٢:٠٧ PM", TimeFormat.format(at, "%I:%M %p", eastern))
    // A week number, an ISO year and an epoch are numbers the engine computed too.
    assertEquals("٢٠", TimeFormat.format(at, "%U", eastern))
  }

  @Test
  fun `a specification's format decides the shape, and a rule cannot change it`() {
    val eastern = VegaLocale.EnglishUS.copy(rules = EasternArabicDigits)

    // The order, the fields and the separators are the document's, and stay so.
    assertEquals("May ٢١, ٢٠٢٦", TimeFormat.format(at, "%b %d, %Y", eastern))
    // The **width** is the document's too: `%-d` asked for no padding and `%d` for two digits, and
    // a
    // rule that transliterates cannot make either of them something else.
    assertEquals("٢١", TimeFormat.format(at, "%-d", eastern))
    assertEquals("٩", TimeFormat.format(at, "%-S", eastern))
    assertEquals("٠٩", TimeFormat.format(at, "%S", eastern))

    // And literal text the document wrote is never passed to the rule: this `2026` was typed by
    // whoever wrote the specification rather than computed by the engine.
    assertEquals("Q٢ 2026", TimeFormat.format(at, "Q%q 2026", eastern))
  }

  @Test
  fun `a locale with no rules is byte-for-byte what it was`() {
    // The property the differential fixtures rest on: `EnglishUS` carries no rules, so nothing
    // about
    // this seam can move what upstream is compared against.
    assertEquals(null, VegaLocale.EnglishUS.rules)
    assertEquals(
      TimeFormat.format(at, "%b %d, %Y %I:%M %p"),
      TimeFormat.format(at, "%b %d, %Y %I:%M %p", VegaLocale.EnglishUS),
    )
    // And a rule that answers null for everything is the same as no rule at all.
    val abstaining =
      object : VegaFormatRules {
        override fun name(
          field: DateName,
          index: Int,
          context: DateNameContext,
          locale: VegaLocale,
        ): String? = null

        override fun digits(number: String): String? = null
      }
    assertEquals(
      TimeFormat.format(at, "%A %B %d %Y %p", VegaLocale.EnglishUS),
      TimeFormat.format(at, "%A %B %d %Y %p", VegaLocale.EnglishUS.copy(rules = abstaining)),
    )
  }

  @Test
  fun `the locale's own composed patterns go through the rules too`() {
    // `%x` renders the locale's `date`, so a rule sees the pattern actually being written — which
    // is
    // what lets a contextual name be right inside a composition as well as beside a literal `%d`.
    val ruled = polish.copy(date = "%-d %B %Y", rules = PolishForms)
    assertEquals("21 maja 2026", TimeFormat.format(at, "%x", ruled))
  }
}
