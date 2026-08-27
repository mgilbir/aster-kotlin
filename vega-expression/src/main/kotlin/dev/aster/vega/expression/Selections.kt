package dev.aster.vega.expression

import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asString
import dev.aster.vega.model.field
import dev.aster.vega.model.isNullish

/**
 * The `vlSelection*` family: whether a row is inside an interactive selection, and what one
 * resolves to.
 *
 * A transcription of `vega-selections`, which is a Vega package rather than a Vega-Lite one — these
 * four are in Vega's own function table, and a Vega-Lite chart with a brush on it compiles to a
 * specification that calls them. They were excused here as "selection helpers require the signal
 * and selection subsystems", which was never true: a selection is an ordinary **dataset** whose
 * rows say `{unit, fields, values}`, and every one of these functions is arithmetic over those
 * rows.
 *
 * The field types are the vocabulary: `E` enumerates values, `R` is an inclusive range, `R-E`,
 * `R-LE` and `R-RE` are the same range with an end opened — `R-RE` is the one a binned selection
 * uses, so a value on a bin's upper edge belongs to the *next* bin — and the `E-LT`/`E-GT` family
 * are one-sided comparisons. `E-VALID` asks only that the value exist.
 */
internal object Selections {

  private const val TYPE_ENUM = "E"
  private const val TYPE_RANGE = "R"
  private const val TYPE_RANGE_EXCLUSIVE = "R-E"
  private const val TYPE_RANGE_LOW_EXCLUSIVE = "R-LE"
  private const val TYPE_RANGE_HIGH_EXCLUSIVE = "R-RE"
  private const val TYPE_LESS = "E-LT"
  private const val TYPE_LESS_EQUAL = "E-LTE"
  private const val TYPE_GREATER = "E-GT"
  private const val TYPE_GREATER_EQUAL = "E-GTE"
  private const val TYPE_VALID = "E-VALID"
  private const val TYPE_ONE_OF = "E-ONE"

  private const val INTERSECT = "intersect"

  /** Vega-Lite's own id column, which a point selection stores instead of field values. */
  private const val SELECTION_ID = "_vgsid_"

  /**
   * One entry of a selection: which unit it came from, which fields it constrains, and to what.
   *
   * `fields` and `values` are parallel, which is upstream's shape and not a convenience: an
   * interval brush over two channels is *one* entry with two fields, so a row has to satisfy both
   * to be inside it.
   */
  private class Entry(val unit: String, val fields: List<VegaValue>, val values: List<VegaValue>)

  private fun entriesOf(rows: List<VegaValue>): List<Entry> = rows.mapNotNull { row ->
    val obj = row as? VegaValue.Obj ?: return@mapNotNull null
    val fields = (obj.fields["fields"] as? VegaValue.Arr)?.values ?: return@mapNotNull null
    val values = (obj.fields["values"] as? VegaValue.Arr)?.values ?: return@mapNotNull null
    Entry(obj.fields["unit"]?.asString() ?: "", fields, values)
  }

  /**
   * How many entries each unit contributed, which is upstream's `index:unit`.
   *
   * Upstream keeps it as a dataflow index on the selection dataset; counting the rows gives the
   * same map, and it is only ever asked two questions — how many units there are, and how many
   * entries one of them has.
   */
  private fun unitCounts(rows: List<VegaValue>): Map<String, Int> {
    val counts = LinkedHashMap<String, Int>()
    for (row in rows) {
      val unit = (row as? VegaValue.Obj)?.fields?.get("unit")?.asString() ?: continue
      counts[unit] = (counts[unit] ?: 0) + 1
    }
    return counts
  }

  /** `vlSelectionTest(name, datum, op)` — whether the datum is inside the selection. */
  fun test(rows: List<VegaValue>, datum: VegaValue, op: String?): Boolean {
    val entries = entriesOf(rows)
    val intersect = op == INTERSECT
    val units = if (intersect) unitCounts(rows) else emptyMap()
    // Upstream only takes the per-unit path when the dataflow built it an index, which it does for
    // an intersecting selection and nothing else.
    val perUnit = intersect && units.isNotEmpty()
    val missed = HashMap<String, Int>()
    for (entry in entries) {
      if (perUnit) {
        // A multiple selection unions *within* a unit and intersects *across* units, so a unit that
        // has already matched is skipped and a unit that misses on every one of its entries settles
        // the whole question.
        val count = missed[entry.unit] ?: 0
        if (count == -1) continue
        val hit = testPoint(datum, entry)
        missed[entry.unit] = if (hit) -1 else count + 1
        if (hit && units.size == 1) return true
        if (!hit && count + 1 == units[entry.unit]) return false
      } else {
        val hit = testPoint(datum, entry)
        // `intersect ^ b`: a miss settles an intersection and a hit settles a union.
        if (intersect != hit) return hit
      }
    }
    // Having seen no miss, an intersection is satisfied; a union that saw no match is not. An empty
    // selection is outside either way.
    return entries.isNotEmpty() && intersect
  }

