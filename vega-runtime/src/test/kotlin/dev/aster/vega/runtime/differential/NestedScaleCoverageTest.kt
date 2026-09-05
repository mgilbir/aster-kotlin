package dev.aster.vega.runtime.differential

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which nested scales the comparison reaches, and which it does not — measured, not asserted.
 *
 * This class began by holding that the reference recorded **no** nested scale at all, on the row's
 * stated grounds: "a faceted group resolves its scales once per cell, so there is no single scale
 * of that name to compare". It came down in two steps, and both times the reason had been read
 * wider than it was true.
 *
 * First: it covers only *faceted* groups, and half the nested scales in this corpus belong to a
 * group drawn **once** — the detail and overview panels of `overview-plus-detail`, the three
 * histograms of `crossfilter-flights`. Those have exactly one scale of their name and were excluded
 * by a reason that did not apply to them.
 *
 * Then the faceted half, where the premise turned out to be about the **key** and not about the
 * data. Upstream keeps one `subcontext` entry per cell, each with that cell's own resolved scales;
 * "no single scale of that name" is true and only means there is no single *name*. There is one per
 * cell, and it is the group's name plus the cell's facet key — `site[|"Waseca"|]`.
 *
 * And then the last of it, which was "an **unnamed** group has no key to record it under". It has
 * an identity, and it is the one the engine already gives it: its **position among its siblings**,
 * which `ScopeCompiler` spells `[i]`. `normalizeNestedScales` builds the same spelling from the
 * runtime, taking the guides out of the sibling list first — the scenegraph interleaves an axis, a
 * legend and a title among the marks and the specification does not.
 *
 * So every group with a scale of its own is now compared, and this says which shape each one in the
 * corpus is so that neither side can drift:
 *
 * - a group **drawn once**, named or not: one entry, under its path;
 * - a **faceted** group, named or not: one entry per cell, under its path and the cell's facet key.
 *
 * **A position taken from the specification, not from an array of results**, and that distinction
 * is the whole reason this is safe where "pair them by subcontext index" was not: add a mark and
 * both engines renumber together, because both are counting the same `marks` array. Pairing by
 * position in upstream's *output* would mis-pair the moment either side reordered, which is the
 * same reason node ids are unusable for comparison in this harness.
 */
class NestedScaleCoverageTest {

  private val root = File(System.getProperty("user.dir")).parentFile

  private class Nested(
    val fixture: String,
    val group: String?,
    /**
     * The path the compiler records this group's scope under: its name, or `[i]` where it has none.
     *
     * The same spelling on both sides of the comparison — `ScopeCompiler.path` builds it from the
     * specification and `normalizeNestedScales` builds it from the runtime — which is what lets an
     * unnamed group be compared at all.
     */
    val path: String,
    val kind: Drawn,
  ) {
    val faceted: Boolean
      get() = kind == Drawn.FACET
  }

  /**
   * How many times a group with scales of its own is drawn, and what tells its copies apart.
   *
   * The three shapes the corpus has, and the reason the comparison can reach two of them:
   * - [ONCE] — no `from`, or one naming neither data nor a facet. One scope, one key: its path.
   * - [FACET] — one scope per cell, told apart by the `groupby` values that made the cell.
   * - [FROM_DATA] — `from: {"data": …}`, which draws the group **once per row**. Several scopes at
   *   one path with no grouping value between them: the only thing that distinguishes a cell is the
   *   row it was drawn for, and pairing those means agreeing on a canonical form for an arbitrary
   *   datum in two languages. A key that fails to pair is a comparison that goes silent, which is
   *   worse than the honest gap, so this one stays uncompared and its cells' geometry carries it —
   *   a wrong per-cell domain moves every mark in the cell.
   */
  private enum class Drawn {
    ONCE,
    FACET,
    FROM_DATA,
  }

