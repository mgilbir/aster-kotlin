package dev.aster.vega.model.spec

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.asDouble
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `"width": {"signal": ...}`, at the level the parser settles it.
 *
 * The drawing is pinned by `a-size-written-as-a-signal` and
 * `a-declared-size-outranks-the-reference`, which is where it matters. What a fixture cannot see is
 * the **diagnostic**: a signal reference is a written form upstream accepts without a word, and
 * reading it with `optionalNumber` warned that `'width' must be a number` while returning null
 * either way. A chart drew correctly and complained about itself, which is the kind of thing only a
 * parse-level test catches.
 */
class SizeSignalReferenceTest {

  /** A mark, so the only thing left to say about these is the size. */
  private fun parse(json: String): ParsedSpec = SpecParser().parse(VegaJson.parse(json))

  @Test
  fun `a size written as a signal reference becomes that signal's update expression`() {
    val parsed =
      parse(
        """
        {
          "width": {"signal": "w * 2 + 10"},
          "height": {"signal": "h"},
          "signals": [{"name": "w", "value": 95}, {"name": "h", "value": 70}],
          "marks": [{"type": "rect"}]
        }
        """
      )
    val spec = parsed.spec!!

    assertNull(spec.width, "the property is not a number and does not pretend to be one")
    assertNull(spec.height)
    assertEquals("w * 2 + 10", spec.signals.first { it.name == "width" }.update)
    assertEquals("h", spec.signals.first { it.name == "height" }.update)
    assertTrue(parsed.diagnostics.isEmpty(), "upstream accepts the form silently: $parsed")
  }

  @Test
  fun `a declaration carrying an update overwrites the reference`() {
    val spec =
      parse(
          """
          {
            "width": {"signal": "w"},
            "signals": [{"name": "w", "value": 300}, {"name": "width", "update": "120"}]
          }
          """
        )
        .spec!!
    val width = spec.signals.single { it.name == "width" }

    assertEquals("120", width.update, "extend copies the declaration onto the built-in")
  }

  @Test
  fun `a declaration carrying only a value leaves the reference standing`() {
    val spec =
      parse(
          """
          {
            "width": {"signal": "w"},
            "signals": [{"name": "w", "value": 300}, {"name": "width", "value": 333}]
          }
          """
        )
        .spec!!
    val width = spec.signals.single { it.name == "width" }

    assertEquals("w", width.update, "the built-in's update survives a key the declaration omits")
    assertEquals(333.0, width.value?.asDouble(), "and the declared value is the initial one")
  }

  @Test
  fun `an ordinary number is still an ordinary number`() {
    val parsed = parse("""{"width": 200, "height": 90, "marks": [{"type": "rect"}]}""")
    val spec = parsed.spec!!

    assertEquals(200.0, spec.width)
    assertEquals(90.0, spec.height)
    assertTrue(spec.signals.none { it.name == "width" || it.name == "height" })
    assertTrue(parsed.diagnostics.isEmpty())
  }
}
