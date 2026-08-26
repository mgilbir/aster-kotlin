package dev.aster.vega.compose.mp

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.aster.vega.scene.Scene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * This renderer against `test-fixtures/host-conformance/placement.txt`.
 *
 * One golden, one reader per host. The three renderers each compute where a scene goes in a slot,
 * and they disagreed until #99 in 0.3.0 — this one and SwiftUI centred a scene where the Android
 * view pinned it to the padded top-left, so the same chart sat in a different place depending on
 * the host. Nothing compared them, and `scripts/host-parity.py` cannot: a signature says nothing
 * about arithmetic.
 *
 * Read through `onPlaced`, which is the only way a host learns this and so the only reading worth
 * checking. Density is pinned at 1 so a dp slot is a pixel slot and the golden's numbers are the
 * ones this test means.
 */
@OptIn(ExperimentalTestApi::class)
class PlacementConformanceTest {

  @Test
  fun `places a scene where every other renderer places it`() {
    val cases =
      HostConformance.cases(java.io.File(HostConformance.repositoryRoot, HostConformance.PLACEMENT))
    assertTrue(cases.isNotEmpty(), "the golden is empty")

    for ((case, expected) in cases) {
      val (sceneSize, slot) = HostConformance.placementCase(case)
      var placement: ChartPlacement? = null
      runComposeUiTest {
        setContent {
          CompositionLocalProvider(LocalDensity provides Density(1f)) {
            VegaChart(
              Scene.empty(width = sceneSize.first, height = sceneSize.second),
              modifier = Modifier.size(slot.first.dp, slot.second.dp),
              onPlaced = { placement = it },
            )
          }
        }
        waitForIdle()
      }

      val placed = assertNotNull(placement, "the placement was never reported for $case")
      assertEquals(
        expected,
        listOf(
          HostConformance.six(placed.scale),
          HostConformance.six(placed.left),
          HostConformance.six(placed.top),
        ),
        "for $case",
      )
    }
  }
}
