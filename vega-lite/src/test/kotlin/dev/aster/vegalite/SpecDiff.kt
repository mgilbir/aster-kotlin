package dev.aster.vegalite

import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.canonicalNumberString

/**
 * A structural comparison of two Vega specifications, reported as a list of differences.
 *
 * Object key order is ignored and array order is not, which is the distinction that matters: Vega
 * reads an object's properties by name, so a different order is the same specification, while the
 * order of `data`, `marks` and `axes` decides what is derived from what and what is painted over
 * what.
 *
 * Numbers compare at the harness's canonical precision so that `0.1 + 0.2` and
 * `0.30000000000000004` do not read as a disagreement about the chart.
 */
internal object SpecDiff {

  fun compare(expected: VegaValue, actual: VegaValue, path: String = "$"): List<String> {
    val differences = mutableListOf<String>()
    walk(expected, actual, path, differences)
    return differences
  }

  private fun walk(expected: VegaValue, actual: VegaValue, path: String, out: MutableList<String>) {
    when {
      expected is VegaValue.Obj && actual is VegaValue.Obj -> {
        for (key in expected.fields.keys + actual.fields.keys) {
          val left = expected.fields[key]
          val right = actual.fields[key]
          when {
            left == null -> out += "$path.$key: unexpected ${render(right!!)}"
            right == null -> out += "$path.$key: missing ${render(left)}"
            else -> walk(left, right, "$path.$key", out)
          }
        }
      }
      expected is VegaValue.Arr && actual is VegaValue.Arr -> {
        if (expected.values.size != actual.values.size) {
          out += "$path: ${expected.values.size} entries upstream, ${actual.values.size} here"
        }
        for (index in 0 until minOf(expected.values.size, actual.values.size)) {
          walk(expected.values[index], actual.values[index], "$path[$index]", out)
        }
        for (index in actual.values.size until expected.values.size) {
          out += "$path[$index]: missing ${render(expected.values[index])}"
        }
        for (index in expected.values.size until actual.values.size) {
          out += "$path[$index]: unexpected ${render(actual.values[index])}"
        }
      }
      expected is VegaValue.Num && actual is VegaValue.Num ->
        if (canonicalNumberString(expected.value) != canonicalNumberString(actual.value)) {
          out += "$path: upstream ${render(expected)}, here ${render(actual)}"
        }
      expected != actual -> out += "$path: upstream ${render(expected)}, here ${render(actual)}"
    }
  }

  private fun render(value: VegaValue): String {
    val text =
      when (value) {
        is VegaValue.Str -> "\"${value.value}\""
        is VegaValue.Num -> canonicalNumberString(value.value)
        is VegaValue.Bool -> value.value.toString()
        is VegaValue.Null -> "null"
        else -> dev.aster.vega.model.VegaJson.write(value).replace(Regex("\\s+"), " ")
      }
    return if (text.length > 160) text.take(157) + "..." else text
  }
}
