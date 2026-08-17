package dev.aster.vega.model

/**
 * Reads CSV, TSV and other delimiter-separated text into rows.
 *
 * RFC 4180's quoting rules, which are not optional in practice: a field wrapped in double quotes
 * may contain the delimiter, a newline, or a doubled `""` standing for one quote. A naive `split`
 * gets every one of those wrong, and gets them wrong *quietly* — a stray comma inside a quoted
 * label shifts every later column of that row, so the chart draws from data that is misaligned
 * rather than missing.
 *
 * **Every cell comes out as a string.** That is upstream's behaviour and it matters: a CSV has no
 * types, so a numeric column stays text until `format.parse` says otherwise, and a scale built over
 * it is an ordinal one over strings. A specification that omits `parse` on a CSV gets a visibly odd
 * chart rather than a subtly wrong one.
 */
public object DelimitedText {

  /**
   * @param delimiter the field separator; a tab for TSV, a comma for CSV.
   * @return one object per data row, keyed by the header row's names.
   */
  public fun parse(text: String, delimiter: Char): List<VegaValue> {
    val rows = rows(text, delimiter)
    if (rows.isEmpty()) return emptyList()
    val header = rows.first()
    return rows.drop(1).map { cells ->
      // A blank line is a **record**, not nothing: upstream reads it as a row of empty fields, and
      // dropping it loses a datum from the middle of a file rather than tidying one from the end.
      // (The end tidies itself — a trailing terminator closes its row and starts no new one.)
      val fields = LinkedHashMap<String, VegaValue>(header.size)
      header.forEachIndexed { index, name ->
        // A short row leaves the remaining columns absent rather than empty: "no value" and "the
        // empty string" are different things to a filter or a scale domain.
        cells.getOrNull(index)?.let { fields[name] = VegaValue.Str(it) }
      }
      VegaValue.Obj(fields)
    }
  }

  /**
   * The rows as raw cells, with no header applied — d3's `parseRows`, and what [parse] is built on.
   */
  public fun parseRows(text: String, delimiter: Char): List<List<String>> = rows(text, delimiter)

  /**
   * One cell as d3 writes it: quoted when it holds a quote, the delimiter, or a line ending.
   *
   * The `\r` in that set is not decoration. A header written on a machine with classic Mac line
   * endings, or a field carrying one, has to come back as **one** field — and this rule is the only
   * thing that makes the text this engine hands its own parser round-trip.
   */
  public fun formatValue(value: String, delimiter: Char): String =
    if (value.any { it == '"' || it == delimiter || it == '\n' || it == '\r' }) {
      "\"" + value.replace("\"", "\"\"") + "\""
    } else {
      value
    }

  /** One row, quoted cell by cell and joined with the delimiter — d3's `formatRow`. */
  public fun formatRow(cells: List<String>, delimiter: Char): String =
    cells.joinToString(delimiter.toString()) { formatValue(it, delimiter) }

  /** Rows joined by newlines, with no header inferred — d3's `formatRows`. */
  public fun formatRows(rows: List<List<String>>, delimiter: Char): String =
    rows.joinToString("\n") { formatRow(it, delimiter) }

  /**
   * Objects as delimited text, with the header inferred from the keys — d3's `format`.
   *
   * The columns are the **union** of every row's keys in first-seen order, so a row that omits a
   * field still lines up under it and a row that adds one widens the table rather than shifting it.
   */
  public fun format(
    rows: List<VegaValue>,
    delimiter: Char,
    columnNames: List<String>? = null,
  ): String {
    val columns = LinkedHashSet<String>()
    if (columnNames != null) columns.addAll(columnNames)
    else for (row in rows) if (row is VegaValue.Obj) columns.addAll(row.fields.keys)
    val header = formatRow(columns.toList(), delimiter)
    val body = rows.map { row ->
      formatRow(columns.map { cellText((row as? VegaValue.Obj)?.fields?.get(it)) }, delimiter)
    }
    return (listOf(header) + body).joinToString("\n")
  }

  /**
   * A cell's text, as JavaScript would write it; an absent or null value is empty rather than
   * "null".
   */
  private fun cellText(value: VegaValue?): String =
    when (value) {
      null,
      is VegaValue.Null -> ""
      is VegaValue.Str -> value.value
      is VegaValue.Num -> Decimals.jsString(value.value)
      is VegaValue.Bool -> value.value.toString()
      else -> value.toString()
    }

  /** Splits the text into rows of raw cells, honouring quotes across delimiters and newlines. */
  private fun rows(text: String, delimiter: Char): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var cells = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var i = 0
    while (i < text.length) {
      val c = text[i]
      when {
        quoted && c == '"' ->
          // A doubled quote inside a quoted field is one literal quote, not the end of the field.
          if (i + 1 < text.length && text[i + 1] == '"') {
            cell.append('"')
            i++
          } else {
            quoted = false
          }
        quoted -> cell.append(c)
        c == '"' && cell.isEmpty() -> quoted = true
        c == delimiter -> {
          cells.add(cell.toString())
          cell.clear()
        }
        // All three line endings end a row. Treating a lone `\r` as nothing — as this did — joins
        // every line of a classic-Mac file into one row, and the columns then belong to no header.
        c == '\r' || c == '\n' -> {
          cells.add(cell.toString())
          cell.clear()
          rows.add(cells)
          cells = mutableListOf()
          if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
        }
        else -> cell.append(c)
      }
      i++
    }
    if (cell.isNotEmpty() || cells.isNotEmpty()) {
      cells.add(cell.toString())
      rows.add(cells)
    }
    return rows
  }
}
