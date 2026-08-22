package dev.aster.vega.model.locale

/**
 * A name-bearing piece of a date format — the five directives that write a word rather than a
 * number.
 *
 * Not to be confused with [DateField], which is about the *order* of a date: `%x` says whether the
 * month comes before the day, and this says what the month is called once it is placed.
 */
public enum class DateName {
  /** `%B` — the month's full name. */
  MONTH,
  /** `%b` — the month, abbreviated. */
  MONTH_SHORT,
  /** `%A` — the weekday's full name. */
  WEEKDAY,
  /** `%a` — the weekday, abbreviated. */
  WEEKDAY_SHORT,
  /** `%p` — the half-day marker. */
  HALF_DAY,
}

/**
 * What else the format being written contains, for a name whose form depends on it.
 *
 * Handed over rather than left for a host to read off the pattern, and that is not a convenience.
 * The first rule written against an earlier version of this interface tested
 * `pattern.contains("%d")` and was wrong, because the pattern was `%-d`: a pad modifier sits
 * between the percent and the letter, so the obvious check misses half the patterns in the world.
 * Asking a host to re-parse strftime is the engine pushing its own work outward, and it fails
 * quietly.
 *
 * So the directives arrive already parsed, with the questions a contextual name actually asks
 * answered.
 */
public class DateNameContext
internal constructor(
  /** The whole format being written, as written. */
  public val pattern: String,
  /** Every directive letter in it, pad modifiers stripped — `d`, `m`, `Y`, `B`. */
  public val directives: Set<Char>,
) {
  /** Whether a day of the month accompanies the name — `%d` or `%e`. */
  public val hasDayOfMonth: Boolean
    get() = 'd' in directives || 'e' in directives

  /** Whether a year accompanies it — `%Y`, `%y`, or their ISO week-numbering forms. */
  public val hasYear: Boolean
    get() = 'Y' in directives || 'y' in directives || 'G' in directives || 'g' in directives

  /** Whether a time of day accompanies it. */
  public val hasTimeOfDay: Boolean
    get() = 'H' in directives || 'I' in directives || 'M' in directives || 'S' in directives

  /** Whether the name is the only thing in the format, which several languages inflect for. */
  public val isAlone: Boolean
    get() = directives.size == 1

  override fun toString(): String = "DateNameContext($pattern)"
}

/**
 * The host's own rules for the **pieces** a format is made of.
 *
 * The one seam in this engine's locale handling that is behaviour rather than data, and it is
 * shaped by a rule about precedence: **a specification's format decides the shape, and the host
 * decides the details inside it.** A document writing `"format": "%b %d, %Y"` gets an abbreviated
 * month, a day and a four-digit year in that order, whatever a host supplies here — this cannot
 * reorder a date, drop a field or substitute a pattern of its own. What it can do is decide what
 * `%b` says and what a digit looks like, which is where a device's and a reader's own preferences
 * actually live.
 *
 * That is the difference between this and everything else on [VegaLocale]. The names, the
 * separators and the three patterns are a table, and a table can only answer what somebody thought
 * to tabulate. Two things it provably cannot answer, both from real languages:
 *
 * - **A contextual name.** Polish writes `styczeń` alone and `stycznia` beside a day number, so the
 *   month's form depends on the rest of the pattern. One `months` list cannot hold both, and no
 *   arrangement of lists can, because the choice is a function of the format. [name] is given the
 *   whole [pattern] for exactly this.
 * - **A numbering system.** `TimeFormat` and `NumberFormat` write `value.toString()`, which is
 *   ASCII always, so `١٢` was unreachable however the locale was filled in. [digits] is applied to
 *   the engine's own numeric output, after grouping and padding, so it is a numbering system rather
 *   than a formatter.
 *
 * **Every method may answer null**, meaning "the engine's own answer": a host implements the rules
 * it has and inherits the rest. And a locale with no rules at all is byte-for-byte what it was,
 * which is what keeps `VegaLocale.EnglishUS` reproducing upstream and the differential fixtures
 * meaningful.
 *
 * What it deliberately does **not** reach, so that a specification's format stays a specification's
 * format:
 * - Which day a week starts on. `%U` is Sunday-based and `%W` Monday-based; that is a *field* the
 *   document chose, not a rendering of one.
 * - A calendar or an era. `%Y` means "the year, four digits"; a Japanese era year is a different
 *   field, not a different spelling of that one.
 * - Any format a platform *composes* rather than names — `getBestDateTimePattern`,
 *   `Date.FormatStyle`. Handing one of those in would be replacing the document's format, which is
 *   the thing this shape exists to prevent.
 *
 * ```kotlin
 * object PolishRules : VegaFormatRules {
 *   override fun name(
 *     field: DateName,
 *     index: Int,
 *     context: DateNameContext,
 *     locale: VegaLocale,
 *   ): String? =
 *     // A day number in the same format means the genitive.
 *     if (field == DateName.MONTH && context.hasDayOfMonth) GENITIVE[index] else null
 *
 *   override fun digits(number: String): String? = null
 * }
 * ```
 */
public interface VegaFormatRules {

  /**
   * What a name-bearing directive writes, or null for the locale's own list.
   *
   * @param field which directive is being written; see [DateName].
   * @param index the month (0 for January), the weekday (**0 for Sunday**, which is the week d3
   *   labels against) or the half-day (0 before noon). The same index the locale's own list is read
   *   at, so a rule is a better lookup rather than a different one.
   * @param context what else the format contains, already parsed — so a language whose month form
   *   depends on what accompanies it can tell which form to use without re-reading strftime. For
   *   `%x`, `%X` and `%c` this describes the pattern the *locale* supplied, since that is the
   *   format actually being written.
   */
  public fun name(
    field: DateName,
    index: Int,
    context: DateNameContext,
    locale: VegaLocale,
  ): String?

  /**
   * The digits a number is written with, or null for the engine's own ASCII.
   *
   * Applied to each numeric piece the engine produced — a padded hour, a grouped thousand, a signed
   * year, a formatted number — **after** grouping, padding and the decimal separator are in place.
   * So this is a transliteration and not a formatter: it cannot change how wide a field is or where
   * a separator goes, both of which the specification's own format decided.
   *
   * Literal text in a pattern is never passed here. A document writing `"Q%q 2026"` keeps its
   * `2026` exactly as typed, because that is text the document wrote rather than a number the
   * engine computed.
   */
  public fun digits(number: String): String?
}
