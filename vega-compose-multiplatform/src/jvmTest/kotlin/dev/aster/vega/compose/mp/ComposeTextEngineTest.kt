package dev.aster.vega.compose.mp

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.aster.vega.scene.MetricTextEngine
import dev.aster.vega.scene.TextStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Compose text engine, measured against the two things it has to be right about: the font, and
 * the units.
 *
 * The module had no text engine at all, so it fell back to `MetricTextEngine` — advance widths that
 * are a fixed fraction of the font size and match no real font — while Compose drew real glyphs.
 * The layout was therefore computed from widths nothing would draw with, and the symptom on iOS,
 * where the same mistake was made and fixed first, was right-aligned axis labels sitting over the
 * domain line.
 *
 * Units are the other half and the easier one to get wrong: Compose measures in **pixels**, the
 * engine works in scene units, and the reader's font scale has to survive the trip because the
 * whole reason to measure with it is so the axis reserves the larger box.
 */
class ComposeTextEngineTest {

  private fun engine(
    density: Float = 1f,
    fontScale: Float = 1f,
    resolver: (String) -> FontFamily? = ::genericFontFamily,
  ): ComposeTextEngine {
    val densities = Density(density, fontScale)
    val measurer =
      TextMeasurer(
        defaultFontFamilyResolver = createFontFamilyResolver(),
        defaultDensity = densities,
        defaultLayoutDirection = LayoutDirection.Ltr,
      )
    return ComposeTextEngine(measurer, densities, resolver)
  }

  private val style = TextStyle(fontFamily = "sans-serif", fontSize = 11.0)

  @Test
  fun `an advance is a real font's, not a ratio of the font size`() {
    val engine = engine()
    val fallback = MetricTextEngine()

    // Two strings of the same length, one narrow and one wide. A ratio of the font size cannot tell
    // them apart and says both are 26.4 units; a real font is emphatic about it. That is the whole
    // difference this class exists to make, stated as something no coincidence can satisfy.
    assertEquals(
      fallback.advanceOf("iiii", style),
      fallback.advanceOf("mmmm", style),
      0.001,
      "the fallback engine is length times a ratio, which is why it cannot lay out a chart",
    )
    val narrow = engine.advanceOf("iiii", style)
    val wide = engine.advanceOf("mmmm", style)
    assertTrue(wide > narrow * 2.0, "iiii is $narrow and mmmm is $wide; this is not a real font")
  }

  @Test
  fun `a wider string measures wider, and a larger font measures larger`() {
    val engine = engine()
    assertTrue(engine.advanceOf("mm", style) > engine.advanceOf("m", style))
    assertTrue(engine.advanceOf("i", style) < engine.advanceOf("m", style), "proportional font")
    assertTrue(
      engine.advanceOf("Time", style.copy(fontSize = 22.0)) > 1.5 * engine.advanceOf("Time", style),
      "double the font size is far more than half again as wide",
    )
  }

  @Test
  fun `an advance is in scene units, so the display's density does not change it`() {
    val one = engine(density = 1f).advanceOf("Measurement", style)
    val three = engine(density = 3f).advanceOf("Measurement", style)

    // Scene units are CSS pixels. A phone at 3x has three times as many *pixels* for the same
    // label,
    // and the layout above this engine must not see a label three times as wide — which is exactly
    // what forgetting the division would produce, and is the same arithmetic slip that drew every
    // glyph at three times its size.
    assertEquals(one, three, 0.05, "the density leaked into the measurement")
  }

  @Test
  fun `the reader's font scale does change it, which is the point`() {
    val plain = engine().advanceOf("Measurement", style)
    val enlarged = engine(fontScale = 2f).advanceOf("Measurement", style)

    // Not cosmetic: the axis reserves its label box from this number. Feeding the scale in here is
    // what makes a chart at a 2x text setting reserve a box twice as wide instead of overlapping.
    assertEquals(2.0 * plain, enlarged, plain * 0.05, "font scale did not reach the measurement")

    val ascentPlain = engine().ascentOf(style)
    assertEquals(
      2.0 * ascentPlain,
      engine(fontScale = 2f).ascentOf(style),
      ascentPlain * 0.05,
      "a taller line needs a taller reserved box too",
    )
  }

  @Test
  fun `vertical metrics come from the font and bracket the baseline`() {
    val engine = engine()
    val ascent = engine.ascentOf(style)
    val descent = engine.descentOf(style)

    assertTrue(ascent > 0.0 && descent > 0.0, "ascent $ascent descent $descent")
    assertTrue(
      ascent > descent,
      "a Latin face rises further above the baseline than it falls below",
    )
    // The line height is the font's own, which is what the base class invites a platform engine to
    // prefer, and it has to be at least the ink it contains.
    assertTrue(
      engine.defaultLineHeightOf(style) >= ascent + descent - 0.001,
      "line height ${engine.defaultLineHeightOf(style)} is less than $ascent + $descent",
    )
  }

  @Test
  fun `an empty line has no width, and is not measured`() {
    assertEquals(0.0, engine().advanceOf("", style))
  }

