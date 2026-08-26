package dev.aster.vega.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.aster.vega.scene.FontStyle
import dev.aster.vega.scene.TextDirection
import dev.aster.vega.scene.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * This engine against `test-fixtures/host-conformance/font-stack.txt`.
 *
 * One golden, one reader per host. `scripts/host-parity.py` checks a seam exists; this checks the
 * three engines *agree about what it does*, which is where the defects were — #123 was four hosts
 * carrying `fontResolver` and three reading a CSS stack three different ways.
 *
 * The golden travels with the app as an asset: an instrumented test runs on a device and cannot
 * read the repository. `build.gradle.kts` copies it in, so a change to the file reaches this test
 * the same way it reaches the other two.
 */
@RunWith(AndroidJUnit4::class)
class FontStackConformanceTest {

  private fun style(family: String) =
    TextStyle(
      fontFamily = family,
      fontSize = 12.0,
      fontWeight = 400,
      fontStyle = FontStyle.NORMAL,
      letterSpacing = 0.0,
      lineHeight = null,
      locale = "und",
      direction = TextDirection.LTR,
    )

  @Test
  fun asksItsResolverTheSameNamesEveryOtherEngineAsks() {
    val golden =
      InstrumentationRegistry.getInstrumentation()
        .context
        .assets
        .open("font-stack.txt")
        .bufferedReader()
        .use { it.readText() }

    val expected = HostConformance.cases(golden)
    val transcript = expected.map { (stack, _) ->
      val asked = mutableListOf<String>()
      val engine =
        AndroidTextEngine(
          typefaceResolver = {
            asked.add(it)
            null
          }
        )
      engine.advanceOf("M", style(stack))
      stack to asked.toList()
    }

    assertEquals(expected, transcript)
  }
}
