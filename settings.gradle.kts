pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    // TEMPORARY, and the only thing blocking this branch: `io.github.mgilbir:ktecma262` 0.1.4 —
    // the engine this core's regular expressions are — is not on Maven Central yet, so it is built
    // from source with `./gradlew publishToMavenLocal`.
    //
    // **Ahead of mavenCentral() on purpose.** Gradle takes the first repository holding a
    // coordinate, and an earlier version published without its native modules would otherwise win
    // over the local one. Delete this block when 0.1.4 is public: the catalogue already names the
    // version to resolve, so nothing else changes.
    mavenLocal()
    google()
    mavenCentral()
  }
}

rootProject.name = "aster-kotlin"

// Platform-independent core. No Android types may appear in these modules.
include(":vega-model")

include(":vega-expression")

include(":vega-dataflow")

include(":vega-scene")

include(":vega-runtime")

include(":vega-svg")

include(":vega-loader")

include(":test-fixtures")

// Android presentation layer.
include(":vega-android-canvas")

include(":vega-compose")

include(":demo")

include(":benchmark")
