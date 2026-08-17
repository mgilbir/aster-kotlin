package dev.aster.vega.runtime.differential

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.loader.FileDataLoader
import dev.aster.vega.runtime.compile.SpecCompiler
import dev.aster.vega.svg.SvgRenderer
import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Writes every fixture out as SVG, next to the upstream SVG the oracle produces.
 *
 * Not an assertion about pixels — the differential tests already compare geometry exactly. This
 * exists so a disagreement can be *looked at*: two files under `build/fixture-svg/` open side by
 * side, and a wrong legend or a shifted axis is obvious in a second where a list of coordinate
 * deltas is not.
 */
class FixtureSvgTest {

  private val repositoryRoot = File(System.getProperty("user.dir")).parentFile

  @Test
  fun `every fixture renders to SVG`() {
    val output = File(repositoryRoot, "build/fixture-svg").apply { mkdirs() }
    val specs =
      requireNotNull(File(repositoryRoot, "test-fixtures/specs").listFiles())
        .filter { it.name.endsWith(".vg.json") }
        .sortedBy { it.name }
    assertTrue(specs.isNotEmpty(), "no fixtures found")

    for (spec in specs) {
      val compiled =
        SpecCompiler(
            VegaHeadlessTextEngine(),
            FileDataLoader(File(repositoryRoot, "test-fixtures")),
          )
          .compileJson(spec.readText())
      val scene = requireNotNull(compiled.scene) { "${spec.name} produced no scene" }
      val name = spec.name.removeSuffix(".vg.json")
      File(output, "$name.ours.svg").writeText(SvgRenderer().render(scene).svg)
    }
  }
}
