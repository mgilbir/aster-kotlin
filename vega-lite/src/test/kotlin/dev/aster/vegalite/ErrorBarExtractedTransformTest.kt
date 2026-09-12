package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A channel of an error bar asking for a mean asks the **summary** for it.
 *
 * `errorBarParams` hands the encoding to `extractTransformsFromEncoding` before anything is drawn
 * from it, and what that walk finds becomes a transform of its own:
 * ```js
 * const aggregate: AggregatedFieldDef[] = [...oldAggregate, ...errorBarSpecificAggregate];
 * ```
 *
 * so the encoding's own measures are the first entries of the summary, and the channel is rewritten
 * to read the column the summary writes. This left the request on the channel, where it named an
 * aggregate of a table that had already been collapsed to one row per group — a second summary over
 * the first, grouped by the interval's own bounds, which is a different number rather than a
 * differently-computed one.
 *
 * Two details of the upstream walk decide the answer.
 *
 * `forEach` spreads a **list** channel, so every entry contributes its grouping or its measure —
 * but the rewrite writes the *channel*, `encoding[channel] = newFieldDef`, so only the **last**
 * entry is left standing and a two-column tooltip over an error bar reads one line. Where that last
 * entry asks for nothing derived, the other branch writes `oldEncoding[channel]` — the list, whole
 * and untouched, aggregating entries and all. That asymmetry is upstream's, and an aggregating
 * entry written *before* a plain one really does summarise twice; it is checked below so the rule
 * stays the one upstream has rather than the one it looks like it should have.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class ErrorBarExtractedTransformTest {

  /** One aggregate node, as `groupby | op(field)→output, …`. */
  private fun summaries(encoding: String): List<String> {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"x":1,"y":2,"c":"a","t":"2001-01-01","a b":3,"p":4,"q":5}]},
               "mark":"errorbar",
               "encoding":{"x":{"field":"x","type":"quantitative"},
                           "y":{"field":"y","type":"quantitative"},$encoding}}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    return (compiled.fields["data"] as VegaValue.Arr)
      .values
      .mapNotNull { it as? VegaValue.Obj }
      .flatMap { (it.fields["transform"] as? VegaValue.Arr)?.values.orEmpty() }
      .mapNotNull { it as? VegaValue.Obj }
      .filter { (it.fields["type"] as? VegaValue.Str)?.value == "aggregate" }
      .map { node ->
        fun list(key: String) =
          (node.fields[key] as? VegaValue.Arr)?.values.orEmpty().map {
            (it as? VegaValue.Str)?.value ?: "-"
          }
        val measures =
          list("ops").zip(list("fields")).zip(list("as")).joinToString(", ") { (opField, output) ->
            "${opField.first}(${opField.second})→$output"
          }
        "${list("groupby").joinToString(",")} | $measures"
      }
  }

  /** What resting on the drawn interval reads. */
  private fun tooltip(encoding: String): String? {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"x":1,"y":2,"c":"a","t":"2001-01-01","a b":3,"p":4,"q":5}]},
               "mark":"errorbar",
               "encoding":{"x":{"field":"x","type":"quantitative"},
                           "y":{"field":"y","type":"quantitative"},$encoding}}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    val mark = (compiled.fields["marks"] as VegaValue.Arr).values.first() as VegaValue.Obj
    val update = (mark.obj("encode"))?.obj("update")
    return (update?.fields?.get("tooltip") as? VegaValue.Obj)?.string("signal")
  }

  private val plain = """{"field":"c","type":"nominal"}"""

  /** The reported shape: a tooltip listing a column and a mean of another. */
  @Test
  fun `an aggregating tooltip entry becomes a measure of the summary`() {
    assertEquals(
      listOf("x,c | mean(y)→mean_y, mean(y)→center_y, stderr(y)→extent_y"),
      summaries(""""tooltip":[$plain,{"field":"y","aggregate":"mean","type":"quantitative"}]"""),
      "one summary, and the tooltip's mean is the first measure of it",
    )
  }

  /** And the channel then reads the column the summary wrote, under the title it was asked by. */
  @Test
  fun `the rewritten channel reads the summary's column`() {
    assertEquals(
      """format(datum["mean_y"], "")""",
      tooltip(""""tooltip":[$plain,{"field":"y","aggregate":"mean","type":"quantitative"}]"""),
      "the last entry is the one left standing, so the tooltip is that one line",
    )
  }

  /**
   * Upstream's asymmetry, kept: an aggregating entry written **before** a plain one leaves the
   * whole list on the channel, and the part view summarises the summary.
   */
  @Test
  fun `an aggregating entry before a plain one summarises twice`() {
    assertEquals(
      listOf(
        "x,c | mean(y)→mean_y, mean(y)→center_y, stderr(y)→extent_y",
        "x,lower_y,upper_y,c | mean(y)→mean_y",
      ),
      summaries(""""tooltip":[{"field":"y","aggregate":"mean","type":"quantitative"},$plain]"""),
    )
  }

  /** A `count` is the one measure with nothing to be a measure *of*, and names itself. */
  @Test
  fun `a counting tooltip entry counts the rows of the group`() {
    assertEquals(
      listOf("x,c | count(-)→__count, stderr(y)→extent_y, mean(y)→center_y"),
      summaries(""""tooltip":[$plain,{"aggregate":"count","type":"quantitative"}]"""),
    )
    assertEquals(
      """format(datum["__count"], "")""",
      tooltip(""""tooltip":[$plain,{"aggregate":"count","type":"quantitative"}]"""),
    )
  }

  /**
   * An `argmax` produces the whole extreme **row**, so it is named after the column it was taken
   * over and the channel reads one step further in.
   */
  @Test
  fun `an argmax tooltip entry names the column it was taken over`() {
    val entry = """{"field":"p","aggregate":{"argmax":"q"},"type":"quantitative"}"""
    assertEquals(
      listOf("x,c | argmax(p)→argmax_q, stderr(y)→extent_y, mean(y)→center_y"),
      summaries(""""tooltip":[$plain,$entry]"""),
    )
    assertEquals("""format(datum["argmax_q.p"], "")""", tooltip(""""tooltip":[$plain,$entry]"""))
  }

  /** A **bucketed** entry is bucketed above the summary, and groups it by the bucket. */
  @Test
  fun `a bucketed tooltip entry groups the summary by its bucket`() {
    val entry = """{"field":"t","timeUnit":"year","type":"temporal"}"""
    assertEquals(
      listOf("x,c,year_t | stderr(y)→extent_y, mean(y)→center_y"),
      summaries(""""tooltip":[$plain,$entry]"""),
    )
    assertEquals(
      """timeFormat(datum["year_t"], "%b %d, %Y")""",
      tooltip(""""tooltip":[$plain,$entry]"""),
    )
  }

  /**
   * A column that arrived **already** bucketed keeps its own name — there is nothing left to bucket
   * — but the unit still comes off the channel.
   */
  @Test
  fun `a pre-bucketed tooltip entry groups by the column as it stands`() {
    val entry = """{"field":"t","timeUnit":"binnedyearmonth","type":"temporal"}"""
    assertEquals(
      listOf("x,c,t | stderr(y)→extent_y, mean(y)→center_y"),
      summaries(""""tooltip":[$plain,$entry]"""),
    )
    assertEquals(
      """timeFormat(datum["t"], "%b %d, %Y")""",
      tooltip(""""tooltip":[$plain,$entry]"""),
    )
  }

  /**
   * The output column is named as `vgField(…, {forAs: true})` names it: a space stays a space, this
   * being a name and not an identifier.
   */
  @Test
  fun `a measure of a column with a space in its name keeps the space`() {
    assertEquals(
      listOf("x,c | mean(a b)→mean_a b, stderr(y)→extent_y, mean(y)→center_y"),
      summaries(""""tooltip":[$plain,{"field":"a b","aggregate":"mean","type":"quantitative"}]"""),
    )
  }

  /** It is not a tooltip's rule: any channel of the error bar asks the summary the same way. */
  @Test
  fun `an aggregating colour asks the summary for its measure`() {
    assertEquals(
      listOf("x | max(g)→max_g, stderr(y)→extent_y, mean(y)→center_y"),
      summaries(""""color":{"field":"g","aggregate":"max","type":"quantitative"}"""),
    )
    assertEquals(
      """{"Mean of y": format(datum["center_y"], ""), "Mean + stderr of y": """ +
        """format(datum["upper_y"], ""), "Mean - stderr of y": format(datum["lower_y"], ""), """ +
        """"x": format(datum["x"], ""), "Max of g": format(datum["max_g"], "")}""",
      tooltip(""""color":{"field":"g","aggregate":"max","type":"quantitative"}"""),
      "the rewritten channel carries the title it was asked by into the derived tooltip",
    )
  }
}
