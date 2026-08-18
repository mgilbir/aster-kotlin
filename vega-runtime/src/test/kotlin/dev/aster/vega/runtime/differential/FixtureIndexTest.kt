package dev.aster.vega.runtime.differential

import dev.aster.vega.fixtures.GoldenFiles
import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asString
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `test-fixtures/INDEX.md`, derived from the corpus rather than written by hand.
 *
 * The index it replaces was a table of 137 rows in `STATUS.md` beside a directory of 191 fixtures,
 * and the two disagreed with each other and with a second count further down the same file. A
 * hand-maintained list of what is on disk is a list that will be wrong: nothing fails when a
 * fixture is added, so nothing makes anyone add the row. So the table is generated from the
 * specifications and their upstream references, and this test is what fails when the file has
 * drifted:
 * ```
 * ./gradlew :vega-runtime:jvmTest -PupdateGoldens=true --rerun-tasks
 * ```
 *
 * Every column is read off disk. The mark count and the mark types come from the **upstream**
 * reference rather than from this engine's own scene, so the numbers in the index are upstream's
 * answer and not this port's opinion of it.
 */
class FixtureIndexTest {

  @Test
  fun `the fixture index matches the corpus`() {
    val actual = buildIndex()
    val committed = File(repositoryRoot, "test-fixtures/INDEX.md")

    if (GoldenFiles.updateEnabled) {
      committed.writeText(GoldenFiles.normalize(actual))
      return
    }

    assertEquals(
      GoldenFiles.normalize(committed.takeIf { it.isFile }?.readText() ?: ""),
      GoldenFiles.normalize(actual),
      "test-fixtures/INDEX.md is stale. Regenerate it with " +
        "./gradlew :vega-runtime:jvmTest -PupdateGoldens=true --rerun-tasks",
    )
  }

  @Test
  fun `every vega fixture has an upstream reference`() {
    val orphans =
      referenceFiles()
        .map { it.name.removeSuffix(".reference.json") }
        .filter { !File(repositoryRoot, "test-fixtures/specs/$it.vg.json").isFile }
    assertEquals(
      emptyList<String>(),
      orphans,
      "references with no fixture: a renamed or deleted fixture left its reference behind",
    )
  }

  private fun buildIndex(): String {
    val vega = specFiles().map { row(it) }
    val vegaLite = vegaLiteFiles().map { vegaLiteRow(it) }

    return buildString {
      appendLine("# Fixture index")
      appendLine()
      appendLine(
        "Generated from the corpus by `FixtureIndexTest`, which fails when this file has drifted:"
      )
      appendLine()
      appendLine("```sh")
      appendLine("./gradlew :vega-runtime:jvmTest -PupdateGoldens=true --rerun-tasks")
      appendLine("```")
      appendLine()
      appendLine("${vega.size} Vega differential fixtures and ${vegaLite.size} Vega-Lite fixtures.")
      appendLine()
      appendLine(
        "Every column is read off disk. A Vega fixture's mark count and mark types come from its"
      )
      appendLine(
        "**upstream** reference, so they are upstream's answer rather than this port's opinion of it."
      )
      appendLine()
      appendLine("## Vega")
      appendLine()
      appendLine("| Fixture | Marks | Mark types | Transforms | Scales |")
      appendLine("| --- | --- | --- | --- | --- |")
      vega.forEach { appendLine(it) }
      appendLine()
      appendLine("## Vega-Lite")
      appendLine()
      appendLine("| Fixture | Composition | Marks | Transforms |")
      appendLine("| --- | --- | --- | --- |")
      vegaLite.forEach { appendLine(it) }
    }
  }

