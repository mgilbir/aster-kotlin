package dev.aster.vega.runtime

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which **kind** of legend a `type` asks for, and what the legend is then called.
 *
 * Not a differential fixture, and the reason is upstream's own: a `type` it does not recognise
 * makes the symbol marks and then **never positions them**. Probed — `type: "symbol"` places its
 * swatches at `(6, 6)` inside each entry and `type: "nonsense"` leaves every one of them at `null`,
 * so a fixture over that shape would be asking this engine to reproduce a legend whose entries are
 * nowhere. That is an accepted divergence, in the sense the Vega-Lite sweep already uses the term:
 * upstream's answer is not one a reader can use, and matching it would draw nothing where this
 * draws a legend.
 *
 * What is worth matching is which kind gets built and what it is called, and both are checked here.
 */
class LegendKindTest {

  private fun compile(type: String?): CompiledScene {
    val typeJson = type?.let { ""","type": $it""" } ?: ""
    val controller = VegaChartController()
    controller.setSpec(
      """
      {"width":100,"height":60,"padding":5,
       "data":[{"name":"t","values":[{"v":0},{"v":10}]}],
       "scales":[{"name":"c","type":"linear","domain":[0,10],"range":{"scheme":"blues"}}],
       "legends":[{"fill":"c"$typeJson}],
       "marks":[]}
      """
        .trimIndent()
    )
    val compiled = controller.lastCompiled!!
    val roles = mutableSetOf<String>()
    var caption: String? = null
    fun walk(n: dev.aster.vega.scene.SceneNode) {
      n.metadata.role?.let { roles += it }
      if (n.metadata.role == "legend") caption = n.metadata.accessibility?.label
      if (n is dev.aster.vega.scene.GroupNode) n.children.forEach { walk(it) }
    }
    compiled.scene?.root?.let { walk(it) }
    return CompiledScene(roles, caption)
  }

  private data class CompiledScene(val roles: Set<String>, val caption: String?)

  @Test
  fun `a falsey type infers the kind from the scale`() {
    // `spec.type || (isContinuous(scale) ? 'gradient' : 'symbol')`. A colour ramp over a linear
    // scale
    // is a gradient, and absent, `null` and `""` all reach that the same way — the `||` is
    // falsiness, so a `0` or a `false` written there would too.
    for (written in listOf(null, "null", "\"\"")) {
      val scene = compile(written)
      assertTrue(
        "legend-gradient" in scene.roles,
        "a type of ${written ?: "nothing"} should infer the gradient the scale implies, got ${scene.roles}",
      )
    }
  }

  @Test
  fun `a type present decides, and only gradient means gradient`() {
    assertTrue("legend-gradient" in compile("\"gradient\"").roles)
    assertTrue("legend-symbol" in compile("\"symbol\"").roles)
    // The one that was wrong: a word nothing recognises is still a value *present*, so it decides,
    // and anything that is not `gradient` draws symbols. This engine used to fail to parse it and
    // infer, drawing the gradient upstream refuses to draw — a plain typo, no signal involved.
    val nonsense = compile("\"nonsense\"")
    assertTrue(
      "legend-symbol" in nonsense.roles && "legend-gradient" !in nonsense.roles,
      "an unrecognised type draws symbols, got ${nonsense.roles}",
    )
  }

  @Test
  fun `the legend is called what it was written`() {
    // Upstream keeps the word and says it verbatim, so a screen reader hears the specification's
    // own
    // term rather than the kind this engine settled on. Drawing and naming are two questions, and a
    // fix that answered only the first made the caption say "Symbol" where upstream says
    // "Nonsense".
    assertEquals(
      "Nonsense legend for fill color with values from 0 to 10",
      compile("\"nonsense\"").caption,
    )
    assertEquals(
      "Gradient legend for fill color with values from 0 to 10",
      compile(null).caption,
    )
  }
}
