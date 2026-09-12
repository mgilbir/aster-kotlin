package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A channel the mark does not **grow** along reads its column as it stands, stack or no stack.
 *
 * ```js
 * const posRef = ref.midPointRefWithPositionInvalidTest({
 *   channel, channelDef: fieldDef, markDef, config, scaleName, scale, stack, offset, ...
 * });
 * ```
 *
 * `positionAndSize` hands `midPoint` the stack, but only so that an **imputed** bin can be read by
 * its middle: nowhere in that function does a stack put an `_end` on the column. The two ends of a
 * stack are written by `rangePosition`, which is where a channel a mark grows along is settled.
 *
 * Suffixed here as well, a bar turned by a bucketed position — one that spans its two ends along
 * the other axis, and so is only a five-unit marker along this one — was placed at the top of a
 * total it is no part of. On a single row that is the same place; on several it is not.
 *
 * One specification in the wild corpus draws its buckets that way.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class CentredStackPositionTest {

  /** The position channels the mark is given, each with the column or value it reads. */
  private fun placed(encoding: String, mark: String = "bar"): String {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """{"data":{"values":[{"s":0,"e":2,"c":3,"k":"a"}]},
                 "mark":"$mark","encoding":{$encoding}}"""
            )
            .toJson()
        ) {
          "no output"
        }
      ) as VegaValue.Obj
    return ((compiled.fields["marks"] as VegaValue.Arr).values[0] as VegaValue.Obj)
      .obj("encode")!!
      .obj("update")!!
      .fields
      .entries
      .filter { it.key in setOf("x", "x2", "xc", "y", "y2", "yc", "width", "height") }
      .joinToString(",") { (key, value) ->
        value as VegaValue.Obj
        val read =
          value.string("field")
            ?: value.fields["value"]?.let { VegaJson.write(it) }
            ?: value.string("signal")?.let { "signal" }
        "$key=$read"
      }
  }

  private val q = { f: String -> """{"field":"$f","type":"quantitative"}""" }

  /** The reported shape: a bar spanning a bucket, marking a height it does not grow to. */
  @Test
  fun `a marker reads the plain column`() {
    assertEquals(
      "x2=bin_step_2_s,x=bin_step_2_s_end,yc=c,height=5",
      placed(
        """"x":{"field":"s","type":"quantitative","bin":{"step":2}},"x2":{"field":"e"},
           "y":${q("c")}"""
      ),
    )
  }

  /** The same read along the other axis. */
  @Test
  fun `a marker along the other axis reads the plain column too`() {
    assertEquals(
      "xc=c,width=5,y2=bin_step_2_s,y=bin_step_2_s_end",
      placed(
        """"y":{"field":"s","type":"quantitative","bin":{"step":2}},"y2":{"field":"e"},
           "x":${q("c")}"""
      ),
    )
  }

  /** A bar that **does** grow along the channel reads both ends of the stack, as it always did. */
  @Test
  fun `a growing bar reads both ends`() {
    assertEquals(
      "x=k,width=signal,y=c_end,y2=c_start",
      placed(""""x":{"field":"k","type":"nominal"},"y":${q("c")}"""),
    )
    assertEquals(
      "x=c_end,x2=c_start,y=k,height=signal",
      placed(""""y":{"field":"k","type":"nominal"},"x":${q("c")}"""),
    )
  }

  /** A tick marks the place it measures rather than growing to it, and reads the column plainly. */
  @Test
  fun `a tick reads the plain column`() {
    assertEquals(
      "x=k,width=signal,yc=c,height=1",
      placed(""""x":{"field":"k","type":"nominal"},"y":${q("c")}""", mark = "tick"),
    )
  }
}
