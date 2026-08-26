package dev.aster.vega.android

import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.aster.vega.scene.FontStyle
import dev.aster.vega.scene.TextDirection
import dev.aster.vega.scene.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The reproduction from #123, on the renderer that failed it worst.
 *
 * A specification writes a CSS stack — `"Noto Sans, Chart Sans"` — and this engine handed the whole
 * string to the host's resolver. A host that had registered `Chart Sans` was asked for a name it
 * had never heard of, answered nothing, and the chart drew in the platform default while the
 * Compose Multiplatform renderer drew the registered face from the same specification.
 */
@RunWith(AndroidJUnit4::class)
class FontStackInstrumentedTest {

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
  fun aRegisteredFaceIsFoundAnywhereInTheStack() {
    val asked = mutableListOf<String>()
    val engine =
      AndroidTextEngine(
        typefaceResolver = { name ->
          asked.add(name)
          if (name == "Chart Sans") Typeface.MONOSPACE else null
        }
      )

    // Measuring is what resolves the face, so the widths are the observable.
    val registered = engine.advanceOf("MMMM", style("Noto Sans, Chart Sans"))
    val platform = engine.advanceOf("MMMM", style("Noto Sans"))

    assertEquals(listOf("Noto Sans", "Chart Sans", "Noto Sans"), asked)
    assertEquals(
      "the registered face should have been used, so the advance differs from the default",
      false,
      registered == platform,
    )
  }

  @Test
  fun aGenericDoesNotStopTheSearch() {
    // The Apple renderer used to stop at a leading generic; this one never split at all. Both now
    // offer every name, so a stack that ends in a registered face finds it.
    val asked = mutableListOf<String>()
    val engine =
      AndroidTextEngine(
        typefaceResolver = { name ->
          asked.add(name)
          if (name == "Chart Sans") Typeface.MONOSPACE else null
        }
      )
    engine.advanceOf("M", style("sans-serif, Chart Sans"))

    assertEquals(listOf("sans-serif", "Chart Sans"), asked)
  }

  @Test
  fun aStackFallsBackToItsOwnGenericRatherThanToTheWholeString() {
    // With nothing registered, `"Noto Sans, monospace"` asks for a mono as its last resort. This
    // used to match none of the platform aliases and reach `Typeface.create` with the entire list
    // as
    // a family name, which resolves to the default.
    val engine = AndroidTextEngine(typefaceResolver = { null })
    val stack = engine.advanceOf("iiii", style("Noto Sans, monospace"))
    val mono = engine.advanceOf("iiii", style("monospace"))

    assertEquals("a stack naming monospace should measure as monospace", mono, stack, 0.01)
  }

  @Test
  fun aFamilyNothingCouldAnswerIsRecorded() {
    // Android answers an unknown name with the default face: legible, wrong, and silent. Only the
    // Compose Multiplatform engine said so, which is part of why the three renderers disagreed
    // about
    // reading a stack for as long as they did (#123).
    val engine = AndroidTextEngine(typefaceResolver = { null })
    engine.advanceOf("M", style("Definitely Not Installed"))

    assertEquals(setOf("Definitely Not Installed"), engine.unresolvedFontFamilies)
  }

  @Test
  fun aStackThatEndsInAGenericIsNotAMiss() {
    // A well-formed stack asks for a generic as its last resort and gets it. Recording that would
    // make the set noise, and a set nobody can act on gets ignored.
    val engine = AndroidTextEngine(typefaceResolver = { null })
    engine.advanceOf("M", style("Noto Sans, sans-serif"))

    assertEquals(emptySet<String>(), engine.unresolvedFontFamilies)
  }

  @Test
  fun aFamilyTheHostAnsweredIsNotAMiss() {
    // What the host returns is its business; that it returned something is the fact. The Apple
    // engine got this wrong first — it compared the resulting face's name against the requested
    // one,
    // which records a host answering `Chart Sans` with a different face as a miss.
    val engine = AndroidTextEngine(typefaceResolver = { Typeface.MONOSPACE })
    engine.advanceOf("M", style("Chart Sans"))

    assertEquals(emptySet<String>(), engine.unresolvedFontFamilies)
  }
}
