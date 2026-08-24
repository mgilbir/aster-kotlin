package dev.aster.vegalite

import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.canonicalNumberString
import dev.aster.vega.model.locale.VegaLocale

/**
 * The name a channel definition takes in the compiled Vega, and the title a guide shows for it.
 *
 * Both are derived rather than chosen, and both appear in several places at once: `mean_b` names
 * the aggregate's output, the scale's domain field, the mark's encoding and the accessibility
 * description, so a name that differs from upstream's differs in all four. This is `vgField` and
 * `title` from `channeldef.ts`.
 */
internal object Fields {

  /**
   * The Vega field name for a definition.
   *
   * @param suffix appended after an underscore — `"end"` for the upper edge of a stack
   * @param forAs true when the name is a transform's output, where a nested path is flattened
   *   rather than escaped
   */
  fun vgField(def: ChannelDef, suffix: String? = null, forAs: Boolean = false): String {
    var field = def.field
    var effectiveSuffix = suffix

    // An `argmin`/`argmax` names its output after the column it was taken over, not after the one
    // being read — the row it answers with carries every column, and the field says which.
    if (def.argumentField != null) {
      val named = "${def.aggregate}_${def.argumentField}"
      val full = if (effectiveSuffix != null) "${named}_$effectiveSuffix" else named
      // As a *name* it is the column alone; as a **reference** it is that column read one step
      // further in, and that step is not escaped — it is a real path into a real object.
      return if (forAs) removePathFromField(full) else replacePathInField(full) + argAccessor(def)
    }

    if (def.aggregate == "count") {
      // Upstream reserves a double-underscore namespace for fields it invents.
      field = "__count"
    } else {
      val function =
        when {
          def.bin is Binning.Bin -> {
            effectiveSuffix = suffix
            binToString(def.bin.params)
          }
          def.aggregate != null -> def.aggregate
          // `isBinnedTimeUnit`: a column that arrives already bucketed keeps its own name — there
          // is no transform writing a new one, only a formula computing the bucket's far edge.
          def.timeUnit != null && !isBinnedTimeUnit(def.timeUnit) -> timeUnitToString(def.timeUnit)
          else -> null
        }
      if (function != null) {
        field = if (field != null) "${function}_$field" else function
      }
    }

    if (effectiveSuffix != null) field = "${field}_$effectiveSuffix"
    val resolved = field ?: ""
    return if (forAs) removePathFromField(resolved) else replacePathInField(resolved)
  }

  /**
   * `accessWithDatumToUnescapedPath`: `datum['a']` over a column named as it is in the data.
   *
   * A single quote in the name would end the string it is written in, so it is escaped — a column
   * called `monthly(date_trunc('day', date))` is a real one, and reading it needs `\'`.
   */
  fun datumPath(field: String): String = "datum['${field.replace("'", "\\'")}']"

  /** `datum["mean_b"]`, the accessor an emitted expression uses to read the field. */
  fun datumAccess(def: ChannelDef, suffix: String? = null, datum: String = "datum"): String =
    "$datum[${quoted(removePathFromField(vgField(def, suffix, forAs = true)))}]" + argAccessor(def)

  /** The one path step an `argmin`/`argmax` reads out of the row it answered with. */
  private fun argAccessor(def: ChannelDef): String {
    if (def.argumentField == null) return ""
    val field = def.field ?: return ""
    return "[${quoted(field)}]"
  }

  /**
   * `x_c_sort_index` — the column a written-out `sort` order records each row's place in.
   *
   * Prefixed by the *channel*, because one field may be ordered one way along the axis and another
   * in the legend, and each order is a column of its own.
   */
  fun sortIndexField(channel: String, def: ChannelDef, forAs: Boolean = false): String =
    "${channel}_${vgField(def, suffix = "sort_index", forAs = forAs)}"

  /**
   * The guide title, before a guide decides whether to show it.
   *
   * The verbal formatter is the default and is the reason a mean reads `Mean of b` rather than
   * `mean_b`, and a count reads `Count of Records` — the one title that comes from configuration
   * rather than from the field, because there is no field to name.
   */
  fun defaultTitle(def: ChannelDef, config: Config): String? {
    if (def.aggregate == "count") return config.countTitle
    if (def.bin is Binning.Bin) return "${def.field} (binned)"
    // A column that arrived already bucketed is titled by its own name: nothing here bucketed it,
    // so there is no derivation to announce.
    if (def.timeUnit != null && !isBinnedTimeUnit(def.timeUnit)) {
      val parts = timeUnitParts(def.timeUnit)
      if (parts.isNotEmpty()) return "${def.field} (${parts.joinToString("-")})"
    }
    // `Production Budget for max US Gross` — the column being read, then the one it was chosen by.
    if (def.argumentField != null) {
      val extreme = if (def.aggregate == "argmax") "max" else "min"
      return "${def.field} for $extreme ${def.argumentField}"
    }
    if (def.aggregate != null) return "${titleCase(def.aggregate)} of ${def.field}"
    return def.field
  }

