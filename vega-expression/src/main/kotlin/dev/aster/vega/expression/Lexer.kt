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
  /**
   * A regular-expression literal, `/pattern/flags`.
   *
   * [Token.text] is the **whole literal**, delimiters and flags included, rather than the pattern
   * alone: `Token` is a public data class and a second payload field would widen it for every token
   * type to serve one. The closing delimiter is the last `/` in the text — flags are letters — so
   * the split is unambiguous, and `Parser` does it.
   */
  REGEX,
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
 * code generation tractable.
 *
 * **Regular-expression literals are lexed**, into the same `VegaValue.Pattern` that `regexp()`
 * produces. They were excluded once, on the argument that there was no engine to hand a pattern to;
 * `ktecma262` is that engine and the exclusion outlived it. Upstream's own parser scans them — its
 * message table carries `Invalid regular expression: missing /` — so an expression written against
 * Vega, `replace(datum.label, / #\d+$/, '')`, is one this engine now reads rather than refuses.
 * See #153.
 *
 * Deliberately not implemented: template strings, which are reported as syntax errors rather than
 * mis-tokenized.
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

  /**
   * The token before the one being read, which is the only thing that says what a `/` means.
   *
   * `a / b` divides and `replace(s, /x/, '')` does not, and no amount of looking at the `/` itself
   * tells them apart — JavaScript resolves it by what came *before*. So the lexer carries one token
   * of history. [tokenize] is the only caller of [next], so this is a linear scan's worth of state
   * rather than lookahead.
   */
  private var previous: Token? = null

  private fun next(): Token {
    val token = read()
    previous = token
    return token
  }

  private fun read(): Token {
    skipWhitespace()
    if (index >= source.length) return Token(TokenType.END, "", index)

    val start = index
    val ch = source[index]
    return when {
      ch.isDigit() || (ch == '.' && index + 1 < source.length && source[index + 1].isDigit()) ->
        number(start)
      ch == '"' || ch == '\'' -> string(start, ch)
      ch == '/' && regexCanStartHere() -> regex(start)
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

  /**
   * ECMA-262's *LineTerminator*, which a regular-expression literal may not contain.
   *
   * A subset of [isJsWhitespace] and named separately because the two answer different questions:
   * whitespace between tokens is skipped, a line terminator inside a literal ends the literal's
   * chance of being one. Without it an unterminated `/` swallows the rest of the source and the
   * complaint arrives with an offset nowhere near the mistake.
   *
   * **Written here rather than taken from `ktecma262`**, which is where it belongs: that library
   * holds the *WhiteSpace* table but `isEcmaWhiteSpace` is `internal`, and it has no
   * *LineTerminator* predicate at all, though `isEcmaIdentifierName` beside them is public and this
   * file uses it. Asked for as ktecma262#5. Four code points, so the copy is small — but it is a
   * copy of a spec table that library already holds, which is the reason to say so.
   */
  private fun isJsLineTerminator(ch: Char): Boolean =
    ch == '\n' || ch == '\r' || ch == '\u2028' || ch == '\u2029'

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

  /**
   * Whether a `/` at [index] opens a regular-expression literal rather than dividing.
   *
   * The rule is JavaScript's and it is about the **previous token**: a `/` divides when what came
   * before it could end an expression, and starts a literal when it could not. `a / b` divides
   * because `a` is a value; `replace(s, /x/, '')` does not, because a comma cannot end one.
   *
   * The whole rule fits here because this language has no *word* operators. In JavaScript `typeof`
   * and `in` and `return` are identifiers that cannot end an expression, so `typeof /re/` needs a
   * keyword list to get right. Vega's expression language has no statements and no word operators —
   * `PUNCTUATION` is `()[]{},:.` and everything else is symbols — so an identifier here is always a
   * value and always means division.
   *
   * A regular expression can itself be divided (`/a/.source / 2` via the member, or `/a/ / 2`
   * literally), so [TokenType.REGEX] ends an expression like any other value.
   */
  private fun regexCanStartHere(): Boolean {
    val before = previous ?: return true // Nothing before it: the expression starts here.
    return when (before.type) {
      TokenType.NUMBER,
      TokenType.STRING,
      TokenType.IDENTIFIER,
      TokenType.REGEX -> false
      // A closing bracket ends a value; an opening one, a comma, a colon or a dot does not.
      TokenType.PUNCTUATION -> before.text !in CLOSERS
      // Every operator here is prefix or infix, so a value has to follow it.
      TokenType.OPERATOR -> true
      TokenType.END -> true
    }
  }

  /**
   * `/pattern/flags`, scanned to its closing delimiter.
   *
   * Three things make the closing `/` hard to find, and all three are ECMA-262's:
   * - `\` escapes the character after it, so `/a\/b/` has one delimiter at each end and a literal
   *   slash in the middle.
   * - `[…]` is a character class, and a `/` inside one is literal: `/[/]/` is a pattern matching a
   *   slash, not an empty pattern followed by junk.
   * - a line terminator may not appear in the body at all, which is what stops an unterminated
   *   literal from swallowing the rest of a document.
   *
   * The pattern itself is *not* validated here. That is `ktecma262`'s job and it happens in
   * [Parser], so a bad pattern is reported by the engine that will run it rather than by a second
   * opinion this file would have to keep in step.
   */
  private fun regex(start: Int): Token {
    index++ // opening delimiter
    var inClass = false
    while (true) {
      if (index >= source.length) {
        // Upstream's parser has this message verbatim, and it is the accurate one: the body is
        // fine, the delimiter is missing.
        throw ExpressionSyntaxException("Invalid regular expression: missing /", start, source)
      }
      val c = source[index]
      when {
        isJsLineTerminator(c) ->
          throw ExpressionSyntaxException("Invalid regular expression: missing /", start, source)
        c == '\\' -> {
          index++
          if (index >= source.length || isJsLineTerminator(source[index])) {
            throw ExpressionSyntaxException("Invalid regular expression: missing /", start, source)
          }
          index++
        }
        c == '[' -> {
          inClass = true
          index++
        }
        c == ']' -> {
          inClass = false
          index++
        }
        c == '/' && !inClass -> {
          // An empty body is `//`, which is not a pattern in any dialect. Named here rather than
          // left to the regular-expression engine, whose complaint about an empty source reads as
          // an engine limitation instead of a typo.
          if (index == start + 1) {
            throw ExpressionSyntaxException("Invalid regular expression: empty", start, source)
          }
          index++
          // Flags are letters and there is no separator, so they run to the first character that is
          // not one. Which letters are legal is `ktecma262`'s question, asked in `Parser`.
          while (index < source.length && source[index].isLetter()) index++
          return Token(TokenType.REGEX, source.substring(start, index), start)
        }
        else -> index++
      }
    }
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

    /**
     * The punctuation that can *end* a value, which is what decides a following `/`.
     *
     * `)` and `]` and `}` close a call, an index or an array, and an object literal — all of which
     * produce a value, so a `/` after one divides. Every other member of [PUNCTUATION] opens
     * something or separates two things, and a value has to follow it.
     */
    val CLOSERS = setOf(")", "]", "}")

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
