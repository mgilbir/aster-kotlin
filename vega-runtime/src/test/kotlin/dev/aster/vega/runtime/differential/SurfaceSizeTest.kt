package dev.aster.vega.runtime.differential

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.loader.FileDataLoader
import dev.aster.vega.model.locale.VegaLocale
import dev.aster.vega.runtime.compile.SpecCompiler
import dev.aster.vega.scene.flatten
import dev.aster.vegalite.VegaLiteCompiler
import java.io.File
import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The Vega-Lite surface matches upstream's, on every fixture and in both directions.
 *
 * It used to come out between half a unit and a unit **small**, and the row explained that as a
 * guide extent the mark comparison could not see, "because text bounds are excluded by design". It
 * was not that. Every shortfall was exactly 0.5 or 1.0 and never anything between, which is the
 * signature of a half-pixel rather than of accumulated measurement: `strokedFrame` unioned the
 * frame's own stroked rectangle into the reach, where upstream takes the union of the frame's
 * extent and its children's and expands *that* by half the stroke. Taking `min(-46, -0.5)` leaves
 * an edge at -46 where upstream has -46.5, and every Vega-Lite chart carries a `cell` style with a
 * one-unit border, so every one of them was short.
 *
 * So this no longer measures a shortfall, it holds the equality — and it holds it in **both**
 * directions, because the failure it is now guarding against is the opposite one: an over-expansion
 * that made every surface a unit too large would have satisfied the old test's "at most a unit
 * small" just as well.
 */
class SurfaceSizeTest {

  private val root = File(System.getProperty("user.dir")).parentFile

  /** The same loader the differential harness uses; without it a `url` dataset loads nothing. */
  private val loader = FileDataLoader(File(root, "test-fixtures"))

  private fun fixtureNames(): List<String> =
    File(root, "test-fixtures/vega-lite")
      .listFiles { f -> f.name.endsWith(".vl.json") }
      .orEmpty()
      .map { it.name.removeSuffix(".vl.json") }
      .sorted()

  @Test
  fun `the surface matches upstream in both directions`() {
    val names = fixtureNames()
    assumeTrue(names.isNotEmpty(), "no vega-lite fixtures in this checkout")

    var compared = 0
    var worst = 0.0
    val wrong = mutableListOf<String>()

    for (name in names) {
      val reference = File(root, "test-fixtures/vega-lite-reference/$name.reference.json")
      val source = File(root, "test-fixtures/vega-lite/$name.vl.json")
      if (!reference.isFile || !source.isFile) continue
      val vega = VegaLiteCompiler().compileJson(source.readText()).toJson() ?: continue
      val upstream = Differential.readReference(reference)
      val scene =
        SpecCompiler(VegaHeadlessTextEngine(), loader, locale = VegaLocale.EnglishUS)
          .compileJson(vega)
          .scene ?: continue

      // The one allowance, and it is not about the frame: upstream measures a true arc from its
      // centre and radii where this scene graph has only the cubics that approximate one, so a
      // chart whose reach is set by a curve may come out a fraction of a unit larger. See
      // `CurvedExtentLimitTest`.
      val curved =
        scene.flatten().any { placed ->
          val kind = placed.node.metadata.markKind
          kind == "arc" || kind == "trail"
        }
      val allowed =
        if (curved) Differential.CURVE_EXTENT_TOLERANCE else Differential.GEOMETRY_TOLERANCE

      for ((axis, pair) in
        mapOf(
          "width" to (upstream.width to scene.width),
          "height" to (upstream.height to scene.height),
        )) {
        val (theirs, ours) = pair
        val difference = theirs - ours
        compared++
        if (abs(difference) > worst) worst = abs(difference)
        if (abs(difference) > allowed) wrong += "$name.$axis by $difference"
      }
    }

    assumeTrue(compared > 100, "only $compared surfaces compared; the corpus is not built")
    assertTrue(
      wrong.isEmpty(),
      "${wrong.size} of $compared surfaces differ from upstream: ${wrong.take(8)}",
    )
    println("surface: worst difference $worst across $compared measurements")
  }
}
