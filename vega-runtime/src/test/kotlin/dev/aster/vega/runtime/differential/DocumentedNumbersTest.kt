package dev.aster.vega.runtime.differential

import dev.aster.vega.dataflow.transform.TransformRegistry
import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.spec.MarkType
import dev.aster.vega.model.spec.ScaleType
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The counts the documentation states, checked against what the code and the corpus actually hold.
 *
 * Every number here had drifted at least once, and two of them disagreed with a second copy of
 * themselves in the same file: `STATUS.md` claimed 138 differential fixtures in one place and 188
 * in another beside a directory of 191, and the transform count was one too high everywhere it
 * appeared. A number in prose has nothing holding it to the thing it counts, so each one is written
 * in **one shape** that this test looks for and compares against the source of truth. Rephrasing is
 * fine; dropping the phrase is not, because a count nobody can find is a count nobody checks.
 */
class DocumentedNumbersTest {

  /**
   * Every ADR's **Status** line says the same thing in the file and in the index beside it.
   *
   * `docs/adr/README.md` says to amend a record rather than silently changing behaviour that
   * contradicts it, and nothing checked whether that had been done: 0003 and 0005 each read as
   * plainly accepted while a shipped module contradicted them, for several releases. Nothing here
   * can tell whether a record is *true* — that is a reading, not a test — but it can insist that a
   * record which has been superseded or amended says so **in both places**, so a reader scanning
   * the index cannot be told one thing and the file another.
   */
  @Test
  fun `an amended or superseded ADR says so in its file and in the index`() {
    val directory = File(repositoryRoot, "docs/adr")
    val index = File(directory, "README.md").readText()
    val records =
      directory.listFiles().orEmpty().filter { it.name.matches(Regex("""\d{4}-.*\.md""")) }
    assertTrue(records.size >= 8, "expected the ADR directory to hold the records; found $records")

    for (record in records.sortedBy { it.name }) {
      val status =
        Regex("""(?m)^Status: (.*)$""").find(record.readText())?.groupValues?.get(1).orEmpty()
      assertTrue(status.isNotBlank(), "${record.name} has no Status line")
      val row =
        index.lines().firstOrNull { it.contains("(${record.name})") }
          ?: throw AssertionError("${record.name} is not in docs/adr/README.md's table")
      for (word in listOf("superseded", "amended")) {
        if (!status.lowercase().contains(word)) continue
        assertTrue(
          row.lowercase().contains(word),
          "${record.name} is $word in its Status line and the index says nothing: $row",
        )
      }
    }
  }

  @Test
  fun `the documented transform count is the registry's size`() {
    assertCount(
      Regex("""(\d+) of upstream's 51 documented data transforms"""),
      TransformRegistry.Default.types.size,
    )
  }

  @Test
  fun `the documented mark and scale type counts are the enums' sizes`() {
    assertCount(Regex("""all (\d+) of Vega's mark types"""), MarkType.entries.size)
    assertCount(Regex("""the (\d+) scale types it models"""), ScaleType.entries.size)
  }

  @Test
  fun `the documented fixture counts are what is on disk`() {
    assertCount(
      Regex("""(\d+) Vega differential fixtures"""),
      countFiles("test-fixtures/specs", ".vg.json"),
    )
    assertCount(
      Regex("""(\d+) Vega-Lite fixtures"""),
      countFiles("test-fixtures/vega-lite", ".vl.json"),
    )
  }

  @Test
  fun `the annotated fixture table names fixtures that exist, with upstream's mark counts`() {
    val rows = Regex("""^\| `([a-z0-9-]+)` \| (\d+) \| """, RegexOption.MULTILINE)
    val missing = mutableListOf<String>()
    val wrongCount = mutableListOf<String>()

    val status = File(repositoryRoot, "STATUS.md").readText()
    for (row in rows.findAll(status)) {
      val (name, stated) = row.destructured
      val reference = File(repositoryRoot, "test-fixtures/reference/$name.reference.json")
      if (!reference.isFile) {
        missing += name
        continue
      }
      val marks =
        ((VegaJson.parse(reference.readText()) as VegaValue.Obj).fields["marks"] as VegaValue.Arr)
          .values
          .size
      if (marks != stated.toInt()) wrongCount += "$name states $stated, upstream draws $marks"
    }

    assertEquals(emptyList<String>(), missing, "STATUS.md names fixtures that are not on disk")
    assertEquals(emptyList<String>(), wrongCount, "STATUS.md states the wrong mark count")
  }

  /**
   * The accepted-difference count the documentation quotes is the one in the file.
   *
   * This is the number that went stale exactly as the prose beside it did. `known-divergences.json`
   * held eighteen entries once; the ones that were bugs were fixed and their entries deleted, which
   * is the mechanism working. Two rows went on saying "the last **five** are pinned" and
   * "**thirteen** signatures pinned" for as long as nobody counted — a claim about a file that is
   * *in the repository*, checkable in a second, and unchecked for months.
   *
   * The phrase is required to exist, like every other documented number here: a count nobody can
   * find is a count nobody checks.
   */
  @Test
  fun `the documented divergence count is what the file holds`() {
    val file = File(repositoryRoot, "test-fixtures/upstream-vectors/known-divergences.json")
    assertTrue(file.isFile, "missing ${file.path}")
    val entries =
      (VegaJson.parse(file.readText()) as VegaValue.Obj).fields["divergences"] as VegaValue.Arr
    assertCount(Regex("""(\d+) accepted differences? from upstream"""), entries.values.size)
  }

  /**
   * Asserts every occurrence of [pattern] across the documentation names [expected], and that there
   * is at least one.
   */
  private fun assertCount(pattern: Regex, expected: Int) {
    var found = 0
    for (name in DOCUMENTS) {
      val file = File(repositoryRoot, name)
      assertTrue(file.isFile, "missing documentation file: $name")
      // **The whole text, with runs of whitespace flattened — not line by line.**
      //
      // A line-based scan silently misses any claim that wraps, and this repository hard-wraps its
      // prose at about a hundred columns, so wrapping is the normal case rather than the exception.
      // `README.md` ended a line with "193" and began the next with "Vega differential fixtures",
      // and the gate that exists to catch exactly that number being wrong never saw it: the corpus
      // had grown to 195 two fixtures earlier. Three other claims were invisible the same way.
      //
      // The offset is reported instead of a line number, which is the cost of flattening and is
      // worth it: a claim nobody checks is worse than a claim whose location takes a grep.
      val flattened = file.readText().replace(Regex("""\s+"""), " ")
      pattern.findAll(flattened).forEach { match ->
        found++
        assertEquals(
          expected,
          match.groupValues[1].toInt(),
          "$name states \"${match.value}\" at offset ${match.range.first}",
        )
      }
    }
    assertTrue(
      found > 0,
      "no documentation states \"${pattern.pattern}\"; a count nobody can find is a count " +
        "nobody checks, so keep the phrase or update this test",
    )
  }

  private fun countFiles(directory: String, suffix: String): Int =
    requireNotNull(File(repositoryRoot, directory).listFiles()) { "no directory: $directory" }
      .count { it.name.endsWith(suffix) }

  private companion object {
    val repositoryRoot: File = File(System.getProperty("user.dir")).parentFile

    /** The documents that state numbers about the engine. */
    val DOCUMENTS =
      listOf("README.md", "STATUS.md", "SUPPORTED_FEATURES.md", "test-fixtures/INDEX.md")
  }
}
