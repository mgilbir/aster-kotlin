package dev.aster.vega.expression

import io.github.mgilbir.ecma262.text.isEcmaIdentifierName

/** A lexical token, with its source offset so a parse error can point at the right character. */
public data class Token(val type: TokenType, val text: String, val start: Int) {
  override fun toString(): String = "$type('$text')@$start"
}

public enum class TokenType {
  NUMBER,
  STRING,
  IDENTIFIER,
  OPERATOR,
  PUNCTUATION,
  END,
}

public class ExpressionSyntaxException(
  message: String,
  public val offset: Int,
  public val source: String,
) : Exception("$message at offset $offset in \"$source\"")

/**
 * Tokenizer for Vega's expression language.
 *
 * The language is a JavaScript expression subset: literals, identifiers, member access, calls, and
 * the usual unary, binary, logical, bitwise and conditional operators. There are no statements, no
 * assignment, and no function definitions — which is what makes evaluating it without `eval` or
 * code generation tractable (PROJECT_BRIEF.md 6.1).
 *
 * Deliberately not implemented: regular-expression literals and template strings. Both are reported
 * as syntax errors rather than mis-tokenized.
 */
public class Lexer(private val source: String) {

  private var index = 0

  public fun tokenize(): List<Token> {
    val tokens = mutableListOf<Token>()
    while (true) {
      val token = next()
      tokens.add(token)
      if (token.type == TokenType.END) return tokens
    }
  }

  private fun next(): Token {
    skipWhitespace()
    if (index >= source.length) return Token(TokenType.END, "", index)

    val start = index
    val ch = source[index]
    return when {
      ch.isDigit() || (ch == '.' && index + 1 < source.length && source[index + 1].isDigit()) ->
        number(start)
      ch == '"' || ch == '\'' -> string(start, ch)
      isIdentifierStart(ch) -> identifier(start)
      ch in PUNCTUATION -> {
        index++
        Token(TokenType.PUNCTUATION, ch.toString(), start)
      }
      else -> operator(start)
    }
  }

  private fun skipWhitespace() {
    while (index < source.length && isJsWhitespace(source[index])) index++
  }

  /**
   * JavaScript's *WhiteSpace* and *LineTerminator*, which are not Kotlin's.
   *
   * The one that matters in practice is the **no-break space**. `Char.isWhitespace` excludes U+00A0
   * on purpose — it is not a separator when you are breaking text into words — and JavaScript's
   * grammar includes it, so an expression copied out of a rendered web page failed with `Unexpected
   * character ' '` and no way to see what the character was. U+FEFF is the other one worth naming:
   * it is what a UTF-8 byte-order mark decodes to, so a specification saved with one had an
   * unlexable first token.
   */
  private fun isJsWhitespace(ch: Char): Boolean =
    when (ch) {
      '\u0009',
      '\u000B',
      '\u000C',
      '\u00A0',
      '\uFEFF',
      '\n',
      '\r',
      '\u2028',
      '\u2029' -> true
      else -> ch.category == CharCategory.SPACE_SEPARATOR
    }

  private fun number(start: Int): Token {
    // Hex and binary literals are legal JavaScript and appear in colour arithmetic.
    if (
      source[index] == '0' && index + 1 < source.length && source[index + 1].lowercaseChar() in "xb"
    ) {
      index += 2
      while (index < source.length && (source[index].isLetterOrDigit())) index++
      return Token(TokenType.NUMBER, source.substring(start, index), start)
    }
    var seenDot = false
    var seenExponent = false
    while (index < source.length) {
      val c = source[index]
      when {
        c.isDigit() -> index++
        c == '.' && !seenDot && !seenExponent -> {
          seenDot = true
          index++
        }
        (c == 'e' || c == 'E') && !seenExponent -> {
          seenExponent = true
          index++
          if (index < source.length && (source[index] == '+' || source[index] == '-')) index++
          if (index >= source.length || !source[index].isDigit()) {
            throw ExpressionSyntaxException("Malformed exponent", index, source)
          }
        }
        else -> break
      }
    }
    return Token(TokenType.NUMBER, source.substring(start, index), start)
  }

  private fun string(start: Int, quote: Char): Token {
    index++ // opening quote
    val text = StringBuilder()
    while (true) {
      if (index >= source.length) {
        throw ExpressionSyntaxException("Unterminated string", start, source)
      }
      val c = source[index]
      when {
        c == quote -> {
          index++
          return Token(TokenType.STRING, text.toString(), start)
        }
        c == '\\' -> {
          index++
          if (index >= source.length) {
            throw ExpressionSyntaxException("Unterminated escape", index, source)
          }
          appendEscape(text)
        }
        else -> {
          text.append(c)
          index++
        }
      }
    }
  }

