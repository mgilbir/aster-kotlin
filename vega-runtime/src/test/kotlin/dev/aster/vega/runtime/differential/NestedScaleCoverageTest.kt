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
 * So what is left out is left out for the one reason that survives, and this says which case each
 * group falls into so neither side can drift:
 *
 * - a **named** group, drawn once or faceted: recorded and compared, a faceted one per cell;
 * - an **unnamed** group: not recorded, because there is no key to record it under.
 *
 * Keyed by name and facet key rather than by upstream's subcontext index on purpose. Pairing by
 * position would mis-pair the moment either side reordered, which is the same reason node ids are
 * unusable for comparison in this harness.
 */
class NestedScaleCoverageTest {

  private val root = File(System.getProperty("user.dir")).parentFile

  private class Nested(val fixture: String, val group: String?, val faceted: Boolean)

  /** Every group mark in the corpus that declares a scale of its own, with how it is drawn. */
  private fun nestedScaleGroups(): List<Nested> {
    val specs = File(root, "test-fixtures/specs").listFiles { f -> f.name.endsWith(".vg.json") }
    val out = mutableListOf<Nested>()
    for (file in specs.orEmpty().sortedBy { it.name }) {
      val spec = VegaJson.parse(file.readText()) as? VegaValue.Obj ?: continue
      fun walk(marks: VegaValue?) {
        val arr = marks as? VegaValue.Arr ?: return
        for (mark in arr.values) {
          val obj = mark as? VegaValue.Obj ?: continue
          if ((obj.fields["type"] as? VegaValue.Str)?.value != "group") continue
          if ((obj.fields["scales"] as? VegaValue.Arr)?.values?.isNotEmpty() == true) {
            val from = obj.fields["from"] as? VegaValue.Obj
            out +=
              Nested(
                fixture = file.name.removeSuffix(".vg.json"),
                group = (obj.fields["name"] as? VegaValue.Str)?.value,
                faceted = from?.fields?.containsKey("facet") == true,
              )
          }
          walk(obj.fields["marks"])
        }
      }
      walk(spec.fields["marks"])
    }
    return out
  }

  private fun recordedGroups(fixture: String): Set<String> {
    val file = File(root, "test-fixtures/reference/$fixture.reference.json")
    if (!file.isFile) return emptySet()
    val reference = VegaJson.parse(file.readText()) as VegaValue.Obj
    return (reference.fields["nestedScales"] as? VegaValue.Obj)?.fields?.keys.orEmpty().toSet()
  }

  /** The corpus has all three shapes in it, so the rules below each decide something. */
  @Test
  fun `the corpus exercises named, faceted and unnamed nested scales`() {
    val groups = nestedScaleGroups()
    assertTrue(groups.isNotEmpty(), "no fixture declares a scale inside a group")
    assertTrue(
      groups.any { it.group != null && !it.faceted },
      "no named, singly-drawn group declares a scale, so the recorded case is untested",
    )
    assertTrue(
      groups.any { it.faceted },
      "no faceted group declares a scale, so the excluded case is untested",
    )
    assertTrue(
      groups.any { it.group == null },
      "no unnamed group declares a scale, so the unkeyable case is untested",
    )
  }

  /** A named, singly-drawn group's scales are recorded — and therefore compared. */
  @Test
  fun `a named group drawn once has its scales recorded`() {
    val missing = mutableListOf<String>()
    for (nested in nestedScaleGroups()) {
      val name = nested.group ?: continue
      if (nested.faceted) continue
      // A name used by more than one group in the same chart is the faceted case by another route.
      if (nestedScaleGroups().count { it.fixture == nested.fixture && it.group == name } > 1) {
        continue
      }
      if (name !in recordedGroups(nested.fixture)) missing += "${nested.fixture}/$name"
    }
    assertEquals(
      emptyList<String>(),
      missing,
      "these groups are drawn once and named, so their scales should be recorded and compared",
    )
  }

  /**
   * A **named faceted** group's are recorded once per cell, under the cell's facet key.
   *
   * The half this class used to assert the opposite of. What makes it a real check rather than a
   * restatement is the count: a group recorded under one key would satisfy "recorded" while still
   * letting one cell stand in for all of them, which is the thing the old reason was right to
   * refuse. So this asks for **every** cell.
   */
  @Test
  fun `a named faceted group's scales are recorded once per cell`() {
    val wrong = mutableListOf<String>()
    for (nested in nestedScaleGroups()) {
      if (!nested.faceted) continue
      val name = nested.group ?: continue
      val cells = recordedGroups(nested.fixture).filter { it.startsWith("$name[") }
      if (cells.size < 2) {
        wrong += "${nested.fixture}/$name: ${cells.size} cell(s) recorded, and a facet has several"
      }
      if (name in recordedGroups(nested.fixture)) {
        wrong += "${nested.fixture}/$name: recorded unkeyed, so one cell stands in for all of them"
      }
    }
    assertEquals(
      emptyList<String>(),
      wrong,
      "a named faceted group's cells are not each recorded and compared",
    )
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
        .filter { it.faceted && it.group != null }
        .sumOf { nested ->
          recordedGroups(nested.fixture).count { it.startsWith("${nested.group}[") }
        }
    assertTrue(
      cells >= 9,
      "only $cells faceted cells have their scales recorded, down from 9; the recorder or a " +
        "fixture has lost some, and an unrecorded cell is compared against nothing",
    )
  }

  /** An **unnamed** group's are not, because there is still no key to record them under. */
  @Test
  fun `an unnamed group's scales are not recorded`() {
    val fixtures = nestedScaleGroups().filter { it.group == null }.map { it.fixture }.distinct()
    assertTrue(fixtures.isNotEmpty(), "no unnamed group declares a scale, so this decides nothing")
    for (fixture in fixtures) {
      val named =
        nestedScaleGroups().filter { it.fixture == fixture }.mapNotNull { it.group }.toSet()
      val unaccounted =
        recordedGroups(fixture).filterNot { key ->
          named.any { key == it || key.startsWith("$it[") }
        }
      assertEquals(
        emptyList<String>(),
        unaccounted,
        "$fixture records a group's scales under a key no named group in it accounts for",
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
