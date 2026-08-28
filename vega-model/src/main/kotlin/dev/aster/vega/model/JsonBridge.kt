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

  /**
   * How deeply JSON may nest before this refuses to parse it.
   *
   * **The parser recurses, and a `StackOverflowError` is not catchable everywhere.** kotlinx's
   * lexer descends once per `{` or `[`, so a document nested a few thousand deep exhausts the
   * thread's stack — and how deep "a few thousand" is depends on the stack the host happened to
   * give the thread. That is not a number this engine can rely on: the same document survived on a
   * macOS laptop and took the process down on a Linux CI runner, in the same commit.
   *
   * On the JVM the overflow is at least catchable; on Kotlin/Native it is not catchable at all. So
   * this is checked **before** the parser is handed the text, by a scan that does not recurse —
   * which is the only shape of answer that works on every target.
   *
   * The number is derived rather than chosen, from three measurements that bracket it:
   *
   * - the deepest document in this repository's own corpus of 761 specifications and references is
   *   **twelve** levels, so this is sixteen times any real chart;
   * - a document that reaches `ScopeCompiler.MAX_GROUP_DEPTH` has to be *expressible*, and a group
   *   mark costs about two JSON levels — so anything below about 130 would make that limit dead
   *   code, refused by the parser before the compiler could report it;
   * - and the ceiling is the platform with the least stack. It was 512, which was measured against
   *   the JVM and is **wrong for Apple**: a document that parses is one the compiler then walks,
   *   and through `ChartSession` on macOS that walk dies at about 450 where the JVM survives 511.
   *   The parse bound has to leave room for everything downstream of it, on the tightest target,
   *   not on the one the number was first tried on.
   */
  public const val MAX_JSON_DEPTH: Int = 192

  /** @throws VegaSpecException if [text] is not valid JSON, or nests past [MAX_JSON_DEPTH]. */
  public fun parse(text: String): VegaValue {
    depthOf(text)?.let { depth ->
      throw VegaSpecException(
        VegaDiagnostic(
          severity = DiagnosticSeverity.FATAL,
          code = DiagnosticCodes.COMPILE_LIMIT_EXCEEDED,
          message =
            "This document nests more than $MAX_JSON_DEPTH levels deep (reached $depth), which is " +
              "further than the JSON parser can descend without running out of stack. The deepest " +
              "chart anyone draws is about a dozen.",
        )
      )
    }
    return parseChecked(text)
  }

  /**
   * The depth at which [text] passes [MAX_JSON_DEPTH], or null when it never does.
   *
   * A character scan with a counter, because a function whose job is deciding whether something is
   * too deep to recurse over must not itself recurse over it. Strings are skipped: a `{` inside one
   * is a character, not a level, and `"\""` does not end the string it is in.
   *
   * Deliberately not a validator. Text that is not JSON at all reaches the parser and gets the
   * parser's own message, which is the one a reader can act on.
   */
  private fun depthOf(text: String): Int? {
    var depth = 0
    var index = 0
    var inString = false
    while (index < text.length) {
      val ch = text[index]
      if (inString) {
        if (ch == '\\') index++ else if (ch == '"') inString = false
      } else {
        when (ch) {
          '"' -> inString = true
          '{',
          '[' -> {
            depth++
            if (depth > MAX_JSON_DEPTH) return depth
          }
          '}',
          ']' -> depth--
        }
      }
      index++
    }
    return null
  }

  private fun parseChecked(text: String): VegaValue =
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
    // `JSON.stringify` drops an object property whose value is `undefined` and writes `null` for
    // an array element that is one. Neither can arrive here — nothing that compiles into a
    // specification holds an `undefined`, which only an expression produces — and `null` is the
    // answer that keeps the document readable if one ever does.
    is VegaValue.Undefined -> out.append("null")
    is VegaValue.Bool -> out.append(value.value)
    is VegaValue.Num -> out.append(jsonNumber(value.value))
    is VegaValue.Timestamp -> out.append(jsonNumber(value.epochMillis))
    is VegaValue.Str -> writeJsonString(value.value, out)
    // `JSON.stringify(/a.b/i)` is `{}`: a regular expression has no enumerable properties of its
    // own, so JavaScript writes the empty object and loses it. This writer exists to say what
    // JavaScript says, and a pattern is a *runtime* value in any case — nothing that compiles into
    // a specification can hold one.
    is VegaValue.Pattern -> out.append("{}")
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
 * JavaScript's `JSON.stringify` number rules: a non-finite value becomes `null` — which is what
 * JSON permits and what upstream writes — and every other one is written as `String(x)`.
 *
 * `String(x)` is [Decimals.jsString], not the platform's `toString`. The rules it applies are not
 * the ones a platform picks: an integral value loses its point up to 10^21 and goes exponential
 * above it, a small one stays in full down to 10^-7 and goes exponential below, and the digits in
 * between are the *fewest* that read back as the same double. Kotlin's `toString` switches to
 * exponential at 10^7, writes `1.5E-6` where JavaScript writes `0.0000015`, and — being the
 * platform's — answers differently on each Kotlin/Native target, so this JSON was not even the same
 * text on two of the five hosts that emit it.
 */
private fun jsonNumber(value: Double): String =
  if (!value.isFinite()) "null" else Decimals.jsString(value)

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
