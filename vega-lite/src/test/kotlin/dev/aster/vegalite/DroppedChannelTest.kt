package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A channel the mark has nothing to set from is **dropped from the encoding**.
 *
 * `initEncoding` is the first thing a unit does with its encoding, and it drops four kinds of
 * channel before anything else reads one:
 * ```js
 * if (!markChannelCompatible(encoding, channel, mark)) { log.warn(…); continue; }
 * if (channel === SIZE && mark === 'line') { … if (fieldDef?.aggregate) continue; }
 * if (channel === COLOR && (filled ? 'fill' in encoding : 'stroke' in encoding)) continue;
 * // and an offset nested inside a continuous position, above
 * ```
 *
 * Dropping them is not cosmetic. A channel that stays is a grouping of an aggregate, a scale of its
 * own, a field in the chart's spoken description and a line in its tooltip — so a `line` whose
 * shared layer states the `text` its sibling label draws described every point by a column the line
 * does not show, and 11 specifications in the wild corpus differed for that family of reasons.
 *
 * The `angle` rewrite belongs with them and has to run **first**: an `angle` on a pie is the slice,
 * so upstream reads it as `theta` — and an `arc` does not support `angle`, so a compatibility check
 * running before the rewrite would drop the very channel that carries the chart.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class DroppedChannelTest {

  private fun compiled(spec: String): VegaValue.Obj =
    VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
      as VegaValue.Obj

  private fun chart(mark: String, encoding: String) =
    """{"data":{"values":[{"a":1,"b":2,"t":"x"}]},"mark":"$mark","encoding":{$encoding}}"""

  /** The scales a chart ends up with, which is where a surviving channel shows first. */
  private fun scales(mark: String, encoding: String): List<String> =
    (compiled(chart(mark, encoding)).fields["scales"] as? VegaValue.Arr)
      ?.values
      .orEmpty()
      .mapNotNull { (it as? VegaValue.Obj)?.string("name") }

  /** What the chart says it is showing — every field of the encoding, in channel order. */
  private fun described(mark: String, encoding: String): List<String> {
    val marks = compiled(chart(mark, encoding)).fields["marks"] as VegaValue.Arr
    val update = (marks.values.first() as VegaValue.Obj).obj("encode")?.obj("update")
    val signal =
      (update?.fields?.get("description") as? VegaValue.Obj)?.string("signal") ?: return emptyList()
    return Regex("\"(?:; )?([^\"]+): \" \\+").findAll(signal).map { it.groupValues[1] }.toList()
  }

  private val position =
    """"x":{"field":"a","type":"quantitative"},"y":{"field":"b","type":"quantitative"}"""
  private val text = """"text":{"field":"t","type":"nominal"}"""

  /** The reported shape: a `text` channel on a mark that draws no text. */
  @Test
  fun `a text channel on a line is dropped`() {
    assertEquals(listOf("a", "b"), described("line", """$position,$text"""))
  }

  /** And on the mark that does draw text, it is kept — the rule reaches no further. */
  @Test
  fun `a text channel on a text mark is kept`() {
    assertEquals(listOf("a", "b", "t"), described("text", """$position,$text"""))
  }

  /** A `shape` is a `point`'s or a `geoshape`'s, and nothing else's. */
  @Test
  fun `a shape channel on a bar is dropped`() {
    val shape = """"shape":{"field":"t","type":"nominal"}"""
    assertEquals(listOf("x", "y"), scales("bar", """$position,$shape"""))
    assertEquals(listOf("a", "b"), described("bar", """$position,$shape"""))
    assertEquals(listOf("x", "y", "shape"), scales("point", """$position,$shape"""))
    assertEquals(listOf("a", "b", "t"), described("point", """$position,$shape"""))
  }

  /**
   * The **second edge** of an interval on a mark that draws a point: kept only where the primary
   * channel's data arrived binned, there being nothing else for an end to mean.
   */
  @Test
  fun `a second edge on a point is kept only for binned data`() {
    val x2 = """"x2":{"field":"b"}"""
    assertEquals(
      listOf("a", "b"),
      described("point", """$position,$x2"""),
      "not binned, so the second edge goes and `a` is one number",
    )
    assertEquals(
      listOf("a", "b"),
      described(
        "point",
        """"x":{"field":"a","bin":"binned","type":"quantitative"},
           "y":{"field":"b","type":"quantitative"},$x2""",
      ),
      "binned, so it is kept — and `a` is now read as the span it names",
    )
    assertEquals(
      "\"a: \" + (!isValid(datum[\"a\"]) || !isFinite(+datum[\"a\"]) ? \"null\" : " +
        "format(datum[\"a\"], \"\") + \" – \" + format(datum[\"b\"], \"\")) + \"; b: \" + " +
        "(format(datum[\"b\"], \"\"))",
      ((compiled(
              chart(
                "point",
                """"x":{"field":"a","bin":"binned","type":"quantitative"},
                 "y":{"field":"b","type":"quantitative"},$x2""",
              )
            )
            .fields["marks"]
            as VegaValue.Arr)
          .values
          .first() as VegaValue.Obj)
        .obj("encode")
        ?.obj("update")
        ?.obj("description")
        ?.string("signal"),
    )
    assertEquals(listOf("a", "b"), described("bar", """$position,$x2"""), "a bar always takes one")
  }

  /** A `geoshape` is placed by its projection, so a position channel means nothing to it. */
  @Test
  fun `a position channel on a geoshape is dropped`() {
    assertEquals(emptyList<String>(), scales("geoshape", position))
  }

  /**
   * An `angle` on an `arc` is the **slice**, and is read as `theta` — at the place the `angle` was
   * written, which is what puts the colour before it in the description.
   *
   * The *scale* order for this one is asserted a commit later: the rewrite is the first thing to
   * put a polar position anywhere but its own slot, and it uncovered that this engine assembles its
   * scales in encoding order where upstream assembles them in `SCALE_CHANNELS` order. The set of
   * scales is the rewrite's own claim, and is checked here.
   */
  @Test
  fun `an angle on an arc is read as theta`() {
    val colour = """"color":{"field":"t","type":"nominal"}"""
    val angle = """"angle":{"field":"a","type":"quantitative"}"""
    assertEquals(setOf("theta", "color"), scales("arc", """$angle,$colour""").toSet())
    assertEquals(listOf("t", "a"), described("arc", """$angle,$colour"""))
    // With a `theta` of its own there is nothing to rewrite, and an `arc` has no angle to set.
    assertEquals(
      listOf("theta", "color"),
      scales("arc", """$angle,"theta":{"field":"b","type":"quantitative"},$colour"""),
    )
    assertEquals(
      listOf("b", "t"),
      described("arc", """$angle,"theta":{"field":"b","type":"quantitative"},$colour"""),
    )
    // And a mark that does turn keeps its angle.
    assertEquals(
      listOf("x", "angle"),
      scales("point", """"x":{"field":"a","type":"quantitative"},$angle"""),
    )
  }

  /** A `line` is one path of one thickness, so a `size` that varies per group is dropped. */
  @Test
  fun `an aggregating size on a line is dropped`() {
    val varying = """"size":{"aggregate":"mean","field":"b","type":"quantitative"}"""
    assertEquals(listOf("x", "y"), scales("line", """$position,$varying"""))
    assertEquals(
      listOf("x", "y", "size"),
      scales("line", """$position,"size":{"field":"b","type":"quantitative"}"""),
      "a size that does not aggregate is kept, upstream's rule being about the aggregate",
    )
    assertEquals(
      listOf("x", "y", "size"),
      scales("trail", """$position,$varying"""),
      "a trail is the mark for a varying thickness, and keeps it",
    )
  }

  /**
   * `color` means *whichever of fill and stroke this mark paints with*, so stating that one as well
   * leaves the colour nothing to set. Which one collides is the mark's own answer.
   */
  @Test
  fun `a colour beside the channel it would have set is dropped`() {
    val colour = """"color":{"field":"t","type":"nominal"}"""
    val x = """"x":{"field":"a","type":"quantitative"}"""
    assertEquals(
      listOf("x", "fill"),
      scales("bar", """$x,$colour,"fill":{"field":"t","type":"nominal"}"""),
      "a bar is filled, so its fill is what the colour would have set",
    )
    assertEquals(
      listOf("x", "color", "stroke"),
      scales("bar", """$x,$colour,"stroke":{"field":"t","type":"nominal"}"""),
      "and a stroke beside it is a second thing to paint, so both stand",
    )
    assertEquals(
      listOf("x", "y", "stroke"),
      scales("line", """$position,$colour,"stroke":{"field":"t","type":"nominal"}"""),
      "a line is an outline, so its stroke is what the colour would have set",
    )
    assertEquals(
      listOf("x", "y", "color", "fill"),
      scales("line", """$position,$colour,"fill":{"field":"t","type":"nominal"}"""),
    )
  }

  /**
   * An **offset nested inside a continuous position** is dropped: offsetting is moving a mark
   * within its own band, and a continuous position has no band. A position bucketed by a time unit
   * has bands after all, and an offset given as a plain value is not a nesting.
   */
  @Test
  fun `an offset inside a continuous position is dropped`() {
    val offset = """"xOffset":{"field":"t","type":"nominal"}"""
    assertEquals(listOf("x", "y"), scales("bar", """$position,$offset"""))
    assertEquals(
      listOf("x", "y", "xOffset"),
      scales(
        "bar",
        """"x":{"field":"t","type":"nominal"},"y":{"field":"b","type":"quantitative"},$offset""",
      ),
      "a discrete position has bands to offset within",
    )
    assertEquals(
      listOf("x", "y", "xOffset"),
      scales(
        "bar",
        """"x":{"field":"a","timeUnit":"year","type":"temporal"},
           "y":{"field":"b","type":"quantitative"},$offset""",
      ),
      "and so does a bucketed instant",
    )
    assertEquals(
      listOf("x", "y"),
      scales("bar", """$position,"xOffset":{"value":5}"""),
      "an offset stated as a value is not a nesting, and needs no scale either way",
    )
  }
}
