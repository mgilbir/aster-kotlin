package dev.aster.vega.expression

import io.github.mgilbir.ecma262.text.isEcmaIdentifierName
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which characters may spell a field name, checked against **ktecma262**'s own grammar.
 *
 * A field is reached through `datum.name`, so the identifier grammar decides which columns an
 * expression can see at all. This engine used to test for a letter or a digit, which is narrower
 * than ECMA-262's `UnicodeIDStart`/`UnicodeIDContinue` in ways that reach real data: a decomposed
 * `café`, a name carrying a combining mark, a letter number like `Ⅷ`, or the zero-width joiners
 * several scripts need to spell words correctly.
 *
 * The library answers the same question for a whole string, so the two are compared over the
 * **entire Basic Multilingual Plane** — every character, as a start and as a continuation. That is
 * cheaper than reasoning about Unicode categories and it is the specification's own answer rather
 * than a second reading of it.
 */
class IdentifierGrammarTest {

  /** Whether the lexer reads `text` as one identifier and nothing else. */
  private fun lexesAsOneIdentifier(text: String): Boolean {
    val tokens = runCatching { Lexer(text).tokenize() }.getOrNull() ?: return false
    // A trailing end-of-input token is expected; anything more means the lexer split the name.
    val words = tokens.filter { it.type != TokenType.END }
    return words.size == 1 && words[0].type == TokenType.IDENTIFIER && words[0].text == text
  }

  @Test
  fun `the lexer's identifier grammar is the language's`() {
    val disagreements = mutableListOf<String>()
    for (code in 0..0xFFFF) {
      val ch = code.toChar()
      // Surrogates are only meaningful in pairs, and neither side reads a lone one as a name.
      if (ch.isSurrogate()) continue

      val asStart = ch.toString()
      val expectedStart = asStart.isEcmaIdentifierName()
      if (expectedStart != lexesAsOneIdentifier(asStart)) {
        disagreements += "start U+${code.toString(16).uppercase().padStart(4, '0')}"
      }

      val asPart = "a" + ch
      val expectedPart = asPart.isEcmaIdentifierName()
      if (expectedPart != lexesAsOneIdentifier(asPart)) {
        disagreements += "part U+${code.toString(16).uppercase().padStart(4, '0')}"
      }
    }
    assertEquals(
      emptyList<String>(),
      disagreements.take(20),
      "${disagreements.size} characters are read differently from the specification",
    )
  }
}
