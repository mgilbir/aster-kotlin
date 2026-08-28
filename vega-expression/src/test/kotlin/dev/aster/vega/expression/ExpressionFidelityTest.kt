package dev.aster.vega.expression

import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * The expression engine against upstream, for the divergences an audit found by reading.
 *
 * Every expectation below was read off the pinned Vega, not reasoned about:
 * ```
 * cd oracle-js && node src/eval-probe.js "'' + clamp(5, 10, 0)"
 * ```
 *
 * The `'' +` is not decoration: `eval-probe.js` prints `JSON.stringify`, which writes `null` for
 * NaN, for both infinities, and for a `Date` it cannot serialize — so the interesting answers are
 * indistinguishable without it.
 *
 * The JVM tests pin `Europe/Amsterdam` and so does `scripts/oracle.sh`, which is what makes the
 * date rows here comparable at all.
 */
class ExpressionFidelityTest {

  private object EmptyScope : ExpressionScope {
    override val datum: VegaValue = VegaValue.EmptyObject

    override fun signal(name: String): VegaValue = VegaValue.Null

    override fun dataset(name: String): List<VegaValue> = emptyList()
  }

  private fun evaluate(source: String): VegaValue {
    val result = VegaExpressionCompiler().compile(source)
    assertTrue(result is ExpressionResult.Compiled, "failed to parse '$source': $result")
    return (result as ExpressionResult.Compiled).expression.evaluate(EmptyScope)
  }

  /** The same, over a row, for the expressions that read one. */
  private fun evaluate(source: String, datum: VegaValue): VegaValue {
    val result = VegaExpressionCompiler().compile(source)
    assertTrue(result is ExpressionResult.Compiled, "failed to parse '$source': $result")
    val scope =
      object : ExpressionScope {
        override val datum: VegaValue = datum

        override fun signal(name: String): VegaValue = VegaValue.Null

        override fun dataset(name: String): List<VegaValue> = emptyList()
      }
    return (result as ExpressionResult.Compiled).expression.evaluate(scope)
  }

  private fun text(source: String): String =
    when (val value = evaluate(source)) {
      is VegaValue.Str -> value.value
      is VegaValue.Num -> JsSemantics.numberToString(value.value)
      is VegaValue.Bool -> value.value.toString()
      is VegaValue.Null -> "null"
      is VegaValue.Undefined -> "undefined"
      else -> JsSemantics.toStringValue(value)
    }

  @ParameterizedTest(name = "{0} => {1}")
  @CsvSource(
    delimiter = '|',
    value =
      [
        // H16 — `erfInverse` fell out of its own last branch and lost the sign of x.
        "'' + quantileNormal(0)|-Infinity",
        "'' + quantileNormal(1)|Infinity",
        "'' + quantileLogNormal(0)|0",
        "'' + quantileLogNormal(1)|Infinity",
        // H18 — `span` of nothing is 0, and `|| 0` is why.
        "span([])|0",
        "span(null)|0",
        "span([1])|0",
        "span([1, 5])|4",
        // M31 — `clampRange` reads the ends as min and max, so a descending range is normalized.
        "'' + clampRange([5, 1], 0, 10)|1,5",
        "'' + clampRange([1, 5], 2, 3)|2,3",
        // M32 — the two flags are inclusivity, and an omitted one is inclusive.
        "inrange(1, [1, 2])|true",
        "inrange(1, [1, 2], true)|true",
        "inrange(1, [1, 2], false)|false",
        "inrange(2, [1, 2], true, false)|false",
        "inrange(1.5, [1, 2], false, false)|true",
        // M35 — a property key is a string, and only its canonical spelling is an array index.
        "'' + [10, 20, 30][1.5]|undefined",
        "'' + [10, 20, 30]['01']|undefined",
        "'' + [10, 20, 30]['1']|20",
        "'' + [10, 20, 30][0 - 0]|10",
        // M38 — an array against a string primitivizes to a string, not to a number.
        "[1, 2] == '1,2'|true",
        "[1] == 1|true",
        // M30 — `sort` is vega-util's `ascending`: absent first, then NaN, then the rest.
        "'' + sort([10, 9, '10', '9'])|9,9,10,10",
        "'' + sort([3, 1])|1,3",
        // L39 — `clamp` is `max(min, min(max, value))`, composed and not corrected.
        "clamp(5, 10, 0)|10",
        "clamp(5, 0, 10)|5",
        // L40 — `hypot` scales before squaring.
        "'' + hypot(1e200, 1e200)|1.414213562373095e+200",
        "'' + hypot()|0",
        "'' + hypot(3, 4, 12)|13",
        // L41 — `round` keeps a negative zero and does not round the tie's neighbour up.
        "'' + round(0.49999999999999994)|0",
        "'' + (1 / round(0 - 0.4))|-Infinity",
        "'' + round(2.5)|3",
        "'' + round(-2.5)|-2",
        "'' + round(-1.5)|-1",
        // L42 — `parseInt` works the radix out from the prefix; `parseFloat` reads Infinity.
        "parseInt('0xFF')|255",
        "parseInt('0xFF', 10)|0",
        "parseInt('  12px')|12",
        "'' + parseFloat('Infinity')|Infinity",
        "'' + parseFloat('-Infinity')|-Infinity",
        // M36 — the four in upstream's codegen table and not in its function context.
        "isNaN(1)|false",
        "isNaN(1 / 'a')|true",
        "isNaN('x')|false",
        "atob('YQ==')|a",
        "btoa('a')|YQ==",
        "encodeURIComponent('a b')|a%20b",
      ],
  )
  fun `the engine answers what upstream answers`(source: String, expected: String) {
    assertEquals(expected, text(source), source)
  }

