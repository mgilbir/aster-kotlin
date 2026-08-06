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
   * Guards the core's portability to Kotlin Multiplatform.
   *
   * The core is plain Kotlin/JVM today but is meant to move to KMP unchanged, so it must not reach
   * for JVM-only APIs. Calendar work goes through `kotlinx-datetime`; rounding goes through
   * `roundHalfUp`, which is also more faithful to d3 than `java.lang.Math.round`.
   *
   * One file is exempt, [dev.aster.vega.model.PlatformDecimals], and it explains itself: rounding a
   * decimal at N places has to round the double's exact binary value, which needs
   * arbitrary-precision arithmetic common Kotlin does not have. Confining it to one file is what
   * makes the eventual `expect`/`actual` split mechanical. Anything else fails here.
   */
  @Test
  fun `core modules use no JVM-only APIs outside the one platform seam`() {
    // The single permitted exception, and the file's own documentation says why: rounding a decimal
    // at
    // N places has to round the double's exact binary value, which needs arbitrary-precision
    // arithmetic that common Kotlin does not have. It becomes the `expect` when the core goes
    // multiplatform.
    val allowed = setOf("vega-model/main/kotlin/dev/aster/vega/model/PlatformDecimals.kt")
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
