package dev.aster.vega.runtime.differential

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.loader.FileDataLoader
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.locale.VegaLocale
import dev.aster.vega.runtime.compile.CompiledSpec
import dev.aster.vega.runtime.compile.SpecCompiler
import dev.aster.vega.scene.flatten
import dev.aster.vegalite.VegaLiteCompiler
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * The end-to-end gate for Vega-Lite: a fixture compiled by *this* compiler, drawn by *this*
 * runtime, and compared against the chart upstream draws from its own compilation of the same
 * fixture.
 *
 * `:vega-lite`'s own test compares the emitted specification property by property, which says
 * precisely which rule drifted. This says whether the result is the same picture, which is the
 * thing a reader actually cares about — and the two can disagree in both directions. A
 * specification can match upstream's and still draw differently if the runtime reads some construct
 * differently, and it can differ harmlessly if two phrasings mean the same thing. Both are worth
 * knowing, so both are checked.
 */
class VegaLiteFixtureDifferentialTest {

  /**
   * The scene references are **all there, or none are** — a partial set is a failure, not a skip.
   *
   * A missing reference is answered with an assumption, which is right for a fresh clone and the
   * edit-run loop: the references are gitignored, being sixteen megabytes of derived output nobody
   * reads a diff of. It is wrong for every other case, and this is the floor that separates the
   * two, the same shape as `replayed >= 60` in the upstream replays.
   *
   * What it catches is the gate quietly shrinking: a rendering step that failed part way, a fixture
   * added to `test-fixtures/vega-lite/` without the references being rebuilt, or a stale directory
   * from before a fixture was renamed. Any of those left most of the 1126 cases skipping while a
   * run still reported green — and `scripts/check.sh` used to render the references *after* the
   * tests that need them, so a single run could not arm this gate at all.
   */
  @Test
  fun `the upstream scenes are all present or all absent`() {
    val directory = File(repositoryRoot, "test-fixtures/vega-lite-reference")
    val missing = fixtures().filter { !File(directory, "$it.reference.json").isFile }
    if (missing.size == fixtures().size) return // A fresh clone. Every case will say so itself.
    assertEquals(
      emptyList<String>(),
      missing,
      "the upstream scenes are only partly rendered, so ${missing.size} fixtures would skip " +
        "rather than compare. Run scripts/vega-lite-oracle.sh --references-only",
    )
  }

