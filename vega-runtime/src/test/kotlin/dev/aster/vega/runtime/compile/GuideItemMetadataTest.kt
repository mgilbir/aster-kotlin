package dev.aster.vega.runtime.compile

import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asString
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.flatten
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The five channels a guide's `encode` can put on the **item**: `tooltip`, `cursor`, `zindex`,
 * `aria` and `description`.
 *
 * Every one of them is already a mark channel and none was reachable from a guide, so a hoverable
 * axis label, a tick that says what it marks, a label raised over its neighbours and a decorative
 * title kept out of the accessibility tree were all impossible to ask for.
 *
 * Tested here rather than by a fixture because the differential harness cannot see any of them:
 * they reach the item rather than its geometry or its paint, and the comparison reads coordinates,
 * colours and text. The expectations are the values upstream puts on the same items for the same
 * spec, read out of its scenegraph — which is where the resolution against the guide's own datum
 * shows: a tooltip written `{"signal": "'at ' + datum.value"}` on an axis label reads that label's
 * tick.
 */
class GuideItemMetadataTest {

  private val spec =
    """
    {"width": 120, "height": 60, "padding": 5,
     "data": [{"name": "t", "values": [{"c": "a"}, {"c": "b"}]}],
     "scales": [{"name": "s", "type": "linear", "domain": [0, 10], "range": "width"},
                {"name": "o", "type": "ordinal", "domain": {"data": "t", "field": "c"},
                 "range": "category"}],
     "axes": [{"scale": "s", "orient": "bottom", "title": "T", "tickCount": 2, "encode": {
        "labels": {"update": {"tooltip": {"signal": "'at ' + datum.value"},
                              "cursor": {"value": "pointer"},
                              "zindex": {"value": 2},
                              "description": {"signal": "'tick ' + datum.label"}}},
        "ticks": {"update": {"tooltip": {"signal": "datum.value"}}},
        "title": {"update": {"aria": {"value": false}}}}}],
     "legends": [{"fill": "o", "title": "L", "encode": {
        "symbols": {"update": {"tooltip": {"signal": "datum.label"},
                               "cursor": {"value": "crosshair"},
                               "zindex": {"value": 1}}},
        "labels": {"update": {"description": {"signal": "'swatch ' + datum.label"}}},
        "legend": {"update": {"cursor": {"value": "help"}}}}}],
     "marks": []}
    """

  private fun first(role: String): SceneNode {
    val compiled = SpecCompiler().compileJson(spec)
    val scene = requireNotNull(compiled.scene) { compiled.diagnostics.toString() }
    return requireNotNull(scene.flatten().firstOrNull { it.node.metadata.role == role }?.node) {
      "no $role in the scene"
    }
  }

  @Test
  fun `an axis label carries a tooltip, a cursor, a paint order and a description`() {
    val label = first("axis-label").metadata
    // The tooltip and the description are resolved against the **tick**, which is what makes them
    // worth having: one value per label rather than one for the axis.
    assertEquals("at 0", label.tooltip?.asString())
    assertEquals("pointer", label.cursor)
    assertEquals(2, label.zindex)
    assertEquals("tick 0", label.accessibility?.label)
  }

  /**
   * A tooltip that is a bare number stays one: an object becomes a table where a scalar is a line.
   */
  @Test
  fun `a tick tooltip reads the value it marks`() {
    assertEquals(VegaValue.Num(0.0), first("axis-tick").metadata.tooltip)
  }

  /** `aria: false` takes the item out of the tree; upstream marks it `aria-hidden`. */
  @Test
  fun `a title can opt out of the accessibility tree`() {
    assertNull(first("axis-title").metadata.accessibility)
  }

  @Test
  fun `a legend swatch and its label carry them too`() {
    val symbol = first("legend-symbol").metadata
    assertEquals("a", symbol.tooltip?.asString())
    assertEquals("crosshair", symbol.cursor)
    assertEquals(1, symbol.zindex)
    assertEquals("swatch a", first("legend-label").metadata.accessibility?.label)
  }

  /** The legend's own group takes them from `encode.legend`, so a whole legend can be hoverable. */
  @Test
  fun `the legend group takes them from its own block`() {
    assertEquals("help", first("legend").metadata.cursor)
  }

  /** And none of it is reported any more, on any part. */
  @Test
  fun `none of the five is reported`() {
    val reported =
      SpecCompiler().compileJson(spec).diagnostics.filter { d ->
        listOf("tooltip", "cursor", "zindex", "aria", "description").any {
          d.jsonPath?.endsWith(it) == true
        }
      }
    assertEquals(emptyList<String>(), reported.map { it.message })
  }
}