  /** The title actually written, honouring an explicit one and an explicit `null` that hides it. */
  fun title(def: ChannelDef, config: Config): VegaValue? {
    val guideTitle = def.axis?.fields?.get("title") ?: def.legend?.fields?.get("title")
    if (guideTitle != null) return guideTitle
    def.explicitTitle?.let {
      return it
    }
    return defaultTitle(def, config)?.let { VegaValue.Str(it) }
  }

  /**
   * `bin_maxbins_10`: the bin parameters spelled into the name, so two different binnings of one
   * field cannot collide.
   */
  fun binToString(params: VegaValue.Obj): String = buildString {
    append("bin")
    for ((key, value) in params.fields) {
      append(varName("_${key}_${literalText(value)}"))
    }
  }

  fun timeUnitToString(timeUnit: String): String = timeUnit

  /** `{"unit": …, "step": n}` — how many of the unit each bucket spans, where more than one. */
  fun timeUnitStep(timeUnit: String): Int? =
    Regex("_step_(\\d+)").find(timeUnit)?.groupValues?.get(1)?.toIntOrNull()

  /** `binnedyearmonth` — a time unit the *data* was bucketed by before it arrived. */
  fun isBinnedTimeUnit(timeUnit: String): Boolean = timeUnit.startsWith("binned")

  /**
   * `timeUnitSpecifier(...)`: the format specifier a bucketed instant is labelled with.
   *
   * Vega picks the specifier at render time from the units present and the span being shown, which
   * is why this is an expression rather than a `%b`. The second argument is Vega-Lite's own
   * override table — a month within one year reads `Jan`, a month spanning several reads `Jan
   * 2009`.
   */
  fun timeUnitSpecifier(timeUnit: String, locale: VegaLocale = VegaLocale.EnglishUS): String {
    val parts = timeUnitParts(timeUnit).joinToString(",") { "\"$it\"" }
    val table = localeTimeFormat(locale)
    val written =
      table.entries.joinToString(",") { (key, value) ->
        "\"$key\":" + if (value == null) "null" else "\"${escaped(value)}\""
      }
    return "timeUnitSpecifier([$parts], {$written})"
  }

  /**
   * The specifier a **plain** temporal field is labelled with, quoted ready for an expression.
   *
   * The other half of [timeUnitSpecifier]. A field carrying a `timeUnit` gets an expression,
   * because Vega chooses among the units at render time from the span being shown; a field with no
   * `timeUnit` has nothing to choose between and gets a literal, which upstream writes as `"%b %d,
   * %Y"` and cannot do otherwise, having no locale to ask.
   *
   * It is the same table's `year-month-date` entry, so the two halves cannot drift: whatever a
   * bucketed year-month-date is labelled with, an unbucketed date is labelled with too.
   *
   * **Trimmed**, and that is not cosmetic. Every entry in the table ends in a space because
   * `TimeUnits.specifier` concatenates the pieces of a compound specifier and trims the result. A
   * literal is not concatenated with anything, so it has to do the trimming here — and for
   * [VegaLocale.EnglishUS] that is exactly what makes this `%b %d, %Y`, upstream's own string, with
   * every fixture unmoved.
   */
  fun fullDateSpecifier(locale: VegaLocale = VegaLocale.EnglishUS): String {
    // A host may state `null` for an entry, which *removes* it rather than restoring a default.
    // With
    // no year-month-date to read, upstream's own is the honest answer rather than nothing at all.
    val pattern =
      localeTimeFormat(locale)["year-month-date"] ?: UPSTREAM_TIME_FORMAT["year-month-date"]
    return "\"" + escaped(pattern.orEmpty().trim()) + "\""
  }

