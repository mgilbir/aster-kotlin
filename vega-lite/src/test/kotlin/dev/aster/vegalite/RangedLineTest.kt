package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A **line** given a second position is a `rule`.
 *
 * ```js
 * if (mark === 'line' || (isMarkDef(mark) && mark.type === 'line')) {
 *   for (const channel of SECONDARY_RANGE_CHANNEL) {
 *     const mainChannel = getMainRangeChannel(channel);
 *     const mainChannelDef = encoding[mainChannel];
 *     if (encoding[channel]) {
 *       if ((isFieldDef(mainChannelDef) && !isBinned(mainChannelDef.bin)) || isDatumDef(mainChannelDef)) {
 *         return true;
 *       }
 *     }
 *   }
 * }
 * ```
 *
 * A line is drawn *through* its points and has one position per row; a second position asks for a
 * segment, and a segment is what a rule is. `RuleForRangedLineNormalizer` rewrites the mark and
 * says so.
 *
 * Left a line, such a view kept the mark and lost the second position with it: the far end of every
 * segment was dropped, and a map of great circles came out as a line from each origin to nowhere.
 * One specification in the wild corpus draws its routes that way.
 *
 * A column that arrived already **binned** is a span in itself, and its `x2` is the far edge of
 * that span rather than the far end of a segment — so such a line stays a line.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class RangedLineTest {

  /** The mark type, and the positions it was given. */
  private fun drawn(spec: String): String {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson("""{"data":{"values":[{"a":1,"b":2,"c":3,"d":4}]},$spec}""")
            .toJson()
        ) {
          "no output"
        }
      ) as VegaValue.Obj
    val mark = (compiled.fields["marks"] as VegaValue.Arr).values[0] as VegaValue.Obj
    val update = mark.obj("encode")!!.obj("update")!!
    val positions =
      update.fields
        .filterKeys { it in setOf("x", "x2", "y", "y2") }
        .entries
        .joinToString(",") { (key, value) ->
          "$key=${VegaJson.write(value).replace(Regex("""\n\s*"""), "")}"
        }
    return "${mark.string("type")} $positions"
  }

  private val q = { f: String -> """{"field":"$f","type":"quantitative"}""" }

  /** The reported shape: a segment between two places. */
  @Test
  fun `a line given a far latitude is a rule`() {
    assertEquals(
      """rule x={"field": "x"},x2={"field": "x2"},y={"field": "y"},y2={"field": "y2"}""",
      drawn(
        """"mark":"line","encoding":{"longitude":${q("a")},"latitude":${q("c")},
           "latitude2":{"field":"d"}}"""
      ),
    )
  }

  /** The same along the page rather than the globe. */
  @Test
  fun `a line given a far x is a rule`() {
    assertEquals(
      """rule x={"scale": "x","field": "a"},x2={"scale": "x","field": "b"},""" +
        """y={"scale": "y","field": "c"}""",
      drawn(""""mark":"line","encoding":{"x":${q("a")},"x2":{"field":"b"},"y":${q("c")}}"""),
    )
  }

  /** And along the other one. */
  @Test
  fun `a line given a far y is a rule`() {
    assertEquals(
      """rule x={"scale": "x","field": "a"},y={"scale": "y","field": "c"},""" +
        """y2={"scale": "y","field": "d"}""",
      drawn(""""mark":"line","encoding":{"x":${q("a")},"y":${q("c")},"y2":{"field":"d"}}"""),
    )
  }

  /**
   * A column that arrived **binned** is a span already, so its line stays a line.
   *
   * Only the mark is asserted here. Upstream also places such a line at the middle of each bucket —
   * `scale("x", 0.5 * datum["a"] + 0.5 * datum["b"])` — where this compiler places it at the near
   * edge; a gap of its own, and not this rule's.
   */
  @Test
  fun `a line over a binned column stays a line`() {
    assertEquals(
      "line",
      drawn(
          """"mark":"line","encoding":{"x":{"field":"a","type":"quantitative","bin":"binned"},
           "x2":{"field":"b"},"y":${q("c")}}"""
        )
        .substringBefore(" "),
    )
  }

  /** A line with one position per row is a line, which is every other chart. */
  @Test
  fun `a line with no second position is a line`() {
    assertEquals(
      """line x={"scale": "x","field": "a"},y={"scale": "y","field": "c"}""",
      drawn(""""mark":"line","encoding":{"x":${q("a")},"y":${q("c")}}"""),
    )
  }

  /** The rest of the mark's definition is kept: only its type changes. */
  @Test
  fun `a written-out line keeps everything but its type`() {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """{"data":{"values":[{"a":1,"b":2,"c":3}]},
                 "mark":{"type":"line","strokeWidth":4,"color":"red"},
                 "encoding":{"x":${q("a")},"x2":{"field":"b"},"y":${q("c")}}}"""
            )
            .toJson()
        ) {
          "no output"
        }
      ) as VegaValue.Obj
    val mark = (compiled.fields["marks"] as VegaValue.Arr).values[0] as VegaValue.Obj
    val update = mark.obj("encode")!!.obj("update")!!
    assertEquals(
      """rule {"value": 4} {"value": "red"}""",
      ("${mark.string("type")} ${VegaJson.write(update.fields["strokeWidth"]!!)} " +
          VegaJson.write(update.fields["stroke"]!!))
        .replace(Regex("""\n\s*"""), ""),
    )
  }

  /** And the rewrite is **reported**: a mark drawn as something else is not a silent detail. */
  @Test
  fun `the rewrite is reported`() {
    val reported =
      VegaLiteCompiler()
        .compileJson(
          """{"data":{"values":[{"a":1,"b":2,"c":3}]},"mark":"line",
             "encoding":{"x":${q("a")},"x2":{"field":"b"},"y":${q("c")}}}"""
        )
        .diagnostics
        .filter { it.code == VegaLiteDiagnostics.UNSUPPORTED_ENCODING_PROPERTY }
    assertEquals(1, reported.size, reported.toString())
    assertTrue(reported.single().message.contains("rule"), reported.single().message)
  }
}
