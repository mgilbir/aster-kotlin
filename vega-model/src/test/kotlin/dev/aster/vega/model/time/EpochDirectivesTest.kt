package dev.aster.vega.model.time

import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `%Q` and `%s`, the two directives that write the instant rather than a field of it.
 *
 * They are the only two whose answer is arithmetic on the epoch, and the arithmetic is where they
 * were wrong: `%s` divided and truncated toward zero, so every instant in the half-second before an
 * epoch second read as the second *after* it. Before 1970 that is every label on the axis.
 *
 * The expectations are `utcFormat('%s')` and `utcFormat('%Q')` from the pinned d3-time-format, read
 * out rather than reasoned about:
 * ```
 * node --input-type=module -e "import {utcFormat} from 'd3-time-format';
 *   const f = utcFormat('%s'); console.log(f(new Date(-1500)))"   // -2
 * ```
 */
class EpochDirectivesTest {

  private fun seconds(millis: Double) = TimeFormat.format(millis, "%s", TimeZone.UTC)

  private fun instant(millis: Double) = TimeFormat.format(millis, "%Q", TimeZone.UTC)

  @Test
  fun `seconds since the epoch floor, they do not truncate`() {
    assertEquals("-2", seconds(-1500.0))
    assertEquals("-1", seconds(-1.0))
    assertEquals("0", seconds(0.0))
    assertEquals("1", seconds(1500.0))
    assertEquals("-86400", seconds(-86_400_000.0))
  }

  @Test
  fun `milliseconds since the epoch are written whole`() {
    assertEquals("-1500", instant(-1500.0))
    assertEquals("0", instant(0.0))
    assertEquals("1500", instant(1500.0))
    assertEquals("-86400000", instant(-86_400_000.0))
  }
}