  /**
   * The table `timeUnitSpecifier` is given as its second argument: **one date per locale**, shared
   * with the Vega path rather than derived a second way here.
   *
   * This used to read only the field *order* off the locale and rebuild the entries from
   * Vega-Lite's own directives with a single space between them, on the argument that `%x` is
   * numeric and substituting a month name into numeric separators gives `21-mei-2026`. The argument
   * is sound against substituting; it is not an argument for discarding the pattern. Keeping the
   * locale's own directives substitutes nothing, and rebuilding threw away everything a language
   * writes that is not a field: a Dutch host got `%d %b %Y` here and `%d-%m-%Y` from the same
   * locale on a Vega chart, Spanish lost its `de`, and `%b %d, %Y` came back without its comma —
   * which is the derivation `VegaLocale.timeUnitSpecifierOverrides` documents, disagreeing with the
   * code that implemented it. Reported as #97.
   *
   * So the derivation is `VegaLocale.timeUnitSpecifiers`, the same one a Vega chart reads, and the
   * two grammars cannot answer differently about the same locale again.
   *
   * What stays Vega-Lite's own is the **fallback**, and only that. Where a locale says nothing —
   * `VegaLocale.EnglishUS` states an empty map, and a `%x` naming no date field derives nothing —
   * this answers with upstream's `VEGALITE_TIMEFORMAT`, the table that spells a month as a *name*,
   * where a Vega chart would fall through to `TimeUnits.SPECIFIERS` and get numerals. That
   * difference is upstream's and is the whole reason this table exists.
   *
   * Two consequences worth stating rather than discovering:
   * - **`EnglishUS` is untouched.** It states a map, so nothing is derived for it, and upstream's
   *   `%b %d, %Y ` survives comma and all — which is what keeps the 283 fixture comparisons
   *   meaningful.
   * - **A `%x` naming fewer than three fields now derives** rather than falling back. It gets a
   *   `year-month-date` with no day in it, which is not good, and is exactly what a Vega chart has
   *   always done with the same locale. Agreeing is the point here; improving it is a question for
   *   both paths at once.
   *
   * A stated table still wins outright, and now for **every** key it names. It used to be filtered
   * to the keys the fallback happened to hold, so a host stating `month-date` had it honoured on a
   * Vega chart and dropped here.
   *
   * The trailing spaces are load-bearing — `TimeUnits.specifier` concatenates the pieces of a
   * compound specifier and trims the result — and the insertion order is what keeps the emitted
   * expression identical to upstream's for a locale that states nothing.
   */
  private fun localeTimeFormat(locale: VegaLocale): Map<String, String?> {
    val stated = locale.timeUnitSpecifierOverrides
    // Stated against upstream's table rather than against a derivation, because a host stating one
    // entry of a Vega-Lite table means the other entries to stay what Vega-Lite would have said.
    if (stated != null) return UPSTREAM_TIME_FORMAT + stated
    return locale.timeUnitSpecifiers.ifEmpty { UPSTREAM_TIME_FORMAT }
  }

  /**
   * Upstream's table verbatim: a month within one year reads `Jan`, a month spanning several reads
   * `Jan 2009`.
   *
   * The fallback for a locale whose `date` names fewer than the three fields — not a date order,
   * and not something to invent one from.
   */
  private val UPSTREAM_TIME_FORMAT: Map<String, String?> =
    linkedMapOf("year-month" to "%b %Y ", "year-month-date" to "%b %d, %Y ")

