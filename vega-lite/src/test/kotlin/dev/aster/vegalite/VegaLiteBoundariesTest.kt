package dev.aster.vegalite

import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the Vega-Lite compiler does with a document that is wrong, hostile, or merely unusual.
 *
 * This module takes **pasted text** — `VegaLiteInput.toVega` is a `String`, and the demo apps on
 * three platforms have a paste screen — and until the change these tests pin, it had no `try` in it
 * anywhere. So the interesting question about a pathological specification was not what chart it
 * makes but what it does to the host, and the answer was too often "takes it down".
 *
 * Every expectation here was read off vega-lite 6.4.3 first, and where this engine deliberately
 * differs the comment says which way and why.
 */
class VegaLiteBoundariesTest {

  private fun compile(json: String) = VegaLiteCompiler().compileJson(json)

  private fun codes(json: String) = compile(json).diagnostics.map { it.code }

  // ---- dates that roll over ---------------------------------------------------

  /**
   * `{"month": 13}` is January of the next year, not an exception.
   *
   * Every field of a JavaScript date constructor rolls over, and a written date in a selection's
   * `value` goes through one: upstream's `dateTimeToTimestamp` is `+new Date(...parts)`. Building a
   * `LocalDateTime` from the parts instead threw `IllegalArgumentException` on each of these — out
   * of a public entry point, from a module with no catch in it.
   */
  @Test
  fun `a written date rolls over the way a javascript date does`() {
    fun timestamp(fields: String): Double {
      val spec =
        """
        {"data": {"values": [{"d": "2020-01-01", "v": 1}]}, "mark": "point",
         "params": [{"name": "p", "select": {"type": "interval", "encodings": ["x"]},
                     "value": {"x": [$fields, {"year": 2021}]}}],
         "encoding": {"x": {"field": "d", "type": "temporal"},
                      "y": {"field": "v", "type": "quantitative"}}}
        """
      val vega = compile(spec).vega
      assertNotNull(vega, "a rolled-over date must compile, not throw")
      val store =
        (vega!!.fields["data"] as VegaValue.Arr).values.first {
          (it as VegaValue.Obj).fields["name"] == VegaValue.Str("p_store")
        } as VegaValue.Obj
      val row = ((store.fields["values"] as VegaValue.Arr).values.first() as VegaValue.Obj)
      val extent = (row.fields["values"] as VegaValue.Arr).values.first() as VegaValue.Arr
      return (extent.values.first() as VegaValue.Num).value
    }

    // Month 13 of 2020 is January 2021 — and it is one-based on the way in, so `13` is the
    // thirteenth month, which `dateTimeParts` turns into index 12.
    val rolled = timestamp("""{"year": 2020, "month": 13, "date": 1}""")
    val direct = timestamp("""{"year": 2021, "month": 1, "date": 1}""")
    assertEquals(direct, rolled, "month 13 of 2020 is January 2021")

    // 30 February is 1 March, in a leap year.
    assertEquals(
      timestamp("""{"year": 2020, "month": 3, "date": 1}"""),
      timestamp("""{"year": 2020, "month": 2, "date": 30}"""),
      "30 February 2020 is 1 March",
    )

    // Hour 24 is the next midnight.
    assertEquals(
      timestamp("""{"year": 2020, "month": 1, "date": 2}"""),
      timestamp("""{"year": 2020, "month": 1, "date": 1, "hours": 24}"""),
      "hour 24 is the next day",
    )

    // Date 0 is the last day of the month before.
    assertEquals(
      timestamp("""{"year": 2019, "month": 12, "date": 31}"""),
      timestamp("""{"year": 2020, "month": 1, "date": 0}"""),
      "date 0 is the last day of the previous month",
    )
  }

  /**
   * A selection's interval bounds reach the store as **timestamps**.
   *
   * A store is a dataset — the filter compares its numbers against a column of numbers — so
   * upstream converts while compiling. The interval branch emitted the `{"year": …}` object raw, so
   * the initial filtering compared a column of milliseconds against an object and matched nothing,
   * until the reader's first drag replaced the store with real numbers.
   */
  @Test
  fun `an interval selection's written dates reach the store as numbers`() {
    val vega =
      compile(
          """
          {"data": {"values": [{"d": "2020-01-01", "v": 1}]}, "mark": "point",
           "params": [{"name": "brush", "select": {"type": "interval", "encodings": ["x"]},
                       "value": {"x": [{"year": 2020, "month": 1, "date": 1},
                                       {"year": 2020, "month": 6, "date": 1}]}}],
           "encoding": {"x": {"field": "d", "type": "temporal"},
                        "y": {"field": "v", "type": "quantitative"}}}
          """
        )
        .vega
    val store =
      (vega!!.fields["data"] as VegaValue.Arr).values.first {
        (it as VegaValue.Obj).fields["name"] == VegaValue.Str("brush_store")
      } as VegaValue.Obj
    val row = (store.fields["values"] as VegaValue.Arr).values.first() as VegaValue.Obj
    val extent = (row.fields["values"] as VegaValue.Arr).values.first() as VegaValue.Arr
    assertTrue(
      extent.values.all { it is VegaValue.Num },
      "the store holds data, so a written date is the millisecond it names: $extent",
    )
  }