  /**
   * M33 and M34, which cannot live in the table above: an empty expectation is a null there, and
   * `|` is the table's own delimiter.
   *
   * A `timeSequence` whose step is not a positive whole number is `[]` upstream — d3's
   * `interval.range` refuses it. A negative one walked *downwards* here, away from `stop`, so every
   * iteration stayed below it and a hundred thousand timestamps came out before the guard stopped
   * it.
   *
   * `ToInt32` **wraps** modulo 2^32 and does not saturate. `toLong()` saturates at ±2^63, so
   * everything above it came out as −1.
   */
  @Test
  fun `a backwards sequence is empty and a bitwise operator wraps`() {
    assertEquals(
      VegaValue.Arr(emptyList()),
      evaluate("timeSequence('day', datetime(2020, 0, 5), datetime(2020, 0, 1))"),
    )
    assertEquals("1661992960", text("'' + (1e20 | 0)"))
    assertEquals("-1661992960", text("'' + ((0 - 1e20) | 0)"))
    assertEquals("0", text("'' + (4294967296 | 0)"))
    assertEquals("0", text("'' + (1 / 'a' | 0)"))
  }

  /**
   * C8 — `datetime(x)` with **one** argument is `new Date(x)`, a time value, not a year.
   *
   * `datetime(datum.epochMillis)` is a documented upstream idiom, and reading the first argument as
   * a year made it the year 1.6 trillion — `toInt()` saturated on the way and `LocalDate` threw an
   * `IllegalArgumentException` out of a public compile, which every catch site in the engine is too
   * narrowly typed to see.
   */
  @Test
  fun `a one-argument datetime is a time value and a two-argument one is a calendar`() {
    assertEquals(VegaValue.Timestamp(1600000000000.0), evaluate("datetime(1600000000000)"))
    // `new Date(2020)` is 2020 **milliseconds** after the epoch.
    assertEquals(VegaValue.Timestamp(2020.0), evaluate("datetime(2020)"))
    // A string argument is parsed, and a bare ISO date is UTC.
    assertEquals(VegaValue.Timestamp(1577836800000.0), evaluate("datetime('2020-01-01')"))
    // Two or more arguments are the calendar constructor, which is unchanged.
    assertEquals("1577836800000", text("'' + time(utc(2020, 0, 1))"))
    // `utc` keeps reading its first argument as a year, because `Date.UTC` does.
    assertEquals("1577836800000", text("'' + utc(2020)"))
  }

  /**
   * C7's other half: `datetime` and `utc` are `new Date(...)`, so every field rolls over and a
   * two-digit year is nineteen-hundreds.
   *
   * Both had been open-coded here from "the first of January, then add", which gives the rollover
   * for free and the year rule not at all — so `datetime(99, 1, 1)` was February of the year 99
   * rather than of 1999, which is what every Vega renderer answers. It shares one implementation
   * with the Vega-Lite compiler now: `JsDate.make`.
   */
  @Test
  fun `a date constructor rolls over and reads a two-digit year as nineteen hundreds`() {
    assertEquals(text("'' + time(utc(1999, 1, 1))"), text("'' + time(utc(99, 1, 1))"))
    assertEquals(text("'' + time(utc(1900, 0, 1))"), text("'' + time(utc(0, 0, 1))"))
    assertEquals(text("'' + time(utc(2013, 0, 1))"), text("'' + time(utc(2012, 12, 1))"))
    assertEquals(text("'' + time(utc(2012, 2, 1))"), text("'' + time(utc(2012, 1, 30))"))
    assertEquals(text("'' + time(utc(2012, 0, 2))"), text("'' + time(utc(2012, 0, 1, 24))"))
    assertEquals(text("'' + time(utc(2011, 11, 31))"), text("'' + time(utc(2012, 0, 0))"))
  }

