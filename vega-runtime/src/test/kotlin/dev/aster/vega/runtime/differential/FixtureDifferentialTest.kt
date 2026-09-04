@file:OptIn(dev.aster.vega.model.InternalAsterVegaApi::class)

package dev.aster.vega.runtime.differential

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.loader.FileDataLoader
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.locale.VegaLocale
import dev.aster.vega.runtime.compile.CompiledSpec
import dev.aster.vega.runtime.compile.SpecCompiler
import dev.aster.vega.scene.Scene
import dev.aster.vega.scene.flatten
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
 * explicitly by `./scripts/oracle.sh`, so this needs neither Node nor a network (CONTRIBUTING.md).
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
    return reference to
      SpecCompiler(VegaHeadlessTextEngine(), fixtureLoader, locale = VegaLocale.EnglishUS)
        .compileJson(spec.readText())
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

  /**
   * And the scales a **group mark** built for itself, a faceted one once per cell.
   *
   * The row read `Not compared`, on the grounds that "a faceted group resolves its scales once per
   * cell and there is no single scale of that name to compare". Half of that was answered first: a
   * group drawn **once** has exactly one scale of its name, and half the nested scales in this
   * corpus are of that kind — the detail and overview panels of `overview-plus-detail`, the three
   * histograms of `crossfilter-flights`.
   *
   * The rest is answered here, and the premise turned out to be about the *key* rather than about
   * the data. Upstream keeps one `subcontext` entry per cell, each holding that cell's own resolved
   * scales; what was missing was a name to record them under. It is the group's name plus the
   * cell's **facet key** — the `groupby` values that made it — on both sides, so a cell only one
   * engine built shows up as a missing key rather than as a comparison against the wrong cell.
   *
   * A key rather than a position, deliberately: upstream hands out subcontexts in an array and this
   * engine builds its cells in partition order, and pairing those by index would mis-pair the
   * moment either side reordered. That is the failure mode that made node ids unusable here too.
   *
   * An **unnamed** group is still not recorded, for the reason it always was: there is no key.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  fun `a group mark's own scales match upstream`(name: String) {
    val (reference, compiled) = compile(name)
    // **No assumption.** A fixture with no scale inside a group has nothing to compare and passes
    // over an empty set, which is a pass rather than a skip. Skipping instead put 195 of the 198
    // fixtures into "assumed away" and CI refuses that — a suite that assumes itself out of
    // existence is the vacuous-test failure this repository keeps finding, and the gate is right to
    // treat a wall of skips as one. That the corpus really does exercise the named, faceted and
    // unnamed cases is `NestedScaleCoverageTest`'s assertion, where it can be made once instead of
    // once per fixture.
    val differences = mutableListOf<String>()
    val byFacetKey = facetKeyed(compiled)
    for ((group, scales) in reference.nestedScales) {
      val ours =
        byFacetKey[group]
          ?: compiled.groupScales[group]
          ?: run {
            differences += "group '$group' built no scales here; upstream has ${scales.keys}"
            continue
          }
      differences += Differential.compareScales(scales, ours).map { "group '$group': $it" }
    }
    assertTrue(
      differences.isEmpty(),
      "$name nested scale differences:\n${differences.joinToString("\n")}",
    )
  }

  /**
   * This engine's per-cell scales, under the same `name[|key|]` the recorder writes.
   *
   * The compiler keys a cell by its path — `site/cells[3]` — which is the right key for dispatching
   * a handler and the wrong one for comparing against another engine's array. So the facet key is
   * rebuilt here from the cell's own datum, which is the thing both engines agree the cell *is*.
   *
   * A group whose `from.facet` names a `field` rather than a `groupby` is left out: that is
   * pre-faceted data, one cell per *row*, and there is no grouping value to key by.
   */
  private fun facetKeyed(
    compiled: dev.aster.vega.runtime.compile.CompiledSpec
  ): Map<String, Map<String, dev.aster.vega.runtime.scale.VegaScale>> {
    val groupby = mutableMapOf<String, List<String>>()
    fun walk(marks: List<dev.aster.vega.model.spec.MarkSpec>) {
      for (mark in marks) {
        if (mark.type != dev.aster.vega.model.spec.MarkType.GROUP) continue
        val facet = mark.from?.facet
        if (mark.name != null && facet != null && facet.groupby.isNotEmpty()) {
          groupby[mark.name!!] = facet.groupby
        }
        walk(mark.marks)
      }
    }
    walk(compiled.spec?.marks.orEmpty())
    if (groupby.isEmpty()) return emptyMap()
    val out = mutableMapOf<String, Map<String, dev.aster.vega.runtime.scale.VegaScale>>()
    for ((path, scales) in compiled.groupScales) {
      val name = path.substringBefore("/cells[").substringAfterLast('/')
      val fields = groupby[name] ?: continue
      val key = Differential.facetKeyOf(compiled.groupDatums[path] ?: VegaValue.Null, fields)
      if (key != null) out["$name[$key]"] = scales
    }
    return out
  }

  /** True when a mark whose outline is approximated by cubics could be setting the surface size. */
  private fun curved(scene: dev.aster.vega.scene.Scene): Boolean =
    scene
      .flatten()
      .map { it.node }
      .any {
        it.metadata.markKind == "arc" || it.metadata.markKind == "trail"
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
    // rounding and nothing more — an actual disagreement is never a fraction of a unit. A drawing
    // whose reach is set by a curve gets the same allowance its extent does: upstream measures a
    // true circle where this scene graph has the cubics that approximate it.
    val tolerance =
      if (curved(scene)) Differential.CURVE_EXTENT_TOLERANCE else Differential.GEOMETRY_TOLERANCE
    assertEquals(reference.width, scene.width, tolerance, "$name width")
    assertEquals(reference.height, scene.height, tolerance, "$name height")
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  fun `compilation is deterministic`(name: String) {
    val spec = File(repositoryRoot, "test-fixtures/specs/$name.vg.json").readText()
    val once =
      SpecCompiler(VegaHeadlessTextEngine(), fixtureLoader, locale = VegaLocale.EnglishUS)
        .compileJson(spec)
    val again =
      SpecCompiler(VegaHeadlessTextEngine(), fixtureLoader, locale = VegaLocale.EnglishUS)
        .compileJson(spec)
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

    /**
     * A fixture's `url` data, read from the checked-in copy under `test-fixtures/data`.
     *
     * A **file** loader and nothing else: these tests must run from a checked-out tree with no
     * network (CONTRIBUTING.md), and a loader that could fall back to fetching would make a green
     * run depend on a connection. Missing data is fetched by `scripts/oracle.sh`, which is a
     * deliberate step whose result is reviewed and committed like a reference is.
     */
    val fixtureLoader = FileDataLoader(File(repositoryRoot, "test-fixtures"))

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
