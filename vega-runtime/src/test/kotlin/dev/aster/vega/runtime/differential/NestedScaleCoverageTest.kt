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
 * This class used to hold that the reference recorded **no** nested scale at all, on the row's
 * stated grounds: "a faceted group resolves its scales once per cell, so there is no single scale
 * of that name to compare". That reason is sound and it covers only *faceted* groups. Half the
 * nested scales in this corpus belong to a group drawn **once** — the detail and overview panels of
 * `overview-plus-detail`, the three histograms of `crossfilter-flights` — and each of those has
 * exactly one scale of its name. They were excluded by a reason that did not apply to them.
 *
 * So `normalizeNestedScales` records a group's scales when its **name** identifies exactly one
 * subcontext, and `FixtureDifferentialTest` compares them. What is left out is left out for the
 * reason the row gives, and this says which is which so neither side can drift:
 *
 * - a **named, singly-drawn** group: recorded and compared;
 * - a **faceted** group: not recorded, because there is no single scale of that name;
 * - an **unnamed** group: not recorded, because there is no key to record it under.
 *
 * Keyed by name rather than by upstream's subcontext index on purpose. Pairing by position would
 * mis-pair the moment a mark was added, which is the same reason node ids are unusable for
 * comparison in this harness.
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

  /** A faceted group's are not, because there is no single scale of that name. */
  @Test
  fun `a faceted group's scales are not recorded`() {
    val wrong = mutableListOf<String>()
    for (nested in nestedScaleGroups()) {
      if (!nested.faceted) continue
      val name = nested.group ?: continue
      if (name in recordedGroups(nested.fixture)) wrong += "${nested.fixture}/$name"
    }
    assertEquals(
      emptyList<String>(),
      wrong,
      "a faceted group's scales were recorded, so one cell's is standing in for all of them",
    )
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
