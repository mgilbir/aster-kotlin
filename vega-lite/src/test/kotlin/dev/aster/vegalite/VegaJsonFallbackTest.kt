package dev.aster.vegalite

import java.io.File
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Nothing shipped, and nothing taught, elides a null `vegaJson`.
 *
 * `VegaLiteConversion.vegaJson` is nullable, and the nullability invites exactly one completion —
 * `?: text` — which is wrong in every case it fires. A null means the input **was** Vega-Lite and
 * compiling it produced nothing; text that is not JSON, and JSON that is not Vega-Lite, comes back
 * unchanged with `wasVegaLite` false. So the elvis only ever runs on a document a Vega parser
 * cannot read, and what it produces is a complaint in the wrong grammar or an empty chart, with the
 * conversion's own diagnostic buried underneath.
 *
 * That defect was fixed in `ChartSession` on the Swift side and then went on being **taught** by
 * this repository's own README and **done** by its own demo, in both of the demo's conversion
 * paths, which is the reason this is a test and not a comment. An adopter copies what the README
 * shows and what the demo does.
 *
 * The rule is therefore absolute and easy to check: branch on it, never elide it. A test source may
 * write `requireNotNull(...)`, which asserts rather than substitutes, and is why only main sources
 * and the prose are scanned.
 */
class VegaJsonFallbackTest {

  @Test
  fun `no main source or document elides a null vegaJson`() {
    val offenders =
      scanned().flatMap { file ->
        file
          .readLines()
          .withIndex()
          .filter { (_, line) -> ELISION.containsMatchIn(line) }
          .map { (index, line) ->
            "${file.relativeTo(repositoryRoot).path}:${index + 1}: ${line.trim()}"
          }
      }

    assertTrue(
      offenders.isEmpty(),
      "a null `vegaJson` means the input was Vega-Lite and compiled to nothing, so falling back " +
        "to the source text hands a Vega parser a document it cannot read. Branch on it and " +
        "surface `converted.diagnostics` instead. Found:\n${offenders.joinToString("\n")}",
    )
  }

  @Test
  fun `a refused document is what the rule is about`() {
    // The premise, asserted rather than assumed: a Vega-Lite document that compiles to nothing
    // really does come back with a null `vegaJson` and a diagnostic saying why.
    val dollar = "$"
    val refused = """{"${dollar}schema": "https://vega.github.io/schema/vega-lite/v6.json"}"""
    val converted = VegaLiteInput.toVega(refused)
    assertTrue(converted.wasVegaLite, "should have been taken for Vega-Lite")
    assertNull(converted.vegaJson, "a document with nothing to draw should compile to nothing")
    assertTrue(converted.diagnostics.isNotEmpty(), "and should say why")
  }

  private companion object {
    val repositoryRoot: File = File(System.getProperty("user.dir")).parentFile

    /** `.vegaJson ?:` in Kotlin, `.vegaJson ??` in Swift. */
    val ELISION = Regex("""\.vegaJson\s*(\?:|\?\?)""")

    fun scanned(): List<File> = buildList {
      add(File(repositoryRoot, "README.md"))
      for (module in repositoryRoot.listFiles().orEmpty()) {
        val main = File(module, "src/main")
        if (main.isDirectory) addAll(main.walkTopDown().filter { it.extension == "kt" })
      }
      val swift = File(repositoryRoot, "swift")
      if (swift.isDirectory) {
        addAll(swift.walkTopDown().filter { it.extension == "swift" && "/Tests/" !in it.path })
      }
    }
      .filter { it.isFile }
  }
}
