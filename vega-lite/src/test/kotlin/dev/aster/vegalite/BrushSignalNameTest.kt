package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A brush's two signals per channel are named from **one** set of claimed names.
 *
 * ```js
 * const signals = new Set<string>();
 * const signalName = (p: SelectionProjection, range: 'data' | 'visual') => {
 *   const suffix = range === 'visual' ? p.channel : p.field;
 *   let sg = varName(`${name}_${suffix}`);
 *   for (let counter = 1; signals.has(sg); counter++) {
 *     sg = varName(`${name}_${suffix}_${counter}`);
 *   }
 *   signals.add(sg);
 *   return {[range]: sg};
 * };
 * …
 * p.signals = {...signalName(p, 'data'), ...signalName(p, 'visual')};
 * ```
 *
 * The **data** name comes from the field and the **visual** one from the channel, the data name is
 * claimed first, and a name already taken gets the first free counter appended. So the `_1` is not
 * a property of the visual signal at all:
 *
 * - a brush over columns called `x` and `y` gives `br_x` to the data and `br_x_1` to the pixels;
 * - a brush whose **y** reads a column called `x` gives `br_x` to the *x channel's pixels* and
 *   `br_x_1` to the *y channel's data*.
 *
 * This engine compared each projection's two names to each other, which catches the first shape and
 * not the second — such a chart then had two signals of one name. And the scale trigger spelled the
 * pixel name out by hand as `«name»_«channel»`, so it inverted the *data* signal and compared an
 * extent with itself: the brush kept its pixels while the scale under it moved. Two specifications
 * in the wild corpus differ for that.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class BrushSignalNameTest {

  private class Brush(val names: List<String>, val trigger: String)

  private fun brush(encoding: String, rows: String): Brush {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":$rows},"params":[{"name":"br","select":{"type":"interval"}}],
               "mark":"point","encoding":$encoding}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    val signals =
      (compiled.fields["signals"] as VegaValue.Arr).values.mapNotNull { it as? VegaValue.Obj }
    return Brush(
      names = signals.mapNotNull { it.string("name") }.filter { it.startsWith("br") },
      trigger =
        signals
          .firstOrNull { it.string("name") == "br_scale_trigger" }
          ?.let { (it.fields["on"] as? VegaValue.Arr)?.values?.firstOrNull() }
          ?.let { (it as VegaValue.Obj).string("update") }
          .orEmpty(),
    )
  }

  /** The reported shape: columns named after the channels they are drawn on. */
  @Test
  fun `columns named after the channels give the pixels the counter`() {
    val brush =
      brush(
        """{"x":{"field":"x","type":"quantitative"},"y":{"field":"y","type":"quantitative"}}""",
        """[{"x":1,"y":2}]""",
      )
    assertEquals(
      listOf("br", "br_x_1", "br_x", "br_y_1", "br_y"),
      brush.names.take(5),
      "the data name is claimed first, so the pixels take the counter",
    )
    assertTrue(
      brush.trigger.startsWith("""(!isArray(br_x) || (+invert("x", br_x_1)[0] === +br_x[0]"""),
      "the trigger inverts the pixels and compares them with the data — was: ${brush.trigger}",
    )
  }

  /** Distinct names need no counter at all. */
  @Test
  fun `columns named otherwise need no counter`() {
    val brush =
      brush(
        """{"x":{"field":"a","type":"quantitative"},"y":{"field":"b","type":"quantitative"}}""",
        """[{"a":1,"b":2}]""",
      )
    assertEquals(listOf("br", "br_x", "br_a", "br_y", "br_b"), brush.names.take(5))
    assertTrue(
      brush.trigger.startsWith("""(!isArray(br_a) || (+invert("x", br_x)[0] === +br_a[0]"""),
      "was: ${brush.trigger}",
    )
  }

  /**
   * The **join** is what `varName` cleans, not the field on its own: a column called `2020_21`
   * starts with a digit, so cleaning it alone prefixes an underscore and the joined name comes out
   * `grid__2020_21` where upstream writes `grid_2020_21`. A selection bound to the **scales** is
   * named the same way, and is where a specification in the wild corpus showed it.
   */
  @Test
  fun `the joined name is what is cleaned`() {
    val rows = """[{"2020_21":1,"b":2}]"""
    val encoding =
      """{"x":{"field":"2020_21","type":"quantitative"},"y":{"field":"b","type":"quantitative"}}"""
    assertEquals(
      listOf("br", "br_x", "br_2020_21", "br_y", "br_b"),
      brush(encoding, rows).names.take(5),
    )
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":$rows},
               "params":[{"name":"br","select":{"type":"interval"},"bind":"scales"}],
               "mark":"circle","encoding":$encoding}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    assertEquals(
      listOf("br", "br_2020_21", "br_b"),
      (compiled.fields["signals"] as VegaValue.Arr)
        .values
        .mapNotNull { (it as? VegaValue.Obj)?.string("name") }
        .filter { it.startsWith("br") }
        .take(3),
      "a selection bound to the scales names its signals from the same join",
    )
  }

  /**
   * The collision that reaches *across* projections: a column called `x` read on the **y** channel
   * takes the counter for its own data name, the x channel's pixels having claimed `br_x` first.
   */
  @Test
  fun `a column named after another channel takes the counter itself`() {
    val brush =
      brush(
        """{"x":{"field":"a","type":"quantitative"},"y":{"field":"x","type":"quantitative"}}""",
        """[{"a":1,"x":2}]""",
      )
    assertEquals(listOf("br", "br_x", "br_a", "br_y", "br_x_1"), brush.names.take(5))
    assertTrue(
      brush.trigger.contains("""(!isArray(br_x_1) || (+invert("y", br_y)[0] === +br_x_1[0]"""),
      "was: ${brush.trigger}",
    )
  }
}
