package dev.aster.vega.model.time

import dev.aster.vega.model.locale.DateField

/**
 * What a locale's own `%x` and `%X` patterns say about the **order** a language writes a date in.
 *
 * The fact this exists to carry: `VegaLocale.date` is d3's `%x` — "the date order this language
 * writes, as a pattern of directives" — and it was the only place in the engine that knew that
 * order while being read by nothing that decided a label. `TimeUnits.SPECIFIERS` carried `%Y-%m-%d`
 * and Vega-Lite wrote `%b %d, %Y`, both as literals with no locale in them, so a Dutch chart said
 * `mei 21, 2026`: the right month name in the wrong order.
 *
 * Upstream has no equivalent — its `timeUnitSpecifier` takes no locale at all — so deriving
 * anything here is a deliberate divergence rather than a port, and `VegaLocale.EnglishUS` pins
 * upstream's own answers so that the differential fixtures still compare against them.
 *
 * The compound specifiers are the locale's pattern with fields **dropped** rather than a table
 * rebuilt from parts, which is what keeps them the language's own: `%b %d, %Y` without its date is
 * `%b %Y` and not `%b, %Y`, because dropping a field takes one adjacent separator with it.
 */
internal object LocaleDatePattern {

  /** One directive in a pattern, and the literal text that followed it. */
  private class Piece(val field: DateField, val directive: String, var separator: String)

  /**
   * `%x` with only [keep]'s fields left in it.
   *
   * Null where the pattern names none of them, or names none of [keep] — a caller then keeps
   * whatever the default table said, which is the honest answer to a pattern this cannot read.
   *
   * Dropping a field takes one separator with it, and **which** one is the whole of the arithmetic:
   * a leading or middle field takes the separator that follows it, so `%b %d, %Y` without its date
   * is `%b %Y`; a trailing field takes the one before it, so the same pattern without its year is
   * `%b %d`.
   */
  fun withFields(pattern: String, keep: Set<DateField>): String? {
    val pieces = parse(pattern) ?: return null
    val kept = pieces.filter { it.field in keep }
    if (kept.isEmpty()) return null
    return buildString {
      for ((index, piece) in kept.withIndex()) {
        append(piece.directive)
        if (index < kept.size - 1) {
          // Between two kept fields, whatever sat there stays: it is doing the separating.
          append(piece.separator)
        } else if (piece === pieces.last() || isMarker(piece.separator)) {
          // After the last kept field, only text that **belongs to that field**. A pattern ending
          // in a literal keeps it, and so does a marker — see [isMarker].
          append(piece.separator)
        }
      }
    }
      // A marker may carry a space after it — `%Y년 %m월 ` — and nothing follows it now.
      .trimEnd()
  }

  /**
   * Whether [separator] is a **marker owned by the field before it**, rather than a connector
   * between two fields.
   *
   * This is the distinction the whole of [withFields] turns on, and getting it wrong is silent.
   * Dropping a field takes one adjacent separator with it, and which one depends on what the
   * separator is for:
   *
   * - `%b %d, %Y` — the `, ` sits *between* two fields and belongs to neither, so dropping the date
   *   takes the space after it and leaves `%b %Y`. A connector.
   * - `%Y年%m月%d日` — the `月` is not between anything, it is what makes the number a month. Dropping
   *   the day has to leave `%Y年%m月`, and taking `月` with it leaves `%Y年%m`, which reads "2026年08"
   *   and is not a date in any language. A marker.
   *
   * Two conditions, and both are needed. **A marker carries a letter**, which is what separates `月`
   * and `년` from `-`, `/`, `.` and `, `. **A marker is attached**, with no space before it, which
   * is what separates `月` from Spanish `%e de %B de %Y`: `de` is a word with letters in it, but the
   * space in front says it stands between the two fields rather than belonging to the one behind,
   * and `%e de %B de ` would be wrong where `%e de %B` is right.
   *
   * `Character.isLetter` is true for CJK ideographs, which is the case this exists for.
   */
  private fun isMarker(separator: String): Boolean =
    separator.isNotEmpty() && !separator.first().isWhitespace() && separator.any { it.isLetter() }

  /**
   * The order [pattern] writes year, month and day of month in, or null where it names none of
   * them.
   *
   * The *order* rather than the pattern, because that is the half of a language's date convention
   * that transfers: Vega-Lite's own table writes the month as a **name** — `%b %d, %Y` — and
   * substituting a name into a locale's numeric pattern gives `21-mei-2026`, where the separators
   * were chosen for numbers. So `:vega-lite` takes the order from here and keeps its own directives
   * and its own spacing.
   *
   * A field the pattern does not name is absent from the list rather than guessed at.
   */
  fun fieldOrder(pattern: String): List<DateField>? = parse(pattern)?.map { it.field }?.distinct()

