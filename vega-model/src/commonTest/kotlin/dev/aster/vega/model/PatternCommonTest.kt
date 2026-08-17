package dev.aster.vega.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A specification's own regular expressions, run on every target.
 *
 * This is the case the whole ECMA-262 swap was about, and a JVM-only test cannot make the point:
 * the question is precisely whether a pattern behaves the same *off* the JVM, where Kotlin's own
 * `Regex` is a different engine again. The four cases below are the measured divergences between
 * `java.util.regex` and JavaScript — two of which used to **throw** here — and they are asserted
 * against upstream's answers, read out of Vega with `oracle-js/src/eval-probe.js`.
 *
 * Running on Kotlin/Native also exercises `io.github.mgilbir:ktecma262`'s own native artifacts,
 * which is worth doing here rather than assuming: this repository is one of its first consumers.
 */
class PatternCommonTest {

  private fun matches(source: String, flags: String, input: String): Boolean =
    VegaValue.Pattern(source, flags).regex.findAll(input).isNotEmpty()

  /** Java's `$` sits before a final line terminator; JavaScript's does not. */
  @Test
  fun `a dollar anchor does not match before a trailing newline`() {
    assertEquals(false, matches("a$", "", "a\n"))
    assertEquals(true, matches("a$", "", "a"))
  }

  /** Annex B: a lone `{` is a literal, and `\a` is an identity escape. Java rejects both. */
  @Test
  fun `annex B syntax is accepted rather than thrown on`() {
    assertEquals(true, matches("x{", "", "x{"))
    assertEquals(true, matches("\\a", "", "a"))
  }

  /** An empty class matches nothing at all — and is a syntax error to `java.util.regex`. */
  @Test
  fun `an empty character class never matches`() {
    assertEquals(false, matches("[]", "", "a"))
    assertEquals(true, matches("[^]", "", "a"))
  }

  /** The flags that used to be accepted and dropped for want of a Kotlin equivalent. */
  @Test
  fun `every flag means what it means in a browser`() {
    assertEquals(true, matches("^b", "m", "a\nb"))
    assertEquals(false, matches("^b", "", "a\nb"))
    assertEquals(true, matches("a.b", "s", "a\nb"))
    assertEquals(false, matches("a.b", "", "a\nb"))
    assertEquals(true, matches("A", "i", "a"))
  }

  /** `g` decides replace-once from replace-throughout, and `$` substitution is JavaScript's. */
  @Test
  fun `replacement follows JavaScript's rules`() {
    assertEquals("a+b-c", VegaValue.Pattern("-", "").regex.replace("a-b-c", "+"))
    assertEquals("a+b+c", VegaValue.Pattern("-", "g").regex.replace("a-b-c", "+"))
    assertEquals(
      "Lovelace, Ada",
      VegaValue.Pattern("(\\w+) (\\w+)", "").regex.replace("Ada Lovelace", "$2, $1"),
    )
    assertEquals("price [5]", VegaValue.Pattern("(\\d)", "").regex.replace("price 5", "[$&]"))
  }

  /** The string form is the literal a reader would have written, which `isRegExp` depends on. */
  @Test
  fun `a pattern stringifies as a literal and is truthy`() {
    val pattern = VegaValue.Pattern("a.b", "i")
    assertEquals("/a.b/i", pattern.asString())
    assertTrue(pattern.asBoolean())
    assertTrue(pattern.asDouble().isNaN())
    assertEquals(VegaValue.Pattern("a.b", "i"), pattern)
  }
}
