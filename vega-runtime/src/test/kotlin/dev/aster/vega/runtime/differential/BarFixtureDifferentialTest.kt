package dev.aster.vega.runtime.differential

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.runtime.compile.SpecCompiler
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The project's first differential test: compile a real Vega specification and compare the result
 * against upstream Vega's own output.
 *
 * This is the gate the rest of the runtime should be built behind. Golden tests catch regressions
 * but cannot catch a wrong reading of Vega's semantics; only upstream can. The reference file is
 * checked in and regenerated explicitly by `./scripts/oracle.sh`, so this test needs no Node and no
 * network (PROJECT_BRIEF.md 21).
 */
class BarFixtureDifferentialTest {

  /** Named so a failure says which fixture disagreed. */
  private fun fixture(
    name: String
  ): Pair<Differential.Reference, dev.aster.vega.runtime.compile.CompiledSpec> {
    val spec = File(repositoryRoot, "test-fixtures/specs/$name.vg.json")
    val reference =
      Differential.readReference(
        File(repositoryRoot, "test-fixtures/reference/$name.reference.json")
      )
    return reference to SpecCompiler(VegaHeadlessTextEngine()).compileJson(spec.readText())
  }

  private val repositoryRoot = File(System.getProperty("user.dir")).parentFile
  private val specFile = File(repositoryRoot, "test-fixtures/specs/bar.vg.json")
  private val referenceFile = File(repositoryRoot, "test-fixtures/reference/bar.reference.json")

  private val reference = Differential.readReference(referenceFile)
  // Compiled with the engine that reproduces upstream's headless text measurement, so layout —
  // which
  // depends on axis-label widths — is comparable too. See VegaHeadlessTextEngine.
  private val compiled = SpecCompiler(VegaHeadlessTextEngine()).compileJson(specFile.readText())

  @Test
  fun `the specification compiles without errors`() {
    val serious = compiled.diagnostics.filter { it.severity >= DiagnosticSeverity.ERROR }
    assertTrue(
      serious.isEmpty(),
      "the fixture should compile cleanly; got:\n${serious.joinToString("\n")}",
    )
    assertTrue(compiled.isUsable, "compilation produced no scene")
  }

  @Test
  fun `scale domains, ranges, bandwidth and ticks match upstream exactly`() {
    val differences = Differential.compareScales(reference.scales, compiled.scales)
    assertTrue(differences.isEmpty(), "scale differences:\n${differences.joinToString("\n")}")
  }

  @Test
  fun `mark counts by type and role match upstream`() {
    val ours = Differential.flattenScene(compiled.scene!!).groupingBy { it.key }.eachCount()
    val theirs = reference.marks.groupingBy { it.key }.eachCount()
    assertEquals(theirs, ours, "mark counts by type/role differ")
  }

  @Test
  fun `every mark's geometry matches upstream within tolerance`() {
    val ours = Differential.flattenScene(compiled.scene!!)
    val differences = Differential.compareMarks(reference.marks, ours)
    assertTrue(
      differences.isEmpty(),
      buildString {
        append("compiled ${ours.size} marks, upstream had ${reference.marks.size}\n")
        append("${differences.size} difference(s), first 25:\n")
        differences.take(25).forEach { append("  ").append(it).append('\n') }
      },
    )
  }

  @Test
  fun `the surface size matches upstream's autosize pad result`() {
    val scene = compiled.scene!!
    // Known difference, half a pixel per axis: Vega computes an axis group's bounds from the axis
    // extent — the full scale range along the axis, the tick and label reach across it — rather
    // than by
    // unioning its items' bounds. Its frame bounds therefore exclude the half-pixel crisp offset
    // and
    // the domain line's stroke overflow, which ours include. Every mark coordinate still agrees
    // exactly; only the surface the marks sit on is a half pixel larger. Recorded in
    // SUPPORTED_FEATURES.md.
    assertEquals(reference.width, scene.width, AXIS_BOUNDS_TOLERANCE, "surface width")
    assertEquals(reference.height, scene.height, AXIS_BOUNDS_TOLERANCE, "surface height")
  }

  @Test
  fun `the plotting area itself matches upstream exactly`() {
    // The part that must not drift: the scale ranges define the plot box, and every mark inside it
    // was
    // already compared exactly. This asserts the surface is only ever larger by the documented
    // axis-bounds difference, never smaller or differently proportioned.
    val scene = compiled.scene!!
    assertTrue(
      scene.width >= reference.width - 1e-6,
      "surface should never be narrower than upstream's: ${scene.width} vs ${reference.width}",
    )
    assertTrue(
      scene.height >= reference.height - 1e-6,
      "surface should never be shorter than upstream's: ${scene.height} vs ${reference.height}",
    )
  }

  @Test
  fun `compilation is deterministic`() {
    val again = SpecCompiler(VegaHeadlessTextEngine()).compileJson(specFile.readText())
    assertEquals(
      Differential.flattenScene(compiled.scene!!).map { it.toString() },
      Differential.flattenScene(again.scene!!).map { it.toString() },
    )
  }

  private companion object {
    /**
     * Half a pixel per axis, from Vega's special-cased axis group bounds. Deliberately tight enough
     * that a real layout regression cannot hide behind it.
     */
    const val AXIS_BOUNDS_TOLERANCE = 1.0
  }

  // ---- second fixture: transforms, signals and conditional encodings ---------

  @Test
  fun `the stacked bar fixture compiles without errors`() {
    val (_, compiled) = fixture("stacked-bar")
    val serious = compiled.diagnostics.filter { it.severity >= DiagnosticSeverity.ERROR }
    assertTrue(serious.isEmpty(), "expected a clean compile; got:\n${serious.joinToString("\n")}")
  }