  // ---- documents too big to walk ---------------------------------------------

  /**
   * Nesting, and a flat list of transforms, both bought recursion — and one of them is not nesting
   * at all, which is why a depth limit alone would not have done.
   *
   * Upstream refuses both with a `RangeError` from V8's stack guard. What is new is that this comes
   * back as a diagnostic from a compiler that returns.
   */
  @Test
  fun `a document too deep or too long is refused rather than fatal`() {
    val deep = buildString {
      repeat(2000) { append("""{"layer":[""") }
      append("""{"mark":"point","encoding":{}}""")
      repeat(2000) { append("]}") }
    }
    val nested = compile(deep)
    assertNull(nested.vega)
    assertEquals(listOf(VegaLiteDiagnostics.LIMIT_EXCEEDED), nested.diagnostics.map { it.code })

    val many = buildString {
      append("""{"mark":"point","data":{"values":[]},"transform":[""")
      append((1..2000).joinToString(",") { """{"calculate":"1","as":"f$it"}""" })
      append("""],"encoding":{}}""")
    }
    val long = compile(many)
    assertNull(long.vega)
    assertEquals(listOf(VegaLiteDiagnostics.LIMIT_EXCEEDED), long.diagnostics.map { it.code })

    // And a document just inside the limits still compiles, so the cap is not the whole answer.
    val fine = buildString {
      repeat(8) { append("""{"layer":[""") }
      append("""{"mark":"point","data":{"values":[{"a":1}]},"encoding":{}}""")
      repeat(8) { append("]}") }
    }
    assertNotNull(compile(fine).vega)
  }

  /** A repeat grid is a cross product of *fully compiled* views, and nothing was bounding it. */
  @Test
  fun `an enormous repeat grid is refused`() {
    val fields = (1..40).joinToString(",") { "\"f$it\"" }
    val huge =
      """
      {"repeat": {"row": [$fields], "column": [$fields]},
       "spec": {"data": {"values": [{"a": 1}]}, "mark": "point",
                "encoding": {"x": {"field": {"repeat": "column"}, "type": "quantitative"}}}}
      """
    val result = compile(huge)
    assertNull(result.vega)
    assertTrue(
      result.diagnostics.any { it.code == VegaLiteDiagnostics.LIMIT_EXCEEDED },
      result.diagnostics.toString(),
    )
  }

  // ---- a document that is simply wrong ---------------------------------------

  /**
   * A specification with no `data` is a **valid** chart: it reads an empty table called `source`,
   * which is the seam a host supplies its own rows through.
   *
   * It used to be an ERROR *and* a non-null result whose marks read a dataset called `""`, so a
   * host following the README's stop-on-null pattern handed the runtime something broken.
   */
  @Test
  fun `a specification with no data reads an empty named table`() {
    val result =
      compile("""{"mark": "point", "encoding": {"x": {"value": 5}, "y": {"value": 5}}}""")
    assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
    val vega = result.vega!!
    val data = vega.fields["data"] as VegaValue.Arr
    assertEquals(
      VegaValue.Obj(linkedMapOf("name" to VegaValue.Str("source"))),
      data.values.single(),
      "upstream compiles this to a single empty source",
    )
    val mark = (vega.fields["marks"] as VegaValue.Arr).values.first() as VegaValue.Obj
    assertEquals(
      VegaValue.Str("source"),
      (mark.fields["from"] as VegaValue.Obj).fields["data"],
    )
  }