  /**
   * One escape sequence, appended to [text], with [index] left after it.
   *
   * Three of JavaScript's forms were missing and each of them **silently produced the wrong text**,
   * because the fallback is the identity: `'\x41'` came out as `"x41"` rather than `"A"`,
   * `'\u{1F600}'` as `"u{1F600}"`, and a backslash at the end of a line — a *line continuation*,
   * which is how a long pattern is written across two lines — put the newline into the string.
   * Nothing reported any of it, so a label was simply wrong.
   *
   * A whole code point, not a character, because `\u{...}` above U+FFFF is a surrogate pair.
   */
  private fun appendEscape(text: StringBuilder) {
    when (val c = source[index]) {
      'n' -> {
        text.append('\n')
        index++
      }
      't' -> {
        text.append('\t')
        index++
      }
      'r' -> {
        text.append('\r')
        index++
      }
      'b' -> {
        text.append('\b')
        index++
      }
      'f' -> {
        text.append('\u000C')
        index++
      }
      'v' -> {
        text.append('\u000B')
        index++
      }
      '0' -> {
        text.append('\u0000')
        index++
      }
      // A *LineContinuation*: the backslash and the line terminator both vanish. `\r\n` is one
      // terminator, not two.
      '\n',
      '\u2028',
      '\u2029' -> index++
      '\r' -> {
        index++
        if (index < source.length && source[index] == '\n') index++
      }
      'x' -> {
        val code = hexAt(index + 1, 2) ?: throw invalidEscape("x", index, 2)
        text.append(code.toChar())
        index += 3
      }
      'u' -> {
        if (index + 1 < source.length && source[index + 1] == '{') {
          val close = source.indexOf('}', index + 2)
          val digits = if (close < 0) null else source.substring(index + 2, close)
          val code = digits?.takeIf { it.isNotEmpty() }?.toIntOrNull(16)
          if (code == null || code > 0x10FFFF) {
            throw ExpressionSyntaxException("Invalid unicode escape", index, source)
          }
          text.appendCodePointCompat(code)
          index = close + 1
        } else {
          // \uXXXX; the four hex digits follow, so consume them here.
          val code = hexAt(index + 1, 4) ?: throw invalidEscape("u", index, 4)
          text.append(code.toChar())
          index += 5
        }
      }
      else -> {
        text.append(c)
        index++
      }
    }
  }

  /** [count] hexadecimal digits starting at [from], or null when they are not all there. */
  private fun hexAt(from: Int, count: Int): Int? {
    if (from + count > source.length) return null
    return source.substring(from, from + count).toIntOrNull(16)
  }

  private fun invalidEscape(marker: String, at: Int, digits: Int) =
    ExpressionSyntaxException(
      "Invalid escape '\\$marker': it takes $digits hexadecimal digits",
      at,
      source,
    )

  private fun isIdentifierStart(ch: Char): Boolean = IdentifierChars.isStart(ch)

  private fun isIdentifierPart(ch: Char): Boolean = IdentifierChars.isPart(ch)

  private fun identifier(start: Int): Token {
    while (index < source.length && isIdentifierPart(source[index])) {
      index++
    }
    return Token(TokenType.IDENTIFIER, source.substring(start, index), start)
  }

  private fun operator(start: Int): Token {
    // Longest match first, so `===` is not read as `==` then `=`.
    for (candidate in OPERATORS) {
      if (source.startsWith(candidate, index)) {
        index += candidate.length
        return Token(TokenType.OPERATOR, candidate, start)
      }
    }
    throw ExpressionSyntaxException("Unexpected character '${source[index]}'", index, source)
  }

  private companion object {
    val PUNCTUATION = "()[]{},:.".toSet()

    /** Ordered longest-first; membership and order both matter. */
    val OPERATORS =
      listOf(
        ">>>",
        "===",
        "!==",
        "==",
        "!=",
        "<=",
        ">=",
        "&&",
        "||",
        "<<",
        ">>",
        "+",
        "-",
        "*",
        "/",
        "%",
        "<",
        ">",
        "!",
        "~",
        "&",
        "|",
        "^",
        "?",
      )
  }
}

/**
 * Which characters may spell an identifier, answered by **ktecma262** rather than approximated.
 *
 * A field is reached through `datum.name`, so this grammar decides which columns an expression can
 * see at all. It used to be a letter-or-digit test, which rejected a decomposed `café`, a letter
 * number like `Ⅷ`, and the zero-width joiners several scripts need — all of which upstream accepts.
 *
 * Reading the Unicode categories directly instead gets closer and still does not arrive: `ID_Start`
 * and `ID_Continue` carry **`Other_ID_Start`** and **`Other_ID_Continue`** on top of the categories
 * — the middle dot, the Ethiopic digits, two Mongolian letters — and they move with the Unicode
 * version the platform happens to ship. Comparing the two found 86 characters where a category test
 * and the specification disagree.
 *
 * The library answers for a whole string, so each character is asked once and remembered. Four
 * arrays of the Basic Multilingual Plane — a known flag and an answer, for each of the two
 * questions — is a quarter of a megabyte, filled only where a specification actually reaches; a
 * lexer runs this per character and cannot afford to allocate.
 */
private object IdentifierChars {
  private const val PLANE = 0x10000
  private val startKnown = BooleanArray(PLANE)
  private val startValue = BooleanArray(PLANE)
  private val partKnown = BooleanArray(PLANE)
  private val partValue = BooleanArray(PLANE)

  fun isStart(ch: Char): Boolean {
    val at = ch.code
    if (!startKnown[at]) {
      startValue[at] = ch.toString().isEcmaIdentifierName()
      startKnown[at] = true
    }
    return startValue[at]
  }

  fun isPart(ch: Char): Boolean {
    val at = ch.code
    if (!partKnown[at]) {
      // Asked as a *continuation*, which is a different set: a digit continues a name and cannot
      // begin one.
      partValue[at] = ("a" + ch).isEcmaIdentifierName()
      partKnown[at] = true
    }
    return partValue[at]
  }
}

/**
 * `StringBuilder.appendCodePoint`, which the common standard library does not have.
 *
 * A code point above U+FFFF is a surrogate pair, and `\u{1F600}` is how a specification writes one.
 */
private fun StringBuilder.appendCodePointCompat(code: Int) {
  if (code <= 0xFFFF) {
    append(code.toChar())
  } else {
    val shifted = code - 0x10000
    append((0xD800 + (shifted shr 10)).toChar())
    append((0xDC00 + (shifted and 0x3FF)).toChar())
  }
}
