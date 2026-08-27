package dev.aster.vega.compose.mp

import dev.aster.vega.loader.FileDataLoader
import dev.aster.vega.model.locale.VegaLocale
import dev.aster.vega.model.time.VegaTimeZones
import dev.aster.vega.runtime.compile.SpecCompiler
import dev.aster.vega.scene.MetricTextEngine
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The **canonical** draw-call sequence for a handful of fixtures, written down.
 *
 * Half of a comparison. `SceneWalkParityTests` in `swift/AsterVegaRender` reads these same files
 * and asserts that the Swift walk produces them too, which is the thing nothing was doing: there
 * are two walks over a scene, each documented as emitting "the same calls in the same order", and
 * the only check was a person reading two differently-formatted recordings side by side.
 *
 * It cost a defect that shipped. The Swift walk had no zero-opacity guard, so a label an axis had
 * hidden reached its text branch and was painted black; on `label-overlap.vg.json` it emitted 43
 * text runs where this walk emitted 19. Both renderers' own tests passed throughout, because each
 * was asserting about itself.
 *
 * **The scene is identical by construction**, which is what makes this a comparison of the walks
 * and not of two compilers. Both sides compile the same fixture with the same Kotlin engine through
 * the same compiler — Swift reaches it through the framework — so every input is pinned here and
 * spelled out there: `MetricTextEngine`'s default ratios, the fixed clock, the default random seed,
 * `VegaLocale.EnglishUS`, and **UTC**. The zone is the one that would otherwise slip: a `time`
 * scale is local, the JVM tests pin `Europe/Amsterdam` through Gradle and `swift test` pins
 * nothing, so a golden written without it would be a golden about this machine.
 *
 * Regenerate with `./gradlew :vega-compose-multiplatform:jvmTest -PupdateGoldens=true
 * --rerun-tasks` and read the diff as a change to what a renderer draws.
 */
class SceneWalkGoldenTest {

  /**
   * The fixtures, chosen to reach every branch of both walks.
   *
   * One per primitive and per feature the two could disagree about, rather than a broad sweep:
   * these goldens are read by a human when they change, so there is a real cost to each line of
   * them.
   *
   * - `bar` — groups, rects, axis rules and text
   * - `label-overlap` — labels hidden at zero opacity, which is the defect this file exists for
   * - `line-area` — paths with curves, and `defined` gaps
   * - `symbols-and-curves` — symbol outlines, which the differential harness once could not see
   * - `gradient-fills` — linear and radial brushes, with their stops
   * - `arc-padding` — arc paths, the longest command sequences here
   * - `text-anchors` — every alignment and a rotation, so the anchor and the pen are both compared
   * - `axis-style` — dashed strokes, caps and joins
   * - `density-heatmaps` — an engine-produced raster, the only image path
   * - `encode-channels` — a `blend`, which is the only channel here that changes *compositing*
   *   rather than geometry, and which one of the two walks used to drop on the floor
   *
   * **Discovered, not listed.** The names come from the committed goldens themselves, and the Swift
   * side does the same — `SceneWalkParityTests` scans the same directory. Two hard-coded lists is
   * how a golden stops being asserted on one engine while still looking committed, which is exactly
   * the failure the conformance README warns about; with both sides reading the directory, adding a
   * fixture to one immediately obliges the other.
   */
  private val fixtures: List<String> =
    File(repositoryRoot, "test-fixtures/scene-walk")
      .listFiles()
      .orEmpty()
      .mapNotNull {
        it.name.removeSuffix(".calls.txt").takeIf { _ -> it.name.endsWith(".calls.txt") }
      }
      .sorted()

  @Test
  fun `the canonical call sequences match what is committed`() {
    val updating = System.getProperty("vega.updateGoldens") == "true"
    val stale = mutableListOf<String>()
    for (name in fixtures) {
      val golden = File(repositoryRoot, "test-fixtures/scene-walk/$name.calls.txt")
      val recorded = canonical(name)
      if (updating) {
        golden.parentFile.mkdirs()
        golden.writeText(recorded + "\n")
        continue
      }
      assertTrue(golden.isFile, "no golden for $name; regenerate with -PupdateGoldens=true")
      if (golden.readText().trimEnd('\n') != recorded) stale += name
    }
    assertEquals(
      emptyList<String>(),
      stale,
      "the drawing changed for these fixtures. Review it, then regenerate with " +
        "./gradlew :vega-compose-multiplatform:jvmTest -PupdateGoldens=true --rerun-tasks",
    )
  }

  /**
   * The goldens are not empty, and they are not all the same shape.
   *
   * A guard against the way this kind of file fails: a generator that wrote nothing, or a fixture
   * list that quietly stopped resolving, leaves nine files that agree with each other perfectly.
   */
  @Test
  fun `every fixture draws something, and the image one draws an image`() {
    for (name in fixtures) {
      val recorded = canonical(name)
      assertTrue(recorded.lines().size > 4, "$name recorded almost nothing:\n$recorded")
    }
    assertTrue(canonical("density-heatmaps").contains("image url="), "no image call recorded")
    assertTrue(canonical("gradient-fills").contains("linear("), "no linear gradient recorded")
    assertTrue(canonical("text-anchors").contains("angle="), "no text call recorded")
  }

  private fun canonical(name: String): String {
    val spec = File(repositoryRoot, "test-fixtures/specs/$name.vg.json")
    assertTrue(spec.isFile, "no fixture at $spec")
    val compiled =
      SpecCompiler(
          textEngine = MetricTextEngine(),
          loader = FileDataLoader(File(repositoryRoot, "test-fixtures")),
          locale = VegaLocale.EnglishUS,
          // UTC, so a golden is about the walk rather than about the machine that wrote it. A
          // `time`
          // scale is local, and the two test runners pin different zones — or none.
          timeZone = VegaTimeZones.utc,
        )
        .compileJson(spec.readText())
    val scene = requireNotNull(compiled.scene) { "$name did not compile: ${compiled.diagnostics}" }
    val calls = CanonicalCalls()
    SceneWalk().draw(scene, calls)
    return calls.text
  }

  private companion object {
    private val repositoryRoot: File = File(System.getProperty("user.dir")).parentFile
  }
}