  /**
   * A non-ASCII field name is reachable, bare and bracketed — the audit's open question Q15.
   *
   * It matters because `Fields.varName` was made ASCII to match upstream's `\W → _`, and that is
   * only the right fix if the *parser* accepts these names in the first place: if it did not, a
   * chart over a column called `año` would be broken rather than merely spelled differently from
   * upstream's. Probed against vega 6.3.1, which reads both forms; this lexer asks **ktecma262**
   * for `ID_Start`/`ID_Continue` rather than approximating with a category test, so it does too.
   *
   * So the `varName` change is a parity fix and nothing is broken behind it.
   */
  @Test
  fun `a non-ascii field name is reachable bare and bracketed`() {
    val datum =
      VegaValue.Obj(
        linkedMapOf(
          "año" to VegaValue.Num(5.0),
          "café" to VegaValue.Str("x"),
          // A decomposed e-acute: two code points, and a letter to the specification.
          "cafe\u0301" to VegaValue.Str("decomposed"),
        )
      )
    assertEquals(VegaValue.Num(5.0), evaluate("datum.año", datum))
    assertEquals(VegaValue.Str("x"), evaluate("datum.café", datum))
    assertEquals(VegaValue.Str("x"), evaluate("datum['café']", datum))
    assertEquals(VegaValue.Str("decomposed"), evaluate("datum.cafe\u0301", datum))
  }

  /** A year outside the calendar is an Invalid Date upstream, and used to throw here. */
  @Test
  fun `a year outside the calendar is NaN rather than an exception`() {
    assertEquals("NaN", text("'' + utc(1600000000000)"))
    assertEquals("NaN", text("'' + utc(1e10, 0, 1)"))
    assertEquals("NaN", text("'' + time(datetime(8640000000000001))"))
    assertEquals("8640000000000000", text("'' + time(datetime(8640000000000000))"))
  }

  /**
   * H19 — `utcOffset` was registered twice and the second registration won, answering a number.
   *
   * Its twin `timeOffset` answers a date, and so does upstream's `utcOffset`, so `isDate` gave
   * opposite answers for two functions upstream defines as a pair.
   */
  @Test
  fun `utcOffset answers a date, as its twin does`() {
    assertTrue(evaluate("utcOffset('day', datetime(2020, 0, 1))") is VegaValue.Timestamp)
    assertTrue(evaluate("timeOffset('day', datetime(2020, 0, 1))") is VegaValue.Timestamp)
    assertEquals("true", text("'' + isDate(utcOffset('day', datetime(2020, 0, 1)))"))
  }

  /**
   * M37 and L38 — a `Date` is an object, and objects do not behave like their numbers.
   *
   * It is truthy at the epoch, it concatenates rather than adding, and it is never `===` or `==` a
   * number. The old bridge that compared a timestamp with a number went through a boxed `Double`,
   * whose `equals` says `NaN` equals itself and `-0.0` does not equal `0.0` — both inverted from
   * JavaScript, and both unreachable now that the bridge is gone.
   *
   * Relational comparison is the exception and stays numeric: `<` asks for the number hint.
   */
  @Test
  fun `a date is an object everywhere except in a relational comparison`() {
    assertEquals("yes", text("datetime(0) ? 'yes' : 'no'"))
    assertEquals("false", text("'' + (datetime(0) == 0)"))
    assertEquals("false", text("'' + (datetime(0) === 0)"))
    assertEquals("true", text("'' + (datetime(0) < 1)"))
    assertEquals("true", text("'' + (datetime(1) > datetime(0))"))
    assertEquals("1000", text("'' + (datetime(1000) - datetime(0))"))
    assertEquals("0", text("'' + (datetime(0) * 2)"))
    assertEquals("5", text("'' + toNumber(datetime(5))"))
  }

