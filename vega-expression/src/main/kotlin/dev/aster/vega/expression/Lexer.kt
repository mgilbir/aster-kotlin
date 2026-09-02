package dev.aster.vega.expression

import io.github.mgilbir.ecma262.lexer.decodeEscapeSequence
import io.github.mgilbir.ecma262.lexer.scanRegExpLiteral
import io.github.mgilbir.ecma262.text.isEcmaIdentifierName
import io.github.mgilbir.ecma262.text.isEcmaLineTerminator
import io.github.mgilbir.ecma262.text.isEcmaWhiteSpace

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
   * JavaScript's *WhiteSpace* **and** *LineTerminator* — two productions, one question here, since
   * everything between two tokens is skipped whichever it is.
   *
   * Both tables come from `ktecma262`. They were written out here — nine code points and a category
   * check — because `isEcmaWhiteSpace` was `internal` and no *LineTerminator* predicate existed.
   * Both were asked for (ktecma262#5) and both arrived in 0.3.0, so this is the library's answer
   * rather than a second copy of a specification table kept in step by hand.
   *
   * The two that motivated writing it out are still worth naming: U+00A0, which `Char.isWhitespace`
   * excludes on purpose and JavaScript's grammar includes — an expression copied out of a rendered
   * web page failed on it — and U+FEFF, which is what a byte-order mark decodes to, so a
   * specification saved with one had an unlexable first token.
   */
  private fun isJsWhitespace(ch: Char): Boolean = isEcmaWhiteSpace(ch) || isEcmaLineTerminator(ch)

  private fun number(start: Int): Token {
    // Hex, octal and binary literals are legal JavaScript; hex is what colour arithmetic is
    // written with. `o` was missing, so `0o17` lexed as `0` followed by an identifier and failed
    // to parse where a browser reads 15 (#155).
    if (
      source[index] == '0' &&
        index + 1 < source.length &&
        source[index + 1].lowercaseChar() in "xob"
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
   * `/pattern/flags`, scanned by `ktecma262`.
   *
   * Finding the closing delimiter is not a substring search. A backslash escapes the next
   * character, so `/a\/b/` has a literal slash between its delimiters; `[...]` is a character class
   * and a `/` inside one is literal, so `/[/]/` matches a slash; and a *LineTerminator* may not
   * appear in the body at all, which is what stops an unterminated literal swallowing the rest of a
   * document.
   *
   * Those are the same three facts `ktecma262`'s regular-expression parser already tracks while
   * parsing a pattern, so deriving them here was deriving a subset of it. Asked for as ktecma262#6
   * and answered in 0.3.0 by [scanRegExpLiteral], which takes a source and an offset and returns
   * the pattern, the flags, and where the literal ended.
   *
   * What stays here is the part that is this *language's* rather than ECMA-262's: whether a `/` at
   * this offset opens a literal at all, which depends on the token before it. See
   * [regexCanStartHere].
   *
   * The pattern is still not validated here — `VegaValue.Pattern` compiles it through `RegExp`, so
   * an unreadable one is refused by the engine that would have run it.
   */
  private fun regex(start: Int): Token {
    val scan =
      scanRegExpLiteral(source, start)
        // Upstream's own parser carries this message, and it is the accurate one: the body scanned
        // fine, the delimiter that would have closed it is not there.
        ?: throw ExpressionSyntaxException("Invalid regular expression: missing /", start, source)
    index = scan.end
    return Token(TokenType.REGEX, source.substring(start, scan.end), start)
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
   * One escape sequence, decoded by `ktecma262`.
   *
   * `\n`, `\t`, `\xHH`, `\uHHHH`, `\u{...}`, identity escapes, and *LineContinuation* — where the
   * backslash and the line terminator both vanish and a CRLF counts as one. None of that is
   * guessable from the character after the backslash without the table, and two rules are easy to
   * get subtly wrong: `\0` is a NUL only when no digit follows it, and `\u{...}` may name a
   * supplementary code point that has to become a surrogate pair.
   *
   * That table lived here. It is ECMA-262 §12.9.4 and belongs beside the identifier and whitespace
   * tables the same library already owns — asked for as ktecma262#8, answered in 0.3.0 by
   * [decodeEscapeSequence].
   */
  private fun appendEscape(text: StringBuilder) {
    // The library wants the backslash's own offset; `index` is sitting on the character after it.
    val decoded =
      decodeEscapeSequence(source, index - 1)
        ?: throw ExpressionSyntaxException("Invalid escape", index, source)
    text.append(decoded.text)
    index = decoded.end
  }

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
