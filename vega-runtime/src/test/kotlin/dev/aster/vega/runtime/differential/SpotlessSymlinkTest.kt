package dev.aster.vega.runtime.differential

import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * The Spotless target stays anchored, and does not name an exclusion.
 *
 * This pins an idiom rather than a behaviour, for the same reason `CallShapeTests` pins a Swift
 * trailing closure: the fault is invisible in the artefact it damages. An exclusion covering every
 * `build` directory reads as a harmless guard and is the opposite of one — Spotless implements an
 * exclusion as a `SubtractingFileCollection`, which must *enumerate* what it subtracts, so the
 * pattern makes Gradle walk the very directory it was written to avoid.
 *
 * That walk is fatal on macOS and nowhere else. A linked framework is a versioned bundle
 * (`AsterVega.framework/AsterVega -> Versions/Current/AsterVega`), `./gradlew build` links it and
 * runs Spotless at the same time, and a walk arriving mid-link finds a symlink whose target does
 * not exist yet: "Couldn't follow symbolic link", and the build fails. The 0.2.0 release died of
 * exactly this, twenty-seven minutes into the publish job, on the one host that compiles Apple
 * targets and in the one workflow that runs `build` rather than `check.sh`.
 *
 * The target is relative to the project directory and anchored at `src`, so nothing under `build`
 * can match it and the exclusion was never doing anything. Reproduce the old failure by putting a
 * dangling symlink under any module's `build` directory and running `./gradlew
 * :vega-runtime:spotlessKotlin`.
 */
class SpotlessSymlinkTest {

  @Test
  fun `the Spotless configuration names no exclusion`() {
    val build = File(repositoryRoot, "build.gradle.kts")
    val offending =
      build
        .readLines()
        .withIndex()
        .filter { (_, line) -> line.trimStart().startsWith("targetExclude(") }
        .map { (index, line) -> "build.gradle.kts:${index + 1}: ${line.trim()}" }

    assertFalse(
      offending.isNotEmpty(),
      "Spotless must not name an exclusion — it enumerates what it subtracts, which walks " +
        "`build/` and fails on a framework's symlinks during `./gradlew build` on macOS. " +
        "Narrow the `target(...)` instead. Found:\n${offending.joinToString("\n")}",
    )
  }

  private companion object {
    val repositoryRoot: File = File(System.getProperty("user.dir")).parentFile
  }
}