  /**
   * `String(date)` is ECMA-262's date string, not the milliseconds behind it.
   *
   * One divergence is left and the specification is why: after the offset an implementation *may*
   * append a parenthesised zone name, and V8 appends `" (Central European Standard Time)"`.
   * Producing it needs CLDR data that is not on every target this engine compiles for, and
   * 21.4.4.41 marks it implementation-defined. Everything before it agrees, which is what this
   * asserts.
   */
  @Test
  fun `a date stringifies as a date`() {
    assertEquals("Thu Jan 01 1970 01:00:00 GMT+0100", text("'' + datetime(0)"))
    assertEquals("Sun Sep 13 2020 14:26:40 GMT+0200", text("'' + datetime(1600000000000)"))
    assertEquals("Invalid Date", text("'' + datetime(8640000000000001)"))
    // And it concatenates rather than adding, which is what made the millis visible.
    assertEquals("Thu Jan 01 1970 01:00:00 GMT+01001", text("'' + (datetime(0) + 1)"))
  }

  /**
   * L37 — three escape forms were silently producing the wrong text.
   *
   * The fallback in the lexer is the identity, so `'\x41'` came out as `"x41"` and nothing said so.
   */
  @Test
  fun `every JavaScript string escape is read`() {
    assertEquals("A", text("'\\x41'"))
    assertEquals("A", text("'\\u0041'"))
    assertEquals("😀", text("'\\u{1F600}'"))
    // A line continuation: the backslash and the newline both vanish.
    assertEquals("ab", text("'a\\\nb'"))
    assertEquals("\n\t", text("'\\n\\t'"))
  }

  /**
   * L45 — a no-break space is JavaScript whitespace and is not Kotlin's.
   *
   * An expression copied out of a rendered web page failed with `Unexpected character ' '`, which
   * is unreadable in a diagnostic because the two characters look identical.
   */
  @Test
  fun `an expression pasted from a web page lexes`() {
    assertEquals("3", text("'' + (1 + 2)"))
    assertEquals("3", text("﻿1 + 2"))
  }

  /**
   * H17 — three ways a spec-supplied string escaped the diagnostic net entirely.
   *
   * Each threw something that is not an `ExpressionEvaluationException`, so no catch site between
   * here and the host could see it: `NoSuchElementException`, the regular-expression engine's own
   * syntax error, and — the worst of the three, because it is an `Error` and unrecoverable on
   * Kotlin/Native — a `StackOverflowError`.
   */
  @Test
  fun `the three unstructured escape hatches are diagnostics now`() {
    val noArguments = assertThrows<ExpressionEvaluationException> { evaluate("data()") }
    assertEquals(DiagnosticCodes.EXPRESSION_UNSUPPORTED_FUNCTION, noArguments.diagnostic.code)

    val badPattern = assertThrows<ExpressionEvaluationException> { evaluate("regexp('(')") }
    assertTrue("regular expression" in badPattern.diagnostic.message, badPattern.diagnostic.message)

    val deep = VegaExpressionCompiler().compile("(".repeat(5_000) + "1" + ")".repeat(5_000))
    assertTrue(deep is ExpressionResult.Failed, "expected a diagnostic, got $deep")
    assertEquals(
      DiagnosticCodes.EXPRESSION_PARSE_ERROR,
      (deep as ExpressionResult.Failed).diagnostic.code,
    )

    // Nesting a person would actually write still parses.
    assertEquals("1", text("'' + " + "(".repeat(40) + "1" + ")".repeat(40)))
  }

  /**
   * C2 — a signal whose name is also a datum property was reported as no dependency at all.
   *
   * The walk never visits a non-computed member's property, so nothing added `year` from
   * `datum.year` in the first place; a second clause removed it **by name** from the whole
   * expression anyway. `DataflowOrder` then resolved the expression before the signal existed and
   * never re-evaluated it after, so a slider bound to `year` moved nothing — and there was no
   * diagnostic, because from the compiler's point of view the expression did not mention it.
   */
  @Test
  fun `a signal is a dependency even when a field shares its name`() {
    fun dependencies(source: String) =
      (VegaExpressionCompiler().compile(source) as ExpressionResult.Compiled)
        .expression
        .signalDependencies

    assertEquals(setOf("year"), dependencies("year == datum.year"))
    assertEquals(setOf("year"), dependencies("datum.year == year"))
    assertEquals(setOf("year"), dependencies("year"))
    // A property name on its own is still not a signal.
    assertEquals(emptySet<String>(), dependencies("datum.year"))
    assertEquals(emptySet<String>(), dependencies("datum.a.b"))
  }
}
