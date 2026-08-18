package dev.aster.vega.model.locale

/**
 * Everything language-dependent that the engine **generates**, supplied by the host.
 *
 * Not a `Locale` and not a language tag: Kotlin Multiplatform common code cannot reach a platform's
 * date or number formatting, and `kotlinx-datetime` offers the substitution mechanism and no names
 * — its `MonthNames` companion has `ENGLISH_FULL` and `ENGLISH_ABBREVIATED` and nothing else. So
 * the names have to come from *outside* the engine, and this is the shape they arrive in: a data
 * holder given to a compiler beside its text engine, which is this project's established seam for
 * "the host knows this and the engine does not".
 *
 * The fields are **d3's**, field for field, because that is the vocabulary the specifications this
 * engine reads were written against: the first six are `d3-time-format`'s locale definition and the
 * next four are `d3-format`'s. A host that already has a d3 locale JSON — and there is one for
 * every language d3 ships — can copy it across without deciding anything.
 *
 * ```kotlin
 * val dutch = VegaLocale(
 *   months = listOf("januari", "februari", …),
 *   shortMonths = listOf("jan", "feb", …),
 *   days = listOf("zondag", "maandag", …),
 *   shortDays = listOf("zo", "ma", …),
 *   periods = listOf("AM", "PM"),
 *   date = "%d-%m-%Y",
 *   time = "%H:%M:%S",
 *   decimal = ",",
 *   thousands = ".",
 *   captions = DutchCaptions,
 * )
 * SpecCompiler(textEngine, locale = dutch)
 * ```
 *
 * **What this does not touch is parsing.** `TimeParse` reads a month name out of a specification's
 * own data with `TimeFormat.MONTHS`, and d3's parsing is part of the wire format: a specification
 * that writes `"Jan 5 2026"` means January whatever language the chart is drawn in. Rendering
 * localises; parsing does not. The two lists are deliberately kept apart for that reason, and a
 * locale replacing the parsing names globally is the one mistake this seam is shaped to prevent.
 */
public data class VegaLocale(
  /** `%B` — full month names, January first. */
  public val months: List<String>,
  /** `%b` — abbreviated month names, in the same order. */
  public val shortMonths: List<String>,
  /** `%A` — full weekday names, **Sunday first**, which is the week d3 labels against. */
  public val days: List<String>,
  /** `%a` — abbreviated weekday names, in the same order. */
  public val shortDays: List<String>,
  /** `%p` — the two half-day markers, morning first. */
  public val periods: List<String>,
  /** `%x` — the date order this language writes, as a pattern of directives. */
  public val date: String = "%-m/%-d/%Y",
  /** `%X` — the time of day, as a pattern. */
  public val time: String = "%-I:%M:%S %p",
  /** `%c` — date and time together, as a pattern. */
  public val dateTime: String = "%x, %X",
  /** What separates a whole number from its fraction. */
  public val decimal: String = ".",
  /**
   * What separates groups of digits, or the empty string for a language that groups with nothing.
   */
  public val thousands: String = ",",
  /**
   * How many digits are in each group, from the decimal point outwards, cycling on the last entry.
   *
   * `[3]` is most of the world. `[3, 2]` is the Indian system, where a lakh is written `1,00,000`.
   */
  public val grouping: List<Int> = listOf(3),
  /**
   * The sign a negative number is written with.
   *
   * d3 uses U+2212, the typographic minus, which is a different glyph from the hyphen an exponent
   * takes — and a screen reader says the two differently.
   */
  public val minus: String = "−",
  /** The sentences a screen reader is given; see [VegaCaptions]. */
  public val captions: VegaCaptions = VegaCaptions.English,
) {
  init {
    require(months.size == 12) { "months must have 12 entries, had ${months.size}" }
    require(shortMonths.size == 12) { "shortMonths must have 12 entries, had ${shortMonths.size}" }
    require(days.size == 7) { "days must have 7 entries, had ${days.size}" }
    require(shortDays.size == 7) { "shortDays must have 7 entries, had ${shortDays.size}" }
    require(periods.size == 2) { "periods must be AM and PM, had ${periods.size} entries" }
    require(grouping.isNotEmpty() && grouping.all { it > 0 }) {
      "grouping must be positive sizes, was $grouping"
    }
  }

  public companion object {
    /**
     * d3's own `en-US` default, which is what upstream produces when nothing says otherwise.
     *
     * The default everywhere in this engine, so a chart drawn without a locale is byte-for-byte
     * what it was before locales existed — which is what keeps the differential fixtures
     * meaningful.
     */
    public val EnglishUS: VegaLocale =
      VegaLocale(
        months =
          listOf(
            "January",
            "February",
            "March",
            "April",
            "May",
            "June",
            "July",
            "August",
            "September",
            "October",
            "November",
            "December",
          ),
        shortMonths =
          listOf(
            "Jan",
            "Feb",
            "Mar",
            "Apr",
            "May",
            "Jun",
            "Jul",
            "Aug",
            "Sep",
            "Oct",
            "Nov",
            "Dec",
          ),
        days = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"),
        shortDays = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"),
        periods = listOf("AM", "PM"),
      )
  }
}