  /** `vlSelectionIdTest(name, datum, op)` — the same question for a selection stored by id. */
  fun idTest(rows: List<VegaValue>, datum: VegaValue, op: String?): Boolean {
    val value = datum.field(SELECTION_ID)
    if (value.isNullish) return false
    val matching = rows.count { it.field(SELECTION_ID) == value }
    if (matching == 0) return false
    if (op == INTERSECT) {
      val units = unitCounts(rows)
      if (units.isEmpty()) return true
      if (units.size == 1) return true
      // Intersecting means every unit has to have selected this id, so fewer matches than units is
      // a miss.
      if (matching < units.size) return false
    }
    return true
  }

  /**
   * `vlSelectionResolve(name, op, isMulti, vl5)` — the selection as fields and values.
   *
   * Two passes, which is upstream's structure and the reason a brush dragged in two views resolves
   * the way it does: entries are unioned **within** a unit first, whatever the operation, and only
   * then combined across units by it.
   */
  fun resolve(
    rows: List<VegaValue>,
    op: String?,
    isMulti: Boolean,
    vl5: Boolean,
  ): VegaValue {
    val resolved = LinkedHashMap<String, LinkedHashMap<String, MutableList<VegaValue>>>()
    val types = LinkedHashMap<String, String>()
    val multi = LinkedHashMap<String, MutableList<VegaValue>>()
    var ids = false

    for (row in rows) {
      val obj = row as? VegaValue.Obj ?: continue
      val unit = obj.fields["unit"]?.asString() ?: ""
      val fields = (obj.fields["fields"] as? VegaValue.Arr)?.values
      val values = (obj.fields["values"] as? VegaValue.Arr)?.values
      if (fields != null && values != null) {
        fields.forEachIndexed { index, definition ->
          val name = definition.field("field").asString()
          val kind = definition.field("type").asString().take(1)
          types[name] = kind
          val byUnit = resolved.getOrPut(name) { LinkedHashMap() }
          val existing = byUnit.getOrPut(unit) { mutableListOf() }
          byUnit[unit] = union(kind, existing, listOf(values.getOrNull(index) ?: VegaValue.Null))
        }
        if (isMulti) {
          val entry = LinkedHashMap<String, VegaValue>()
          fields.forEachIndexed { index, definition ->
            entry[definition.field("field").asString()] = values.getOrNull(index) ?: VegaValue.Null
          }
          multi.getOrPut(unit) { mutableListOf() }.add(VegaValue.Obj(entry))
        }
      } else {
        // A point selection stores ids rather than field values, and each unit's are already
        // sorted.
        ids = true
        val value = obj.fields[SELECTION_ID] ?: VegaValue.Null
        resolved
          .getOrPut(SELECTION_ID) { LinkedHashMap() }
          .getOrPut(unit) { mutableListOf() }
          .add(value)
        if (isMulti) {
          multi
            .getOrPut(unit) { mutableListOf() }
            .add(VegaValue.Obj(linkedMapOf(SELECTION_ID to value)))
        }
      }
    }

    val operation = op ?: "union"
    val out = LinkedHashMap<String, VegaValue>()
    if (ids) {
      val perUnit = resolved[SELECTION_ID]?.values?.toList().orEmpty()
      val combined = perUnit.reduceOrNull { acc, next ->
        if (operation == INTERSECT) acc.filter { it in next }.toMutableList()
        else (acc + next.filterNot { it in acc }).toMutableList()
      }
      out[SELECTION_ID] = VegaValue.Arr(combined.orEmpty())
    } else {
      for ((name, byUnit) in resolved) {
        val kind = types[name] ?: TYPE_ENUM
        val combined =
          byUnit.values.reduceOrNull { acc, next ->
            if (operation == INTERSECT) intersect(kind, acc, next) else union(kind, acc, next)
          }
        out[name] = VegaValue.Arr(combined.orEmpty())
      }
    }

    if (isMulti && multi.isNotEmpty()) {
      // Vega-Lite v5 renamed a "multi" selection to a "point" one; the resolved object carries the
      // new name, which is the only thing this flag decides.
      val key = if (vl5) "vlPoint" else "vlMulti"
      out[key] =
        if (operation == INTERSECT) {
          VegaValue.Obj(
            linkedMapOf(
              "and" to
                VegaValue.Arr(
                  multi.values.map { VegaValue.Obj(linkedMapOf("or" to VegaValue.Arr(it))) }
                )
            )
          )
        } else {
          VegaValue.Obj(linkedMapOf("or" to VegaValue.Arr(multi.values.flatten())))
        }
    }
    return VegaValue.Obj(out)
  }

  /** `vlSelectionTuples(items, base)` — scene items turned into selection entries. */
  fun tuples(items: List<VegaValue>, base: VegaValue): VegaValue {
    val baseFields = (base.field("fields") as? VegaValue.Arr)?.values
    val extra = (base as? VegaValue.Obj)?.fields.orEmpty()
    return VegaValue.Arr(
      items.map { item ->
        val datum = item.field("datum")
        val entry = LinkedHashMap<String, VegaValue>()
        if (baseFields != null) {
          entry["values"] =
            VegaValue.Arr(baseFields.map { datum.field(it.field("field").asString()) })
        } else {
          entry[SELECTION_ID] = datum.field(SELECTION_ID)
        }
        entry.putAll(extra)
        VegaValue.Obj(entry)
      }
    )
  }

