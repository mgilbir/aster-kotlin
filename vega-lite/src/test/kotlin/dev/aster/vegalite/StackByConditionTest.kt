package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A channel that names a column only **under a test** is a dimension of the stack all the same.
 *
 * ```js
 * const stackBy = NONPOSITION_CHANNELS.reduce((sc, channel) => {
 *   // Ignore tooltip in stackBy (https://github.com/vega/vega-lite/issues/4001)
 *   if (channel !== 'tooltip' && channelHasField(encoding, channel)) {
 *     const channelDef = encoding[channel];
 *     for (const cDef of array(channelDef)) {
 *       const fieldDef = getFieldDef(cDef);
 * ```
 *
 * `channelHasField` counts a conditional field def and `getFieldDef` then reaches into the
 * condition for it, so a colour that is a measure where a row was picked and grey otherwise orders
 * the stack like an unconditional one would. This engine read the unconditional part alone, so such
 * a chart came out stacked in no order at all — `sort: {"field": [], "order": []}` — and two
 * specifications in the wild corpus are that chart.
 *
 * Every entry of a channel written as a **list** counts, not the first alone. The guard reads those
 * entries differently from a lone definition, and faithfully so: `some(channelDef, fieldDef =>
 * !!fieldDef.field)` asks each entry for a field of its **own**, so a list of conditions names no
 * column where a single condition does.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class StackByConditionTest {

  /** Every `stack` the chart's tables hold, which is one or none. */
  private fun stacks(encoding: String): String {
    val spec =
      """{"data":{"values":[{"a":1,"b":2,"c":"x","d":"y"}]},"mark":"bar",
         "params":[{"name":"p","select":"point"}],"encoding":{$encoding}}"""
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    val found =
      (compiled.fields["data"] as VegaValue.Arr).values.flatMap { table ->
        ((table as VegaValue.Obj).fields["transform"] as? VegaValue.Arr)?.values.orEmpty().filter {
          (it as VegaValue.Obj).string("type") == "stack"
        }
      }
    return VegaJson.write(VegaValue.Arr(found)).replace(Regex("""\n\s*"""), "")
  }

  private val bar = """"x":{"field":"a","type":"quantitative"},"y":{"field":"c","type":"nominal"}"""

  private fun stacked(sort: String) =
    """[{"type": "stack","groupby": ["c"],"field": "a","sort": $sort,""" +
      """"as": ["a_start","a_end"],"offset": "zero"}]"""

  /** The reported shape: a colour that names its column under a test. */
  @Test
  fun `a conditional colour orders the stack`() {
    assertEquals(
      stacked("""{"field": ["a"],"order": ["ascending"]}"""),
      stacks(
        """$bar,"color":{"condition":{"field":"a","type":"quantitative","param":"p"},
           "value":"grey"}"""
      ),
    )
  }

  /** An unconditional one always did, which is the whole of what must not change. */
  @Test
  fun `a plain colour orders the stack`() {
    assertEquals(
      stacked("""{"field": ["d"],"order": ["ascending"]}"""),
      stacks("""$bar,"color":{"field":"d","type":"nominal"}"""),
    )
  }

  /** Every entry of a list, in the order it was written. */
  @Test
  fun `both columns of a list order the stack`() {
    assertEquals(
      stacked("""{"field": ["d","b"],"order": ["ascending","ascending"]}"""),
      stacks(
        """$bar,"detail":[{"field":"d","type":"nominal"},{"field":"b","type":"quantitative"}]"""
      ),
    )
  }

  /** And a **list** of conditions names no column, which a lone condition does. */
  @Test
  fun `a list of conditions orders nothing`() {
    assertEquals(
      stacked("""{"field": [],"order": []}"""),
      stacks(
        """$bar,"detail":[{"condition":{"field":"d","type":"nominal","param":"p"},"value":"z"}]"""
      ),
    )
  }

  /** A condition that names only a value names no column either. */
  @Test
  fun `a condition without a column orders nothing`() {
    assertEquals(
      stacked("""{"field": [],"order": []}"""),
      stacks("""$bar,"color":{"condition":{"value":"red","param":"p"},"value":"grey"}"""),
    )
  }

  /** A summarised channel is no dimension of the stack — and with none, nothing stacks at all. */
  @Test
  fun `a summarised colour is no dimension`() {
    assertEquals(
      "[]",
      stacks("""$bar,"color":{"field":"b","type":"quantitative","aggregate":"mean"}"""),
    )
  }

  /** Nor is a channel naming the column the stack is already grouped by. */
  @Test
  fun `a colour on the grouping column orders nothing`() {
    assertEquals(
      stacked("""{"field": [],"order": []}"""),
      stacks("""$bar,"color":{"field":"c","type":"nominal"}"""),
    )
  }
}
