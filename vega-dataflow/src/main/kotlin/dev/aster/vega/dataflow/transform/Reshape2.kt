package dev.aster.vega.dataflow.transform

import dev.aster.vega.expression.JsSemantics
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asString
import dev.aster.vega.model.field

/**
 * `cross`: every row paired with every row, which is how a specification builds a matrix.
 *
 * Each output row holds the two originals whole, under `a` and `b` unless `as` renames them — not
 * their fields merged, which is what "cross" suggests and would lose a column whenever the two
 * sides share a name. An expression reaching into one therefore writes `datum.a.value`.
 *
 * `filter` is applied to the pairs as they are formed, so it is the way to get the upper triangle
 * of a matrix rather than all of it.
 */
public object CrossTransform : Transform {
  override val type: String = "cross"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val names = params.stringList("as")
    val left = names.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: "a"
    val right = names.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: "b"

    val other =
      params.string("from")?.let { context.scope.dataset(it) }?.takeIf { it.isNotEmpty() } ?: input
    val filter = params.string("filter")?.let { TupleExpression(it, context, type) }
    if (filter != null && !filter.isUsable) return input

    val result = mutableListOf<VegaValue>()
    for (a in input) {
      for (b in other) {
        val pair = VegaValue.Obj(linkedMapOf(left to a, right to b))
        if (filter == null || JsSemantics.truthy(filter.evaluate(pair) ?: VegaValue.Null)) {
          result += pair
        }
      }
    }
    return result
  }
}

/**
 * `pivot`: turns rows into columns, so a long table becomes a wide one.
 *
 * The column names come from the data, which is the awkward part: they are collected across the
 * whole dataset and **sorted alphabetically** before `limit` takes the first few — so limiting a
 * pivot keeps the alphabetically first columns, not the commonest or the earliest, and a column
 * name that sorts late disappears however often it occurs.
 *
 * Cells are summarised with `op`, which defaults to `sum`, because two rows can land in the same
 * cell. Without a `groupby` the whole dataset collapses to a single row.
 */
public object PivotTransform : Transform {
  override val type: String = "pivot"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val field = params.string("field")
    val value = params.string("value")
    if (field.isNullOrEmpty() || value.isNullOrEmpty()) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "pivot needs 'field' and 'value'",
        operator = type,
      )
      return input
    }
    val groupBy = params.stringList("groupby")
    val limit = params.number("limit")?.toInt() ?: 0
    val opName = params.string("op") ?: "sum"
    val op =
      AggregateOp.fromName(opName)
        ?: run {
          context.diagnostics.error(
            DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED,
            "pivot operation '$opName' is not implemented",
            operator = type,
          )
          return input
        }

    val columns =
      input
        .map { it.field(field) }
        .filterNot { it is VegaValue.Null }
        .map { it.asString() }
        .distinct()
        .sorted()
        .let { if (limit > 0) it.take(limit) else it }

    return groupTuples(input, groupBy).map { (groupKey, rows) ->
      val fields = LinkedHashMap<String, VegaValue>(groupBy.size + columns.size)
      groupBy.forEachIndexed { index, path -> fields[path] = groupKey[index] }
      for (column in columns) {
        val cells = rows.filter { it.field(field).asString() == column }
        // A column no row in this group reaches is absent rather than zero, which is what a mark
        // encoding it will see.
        if (cells.isNotEmpty()) fields[column] = aggregateOver(op, value, cells)
      }
      VegaValue.Obj(fields)
    }
  }
}

/**
 * `countpattern`: counts the words in a text column, which is what a word cloud is made of.
 *
 * The pattern defaults to `[\w']+`, so an apostrophe holds a word together and everything else
 * splits it. `stopwords` is a regular expression matched against the **whole** token, anchored and
 * case-insensitively, so `the|on` drops exactly those two words and not every word containing them.
 *
 * Counts come out in first-appearance order rather than sorted, so a chart that wants the commonest
 * first has to `collect` afterwards.
 */
public object CountPatternTransform : Transform {
  override val type: String = "countpattern"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val field = params.string("field")
    if (field.isNullOrEmpty()) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "countpattern needs a 'field'",
        operator = type,
      )
      return input
    }
    val patternSource = params.string("pattern") ?: "[\\w']+"
    val stopSource = params.string("stopwords") ?: ""
    val casing = params.string("case") ?: "mixed"
    val names = params.stringList("as")
    val textName = names.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: "text"
    val countName = names.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: "count"

    val pattern = runCatching {
      Regex(patternSource)
    }
      .getOrElse {
        context.diagnostics.error(
          DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
          "countpattern could not read the pattern '$patternSource'",
          operator = type,
        )
        return input
      }
    // Anchored, as upstream anchors it: a stopword list names whole words, not substrings.
    val stopwords = runCatching {
      Regex("^($stopSource)$", RegexOption.IGNORE_CASE)
    }
      .getOrElse {
        context.diagnostics.error(
          DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
          "countpattern could not read the stopwords '$stopSource'",
          operator = type,
        )
        return input
      }

    val counts = LinkedHashMap<String, Int>()
    for (datum in input) {
      val raw = datum.field(field)
      if (raw is VegaValue.Null) continue
      val text =
        when (casing.lowercase()) {
          "upper" -> raw.asString().uppercase()
          "lower" -> raw.asString().lowercase()
          else -> raw.asString()
        }
      for (match in pattern.findAll(text)) {
        val token = match.value
        if (stopSource.isNotEmpty() && stopwords.matches(token)) continue
        counts[token] = (counts[token] ?: 0) + 1
      }
    }
    return counts.map { (token, count) ->
      VegaValue.Obj(
        linkedMapOf(textName to VegaValue.Str(token), countName to VegaValue.Num(count.toDouble()))
      )
    }
  }
}
