package dev.aster.vega.runtime.differential

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.runtime.compile.CompiledSpec
import dev.aster.vega.runtime.compile.SpecCompiler
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * The differential gate: every fixture compiled here, and compared against upstream Vega's own
 * output.
 *
 * This is what the rest of the runtime is built behind. Golden tests catch regressions but cannot
 * catch a wrong reading of Vega's semantics; only upstream can, and it keeps doing so — a fixture
 * has yet to be added without finding something. The reference files are checked in and regenerated
 * explicitly by `./scripts/oracle.sh`, so this needs neither Node nor a network (PROJECT_BRIEF.md
 * 21).
 *
 * Fixtures are discovered from the directory rather than listed, so adding one is a single file and
 * forgetting its reference fails loudly rather than quietly skipping.
 */
class FixtureDifferentialTest {

  private fun compile(name: String): Pair<Differential.Reference, CompiledSpec> {
    val spec = File(repositoryRoot, "test-fixtures/specs/$name.vg.json")
    val reference =
      Differential.readReference(
        File(repositoryRoot, "test-fixtures/reference/$name.reference.json")
      )
    // The engine that reproduces upstream's headless text measurement, so layout — which depends on
    // how wide the labels are — is comparable too. See VegaHeadlessTextEngine.
    return reference to SpecCompiler(VegaHeadlessTextEngine()).compileJson(spec.readText())
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  fun `compiles without errors`(name: String) {
    val (_, compiled) = compile(name)
    val serious = compiled.diagnostics.filter { it.severity >= DiagnosticSeverity.ERROR }
    assertTrue(
      serious.isEmpty(),
      "$name should compile cleanly; got:\n${serious.joinToString("\n")}",
    )
    assertTrue(compiled.isUsable, "$name produced no scene")
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  fun `mark counts by type and role match upstream`(name: String) {
    val (reference, compiled) = compile(name)
    val ours =
      Differential.flattenScene(requireNotNull(compiled.scene)).groupingBy { it.key }.eachCount()
    val theirs = reference.marks.groupingBy { it.key }.eachCount()
    assertEquals(theirs, ours, "$name mark counts by type/role differ")
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  fun `every mark's geometry matches upstream`(name: String) {
    val (reference, compiled) = compile(name)
    val ours = Differential.flattenScene(requireNotNull(compiled.scene))
    val differences = Differential.compareMarks(reference.marks, ours)
    assertTrue(
      differences.isEmpty(),
      buildString {
        append("$name: compiled ${ours.size} marks, upstream had ${reference.marks.size}\n")
        append("${differences.size} difference(s), first 25:\n")
        differences.take(25).forEach { append("  ").append(it).append('\n') }
      },
    )
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  fun `scale domains, ranges, bandwidth and ticks match upstream`(name: String) {
    val (reference, compiled) = compile(name)
    val differences = Differential.compareScales(reference.scales, compiled.scales)
    assertTrue(differences.isEmpty(), "$name scale differences:\n${differences.joinToString("\n")}")
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  fun `the surface is exactly the size upstream makes it`(name: String) {
    // Exactly, with no tolerance. Three separate behaviours had to be reproduced to get here, none
    // of
    // them visible in a single mark's coordinates: an axis is measured by its extent rather than by
    // the items it drew, gridlines are excluded from that measurement, and a stroked path reserves
    // four stroke widths for a miter join rather than the ten a canvas would.
    val (reference, compiled) = compile(name)
    val scene = requireNotNull(compiled.scene)
    // The reference stores numbers at the harness's canonical precision, so the tolerance is that
    // rounding and nothing more — an actual disagreement is never a fraction of a unit.
    assertEquals(reference.width, scene.width, Differential.GEOMETRY_TOLERANCE, "$name width")
    assertEquals(reference.height, scene.height, Differential.GEOMETRY_TOLERANCE, "$name height")
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  fun `compilation is deterministic`(name: String) {
    val spec = File(repositoryRoot, "test-fixtures/specs/$name.vg.json").readText()
    val once = SpecCompiler(VegaHeadlessTextEngine()).compileJson(spec)
    val again = SpecCompiler(VegaHeadlessTextEngine()).compileJson(spec)
    assertEquals(
      Differential.flattenScene(requireNotNull(once.scene)).map { it.toString() },
      Differential.flattenScene(requireNotNull(again.scene)).map { it.toString() },
      name,
    )
  }

  @Test
  fun `every reference was generated from the pinned upstream version`() {
    // A silently upgraded oracle would make every comparison above suspect.
    for (name in fixtures()) {
      assertEquals("6.3.1", compile(name).first.vegaVersion, name)
    }
  }

  @Test
  fun `each facet cell resolves its own scales, so the cells differ`() {
    // The point of faceting: three cells, each scaled to its own partition. If nested scales leaked
    // between cells every bar would be the same height, and the geometry comparison would still
    // pass
    // only if upstream leaked identically — which it does not.
    val (_, compiled) = compile("facet-trellis")
    val marks = Differential.flattenScene(requireNotNull(compiled.scene))
    assertEquals(3, marks.count { it.role == "scope" }, "one group item per region")
    val bars = marks.filter { it.type == "rect" && it.role == "mark" }
    assertEquals(9, bars.size)
    assertTrue(
      bars.map { it.numbers["height"] }.distinct().size > 1,
      "bars should not all share one height",
    )
  }

  private companion object {
    val repositoryRoot: File = File(System.getProperty("user.dir")).parentFile

    /** Every fixture on disk, so adding one needs no edit here. */
    @JvmStatic
    fun fixtures(): List<String> =
      requireNotNull(File(repositoryRoot, "test-fixtures/specs").listFiles()) {
          "no fixture directory"
        }
        .filter { it.name.endsWith(".vg.json") }
        .map { it.name.removeSuffix(".vg.json") }
        .sorted()
        .also { require(it.isNotEmpty()) { "no fixtures found" } }
  }
}
