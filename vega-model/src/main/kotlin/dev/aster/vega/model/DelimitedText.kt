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
    return rows.drop(1).mapNotNull { cells ->
      // A trailing newline leaves one empty row, which is not a record.
      if (cells.size == 1 && cells[0].isEmpty()) return@mapNotNull null
      val fields = LinkedHashMap<String, VegaValue>(header.size)
      header.forEachIndexed { index, name ->
        // A short row leaves the remaining columns absent rather than empty: "no value" and "the
        // empty string" are different things to a filter or a scale domain.
        cells.getOrNull(index)?.let { fields[name] = VegaValue.Str(it) }
      }
      VegaValue.Obj(fields)
    }
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
        c == '\r' -> Unit // Part of a CRLF; the newline that follows ends the row.
        c == '\n' -> {
          cells.add(cell.toString())
          cell.clear()
          rows.add(cells)
          cells = mutableListOf()
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