  /** Every group mark in the corpus that declares a scale of its own, with how it is drawn. */
  private fun nestedScaleGroups(): List<Nested> {
    val specs = File(root, "test-fixtures/specs").listFiles { f -> f.name.endsWith(".vg.json") }
    val out = mutableListOf<Nested>()
    for (file in specs.orEmpty().sortedBy { it.name }) {
      val spec = VegaJson.parse(file.readText()) as? VegaValue.Obj ?: continue
      fun walk(marks: VegaValue?, prefix: String) {
        val arr = marks as? VegaValue.Arr ?: return
        // Indexed over **every** mark, not only the group ones, because that is what
        // `ScopeCompiler` numbers a group's siblings with.
        arr.values.forEachIndexed { index, mark ->
          val obj = mark as? VegaValue.Obj ?: return@forEachIndexed
          if ((obj.fields["type"] as? VegaValue.Str)?.value != "group") return@forEachIndexed
          val name = (obj.fields["name"] as? VegaValue.Str)?.value
          val here = (if (prefix.isEmpty()) "" else "$prefix/") + (name ?: "[$index]")
          if ((obj.fields["scales"] as? VegaValue.Arr)?.values?.isNotEmpty() == true) {
            val from = obj.fields["from"] as? VegaValue.Obj
            out +=
              Nested(
                fixture = file.name.removeSuffix(".vg.json"),
                group = name,
                path = here,
                kind =
                  when {
                    from?.fields?.containsKey("facet") == true -> Drawn.FACET
                    from?.fields?.containsKey("data") == true -> Drawn.FROM_DATA
                    else -> Drawn.ONCE
                  },
              )
          }
          walk(obj.fields["marks"], here)
        }
      }
      walk(spec.fields["marks"], "")
    }
    return out
  }

  private fun recordedGroups(fixture: String): Set<String> {
    val file = File(root, "test-fixtures/reference/$fixture.reference.json")
    if (!file.isFile) return emptySet()
    val reference = VegaJson.parse(file.readText()) as VegaValue.Obj
    return (reference.fields["nestedScales"] as? VegaValue.Obj)?.fields?.keys.orEmpty().toSet()
  }

  /** The corpus has every shape in it, so the rules below each decide something. */
  @Test
  fun `the corpus exercises each shape of nested scale`() {
    val groups = nestedScaleGroups()
    assertTrue(groups.isNotEmpty(), "no fixture declares a scale inside a group")
    for (kind in Drawn.entries) {
      assertTrue(
        groups.any { it.kind == kind },
        "no group with scales of its own is drawn $kind, so that rule decides nothing",
      )
    }
    assertTrue(
      groups.any { it.group == null && it.kind != Drawn.FROM_DATA },
      "no unnamed group is compared, so the index-keyed case is untested",
    )
    assertTrue(
      groups.any { it.group != null },
      "no named group declares a scale, so the name-keyed case is untested",
    )
  }

  /** A group drawn once has its scales recorded under its path, named or not. */
  @Test
  fun `a group drawn once has its scales recorded`() {
    val missing = mutableListOf<String>()
    for (nested in nestedScaleGroups()) {
      if (nested.kind != Drawn.ONCE) continue
      // A path used by more than one group in the same chart is a group nested inside a faceted
      // one, drawn once per cell of its parent. `ScopeCompiler` distinguishes those by pushing the
      // parent's cell onto the path; the recorder does not read that back, so they are left out.
      if (
        nestedScaleGroups().count { it.fixture == nested.fixture && it.path == nested.path } > 1
      ) {
        continue
      }
      if (nested.path !in recordedGroups(nested.fixture)) {
        missing += "${nested.fixture}/${nested.path}"
      }
    }
    assertEquals(
      emptyList<String>(),
      missing,
      "these groups are drawn once, so their scales should be recorded and compared",
    )
  }

  /**
   * A **faceted** group's are recorded once per cell, under the cell's facet key. Named or not.
   *
   * The half this class used to assert the opposite of. What makes it a real check rather than a
   * restatement is the count: a group recorded under one key would satisfy "recorded" while still
   * letting one cell stand in for all of them, which is the thing the old reason was right to
   * refuse. So this asks for **every** cell.
   */
  @Test
  fun `a faceted group's scales are recorded once per cell`() {
    val wrong = mutableListOf<String>()
    for (nested in nestedScaleGroups()) {
      if (!nested.faceted) continue
      val cells = recordedGroups(nested.fixture).filter { it.startsWith("${nested.path}[") }
      if (cells.size < 2) {
        wrong +=
          "${nested.fixture}/${nested.path}: ${cells.size} cell(s) recorded, and a facet has several"
      }
      if (nested.path in recordedGroups(nested.fixture)) {
        wrong +=
          "${nested.fixture}/${nested.path}: recorded unkeyed, so one cell stands in for all of them"
      }
    }
    assertEquals(emptyList<String>(), wrong, "a faceted group's cells are not each compared")
  }

