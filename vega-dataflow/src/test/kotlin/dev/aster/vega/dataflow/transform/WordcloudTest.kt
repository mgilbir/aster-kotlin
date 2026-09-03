@file:OptIn(dev.aster.vega.model.InternalAsterVegaApi::class)

package dev.aster.vega.dataflow.transform

import dev.aster.vega.expression.CachingExpressionCompiler
import dev.aster.vega.expression.ExpressionCompiler
import dev.aster.vega.expression.ExpressionScope
import dev.aster.vega.expression.RandomStream
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.asString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The `wordcloud` transform: parameters in, seven columns out.
 *
 * `CloudLayoutTest` is where the placement is compared against upstream's, pixel for pixel, using
 * upstream's own recorded sprites. This is the layer above it — the size scale, the parameter
 * spellings, and what a row that could not be placed looks like — none of which the replay reaches
 * because the replay starts from placements upstream already made.
 */
class WordcloudTest {

  private class Context : TransformContext {
    override var tree: TreeSource? = null
    override val diagnostics = DiagnosticCollector()
    override val expressions: ExpressionCompiler =
      CachingExpressionCompiler(VegaExpressionCompilerForTest)
    val stream = RandomStream()
    override val scope: ExpressionScope = scopeFor(VegaValue.Null)

    override fun setSignal(name: String, value: VegaValue) = Unit

    override fun scopeFor(datum: VegaValue): ExpressionScope =
      object : ExpressionScope {
        override val datum: VegaValue = datum
        override val random: RandomStream = stream

        override fun signal(name: String): VegaValue = VegaValue.Null

        override fun dataset(name: String): List<VegaValue> = emptyList()
      }
  }

  private val words =
    listOf(
      "visualization" to 100,
      "grammar" to 84,
      "Vega" to 72,
      "interaction" to 66,
      "scale" to 58,
      "data" to 45,
      "signal" to 35,
      "mark" to 31,
      "axis" to 27,
      "legend" to 24,
    )

  private fun rows(): List<VegaValue> = words.map { (text, count) ->
    VegaValue.Obj(
      linkedMapOf("text" to VegaValue.Str(text), "count" to VegaValue.Num(count.toDouble()))
    )
  }

  private fun run(json: String, input: List<VegaValue> = rows()): Pair<List<VegaValue>, Context> {
    val context = Context()
    val params = VegaJson.parse(json) as VegaValue.Obj
    return WordcloudTransform.apply(input, params, context) to context
  }

  private fun VegaValue.num(name: String) = (this as VegaValue.Obj).fields[name]!!.asDouble()

  private fun VegaValue.str(name: String) = (this as VegaValue.Obj).fields[name]!!.asString()

  @Test
  fun `every row gets the seven output columns`() {
    val (out, context) =
      run("""{"size": [400, 300], "text": "text", "fontSize": {"field": "count"}}""")
    assertEquals(words.size, out.size, "a transform never drops a row, only fails to place it")
    for (row in out) {
      val obj = row as VegaValue.Obj
      for (name in listOf("x", "y", "fontSize")) {
        assertTrue(name in obj.fields, "'${row.str("text")}' has no '$name'")
      }
    }
    assertTrue(context.diagnostics.diagnostics.none { it.severity.name == "ERROR" })
  }

  /**
   * A `fontSize` that reads the data is a **weight**, scaled onto `fontSizeRange`; a constant is a
   * size.
   *
   * The same property meaning two different things is upstream's design and the easiest thing here
   * to get wrong, because both spellings type-check and the wrong one produces a cloud that looks
   * fine until you notice every word is the same size.
   */
  @Test
  fun `a data-driven font size is scaled onto the range and a constant is not`() {
    // A canvas with room for everything, so the assertions are about the *scale* rather than about
    // which words happened to fit: an unplaced word keeps upstream's `fontSize` of zero, and a
    // dropped word would make this read as a scaling error.
    val (scaled, _) =
      run(
        """{"size": [2000, 1500], "text": "text",
            "fontSize": {"field": "count"}, "fontSizeRange": [12, 60]}"""
      )
    val placed = scaled.filter { !it.num("x").isNaN() }
    assertEquals(words.size, placed.size, "not every word fitted in 2000 x 1500")
    val sizes = placed.associate { it.str("text") to it.num("fontSize") }
    // The largest count takes the top of the range and the smallest the bottom, and the scale is
    // `sqrt` — so the middle is pulled *up*, not left halfway. Read off d3: sqrt(24) to sqrt(100)
    // mapped onto 12..60 puts count 45 at 12 + 48 * (sqrt(45) - sqrt(24)) / (sqrt(100) - sqrt(24)).
    assertEquals(
      60.0,
      sizes["visualization"]!!,
      1.0,
      "the largest count takes the top of the range",
    )
    assertEquals(12.0, sizes["legend"]!!, 1.0, "the smallest count takes the bottom")
    val expectedForData =
      12 +
        48 * (kotlin.math.sqrt(45.0) - kotlin.math.sqrt(24.0)) /
          (kotlin.math.sqrt(100.0) - kotlin.math.sqrt(24.0))
    assertEquals(expectedForData, sizes["data"]!!, 1.0, "the middle is placed by a sqrt scale")

    // A constant is used as written, and the range is ignored — not scaled onto it.
    val (flat, _) =
      run("""{"size": [2000, 1500], "text": "text", "fontSize": 20, "fontSizeRange": [12, 60]}""")
    val flatSizes = flat.filter { !it.num("x").isNaN() }.map { it.num("fontSize") }.toSet()
    assertEquals(setOf(20.0), flatSizes, "a constant fontSize was scaled when it should be literal")
  }

