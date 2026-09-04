package dev.aster.vega.android

import android.graphics.PorterDuff
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.aster.vega.scene.SceneBlendMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Which CSS blend modes a `PorterDuff` canvas can express, which is the documented limitation.
 *
 * `SUPPORTED_FEATURES.md` records that below API 29 the eleven modes `PorterDuff` has no equivalent
 * for are reported rather than swapped for whichever looks closest. That row used to stand on
 * *scope* — the branch is gated on `Build.VERSION.SDK_INT` and this project's test matrix has no
 * way to run below 29, the emulator being far above it and there being no Robolectric.
 *
 * But the branch is not the claim. The claim is the **table**, and a table can be tested anywhere:
 * `porterDuffFor` is a pure function of the mode, so what a device below 29 would do is decided
 * here and asserted here, on whatever device happens to run the suite.
 *
 * `minSdk` is 26, so those devices are real.
 */
@RunWith(AndroidJUnit4::class)
class AndroidBlendModeTableTest {

  private val renderer = AndroidCanvasSceneRenderer()

  /** The four with a true `PorterDuff` equivalent, and the mode each maps to. */
  private val mapped =
    mapOf(
      SceneBlendMode.SCREEN to PorterDuff.Mode.SCREEN,
      SceneBlendMode.OVERLAY to PorterDuff.Mode.OVERLAY,
      SceneBlendMode.DARKEN to PorterDuff.Mode.DARKEN,
      SceneBlendMode.LIGHTEN to PorterDuff.Mode.LIGHTEN,
    )

  @Test
  fun theFourExpressibleModesMapToTheirOwnEquivalent() {
    for ((mode, expected) in mapped) {
      assertEquals("$mode", expected, renderer.porterDuffFor(mode))
    }
  }

  /**
   * Everything else is refused, and that is eleven of the sixteen.
   *
   * Counted rather than listed, so adding a mode to `SceneBlendMode` without deciding what a pre-Q
   * canvas does with it fails here rather than silently drawing it as normal.
   */
  @Test
  fun elevenModesAreRefusedRatherThanApproximated() {
    val refused = SceneBlendMode.entries.filter { it != SceneBlendMode.NORMAL && it !in mapped }
    for (mode in refused) {
      assertNull("$mode should have no PorterDuff equivalent", renderer.porterDuffFor(mode))
    }
    assertEquals(11, refused.size)
    assertEquals(16, SceneBlendMode.entries.size)
  }

  /**
   * **`MULTIPLY` is refused on purpose**, and it is the one that matters most.
   *
   * Android's `PorterDuff.Mode.MULTIPLY` is documented as `[Sa * Da, Sc * Dc]` — *modulate*, not
   * CSS `multiply`. The two agree only where the destination is fully opaque; where it is
   * transparent, modulate produces transparent while CSS multiply produces the source unchanged. A
   * chart's background is transparent unless the specification paints one, so mapping it would make
   * the most-used blend mode make marks **vanish** over empty parts of the chart.
   *
   * Pinned separately from the count because it is the one a future reader would most reasonably
   * try to "fix".
   */
  @Test
  fun multiplyIsRefusedBecauseAndroidsIsModulate() {
    assertNull(renderer.porterDuffFor(SceneBlendMode.MULTIPLY))
  }

  /** `NORMAL` never reaches the table: it is cleared before the API check, on every device. */
  @Test
  fun normalIsHandledBeforeTheTable() {
    assertNull(renderer.porterDuffFor(SceneBlendMode.NORMAL))
  }
}
