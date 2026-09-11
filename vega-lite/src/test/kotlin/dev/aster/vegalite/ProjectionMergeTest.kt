package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Two layers' projections merge when one of them said nothing.
 *
 * ```js
 * const size = deepEqual(first.size, second.size);
 * if (size) {
 *   if (allPropertiesShared) {
 *     return first;
 *   } else if (deepEqual(first.explicit, {})) {
 *     return second;
 *   } else if (deepEqual(second.explicit, {})) {
 *     return first;
 *   }
 * }
 * return null;
 * ```
 *
 * A member that stated nothing **agrees** with one that did, and the merge takes the one that
 * spoke. This engine compared the two specifications for equality, so a map layered under another
 * map where only the upper one names its kind came out with a projection each — and two projections
 * fitted to two different sets of outlines draw the same country at two sizes. Six specifications
 * in the wild corpus layer their maps that way.
 *
 * The **order** of what a merged projection is fitted to follows from how upstream builds it: the
 * component starts as a copy of the fold's own data — whichever child the fold ended on — and then
 * every fitted child is appended in order. So the child that *spoke* is fitted first.
 *
 * Where they disagree, or where one is placed by hand and the other fitted (their sizes then being
 * a pair of pixels and nothing at all), each member keeps its own.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class ProjectionMergeTest {

  private fun projections(first: String, second: String): VegaValue? {
    val spec =
      """{"layer":[
         {"data":{"url":"http://example.test/a.json","format":{"type":"topojson","feature":"x"}},
          "mark":"geoshape"$first},
         {"data":{"url":"http://example.test/b.json","format":{"type":"topojson","feature":"y"}},
          "mark":"geoshape"$second}]}"""
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    return compiled.fields["projections"]
  }

  private fun json(text: String) = VegaJson.parse(text)

  /** The reported shape: the upper layer names the kind of map and the lower one says nothing. */
  @Test
  fun `a member that said nothing agrees with one that did`() {
    assertEquals(
      json(
        """[{"name":"projection","size":{"signal":"[width, height]"},
            "fit":{"signal":"[data('source_1'), data('source_0')]"},"type":"mercator"}]"""
      ),
      projections("", ""","projection":{"type":"mercator"}"""),
    )
  }

  /** Both naming the same kind is the same one projection, fitted in child order. */
  @Test
  fun `two members naming the same kind are one projection`() {
    assertEquals(
      json(
        """[{"name":"projection","size":{"signal":"[width, height]"},
            "fit":{"signal":"[data('source_0'), data('source_1')]"},"type":"mercator"}]"""
      ),
      projections(""","projection":{"type":"mercator"}""", ""","projection":{"type":"mercator"}"""),
    )
  }

  /** And two that say nothing are one projection with nothing said: no default is invented. */
  @Test
  fun `two silent members are one projection with no kind`() {
    assertEquals(
      json(
        """[{"name":"projection","size":{"signal":"[width, height]"},
            "fit":{"signal":"[data('source_0'), data('source_1')]"}}]"""
      ),
      projections("", ""),
    )
  }

  /**
   * Where they disagree each keeps its own, named for the member and fitted to its own outlines.
   */
  @Test
  fun `members that disagree keep a projection each`() {
    assertEquals(
      json(
        """[{"name":"layer_0_projection","size":{"signal":"[width, height]"},
            "fit":{"signal":"data('source_0')"},"type":"mercator"},
           {"name":"layer_1_projection","size":{"signal":"[width, height]"},
            "fit":{"signal":"data('source_1')"},"type":"albersUsa"}]"""
      ),
      projections(
        ""","projection":{"type":"mercator"}""",
        ""","projection":{"type":"albersUsa"}""",
      ),
    )
  }

  /** A projection **placed** by hand has no size to compare, so it merges with nothing. */
  @Test
  fun `a placed projection does not merge with a fitted one`() {
    assertEquals(
      json(
        """[{"name":"layer_0_projection","size":{"signal":"[width, height]"},
            "fit":{"signal":"data('source_0')"},"type":"equalEarth"},
           {"name":"layer_1_projection","translate":{"signal":"[width / 2, height / 2]"},
            "scale":200,"type":"equalEarth"}]"""
      ),
      projections("", ""","projection":{"scale":200}"""),
    )
  }
}