  /**
   * A word that will not fit is dropped with `NaN` coordinates, not squeezed in.
   *
   * `NaN` rather than a sentinel because that is what a mark needs: an `x` of NaN draws nothing,
   * where a zero would stack every unplaced word in the corner. Upstream writes NaN for the same
   * reason and this is one of the few places its choice is load-bearing rather than incidental.
   */
  @Test
  fun `words that do not fit are reported and marked NaN`() {
    val (out, context) =
      run("""{"size": [64, 64], "text": "text", "fontSize": {"field": "count"}}""")
    val unplaced = out.filter { it.num("x").isNaN() }
    assertTrue(unplaced.isNotEmpty(), "everything fitted in 64 x 64, so nothing was dropped")
    assertEquals(out.size, words.size, "a dropped word is still a row")
    for (row in unplaced) {
      assertTrue(row.num("y").isNaN(), "'${row.str("text")}' has an x of NaN and a real y")
      assertEquals(0.0, row.num("fontSize"), "an unplaced word keeps upstream's fontSize of 0")
    }
    assertTrue(
      context.diagnostics.diagnostics.any { "did not fit" in it.message },
      "words were dropped and nothing said so",
    )
  }

  /** Placed words never overlap and never leave the canvas — the two ways a cloud can be wrong. */
  @Test
  fun `a placed cloud is inside its canvas and does not overlap`() {
    val (out, _) = run("""{"size": [500, 400], "text": "text", "fontSize": {"field": "count"}}""")
    val placed = out.filter { !it.num("x").isNaN() }
    assertTrue(placed.size >= 5, "only ${placed.size} of ${words.size} words were placed")
    for (row in placed) {
      val x = row.num("x")
      val y = row.num("y")
      assertTrue(x in 0.0..500.0 && y in 0.0..400.0, "'${row.str("text")}' at ($x, $y) is outside")
    }
  }

  @Test
  fun `the as parameter renames every output column`() {
    val (out, _) =
      run(
        """{"size": [400, 300], "text": "text", "fontSize": {"field": "count"},
            "as": ["px", "py", "ff", "fs", "fst", "fw", "rot"]}"""
      )
    val row =
      out.first { !(it as VegaValue.Obj).fields["px"]!!.asDouble().isNaN() } as VegaValue.Obj
    for (name in listOf("px", "py", "ff", "fs", "fst", "fw", "rot")) {
      assertTrue(name in row.fields, "'$name' is missing, so 'as' was ignored")
    }
    assertTrue(
      "x" !in row.fields,
      "the default column names were written as well as the renamed ones",
    )
  }

  @Test
  fun `a size with a zero dimension is refused rather than dividing by it`() {
    val (out, context) = run("""{"size": [0, 300], "text": "text"}""")
    assertEquals(rows().size, out.size, "the rows are handed back untouched")
    assertTrue(
      context.diagnostics.diagnostics.any { "non-zero" in it.message },
      context.diagnostics.diagnostics.map { it.message }.toString(),
    )
  }

  /** The same seed gives the same cloud, which is the difference from upstream worth having. */
  @Test
  fun `two runs of the same specification agree`() {
    val spec = """{"size": [400, 300], "text": "text", "fontSize": {"field": "count"}}"""
    fun positions() = run(spec).first.map { Triple(it.str("text"), it.num("x"), it.num("y")) }
    val first = positions()
    val second = positions()
    assertEquals(
      first.map { "${it.first}:${it.second}:${it.third}" },
      second.map { "${it.first}:${it.second}:${it.third}" },
      "the same specification drew two different clouds",
    )
  }
}

/** The compiler the other transform tests use, named here so this file reads on its own. */
private object VegaExpressionCompilerForTest :
  ExpressionCompiler by dev.aster.vega.expression.VegaExpressionCompiler()
