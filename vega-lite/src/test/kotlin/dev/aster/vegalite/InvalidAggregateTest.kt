package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A channel summarised by a word that is no operation is summarised by nothing.
 *
 * ```js
 * // Drop invalid aggregate
 * if (!compositeMark && aggregate && !isAggregateOp(aggregate) && !isArgmaxDef(aggregate) && !isArgminDef(aggregate)) {
 *   log.warn(log.message.invalidAggregate(aggregate));
 *   delete fieldDef.aggregate;
 * }
 * ```
 *
 * `isAggregateOp` asks `AGGREGATE_OP_INDEX` for the word **as written**, so `"Mean"` is no more an
 * operation than `"null"` is, and `initFieldDef` deletes what it does not know. The channel is then
 * the plain column it names and everything downstream reads it as one: the field keeps its own
 * name, the axis its own title, and a bar whose measure is no longer summarised **stacks** — a
 * stack being what an unsummarised measure over a category is.
 *
 * Kept instead, `{"aggregate": "null"}` reached Vega as an `aggregate` transform asking for an
 * operation called `null`, and every column that summary would have produced was named after it:
 * `null_Salary` beneath an axis reading `Null of Salary`. Two specifications in the wild corpus
 * write exactly that.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class InvalidAggregateTest {

  /** What the chart derives, what its axes are called, and where the mark's measure comes from. */
  private fun summarised(y: String, mark: String = "bar"): String {
    val spec =
      """{"data":{"values":[{"a":1,"b":2,"c":"x"}]},"mark":"$mark",
         "encoding":{"x":{"field":"c","type":"nominal"},"y":$y}}"""
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    fun written(value: VegaValue?) =
      VegaJson.write(value ?: VegaValue.Null).replace(Regex("""\n\s*"""), "")
    val derived =
      (compiled.fields["data"] as VegaValue.Arr).values.drop(1).joinToString(";") {
        written((it as VegaValue.Obj).fields["transform"])
      }
    val titles =
      (compiled.fields["axes"] as? VegaValue.Arr)?.values.orEmpty().map {
        (it as VegaValue.Obj).string("title")
      }
    val update =
      ((compiled.fields["marks"] as VegaValue.Arr).values[0] as VegaValue.Obj)
        .obj("encode")
        ?.obj("update")
    return "derived=$derived titles=$titles " +
      "y=${written(update?.fields?.get("y"))} y2=${written(update?.fields?.get("y2"))}"
  }

  /** The reported shape: a measure summarised by the *string* `"null"`. */
  @Test
  fun `a word that is no operation summarises nothing`() {
    assertEquals(
      """derived=[{"type": "stack","groupby": ["c"],"field": "a","sort": {"field": [],""" +
        """"order": []},"as": ["a_start","a_end"],"offset": "zero"},{"type": "filter",""" +
        """"expr": "isValid(datum[\"a\"]) && isFinite(+datum[\"a\"])"}] """ +
        """titles=[null, c, a] y={"scale": "y","field": "a_end"} """ +
        """y2={"scale": "y","field": "a_start"}""",
      summarised("""{"field":"a","type":"quantitative","aggregate":"null"}"""),
    )
  }

  /** The index is read as written, so the operation's own spelling is the only one. */
  @Test
  fun `an operation misspelt by its case is no operation`() {
    assertEquals(
      summarised("""{"field":"a","type":"quantitative","aggregate":"null"}"""),
      summarised("""{"field":"a","type":"quantitative","aggregate":"Mean"}"""),
    )
  }

  /** And the operation itself still summarises, which is the whole of what must not change. */
  @Test
  fun `an operation the index knows still summarises`() {
    assertEquals(
      """derived=[{"type": "aggregate","groupby": ["c"],"ops": ["mean"],"fields": ["a"],""" +
        """"as": ["mean_a"]},{"type": "filter","expr": "isValid(datum[\"mean_a\"]) && """ +
        """isFinite(+datum[\"mean_a\"])"}] titles=[null, c, Mean of a] """ +
        """y={"scale": "y","field": "mean_a"} y2={"scale": "y","value": 0}""",
      summarised("""{"field":"a","type":"quantitative","aggregate":"mean"}"""),
    )
  }

  /** An operation that answers with a whole row is one of the twenty-five, written as an object. */
  @Test
  fun `an argmax still summarises`() {
    assertEquals(
      """derived=[{"type": "aggregate","groupby": ["c"],"ops": ["argmax"],"fields": ["b"],""" +
        """"as": ["argmax_b"]},{"type": "filter","expr": "isValid(datum[\"argmax_b\"][\"a\"])""" +
        """ && isFinite(+datum[\"argmax_b\"][\"a\"])"}] titles=[null, c, a for max b] """ +
        """y={"scale": "y","field": "argmax_b[\"a\"]"} y2={"scale": "y","value": 0}""",
      summarised("""{"field":"a","type":"quantitative","aggregate":{"argmax":"b"}}"""),
    )
  }

  /**
   * Dropped **before** the channel is asked whether it has anything to draw: a `count` needs no
   * column of its own, so a channel whose only content was the operation has nothing left at all
   * and goes with it.
   */
  @Test
  fun `a channel whose only content was the operation is dropped`() {
    assertEquals(
      """derived= titles=[c] y={"value": 0} y2={"field": {"group": "height"}}""",
      summarised("""{"aggregate":"nope","type":"quantitative"}"""),
    )
  }

  /** And before the **type** is settled, which is what counting rows would have decided. */
  @Test
  fun `a count of rows is measured and a word that is no operation is not`() {
    assertEquals(
      """derived=[{"type": "aggregate","groupby": ["c"],"ops": ["count"],"fields": [null],""" +
        """"as": ["__count"]}] titles=[null, c, Count of Records] """ +
        """y={"scale": "y","field": "__count"} y2=null""",
      summarised("""{"field":"a","aggregate":"count"}""", mark = "point"),
    )
    assertEquals(
      """derived= titles=[c, a] y={"scale": "y","field": "a"} y2=null""",
      summarised("""{"field":"a","aggregate":"nope"}""", mark = "point"),
    )
  }

  /** It is reported, as upstream warns: a chart drawn otherwise than it was written says so. */
  @Test
  fun `dropping it is reported`() {
    val spec =
      """{"data":{"values":[{"a":1,"c":"x"}]},"mark":"bar",
         "encoding":{"x":{"field":"c","type":"nominal"},
                     "y":{"field":"a","type":"quantitative","aggregate":"null"}}}"""
    val diagnostics = VegaLiteCompiler().compileJson(spec).diagnostics
    assertEquals(
      listOf(VegaLiteDiagnostics.INVALID_ENCODING to "$.encoding.y.aggregate"),
      diagnostics.map { it.code to it.jsonPath },
    )
  }
}