  /** Whether [pattern] writes the month before the day of the month. */
  fun monthBeforeDate(pattern: String): Boolean? {
    val pieces = parse(pattern) ?: return null
    val month = pieces.indexOfFirst { it.field == DateField.MONTH }
    val date = pieces.indexOfFirst { it.field == DateField.DATE }
    if (month < 0 || date < 0) return null
    return month < date
  }

  /**
   * Whether [pattern] tells the time on a 24-hour clock.
   *
   * `%H` is the 24-hour hour and `%I` the 12-hour one, so this is a reading of the pattern rather
   * than a guess from the language. A pattern naming neither answers null and the caller keeps its
   * default, which is d3's `%I %p`.
   */
  fun twentyFourHour(pattern: String): Boolean? {
    val directives = directives(pattern).map { it.removePrefix("%").trimStart('-', '_', '0') }
    return when {
      directives.any { it.startsWith("H") || it.startsWith("k") } -> true
      directives.any { it.startsWith("I") || it.startsWith("l") } -> false
      else -> null
    }
  }

  /**
   * The pattern read as directives and the literal text between them.
   *
   * Null when it names no date field at all, which is the case a caller has to be able to tell from
   * "an order I can read": a `%x` of `"%s"` is not a date order and must not be treated as one.
   */
  private fun parse(pattern: String): List<Piece>? {
    val pieces = mutableListOf<Piece>()
    var index = 0
    val separator = StringBuilder()
    while (index < pattern.length) {
      val character = pattern[index]
      if (character != '%') {
        separator.append(character)
        index += 1
        continue
      }
      val directive = directiveAt(pattern, index)
      if (directive == null) {
        // A trailing `%`, or `%%` — a literal percent sign, which is text like any other.
        separator.append(character)
        index += 1
        continue
      }
      val field = fieldOf(directive)
      if (field == null) {
        // A directive this does not classify — `%A`, `%j` — is text as far as an *order* goes: it
        // moves with whatever it sits beside rather than being a field that can be dropped.
        separator.append(directive)
        index += directive.length
        continue
      }
      if (pieces.isEmpty() && separator.isNotEmpty()) {
        // Text before the first field belongs to that field, so it survives while the field does.
        pieces.add(Piece(field, separator.toString() + directive, ""))
      } else {
        pieces.lastOrNull()?.let { it.separator = separator.toString() }
        pieces.add(Piece(field, directive, ""))
      }
      separator.clear()
      index += directive.length
    }
    if (pieces.isEmpty()) return null
    pieces.last().separator = separator.toString()
    return pieces
  }

  /** Every directive in the pattern, whatever it names. */
  private fun directives(pattern: String): List<String> {
    val found = mutableListOf<String>()
    var index = 0
    while (index < pattern.length) {
      if (pattern[index] == '%') {
        val directive = directiveAt(pattern, index)
        if (directive != null) {
          found.add(directive)
          index += directive.length
          continue
        }
      }
      index += 1
    }
    return found
  }

  /**
   * The directive beginning at [at], padding modifier and all, or null where there is none.
   *
   * d3's modifiers are `-` (no padding), `_` (space padding) and `0` (zero padding), and they sit
   * between the percent and the letter — `%-d` is a day of the month without its leading zero,
   * which is what half the world's `%x` patterns are written with.
   */
  private fun directiveAt(pattern: String, at: Int): String? {
    var index = at + 1
    if (
      index < pattern.length &&
        (pattern[index] == '-' || pattern[index] == '_' || pattern[index] == '0')
    ) {
      index += 1
    }
    if (index >= pattern.length) return null
    val letter = pattern[index]
    if (!letter.isLetter()) return null
    return pattern.substring(at, index + 1)
  }

  private fun fieldOf(directive: String): DateField? =
    when (directive.last()) {
      // `%G` and `%g` are the ISO week-numbering year, which is a year for ordering purposes.
      'Y',
      'y',
      'G',
      'g' -> DateField.YEAR
      // `%b` and `%B` are names and `%m` a number; all three sit where the month sits.
      'm',
      'b',
      'B' -> DateField.MONTH
      // `%e` is a space-padded day of the month, which several locales' `%x` prefer.
      'd',
      'e' -> DateField.DATE
      else -> null
    }
}
