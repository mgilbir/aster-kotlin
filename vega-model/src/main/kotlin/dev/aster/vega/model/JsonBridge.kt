package dev.aster.vega.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Converts JSON text into the runtime's [VegaValue] model.
 *
 * `kotlinx.serialization` only appears here and in the specification models; the rest of the engine
 * works on [VegaValue] so that nothing downstream depends on a JSON library.
 */
public object VegaJson {
  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = false
    allowSpecialFloatingPointValues = true
  }

  /** @throws VegaSpecException if [text] is not valid JSON. */
  public fun parse(text: String): VegaValue =
    try {
      json.parseToJsonElement(text).toVegaValue()
    } catch (e: Exception) {
      throw VegaSpecException(
        VegaDiagnostic(
          severity = DiagnosticSeverity.FATAL,
          code = DiagnosticCodes.PARSE_INVALID_JSON,
          message = "Specification is not valid JSON: ${e.message}",
          cause = e,
        )
      )
    }

  public fun parseOrNull(text: String, diagnostics: DiagnosticCollector): VegaValue? =
    try {
      parse(text)
    } catch (e: VegaSpecException) {
      diagnostics.add(e.diagnostic)
      null
    }

  /**
   * Writes a value back out as JSON text, indented two spaces and in insertion order.
   *
   * This exists for specifications that the engine *produces* rather than reads — a Vega-Lite
   * compilation is one — so that the result can be written to a file, diffed against upstream's, or
   * handed to a renderer. Nothing in the drawing path uses it.
   */
  public fun write(value: VegaValue): String = buildString { writeJson(value, this, 0) }
}

private fun writeJson(value: VegaValue, out: StringBuilder, depth: Int) {
  val pad = "  ".repeat(depth + 1)
  val closePad = "  ".repeat(depth)
  when (value) {
    is VegaValue.Null -> out.append("null")
    is VegaValue.Bool -> out.append(value.value)
    is VegaValue.Num -> out.append(jsonNumber(value.value))
    is VegaValue.Timestamp -> out.append(jsonNumber(value.epochMillis))
    is VegaValue.Str -> writeJsonString(value.value, out)
    is VegaValue.Arr ->
      if (value.values.isEmpty()) {
        out.append("[]")
      } else {
        out.append("[\n")
        value.values.forEachIndexed { index, element ->
          if (index > 0) out.append(",\n")
          out.append(pad)
          writeJson(element, out, depth + 1)
        }
        out.append('\n').append(closePad).append(']')
      }
    is VegaValue.Obj ->
      if (value.fields.isEmpty()) {
        out.append("{}")
      } else {
        out.append("{\n")
        var first = true
        for ((key, field) in value.fields) {
          if (!first) out.append(",\n")
          first = false
          out.append(pad)
          writeJsonString(key, out)
          out.append(": ")
          writeJson(field, out, depth + 1)
        }
        out.append('\n').append(closePad).append('}')
      }
  }
}

/**
 * JavaScript's `JSON.stringify` number rules: an integral value loses its `.0`, and a non-finite
 * one becomes `null` — which is what JSON permits and what upstream writes.
 */
private fun jsonNumber(value: Double): String =
  when {
    !value.isFinite() -> "null"
    value == 0.0 -> "0"
    value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e21 -> value.toLong().toString()
    else -> value.toString()
  }

private fun writeJsonString(text: String, out: StringBuilder) {
  out.append('"')
  for (ch in text) {
    when (ch) {
      '"' -> out.append("\\\"")
      '\\' -> out.append("\\\\")
      '\n' -> out.append("\\n")
      '\r' -> out.append("\\r")
      '\t' -> out.append("\\t")
      '\b' -> out.append("\\b")
      '\u000C' -> out.append("\\f")
      else ->
        if (ch < ' ') {
          out.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
        } else {
          out.append(ch)
        }
    }
  }
  out.append('"')
}

public fun JsonElement.toVegaValue(): VegaValue =
  when (this) {
    is JsonNull -> VegaValue.Null
    is JsonPrimitive -> toVegaValue()
    is JsonArray -> VegaValue.Arr(map { it.toVegaValue() })
    is JsonObject ->
      VegaValue.Obj(
        LinkedHashMap<String, VegaValue>(size).also {
          for ((key, value) in this) it[key] = value.toVegaValue()
        }
      )
  }

private fun JsonPrimitive.toVegaValue(): VegaValue {
  if (isString) return VegaValue.Str(content)
  booleanOrNull?.let {
    return VegaValue.Bool(it)
  }
  doubleOrNull?.let {
    return VegaValue.Num(it)
  }
  // A non-string primitive that is neither boolean nor numeric can only be a malformed literal;
  // keep the raw text rather than dropping data.
  return VegaValue.Str(content)
}
