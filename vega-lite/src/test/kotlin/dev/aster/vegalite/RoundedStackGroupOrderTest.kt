package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A rounded stack's group writes its corner radii in a different place depending on which way the
 * stack runs, and the asymmetry is upstream's own.
 *
 * `getGroupsForStackedBarWithCornerRadius` builds the outer group's encoding from one of two picks,
 * and only one of them names the corner channels:
 * ```js
 * if (model.stack.fieldChannel === 'x') {
 *   groupUpdate = {
 *     ...pick(mark.encode.update, ['y', 'yc', 'y2', 'height', ...VG_CORNERRADIUS_CHANNELS]),
 *     x: {signal: stackFieldGroup('min', 'datum')},
 *     x2: {signal: stackFieldGroup('max', 'datum')},
 *     clip: {value: true},
 *   };
 * } else {
 *   groupUpdate = {
 *     ...pick(mark.encode.update, ['x', 'xc', 'x2', 'width']),
 *     y: {signal: stackFieldGroup('min', 'datum')},
 *     y2: {signal: stackFieldGroup('max', 'datum')},
 *     clip: {value: true},
 *   };
 * }
 * ```
 *
 * A stack along **x** — a bar lying on its side — picks the radii up with the properties that place
 * it across its own direction, so they are written *before* the extent and the clip. A stack along
 * **y** leaves them out, and they arrive later from the loop that moves each corner off the mark:
 * ```js
 * for (const key of VG_CORNERRADIUS_CHANNELS) {
 *   ...
 *   if (mark.encode.update[key]) { groupUpdate[key] = mark.encode.update[key]; ... }
 * ```
 *
 * which in JavaScript, as in a `LinkedHashMap` here, assigns into an existing key without moving
 * it. So naming the radii in the first pick is the whole of what puts them in front, and this
 * compiler appended them uniformly and wrote a horizontal stack's group with its corners last.
 *
 * This is checked here and not by a fixture on purpose: `SpecDiff` ignores object key order, by
 * design and for good reason, so neither the fixture gate nor the schema sweep can see this rule at
 * all. `JavaScriptKeyOrderTest` is the same shape of test for the same reason.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class RoundedStackGroupOrderTest {

  /** The outer group's `encode.update` keys, for a stacked bar whose end is rounded. */
  private fun groupKeys(dimension: String, measure: String): List<String> {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"c":"A","g":"x","v":3},{"c":"A","g":"y","v":5},
                                 {"c":"B","g":"x","v":2},{"c":"B","g":"y","v":7}]},
               "mark":{"type":"bar","cornerRadiusEnd":6},
               "encoding":{"$dimension":{"field":"c","type":"nominal"},
                           "$measure":{"field":"v","type":"quantitative"},
                           "color":{"field":"g","type":"nominal"}}}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    val group = (compiled.fields["marks"] as VegaValue.Arr).values.first() as VegaValue.Obj
    val update = (group.fields["encode"] as VegaValue.Obj).fields["update"] as VegaValue.Obj
    return update.fields.keys.toList()
  }

  /** A stack along **y**: the radii come last, after the extent and the clip. */
  @Test
  fun `a vertical stack rounds its group after clipping it`() {
    assertEquals(
      listOf("x", "width", "y", "y2", "clip", "cornerRadiusTopLeft", "cornerRadiusTopRight"),
      groupKeys(dimension = "x", measure = "y"),
    )
  }

  /** A stack along **x**: the radii are picked up early, before the extent and the clip. */
  @Test
  fun `a horizontal stack rounds its group before clipping it`() {
    assertEquals(
      listOf("y", "height", "cornerRadiusTopRight", "cornerRadiusBottomRight", "x", "x2", "clip"),
      groupKeys(dimension = "y", measure = "x"),
    )
  }
}