  /** One entry against one row: every field it constrains has to hold. */
  private fun testPoint(datum: VegaValue, entry: Entry): Boolean {
    entry.fields.forEachIndexed { index, definition ->
      val name = definition.field("field").asString()
      val kind = definition.field("type").asString()
      val actual = datum.field(name)
      val expected = entry.values.getOrNull(index) ?: VegaValue.Null
      val held =
        when (kind) {
          TYPE_ENUM ->
            if (expected is VegaValue.Arr) expected.values.any { same(it, actual) }
            else same(expected, actual)
          TYPE_RANGE -> inRange(actual, expected, low = true, high = true)
          TYPE_RANGE_HIGH_EXCLUSIVE -> inRange(actual, expected, low = true, high = false)
          TYPE_RANGE_EXCLUSIVE -> inRange(actual, expected, low = false, high = false)
          TYPE_RANGE_LOW_EXCLUSIVE -> inRange(actual, expected, low = false, high = true)
          TYPE_LESS -> number(actual) < number(expected)
          TYPE_LESS_EQUAL -> number(actual) <= number(expected)
          TYPE_GREATER -> number(actual) > number(expected)
          TYPE_GREATER_EQUAL -> number(actual) >= number(expected)
          TYPE_VALID -> !actual.isNullish && !number(actual).isNaN()
          TYPE_ONE_OF -> (expected as? VegaValue.Arr)?.values?.any { same(it, actual) } == true
          else -> true
        }
      if (!held) return false
    }
    return true
  }

  /** A date compares as its instant, which is what upstream's `isDate` coercion arranges. */
  private fun number(value: VegaValue): Double = JsSemantics.toNumber(value)

  private fun same(a: VegaValue, b: VegaValue): Boolean =
    if (a is VegaValue.Timestamp || b is VegaValue.Timestamp) number(a) == number(b)
    else JsSemantics.strictEquals(a, b)

  private fun inRange(value: VegaValue, range: VegaValue, low: Boolean, high: Boolean): Boolean {
    val ends = (range as? VegaValue.Arr)?.values ?: return false
    if (ends.size < 2) return false
    val v = number(value)
    val lo = number(ends[0])
    val hi = number(ends[1])
    if (v.isNaN() || lo.isNaN() || hi.isNaN()) return false
    val (min, max) = if (lo <= hi) lo to hi else hi to lo
    return (if (low) v >= min else v > min) && (if (high) v <= max else v < max)
  }

  /** `E_union` keeps first appearances; `R_union` widens the interval, ends sorted. */
  private fun union(
    kind: String,
    base: MutableList<VegaValue>,
    value: List<VegaValue>,
  ): MutableList<VegaValue> {
    if (kind == TYPE_RANGE) {
      val ends = flatten(value)
      if (ends.size < 2) return base
      val lo = minOf(number(ends[0]), number(ends[1]))
      val hi = maxOf(number(ends[0]), number(ends[1]))
      if (base.isEmpty()) return mutableListOf(VegaValue.Num(lo), VegaValue.Num(hi))
      if (number(base[0]) > lo) base[0] = VegaValue.Num(lo)
      if (number(base[1]) < hi) base[1] = VegaValue.Num(hi)
      return base
    }
    val values = flatten(value)
    if (base.isEmpty()) return values.toMutableList()
    for (entry in values) if (base.none { same(it, entry) }) base.add(entry)
    return base
  }

  private fun intersect(
    kind: String,
    base: MutableList<VegaValue>,
    value: MutableList<VegaValue>,
  ): MutableList<VegaValue> {
    if (kind == TYPE_RANGE) {
      if (value.size < 2) return base
      val lo = minOf(number(value[0]), number(value[1]))
      val hi = maxOf(number(value[0]), number(value[1]))
      if (base.isEmpty()) return mutableListOf(VegaValue.Num(lo), VegaValue.Num(hi))
      // Disjoint intervals intersect to **nothing**, which is an empty array rather than a
      // degenerate one: a scale given it has no domain at all, which is the visible answer.
      if (hi < number(base[0]) || number(base[1]) < lo) return mutableListOf()
      if (number(base[0]) < lo) base[0] = VegaValue.Num(lo)
      if (number(base[1]) > hi) base[1] = VegaValue.Num(hi)
      return base
    }
    if (base.isEmpty()) return value
    return base.filter { entry -> value.any { same(it, entry) } }.toMutableList()
  }

  /** A value that may be one thing or several, as a list — upstream's `array()`. */
  private fun flatten(values: List<VegaValue>): List<VegaValue> = values.flatMap {
    if (it is VegaValue.Arr) it.values else listOf(it)
  }
}
