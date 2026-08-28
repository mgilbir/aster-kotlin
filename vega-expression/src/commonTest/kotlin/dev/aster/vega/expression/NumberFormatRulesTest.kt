package dev.aster.vega.expression

import dev.aster.vega.model.locale.DateName
import dev.aster.vega.model.locale.DateNameContext
import dev.aster.vega.model.locale.VegaFormatRules
import dev.aster.vega.model.locale.VegaLocale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A host's numbering system reaches a **number**, and the specifier still decides its shape.
 *
 * The other half of `VegaFormatRulesTest`, which covers dates. The engine writes digits with
 * `value.toString()`, so `١٢٣` was unreachable however a locale was filled in — the separators, the
 * grouping and the minus were all data and the digits themselves were not.
 *
 * The precedence is the same: a specification writing `",.2f"` gets grouping and two decimals,
 * whatever a host supplies, and what a host supplies is which digits write them. A rule cannot move
 * a separator or change a width, because the document decided both.
 */
class NumberFormatRulesTest {

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

  private val eastern = VegaLocale.EnglishUS.copy(rules = EasternArabicDigits)

  @Test
  fun `the digits are the host's and the shape is the specifier's`() {
    // Grouping and two decimals are what `,.2f` asked for, and they survive.
    assertEquals("١,٢٣٤.٥٧", NumberFormat.format(1234.567, ",.2f", eastern))
    // A width and a zero fill are the document's too: eight characters, padded with digits that are
    // then written in the host's system like any other.
    assertEquals("٠٠٠١٢.٣٠", NumberFormat.format(12.3, "08.2f", eastern))
    // Text that is not a digit is left alone — a percent, a currency, an SI prefix.
    assertEquals("٥٠%", NumberFormat.format(0.5, ".0%", eastern))
    assertEquals("$١.٢٣", NumberFormat.format(1.23456, "$.2f", eastern))
  }

  @Test
  fun `a locale with no rules is what it always was`() {
    assertEquals("1,234.57", NumberFormat.format(1234.567, ",.2f", VegaLocale.EnglishUS))
    // The separators stay the locale's data, which is where they belong: a rule about digits has no
    // opinion about them.
    val comma = VegaLocale.EnglishUS.copy(decimal = ",", thousands = ".")
    assertEquals("1.234,57", NumberFormat.format(1234.567, ",.2f", comma))
    assertEquals(
      "١.٢٣٤,٥٧",
      NumberFormat.format(1234.567, ",.2f", comma.copy(rules = EasternArabicDigits)),
    )
  }
}