  private fun row(spec: File): String {
    val name = spec.name.removeSuffix(".vg.json")
    val parsed = VegaJson.parse(spec.readText())
    val reference = File(repositoryRoot, "test-fixtures/reference/$name.reference.json")
    val marks =
      (VegaJson.parse(reference.readText()) as VegaValue.Obj).fields["marks"] as? VegaValue.Arr
    val markTypes =
      marks?.values?.mapNotNull { (it as? VegaValue.Obj)?.fields?.get("type")?.asString() }
        ?: emptyList()
    return "| `$name` | ${marks?.values?.size ?: 0} | ${list(markTypes)} | " +
      "${list(typesUnder(parsed, "transform"))} | ${list(scaleTypes(parsed))} |"
  }

  private fun vegaLiteRow(spec: File): String {
    val name = spec.name.removeSuffix(".vl.json")
    val parsed = VegaJson.parse(spec.readText())
    val composition =
      COMPOSITION.filter { keysUnder(parsed).contains(it) }.ifEmpty { listOf("single view") }
    return "| `$name` | ${list(composition)} | ${list(markTypes(parsed))} | " +
      "${list(typesUnder(parsed, "transform"))} |"
  }

  /** A cell, alphabetical and deduplicated; an empty one reads as an em dash rather than blank. */
  private fun list(values: Collection<String>): String =
    values.distinct().sorted().joinToString(", ").ifEmpty { "—" }

  /** The `type` of every object in an array under [key], anywhere in the document. */
  private fun typesUnder(value: VegaValue, key: String): List<String> = buildList {
    walk(value) { obj ->
      (obj.fields[key] as? VegaValue.Arr)?.values?.forEach { entry ->
        (entry as? VegaValue.Obj)?.fields?.get("type")?.let { add(it.asString()) }
      }
    }
  }

  /**
   * Every scale's type, anywhere in the document, including inside a group's own `scales`.
   *
   * A scale that names no type is `linear`, which is Vega's default rather than an omission — so it
   * is written out, the alternative being a fixture that looks as though it has no scale at all.
   */
  private fun scaleTypes(value: VegaValue): List<String> = buildList {
    walk(value) { obj ->
      (obj.fields["scales"] as? VegaValue.Arr)?.values?.forEach { entry ->
        (entry as? VegaValue.Obj)?.let { add(it.fields["type"]?.asString() ?: "linear") }
      }
    }
  }

  /** Vega-Lite's `mark`, as a name or as a definition, at any depth of composition. */
  private fun markTypes(value: VegaValue): List<String> = buildList {
    walk(value) { obj ->
      when (val mark = obj.fields["mark"]) {
        is VegaValue.Str -> add(mark.value)
        is VegaValue.Obj -> mark.fields["type"]?.let { add(it.asString()) }
        else -> Unit
      }
    }
  }

  private fun keysUnder(value: VegaValue): Set<String> = buildSet {
    walk(value) { obj -> addAll(obj.fields.keys) }
  }

  private fun walk(value: VegaValue, visit: (VegaValue.Obj) -> Unit) {
    when (value) {
      is VegaValue.Obj -> {
        visit(value)
        value.fields.values.forEach { walk(it, visit) }
      }
      is VegaValue.Arr -> value.values.forEach { walk(it, visit) }
      else -> Unit
    }
  }

  private fun specFiles(): List<File> = sortedJson("test-fixtures/specs", ".vg.json")

  private fun vegaLiteFiles(): List<File> = sortedJson("test-fixtures/vega-lite", ".vl.json")

  private fun referenceFiles(): List<File> =
    sortedJson("test-fixtures/reference", ".reference.json")

  private fun sortedJson(directory: String, suffix: String): List<File> =
    requireNotNull(File(repositoryRoot, directory).listFiles()) { "no directory: $directory" }
      .filter { it.name.endsWith(suffix) }
      .sortedBy { it.name }

  private companion object {
    val repositoryRoot: File = File(System.getProperty("user.dir")).parentFile

    /**
     * The keys a Vega-Lite specification composes views under, in the order upstream lists them.
     */
    val COMPOSITION = listOf("layer", "facet", "concat", "hconcat", "vconcat", "repeat")
  }
}