  /**
   * A channel that is not a channel is dropped and said so, which is what upstream does.
   *
   * Kept, it went into an aggregate's `groupby`, the spoken description and the tooltip's field
   * list — so `colour` produced a chart grouped by a column nothing was coloured with.
   */
  @Test
  fun `an unknown encoding channel is dropped with a diagnostic`() {
    val result =
      compile(
        """
        {"data": {"values": [{"a": 1, "b": 2}]}, "mark": "point",
         "encoding": {"x": {"field": "a", "type": "quantitative"},
                      "colour": {"field": "b", "type": "nominal"}}}
        """
      )
    assertTrue(
      result.diagnostics.any {
        it.code == VegaLiteDiagnostics.UNSUPPORTED_CHANNEL && it.message.contains("colour")
      },
      result.diagnostics.toString(),
    )
    // Dropped, not compiled: no scale, no encode and no description reads the column it named.
    // (The row itself still holds `b` — inline data is copied through whatever the chart uses.)
    assertFalse(
      result.toJson()!!.contains("\"field\": \"b\""),
      "the dropped channel must not become a field reference",
    )
  }

  /** A concat member that is not a view, and a layer member that could not be read. */
  @Test
  fun `a member that is not a view is reported rather than skipped`() {
    val concat =
      codes(
        """
        {"data": {"values": [{"a": 1}]},
         "vconcat": [{"mark": "point", "encoding": {"x": {"field": "a", "type": "quantitative"}}},
                     42]}
        """
      )
    assertTrue(VegaLiteDiagnostics.UNSUPPORTED_COMPOSITION in concat, concat.toString())
  }

  // ---- names and numbers written into the emitted specification ---------------

  /**
   * `varName` is upstream's `\W → _`, and JavaScript's `\W` is **ASCII**.
   *
   * Kotlin's `isLetterOrDigit` is Unicode, so a column called `año` kept its `ñ` here and lost it
   * upstream: two different signal names for one specification, and the emitted Vega differing on
   * every line that mentions the field.
   */
  @Test
  fun `a name is made safe the way javascript makes it safe`() {
    assertEquals("a_o", Fields.varName("año"))
    assertEquals("_", Fields.varName("é"))
    assertEquals("a_b", Fields.varName("a-b"))
    assertEquals("_1a", Fields.varName("1a"))
    assertEquals("plain_9", Fields.varName("plain_9"))
  }

  /** A number written into an expression is `String(n)`, which Kotlin's `toString` is not. */
  @Test
  fun `a number in an expression is spelled the way javascript spells it`() {
    assertEquals("1e-7", Fields.expressionNumber(1e-7))
    assertEquals("1e+21", Fields.expressionNumber(1e21))
    assertEquals("100000000000000000000", Fields.expressionNumber(1e20))
    assertEquals("2", Fields.expressionNumber(2.0))
    assertEquals("0", Fields.expressionNumber(-0.0))
    assertEquals("0.15000000000000002", Fields.expressionNumber(0.15000000000000002))
  }

  /**
   * A signal rename is a substring replace over the finished specification, and a dataset's rows
   * are the user's values rather than references to anything.
   */
  @Test
  fun `a signal rename does not rewrite the data`() {
    val renamed =
      VegaLiteCompiler().let {
        // Two layers binned on the same field is what makes the compiler fold two bin nodes and
        // rename the signals of one; the row below holds the name it renames *from*.
        compile(
          """
            {"data": {"values": [{"a": 1, "label": "child__layer_1_bin_maxbins_10_a_bins"}]},
             "layer": [
               {"mark": "bar", "encoding": {"x": {"bin": true, "field": "a", "type": "quantitative"},
                                            "y": {"aggregate": "count"}}},
               {"mark": "bar", "encoding": {"x": {"bin": true, "field": "a", "type": "quantitative"},
                                            "y": {"aggregate": "count"}}}]}
            """
        )
      }
    val json = renamed.toJson()!!
    val values = VegaJson.parse(json).let { it as VegaValue.Obj }.fields["data"] as VegaValue.Arr
    val rows =
      values.values.firstNotNullOf { (it as VegaValue.Obj).fields["values"] as? VegaValue.Arr }
    assertEquals(
      VegaValue.Str("child__layer_1_bin_maxbins_10_a_bins"),
      (rows.values.first() as VegaValue.Obj).fields["label"],
      "a row is data, and must survive the rename untouched",
    )
  }

  // ---- the catch-all ---------------------------------------------------------

  /** Text that is not JSON at all comes back as a diagnostic, from both entry points. */
  @Test
  fun `unreadable input is a diagnostic rather than an exception`() {
    val result = compile("{ not json")
    assertNull(result.vega)
    assertTrue(result.diagnostics.all { it.severity == DiagnosticSeverity.FATAL })

    val converted = VegaLiteInput.toVega("{ not json")
    assertFalse(converted.wasVegaLite)
  }
}