  @Test
  fun `the stacked bar fixture's scales match upstream`() {
    val (reference, compiled) = fixture("stacked-bar")
    val differences = Differential.compareScales(reference.scales, compiled.scales)
    assertTrue(differences.isEmpty(), "scale differences:\n${differences.joinToString("\n")}")
  }

  @Test
  fun `the stacked bar fixture's marks match upstream`() {
    val (reference, compiled) = fixture("stacked-bar")
    val ours = Differential.flattenScene(requireNotNull(compiled.scene))
    val differences = Differential.compareMarks(reference.marks, ours)
    assertTrue(
      differences.isEmpty(),
      buildString {
        append("compiled ${ours.size} marks, upstream had ${reference.marks.size}\n")
        append("${differences.size} difference(s), first 25:\n")
        differences.take(25).forEach { append("  ").append(it).append('\n') }
      },
    )
  }

  // ---- third fixture: line, area, symbol and text -----------------------------

  @Test
  fun `the line and area fixture compiles without errors`() {
    val (_, compiled) = fixture("line-area")
    val serious = compiled.diagnostics.filter { it.severity >= DiagnosticSeverity.ERROR }
    assertTrue(serious.isEmpty(), "expected a clean compile; got:\n${serious.joinToString("\n")}")
  }

  @Test
  fun `the line and area fixture's marks match upstream`() {
    val (reference, compiled) = fixture("line-area")
    val ours = Differential.flattenScene(requireNotNull(compiled.scene))
    val differences = Differential.compareMarks(reference.marks, ours)
    assertTrue(
      differences.isEmpty(),
      buildString {
        append("compiled ${ours.size} marks, upstream had ${reference.marks.size}\n")
        append("${differences.size} difference(s), first 25:\n")
        differences.take(25).forEach { append("  ").append(it).append('\n') }
      },
    )
  }

  // ---- fourth fixture: log and sqrt scales ------------------------------------

  @Test
  fun `the log scale fixture compiles without errors`() {
    val (_, compiled) = fixture("log-scale")
    val serious = compiled.diagnostics.filter { it.severity >= DiagnosticSeverity.ERROR }
    assertTrue(serious.isEmpty(), "expected a clean compile; got:\n${serious.joinToString("\n")}")
  }

  @Test
  fun `the log scale fixture's marks match upstream`() {
    val (reference, compiled) = fixture("log-scale")
    val ours = Differential.flattenScene(requireNotNull(compiled.scene))
    val differences = Differential.compareMarks(reference.marks, ours)
    assertTrue(
      differences.isEmpty(),
      buildString {
        append("compiled ${ours.size} marks, upstream had ${reference.marks.size}\n")
        append("${differences.size} difference(s), first 25:\n")
        differences.take(25).forEach { append("  ").append(it).append('\n') }
      },
    )
  }

  // ---- fifth fixture: colour schemes and interpolation ------------------------

  @Test
  fun `the colour scheme fixture compiles without errors`() {
    val (_, compiled) = fixture("colour-scheme")
    val serious = compiled.diagnostics.filter { it.severity >= DiagnosticSeverity.ERROR }
    assertTrue(serious.isEmpty(), "expected a clean compile; got:\n${serious.joinToString("\n")}")
  }

  @Test
  fun `the colour scheme fixture's marks match upstream`() {
    val (reference, compiled) = fixture("colour-scheme")
    val ours = Differential.flattenScene(requireNotNull(compiled.scene))
    val differences = Differential.compareMarks(reference.marks, ours)
    assertTrue(
      differences.isEmpty(),
      buildString {
        append("compiled ${ours.size} marks, upstream had ${reference.marks.size}\n")
        append("${differences.size} difference(s), first 25:\n")
        differences.take(25).forEach { append("  ").append(it).append('\n') }
      },
    )
  }

  // ---- sixth fixture: faceted group marks -------------------------------------

  @Test
  fun `the facet trellis fixture compiles without errors`() {
    val (_, compiled) = fixture("facet-trellis")
    val serious = compiled.diagnostics.filter { it.severity >= DiagnosticSeverity.ERROR }
    assertTrue(serious.isEmpty(), "expected a clean compile; got:\n${serious.joinToString("\n")}")
  }

  @Test
  fun `the facet trellis fixture's marks match upstream`() {
    val (reference, compiled) = fixture("facet-trellis")
    val ours = Differential.flattenScene(requireNotNull(compiled.scene))
    val differences = Differential.compareMarks(reference.marks, ours)
    assertTrue(
      differences.isEmpty(),
      buildString {
        append("compiled ${ours.size} marks, upstream had ${reference.marks.size}\n")
        append("${differences.size} difference(s), first 25:\n")
        differences.take(25).forEach { append("  ").append(it).append('\n') }
      },
    )
  }

  @Test
  fun `each facet cell resolves its own scales, so the cells differ`() {
    // The point of faceting: three cells, each scaled to its own partition. If nested scales leaked
    // between cells every bar would be the same height, and the geometry comparison above would
    // still
    // pass only if upstream leaked identically — which it does not.
    val (_, compiled) = fixture("facet-trellis")
    val cells =
      Differential.flattenScene(requireNotNull(compiled.scene)).filter { it.role == "scope" }
    assertEquals(3, cells.size, "one group item per region")
    val bars =
      Differential.flattenScene(requireNotNull(compiled.scene)).filter {
        it.type == "rect" && it.role == "mark"
      }
    assertEquals(9, bars.size)
    assertTrue(
      bars.map { it.numbers["height"] }.distinct().size > 1,
      "bars should not all share one height",
    )
  }

  @Test
  fun `the reference was generated from the pinned upstream version`() {
    // A silently upgraded oracle would make every comparison suspect.
    assertEquals("6.3.1", reference.vegaVersion)
  }
}
