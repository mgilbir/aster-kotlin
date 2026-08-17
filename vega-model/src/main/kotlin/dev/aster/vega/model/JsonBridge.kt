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
