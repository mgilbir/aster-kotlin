package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A stated scale type is **checked** before it is used.
 *
 * ```js
 * if (type !== undefined) {
 *   // Check if explicitly specified scale type is supported by the channel
 *   if (!channelSupportScaleType(channel, type)) {
 *     log.warn(log.message.scaleTypeNotWorkWithChannel(channel, type, defaultScaleType));
 *     return defaultScaleType;
 *   }
 *
 *   // Check if explicitly specified scale type is supported by the data type
 *   if (isFieldDef(fieldDef) && !scaleTypeSupportDataType(type, fieldDef.type)) {
 *     log.warn(log.message.scaleTypeNotWorkWithFieldDef(type, defaultScaleType));
 *     return defaultScaleType;
 *   }
 *
 *   return type;
 * }
 * ```
 *
 * Two questions, and a `scale: {"type": …}` that fails either one is dropped for the default the
 * channel would have taken anyway. The first asks what the **channel** can carry: there is no band
 * of colour, and a shape chooses between symbols so only a scale whose range is a list can drive
 * one. The second asks what the **field** can sit on: a `threshold` over a list of country names
 * has no extent to cut into pieces, and the scale would go to Vega with nothing to be a domain.
 *
 * This engine took whatever was written, so one wild-corpus specification asked for a threshold
 * scale over its nominal `shape` and got a scale Vega could make nothing of — along with a second
 * legend, the discrete one it should have had being the only one upstream draws.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class StatedScaleTypeTest {

  /** Each scale and the type it settled on. */
  private fun types(encoding: String): String {
    val spec =
      """{"data":{"values":[{"a":"x","b":2,"t":"2020-01-01"}]},"mark":"point",
         "encoding":{$encoding}}"""
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    return (compiled.fields["scales"] as VegaValue.Arr).values.joinToString(" ") {
      it as VegaValue.Obj
      "${it.string("name")}:${it.string("type") ?: "linear"}"
    }
  }

  /** The reported shape: a `threshold` scale asked for over a list of names. */
  @Test
  fun `a threshold scale over a category is refused`() {
    assertEquals(
      "x:linear shape:ordinal",
      types(
        """"x":{"field":"b","type":"quantitative"},
           "shape":{"field":"a","type":"nominal","scale":{"type":"threshold"}}"""
      ),
    )
  }

  /** The same channel with a type it *can* carry keeps it, which is the whole of what must hold. */
  @Test
  fun `an ordinal scale over a category is kept`() {
    assertEquals(
      "x:linear shape:ordinal",
      types(
        """"x":{"field":"b","type":"quantitative"},
           "shape":{"field":"a","type":"nominal","scale":{"type":"ordinal"}}"""
      ),
    )
  }

  /** A category cannot be measured continuously, whatever the channel would allow. */
  @Test
  fun `a linear scale over a category falls back to the point scale`() {
    assertEquals(
      "x:point y:linear",
      types(
        """"x":{"field":"a","type":"nominal","scale":{"type":"linear"}},
           "y":{"field":"b","type":"quantitative"}"""
      ),
    )
  }

  /** An instant is a clock: only `time` and `utc` are its scales. */
  @Test
  fun `a linear scale over an instant falls back to the time scale`() {
    assertEquals(
      "x:time y:linear",
      types(
        """"x":{"field":"t","type":"temporal","scale":{"type":"linear"}},
           "y":{"field":"b","type":"quantitative"}"""
      ),
    )
  }

  /** And `utc`, which is the other one, is kept. */
  @Test
  fun `a utc scale over an instant is kept`() {
    assertEquals(
      "x:utc y:linear",
      types(
        """"x":{"field":"t","type":"temporal","scale":{"type":"utc"}},
           "y":{"field":"b","type":"quantitative"}"""
      ),
    )
  }

  /** A number has no categories to band, even on a position, which bands categories happily. */
  @Test
  fun `a band scale over a number is refused`() {
    assertEquals(
      "x:linear y:linear",
      types(
        """"x":{"field":"b","type":"quantitative","scale":{"type":"band"}},
           "y":{"field":"b","type":"quantitative"}"""
      ),
    )
  }

  /** The **channel**'s own question, which is the one asked first: there is no band of colour. */
  @Test
  fun `a band scale on colour is refused`() {
    assertEquals(
      "x:linear color:linear",
      types(
        """"x":{"field":"b","type":"quantitative"},
           "color":{"field":"b","type":"quantitative","scale":{"type":"band"}}"""
      ),
    )
  }

  /** Colour carries a `threshold` over a number, which is a step scale asked for on purpose. */
  @Test
  fun `a threshold scale on colour over a number is kept`() {
    assertEquals(
      "x:linear color:threshold",
      types(
        """"x":{"field":"b","type":"quantitative"},
           "color":{"field":"b","type":"quantitative","scale":{"type":"threshold"}}"""
      ),
    )
  }

  /** A magnitude carries a category on a point scale, the field's type refusing the stated one. */
  @Test
  fun `a linear scale on size over a category is refused`() {
    assertEquals(
      "x:linear size:point",
      types(
        """"x":{"field":"b","type":"quantitative"},
           "size":{"field":"a","type":"nominal","scale":{"type":"linear"}}"""
      ),
    )
  }

  /**
   * The **channel**'s question alone, the field's own type having no objection: a category sits on
   * a band happily, and it is colour that will not have one.
   */
  @Test
  fun `a band scale on colour over a category is refused`() {
    assertEquals(
      "x:linear color:ordinal",
      types(
        """"x":{"field":"b","type":"quantitative"},
           "color":{"field":"a","type":"nominal","scale":{"type":"band"}}"""
      ),
    )
  }

  /** And the other way round: a number is linear anywhere except on a channel of symbols. */
  @Test
  fun `a linear scale on shape over a number is refused`() {
    assertEquals(
      "x:linear shape:ordinal",
      types(
        """"x":{"field":"b","type":"quantitative"},
           "shape":{"field":"b","type":"quantitative","scale":{"type":"linear"}}"""
      ),
    )
  }

  /** A position takes a band where one is asked for, a point being what it would have chosen. */
  @Test
  fun `a band scale on a position over a category is kept`() {
    assertEquals(
      "x:band y:linear",
      types(
        """"x":{"field":"a","type":"nominal","scale":{"type":"band"}},
           "y":{"field":"b","type":"quantitative"}"""
      ),
    )
  }

  /** A magnitude carries an ordinal, "since we use band: 0.5 to get midpoint". */
  @Test
  fun `an ordinal scale on size over a category is kept`() {
    assertEquals(
      "x:linear size:ordinal",
      types(
        """"x":{"field":"b","type":"quantitative"},
           "size":{"field":"a","type":"nominal","scale":{"type":"ordinal"}}"""
      ),
    )
  }

  /** The clock takes a band or a line and nothing else: its range is a frame per step. */
  @Test
  fun `a point scale on time is refused`() {
    assertEquals(
      "x:linear time:band",
      types(
        """"x":{"field":"b","type":"quantitative"},
           "time":{"field":"a","type":"nominal","scale":{"type":"point"}}"""
      ),
    )
  }

  /** A **datum** is not a field, so the second question is not asked of it at all. */
  @Test
  fun `a datum keeps its stated scale type`() {
    assertEquals(
      "x:log y:linear",
      types(""""x":{"datum":5,"scale":{"type":"log"}},"y":{"field":"b","type":"quantitative"}"""),
    )
  }

  /** Even where the datum says what it *is*: a literal is still not a column of that type. */
  @Test
  fun `a datum keeps a scale type its own stated type would refuse`() {
    assertEquals(
      "x:log y:linear",
      types(
        """"x":{"datum":"x","type":"nominal","scale":{"type":"log"}},
           "y":{"field":"b","type":"quantitative"}"""
      ),
    )
  }

  /** The channel's question **is** asked of a datum, which is the half that is not skipped. */
  @Test
  fun `a datum is refused a scale type its channel cannot carry`() {
    assertEquals(
      "x:linear color:linear",
      types(
        """"x":{"field":"b","type":"quantitative"},"color":{"datum":5,"scale":{"type":"band"}}"""
      ),
    )
  }

  /** A refusal is **reported**: a scale that is not the one asked for is not a silent detail. */
  @Test
  fun `a refused scale type is reported once`() {
    val reported =
      VegaLiteCompiler()
        .compileJson(
          """{"data":{"values":[{"a":"x","b":2}]},"mark":"point",
             "encoding":{"x":{"field":"b","type":"quantitative"},
               "shape":{"field":"a","type":"nominal","scale":{"type":"threshold"}}}}"""
        )
        .diagnostics
        .filter { it.code == VegaLiteDiagnostics.INVALID_ENCODING }
    assertEquals(1, reported.size, reported.toString())
    assertTrue(
      reported.single().message.contains("threshold") &&
        reported.single().message.contains("ordinal"),
      reported.single().message,
    )
  }
}
