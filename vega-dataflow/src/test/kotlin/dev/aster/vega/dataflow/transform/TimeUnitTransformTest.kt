package dev.aster.vega.dataflow.transform

import dev.aster.vega.expression.CachingExpressionCompiler
import dev.aster.vega.expression.ExpressionCompiler
import dev.aster.vega.expression.ExpressionScope
import dev.aster.vega.expression.VegaExpressionCompiler
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.field
import dev.aster.vega.model.time.DateValues
import dev.aster.vega.model.time.TimeFormat
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * The `timeunit` transform, pinned against upstream.
 *
 * Every expected bucket was read off upstream running the same units on the same four instants,
 * which is what makes these reference vectors rather than a restatement of the implementation.
 */
class TimeUnitTransformTest {

  private val utc = TimeZone.UTC

  /** Four instants spanning two years, chosen so month, quarter and year buckets all differ. */
  private val input =
    listOf(
      "2026-01-15T09:20:00Z",
      "2026-01-28T22:05:00Z",
      "2026-03-02T00:00:00Z",
      "2027-03-19T13:45:00Z",
    )

  private class Context(
    override val diagnostics: DiagnosticCollector = DiagnosticCollector(),
    override val expressions: ExpressionCompiler =
      CachingExpressionCompiler(VegaExpressionCompiler()),
  ) : TransformContext {
    override val scope: ExpressionScope = scopeFor(VegaValue.Null)

    override fun setSignal(name: String, value: VegaValue) = Unit

    override fun scopeFor(datum: VegaValue): ExpressionScope =
      object : ExpressionScope {
        override val datum: VegaValue = datum

        override fun signal(name: String): VegaValue = VegaValue.Null

        override fun dataset(name: String): List<VegaValue> = emptyList()
      }
  }

  private fun bucket(units: String, extra: String = ""): Pair<List<String>, DiagnosticCollector> {
    val context = Context()
    val rows = input.map { iso ->
      VegaValue.Obj(
        linkedMapOf("d" to VegaValue.Num(requireNotNull(DateValues.parseIso(iso, utc))))
      )
    }
    val params =
      VegaJson.parse(
        """{"type": "timeunit", "field": "d", "units": $units, "timezone": "utc"$extra}"""
      ) as VegaValue.Obj
    val out = TimeUnitTransform.apply(rows, params, context)
    return out.map { row ->
      val start = row.field("unit0")
      val end = row.field("unit1")
      if (start is VegaValue.Num && end is VegaValue.Num) {
        TimeFormat.format(start.value, "%Y-%m-%dT%H:%M", utc) +
          ".." +
          TimeFormat.format(end.value, "%Y-%m-%dT%H:%M", utc)
      } else {
        "none"
      }
    } to context.diagnostics
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
    delimiter = '|',
    value =
      [
        """["year","month"]|2026-01-01T00:00..2026-02-01T00:00|2027-03-01T00:00..2027-04-01T00:00""",
        """["year"]|2026-01-01T00:00..2027-01-01T00:00|2027-01-01T00:00..2028-01-01T00:00""",
        """["year","quarter"]|2026-01-01T00:00..2026-04-01T00:00|2027-01-01T00:00..2027-04-01T00:00""",
        """["year","month","date"]|2026-01-15T00:00..2026-01-16T00:00|2027-03-19T00:00..2027-03-20T00:00""",
        """["year","month","date","hours"]|2026-01-15T09:00..2026-01-15T10:00|2027-03-19T13:00..2027-03-19T14:00""",
      ],
  )
  fun `each units list buckets the way upstream buckets it`(
    units: String,
    firstRow: String,
    lastRow: String,
  ) {
    val (buckets, diagnostics) = bucket(units)
    assertTrue(diagnostics.diagnostics.isEmpty(), diagnostics.diagnostics.toString())
    assertEquals(firstRow, buckets.first(), units)
    assertEquals(lastRow, buckets.last(), units)
  }

  @Test
  fun `omitting the year collapses every period onto a reference year`() {
    // Not a quirk to work around: this is how a specification asks for a seasonal profile. Every
    // January in the data lands in one bucket, in upstream's reference year of 2012.
    val (buckets, _) = bucket("""["month"]""")
    assertEquals("2012-01-01T00:00..2012-02-01T00:00", buckets[0])
    assertEquals("2012-01-01T00:00..2012-02-01T00:00", buckets[1])
    assertEquals("2012-03-01T00:00..2012-04-01T00:00", buckets[2])
    // Two years apart in the data, one bucket in the output.
    assertEquals(buckets[2], buckets[3])
  }

  @Test
  fun `omitting everything but the hour gives a daily profile`() {
    val (buckets, _) = bucket("""["hours"]""")
    assertEquals("2012-01-01T09:00..2012-01-01T10:00", buckets[0])
    assertEquals("2012-01-01T22:00..2012-01-01T23:00", buckets[1])
  }

  @Test
  fun `as renames both output fields`() {
    val context = Context()
    val rows =
      listOf(
        VegaValue.Obj(
          linkedMapOf("d" to VegaValue.Num(requireNotNull(DateValues.parseIso(input[0], utc))))
        )
      )
    val params =
      VegaJson.parse(
        """{"type":"timeunit","field":"d","units":["year"],"timezone":"utc","as":["lo","hi"]}"""
      ) as VegaValue.Obj
    val out = TimeUnitTransform.apply(rows, params, context).single()
    assertTrue(out.field("lo") is VegaValue.Num)
    assertTrue(out.field("hi") is VegaValue.Num)
    assertTrue(out.field("unit0") is VegaValue.Null)
  }

  @Test
  fun `incompatible units are refused rather than resolved arbitrarily`() {
    // Upstream's own rule: a bucket cannot be both a week and a month.
    val (_, diagnostics) = bucket("""["year","week","month"]""")
    assertTrue(
      diagnostics.diagnostics.any {
        it.severity >= DiagnosticSeverity.ERROR && it.message.contains("incompatible")
      },
      diagnostics.diagnostics.toString(),
    )
  }

  @Test
  fun `week-based units are reported rather than approximated`() {
    val (_, diagnostics) = bucket("""["year","week"]""")
    assertTrue(
      diagnostics.diagnostics.any { it.message.contains("week numbering") },
      diagnostics.diagnostics.toString(),
    )
  }

  @Test
  fun `a row with no readable date gets no bucket rather than a wrong one`() {
    val context = Context()
    val rows = listOf(VegaValue.Obj(linkedMapOf("d" to VegaValue.Str("not a date"))))
    val params =
      VegaJson.parse("""{"type":"timeunit","field":"d","units":["year"],"timezone":"utc"}""")
        as VegaValue.Obj
    val out = TimeUnitTransform.apply(rows, params, context).single()
    assertTrue(out.field("unit0") is VegaValue.Null)
  }

  @Test
  fun `inferring units from the data is reported rather than guessed`() {
    val (_, diagnostics) = bucket("""[]""")
    assertTrue(
      diagnostics.diagnostics.any { it.message.contains("inferring them") },
      diagnostics.diagnostics.toString(),
    )
  }
}