  @Test
  fun `a host's own resolver decides the face, and the engine follows it`() {
    // Narrow letters, because that is where a monospaced face and a proportional one part company
    // by
    // more than rounding: ten `i`s are ten full advances in one and a thin ribbon in the other.
    val probe = "iiiiiiiiii"
    val serif = engine(resolver = { FontFamily.Serif }).advanceOf(probe, style)
    val monospace = engine(resolver = { FontFamily.Monospace }).advanceOf(probe, style)

    // Different faces, different advances — which is what makes "the host ships Google Sans Flex
    // and
    // the chart has to use it" a request this seam can answer.
    assertTrue(monospace > serif * 1.3, "serif $serif and monospace $monospace are too close")
  }

  @Test
  fun `the default resolver maps the generic families and declines the rest`() {
    assertEquals(FontFamily.SansSerif, genericFontFamily("sans-serif"))
    assertEquals(FontFamily.Serif, genericFontFamily("serif"))
    assertEquals(FontFamily.Monospace, genericFontFamily("monospace"))
    assertEquals(FontFamily.Cursive, genericFontFamily("cursive"))
    // A CSS font stack, read left to right as CSS reads it: the first name this can answer wins.
    assertEquals(FontFamily.Serif, genericFontFamily("\"Google Sans Flex\", serif"))
    // A face this cannot know about. Declined rather than guessed, so the caller's own resolver —
    // or
    // the platform default — decides.
    assertEquals(null, genericFontFamily("Google Sans Flex"))
  }

  @Test
  fun `letter spacing widens a run, as CSS says it should`() {
    val engine = engine()
    val plain = engine.advanceOf("Time", style)
    val spaced = engine.advanceOf("Time", style.copy(letterSpacing = 4.0))

    assertTrue(spaced > plain + 8.0, "plain $plain spaced $spaced")
  }

  /**
   * A host's own face is found **by name**.
   *
   * The default resolver knows the generic CSS keywords and nothing else, so a themed specification
   * naming a real family — the one the app bundles, the one its design system uses — was drawn in
   * the platform's default face. The Apple and Android engines resolve an installed family through
   * their platforms (`CoreTextFonts` by descriptor, `AndroidTextEngine` through `Typeface.create`);
   * common Compose code reaches neither, so a name can only be resolved by a host that already
   * holds the `FontFamily`. This is that registry.
   */
  @Test
  fun `a registered family resolves by name, whatever the case, before a generic fallback`() {
    val resolve = namedFontFamily(mapOf("Google Sans Flex" to FontFamily.Cursive))

    assertEquals(FontFamily.Cursive, resolve("Google Sans Flex"))
    // A specification's `"Helvetica Neue"` and a host's `"helvetica neue"` are the same face, and a
    // chart should not turn on which was typed.
    assertEquals(FontFamily.Cursive, resolve("google sans flex"))
    assertEquals(FontFamily.Cursive, resolve("\"Google Sans Flex\""))
    // Left to right, as CSS reads a stack: the registered name beats the generic written after it.
    assertEquals(FontFamily.Cursive, resolve("Google Sans Flex, sans-serif"))
    // And a generic beats a name nobody registered, which is what makes a well-formed stack work.
    assertEquals(FontFamily.SansSerif, resolve("Unknown Face, sans-serif"))
    // Registering nothing changes nothing.
    assertEquals(genericFontFamily("serif"), namedFontFamily(emptyMap())("serif"))
    assertEquals(null, namedFontFamily(emptyMap())("Unknown Face"))
  }

  /**
   * A family the resolver declined is **said**, rather than silently becoming the default face.
   *
   * There is no diagnostics channel through a text engine, so this is the same shape as
   * `DrawScopeTarget.unresolvedImages`: the facts are collected while measuring — which is before
   * anything is drawn — and a host reads them when it wants to.
   */
  @Test
  fun `a family the resolver cannot answer is recorded`() {
    val engine = engine()
    assertEquals(emptySet(), engine.unresolvedFontFamilies)

    // A generic resolves, so it is not reported.
    engine.advanceOf("Total", TextStyle(fontFamily = "sans-serif", fontSize = 11.0))
    assertEquals(emptySet(), engine.unresolvedFontFamilies)

    // A well-formed stack resolves on its last entry, so neither is this.
    engine.advanceOf(
      "Total",
      TextStyle(fontFamily = "Helvetica Neue, Arial, serif", fontSize = 11.0),
    )
    assertEquals(emptySet(), engine.unresolvedFontFamilies)

    // A name with nothing generic behind it is drawn in the default face, and now says so.
    engine.advanceOf("Total", TextStyle(fontFamily = "Google Sans Flex", fontSize = 11.0))
    engine.advanceOf("Total", TextStyle(fontFamily = "Google Sans Flex", fontSize = 12.0))
    assertEquals(setOf("Google Sans Flex"), engine.unresolvedFontFamilies)

    // Registering it makes it resolve, and nothing is reported.
    val registered =
      engine(resolver = namedFontFamily(mapOf("Google Sans Flex" to FontFamily.Serif)))
    registered.advanceOf("Total", TextStyle(fontFamily = "Google Sans Flex", fontSize = 11.0))
    assertEquals(emptySet(), registered.unresolvedFontFamilies)
  }
}