  /**
   * And the corpus keeps enough of them to matter: nine cells over two fixtures.
   *
   * A floor rather than an equality, because a new fixture may add cells. What it may not do is
   * take the number down, which is what the recorder losing its facet key would look like — and
   * losing it would leave `FixtureDifferentialTest` passing over an empty set in silence, since a
   * key that is not recorded is a comparison that does not happen.
   */
  @Test
  fun `the number of compared facet cells does not fall`() {
    val cells =
      nestedScaleGroups()
        .filter { it.faceted }
        .sumOf { nested ->
          recordedGroups(nested.fixture).count { it.startsWith("${nested.path}[") }
        }
    assertTrue(
      cells >= 52,
      "only $cells faceted cells have their scales recorded, down from 52; the recorder or a " +
        "fixture has lost some, and an unrecorded cell is compared against nothing",
    )
  }

  /**
   * An **unnamed** group's are recorded too, under the index the engine already knows it by.
   *
   * This asserted the opposite, on the last surviving form of the row's original reason: no name,
   * no key. There is a key — `ScopeCompiler` has spelled an unnamed group `[i]` since it was
   * written, because a scope has to be recorded under *something* — and the recorder now builds the
   * same spelling from the runtime. Forty-three cells over `grouped-bar`, `calendar-view` and
   * `u-district-cuisine`, plus the singly-drawn plots of `qq-plot`.
   */
  @Test
  fun `an unnamed group's scales are recorded under its index`() {
    val unnamed = nestedScaleGroups().filter { it.group == null && it.kind != Drawn.FROM_DATA }
    assertTrue(unnamed.isNotEmpty(), "no unnamed group declares a scale, so this decides nothing")
    val missing = mutableListOf<String>()
    for (nested in unnamed) {
      val recorded = recordedGroups(nested.fixture)
      val found =
        if (nested.faceted) recorded.any { it.startsWith("${nested.path}[") }
        else nested.path in recorded
      if (!found) missing += "${nested.fixture}/${nested.path}"
    }
    assertEquals(
      emptyList<String>(),
      missing,
      "these groups have no name and are still compared by their index; nothing recorded them",
    )
  }

  /**
   * A group drawn **once per row** of a dataset is the one shape still uncompared, and why.
   *
   * `from: {"data": "rows"}` draws the group once per row, so there are several scopes at one path
   * with no grouping value between them. The only thing that tells two cells apart is the row each
   * was drawn for, and pairing on that means agreeing on a canonical form for an arbitrary datum in
   * two languages — where a key that fails to pair is a comparison that goes *silent*, which is
   * worse than the gap. Its cells' geometry is compared in full instead, and a wrong per-cell
   * domain moves every mark in the cell.
   *
   * One such group in the corpus. This is what keeps the row honest if that changes.
   */
  @Test
  fun `a group drawn once per row is not recorded`() {
    val fromData = nestedScaleGroups().filter { it.kind == Drawn.FROM_DATA }
    assertTrue(
      fromData.isNotEmpty(),
      "no group is drawn from a dataset, so the one remaining gap is untested",
    )
    for (nested in fromData) {
      val recorded = recordedGroups(nested.fixture)
      assertTrue(
        recorded.none { it == nested.path || it.startsWith("${nested.path}[") },
        "${nested.fixture}/${nested.path} is drawn once per row and something recorded it under " +
          "one key, so one row's scales are standing in for all of them",
      )
    }
  }

  /**
   * And the top-level recording still holds only top-level names.
   *
   * The nested scales went into a key of their own rather than being folded in beside them, so a
   * group's `yscale` cannot come to shadow the chart's in the comparison.
   */
  @Test
  fun `the top-level scale recording is unchanged`() {
    for (nested in nestedScaleGroups().distinctBy { it.fixture }) {
      val spec =
        VegaJson.parse(File(root, "test-fixtures/specs/${nested.fixture}.vg.json").readText())
          as VegaValue.Obj
      val reference =
        VegaJson.parse(
          File(root, "test-fixtures/reference/${nested.fixture}.reference.json").readText()
        ) as VegaValue.Obj
      val topLevel =
        (spec.fields["scales"] as? VegaValue.Arr)
          ?.values
          ?.mapNotNull { ((it as? VegaValue.Obj)?.fields?.get("name") as? VegaValue.Str)?.value }
          .orEmpty()
          .toSet()
      val recorded = (reference.fields["scales"] as? VegaValue.Obj)?.fields?.keys.orEmpty().toSet()
      assertEquals(
        emptySet<String>(),
        recorded - topLevel,
        "${nested.fixture} records a non-top-level scale under `scales`, so a group's scale can " +
          "shadow the chart's",
      )
    }
  }
}
