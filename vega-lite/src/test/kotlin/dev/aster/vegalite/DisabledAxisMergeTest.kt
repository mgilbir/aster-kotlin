package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * An axis one layer switches off is switched off for the scale.
 *
 * `parseAxis` keeps a disabled axis as a **component** rather than answering nothing:
 * ```js
 * const disable = axis !== undefined ? !axis : getAxisConfig('disable', …).configValue;
 * axisComponent.set('disable', disable, axis !== undefined);
 * if (disable) {
 *   return axisComponent;
 * }
 * ```
 *
 * `axis !== undefined` makes a stated `"axis": null` an *explicit* decision, and an explicit value
 * beats every sibling's in `mergeValuesWithExplicit`. So the component still takes part in the
 * merge, and carries the decision into it.
 *
 * This returned nothing at all for such a layer, so the layer simply did not contribute and the
 * others put the axis back. A chart whose first layer draws its own time axis and whose later
 * layers are drawn against the same scale — five in the wild corpus — came out with an axis
 * upstream does not draw.
 *
 * The expectations are upstream's. One case is missing on purpose: with the layers the other way
 * round, upstream **throws** inside `mergeTitleFieldDefs`, so there is nothing to match. This
 * disables the axis whichever side the decision arrives on, which agrees with upstream everywhere
 * upstream compiles at all.
 */
class DisabledAxisMergeTest {

  private fun axisScales(spec: String): List<String> {
    val compiled =
      VegaJson.parse(
        requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "did not compile" }
      ) as VegaValue.Obj
    return (compiled.fields["axes"] as? VegaValue.Arr)
      ?.values
      ?.mapNotNull { (it as? VegaValue.Obj)?.fields?.get("scale") as? VegaValue.Str }
      ?.map { it.value }
      .orEmpty()
  }

  private fun layered(first: String, second: String) =
    axisScales(
      """
      {"data":{"values":[{"a":1,"b":2}]},
       "layer":[{"mark":"line","encoding":{"x":$first,"y":{"field":"b","type":"quantitative"}}},
                {"mark":"line","encoding":{"x":$second,"y":{"field":"b","type":"quantitative"}}}]}
      """
    )

  private val plain = """{"field":"a","type":"quantitative"}"""
  private val nulled = """{"field":"a","type":"quantitative","axis":null}"""
  private val titled = """{"field":"a","type":"quantitative","axis":{"title":"T"}}"""

  /** The reported shape: the first layer turns its horizontal axis off. */
  @Test
  fun `an axis switched off in one layer is not drawn for the others`() {
    assertEquals(listOf("y", "y"), layered(nulled, plain), "the gridline and the axis, both y")
  }

  /** Even against a sibling that states something about the axis: explicit beats explicit. */
  @Test
  fun `a sibling that titles the axis does not bring it back`() {
    assertEquals(listOf("y", "y"), layered(nulled, titled))
  }

  /** Both layers agreeing is the same answer by an easier route. */
  @Test
  fun `two layers that both switch it off draw no axis`() {
    assertEquals(listOf("y", "y"), layered(nulled, nulled))
  }

  /**
   * The decision carries whichever side of the merge it arrives on.
   *
   * This is the one case with no upstream expectation to match: asked to compile it, upstream
   * **throws** inside `mergeTitleFieldDefs`, because the disabled component it merges into has no
   * title to fold. Answering "no axis" is what it does in every ordering it survives, and it is the
   * only answer consistent with `disable` being explicit — so that is what this does rather than
   * reproducing a crash.
   */
  @Test
  fun `a switch in the second layer disables it too`() {
    assertEquals(listOf("y", "y"), layered(plain, nulled))
  }

  /** A single view was never affected, and is kept honest. */
  @Test
  fun `a lone view still honours its own switch`() {
    assertEquals(
      listOf("y", "y"),
      axisScales(
        """
        {"data":{"values":[{"a":1,"b":2}]},"mark":"line",
         "encoding":{"x":$nulled,"y":{"field":"b","type":"quantitative"}}}
        """
      ),
    )
  }

  /** And two ordinary layers still get both axes, so the rule reaches no further than it should. */
  @Test
  fun `two ordinary layers keep both axes`() {
    assertEquals(setOf("x", "y"), layered(plain, plain).toSet())
    assertEquals(4, layered(plain, plain).size, "a gridline and an axis for each")
  }
}
