package dev.aster.vega.runtime.differential

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The scale comparison covers **top-level** scales, and says so.
 *
 * `SUPPORTED_FEATURES.md` files nested scale outputs as `Not compared`, and a row admitting that a
 * comparison does not reach something needs holding up more than most: it is the shape of claim
 * that quietly becomes wrong in the *other* direction, where the coverage grows and nobody notices
 * that the caveat is now scaring readers off something that works.
 *
 * The reason is real. A faceted group resolves its scales once per cell, against that cell's data,
 * so there is no single scale of that name to compare — `CompiledSpec.scales` says the same thing
 * in its own KDoc. What the harness does compare is the cells' geometry, in full, and that is what
 * those scales produce: a wrong per-cell domain moves every mark in the cell.
 *
 * So this pins the scope: a faceted fixture's reference records the top-level scales and no others.
 */
class NestedScaleScopeTest {

  private val root = File(System.getProperty("user.dir")).parentFile

  /** Fixtures whose marks include a group that declares scales of its own. */
  private fun facetedFixtures(): List<String> {
    val specs = File(root, "test-fixtures/specs").listFiles { f -> f.name.endsWith(".vg.json") }
    return specs.orEmpty().mapNotNull { file ->
      val spec = VegaJson.parse(file.readText()) as? VegaValue.Obj ?: return@mapNotNull null
      val marks = spec.fields["marks"] as? VegaValue.Arr ?: return@mapNotNull null
      val nested =
        marks.values.any { mark ->
          val obj = mark as? VegaValue.Obj ?: return@any false
          (obj.fields["scales"] as? VegaValue.Arr)?.values?.isNotEmpty() == true
        }
      if (nested) file.name.removeSuffix(".vg.json") else null
    }
  }

  private fun names(value: VegaValue?): Set<String> =
    (value as? VegaValue.Obj)?.fields?.keys.orEmpty().toSet()

  @Test
  fun `a reference records the top-level scales and no nested ones`() {
    val faceted = facetedFixtures()
    assertTrue(
      faceted.isNotEmpty(),
      "no fixture declares scales inside a group, so this test checks nothing",
    )
    for (fixture in faceted) {
      val spec =
        VegaJson.parse(File(root, "test-fixtures/specs/$fixture.vg.json").readText())
          as VegaValue.Obj
      val reference =
        VegaJson.parse(File(root, "test-fixtures/reference/$fixture.reference.json").readText())
          as VegaValue.Obj

      val topLevel =
        (spec.fields["scales"] as? VegaValue.Arr)
          ?.values
          ?.mapNotNull { ((it as? VegaValue.Obj)?.fields?.get("name") as? VegaValue.Str)?.value }
          .orEmpty()
          .toSet()
      val recorded = names(reference.fields["scales"])

      assertEquals(
        emptySet<String>(),
        recorded - topLevel,
        "$fixture's reference records a scale that is not top-level, so nested scales are being " +
          "compared after all and this row is out of date",
      )
    }
  }
}
