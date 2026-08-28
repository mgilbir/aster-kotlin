package dev.aster.vega.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * A document too deeply nested to parse is refused **before** the parser sees it.
 *
 * `kotlinx.serialization`'s lexer descends once per `{` or `[`, so a few thousand levels exhaust
 * the thread's stack — and how many thousand depends on the stack the host gave that thread. That
 * is not something this engine can rely on, and the way it was found says why: the same document
 * parsed on a macOS laptop and took the process down on a Linux CI runner, in the same commit.
 *
 * On the JVM a `StackOverflowError` is at least catchable. On Kotlin/Native it is not catchable at
 * all, and `SpecCompiler`'s and `VegaLiteCompiler`'s guards deliberately catch `Exception` rather
 * than `Throwable` — because an `Error` is not a failed compile. So the only answer that works on
 * every target is to refuse before descending, which is what `VegaJson.MAX_JSON_DEPTH` does.
 */
class VegaJsonDepthTest {

  /** `{"a":{"a":…{}…}}` — one object level per step. */
  private fun nested(levels: Int): String = buildString {
    repeat(levels) { append("""{"a":""") }
    append("1")
    repeat(levels) { append("}") }
  }

  @Test
  fun `a document deeper than the limit is a diagnostic, not a crash`() {
    // Well past where the parser gives out on a small stack, and past where it gives out on a large
    // one: the point is that neither number appears here.
    for (levels in listOf(VegaJson.MAX_JSON_DEPTH + 1, 5_000, 100_000)) {
      val collector = DiagnosticCollector()
      assertNull(VegaJson.parseOrNull(nested(levels), collector), "$levels levels must not parse")
      val reported = collector.diagnostics.single()
      assertEquals(DiagnosticCodes.COMPILE_LIMIT_EXCEEDED, reported.code)
      assertEquals(DiagnosticSeverity.FATAL, reported.severity)
      assertTrue(
        reported.message.contains("${VegaJson.MAX_JSON_DEPTH}"),
        "the message should name the limit: ${reported.message}",
      )
    }
  }

  /** The throwing entry point reports the same thing, since `parseOrNull` is built on it. */
  @Test
  fun `the throwing parse refuses it too`() {
    val failure = assertThrows<VegaSpecException> { VegaJson.parse(nested(5_000)) }
    assertEquals(DiagnosticCodes.COMPILE_LIMIT_EXCEEDED, failure.diagnostic.code)
  }

  /**
   * A document at the limit still parses, so the guard is a ceiling rather than a haircut.
   *
   * The deepest document in this repository's own corpus of 761 specifications and references is
   * **twelve** levels, which is where the limit's number comes from: sixteen times the deepest real
   * chart, and far below where the tightest target this runs on gives out — which is `ChartSession`
   * on macOS, at about 450, rather than the JVM parser that first raised the question.
   */
  @Test
  fun `a document at the limit still parses`() {
    assertNotNull(VegaJson.parse(nested(VegaJson.MAX_JSON_DEPTH)))
    assertNotNull(VegaJson.parse(nested(12)))
  }

  /**
   * Braces inside **strings** are characters, not levels.
   *
   * The scan skips string literals, and an escaped quote does not end the string it is in. Without
   * both, a chart whose data holds JSON-ish text — a `label` of `"{{{"`, which is ordinary in a
   * templating context — would be refused for a depth it does not have.
   */
  @Test
  fun `braces inside strings do not count`() {
    val braces = "{".repeat(VegaJson.MAX_JSON_DEPTH * 2)
    val document = """{"label": "$braces", "other": "a \" quote then $braces"}"""
    val parsed = VegaJson.parse(document) as VegaValue.Obj
    assertEquals(VegaValue.Str(braces), parsed.fields["label"])
  }

  /**
   * Text that is not JSON at all still gets the *parser's* message.
   *
   * The depth scan is deliberately not a validator: an unbalanced document is the parser's to
   * describe, and a reader can act on "Unexpected JSON token" where they cannot act on a depth
   * complaint about something that was never JSON.
   */
  @Test
  fun `malformed json still reports as malformed`() {
    val collector = DiagnosticCollector()
    assertNull(VegaJson.parseOrNull("{ not json", collector))
    assertEquals(DiagnosticCodes.PARSE_INVALID_JSON, collector.diagnostics.single().code)
  }
}
