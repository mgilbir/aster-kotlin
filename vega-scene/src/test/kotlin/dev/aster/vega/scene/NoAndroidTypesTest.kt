package dev.aster.vega.scene

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guards the core's platform independence.
 *
 * The specification models, expressions, dataflow, transforms, scales, signals, scene graph,
 * geometry, hit-test index, SVG generation and snapshot serialization must be plain Kotlin/JVM with
 * no Android imports (PROJECT_BRIEF.md 4.2, and a Milestone 1 acceptance criterion). A build-time
 * check is cheap; discovering the leak when someone tries to reuse the core off-Android is not.
 */
class NoAndroidTypesTest {

  private val coreModules =
    listOf(
      "vega-model",
      "vega-expression",
      "vega-dataflow",
      "vega-scene",
      "vega-runtime",
      "vega-svg",
      "test-fixtures",
    )

  @Test
  fun `core modules import no android types`() {
    val repositoryRoot = File(System.getProperty("user.dir")).parentFile
    val offenders = mutableListOf<String>()
    var scannedFiles = 0

    for (module in coreModules) {
      val sourceRoot = File(repositoryRoot, "$module/src")
      assertTrue(sourceRoot.isDirectory, "missing source root: ${sourceRoot.path}")

      sourceRoot
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .forEach { file ->
          scannedFiles++
          file.readLines().forEachIndexed { index, line ->
            val trimmed = line.trim()
            if (
              trimmed.startsWith("import android.") ||
                trimmed.startsWith("import androidx.") ||
                trimmed.startsWith("import dalvik.")
            ) {
              offenders.add("$module/${file.relativeTo(sourceRoot)}:${index + 1}: $trimmed")
            }
          }
        }
    }

    assertTrue(scannedFiles > 0, "scanned no source files; the path resolution is wrong")
    assertTrue(
      offenders.isEmpty(),
      "Android imports found in platform-independent modules:\n${offenders.joinToString("\n")}",
    )
  }

  @Test
  fun `core modules declare no android gradle plugin`() {
    val repositoryRoot = File(System.getProperty("user.dir")).parentFile
    val offenders = coreModules.mapNotNull { module ->
      val buildFile = File(repositoryRoot, "$module/build.gradle.kts")
      val text = buildFile.readText()
      if (text.contains("com.android") || text.contains("plugins.android")) module else null
    }
    assertTrue(
      offenders.isEmpty(),
      "Android Gradle plugin applied to platform-independent modules: $offenders",
    )
  }

  /**
   * A fast first line under the core's portability. **The compiler is the real one.**
   *
   * The core is multiplatform now — `jvm`, `iosArm64`, `iosSimulatorArm64`, `macosArm64` and
   * `linuxX64`, all four native targets compiled by `scripts/check.sh` — so a JVM-only API in
   * common code is a *build failure* rather than something a regular expression has to notice. Keep
   * this test anyway: it fails in a second with the file and line, where a native compile takes a
   * minute and reports the symptom, and it catches an import in a module nobody has added a target
   * to yet.
   *
   * What it cannot see is why the compiler had to be the arbiter. `LinkedHashMap` is common Kotlin,
   * so the two caches that **subclassed** it for its JVM-only access-order mode passed every check
   * here for six milestones. Calendar work goes through `kotlinx-datetime`; rounding goes through
   * `roundHalfUp`, which is also more faithful to d3 than `java.lang.Math.round`.
   *
   * **Nothing is exempt any more.** `Decimals` used to be, on the argument that rounding a decimal
   * at N places needs arbitrary-precision arithmetic; it needs *exactness*, and a finite double is
   * `m × 2^e`, so its decimal expansion is finite and common Kotlin can produce it. Removing the
   * seam also fixed a divergence the seam had introduced — Java's `%e` rounds the shortest
   * printable form rather than the exact value — so the list below is the whole rule, with no
   * asterisk.
   */
  @Test
  fun `core modules use no JVM-only APIs outside the one platform seam`() {
    // Empty, and meant to stay that way: a JVM-only API in the core is a portability bug, not a
    // documented exception. `Decimals` was the last entry here.
    val allowed = emptySet<String>()
    val banned =
      listOf(
        Regex("""\bjava\.(util|math|text|time)\."""),
        Regex("""\bMath\.[a-z]"""),
        Regex("""\bString\.format\b"""),
      )

    val repositoryRoot = File(System.getProperty("user.dir")).parentFile
    val offenders = mutableListOf<String>()
    for (module in coreModules) {
      File(repositoryRoot, "$module/src/main")
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .forEach { file ->
          val relative = "$module/${file.relativeTo(File(repositoryRoot, "$module/src"))}"
          if (relative in allowed) return@forEach
          file.readLines().forEachIndexed { index, line ->
            // A comment naming an API is documentation, not a use of it.
            val trimmed = line.trim()
            if (trimmed.startsWith("*") || trimmed.startsWith("/*")) return@forEachIndexed
            val code = line.substringBefore("//")
            if (banned.any { it.containsMatchIn(code) }) {
              offenders.add("$relative:${index + 1}: ${line.trim()}")
            }
          }
        }
    }
    assertTrue(
      offenders.isEmpty(),
      "JVM-only APIs in the core; use kotlinx-datetime or a portable helper instead:\n" +
        offenders.joinToString("\n"),
    )
  }
}
