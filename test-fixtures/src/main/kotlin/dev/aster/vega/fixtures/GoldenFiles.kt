package dev.aster.vega.fixtures

import java.io.File

/**
 * Golden-file comparison for canonical scene snapshots and SVG output.
 *
 * Goldens are never rewritten during a normal test run (PROJECT_BRIEF.md 18.3). Regeneration is
 * opt-in through the `vega.updateGoldens` system property, which the root build only sets when
 * `-PupdateGoldens=true` is passed:
 * ```
 * ./gradlew test -PupdateGoldens=true --rerun-tasks
 * ```
 *
 * The resulting diff must be reviewed as a rendering change.
 */
public object GoldenFiles {

  private const val UPDATE_PROPERTY = "vega.updateGoldens"

  public val updateEnabled: Boolean
    get() = System.getProperty(UPDATE_PROPERTY, "false").equals("true", ignoreCase = true)

  /**
   * Compares [actual] against the golden at `src/test/goldens/<name>` relative to
   * [moduleDirectory].
   *
   * @throws AssertionError when the content differs, or when the golden is missing and updating is
   *   disabled.
   */
  public fun assertMatches(
    name: String,
    actual: String,
    moduleDirectory: File = File(System.getProperty("user.dir")),
  ) {
    val golden = File(moduleDirectory, "src/test/goldens/$name")
    val normalizedActual = normalize(actual)

    if (!golden.exists()) {
      if (updateEnabled) {
        golden.parentFile.mkdirs()
        golden.writeText(normalizedActual)
        return
      }
      throw AssertionError(
        "Missing golden '$name' at ${golden.path}.\n" +
          "Create it with: ./gradlew test -PupdateGoldens=true --rerun-tasks\n" +
          "--- actual ---\n$normalizedActual"
      )
    }

    val expected = normalize(golden.readText())
    if (expected == normalizedActual) return

    if (updateEnabled) {
      golden.writeText(normalizedActual)
      return
    }

    throw AssertionError(
      "Golden '$name' does not match.\n" +
        "Review this as a rendering change, then run:\n" +
        "  ./gradlew test -PupdateGoldens=true --rerun-tasks\n\n" +
        firstDifference(expected, normalizedActual)
    )
  }

  /** Normalizes line endings and trailing whitespace so goldens are platform-independent. */
  public fun normalize(text: String): String =
    text.replace("\r\n", "\n").replace("\r", "\n").trimEnd() + "\n"

  private fun firstDifference(expected: String, actual: String): String {
    val expectedLines = expected.lines()
    val actualLines = actual.lines()
    val limit = minOf(expectedLines.size, actualLines.size)
    for (i in 0 until limit) {
      if (expectedLines[i] != actualLines[i]) {
        return buildString {
          append("First difference at line ").append(i + 1).append(":\n")
          append("  expected: ").append(expectedLines[i]).append('\n')
          append("  actual:   ").append(actualLines[i]).append('\n')
          append(context(expectedLines, actualLines, i))
        }
      }
    }
    return "Line counts differ: expected ${expectedLines.size}, actual ${actualLines.size}\n" +
      context(expectedLines, actualLines, limit)
  }

  private fun context(expected: List<String>, actual: List<String>, around: Int): String {
    val from = (around - 3).coerceAtLeast(0)
    val to = (around + 4)
    return buildString {
      append("--- expected ---\n")
      expected.subList(from.coerceAtMost(expected.size), to.coerceAtMost(expected.size)).forEach {
        append(it).append('\n')
      }
      append("--- actual ---\n")
      actual.subList(from.coerceAtMost(actual.size), to.coerceAtMost(actual.size)).forEach {
        append(it).append('\n')
      }
    }
  }
}
