package dev.aster.vega.expression

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
      ch.isLetter() || ch == '_' || ch == '$' -> identifier(start)
      ch in PUNCTUATION -> {
        index++
        Token(TokenType.PUNCTUATION, ch.toString(), start)
      }
      else -> operator(start)
    }
  }

  private fun skipWhitespace() {
    while (index < source.length && source[index].isWhitespace()) index++
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
          text.append(unescape(source[index]))
          index++
        }
        else -> {
          text.append(c)
          index++
        }
      }
    }
  }

  private fun unescape(c: Char): Char =
    when (c) {
      'n' -> '\n'
      't' -> '\t'
      'r' -> '\r'
      'b' -> '\b'
      'f' -> '\u000C'
      'v' -> '\u000B'
      '0' -> '\u0000'
      'u' -> {
        // \uXXXX; the four hex digits follow, so consume them here.
        if (index + 4 >= source.length) {
          throw ExpressionSyntaxException("Truncated unicode escape", index, source)
        }
        val hex = source.substring(index + 1, index + 5)
        val code =
          hex.toIntOrNull(16)
            ?: throw ExpressionSyntaxException("Invalid unicode escape '\\u$hex'", index, source)
        index += 4
        code.toChar()
      }
      else -> c
    }

  private fun identifier(start: Int): Token {
    while (
      index < source.length &&
        (source[index].isLetterOrDigit() || source[index] == '_' || source[index] == '$')
    ) {
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
