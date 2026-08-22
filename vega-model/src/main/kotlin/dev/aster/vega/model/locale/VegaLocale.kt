package dev.aster.vega.model.locale

import dev.aster.vega.model.time.LocaleDatePattern

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
  /**
   * The table `timeUnitSpecifier` reads — the format a **bucketed** instant is labelled with — or
   * null to derive it from [date].
   *
   * Null is the default, and deriving is the point. `%b` has always resolved to this locale's month
   * abbreviation, so a Dutch chart's axis said `mei`; the *order* of the fields came from a table
   * with no locale in it — `%Y-%m-%d` in `TimeUnits`, `%b %d, %Y` in the two entries Vega-Lite
   * overrides — so it said `mei 21, 2026`. The right month name in the wrong order.
   *
   * [date] is d3's `%x`, "the date order this language writes", and that is exactly the missing
   * fact. So `year-month-date` becomes [date] itself and the shorter runs are [date] with a field
   * **dropped**, which is what keeps them the language's own: `%b %d, %Y` without its date is `%b
   * %Y` and not `%b, %Y`, because dropping a field takes one adjacent separator with it.
   *
   * Upstream has no lever for this — its `timeUnitSpecifier` takes no locale at all — so this is a
   * deliberate divergence rather than a port. [EnglishUS] therefore pins upstream's own table with
   * an empty map, and it is the locale the differential fixtures compare against; see
   * [timeUnitSpecifiers].
   *
   * Stating a map replaces derivation entirely for the keys it names, and it is keyed the way
   * `TimeUnits` keys them: a single unit, or a recognised run of units joined by hyphens, coarsest
   * first — `year`, `month`, `year-month`, `year-month-date`, `hours-minutes`. The trailing space
   * is load-bearing: the pieces are concatenated and the result trimmed. A value of **null
   * removes** an entry rather than restoring the default, which is upstream's own behaviour and the
   * only way to say "do not combine these two".
   *
   * A specification's own second argument to `timeUnitSpecifier(units, specifiers)` still wins over
   * all of this, because the document asked for it by name.
   *
   * See [timeTickFormatOverrides] for the other half — a `time` axis with no `timeUnit` on it.
   */
  public val timeUnitSpecifierOverrides: Map<String, String?>? = null,
  /**
   * The cascade a **plain** `time` axis labels its ticks with, or null to derive it from [date] and
   * [time].
   *
   * The other place a date's shape is decided, and a different table from
   * [timeUnitSpecifierOverrides]: a temporal axis with no `timeUnit` on it has no single
   * granularity, so each tick is labelled by the finest field that is not zero. That is d3's
   * `scale.tickFormat`, and the keys are d3's own names for its eight steps — `millisecond`,
   * `second`, `minute`, `hour`, `day`, `week`, `month`, `year` — carrying `.%L`, `:%S`, `%I:%M`,
   * `%I %p`, `%a %d`, `%b %d`, `%B` and `%Y` respectively. (`week` is the step a Sunday takes,
   * which is why it gets the month back where an ordinary day does not.)
   *
   * Two things are derived and the rest is d3's. The clock comes from [time]: a pattern written
   * with `%H` gets `%H:00` and `%H:%M` in place of `%I %p` and `%I:%M`, because `%I %p` is an
   * afternoon in a place that writes 14:00. And the month-and-day order comes from [date], so a
   * day-first language reads `21 mei` rather than `mei 21`.
   *
   * [EnglishUS] pins d3's cascade with an empty map, for the same reason it pins the table above.
   */
  public val timeTickFormatOverrides: Map<String, String>? = null,
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
  /**
   * The host's own **rules** for the pieces a format is made of, or null for this locale's tables.
   *
   * Everything else on this class is data, and data can only answer what somebody thought to
   * tabulate. Two things it provably cannot: a name whose form depends on the rest of the format —
   * Polish writes `stycznia` beside a day number and `styczeń` alone — and a numbering system,
   * since the engine writes `value.toString()` and that is ASCII always.
   *
   * The precedence is the important part. **A specification's format decides the shape and this
   * decides the details inside it**: a document writing `"format": "%b %d, %Y"` gets that order and
   * those fields whatever a host supplies, and what a host supplies is what `%b` says and what a
   * digit looks like. See [VegaFormatRules], which is also where the boundaries are written down.
   *
   * Null for [EnglishUS] and for any locale that does not ask, so a chart drawn without rules is
   * byte-for-byte what it was.
   */
  public val rules: VegaFormatRules? = null,
) {
  /**
   * The table `timeUnitSpecifier` reads: [timeUnitSpecifierOverrides] where stated, and otherwise
   * derived from [date].
   *
   * Three entries are derived, and each is [date] with fields dropped rather than a pattern
   * reassembled from parts — so a language that writes `%e de %B de %Y` gets `%B de %Y` for a month
   * within a year, prose and all, which no table of directives could have produced.
   *
   * Everything not named here falls back to `TimeUnits.SPECIFIERS`, which is upstream's own table:
   * an hour is `%H:00` there whatever the language, because a bucket of hours has no date order in
   * it to disagree about.
   */
  public val timeUnitSpecifiers: Map<String, String?> by lazy {
    timeUnitSpecifierOverrides ?: derivedTimeUnitSpecifiers()
  }

  /**
   * The cascade a plain `time` axis labels its ticks with: [timeTickFormatOverrides] where stated,
   * and otherwise derived from [date] and [time].
   */
  public val timeTickFormats: Map<String, String> by lazy {
    timeTickFormatOverrides ?: derivedTimeTickFormats()
  }

  private fun derivedTimeUnitSpecifiers(): Map<String, String?> {
    val derived = LinkedHashMap<String, String?>()
    // The trailing space is `TimeUnits`'s own convention: the pieces of a compound specifier are
    // concatenated and the result trimmed, so every entry that could be followed by another ends in
    // one.
    fun put(key: String, fields: Set<DateField>) {
      LocaleDatePattern.withFields(date, fields)?.let { derived[key] = "$it " }
    }
    put("year-month-date", setOf(DateField.YEAR, DateField.MONTH, DateField.DATE))
    put("year-month", setOf(DateField.YEAR, DateField.MONTH))
    put("month-date", setOf(DateField.MONTH, DateField.DATE))
    return derived
  }

  /**
   * The order this language writes year, month and day of month in, read off [date].
   *
   * The *order* rather than the pattern, and that distinction is load-bearing. Vega-Lite writes its
   * own specifier table with the month as a **name** — `%b %d, %Y` — and substituting a name into a
   * locale's numeric pattern gives `21-mei-2026`, because those separators were chosen for numbers.
   * So a caller that has its own directives takes the order from here and keeps its own spacing;
   * [timeUnitSpecifiers], whose entries *are* numeric, takes the whole pattern instead.
   *
   * Empty where [date] names none of the three, which is not a pattern an order can be read from.
   */
  public val dateFieldOrder: List<DateField> by lazy {
    LocaleDatePattern.fieldOrder(date).orEmpty()
  }

  private fun derivedTimeTickFormats(): Map<String, String> {
    val derived = LinkedHashMap<String, String>()
    // The clock. `%I %p` is an afternoon in a place that writes 14:00, and `time` is the pattern
    // that
    // says which this language is.
    if (LocaleDatePattern.twentyFourHour(time) == true) {
      derived["hour"] = "%H:00"
      derived["minute"] = "%H:%M"
    }
    // And the month-and-day order, so a day-first language reads `21 mei` rather than `mei 21`.
    // Only
    // where the pattern says otherwise than d3's cascade already does, so a locale that agrees with
    // it contributes nothing and the map stays empty.
    if (LocaleDatePattern.monthBeforeDate(date) == false) {
      derived["week"] = "%d %b"
      derived["day"] = "%d %a"
    }
    return derived
  }

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
     *
     * **Both time tables are pinned empty here on purpose, and it is the one place they are.** d3's
     * `en-US` writes `%x` as `%-m/%-d/%Y`, and upstream's `timeUnitSpecifier` table writes a full
     * date as `%Y-%m-%d` — upstream is internally inconsistent about the order, and deriving one
     * from the other would move every temporal label this engine is compared against. So this
     * locale answers exactly what upstream answers, and every other locale derives from its own
     * `%x`. `LocaleDefaultsTest` asserts that, and the fixture harnesses name this locale rather
     * than relying on a default parameter, because "the locale upstream's tests assume" is a claim
     * worth being able to read at the call site.
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
        timeUnitSpecifierOverrides = emptyMap(),
        timeTickFormatOverrides = emptyMap(),
      )
  }
}
