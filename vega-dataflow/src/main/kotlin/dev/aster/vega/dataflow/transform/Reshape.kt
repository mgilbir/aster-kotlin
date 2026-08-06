package dev.aster.vega.dataflow.transform

import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.field

/**
 * `fold`: turns a set of fields into key/value pairs, one output tuple per field.
 *
 * The original fields are kept alongside the new pair, which is what upstream does — folding is
 * additive, not a projection.
 */
public object FoldTransform : Transform {
  override val type: String = "fold"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val fields = params.stringList("fields")
    if (fields.isEmpty()) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "fold needs 'fields'",
        operator = type,
      )
      return input
    }
    val names = params.stringList("as")
    val keyName = names.getOrNull(0) ?: "key"
    val valueName = names.getOrNull(1) ?: "value"

    return input.flatMap { datum ->
      fields.map { path ->
        datum.withFields(mapOf(keyName to VegaValue.Str(path), valueName to datum.field(path)))
      }
    }
  }
}

/**
 * `flatten`: expands array-valued fields into one tuple per element.
 *
 * Two details verified against upstream:
 * - without `as`, the array field is **replaced** by the element; with `as`, the original array
 *   stays and the element goes to the new field
 * - an empty array produces no tuples at all, so the row disappears
 */
public object FlattenTransform : Transform {
  override val type: String = "flatten"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val fields = params.stringList("fields")
    if (fields.isEmpty()) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "flatten needs 'fields'",
        operator = type,
      )
      return input
    }
    val names = params.stringList("as")
    val indexName = params.string("index")

    return input.flatMap { datum ->
      val arrays = fields.map { (datum.field(it) as? VegaValue.Arr)?.values ?: emptyList() }
      // Parallel arrays flatten together, so the row count is the longest of them.
      val count = arrays.maxOfOrNull { it.size } ?: 0
      (0 until count).map { position ->
        val updates = LinkedHashMap<String, VegaValue>(fields.size + 1)
        fields.forEachIndexed { fieldIndex, path ->
          val target = names.getOrNull(fieldIndex) ?: path
          updates[target] = arrays[fieldIndex].getOrElse(position) { VegaValue.Null }
        }
        indexName?.let { updates[it] = VegaValue.Num(position.toDouble()) }
        datum.withFields(updates)
      }
    }
  }
}
