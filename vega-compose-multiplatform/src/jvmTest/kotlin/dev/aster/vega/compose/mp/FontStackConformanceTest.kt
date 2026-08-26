package dev.aster.vega.compose.mp

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * This engine against `test-fixtures/host-conformance/font-stack.txt`.
 *
 * One golden, one reader per host — see that directory's README. `host-parity.py` checks a seam
 * exists; this checks the three engines *agree about what it does*, which is where the defects
 * were.
 */
class FontStackConformanceTest {

  @Test
  fun `asks its resolver the same names every other engine asks`() {
    val golden = File(HostConformance.repositoryRoot, HostConformance.FONT_STACK)
    val lines = HostConformance.cases(golden)

    val transcript = lines.map { (stack, _) ->
      val asked = mutableListOf<String>()
      val engine =
        ComposeTextEngine(
          TextMeasurer(
            defaultFontFamilyResolver = createFontFamilyResolver(),
            defaultDensity = Density(1f, 1f),
            defaultLayoutDirection = LayoutDirection.Ltr,
          ),
          Density(1f, 1f),
          { name ->
            asked.add(name)
            null as FontFamily?
          },
        )
      engine.fontFamilyResolver(stack)
      stack to asked.toList()
    }

    assertEquals(lines, transcript, "see ${golden.path}")
  }
}
