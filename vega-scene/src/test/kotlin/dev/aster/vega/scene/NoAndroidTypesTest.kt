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
}
