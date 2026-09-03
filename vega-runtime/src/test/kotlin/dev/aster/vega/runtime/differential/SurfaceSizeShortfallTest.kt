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
 * The Vega-Lite surface comes out slightly **small**, and by how much is the claim.
 *
 * `SUPPORTED_FEATURES.md`: "every mark lands exactly where upstream puts it; the surface around
 * them still comes out between half a unit and a unit small in each direction. Since nothing drawn
 * has moved, the shortfall is in a guide *extent* — the one input the mark comparison cannot see,
 * because text bounds are excluded by design (docs/adr/0006)."
 *
 * `VegaLiteFixtureDifferentialTest` already tolerates it per fixture, but its reported test names
 * are the fixture names — it is parameterised — so nothing names *this* claim. And a tolerance is
 * exactly the kind of thing that quietly widens: the row says half a unit to a unit, and a
 * tolerance nobody measures against could absorb ten.
 *
 * So this measures the shortfall across the whole corpus and holds it to what the row says. If it
 * ever reaches zero, the row should say the surface matches; if it grows, the row is wrong and the
 * tolerance is hiding something.
 */
class SurfaceSizeShortfallTest {

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
  fun `the surface is between nothing and a unit small in each direction`() {
    val names = fixtureNames()
    assumeTrue(names.isNotEmpty(), "no vega-lite fixtures in this checkout")

    var compared = 0
    var worst = 0.0
    var sawAShortfall = false
    val tooFar = mutableListOf<String>()

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

      // The same allowance the harness gives a drawing whose reach is set by a curve: upstream
      // measures a true arc from its centre and radii where this scene graph has only the cubics
      // that approximate one, so such a chart may come out a fraction of a unit *larger*. See
      // `CurvedExtentLimitTest`.
      val curved =
        scene.flatten().any { placed ->
          val kind = placed.node.metadata.markKind
          kind == "arc" || kind == "trail"
        }
      val over =
        if (curved) Differential.CURVE_EXTENT_TOLERANCE else Differential.GEOMETRY_TOLERANCE

      for ((axis, pair) in
        mapOf(
          "width" to (upstream.width to scene.width),
          "height" to (upstream.height to scene.height),
        )) {
        val (theirs, ours) = pair
        val shortfall = theirs - ours
        compared++
        if (shortfall > 1e-6) sawAShortfall = true
        if (abs(shortfall) > worst) worst = abs(shortfall)
        // Negative would mean this engine makes the surface *larger*, which the row does not
        // describe and which no guide-extent shortfall can explain.
        if (shortfall < -over || shortfall > 1.0 + 1e-6) tooFar += "$name.$axis by $shortfall"
      }
    }

    assumeTrue(compared > 100, "only $compared surfaces compared; the corpus is not built")
    assertTrue(
      tooFar.isEmpty(),
      "the surface is outside the half-a-unit-to-a-unit shortfall this row describes, on " +
        "${tooFar.size} of $compared measurements: ${tooFar.take(6)}",
    )
    assertTrue(
      sawAShortfall,
      "no surface was small at all across $compared measurements, so the shortfall is gone and " +
        "this row should say the surface matches upstream",
    )
    println("surface shortfall: worst ${worst} across $compared measurements")
  }
}
