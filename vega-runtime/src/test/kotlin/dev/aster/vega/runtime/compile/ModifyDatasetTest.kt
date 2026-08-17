package dev.aster.vega.runtime.compile

import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.field
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `modify()` writing a dataset, including the branch a fixture cannot reach.
 *
 * A toggle that *removes* an existing row is the one case the differential fixture leaves out: run
 * from a signal's `update`, upstream applies its queued changeset twice and ends with two copies of
 * the row rather than none — re-entrancy in its scheduler rather than its documented behaviour. The
 * once-only rule below is what an event handler gets from upstream and what this engine does
 * everywhere.
 */
class ModifyDatasetTest {

  private fun rowsAfter(update: String): List<String> {
    val json =
      """
      {"width": 40, "height": 40,
       "data": [{"name": "sel", "values": [{"c": "alpha"}, {"c": "beta"}]}],
       "signals": [{"name": "did", "update": "$update"}],
       "marks": []}
      """
    val compiled = SpecCompiler().compileJson(json)
    return compiled.signals.dataset("sel").map {
      it.field("c").let { v -> (v as VegaValue.Str).value }
    }
  }

  @Test
  fun `a toggle removes a row that is already there`() {
    assertEquals(listOf("alpha"), rowsAfter("modify('sel', null, null, {c: 'beta'})"))
  }

  @Test
  fun `a toggle adds a row that is not`() {
    assertEquals(
      listOf("alpha", "beta", "gamma"),
      rowsAfter("modify('sel', null, null, {c: 'gamma'})"),
    )
  }

  @Test
  fun `remove true empties the dataset`() {
    assertEquals(emptyList<String>(), rowsAfter("modify('sel', null, true)"))
  }

  @Test
  fun `a remove matches field by field rather than by identity`() {
    assertEquals(listOf("beta"), rowsAfter("modify('sel', null, {c: 'alpha'})"))
  }
}
