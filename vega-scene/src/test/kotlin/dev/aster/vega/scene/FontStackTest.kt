package dev.aster.vega.scene

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One reading of a CSS font stack, which three renderers had three of.
 *
 * The reproduction from #123 is the first test: `"Noto Sans, Chart Sans"` with `Chart Sans`
 * registered. The Compose Multiplatform engine found it, the Apple renderer offered only `Noto
 * Sans`, and the Android view offered the whole string as one name. One specification, one
 * registration, three faces.
 */
class FontStackTest {

  @Test
  fun `a stack is every name in it, in order`() {
    assertEquals(listOf("Noto Sans", "Chart Sans"), FontStack.families("Noto Sans, Chart Sans"))
  }

  @Test
  fun `quotes and spaces are not part of a name`() {
    // A specification may quote a family that has spaces in it, and CSS allows either quote.
    assertEquals(
      listOf("Chart Sans", "Noto Sans", "sans-serif"),
      FontStack.families("""  'Chart Sans' ,  "Noto Sans",sans-serif """),
    )
  }

  @Test
  fun `an empty entry is not a name`() {
    // `"A,,B"` and a trailing comma are both things a hand-written specification contains.
    assertEquals(listOf("A", "B"), FontStack.families("A,,B,"))
    assertEquals(emptyList(), FontStack.families("  ,  "))
    assertEquals(emptyList(), FontStack.families(""))
  }

  @Test
  fun `a single name is a stack of one`() {
    assertEquals(listOf("Helvetica Neue"), FontStack.families("Helvetica Neue"))
  }

  @Test
  fun `the generics are the CSS keywords and nothing else`() {
    for (generic in listOf("sans-serif", "serif", "monospace", "cursive", "fantasy", "system-ui")) {
      assertTrue(FontStack.isGeneric(generic), generic)
      assertTrue(FontStack.isGeneric(generic.uppercase()), "case does not matter")
    }
    // Not generics: these name faces, whatever a platform maps them to.
    for (face in listOf("Helvetica", "Arial", "Courier New", "Chart Sans")) {
      assertFalse(FontStack.isGeneric(face), face)
    }
  }

  @Test
  fun `a generic is a name in the stack like any other`() {
    // The rule the Apple renderer used to break: it stopped at a leading generic and never offered
    // the names after it, so `"sans-serif, Chart Sans"` reached no resolver at all.
    assertEquals(listOf("sans-serif", "Chart Sans"), FontStack.families("sans-serif, Chart Sans"))
  }
}