  private fun compile(name: String): Pair<Differential.Reference, CompiledSpec> {
    val source = File(repositoryRoot, "test-fixtures/vega-lite/$name.vl.json").readText()
    val compiled = VegaLiteCompiler().compileJson(source)
    val vega = requireNotNull(compiled.toJson()) { "$name produced no Vega specification" }
    // The scene upstream draws is **derived**: `scripts/vega-lite-oracle.sh` rebuilds it, so the
    // repository carries the recipe rather than sixteen megabytes of somebody else's output. A
    // fresh clone has none, and says so as an *assumption* rather than passing — a green tick for a
    // check that did not happen is the failure this repository has already had once.
    val scene = File(repositoryRoot, "test-fixtures/vega-lite-reference/$name.reference.json")
    org.junit.jupiter.api.Assumptions.assumeTrue(
      scene.isFile,
      "no upstream scene at ${scene.path} — run scripts/vega-lite-oracle.sh to draw them",
    )
    val reference = Differential.readReference(scene)
    return reference to
      SpecCompiler(VegaHeadlessTextEngine(), fixtureLoader, locale = VegaLocale.EnglishUS)
        .compileJson(vega)
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  fun `the compiled Vega runs without errors`(name: String) {
    assumeProjectionWorks(name)
    val (_, compiled) = compile(name)
    val serious = compiled.diagnostics.filter { it.severity >= DiagnosticSeverity.ERROR }
    assertTrue(
      serious.isEmpty(),
      "$name should run cleanly; got:\n${serious.joinToString("\n")}",
    )
    assertTrue(compiled.isUsable, "$name produced no scene")
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  fun `mark counts by type and role match upstream`(name: String) {
    assumeProjectionWorks(name)
    val (reference, compiled) = compile(name)
    val ours =
      Differential.flattenScene(requireNotNull(compiled.scene)).groupingBy { it.key }.eachCount()
    val theirs = reference.marks.groupingBy { it.key }.eachCount()
    assertEquals(theirs, ours, "$name mark counts by type/role differ")
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  fun `every mark's geometry matches upstream`(name: String) {
    assumeGridLayoutWorks(name)
    assumeProjectionWorks(name)
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

  /**
   * The surface, with the one difference that is still open written into the assertion.
   *
   * Every mark of every fixture lands exactly where upstream puts it, and the surface around them
   * still comes out between half a unit and a unit small in each direction. That combination places
   * the shortfall in a *measurement* rather than in anything drawn: the only inputs to the surface
   * the mark comparison cannot see are text bounds, which it excludes on purpose (docs/adr/0006),
   * and the guide extents computed from them.
   *
   * So this asserts the shape of the discrepancy instead of pretending it is not there — never
   * larger than upstream, never more than a unit smaller. A regression that moved a chart further
   * than that, or in the other direction, still fails, and STATUS.md carries it as an open item.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  fun `the surface is the size upstream makes it, to within the known guide-extent shortfall`(
    name: String
  ) {
    assumeGridLayoutWorks(name)
    assumeProjectionWorks(name)
    val (reference, compiled) = compile(name)
    val scene = requireNotNull(compiled.scene)
    // A drawing whose reach is set by a curve gets the allowance the Vega fixtures give it:
    // upstream measures a true arc from its centre and radii, where this scene graph has only the
    // cubics that approximate one. A thousandth of a unit on a 150-unit radius.
    val curved =
      scene
        .flatten()
        .map { it.node }
        .any {
          it.metadata.markKind == "arc" || it.metadata.markKind == "trail"
        }
    val over = if (curved) Differential.CURVE_EXTENT_TOLERANCE else Differential.GEOMETRY_TOLERANCE
    for ((dimension, pair) in
      mapOf(
        "width" to (reference.width to scene.width),
        "height" to (reference.height to scene.height),
      )) {
      val (theirs, ours) = pair
      assertTrue(
        ours <= theirs + over,
        "$name $dimension is larger than upstream: $ours against $theirs",
      )
      // The unit, plus the rounding the reference is stored at — a difference of exactly one unit
      // reads as a fraction over when the reference has been written to six places.
      assertTrue(
        ours >= theirs - 1.0 - Differential.GEOMETRY_TOLERANCE,
        "$name $dimension is more than a unit smaller than upstream: $ours against $theirs",
      )
    }
  }

  @Test
  fun `every reference was generated from the pinned upstream version`() {
    for (name in fixtures()) {
      assertEquals("6.3.1", compile(name).first.vegaVersion, name)
    }
  }

  @Test
  fun `the compiled Vega is valid JSON that the parser accepts`() {
    // A cheap guard on the writer rather than the compiler: the specification travels as text
    // whenever a host stores or ships it, so a value that cannot be written is a real failure mode.
    for (name in fixtures()) {
      val text = requireNotNull(compile(name).second.spec) { "$name did not parse" }
      assertTrue(text.marks.isNotEmpty(), "$name parsed to no marks")
    }
    val roundTripped =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(File(repositoryRoot, "test-fixtures/vega-lite/bar.vl.json").readText())
            .toJson()
        )
      )
    assertTrue(roundTripped is dev.aster.vega.model.VegaValue.Obj)
  }

  /**
   * Every suppressed fixture is still failing, so a fixed one cannot go on being skipped.
   *
   * This is the guard the two sets above were missing. `facet-footer` was fixed four days after it
   * was listed, by a change that was not looking for it, and went on skipping two comparisons for
   * the ten days after that — because an assumption is silent, and a green run says nothing about
   * what it declined to check. Nobody was careless; no mechanism here could have noticed.
   *
   * So the suppression is now self-expiring: a name in either set that *passes* the geometry
   * comparison fails here instead, naming itself and saying to take it out. Both sets are empty as
   * this is written, which makes this a no-op today and a tripwire the moment one is not.
   */
  @Test
  fun `no fixture is suppressed for a fault it no longer has`() {
    for (name in GRID_LAYOUT_PENDING + PROJECTION_PENDING) {
      val (reference, compiled) = compile(name)
      val differences =
        Differential.compareMarks(
          reference.marks,
          Differential.flattenScene(requireNotNull(compiled.scene)),
        )
      assertTrue(
        differences.isNotEmpty(),
        "$name is listed as pending but its geometry now matches upstream — remove it from the " +
          "set and let the comparison run",
      )
    }
  }

  /**
   * Skips the *placement* comparisons for a chart whose places this runtime projects differently.
   *
   * The compiled specification matches upstream's — the projection, the feature collections it is
   * fitted to and the `geopoint` that reads it are all byte for byte — but fitting a projection to
   * an extent is the runtime's own arithmetic, and `albersUsa` is three projections in a coat. The
   * gap is in the drawing, not in the compiling, and it is recorded rather than hidden.
   */
  private fun assumeProjectionWorks(name: String) {
    org.junit.jupiter.api.Assumptions.assumeFalse(
      name in PROJECTION_PENDING,
      "$name: the compiled specification matches upstream, but this runtime fits its projection " +
        "differently — see STATUS.md",
    )
  }

  private fun assumeGridLayoutWorks(name: String) {
    org.junit.jupiter.api.Assumptions.assumeFalse(
      name in GRID_LAYOUT_PENDING,
      "$name: the compiled specification matches upstream, but this runtime grids its cells " +
        "differently — see STATUS.md",
    )
  }

  private companion object {
    /**
     * Fixtures whose *placement* is pending on the runtime's grid layout. **Empty**, and the way it
     * emptied is the part worth keeping.
     *
     * `facet-footer` sat here from the commit that introduced this set, and was fixed four days
     * later by a change that never mentioned it: adding `row-footer` and `column-footer` to
     * `TrellisRole`, which until then fell through to `CELL` and were gridded *among* the cells.
     * That fixture compiles to exactly one `column-footer` group, so it was the same defect wearing
     * a Vega-Lite hat — but the entry stayed, and an assumption is silent, so two tests skipped for
     * a fault that no longer existed and the run stayed green either way.
     *
     * Removing the role from `TrellisRole.of` reproduces both failures on demand, which is how the
     * entry was retired rather than merely doubted. The lesson is about the mechanism, not the bug:
     * a name in here suppresses a test until somebody takes it out, and nothing else will. So when
     * a grid-layout fault is fixed, empty this set in the same change — and if a fixture is added
     * here, it owes STATUS.md an entry saying what has to become true for it to leave.
     */
    val GRID_LAYOUT_PENDING = emptySet<String>()

    /**
     * Fixtures pending on the runtime's projections. **Empty**, and the history is the useful part.
     *
     * Three reasons were given for this set over its life and all three were wrong. Fitting to an
     * extent was said not to work: it did, and the real fault was `fitExtent` reaching only a
     * concrete projection and never the interface a **composite** implements, so a fitted
     * `albersUsa` drew at its family's unfitted default. A projection fitted to the table that
     * reads it back was called a cycle: upstream refuses that construction too, and what Vega-Lite
     * emits instead is a signal the projection fits to, which this engine handles. A fit published
     * by *two* datasets was then called an unorderable cycle: it is unorderable, and the answer was
     * never an order — a shared fit is eventually consistent, refitting as each collection arrives.
     *
     * Kept because the next projection that misbehaves will need somewhere honest to sit, and
     * because a diagnosis read off a symptom has now been wrong here three times running.
     */
    val PROJECTION_PENDING = emptySet<String>()

    val repositoryRoot: File = File(System.getProperty("user.dir")).parentFile

    val fixtureLoader = FileDataLoader(File(repositoryRoot, "test-fixtures"))

    @JvmStatic
    fun fixtures(): List<String> =
      requireNotNull(File(repositoryRoot, "test-fixtures/vega-lite").listFiles()) {
          "no Vega-Lite fixture directory"
        }
        .filter { it.name.endsWith(".vl.json") }
        .map { it.name.removeSuffix(".vl.json") }
        .sorted()
        .also { require(it.isNotEmpty()) { "no Vega-Lite fixtures found" } }
  }
}
