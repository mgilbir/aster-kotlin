package dev.aster.vegalite

import dev.aster.vega.model.locale.VegaLocale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A facet header follows the reader's language whether or not the field was bucketed.
 *
 * The two branches of `Facet.headerText` sat three lines apart and disagreed: one threaded the
 * locale, the other wrote upstream's `%b %d, %Y` outright. Upstream has no locale to thread, so the
 * hardcoding was defensible as parity and indefensible as a pair with the line above it.
 *
 * English is the case that cannot detect the change — it is the locale upstream's own table
 * describes — so the assertions that matter here are in another language, and the English one is
 * present precisely to pin that nothing moved.
 */
class FacetHeaderLocaleTest {

  /** Day before month, with a suffix the English form does not have. */
  private val dutch =
    VegaLocale.EnglishUS.copy(date = "%d-%m-%Y", timeUnitSpecifierOverrides = null)

  @Test
  fun `English is unchanged, which is what keeps the fixtures still`() {
    assertEquals("\"%b %d, %Y\"", Fields.fullDateSpecifier(VegaLocale.EnglishUS))
  }

  @Test
  fun `the trailing space the table carries does not reach the literal`() {
    // Every entry in the table ends in a space, because `TimeUnits.specifier` concatenates the
    // pieces of a compound specifier and trims the result. A literal is concatenated with nothing,
    // so it has to trim here — and a specifier ending in a space would put one inside every header.
    for (locale in listOf(VegaLocale.EnglishUS, dutch)) {
      val specifier = Fields.fullDateSpecifier(locale)
      assertTrue(specifier.startsWith("\"") && specifier.endsWith("\""), specifier)
      val inner = specifier.removeSurrounding("\"")
      assertEquals(
        inner.trim(),
        inner,
        "a header specifier should carry no outer space: $specifier",
      )
    }
  }

  @Test
  fun `another language gets its own date, not upstream's`() {
    // Deliberately not pinned to a literal. *That* the plain branch reads the locale is this
    // change; *what* the locale's table then says is the separate question of whether Vega-Lite
    // derives from the field order or from the pattern itself — so pinning a string here would make
    // this test fail for a reason that has nothing to do with facet headers.
    //
    // The invariant either way: a language that writes its date differently gets a different
    // header, and it is the same answer the bucketed branch would have given.
    val theirs = Fields.fullDateSpecifier(dutch)
    assertNotEquals(
      Fields.fullDateSpecifier(VegaLocale.EnglishUS),
      theirs,
      "a locale writing day-before-month should not be captioned in American order",
    )
    assertTrue(theirs.contains("%d"), theirs)
    assertTrue(
      theirs.indexOf("%d") < theirs.indexOfAny(listOf("%m", "%b")),
      "day should lead: $theirs",
    )
  }

  @Test
  fun `the bucketed and plain branches agree on what a full date looks like`() {
    // The reason this reads from the same table rather than from a second constant: whatever a
    // bucketed year-month-date is labelled with, an unbucketed date must be labelled with too, or a
    // grid changes its caption format depending on whether somebody wrote a timeUnit.
    for (locale in listOf(VegaLocale.EnglishUS, dutch)) {
      val bucketed = Fields.timeUnitSpecifier("yearmonthdate", locale)
      val plain = Fields.fullDateSpecifier(locale).removeSurrounding("\"")
      assertTrue(
        bucketed.contains(plain),
        "the year-month-date entry $bucketed should carry the plain form $plain",
      )
    }
  }

  @Test
  fun `a host that removes the entry still gets a date`() {
    // `null` in the override map removes an entry rather than restoring a default, which is
    // upstream's behaviour. A removed year-month-date must not leave a header formatted with "".
    val stripped =
      VegaLocale.EnglishUS.copy(timeUnitSpecifierOverrides = mapOf("year-month-date" to null))
    assertEquals("\"%b %d, %Y\"", Fields.fullDateSpecifier(stripped))
  }
}