  /** A pattern going into a JSON string inside a generated expression. */
  private fun escaped(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

  /**
   * The length of one bucket, as an expression — `durationExpr` upstream.
   *
   * An axis over bucketed instants must not tick more finely than the buckets themselves, and there
   * is no constant that says how long a month is, so the difference between two dates in a
   * deliberately non-leap year is what says it.
   */
  fun timeUnitDuration(timeUnit: String, wrap: (String) -> String = { it }): String? {
    val smallest = timeUnitParts(timeUnit).lastOrNull() ?: return null
    if (smallest == "day") return null
    // `datetime` takes a zero-based month, which is why January is 0 and the step lands on 1.
    val fields = listOf("year", "month", "date", "hours", "minutes", "seconds", "milliseconds")
    val start =
      mutableMapOf(
        "year" to 2001,
        "month" to 0,
        "date" to 1,
        "hours" to 0,
        "minutes" to 0,
        "seconds" to 0,
        "milliseconds" to 0,
      )
    // A bucket spanning several of the unit is that many times as wide, and the tick step follows
    // it: a chart bucketed two years at a time has no tick closer than two years.
    val span = timeUnitStep(timeUnit) ?: 1
    val (part, step) =
      when (smallest) {
        "dayofyear" -> "date" to span
        "quarter" -> "month" to 3 * span
        "week" -> "date" to 7 * span
        else -> smallest to span
      }
    if (part !in start) return null
    val end = start.toMutableMap()
    end[part] = start.getValue(part) + step
    fun expr(values: Map<String, Int>) =
      "datetime(" + fields.joinToString(", ") { values.getValue(it).toString() } + ")"
    return "${wrap(expr(end))} - ${wrap(expr(start))}"
  }

  /** `yearmonth` reads as `year-month` in a title. */
  fun timeUnitParts(rawTimeUnit: String): List<String> {
    val timeUnit = rawTimeUnit.removePrefix("binned")
    val units =
      listOf(
        "year",
        "quarter",
        "month",
        "date",
        "week",
        "day",
        "dayofyear",
        "hours",
        "minutes",
        "seconds",
        "milliseconds",
      )
    var rest = timeUnit.removePrefix("utc")
    val parts = mutableListOf<String>()
    while (rest.isNotEmpty()) {
      val match = units.firstOrNull { rest.startsWith(it) } ?: break
      parts += match
      rest = rest.substring(match.length)
    }
    return parts
  }

  private fun titleCase(text: String): String =
    if (text.isEmpty()) text else text.substring(0, 1).uppercase() + text.substring(1)

  /** What JavaScript's template interpolation writes for a bin parameter. */
  private fun literalText(value: VegaValue): String =
    when (value) {
      is VegaValue.Num -> canonicalNumberString(value.value)
      is VegaValue.Str -> value.value
      is VegaValue.Bool -> value.value.toString()
      is VegaValue.Arr -> value.values.joinToString(",") { literalText(it) }
      // A bin extent naming a **selection** is written from the object's *entries* —
      // `entries(bin[p])` in `binToString` — so `{"param": "brush"}` reads `param,brush` and the
      // bucketing is named `bin_maxbins_30_extent_param_brush`. Stringified as an object it read
      // as nothing at all, and two different bucketings of one column shared a name.
      is VegaValue.Obj ->
        value.fields.entries.joinToString(",") { "${it.key},${literalText(it.value)}" }
      else -> ""
    }

  /**
   * A number written into an *expression*, the way JavaScript writes one.
   *
   * Not the display canonicaliser: that rounds, and an expression is compared as a string. Upstream
   * writes `String(n)`, which is the shortest text that reads back as the same double — so `2`, not
   * `2.0`, and `0.15000000000000002`, not `0.15`, those two being different numbers.
   */
  fun expressionNumber(value: Double): String =
    if (value == kotlin.math.floor(value) && !value.isInfinite() && kotlin.math.abs(value) < 1e15) {
      value.toLong().toString()
    } else {
      value.toString()
    }

  fun varName(text: String): String {
    val cleaned = text.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")
    return if (text.firstOrNull()?.isDigit() == true) "_$cleaned" else cleaned
  }

  /** `a.b` stays one nested path in a transform's output; the dots are literal there. */
  private fun removePathFromField(path: String): String = splitAccessPath(path).joinToString(".")

  /** Everywhere else a dot is escaped, because Vega reads an unescaped one as a path step. */
  /**
   * `replacePathInField`: every path step joined by an **escaped** dot, each step escaped in turn.
   *
   * `escapePathAccess` escapes a bracket, a dot and both quotes *inside* a step as well, because a
   * column may be called `source.reco` — a name with a dot in it, not a path into `source` — and
   * writing it unescaped tells Vega to look one level in and find nothing.
   */
  private fun replacePathInField(path: String): String =
    splitAccessPath(path).joinToString("\\.") { step ->
      step.map { if (it in "[].'\"") "\\$it" else "$it" }.joinToString("")
    }

  /** Splits `a.b`, `a["b"]` and `a['b']` the way `vega-util`'s `splitAccessPath` does. */
  fun splitAccessPath(path: String): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var index = 0
    while (index < path.length) {
      when (val ch = path[index]) {
        '\\' -> {
          if (index + 1 < path.length) {
            current.append(path[index + 1])
            index += 2
          } else {
            index += 1
          }
        }
        '.' -> {
          parts += current.toString()
          current.setLength(0)
          index += 1
        }
        '[' -> {
          if (current.isNotEmpty()) {
            parts += current.toString()
            current.setLength(0)
          }
          val close = path.indexOf(']', index)
          if (close < 0) {
            current.append(path.substring(index))
            index = path.length
          } else {
            parts +=
              path.substring(index + 1, close).trim().removeSurrounding("\"").removeSurrounding("'")
            index = close + 1
          }
        }
        else -> {
          current.append(ch)
          index += 1
        }
      }
    }
    if (current.isNotEmpty()) parts += current.toString()
    return if (parts.isEmpty()) listOf("") else parts
  }
}
